package com.kartikey.p2pchat.conf.ui;

import javax.swing.SwingUtilities;

/** Launcher then the conference window on a fake meeting: {@code java -cp target/classes ...UiDemo}. */
public final class UiDemo {
    private UiDemo() {}

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> LauncherDialog.show(null, choice -> {
            String title = choice.host() ? choice.title() : "Joined meeting";
            new ConferenceWindow(new FakeController(title, choice.name())).show();
        }));
    }
}
