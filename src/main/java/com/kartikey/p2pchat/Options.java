package com.kartikey.p2pchat;

/** Command-line options shared by the GUI and the console client. */
public final class Options {

    /** Default UDP port used by both peers (7000 + day of year it was introduced). */
    public static final int DEFAULT_PORT = 7283;
    /**
     * Default UDP port of the remote desktop service. The 7000 + day-of-year rule gives 7283 again
     * (same day), so it takes the next one to let chat and remote desktop run side by side.
     */
    public static final int DESKTOP_PORT = DEFAULT_PORT + 1;
    /** Default TCP port of the browser page ({@code --web}). */
    public static final int DEFAULT_WEB_PORT = 7285;
    /** Default UDP port of conference mode (7000 + day of year it was introduced). */
    public static final int CONFERENCE_PORT = 7302;

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
    /** Remote desktop mode: "host" (share this screen), "view" (control the peer), "" (launcher), null (chat). */
    public String desktop;
    /** Host only: the viewer may watch but not control. */
    public boolean viewOnly;
    /** Remote desktop host upload cap in bytes per second; 0 = the built-in default. */
    public int maxBytesPerSecond;
    /** Host: use java.awt.Robot even when the native capture helper is available. */
    public boolean javaCapture;
    /** Browser access (iPad etc.): TCP port of the web page, or -1 when off. */
    public int webPort = -1;
    /** Address the web page listens on; loopback unless the user opts in to more. */
    public String webBind = "127.0.0.1";
    /** Conference mode: "host", "join", "" (launcher window), null (not a conference). */
    public String conference;
    /** Conference: meeting title when hosting, invite code when joining. */
    public String conferenceArg;
    /** Conference: display name shown to others. */
    public String name;

    public static Options parse(String[] args) {
        Options o = new Options();
        boolean peerPortSet = false, localPortSet = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--cli" -> o.cli = true;
                case "-h", "--help" -> o.help = true;
                case "--peer" -> {
                    int before = o.peerPort;
                    o.peerPort = -1;
                    o.setPeer(value(args, ++i, a));
                    peerPortSet |= o.peerPort != -1;
                    o.peerPort = o.peerPort == -1 ? before : o.peerPort;
                }
                case "--no-stun" -> o.stun = false;
                case "--whoami" -> o.whoami = true;
                case "--peer-port" -> {
                    o.peerPort = port(value(args, ++i, a));
                    peerPortSet = true;
                }
                case "--port" -> {
                    o.localPort = port(value(args, ++i, a));
                    localPortSet = true;
                }
                case "--desktop" -> {
                    o.desktop = "";
                    if (i + 1 < args.length && (args[i + 1].equals("host") || args[i + 1].equals("view")
                            || args[i + 1].equals("bench"))) {
                        o.desktop = args[++i];
                    }
                }
                case "--view-only" -> o.viewOnly = true;
                case "--java-capture" -> o.javaCapture = true;
                case "--max-rate" -> o.maxBytesPerSecond = rate(value(args, ++i, a));
                case "--web" -> {
                    o.webPort = DEFAULT_WEB_PORT;
                    if (i + 1 < args.length && args[i + 1].matches("\\d+")) {
                        o.webPort = port(args[++i]);
                    }
                }
                case "--web-bind" -> o.webBind = value(args, ++i, a);
                case "--key" -> o.passphrase = value(args, ++i, a);
                case "--conference" -> {
                    o.conference = "";
                    if (i + 1 < args.length && (args[i + 1].equals("host") || args[i + 1].equals("join"))) {
                        o.conference = args[++i];
                        if (o.conference.equals("join")) {
                            o.conferenceArg = value(args, ++i, "--conference join");
                        } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                            o.conferenceArg = args[++i];
                        }
                    }
                }
                case "--name" -> o.name = value(args, ++i, a);
                default -> throw new IllegalArgumentException("unknown option: " + a);
            }
        }
        if (o.conference != null) {
            o.localPort = localPortSet ? o.localPort : CONFERENCE_PORT;
        }
        if (o.desktop != null) {
            o.peerPort = peerPortSet ? o.peerPort : DESKTOP_PORT;
            o.localPort = localPortSet ? o.localPort : DESKTOP_PORT;
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

                Remote desktop (needs --key; default port %2$d):
                  --desktop           open the remote desktop launcher window
                  --desktop host      share this screen with the peer
                  --desktop view      view and control the peer's screen
                  --view-only         (host) let the peer watch but not control
                  --desktop bench     measure how fast this computer can share its screen
                  --java-capture      (host, macOS) don't use the native screen capture helper
                  --max-rate <MB/s>   (host) upload cap for screen updates (default 8; lower it,
                                      e.g. 2, on a slow upload to avoid lost tiles)

                Browser access (iPad, phone; needs --key, no Java needed on the viewing device):
                  --web [port]        serve this screen as a web page (default port %3$d)
                  --web-bind <addr>   address to listen on (default 127.0.0.1; use a Tailscale or
                                      LAN address, or 0.0.0.0, to reach it from another device)
                  --view-only         page can watch but not control

                Conference (many people, no server; default port %4$d):
                  --conference                 open the conference launcher window
                  --conference host [title]    start a meeting and print its invite code
                  --conference join <invite>   join a meeting with an invite code
                  --name <name>                name shown to the others

                Run --whoami first on both devices and exchange the printed ip:port.
                Then start both at roughly the same time, each pointing at the other.
                """.formatted(DEFAULT_PORT, DESKTOP_PORT, DEFAULT_WEB_PORT, CONFERENCE_PORT);
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

    static int rate(String s) {
        final double megabytes;
        try {
            megabytes = Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad --max-rate: " + s);
        }
        if (!(megabytes >= 0.1 && megabytes <= 100)) {
            throw new IllegalArgumentException("--max-rate must be between 0.1 and 100 MB/s: " + s);
        }
        return (int) Math.round(megabytes * 1_000_000);
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
