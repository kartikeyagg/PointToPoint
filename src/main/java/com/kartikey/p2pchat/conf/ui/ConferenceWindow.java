package com.kartikey.p2pchat.conf.ui;

import com.kartikey.p2pchat.conf.ParticipantId;
import com.kartikey.p2pchat.conf.ui.ConferenceController.Health;
import com.kartikey.p2pchat.conf.ui.ConferenceController.ParticipantInfo;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListCellRenderer;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Zoom-like conference window: gallery plus speaker tile, controls, participants, chat, status. */
public final class ConferenceWindow implements ConferenceController.ConferenceView {
    private static final int CHAT_LIMIT = 2000;

    private final ConferenceController c;
    private final JFrame frame;
    private final VideoTile speakerTile = new VideoTile();
    private final JPanel grid = new JPanel();
    private volatile Map<ParticipantId, VideoTile> tiles = Map.of();
    private volatile ParticipantId speaker;
    private volatile Set<ParticipantId> speaking = Set.of();
    private TileSelection selection;

    private final JToggleButton mic = new JToggleButton("Mic");
    private final JToggleButton cam = new JToggleButton("Camera");
    private final JToggleButton share = new JToggleButton("Share screen");
    private final JToggleButton chatToggle = new JToggleButton("Chat");
    private final JToggleButton peopleToggle = new JToggleButton("Participants");
    private final JLabel banner = new JLabel(" ");
    private final JLabel status = new JLabel(" ");
    private final JLabel notice = new JLabel(" ");
    private final JPanel adminBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
    private final JPanel side = new JPanel(new GridLayout(0, 1));
    private final JPanel peoplePanel = new JPanel(new BorderLayout());
    private final JPanel chatPanel = new JPanel(new BorderLayout());
    private final ParticipantFilterModel peopleModel = new ParticipantFilterModel();
    private final JList<ParticipantInfo> peopleList = new JList<>(peopleModel);
    private final DefaultListModel<String> chatModel = new DefaultListModel<>();
    private final Timer ticker;
    private final AtomicBoolean peoplePending = new AtomicBoolean();
    private boolean ended;

    public ConferenceWindow(ConferenceController controller) {
        this.c = controller;
        frame = new JFrame(c.meetingTitle());
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                leave();
            }
        });
        selection = new TileSelection(initialTiles());
        buildGallery();
        JPanel center = new JPanel(new BorderLayout());
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, speakerTile, grid);
        split.setResizeWeight(0.5);
        split.setBorder(null);
        center.add(split, BorderLayout.CENTER);

        JPanel top = new JPanel(new BorderLayout());
        banner.setForeground(new Color(180, 90, 0));
        top.add(banner, BorderLayout.NORTH);
        adminBar.add(new JLabel("Admin:"));
        JSpinner secs = new JSpinner(new SpinnerNumberModel(60, 5, 3600, 5));
        JButton open = new JButton("Open everyone's mic for");
        open.addActionListener(e -> c.openMicForAll((Integer) secs.getValue()));
        JButton close = new JButton("Close mics");
        close.addActionListener(e -> c.closeMicForAll());
        JButton end = new JButton("End meeting");
        end.addActionListener(e -> {
            if (confirm("End the meeting for everyone?")) {
                c.endMeeting();
            }
        });
        JButton tilesBtn = new JButton("Choose tiles");
        tilesBtn.addActionListener(e -> chooseTiles());
        adminBar.add(open);
        adminBar.add(secs);
        adminBar.add(new JLabel("sec"));
        adminBar.add(close);
        adminBar.add(end);
        JPanel bars = new JPanel(new BorderLayout());
        bars.add(adminBar, BorderLayout.CENTER);
        bars.add(tilesBtn, BorderLayout.EAST);
        top.add(bars, BorderLayout.CENTER);

        buildPeoplePanel();
        buildChatPanel();
        side.setPreferredSize(new Dimension(300, 100));

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 6));
        for (JToggleButton b : new JToggleButton[] {mic, cam, share, chatToggle, peopleToggle}) {
            b.setFocusable(false);
            bottom.add(b);
        }
        JButton leave = new JButton("Leave");
        leave.setForeground(new Color(180, 0, 0));
        leave.addActionListener(e -> leave());
        bottom.add(leave);
        mic.addActionListener(e -> {
            if (c.canSpeak()) {
                c.setMicOn(mic.isSelected());
            }
            refreshState();
        });
        cam.addActionListener(e -> c.setCameraOn(cam.isSelected()));
        share.addActionListener(e -> c.setScreenShare(share.isSelected()));
        chatToggle.addActionListener(e -> layoutSide());
        peopleToggle.addActionListener(e -> layoutSide());
        peopleToggle.setSelected(true);

        JPanel south = new JPanel(new BorderLayout());
        south.add(bottom, BorderLayout.NORTH);
        JPanel st = new JPanel(new BorderLayout());
        st.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        st.add(status, BorderLayout.WEST);
        st.add(notice, BorderLayout.EAST);
        south.add(st, BorderLayout.SOUTH);

        frame.add(top, BorderLayout.NORTH);
        frame.add(center, BorderLayout.CENTER);
        frame.add(side, BorderLayout.EAST);
        frame.add(south, BorderLayout.SOUTH);
        layoutSide();
        frame.setSize(1100, 720);
        frame.setLocationRelativeTo(null);

        c.setView(this);
        rebuildTiles();
        peopleModel.reload(c::participants);
        refreshState();
        ticker = new Timer(1000, e -> refreshState());
    }

    public void show() {
        ticker.start();
        frame.setVisible(true);
    }

    public JFrame frame() {
        return frame;
    }

    // --- gallery

    private List<ParticipantId> initialTiles() {
        List<ParticipantId> ids = new ArrayList<>();
        for (ParticipantInfo p : c.participants()) {
            if (ids.size() >= ConferenceController.MAX_TILES) {
                break;
            }
            ids.add(p.id());
        }
        return ids;
    }

    private void buildGallery() {
        grid.setBackground(Color.BLACK);
    }

    /** Selected tiles, plus the current speaker when there is room, so the speaker tile has video. */
    List<ParticipantId> effectiveGallery() {
        List<ParticipantId> ids = selection.list();
        ParticipantId s = speaker;
        if (s != null && !ids.contains(s) && ids.size() < ConferenceController.MAX_TILES) {
            ids.add(s);
        }
        return ids;
    }

    private void rebuildTiles() {
        List<ParticipantId> ids = effectiveGallery();
        Map<ParticipantId, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            for (ParticipantInfo p : c.participants()) {
                if (ids.contains(p.id())) {
                    names.put(p.id(), p.name());
                }
            }
        }
        Map<ParticipantId, VideoTile> old = tiles;
        Map<ParticipantId, VideoTile> next = new HashMap<>();
        grid.removeAll();
        int n = Math.max(1, ids.size());
        int cols = (int) Math.ceil(Math.sqrt(n));
        grid.setLayout(new GridLayout(0, cols, 2, 2));
        for (ParticipantId id : ids) {
            VideoTile t = old.getOrDefault(id, new VideoTile());
            t.setName(names.getOrDefault(id, id.shortId()));
            t.setSpeaking(speaking.contains(id));
            next.put(id, t);
            grid.add(t);
        }
        tiles = next;
        updateSpeakerTile();
        grid.revalidate();
        grid.repaint();
        c.setGallery(ids);
    }

    private void updateSpeakerTile() {
        ParticipantId s = speaker;
        VideoTile t = s == null ? null : tiles.get(s);
        speakerTile.setName(s == null ? "" : "Speaking: " + tileName(s));
        speakerTile.setSpeaking(s != null);
        speakerTile.setFrame(t == null ? null : t.frame());
    }

    private String tileName(ParticipantId s) {
        for (ParticipantInfo p : c.participants()) {
            if (p.id().equals(s)) {
                return p.name();
            }
        }
        return s.shortId();
    }

    private void chooseTiles() {
        JDialog d = new JDialog(frame, "Choose tiles (max " + ConferenceController.MAX_TILES + ")", true);
        TileSelection sel = new TileSelection(selection.list());
        ParticipantFilterModel model = new ParticipantFilterModel();
        model.reload(c::participants);
        JList<ParticipantInfo> list = new JList<>(model);
        list.setFixedCellHeight(22);
        list.setPrototypeCellValue(new ParticipantInfo(null, "XXXXXXXXXXXXXXXXXXXX", false, false, true, true, false));
        JLabel count = new JLabel(sel.size() + " selected");
        list.setCellRenderer((l, p, i, isSel, focus) -> {
            JLabel label = new JLabel((sel.contains(p.id()) ? "[x] " : "[ ] ") + p.name());
            label.setOpaque(true);
            label.setBackground(isSel ? l.getSelectionBackground() : l.getBackground());
            return label;
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0 && list.getCellBounds(i, i).contains(e.getPoint())) {
                    if (!sel.toggle(model.getElementAt(i).id())) {
                        count.setText("Limit of " + ConferenceController.MAX_TILES + " reached");
                    } else {
                        count.setText(sel.size() + " selected");
                    }
                    list.repaint();
                }
            }
        });
        JTextField search = new JTextField();
        search.getDocument().addDocumentListener(simple(() -> model.setQuery(search.getText())));
        JButton ok = new JButton("Apply");
        ok.addActionListener(e -> {
            selection = sel;
            rebuildTiles();
            d.dispose();
        });
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(count, BorderLayout.WEST);
        bottom.add(ok, BorderLayout.EAST);
        d.add(search, BorderLayout.NORTH);
        d.add(new JScrollPane(list), BorderLayout.CENTER);
        d.add(bottom, BorderLayout.SOUTH);
        d.setSize(320, 420);
        d.setLocationRelativeTo(frame);
        d.setVisible(true);
    }

    // --- side panels

    private void buildPeoplePanel() {
        JTextField search = new JTextField();
        search.getDocument().addDocumentListener(simple(() -> peopleModel.setQuery(search.getText())));
        JPanel head = new JPanel(new BorderLayout());
        head.add(new JLabel(" Participants "), BorderLayout.WEST);
        head.add(search, BorderLayout.CENTER);
        peopleList.setFixedCellHeight(22);
        peopleList.setPrototypeCellValue(new ParticipantInfo(null, "XXXXXXXXXXXXXXXXXXXXXXXX", true, true, true, true, false));
        ListCellRenderer<ParticipantInfo> renderer = (l, p, i, sel, focus) -> {
            StringBuilder sb = new StringBuilder(p.name());
            if (p.host()) {
                sb.append(" (host)");
            } else if (p.admin()) {
                sb.append(" (admin)");
            }
            if (!p.canSpeak()) {
                sb.append("  muted");
            }
            if (!p.cameraOn()) {
                sb.append("  no cam");
            }
            JLabel label = new JLabel(sb.toString());
            label.setOpaque(true);
            label.setBackground(sel ? l.getSelectionBackground() : l.getBackground());
            return label;
        };
        peopleList.setCellRenderer(renderer);
        peopleList.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybePopup(e);
            }
        });
        peoplePanel.add(head, BorderLayout.NORTH);
        peoplePanel.add(new JScrollPane(peopleList), BorderLayout.CENTER);
    }

    private void maybePopup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int i = peopleList.locationToIndex(e.getPoint());
        if (i < 0 || !peopleList.getCellBounds(i, i).contains(e.getPoint())) {
            return;
        }
        peopleList.setSelectedIndex(i);
        JPopupMenu menu = rowMenu(peopleModel.getElementAt(i));
        if (menu.getComponentCount() > 0) {
            menu.show(peopleList, e.getX(), e.getY());
        }
    }

    JPopupMenu rowMenu(ParticipantInfo t) {
        AdminActions a = AdminActions.of(c);
        JPopupMenu m = new JPopupMenu();
        ParticipantId id = t.id();
        if (a.canAllowSpeak(t)) {
            m.add(item(t.canSpeak() ? "Deny speak" : "Allow speak", () -> c.allowSpeak(id, !t.canSpeak())));
        }
        if (a.canAllowCamera(t)) {
            m.add(item(t.cameraOn() ? "Deny camera" : "Allow camera", () -> c.allowCamera(id, !t.cameraOn())));
        }
        if (a.canGrantAdmin(t)) {
            m.add(item("Make admin", () -> c.grantAdmin(id)));
        }
        if (a.canRevokeAdmin(t)) {
            m.add(item("Remove admin", () -> c.revokeAdmin(id)));
        }
        if (a.canTransferHost(t)) {
            m.add(item("Make host", () -> {
                if (confirm("Make " + t.name() + " the host? You will no longer be host.")) {
                    c.transferHost(id);
                }
            }));
        }
        if (a.canKick(t)) {
            m.add(item("Kick", () -> {
                if (confirm("Kick " + t.name() + "?")) {
                    c.kick(id);
                }
            }));
        }
        return m;
    }

    private static JMenuItem item(String text, Runnable r) {
        JMenuItem i = new JMenuItem(text);
        i.addActionListener(e -> r.run());
        return i;
    }

    private void buildChatPanel() {
        JList<String> list = new JList<>(chatModel);
        JTextField input = new JTextField();
        input.addActionListener(e -> {
            String text = input.getText().strip();
            if (!text.isEmpty()) {
                c.sendChat(text);
                input.setText("");
            }
        });
        chatPanel.add(new JLabel(" Chat"), BorderLayout.NORTH);
        chatPanel.add(new JScrollPane(list), BorderLayout.CENTER);
        chatPanel.add(input, BorderLayout.SOUTH);
    }

    private void layoutSide() {
        side.removeAll();
        if (peopleToggle.isSelected()) {
            side.add(peoplePanel);
        }
        if (chatToggle.isSelected()) {
            side.add(chatPanel);
        }
        side.setVisible(side.getComponentCount() > 0);
        side.revalidate();
        side.repaint();
    }

    // --- state

    void refreshState() {
        AdminActions a = AdminActions.of(c);
        adminBar.setVisible(a.showToolbar());
        boolean speak = c.canSpeak();
        if (!speak) {
            mic.setSelected(false);
            mic.setText("Muted by admin");
        } else {
            mic.setText("Mic");
        }
        long left = c.openMicUntil() - System.currentTimeMillis();
        banner.setText(left > 0 ? "Mics are open for everyone: " + (left + 999) / 1000 + " s left" : " ");
        Health h = c.health();
        StringBuilder sb = new StringBuilder(statusText(h));
        status.setText(sb.toString());
        status.setForeground(h.adminOnline() ? Color.DARK_GRAY : new Color(180, 0, 0));
    }

    /** One-line status; plain function so it can be tested. */
    static String statusText(Health h) {
        String s = String.format("peers %d | rtt %d ms | loss %.1f%% | depth %d | up %d / down %d kbps | tampered %d | audio delay %d ms",
                h.peers(), h.rttMillis(), h.loss() * 100, h.treeDepth(), h.uploadKbps(), h.downloadKbps(),
                h.tampered(), h.audioDelayMillis());
        return h.adminOnline() ? s : s + " | admins offline — joins and kicks paused";
    }

    private boolean confirm(String message) {
        return JOptionPane.showConfirmDialog(frame, message, "Confirm", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION;
    }

    private void leave() {
        if (!ended) {
            ended = true;
            c.leave();
        }
        close();
    }

    private void close() {
        ticker.stop();
        frame.dispose();
    }

    private static DocumentListener simple(Runnable r) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { r.run(); }
            @Override public void removeUpdate(DocumentEvent e) { r.run(); }
            @Override public void changedUpdate(DocumentEvent e) { r.run(); }
        };
    }

    // --- ConferenceView (any thread)

    @Override
    public void onVideo(ParticipantId who, BufferedImage image) {
        VideoTile t = tiles.get(who);
        if (t != null) {
            t.setFrame(image);
        }
        if (who.equals(speaker)) {
            speakerTile.setFrame(image);
        }
    }

    @Override
    public void onParticipantsChanged() {
        if (peoplePending.compareAndSet(false, true)) {
            peopleModel.reload(() -> {
                peoplePending.set(false);
                return c.participants();
            });
        }
    }

    @Override
    public void onPolicyChanged() {
        Edt.run(this::refreshState);
    }

    @Override
    public void onChat(ParticipantId from, String name, String text, long at) {
        Edt.run(() -> {
            chatModel.addElement(new SimpleDateFormat("HH:mm").format(new Date(at)) + " " + name + ": " + text);
            if (chatModel.size() > CHAT_LIMIT) {
                chatModel.removeRange(0, chatModel.size() - CHAT_LIMIT - 1);
            }
        });
    }

    @Override
    public void onActiveSpeakers(List<ParticipantId> speakers) {
        Edt.run(() -> {
            ParticipantId old = speaker;
            ParticipantId now = speakers.isEmpty() ? null : speakers.get(0);
            speaker = now;
            speaking = Set.copyOf(speakers);
            if (java.util.Objects.equals(old, now)) {
                tiles.forEach((id, t) -> t.setSpeaking(speaking.contains(id)));
                return;
            }
            if (now != null && !tiles.containsKey(now) && selection.size() < ConferenceController.MAX_TILES) {
                rebuildTiles();
            } else {
                tiles.forEach((id, t) -> t.setSpeaking(speaking.contains(id)));
                updateSpeakerTile();
            }
        });
    }

    @Override
    public void onNotice(String message) {
        Edt.run(() -> {
            notice.setText(message);
            chatModel.addElement("* " + message);
            Timer t = new Timer(8000, e -> notice.setText(" "));
            t.setRepeats(false);
            t.start();
        });
    }

    @Override
    public void onEnded(String reason) {
        Edt.run(() -> {
            ended = true;
            JOptionPane.showMessageDialog(frame, reason, "Meeting ended", JOptionPane.INFORMATION_MESSAGE);
            close();
        });
    }

    JComponent speakerTileForTest() {
        return speakerTile;
    }
}
