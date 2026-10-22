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
            return DesktopBench.run();
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
        LinkBridge bridge = new LinkBridge();
        InetSocketAddress peer = new InetSocketAddress(o.peerHost, o.peerPort);
        PeerLink link = new PeerLink(o.localPort, peer, new PacketCodec(o.passphrase), bridge,
                o.stun ? StunClient.DEFAULT_SERVERS : List.of());
        try {
            if (host) {
                startHost(o, screen, link, bridge, peer);
            } else {
                startViewer(link, bridge, peer);
            }
        } catch (Exception e) {
            link.close();
            throw e;
        }
        link.start();
    }

    static HostSettings hostSettings(Options o) {
        HostSettings settings = HostSettings.defaults().withControlAllowed(!o.viewOnly);
        return o.maxBytesPerSecond > 0 ? settings.withMaxBytesPerSecond(o.maxBytesPerSecond) : settings;
    }

    private static void startHost(Options o, RobotScreen screen, PeerLink link, LinkBridge bridge,
                                  InetSocketAddress peer) throws Exception {
        DesktopHost host = new DesktopHost(Transport.of(link), screen, new RobotInput(screen.bounds()),
                hostSettings(o));
        Timer[] stats = new Timer[1];
        Runnable stop = () -> {
            if (stats[0] != null) {
                stats[0].stop();
            }
            host.close();
            link.close();
            System.exit(0);
        };
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

    private static void startViewer(PeerLink link, LinkBridge bridge, InetSocketAddress peer) {
        DesktopViewer viewer = new DesktopViewer(Transport.of(link));
        ViewerWindow window = ViewerWindow.open(viewer, "Remote desktop - " + StunClient.format(peer), () -> {
            viewer.close();
            link.close();
            System.exit(0);
        });
        bridge.status = window::setStatus;
        bridge.viewer = viewer;
    }
}
