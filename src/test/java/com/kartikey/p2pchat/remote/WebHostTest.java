package com.kartikey.p2pchat.remote;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebHostTest {
    private final List<String> events = new ArrayList<>();
    private final InputSink sink = new InputSink() {
        public void mouseMove(int x, int y) { events.add("move " + x + "," + y); }
        public void mouseButton(int x, int y, int b, boolean down) { events.add((down ? "down " : "up ") + b + " " + x + "," + y); }
        public void mouseWheel(int x, int y, int r) { events.add("wheel " + r); }
        public void key(int code, boolean down) { events.add((down ? "key+" : "key-") + code); }
    };
    private final ScreenSource screen = () -> new BufferedImage(101, 51, BufferedImage.TYPE_INT_RGB);
    private final HttpClient http = HttpClient.newHttpClient();
    private WebHost host;

    @AfterEach
    void stop() {
        if (host != null) {
            host.close();
        }
    }

    private WebHost start(boolean control) throws Exception {
        host = new WebHost(0, "127.0.0.1", "secret1", screen, sink, control);
        host.start();
        return host;
    }

    private HttpResponse<byte[]> call(String method, String path, String key, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + host.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            b.header("X-Key", key);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void rejectsShortPassphrase() {
        assertThrows(IllegalArgumentException.class,
                () -> new WebHost(0, "127.0.0.1", "abc", screen, sink, true));
    }

    @Test
    void frameAndInputNeedThePassphrase() throws Exception {
        start(true);
        assertEquals(401, call("GET", "/frame", null, null).statusCode());
        assertEquals(401, call("GET", "/frame", "wrong", null).statusCode());
        assertEquals(401, call("POST", "/input", "wrong", "m 0.5 0.5").statusCode());
        assertTrue(events.isEmpty());
        HttpResponse<byte[]> ok = call("GET", "/frame", "secret1", null);
        assertEquals(200, ok.statusCode());
        assertArrayEquals(new byte[] {(byte) 0xFF, (byte) 0xD8}, java.util.Arrays.copyOf(ok.body(), 2));
    }

    @Test
    void pageIsServedWithoutPassphrase() throws Exception {
        start(true);
        HttpResponse<byte[]> page = call("GET", "/", null, null);
        assertEquals(200, page.statusCode());
        assertTrue(new String(page.body()).contains("<title>Remote screen</title>"));
        assertEquals(404, call("GET", "/etc/passwd", null, null).statusCode());
    }

    @Test
    void inputIsMappedToScreenPixels() throws Exception {
        start(true);
        assertEquals(204, call("POST", "/input", "secret1",
                "m 0.5 0.5\nd 1 1 0\nu 1 1 2\nw 0 0 3\nk Enter 1\nt Ab").statusCode());
        assertEquals(List.of("move 50,25", "down 1 100,50", "up 3 100,50", "wheel 3",
                "key+" + KeyEvent.VK_ENTER,
                "key+" + KeyEvent.VK_SHIFT, "key+" + KeyEvent.VK_A, "key-" + KeyEvent.VK_A, "key-" + KeyEvent.VK_SHIFT,
                "key+" + KeyEvent.VK_B, "key-" + KeyEvent.VK_B), events);
    }

    @Test
    void viewOnlyIgnoresInputAndOutOfRangeIsClamped() throws Exception {
        start(false);
        assertEquals(204, call("POST", "/input", "secret1", "m 0.5 0.5").statusCode());
        assertTrue(events.isEmpty());
        assertEquals("0", call("GET", "/frame", "secret1", null).headers().firstValue("X-Control").orElse(""));
        host.setControlAllowed(true);
        call("POST", "/input", "secret1", "m 9 -3\ngarbage line\nm x y");
        assertEquals(List.of("move 100,0"), events);
    }
}
