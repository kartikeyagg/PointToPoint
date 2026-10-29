package com.kartikey.p2pchat.conf.ui;

import com.kartikey.p2pchat.conf.ParticipantId;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** In-memory meeting with 40 fake participants and animated video, for the demo and tests. */
public final class FakeController implements ConferenceController {
    private static final String[] NAMES = {"Ada", "Linus", "Grace", "Alan", "Margaret", "Dennis", "Barbara",
            "Ken", "Radia", "Tim"};

    private final ScheduledExecutorService pool = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "fake-conference");
        t.setDaemon(true);
        return t;
    });
    private final Random rnd = new Random(7);
    private final String title;
    private final ParticipantId self;
    private volatile List<ParticipantInfo> people;
    private volatile ConferenceView view;
    private volatile List<ParticipantId> gallery = List.of();
    private volatile boolean admin = true;
    private volatile boolean host = true;
    private volatile boolean micAllowed = true;
    private volatile long openMicUntil;
    private volatile boolean mic;
    private volatile boolean camera;
    private volatile boolean screen;
    private volatile long tick;

    public FakeController(String title, String myName) {
        this.title = title;
        List<ParticipantInfo> list = new ArrayList<>();
        self = newId();
        list.add(new ParticipantInfo(self, myName, true, true, true, true, false));
        for (int i = 1; i < 40; i++) {
            list.add(new ParticipantInfo(newId(), NAMES[i % NAMES.length] + " " + i, i < 3, false, true,
                    i % 4 != 0, false));
        }
        people = List.copyOf(list);
        pool.scheduleAtFixedRate(this::frames, 100, 80, TimeUnit.MILLISECONDS);
        pool.scheduleAtFixedRate(this::speakers, 500, 1500, TimeUnit.MILLISECONDS);
    }

    private static ParticipantId newId() {
        try {
            KeyPair sign = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            KeyPair agree = KeyPairGenerator.getInstance("X25519").generateKeyPair();
            return new ParticipantId(sign.getPublic().getEncoded(), agree.getPublic().getEncoded());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private void frames() {
        ConferenceView v = view;
        if (v == null) {
            return;
        }
        long t = ++tick;
        for (ParticipantId id : gallery) {
            v.onVideo(id, render(id, t));
        }
    }

    private BufferedImage render(ParticipantId id, long t) {
        int h = Math.abs(id.hashCode());
        BufferedImage img = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.getHSBColor((h % 360) / 360f, 0.5f, 0.35f));
        g.fillRect(0, 0, 320, 180);
        g.setColor(Color.getHSBColor((h % 360) / 360f, 0.6f, 0.9f));
        int x = (int) (160 + 110 * Math.sin((t + h % 50) / 12.0));
        int y = (int) (90 + 50 * Math.cos((t + h % 50) / 9.0));
        g.fillOval(x - 20, y - 20, 40, 40);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
        g.drawString(id.shortId(), 10, 170);
        g.dispose();
        return img;
    }

    private void speakers() {
        ConferenceView v = view;
        if (v == null) {
            return;
        }
        List<ParticipantInfo> p = people;
        List<ParticipantId> out = new ArrayList<>();
        out.add(p.get(rnd.nextInt(p.size())).id());
        if (rnd.nextBoolean()) {
            out.add(p.get(rnd.nextInt(p.size())).id());
        }
        v.onActiveSpeakers(out);
    }

    private void update(ParticipantId id, java.util.function.UnaryOperator<ParticipantInfo> f) {
        List<ParticipantInfo> next = new ArrayList<>(people);
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).id().equals(id)) {
                next.set(i, f.apply(next.get(i)));
            }
        }
        people = List.copyOf(next);
        changed();
    }

    private void changed() {
        ConferenceView v = view;
        if (v != null) {
            v.onParticipantsChanged();
        }
    }

    private void policy() {
        ConferenceView v = view;
        if (v != null) {
            v.onPolicyChanged();
        }
    }

    @Override public ParticipantId self() { return self; }
    @Override public String meetingTitle() { return title; }
    @Override public String inviteCode() { return "FAKE-INVITE-0000"; }
    @Override public List<ParticipantInfo> participants() { return people; }
    @Override public boolean isAdmin() { return admin; }
    @Override public boolean isHost() { return host; }
    @Override public boolean canSpeak() { return micAllowed || System.currentTimeMillis() < openMicUntil; }
    @Override public long openMicUntil() { return openMicUntil; }

    @Override
    public Health health() {
        return new Health(people.size() - 1, 20 + rnd.nextInt(10), 0.01, 3, camera ? 600 : 40, 1800, 0, 120, true);
    }

    @Override public void setMicOn(boolean on) { mic = on && canSpeak(); }
    @Override public void setCameraOn(boolean on) { camera = on; }
    @Override public void setScreenShare(boolean on) { screen = on; }
    @Override public void setGallery(List<ParticipantId> tiles) { gallery = List.copyOf(tiles); }

    @Override
    public void sendChat(String text) {
        ConferenceView v = view;
        if (v != null) {
            long now = System.currentTimeMillis();
            v.onChat(self, "You", text, now);
            pool.schedule(() -> v.onChat(people.get(1).id(), people.get(1).name(), "echo: " + text,
                    System.currentTimeMillis()), 600, TimeUnit.MILLISECONDS);
        }
    }

    @Override public void allowSpeak(ParticipantId p, boolean allow) { update(p, i -> new ParticipantInfo(i.id(), i.name(), i.admin(), i.host(), allow, i.cameraOn(), i.speaking())); }
    @Override public void openMicForAll(int seconds) { openMicUntil = System.currentTimeMillis() + seconds * 1000L; policy(); }
    @Override public void closeMicForAll() { openMicUntil = 0; policy(); }
    @Override public void allowCamera(ParticipantId p, boolean allow) { update(p, i -> new ParticipantInfo(i.id(), i.name(), i.admin(), i.host(), i.canSpeak(), allow, i.speaking())); }
    @Override public void grantAdmin(ParticipantId p) { update(p, i -> new ParticipantInfo(i.id(), i.name(), true, i.host(), i.canSpeak(), i.cameraOn(), i.speaking())); }
    @Override public void revokeAdmin(ParticipantId p) { update(p, i -> new ParticipantInfo(i.id(), i.name(), false, i.host(), i.canSpeak(), i.cameraOn(), i.speaking())); }

    @Override
    public void transferHost(ParticipantId p) {
        host = false;
        update(self, i -> new ParticipantInfo(i.id(), i.name(), true, false, i.canSpeak(), i.cameraOn(), i.speaking()));
        update(p, i -> new ParticipantInfo(i.id(), i.name(), true, true, i.canSpeak(), i.cameraOn(), i.speaking()));
        policy();
    }

    @Override
    public void kick(ParticipantId p) {
        List<ParticipantInfo> next = new ArrayList<>(people);
        next.removeIf(i -> i.id().equals(p));
        people = List.copyOf(next);
        changed();
    }

    @Override
    public void endMeeting() {
        ConferenceView v = view;
        if (v != null) {
            v.onEnded("The host ended the meeting");
        }
        pool.shutdownNow();
    }

    @Override
    public void leave() {
        pool.shutdownNow();
    }

    @Override public void setView(ConferenceView v) { view = v; }
}
