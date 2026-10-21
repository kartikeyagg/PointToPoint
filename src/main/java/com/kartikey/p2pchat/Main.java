package com.kartikey.p2pchat;

import com.kartikey.p2pchat.remote.DesktopApp;

import java.awt.GraphicsEnvironment;

public final class Main {

    private Main() {}

    public static void main(String[] args) throws Exception {
        Options o;
        try {
            o = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println(Options.usage());
            System.exit(2);
            return;
        }
        if (o.help) {
            System.out.println(Options.usage());
            return;
        }
        if (o.whoami) {
            System.exit(ConsoleChat.whoami(o.localPort));
        }
        if (o.desktop != null) {
            int code = DesktopApp.run(o);
            if (code >= 0) {
                System.exit(code);
            }
            return;
        }
        if (o.cli || GraphicsEnvironment.isHeadless()) {
            System.exit(ConsoleChat.run(o));
        } else {
            ChatWindow.open(o);
        }
    }
}
