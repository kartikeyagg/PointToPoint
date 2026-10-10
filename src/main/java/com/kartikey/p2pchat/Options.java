package com.kartikey.p2pchat;

/** Command-line options shared by the GUI and the console client. */
public final class Options {

    /** Default UDP port used by both peers (7000 + day of year it was introduced). */
    public static final int DEFAULT_PORT = 7283;

    public String peerHost;
    public int peerPort = DEFAULT_PORT;
    public int localPort = DEFAULT_PORT;
    public String passphrase;
    public boolean cli;
    public boolean help;
    /** Look up our public address with STUN when connecting (one-off lookup, no relaying). */
    public boolean stun = true;
    /** Only print our public address and exit. */
    public boolean whoami;

    public static Options parse(String[] args) {
        Options o = new Options();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--cli" -> o.cli = true;
                case "-h", "--help" -> o.help = true;
                case "--peer" -> o.setPeer(value(args, ++i, a));
                case "--no-stun" -> o.stun = false;
                case "--whoami" -> o.whoami = true;
                case "--peer-port" -> o.peerPort = port(value(args, ++i, a));
                case "--port" -> o.localPort = port(value(args, ++i, a));
                case "--key" -> o.passphrase = value(args, ++i, a);
                default -> throw new IllegalArgumentException("unknown option: " + a);
            }
        }
        return o;
    }

    public static String usage() {
        return """
                Usage: java -jar p2p-chat.jar [options]

                  --peer <addr>       the other device's PUBLIC address: 1.2.3.4, 1.2.3.4:5000,
                                      2001:db8::1 or [2001:db8::1]:5000 (required for --cli)
                  --peer-port <n>     the other device's public UDP port (default %1$d)
                  --port <n>          UDP port to listen on here         (default %1$d)
                  --key <passphrase>  shared secret; enables AES-256-GCM encryption
                  --whoami            print this device's public address(es) and exit
                  --no-stun           skip the public-address lookup when connecting
                  --cli               terminal chat instead of the GUI
                  -h, --help          show this help

                Run --whoami first on both devices and exchange the printed ip:port.
                Then start both at roughly the same time, each pointing at the other.
                """.formatted(DEFAULT_PORT);
    }

    /** Accepts host, host:port, IPv6 literal, or [IPv6]:port. */
    public void setPeer(String s) {
        s = s.trim();
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("missing ] in " + s);
            }
            peerHost = s.substring(1, close);
            String rest = s.substring(close + 1);
            if (rest.startsWith(":")) {
                peerPort = port(rest.substring(1));
            } else if (!rest.isEmpty()) {
                throw new IllegalArgumentException("bad peer address: " + s);
            }
        } else if (s.indexOf(':') >= 0 && s.indexOf(':') == s.lastIndexOf(':')) {
            peerHost = s.substring(0, s.indexOf(':'));
            peerPort = port(s.substring(s.indexOf(':') + 1));
        } else {
            peerHost = s; // plain host / IPv4, or bare IPv6 literal
        }
        if (peerHost.isEmpty()) {
            throw new IllegalArgumentException("bad peer address: " + s);
        }
    }

    private static String value(String[] args, int i, String flag) {
        if (i >= args.length) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        return args[i];
    }

    static int port(String s) {
        int p;
        try {
            p = Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a port number: " + s);
        }
        if (p < 0 || p > 65535) {
            throw new IllegalArgumentException("port out of range: " + p);
        }
        return p;
    }
}
