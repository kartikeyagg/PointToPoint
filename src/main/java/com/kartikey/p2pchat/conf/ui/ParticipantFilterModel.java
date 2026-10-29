package com.kartikey.p2pchat.conf.ui;

import com.kartikey.p2pchat.conf.ui.ConferenceController.ParticipantInfo;

import javax.swing.AbstractListModel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * List model over a (possibly huge) participant list, filtered by name substring on a background
 * thread. Only the latest request is published; stale results are dropped.
 */
public final class ParticipantFilterModel extends AbstractListModel<ParticipantInfo> {
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "participant-filter");
        t.setDaemon(true);
        return t;
    });

    private final AtomicInteger generation = new AtomicInteger();
    private volatile List<ParticipantInfo> source = List.of();
    private volatile String query = "";
    private List<ParticipantInfo> shown = List.of(); // EDT only

    /** Case-insensitive substring match on the name; a blank query returns the source as is. */
    public static List<ParticipantInfo> filter(List<ParticipantInfo> all, String query) {
        String q = query == null ? "" : query.strip();
        if (q.isEmpty()) {
            return all;
        }
        List<ParticipantInfo> out = new ArrayList<>();
        for (ParticipantInfo p : all) {
            if (containsIgnoreCase(p.name(), q)) {
                out.add(p);
            }
        }
        return out;
    }

    static boolean containsIgnoreCase(String s, String q) {
        if (s == null) {
            return false;
        }
        int n = q.length();
        for (int i = 0, end = s.length() - n; i <= end; i++) {
            if (s.regionMatches(true, i, q, 0, n)) {
                return true;
            }
        }
        return false;
    }

    /** Fetches a fresh snapshot (on the filter thread) and re-filters. */
    public void reload(Supplier<List<ParticipantInfo>> snapshot) {
        int gen = generation.incrementAndGet();
        POOL.execute(() -> {
            List<ParticipantInfo> all = snapshot.get();
            source = all;
            publish(gen, all);
        });
    }

    public void setQuery(String q) {
        query = q == null ? "" : q;
        int gen = generation.incrementAndGet();
        POOL.execute(() -> publish(gen, source));
    }

    private void publish(int gen, List<ParticipantInfo> all) {
        List<ParticipantInfo> result = filter(all, query);
        if (gen != generation.get()) {
            return;
        }
        Edt.run(() -> {
            if (gen != generation.get()) {
                return;
            }
            int old = shown.size();
            shown = result;
            if (old > 0) {
                fireIntervalRemoved(this, 0, old - 1);
            }
            if (!result.isEmpty()) {
                fireIntervalAdded(this, 0, result.size() - 1);
            }
        });
    }

    @Override
    public int getSize() {
        return shown.size();
    }

    @Override
    public ParticipantInfo getElementAt(int index) {
        return shown.get(index);
    }
}
