package com.kartikey.p2pchat.remote;

import com.kartikey.p2pchat.Options;

import java.awt.GraphicsEnvironment;

/** Entry point for {@code --web}: shares this screen as a web page for browsers (iPad, phone). */
public final class WebApp {

    private WebApp() {}

    /** Starts the web page; returns an exit code only if it could not start, otherwise keeps running. */
    public static int run(Options o) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("error: the web page needs a graphical session (no display found)");
            return 2;
        }
        try {
            RobotScreen screen = new RobotScreen();
            WebHost host = new WebHost(o.webPort, o.webBind, o.passphrase, screen,
                    new RobotInput(screen.bounds()), !o.viewOnly);
            host.start();
            System.out.println("Screen is served at http://" + o.webBind + ":" + host.port()
                    + "/  (" + (o.viewOnly ? "view only" : "control allowed") + ")");
            if (!o.webBind.equals("127.0.0.1") && !o.webBind.equals("localhost")) {
                System.out.println("warning: plain HTTP, the passphrase is not encrypted on the network. "
                        + "Only use this over a private network such as Tailscale/VPN or an SSH tunnel.");
            }
            Thread.currentThread().join();
            return 0;
        } catch (IllegalArgumentException | java.io.IOException | java.awt.AWTException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            return 0;
        }
    }
}
