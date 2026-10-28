package com.kartikey.p2pchat.conf.overlay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Offline simulator: join N nodes, churn some, repair, and report tree shape and load. */
public final class OverlaySim {

    public record Stats(int members, int maxDepth, double avgDepth, double forwardingPct,
                        double maxUtilPct, double avgUtilPct, int unplaced, long latencyMillis) {}

    public record Report(long joinMillis, Stats afterJoin, int removed, int reattaches, long repairMillis,
                         Stats afterChurn, boolean valid) {}

    private OverlaySim() {}

    public static Report run(int nodes, int stripes, int stripeKbps, int maxFanout, double churn, long seed) {
        Random rnd = new Random(seed);
        StripeForest f = new StripeForest(new NodeInfo(0, 20_000, true), stripes, stripeKbps, maxFanout);
        Map<Long, NodeInfo> infos = new HashMap<>();
        List<NodeInfo> joiners = new ArrayList<>(nodes);
        for (int i = 1; i <= nodes; i++) {
            boolean relay = rnd.nextDouble() >= 0.15;
            double u = rnd.nextDouble();
            int kbps = relay ? (int) (2000 + 48000 * u * u * u) : 1000; // skewed low, 2..50 Mbps
            NodeInfo ni = new NodeInfo(i, kbps, relay);
            joiners.add(ni);
            infos.put((long) i, ni);
        }
        long t0 = System.nanoTime();
        for (NodeInfo ni : joiners) f.join(ni);
        long joinMs = (System.nanoTime() - t0) / 1_000_000;
        Stats a = stats(f, infos);

        List<Long> ids = new ArrayList<>(infos.keySet());
        Collections.shuffle(ids, rnd);
        int remove = (int) Math.round(nodes * churn);
        int reattaches = 0;
        long t1 = System.nanoTime();
        for (int i = 0; i < remove; i++) reattaches += f.leave(ids.get(i)).size();
        long repairMs = (System.nanoTime() - t1) / 1_000_000;
        for (int i = 0; i < remove; i++) infos.remove(ids.get(i));
        Stats b = stats(f, infos);
        return new Report(joinMs, a, remove, reattaches, repairMs, b, f.validate().isEmpty());
    }

    private static Stats stats(StripeForest f, Map<Long, NodeInfo> infos) {
        int fwd = 0, relays = 0;
        double maxU = 0, sumU = 0;
        for (NodeInfo ni : infos.values()) {
            double used = f.uploadKbpsUsed(ni.id());
            if (used > 0) fwd++;
            if (ni.canRelay()) {
                relays++;
                double u = used / ni.uploadKbps();
                maxU = Math.max(maxU, u);
                sumU += u;
            }
        }
        int n = Math.max(1, infos.size());
        return new Stats(infos.size(), f.maxDepth(), f.averageDepth(), 100.0 * fwd / n,
                100 * maxU, relays == 0 ? 0 : 100 * sumU / relays, f.unplacedCount(), (long) f.maxDepth() * 40);
    }

    private static void print(String label, Stats s) {
        System.out.printf("%s: members=%d maxDepth=%d avgDepth=%.2f forwarding=%.1f%% maxUpload=%.0f%% avgUpload(relays)=%.1f%% unplaced=%d latency@40ms/hop=%dms%n",
                label, s.members(), s.maxDepth(), s.avgDepth(), s.forwardingPct(), s.maxUtilPct(), s.avgUtilPct(), s.unplaced(), s.latencyMillis());
    }

    public static void main(String[] args) {
        int nodes = 100_000, stripes = 4, stripeKbps = 400, maxFanout = 16;
        double churn = 0.05;
        long seed = 1;
        for (String a : args) {
            String[] kv = a.split("=", 2);
            if (kv.length != 2) continue;
            switch (kv[0]) {
                case "nodes" -> nodes = Integer.parseInt(kv[1]);
                case "stripes" -> stripes = Integer.parseInt(kv[1]);
                case "stripeKbps" -> stripeKbps = Integer.parseInt(kv[1]);
                case "maxFanout" -> maxFanout = Integer.parseInt(kv[1]);
                case "churn" -> churn = Double.parseDouble(kv[1]);
                case "seed" -> seed = Long.parseLong(kv[1]);
                default -> System.err.println("unknown arg " + kv[0]);
            }
        }
        Report r = run(nodes, stripes, stripeKbps, maxFanout, churn, seed);
        System.out.println("join time: " + r.joinMillis() + " ms");
        print("after join ", r.afterJoin());
        System.out.println("removed " + r.removed() + " nodes, " + r.reattaches() + " reattachments, repair " + r.repairMillis() + " ms");
        print("after churn", r.afterChurn());
        System.out.println("valid: " + r.valid());
    }
}
