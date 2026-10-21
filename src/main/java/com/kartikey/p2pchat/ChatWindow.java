package com.kartikey.p2pchat;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.BindException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Minimal Swing chat window: fill in the peer, press Connect, chat. */
public final class ChatWindow {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private enum Kind { ME, PEER, SYSTEM }

    private enum Delivery { SENDING, DELIVERED, FAILED }

    private static final class Line {
        final Kind kind;
        final String text;
        final String time = LocalTime.now().format(TIME);
        long id = -1;
        Delivery delivery = Delivery.SENDING;

        Line(Kind kind, String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    private final JFrame frame = new JFrame("P2P Chat");
    private final JTextField peerField = new JTextField(14);
    private final JTextField peerPortField = new JTextField(5);
    private final JTextField localPortField = new JTextField(5);
    private final JPasswordField keyField = new JPasswordField(10);
    private final JButton connectButton = new JButton("Connect");
    private final JButton whoamiButton = new JButton("My public address");
    private final JCheckBox stunBox = new JCheckBox("STUN lookup", true);
    private final DefaultListModel<Line> lines = new DefaultListModel<>();
    private final JList<Line> list = new JList<>(lines);
    private final JTextField input = new JTextField();
    private final JButton sendButton = new JButton("Send");
    private final JLabel statusLabel = new JLabel(" Not connected");

    private PeerLink link;

    public static void open(Options o) {
        SwingUtilities.invokeLater(() -> new ChatWindow(o).frame.setVisible(true));
    }

    private ChatWindow(Options o) {
        peerField.setText(o.peerHost == null ? "" : o.peerHost);
        peerField.setToolTipText("1.2.3.4, 1.2.3.4:5000, 2001:db8::1 or [2001:db8::1]:5000");
        stunBox.setSelected(o.stun);
        stunBox.setToolTipText("Ask a public STUN server for this device's public address when connecting");
        peerPortField.setText(String.valueOf(o.peerPort));
        localPortField.setText(String.valueOf(o.localPort));
        keyField.setText(o.passphrase == null ? "" : o.passphrase);

        JPanel form = new JPanel(new FlowLayout(FlowLayout.LEFT));
        form.add(new JLabel("Peer address"));
        form.add(peerField);
        form.add(new JLabel("Peer port"));
        form.add(peerPortField);
        form.add(new JLabel("My port"));
        form.add(localPortField);
        form.add(new JLabel("Passphrase"));
        form.add(keyField);
        form.add(connectButton);
        form.add(whoamiButton);
        form.add(stunBox);

        JLabel hint = new JLabel(" Local IPs: " + String.join(", ", localAddresses())
                + "   Press 'My public address' and send the result to your peer.");
        hint.setForeground(Color.GRAY);

        JPanel top = new JPanel(new BorderLayout());
        top.add(form, BorderLayout.CENTER);
        top.add(hint, BorderLayout.SOUTH);

        list.setCellRenderer(new LineRenderer());
        JScrollPane scroll = new JScrollPane(list);

        JPanel bottom = new JPanel(new BorderLayout(4, 4));
        bottom.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        bottom.add(input, BorderLayout.CENTER);
        bottom.add(sendButton, BorderLayout.EAST);
        bottom.add(statusLabel, BorderLayout.SOUTH);

        frame.setLayout(new BorderLayout());
        frame.add(top, BorderLayout.NORTH);
        frame.add(scroll, BorderLayout.CENTER);
        frame.add(bottom, BorderLayout.SOUTH);
        frame.setSize(980, 560);
        frame.setLocationByPlatform(true);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                disconnect();
                System.exit(0);
            }
        });

        connectButton.addActionListener(e -> {
            if (link == null) {
                connect();
            } else {
                disconnect();
            }
        });
        whoamiButton.addActionListener(e -> whoami());
        sendButton.addActionListener(e -> send());
        input.addActionListener(e -> send());
        setChatEnabled(false);

        if (o.peerHost != null) {
            SwingUtilities.invokeLater(this::connect);
        }
    }

    private void connect() {
        String host = peerField.getText().trim();
        if (host.isEmpty()) {
            system("Enter the peer's IP address first.");
            return;
        }
        try {
            int peerPort = Options.port(peerPortField.getText());
            int localPort = Options.port(localPortField.getText());
            String key = new String(keyField.getPassword());
            Options parsed = new Options();
            parsed.peerPort = peerPort;
            parsed.setPeer(host); // "host:port" in the address field overrides the port field
            InetSocketAddress peer = new InetSocketAddress(parsed.peerHost, parsed.peerPort);
            link = new PeerLink(localPort, peer, new PacketCodec(key), new UiListener(),
                    stunBox.isSelected() ? StunClient.DEFAULT_SERVERS : List.of());
            system("Listening on UDP " + link.localPort() + (link.isEncrypted()
                    ? ", encrypted." : ", NOT encrypted (set a passphrase on both sides)."));
            link.start();
            setFormEnabled(false);
            connectButton.setText("Disconnect");
            setChatEnabled(true);
            input.requestFocusInWindow();
        } catch (BindException e) {
            link = null;
            system("Port already in use: " + localPortField.getText() + ". Pick another 'My port'.");
        } catch (Exception e) {
            link = null;
            system("Cannot connect: " + e.getMessage());
        }
    }

    /** Looks up this device's public IPv4/IPv6 address on the chosen local port. */
    private void whoami() {
        if (link != null) {
            system("Disconnect first (the lookup must use the chat port), or keep 'STUN lookup' ticked when connecting.");
            return;
        }
        int localPort;
        try {
            localPort = Options.port(localPortField.getText());
        } catch (IllegalArgumentException e) {
            system(e.getMessage());
            return;
        }
        whoamiButton.setEnabled(false);
        system("Asking STUN servers for this device's public address...");
        Thread t = new Thread(() -> {
            String result;
            try {
                List<StunClient.Report> r = StunClient.probeBoth(localPort, StunClient.DEFAULT_SERVERS, 1500);
                result = "IPv4: " + r.get(0).summary() + "\nIPv6: " + r.get(1).summary();
            } catch (BindException e) {
                result = "Port " + localPort + " is in use.";
            } catch (Exception e) {
                result = "Lookup failed: " + e.getMessage();
            }
            String text = result;
            SwingUtilities.invokeLater(() -> {
                for (String line : text.split("\n")) {
                    system(line);
                }
                whoamiButton.setEnabled(true);
            });
        }, "stun-lookup");
        t.setDaemon(true);
        t.start();
    }

    private void disconnect() {
        if (link != null) {
            link.close();
            link = null;
        }
        setFormEnabled(true);
        connectButton.setText("Connect");
        setChatEnabled(false);
    }

    private void send() {
        String text = input.getText();
        if (link == null || text.isBlank()) {
            return;
        }
        Line line = new Line(Kind.ME, text);
        try {
            line.id = link.send(text);
        } catch (IllegalArgumentException | IllegalStateException e) {
            system(e.getMessage());
            return;
        }
        add(line);
        input.setText("");
    }

    private void add(Line line) {
        lines.addElement(line);
        list.ensureIndexIsVisible(lines.size() - 1);
    }

    private void system(String text) {
        add(new Line(Kind.SYSTEM, text));
    }

    private void markDelivery(long id, Delivery delivery) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            Line l = lines.get(i);
            if (l.kind == Kind.ME && l.id == id) {
                l.delivery = delivery;
                lines.set(i, l); // fires a repaint of that row
                return;
            }
        }
    }

    private void setFormEnabled(boolean on) {
        whoamiButton.setEnabled(on);
        for (JComponent c : List.of(peerField, peerPortField, localPortField, keyField, stunBox)) {
            c.setEnabled(on);
        }
    }

    private void setChatEnabled(boolean on) {
        input.setEnabled(on);
        sendButton.setEnabled(on);
    }

    /** Marshals PeerLink callbacks onto the Swing thread. */
    private final class UiListener implements PeerLink.Listener {
        @Override
        public void onStatus(PeerLink.Status status, String detail) {
            SwingUtilities.invokeLater(() -> {
                statusLabel.setText(" " + status + " - " + detail);
                if (status != PeerLink.Status.CLOSED) {
                    system(detail);
                }
            });
        }

        @Override
        public void onMessage(String text) {
            SwingUtilities.invokeLater(() -> add(new Line(Kind.PEER, text)));
        }

        @Override
        public void onDelivered(long id) {
            SwingUtilities.invokeLater(() -> markDelivery(id, Delivery.DELIVERED));
        }

        @Override
        public void onFailed(long id, String text) {
            SwingUtilities.invokeLater(() -> markDelivery(id, Delivery.FAILED));
        }

        @Override
        public void onWarning(String warning) {
            SwingUtilities.invokeLater(() -> system(warning));
        }

        @Override
        public void onNatReport(StunClient.Report report) {
            SwingUtilities.invokeLater(() -> system(report.summary()));
        }
    }

    private static final class LineRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focus) {
            Line l = (Line) value;
            String text = switch (l.kind) {
                case ME -> "[" + l.time + "] me: " + l.text + switch (l.delivery) {
                    case SENDING -> "   (sending...)";
                    case DELIVERED -> "   ✓";
                    case FAILED -> "   (not delivered)";
                };
                case PEER -> "[" + l.time + "] peer: " + l.text;
                case SYSTEM -> "  * " + l.text;
            };
            super.getListCellRendererComponent(list, text, index, selected, focus);
            if (!selected) {
                setForeground(switch (l.kind) {
                    case ME -> l.delivery == Delivery.FAILED ? new Color(0xB00020) : new Color(0x1A5FB4);
                    case PEER -> new Color(0x26A269);
                    case SYSTEM -> Color.GRAY;
                });
            }
            return this;
        }
    }

    private static List<String> localAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address || !(a.isLinkLocalAddress() || a.isSiteLocalAddress())) {
                        String h = a.getHostAddress();
                        int zone = h.indexOf('%');
                        out.add(zone >= 0 ? h.substring(0, zone) : h);
                    }
                }
            }
        } catch (Exception ignored) {
            // purely informational
        }
        return out.isEmpty() ? List.of("unknown") : out;
    }
}
