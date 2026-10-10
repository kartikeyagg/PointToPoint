package com.kartikey.p2pchat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Terminal chat: each line typed is sent; "/quit" exits. */
public final class ConsoleChat {

    private ConsoleChat() {}

    public static int run(Options o) throws Exception {
        if (o.peerHost == null) {
            System.err.println("error: --peer is required in terminal mode");
            System.err.println(Options.usage());
            return 2;
        }
        Map<Long, String> sent = new ConcurrentHashMap<>();
        PeerLink.Listener listener = new PeerLink.Listener() {
            @Override
            public void onStatus(PeerLink.Status status, String detail) {
                print("* " + status + ": " + detail);
            }

            @Override
            public void onMessage(String text) {
                print("peer> " + text);
            }

            @Override
            public void onDelivered(long id) {
                String text = sent.remove(id);
                print("* delivered \u2713 " + (text == null ? "#" + id : text));
            }

            @Override
            public void onFailed(long id, String text) {
                sent.remove(id);
                print("* not delivered: " + text);
            }

            @Override
            public void onWarning(String warning) {
                print("! " + warning);
            }

            @Override
            public void onNatReport(StunClient.Report report) {
                print("* " + report.summary());
            }
        };

        InetSocketAddress peer = new InetSocketAddress(o.peerHost, o.peerPort);
        PeerLink link;
        try {
            link = new PeerLink(o.localPort, peer, new PacketCodec(o.passphrase), listener,
                    o.stun ? StunClient.DEFAULT_SERVERS : List.of());
        } catch (BindException e) {
            System.err.println("error: UDP port " + o.localPort + " is already in use, try --port <other>");
            return 1;
        }
        try (link) {
            print("* listening on UDP " + link.localPort() + (link.isEncrypted()
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
                    sent.put(link.send(line), line);
                } catch (IllegalArgumentException e) {
                    print("! " + e.getMessage());
                }
            }
        }
        return 0;
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Prints a line prefixed with the local time, so logs from two machines can be compared. */
    private static void print(String line) {
        System.out.println("[" + LocalTime.now().format(TIME) + "] " + line);
    }

    /** Prints this device's public IPv4/IPv6 address as seen from the internet. */
    public static int whoami(int localPort) {
        try {
            List<StunClient.Report> reports = StunClient.probeBoth(localPort, StunClient.DEFAULT_SERVERS, 1500);
            System.out.println("IPv4: " + reports.get(0).summary());
            System.out.println("IPv6: " + reports.get(1).summary());
            return 0;
        } catch (BindException e) {
            System.err.println("error: UDP port " + localPort + " is already in use, try --port <other>");
            return 1;
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }
    }
}
