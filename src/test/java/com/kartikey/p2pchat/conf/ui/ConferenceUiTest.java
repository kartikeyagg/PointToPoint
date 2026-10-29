package com.kartikey.p2pchat.conf.ui;

import com.kartikey.p2pchat.conf.ParticipantId;
import com.kartikey.p2pchat.conf.ui.ConferenceController.ParticipantInfo;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConferenceUiTest {

    private static ParticipantInfo info(ParticipantId id, String name, boolean admin, boolean host) {
        return new ParticipantInfo(id, name, admin, host, true, true, false);
    }

    @Test
    void filterOver100kIsCorrectAndFast() {
        List<ParticipantInfo> all = new ArrayList<>();
        for (int i = 0; i < 100_000; i++) {
            all.add(info(null, "User " + i, false, false));
        }
        long t0 = System.nanoTime();
        List<ParticipantInfo> r = ParticipantFilterModel.filter(all, "uSeR 9999");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(11, r.size()); // 9999, 99990..99999
        assertTrue(ms < 200, "took " + ms + " ms");
        assertEquals(100_000, ParticipantFilterModel.filter(all, "  ").size());
        assertEquals(0, ParticipantFilterModel.filter(all, "zzz").size());
    }

    @Test
    void asyncModelPublishesLatestQuery() throws Exception {
        List<ParticipantInfo> all = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            all.add(info(null, "n" + i, false, false));
        }
        ParticipantFilterModel m = new ParticipantFilterModel();
        CountDownLatch done = new CountDownLatch(1);
        m.addListDataListener(new javax.swing.event.ListDataListener() {
            public void intervalAdded(javax.swing.event.ListDataEvent e) {
                if (m.getSize() == 1) {
                    done.countDown();
                }
            }
            public void intervalRemoved(javax.swing.event.ListDataEvent e) {}
            public void contentsChanged(javax.swing.event.ListDataEvent e) {}
        });
        m.reload(() -> all);
        m.setQuery("n999");
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("n999", m.getElementAt(0).name());
    }

    @Test
    void tileSelectionCapsAtTen() throws Exception {
        TileSelection s = new TileSelection();
        java.security.KeyPairGenerator ed = java.security.KeyPairGenerator.getInstance("Ed25519");
        java.security.KeyPairGenerator x = java.security.KeyPairGenerator.getInstance("X25519");
        List<ParticipantId> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            ids.add(new ParticipantId(ed.generateKeyPair().getPublic().getEncoded(),
                    x.generateKeyPair().getPublic().getEncoded()));
        }
        for (ParticipantId id : ids) {
            s.add(id);
        }
        assertEquals(10, s.size());
        assertFalse(s.toggle(ids.get(11)));
        assertTrue(s.toggle(ids.get(0))); // removes
        assertEquals(9, s.size());
        assertTrue(s.toggle(ids.get(11)));
        assertEquals(10, new TileSelection(ids).size());
    }

    @Test
    void adminActionsHiddenForNonAdmins() {
        ParticipantInfo t = info(null, "t", false, false);
        AdminActions none = new AdminActions(false, false, null);
        assertFalse(none.showToolbar());
        assertFalse(none.any(t));
        AdminActions admin = new AdminActions(true, false, null);
        assertTrue(admin.canKick(t));
        assertTrue(admin.canGrantAdmin(t));
        assertFalse(admin.canRevokeAdmin(info(null, "a", true, false)));
        assertFalse(admin.canTransferHost(t));
        assertFalse(admin.canKick(info(null, "a", true, false)));
        AdminActions host = new AdminActions(true, true, null);
        assertTrue(host.canRevokeAdmin(info(null, "a", true, false)));
        assertTrue(host.canTransferHost(t));
        assertFalse(host.canKick(info(null, "h", true, true)));
    }

    @Test
    void edtHelperMarshals() throws Exception {
        CountDownLatch l = new CountDownLatch(1);
        AtomicBoolean onEdt = new AtomicBoolean();
        new Thread(() -> Edt.run(() -> {
            onEdt.set(EventQueue.isDispatchThread());
            l.countDown();
        })).start();
        assertTrue(l.await(5, TimeUnit.SECONDS));
        assertTrue(onEdt.get());
        AtomicBoolean inline = new AtomicBoolean();
        SwingUtilities.invokeAndWait(() -> {
            Edt.run(() -> inline.set(true));
            assertTrue(inline.get()); // ran synchronously
        });
    }

    @Test
    void windowSmoke() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        FakeController fake = new FakeController("t", "me");
        ConferenceWindow[] w = new ConferenceWindow[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new ConferenceWindow(fake);
            w[0].show();
        });
        Thread.sleep(600);
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(w[0].effectiveGallery().size() <= ConferenceController.MAX_TILES);
            w[0].frame().dispose();
        });
        fake.leave();
    }

    @Test
    void statusTextMentionsAdminsOffline() {
        var h = new ConferenceController.Health(3, 20, 0.02, 2, 100, 200, 1, 80, false);
        assertTrue(ConferenceWindow.statusText(h).contains("admins offline"));
        assertFalse(ConferenceWindow.statusText(
                new ConferenceController.Health(3, 20, 0.02, 2, 100, 200, 1, 80, true)).contains("offline"));
    }
}
