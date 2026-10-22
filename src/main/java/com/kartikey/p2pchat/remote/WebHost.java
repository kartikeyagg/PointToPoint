package com.kartikey.p2pchat.remote;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Browser access to this computer's screen (for an iPad or any device without Java). Serves one
 * page, a JPEG of the current screen and an input endpoint, all behind the passphrase.
 *
 * <p>Input lines (one per line, coordinates as fractions 0..1 of the screen):
 * {@code m x y}, {@code d x y button}, {@code u x y button}, {@code w x y rotation},
 * {@code k name down(1|0)} and {@code t text}.
 */
public final class WebHost implements AutoCloseable {
    private static final int MAX_WIDTH = 1600;
    private static final long FAILURE_DELAY_MS = 750;
    private static final Map<String, Integer> NAMED_KEYS = Map.ofEntries(
            Map.entry("Enter", KeyEvent.VK_ENTER), Map.entry("Backspace", KeyEvent.VK_BACK_SPACE),
            Map.entry("Tab", KeyEvent.VK_TAB), Map.entry("Escape", KeyEvent.VK_ESCAPE),
            Map.entry("ArrowLeft", KeyEvent.VK_LEFT), Map.entry("ArrowRight", KeyEvent.VK_RIGHT),
            Map.entry("ArrowUp", KeyEvent.VK_UP), Map.entry("ArrowDown", KeyEvent.VK_DOWN),
            Map.entry("Delete", KeyEvent.VK_DELETE), Map.entry("Home", KeyEvent.VK_HOME),
            Map.entry("End", KeyEvent.VK_END), Map.entry("PageUp", KeyEvent.VK_PAGE_UP),
            Map.entry("PageDown", KeyEvent.VK_PAGE_DOWN), Map.entry("Shift", KeyEvent.VK_SHIFT),
            Map.entry("Control", KeyEvent.VK_CONTROL), Map.entry("Alt", KeyEvent.VK_ALT),
            Map.entry("Meta", KeyEvent.VK_META), Map.entry(" ", KeyEvent.VK_SPACE));
    private static final String SHIFTED = "~!@#$%^&*()_+{}|:\"<>?";
    private static final String UNSHIFTED = "`1234567890-=[]\\;',./";

    private final HttpServer server;
    private final ScreenSource screen;
    private final InputSink input;
    private final byte[] secret;
    private volatile boolean controlAllowed;

    public WebHost(int port, String bindAddress, String passphrase, ScreenSource screen, InputSink input,
                   boolean controlAllowed) throws IOException {
        if (passphrase == null || passphrase.length() < 6) {
            throw new IllegalArgumentException("the web page needs a passphrase of at least 6 characters (--key)");
        }
        this.screen = screen;
        this.input = input;
        this.secret = passphrase.getBytes(StandardCharsets.UTF_8);
        this.controlAllowed = controlAllowed;
        server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        server.createContext("/", this::page);
        server.createContext("/frame", this::frame);
        server.createContext("/input", this::input);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "web-host");
            t.setDaemon(true);
            return t;
        }));
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void setControlAllowed(boolean allowed) {
        controlAllowed = allowed;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void page(HttpExchange ex) throws IOException {
        if (!"/".equals(ex.getRequestURI().getPath())) {
            send(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        send(ex, 200, "text/html; charset=utf-8", WebPage.HTML.getBytes(StandardCharsets.UTF_8));
    }

    private void frame(HttpExchange ex) throws IOException {
        if (!authorized(ex)) {
            send(ex, 401, "text/plain", "wrong passphrase".getBytes(StandardCharsets.UTF_8));
            return;
        }
        BufferedImage image = screen.capture();
        if (image.getWidth() > MAX_WIDTH) {
            int h = Math.max(1, image.getHeight() * MAX_WIDTH / image.getWidth());
            BufferedImage scaled = new BufferedImage(MAX_WIDTH, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(image, 0, 0, MAX_WIDTH, h, null);
            } finally {
                g.dispose();
            }
            image = scaled;
        }
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.getResponseHeaders().add("X-Control", controlAllowed ? "1" : "0");
        send(ex, 200, "image/jpeg", jpeg(image, 0.6f));
    }

    private void input(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod()) || !authorized(ex)) {
            send(ex, 401, "text/plain", "wrong passphrase".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String body;
        try (InputStream in = ex.getRequestBody()) {
            body = new String(in.readNBytes(64 * 1024), StandardCharsets.UTF_8);
        }
        if (controlAllowed) {
            for (String line : body.split("\n")) {
                try {
                    apply(line.strip());
                } catch (RuntimeException ignored) {
                    // malformed line: skip it
                }
            }
        }
        send(ex, 204, "text/plain", new byte[0]);
    }

    /** Applies one input line; package-private for tests. */
    void apply(String line) {
        if (line.isEmpty()) {
            return;
        }
        String[] p = line.split(" ", 3);
        switch (p[0]) {
            case "m" -> {
                int[] xy = pixels(p[1], p[2]);
                input.mouseMove(xy[0], xy[1]);
            }
            case "d", "u" -> {
                String[] rest = p[2].split(" ");
                int[] xy = pixels(p[1], rest[0]);
                input.mouseButton(xy[0], xy[1], button(Integer.parseInt(rest[1])), p[0].equals("d"));
            }
            case "w" -> {
                String[] rest = p[2].split(" ");
                int[] xy = pixels(p[1], rest[0]);
                input.mouseWheel(xy[0], xy[1], Integer.parseInt(rest[1]));
            }
            case "k" -> {
                Integer code = NAMED_KEYS.get(p[1]);
                if (code != null) {
                    input.key(code, p[2].equals("1"));
                }
            }
            case "t" -> type(line.substring(2));
            default -> { }
        }
    }

    private static int button(int domButton) {
        return switch (domButton) {
            case 1 -> RemoteProtocol.BUTTON_MIDDLE;
            case 2 -> RemoteProtocol.BUTTON_RIGHT;
            default -> RemoteProtocol.BUTTON_LEFT;
        };
    }

    private int[] pixels(String fx, String fy) {
        BufferedImage size = screen.capture();
        double x = Math.min(1, Math.max(0, Double.parseDouble(fx)));
        double y = Math.min(1, Math.max(0, Double.parseDouble(fy)));
        return new int[] {(int) Math.round(x * (size.getWidth() - 1)), (int) Math.round(y * (size.getHeight() - 1))};
    }

    private void type(String text) {
        for (char c : text.toCharArray()) {
            int shiftedAt = SHIFTED.indexOf(c);
            boolean shift = Character.isUpperCase(c) || shiftedAt >= 0;
            int code = KeyEvent.getExtendedKeyCodeForChar(shiftedAt >= 0 ? UNSHIFTED.charAt(shiftedAt) : Character.toLowerCase(c));
            if (code == KeyEvent.VK_UNDEFINED) {
                continue;
            }
            if (shift) {
                input.key(KeyEvent.VK_SHIFT, true);
            }
            input.key(code, true);
            input.key(code, false);
            if (shift) {
                input.key(KeyEvent.VK_SHIFT, false);
            }
        }
    }

    private boolean authorized(HttpExchange ex) {
        String supplied = ex.getRequestHeaders().getFirst("X-Key");
        boolean ok = supplied != null && MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8), secret);
        if (!ok) {
            try {
                Thread.sleep(FAILURE_DELAY_MS); // slows down passphrase guessing
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return ok;
    }

    private static byte[] jpeg(BufferedImage image, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, status == 204 ? -1 : body.length);
        if (status != 204) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
        ex.close();
    }
}
