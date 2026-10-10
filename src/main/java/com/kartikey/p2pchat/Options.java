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

    public static Options parse(String[] args) {
        Options o = new Options();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--cli" -> o.cli = true;
                case "-h", "--help" -> o.help = true;
                case "--peer" -> o.peerHost = value(args, ++i, a);
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

                  --peer <ip|host>    the other device's public IP (required for --cli)
                  --peer-port <n>     the other device's UDP port   (default %1$d)
                  --port <n>          UDP port to listen on here    (default %1$d)
                  --key <passphrase>  shared secret; enables AES-256-GCM encryption
                  --cli               terminal chat instead of the GUI
                  -h, --help          show this help

                Both devices must start at roughly the same time and point at each other.
                """.formatted(DEFAULT_PORT);
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
