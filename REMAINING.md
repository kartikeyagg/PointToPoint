# Conference mode: remaining work

What is still open after phases 1–3 (see [CONFERENCE.md](CONFERENCE.md) for what works). These are
roughly in priority order.

## Must do before real meetings

1. **Camera and screen sending.** Capture (`media.device.CameraCapture`, `ScreenCapture`) and VP8
   simulcast (`media.video.Simulcast`) are built and tested, but the app's `AudioDeviceMediaIO`
   only does mic, speaker and Opus. Add a `DeviceMediaIO` that feeds camera/screen frames to
   `Simulcast` on its own encoder thread (keep only the latest frame, bounded output queue) and
   returns encoded layers from `pollVideo()`. Receiving and showing video is already wired.
2. **Keyframe requests upstream.** Receivers notice missing frames (`MediaReceiver.needsKeyframe`),
   but nothing asks the sender yet; video recovers only at the 2 s periodic keyframe. Send a small
   request to the stream's source (rate-limited) and call `MediaIO.requestKeyframe`.
3. **Real-device testing on all three OSes.** Mic, speaker and camera code have only been unit
   tested without hardware, and conference mode has run between two processes on one Mac.
   Test on macOS, Linux and Windows, across two networks, and behind common home routers.
4. **Pilot meetings at growing scale**: 10, 100, then 1 000 people, using the status-bar health
   counters. The 100k target is backed by simulation (`OverlaySim`, `MeetingSimulationTest`),
   not yet by a real meeting.

## Scale (needed for 100k)

5. **Coordinator fan-in.** Each stream's sender coordinates its own tree and holds a link to
   every subscriber. That is fine for thousands, but a speaker with 100k listeners needs
   delegated coordination: hand subtrees to well-connected relays.
6. **Key rotation cost.** Each rotation wraps the key once per member: about 6.4 MB per minute
   at 100k. Move to an MLS-style tree (RFC 9420), where an update costs about log N.
7. **Roster at 100k.** Newcomers sync at most 2 000 recent entries (admins and publishers
   first), and the window filters a local list. Add on-demand participant search through
   gossip for very large meetings.
8. **Tree rebalancing.** Repair hangs orphaned subtrees under the shallowest free slot, so depth
   grows after heavy churn (5 → 9 hops after 5% churn in `OverlaySim`). Rebalance periodically.
9. **Join rush.** Admins hand newcomers a random sample of members to join through, which
   spreads the load. HyParView can still split briefly when about 100 people per second join;
   roster and policy anti-entropy repair it within about 10–20 s. Tune for 100k.

## Hardening

10. **DoS limits on the network layer.** There is no cookie exchange before handshakes, only
    rate limits. Someone replaying a captured HELLO can delay that peer's own connections.
    Roaming isn't protected against an on-path attacker rewriting source addresses.
11. **Opus loss concealment.** A lost packet is covered by a fading repeat, because FFmpeg
    can't call libopus' own concealment. A direct libopus binding (e.g. JNA) would sound better.
12. **Crowd-mix privacy.** Crowd mixes travel as signed but unencrypted payloads inside the
    hop-by-hop encrypted links. Only members can hear them, but they should also be wrapped
    with the epoch key like other media.
13. **Clock assumptions.** Timed open-mic windows and presence freshness assume clocks within
    a few seconds of each other. Media session numbers use the sender's clock, so a clock that
    jumps backwards delays its restarted stream until receivers reconnect.
14. **Second review pass** over phase 3 (`conf.net`, `conf.node*`, `conf.app`). Phases 1–2 were
    reviewed, and all 11 findings were fixed.
15. **Fuzz tests for node messages** (`NodeMessages`, `Presence`, `ChatLine`). Phase-1 decoders,
    invites, gossip and network datagrams are already fuzzed.

## Nice to have

- Speaker view and tile layout polish. On/off states on the toggle buttons.
- Recording, for hosts who want it. It is off by design today.
- Hardware video encoders (VideoToolbox, NVENC, VAAPI) for lower CPU use.
- Packaged installers per OS. Today it runs with `./conference.sh` (needs Java 17+).
