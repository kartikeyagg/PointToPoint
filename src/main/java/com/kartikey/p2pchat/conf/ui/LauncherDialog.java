package com.kartikey.p2pchat.conf.ui;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

/** Asks whether to host or join, and for the display name (remembered between runs). */
public final class LauncherDialog extends JDialog {
    private static final String NAME_KEY = "conf.name";

    private final JRadioButton host = new JRadioButton("Host a meeting", true);
    private final JRadioButton join = new JRadioButton("Join");
    private final JTextField name = new JTextField(20);
    private final JTextField detail = new JTextField(20);
    private final JLabel detailLabel = new JLabel("Meeting title");

    public LauncherDialog(Window owner, Consumer<LaunchChoice> onChoice) {
        super(owner, "P2P Conference", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        name.setText(rememberedName());
        ButtonGroup group = new ButtonGroup();
        group.add(host);
        group.add(join);
        host.addActionListener(e -> detailLabel.setText("Meeting title"));
        join.addActionListener(e -> detailLabel.setText("Invite code"));

        JPanel form = new JPanel(new GridLayout(0, 1, 0, 4));
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 6, 12));
        JPanel modes = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        modes.add(host);
        modes.add(join);
        form.add(modes);
        form.add(new JLabel("Your name"));
        form.add(name);
        form.add(detailLabel);
        form.add(detail);

        JButton go = new JButton("Start");
        JButton cancel = new JButton("Cancel");
        go.addActionListener(e -> {
            String n = name.getText().strip();
            String d = detail.getText().strip();
            if (n.isEmpty() || d.isEmpty()) {
                detailLabel.setText(host.isSelected() ? "Meeting title (required)" : "Invite code (required)");
                return;
            }
            saveName(n);
            boolean h = host.isSelected();
            LaunchChoice choice = new LaunchChoice(h, h ? d : "", h ? "" : d, n);
            dispose();
            onChoice.accept(choice);
        });
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(go);
        getRootPane().setDefaultButton(go);
        add(form, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(owner);
    }

    static String rememberedName() {
        try {
            return Preferences.userNodeForPackage(LauncherDialog.class).get(NAME_KEY,
                    System.getProperty("user.name", ""));
        } catch (RuntimeException e) {
            return "";
        }
    }

    static void saveName(String n) {
        try {
            Preferences.userNodeForPackage(LauncherDialog.class).put(NAME_KEY, n);
        } catch (RuntimeException ignored) {
            // preferences unavailable; just don't remember
        }
    }

    /** Shows the dialog (blocks until closed); the callback runs only on Start. */
    public static void show(Window owner, Consumer<LaunchChoice> onChoice) {
        new LauncherDialog(owner, onChoice).setVisible(true);
    }
}
