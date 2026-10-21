package com.kartikey.p2pchat.remote;

import com.kartikey.p2pchat.Options;
import com.kartikey.p2pchat.StunClient;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.net.BindException;
import java.util.List;

/** Launcher for a remote-desktop host or viewer. */
public final class DesktopLauncher {

    /** Starts the selected remote desktop mode. */
    public interface Starter {
        void start(Options options) throws Exception;
    }

    private DesktopLauncher() {}

    /** Opens the launcher on the Swing event dispatch thread. */
    public static void open(Options defaults, Starter starter) {
        SwingUtilities.invokeLater(() -> createWindow(defaults, starter));
    }

    /** Builds desktop options from form values without accessing Swing. */
    static Options buildOptions(boolean host, String peer, String peerPort, String localPort,
                                String passphrase, boolean allowControl) {
        if (peer == null || peer.trim().isEmpty()) {
            throw new IllegalArgumentException("Peer address is required");
        }
        if (passphrase == null || passphrase.length() < 6) {
            throw new IllegalArgumentException("Passphrase must be at least 6 characters");
        }
        Options options = new Options();
        options.desktop = host ? "host" : "view";
        options.viewOnly = host && !allowControl;
        options.peerPort = -1;
        options.setPeer(peer);
        boolean peerHasPort = options.peerPort != -1;
        if (!peerHasPort) {
            options.peerPort = parsePort(peerPort, "Peer port");
        }
        options.localPort = parsePort(localPort, "Local port");
        options.passphrase = passphrase;
        return options;
    }

    private static void createWindow(Options defaults, Starter starter) {
        JFrame frame = new JFrame("P2P Remote Desktop");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        boolean hostDefault = !"view".equals(defaults.desktop);
        JRadioButton share = new JRadioButton("Share this screen", hostDefault);
        JRadioButton view = new JRadioButton("Control the other screen", !hostDefault);
        ButtonGroup modes = new ButtonGroup();
        modes.add(share);
        modes.add(view);
        JTextField peer = new JTextField(defaults.peerHost == null ? "" : defaults.peerHost, 24);
        JTextField peerPort = new JTextField(String.valueOf(defaultPort(defaults.peerPort)), 7);
        JTextField localPort = new JTextField(String.valueOf(defaultPort(defaults.localPort)), 7);
        JPasswordField passphrase = new JPasswordField(defaults.passphrase == null ? "" : defaults.passphrase, 24);
        JCheckBox allowControl = new JCheckBox("Allow remote control", !defaults.viewOnly);
        allowControl.setEnabled(share.isSelected());
        share.addActionListener(event -> allowControl.setEnabled(true));
        view.addActionListener(event -> allowControl.setEnabled(false));
        JTextArea addressResult = new JTextArea(3, 42);
        addressResult.setEditable(false);
        addressResult.setLineWrap(true);
        addressResult.setWrapStyleWord(true);
        JButton findAddress = new JButton("Find my address");
        findAddress.addActionListener(event -> findAddress(localPort, addressResult, findAddress));
        JButton start = new JButton("Start");
        start.addActionListener(event -> start(frame, starter, share.isSelected(), peer.getText(), peerPort.getText(),
                localPort.getText(), new String(passphrase.getPassword()), allowControl.isSelected(), start));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.gridwidth = 2;
        add(form, c, 0, share);
        add(form, c, 1, view);
        c.gridwidth = 1;
        add(form, c, 2, new JLabel("Peer address"));
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        form.add(peer, c);
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        add(form, c, 3, new JLabel("Peer port"));
        c.gridx = 1;
        form.add(peerPort, c);
        add(form, c, 4, new JLabel("Local port"));
        c.gridx = 1;
        form.add(localPort, c);
        add(form, c, 5, new JLabel("Passphrase"));
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        form.add(passphrase, c);
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        c.gridx = 0;
        c.gridy = 6;
        c.gridwidth = 2;
        form.add(allowControl, c);
        c.gridy = 7;
        form.add(findAddress, c);
        c.gridy = 8;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(new JScrollPane(addressResult), c);
        c.gridy = 9;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.CENTER;
        form.add(start, c);

        JLabel hint = new JLabel("Both sides: same passphrase, each enters the other's public address. Start both within a few seconds.");
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(form, BorderLayout.CENTER);
        content.add(hint, BorderLayout.SOUTH);
        frame.setContentPane(content);
        frame.pack();
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
    }

    private static void add(JPanel panel, GridBagConstraints c, int y, java.awt.Component component) {
        c.gridx = 0;
        c.gridy = y;
        panel.add(component, c);
    }

    private static int defaultPort(int port) {
        return port == Options.DEFAULT_PORT ? Options.DESKTOP_PORT : port;
    }

    private static int parsePort(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            return Options.DESKTOP_PORT;
        }
        try {
            int port = Integer.parseInt(value.trim());
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException(name + " must be between 0 and 65535");
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a port number");
        }
    }

    private static void findAddress(JTextField localPort, JTextArea result, JButton button) {
        final int port;
        try {
            port = parsePort(localPort.getText(), "Local port");
        } catch (IllegalArgumentException e) {
            showError(result, e.getMessage());
            return;
        }
        button.setEnabled(false);
        new Thread(() -> {
            try {
                List<StunClient.Report> reports = StunClient.probeBoth(port, StunClient.DEFAULT_SERVERS, 1500);
                String text = reports.stream().map(StunClient.Report::summary).reduce((a, b) -> a + "\n" + b).orElse("");
                SwingUtilities.invokeLater(() -> result.setText(text));
            } catch (BindException e) {
                SwingUtilities.invokeLater(() -> result.setText("UDP port " + port + " is in use"));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> result.setText("Could not find address: " + e.getMessage()));
            } finally {
                SwingUtilities.invokeLater(() -> button.setEnabled(true));
            }
        }, "desktop-stun").start();
    }

    private static void start(JFrame frame, Starter starter, boolean host, String peer, String peerPort,
                              String localPort, String passphrase, boolean allowControl, JButton button) {
        final Options options;
        try {
            options = buildOptions(host, peer, peerPort, localPort, passphrase, allowControl);
        } catch (IllegalArgumentException e) {
            showError(frame, e.getMessage());
            return;
        }
        button.setEnabled(false);
        new Thread(() -> {
            try {
                starter.start(options);
                SwingUtilities.invokeLater(frame::dispose);
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    showError(frame, startError(e, options.localPort));
                    button.setEnabled(true);
                });
            }
        }, "desktop-start").start();
    }

    private static String startError(Exception error, int localPort) {
        return error instanceof BindException
                ? "UDP port " + localPort + " is already in use, pick another local port"
                : error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static void showError(java.awt.Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "P2P Remote Desktop", JOptionPane.ERROR_MESSAGE);
    }
}
