package com.kartikey.p2pchat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** Terminal chat: each line typed is sent; "/quit" exits. */
public final class ConsoleChat {

    private ConsoleChat() {}

    public static int run(Options o) throws Exception {
        if (o.peerHost == null) {
            System.err.println("error: --peer is required in terminal mode");
            System.err.println(Options.usage());
            return 2;
        }
        PeerLink.Listener listener = new PeerLink.Listener() {
            @Override
            public void onStatus(PeerLink.Status status, String detail) {
                System.out.println("* " + status + ": " + detail);
            }

            @Override
            public void onMessage(String text) {
                System.out.println("peer> " + text);
            }

            @Override
            public void onFailed(long id, String text) {
                System.out.println("* not delivered: " + text);
            }

            @Override
            public void onWarning(String warning) {
                System.out.println("! " + warning);
            }
        };

        InetSocketAddress peer = new InetSocketAddress(o.peerHost, o.peerPort);
        PeerLink link;
        try {
            link = new PeerLink(o.localPort, peer, new PacketCodec(o.passphrase), listener);
        } catch (BindException e) {
            System.err.println("error: UDP port " + o.localPort + " is already in use, try --port <other>");
            return 1;
        }
        try (link) {
            System.out.println("* listening on UDP " + link.localPort() + (link.isEncrypted()
                    ? ", encrypted" : ", NOT encrypted (use --key)") + ". Type messages, /quit to exit.");
            link.start();
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                if (line.equals("/quit")) {
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }
                try {
                    link.send(line);
                } catch (IllegalArgumentException e) {
                    System.out.println("! " + e.getMessage());
                }
            }
        }
        return 0;
    }
}
