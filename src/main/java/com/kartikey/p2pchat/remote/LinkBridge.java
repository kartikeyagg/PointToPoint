package com.kartikey.p2pchat.remote;

import com.kartikey.p2pchat.PeerLink;
import com.kartikey.p2pchat.StunClient;

import java.util.function.Consumer;

/**
 * Forwards {@link PeerLink} callbacks to whichever side runs in this process. The link has to
 * exist before the host/viewer (they send through it), so the target is set afterwards, before
 * {@link PeerLink#start()}.
 */
final class LinkBridge implements PeerLink.Listener {

    volatile DesktopHost host;
    volatile DesktopViewer viewer;
    /** Status line for the window; replaced once the window exists. */
    volatile Consumer<String> status = s -> {};

    @Override
    public void onStatus(PeerLink.Status s, String detail) {
        status.accept(switch (s) {
            case CONNECTING -> "Connecting: " + detail;
            case CONNECTED -> "Connected: " + detail;
            case DISCONNECTED -> "Disconnected: " + detail;
            case CLOSED -> "Closed";
        });
    }

    @Override
    public void onWarning(String warning) {
        status.accept(warning);
    }

    @Override
    public void onNatReport(StunClient.Report report) {
        status.accept(report.summary());
    }

    @Override
    public void onData(byte[] data) {
        deliver(data);
    }

    @Override
    public void onReliable(byte[] data) {
        deliver(data);
    }

    @Override
    public void onPeerSession() {
        DesktopHost h = host;
        if (h != null) {
            h.onPeerSession();
        }
        DesktopViewer v = viewer;
        if (v != null) {
            v.onPeerSession();
        }
    }

    private void deliver(byte[] data) {
        DesktopHost h = host;
        if (h != null) {
            h.onMessage(data);
        }
        DesktopViewer v = viewer;
        if (v != null) {
            v.onMessage(data);
        }
    }
}
