package com.kartikey.p2pchat.remote;

import com.kartikey.p2pchat.Options;
import com.kartikey.p2pchat.PacketCodec;
import com.kartikey.p2pchat.PeerLink;
import com.kartikey.p2pchat.StunClient;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.GraphicsEnvironment;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * Remote desktop over the same serverless link as the chat: one side shares its screen
 * ({@code --desktop host}), the other watches and controls it ({@code --desktop view}).
 */
public final class DesktopApp {

    private DesktopApp() {}

    /** Entry point for {@code --desktop}; returns a process exit code, or -1 while windows stay open. */
    public static int run(Options o) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("error: remote desktop needs a graphical session (no display found)");
            return 2;
        }
        if ("bench".equals(o.desktop)) {
            return DesktopBench.run(o);
        }
        boolean ready = !o.desktop.isEmpty() && o.peerHost != null && o.passphrase != null && !o.passphrase.isBlank();
        if (!ready) {
            DesktopLauncher.open(o, DesktopApp::start);
            return -1;
        }
        try {
            start(o);
            return -1;
        } catch (BindException e) {
            System.err.println("error: UDP port " + o.localPort + " is already in use, try --port <other>");
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
        }
        return 1;
    }

    /** Connects to the peer and opens the host or viewer window. */
    public static void start(Options o) throws Exception {
        if (o.passphrase == null || o.passphrase.isBlank()) {
            throw new IllegalArgumentException("remote desktop needs a passphrase (--key): it never runs unencrypted");
        }
        if (o.peerHost == null) {
            throw new IllegalArgumentException("--peer is required");
        }
        boolean host = "host".equals(o.desktop);
        // grab the screen before opening the port, so a missing display fails cleanly
        RobotScreen screen = host ? new RobotScreen() : null;
        ScreenSource source = host ? openScreen(o, screen) : null;
        LinkBridge bridge = new LinkBridge();
        InetSocketAddress peer = new InetSocketAddress(o.peerHost, o.peerPort);
        PeerLink link = new PeerLink(o.localPort, peer, new PacketCodec(o.passphrase), bridge,
                o.stun ? StunClient.DEFAULT_SERVERS : List.of());
        try {
            if (host) {
                startHost(o, screen, source, link, bridge, peer);
            } else {
                startViewer(link, bridge, peer);
            }
        } catch (Exception e) {
            link.close();
            throw e;
        }
        link.start();
    }

    /** Frames per second when the native capture helper runs; Robot can't keep up with this. */
    static final int NATIVE_FPS = 30;

    /**
     * The fastest screen source available: the native helper on macOS (ScreenCaptureKit),
     * otherwise {@link java.awt.Robot}. The helper must report the same size as Robot's screen,
     * because input coordinates are host-frame pixels.
     */
    static ScreenSource openScreen(Options o, RobotScreen robot) {
        java.nio.file.Path helper = o.javaCapture ? null : helperPath();
        if (helper == null || !java.nio.file.Files.isExecutable(helper)) {
            return robot;
        }
        try {
            NativeScreen nativeScreen = NativeScreen.start(helper, NATIVE_FPS, robot, 5000);
            java.awt.Dimension size = nativeScreen.size();
            java.awt.Rectangle bounds = robot.bounds();
            if (size.width != bounds.width || size.height != bounds.height) {
                nativeScreen.close();
                System.err.println("native capture is " + size.width + "x" + size.height + " but the screen is "
                        + bounds.width + "x" + bounds.height + "; using Java capture");
                return robot;
            }
            System.out.println("screen capture: native helper (" + helper.getFileName() + ")");
            return nativeScreen;
        } catch (java.io.IOException e) {
            System.err.println("native capture unavailable (" + e.getMessage() + "); using Java capture");
            return robot;
        }
    }

    /** {@code $P2P_SCREENSTREAM}, or {@code screenstream} next to the jar (macOS only). */
    static java.nio.file.Path helperPath() {
        String override = System.getenv("P2P_SCREENSTREAM");
        if (override != null && !override.isBlank()) {
            return java.nio.file.Path.of(override);
        }
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")) {
            return null;
        }
        try {
            java.nio.file.Path jar = java.nio.file.Path.of(
                    DesktopApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return jar.resolveSibling("screenstream");
        } catch (Exception e) {
            return null;
        }
    }

    static HostSettings hostSettings(Options o) {
        return hostSettings(o, false);
    }

    static HostSettings hostSettings(Options o, boolean nativeCapture) {
        HostSettings settings = HostSettings.defaults().withControlAllowed(!o.viewOnly);
        if (nativeCapture) {
            settings = settings.withFps(NATIVE_FPS);
        }
        return o.maxBytesPerSecond > 0 ? settings.withMaxBytesPerSecond(o.maxBytesPerSecond) : settings;
    }

    private static void startHost(Options o, RobotScreen screen, ScreenSource source, PeerLink link,
                                  LinkBridge bridge, InetSocketAddress peer) throws Exception {
        DesktopHost host = new DesktopHost(Transport.of(link), source, new RobotInput(screen.bounds()),
                hostSettings(o, source instanceof NativeScreen));
        Timer[] stats = new Timer[1];
        Runnable stop = () -> {
            if (stats[0] != null) {
                stats[0].stop();
            }
            host.close();
            link.close();
            System.exit(0);
        };
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> System.out.println("remote desktop host exiting"), "host-exit-log"));
        HostWindow window = HostWindow.open(StunClient.format(peer), host.isControlAllowed(),
                host::setControlAllowed, stop);
        bridge.status = window::setStatus;
        bridge.host = host;
        host.start();
        SwingUtilities.invokeLater(() -> {
            stats[0] = new Timer(1000, e -> window.setStats(host.tilesSent(), host.bytesSent()));
            stats[0].start();
        });
    }

    /** Once a second: what arrived, what was drawn, what was lost (P2P_STATS=1). */
    private static void printStats(DesktopViewer viewer) {
        DesktopViewer.Stats[] last = {viewer.stats()};
        Thread thread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                DesktopViewer.Stats now = viewer.stats(), before = last[0];
                last[0] = now;
                long drawn = now.tilesDrawn() - before.tilesDrawn();
                long lost = now.tilesIncomplete() - before.tilesIncomplete();
                System.out.printf("%tT stats: %d KB/s, %d fragments/s, %d tiles drawn/s, %d tiles incomplete/s, decode %.2f ms/tile%n",
                        System.currentTimeMillis(), (now.bytes() - before.bytes()) / 1024,
                        now.fragments() - before.fragments(), drawn, lost,
                        drawn == 0 ? 0.0 : (now.decodeNanos() - before.decodeNanos()) / 1e6 / drawn);
            }
        }, "viewer-stats");
        thread.setDaemon(true);
        thread.start();
    }

    private static void startViewer(PeerLink link, LinkBridge bridge, InetSocketAddress peer) {
        DesktopViewer viewer = DesktopViewer.withRenderThread(Transport.of(link));
        ViewerWindow window = ViewerWindow.open(viewer, "Remote desktop - " + StunClient.format(peer), () -> {
            viewer.close();
            link.close();
            System.exit(0);
        });
        bridge.status = window::setStatus;
        bridge.viewer = viewer;
        if (System.getenv("P2P_STATS") != null) {
            printStats(viewer);
        }
    }
}
