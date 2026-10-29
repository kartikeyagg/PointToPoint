package com.kartikey.p2pchat.conf.app;

import com.kartikey.p2pchat.Options;
import com.kartikey.p2pchat.StunClient;
import com.kartikey.p2pchat.conf.Identity;
import com.kartikey.p2pchat.conf.ParticipantId;
import com.kartikey.p2pchat.conf.net.UdpPeerNetwork;
import com.kartikey.p2pchat.conf.node.ConferenceNode;
import com.kartikey.p2pchat.conf.node.Invite;
import com.kartikey.p2pchat.conf.node.MediaIO;
import com.kartikey.p2pchat.conf.ui.ConferenceController;
import com.kartikey.p2pchat.conf.ui.ConferenceWindow;
import com.kartikey.p2pchat.conf.ui.LaunchChoice;
import com.kartikey.p2pchat.conf.ui.LauncherDialog;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** {@code --conference}: host or join a meeting, in the window or (headless / {@code --cli}) in the terminal. */
public final class ConferenceApp {

    private ConferenceApp() {}

    public static int run(Options o) throws Exception {
        boolean gui = !o.cli && !GraphicsEnvironment.isHeadless();
        String mode = o.conference, arg = o.conferenceArg, name = o.name;
        if (mode.isEmpty()) {
            if (!gui) {
                System.err.println("--conference without host/join needs a display; use --conference host or --conference join <invite>");
                return 2;
            }
            CompletableFuture<LaunchChoice> choice = new CompletableFuture<>();
            SwingUtilities.invokeLater(() -> LauncherDialog.show(null, choice::complete));
            LaunchChoice c = choice.get();
            if (c == null) {
                return 0;
            }
            mode = c.host() ? "host" : "join";
            arg = c.host() ? c.title() : c.inviteCode();
            name = name != null ? name : c.name();
        }
        Invite invite = null;
        if (mode.equals("join")) {
            try {
                invite = Invite.decode(arg == null ? "" : arg);
            } catch (IllegalArgumentException e) {
                System.err.println("error: that is not a valid invite code (" + e.getMessage() + ")");
                return 2;
            }
        }
        if (name == null || name.isBlank()) {
            name = System.getProperty("user.name", "Guest");
        }

        Identity me = IdentityStore.forPort(o.localPort, Options.CONFERENCE_PORT).loadOrCreate();
        UdpPeerNetwork net = new UdpPeerNetwork(me, o.localPort, p -> true,
                o.stun ? StunClient.DEFAULT_SERVERS : List.of()); // admission is the node's job, per message
        net.start();
        MediaIO media = AudioDeviceMediaIO.create(problem -> System.err.println("media: " + problem));
        ConferenceNode.Settings settings = ConferenceNode.Settings.of(name);
        NodeRunner runner = mode.equals("host")
                ? NodeRunner.host(me, net, media, settings, arg)
                : NodeRunner.join(me, net, media, settings, invite);
        System.out.println("You are " + name + " (" + me.id().fingerprint() + "), UDP port " + net.port());
        if (mode.equals("host")) {
            Thread.sleep(1_500); // let STUN add our public address to the invite
            System.out.println("Invite code (share it; joins work while an admin is online):");
            System.out.println(runner.inviteCode());
        } else {
            System.out.println("Asking " + invite.admin().shortId() + " to join \"" + invite.title() + "\"...");
        }

        if (gui) {
            SwingUtilities.invokeLater(() -> new ConferenceWindow(runner).show());
        } else {
            runner.setView(new ConsoleView(runner));
            Thread input = new Thread(() -> console(runner), "conference-console");
            input.setDaemon(true);
            input.start();
        }
        String reason = runner.awaitEnd();
        System.out.println("Meeting over: " + reason);
        runner.close();
        return 0;
    }

    /** Terminal commands; anything else is sent as chat. */
    private static void console(NodeRunner r) {
        System.out.println("Type to chat. Commands: /mic on|off, /invite, /people, /allow <n>, /deny <n>, "
                + "/openmic <seconds>, /closemic, /admin <n>, /kick <n>, /end, /quit");
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) {
                line = line.strip();
                String[] w = line.split("\\s+", 2);
                switch (w[0]) {
                    case "" -> { }
                    case "/quit" -> {
                        r.leave();
                        return;
                    }
                    case "/mic" -> r.setMicOn(w.length > 1 && w[1].equals("on"));
                    case "/invite" -> System.out.println(r.inviteCode().isEmpty() ? "only admins can invite" : r.inviteCode());
                    case "/people" -> {
                        List<ConferenceController.ParticipantInfo> ps = r.participants();
                        for (int i = 0; i < ps.size(); i++) {
                            ConferenceController.ParticipantInfo p = ps.get(i);
                            System.out.printf("%3d  %s%s%s%s%n", i, p.name(), p.host() ? " (host)" : p.admin() ? " (admin)" : "",
                                    p.canSpeak() ? " can speak" : "", p.speaking() ? " SPEAKING" : "");
                        }
                    }
                    case "/allow", "/deny", "/admin", "/kick" -> {
                        ParticipantId p = pick(r, w);
                        if (p != null) {
                            switch (w[0]) {
                                case "/allow" -> r.allowSpeak(p, true);
                                case "/deny" -> r.allowSpeak(p, false);
                                case "/admin" -> r.grantAdmin(p);
                                default -> r.kick(p);
                            }
                        }
                    }
                    case "/openmic" -> r.openMicForAll(w.length > 1 ? parse(w[1], 60) : 60);
                    case "/closemic" -> r.closeMicForAll();
                    case "/end" -> r.endMeeting();
                    default -> r.sendChat(line);
                }
            }
        } catch (Exception e) {
            System.err.println("console: " + e.getMessage());
        }
    }

    private static ParticipantId pick(NodeRunner r, String[] w) {
        List<ConferenceController.ParticipantInfo> ps = r.participants();
        int i = w.length > 1 ? parse(w[1], -1) : -1;
        if (i < 0 || i >= ps.size()) {
            System.out.println("give a number from /people");
            return null;
        }
        return ps.get(i).id();
    }

    private static int parse(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private record ConsoleView(NodeRunner r) implements ConferenceController.ConferenceView {
        @Override public void onVideo(ParticipantId who, BufferedImage frame) { }
        @Override public void onParticipantsChanged() { }
        @Override public void onPolicyChanged() { System.out.println("* permissions changed" + (r.canSpeak() ? " (you may speak)" : "")); }
        @Override public void onChat(ParticipantId from, String name, String text, long at) { System.out.println(name + ": " + text); }
        @Override public void onActiveSpeakers(List<ParticipantId> speakers) { }
        @Override public void onNotice(String message) { System.out.println("* " + message); }
        @Override public void onEnded(String reason) { }
    }
}
