package com.kartikey.p2pchat.conf.ui;

import java.awt.EventQueue;

/** Moves work to the Swing thread. */
public final class Edt {
    private Edt() {}

    /** Runs now when already on the EDT, otherwise queues it. */
    public static void run(Runnable r) {
        if (EventQueue.isDispatchThread()) {
            r.run();
        } else {
            EventQueue.invokeLater(r);
        }
    }
}
