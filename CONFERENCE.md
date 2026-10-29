# Conference mode

Large private meetings (target: 100 000 participants) with no central server. The host's own
computer is the admin authority, but it does **not** carry the media: participants relay for
each other, and nobody who relays can read or alter what they forward unnoticed.

Status: **runnable** — host and join meetings over real UDP with audio, chat and all admin
controls, in a window or in the terminal. Camera/screen *sending* is the main piece still to wire
up; see **[REMAINING.md](REMAINING.md)** for everything left.

## Quick start

```bash
./conference.sh                                   # launcher window: host a meeting or paste an invite
./conference.sh host "Team sync" --name Alice     # host; prints the invite code
./conference.sh join <invite-code> --name Bob     # join
# add --cli for the terminal version, --port <n> for a second instance on the same machine
```

The host prints an invite code (`p2pc1:…`). Anyone with it can ask to join **while an admin is
online**. Default UDP port **7302**; let it through your firewall. Your identity is kept in
`~/.p2p-conference/identity` (`identity-<port>` for extra instances).

| In the window | In the terminal (`--cli`) |
|---|---|
| Mic / Camera / Share screen toggles, Chat, Participants, Leave | type to chat, `/mic on`, `/mic off`, `/quit` |
| Participants panel → right-click a person (admins): allow/deny speaking or camera, make admin, make host, kick | `/people`, then `/allow <n>`, `/deny <n>`, `/admin <n>`, `/kick <n>` |
| Admin bar: open everyone's mic for N seconds, close mics, end meeting | `/openmic <seconds>`, `/closemic`, `/end` |
| "Choose tiles": which 10 people's video to show | — |
| Status bar: peers, RTT, loss, up/down rate, tampered packets, audio delay, "admins offline" | `/invite` prints a fresh invite (admins) |

| Phase | Contents | State |
|---|---|---|
| 1 | identities, signed admin policy, epoch keys, signed media packets, striped relay trees + simulator, loudest-speaker selection, crowd mixing | done (this doc) |
| 2 | media: Opus audio, camera + VP8 simulcast (FFmpeg via JavaCPP), screen share, packetizing, jitter buffer | done |
| 3 | UDP network (authenticated sessions, reliable control, hole punching, member relay), gossip membership, relay-tree runtime with repair + NACK recovery, node integration, Swing window, CLI | done (camera sending: see REMAINING.md) |

## Roles and rules

- **Host**: creates the meeting (signs the *genesis*). Always an admin; can add or remove admins
  and hand the host role to someone else (the old host stays an admin). Like WhatsApp groups,
  there can be many admins.
- **Admins**: admit people (join tickets), allow/deny speaking and camera, open everyone's mic for
  a timed window (crowd mode, e.g. a one-minute birthday song), kick non-admins, end the meeting.
  Only the host can kick an admin.
- **Participants**: watch up to ~10 feeds of their choice, talk when allowed, and relay traffic.

**When no admin is online the meeting keeps running with the last signed policy, frozen.**
Nobody can be admitted (join tickets live 60 s and only admins sign them) and nobody can be
kicked until an admin returns. Timed permissions such as an open mic still end on time, because
the expiry is part of the signed command.

## How it fits together

```
 host / admins ──signed commands, key bundles──▶ gossip to every node (anyone forwards, nobody can forge)

 speaker ──stripe 0──▶ relay ──▶ relay ──▶ …        each stripe has its own tree;
         ──stripe 1──▶ relay ──▶ relay ──▶ …        a node forwards in at most one stripe
         ──stripe k──▶ …                            and is a leaf in the others
```

### Admin control plane (`conf.policy`)
Admin commands are signed (Ed25519) and spread by gossip in any order. Every node keeps the set
of commands it has seen and derives the state by replaying them in a fixed order
(lamport clock, issuer, command hash), checking at each step that the issuer had the right to
do it. Two nodes that have seen the same commands therefore always agree, whatever order the
commands arrived in; `PolicyState.digest()` lets peers check that cheaply.

### Keys and privacy (`conf.keys`)
All media is end-to-end encrypted with a 32-byte *epoch key*. An admin makes a new one every
60 s and immediately after a kick, and wraps it for every member with X25519 + HKDF + AES-GCM.
Bundles are signed and split into shards (~1000 members each) so each gossip unit stays small;
a full 100k rotation is about 6.4 MB spread across the overlay. Receivers keep the newest three
epochs so late packets still decrypt. Someone who leaves can decrypt until the next rotation
(at most about a minute) — an accepted trade-off.

### Tamper-proof relaying (`conf.stream`)
Each packet is `clear header (24 B) | AES-256-GCM(payload)`, with the header as associated
data. Relays read the header (stream, simulcast layer, stripe, audio level) to route without
decrypting. Because every member holds the epoch key, encryption alone would let a relay forge
packets, so senders also sign a **manifest** every few packets listing the packets' hashes.
Receivers release a packet only when it matches a verified manifest; modified, forged or
replayed packets are dropped and counted. A relay can still *drop* packets — receivers report
loss and move to a different parent.

### Relay overlay (`conf.overlay`)
SplitStream-style striped trees: the stream is cut into k stripes, each with its own tree.
A node's fanout follows its measured upload; nodes behind strict NAT or on weak links only
receive. Nodes attach under the shallowest parent with a free slot, so depth stays near
log_fanout(N). When a relay leaves, its children reattach elsewhere. `OverlaySim` builds and
churns a 100 000-node forest in memory (see "Try it").

### Audio (`conf.audio`)
- Normal mode: only the active speaker plus the loudest few voices are forwarded
  (`LoudestSelector`, with hysteresis so the choice doesn't flap).
- Crowd mode (an admin opened every mic): audio flows *up* a tree, each relay mixes its children
  (`CrowdMixer`, with a soft limiter so 10 000 voices don't clip) and passes one stream up; the
  root's mix comes back down to everyone. Each mix is signed by its mixer (`SignedMix`) so an
  injected sound can be traced and the mixer muted. Expect about 1 s of delay, so chants work
  but singing in unison won't line up.

## Media (phase 2, `conf.media`)

| Media | Codec | Bitrate | Measured (Apple Silicon laptop) |
|---|---|---|---|
| Audio | Opus 20 ms, in-band FEC, DTX | ~22 kbps (silence ≈ 8 B/frame) | ~0.7 ms encode+decode per frame |
| Camera | VP8 real-time, 3 simulcast layers 180p/360p/720p | 150 k / 500 k / 1.5 Mbps budgets | all 3 layers ≈ 5.5–8 ms per frame; 720p decode ≈ 1.6 ms |
| Screen | VP8 from the existing native/Robot capture | as camera | — |

Why VP8 rather than H.264: it is royalty-free and the bundled FFmpeg has it on every platform
(libx264 isn't bundled, and hardware H.264 differs per OS). Layers nobody watches are switched
off so they cost no CPU.

| Package | What it does |
|---|---|
| `media.audio` | `OpusEncoder` / `OpusDecoder` (lost packet → concealment) |
| `media.video` | `Vp8Encoder` / `Vp8Decoder`, `Simulcast` (per-layer enable, keyframe requests) |
| `media.device` | `MicCapture`, `SpeakerPlayback` (javax.sound), `CameraCapture` (avfoundation / v4l2 / dshow), `ScreenCapture` |
| `media.pipeline` | `MediaSender` (fragment → encrypt → sign), `MediaReceiver` (verify → decrypt → reassemble), `AudioJitterBuffer` (adaptive 40–200 ms) |

FFmpeg comes from Maven (`org.bytedeco:ffmpeg`). A normal build downloads only the natives for
the machine it runs on (≈20 MB in `target/lib`); `mvn package -Pall-platforms` builds one
`target/lib` that runs on macOS, Linux and Windows.

`MediaReliabilityTest` pushes real Opus and VP8 through the pipeline over a simulated bad
network (loss, 30–90 ms jitter, reordering) and asserts how much gets through:

| Link | Result |
|---|---|
| audio, 10% random loss | 90.3% of frames played, the rest concealed (no added loss from signing) |
| 360p video, 3% loss, keyframe requests | 88.8% of frames shown |

Manifests overlap (each covers the last 16 packets, one sent every 8), so a lost manifest costs
nothing. The price is about 43 bytes per packet: ~45% on top of 24 kbps Opus audio, ~4% on video.

## Phase 3: running a meeting

| Package | What it does |
|---|---|
| `conf.net` | `UdpPeerNetwork`: one UDP socket for all peers; SIGMA handshake (Ed25519 identities, X25519 forward secrecy), AES-GCM sessions, reliable ordered control (RFC 6298 RTO, SACK), hole punching, relay through a member; `MemoryFabric` for deterministic tests |
| `conf.node.gossip` | HyParView partial views, eager-push gossip with IHAVE/IWANT repair, policy and key anti-entropy |
| `conf.node.tree` | per-stream tree coordinator (run by the stream's own sender), forwarder with repair and hop-by-hop NACK recovery, crowd-mode mixing |
| `conf.node` | `ConferenceNode` ties it all together: invite → join request → admin's ticket + key → gossip → presence, rosters, rotating keys, speaker trees, crowd mode, kicks |
| `conf.app` | `ConferenceApp` (CLI/launcher), `NodeRunner` (the node's single thread), `IdentityStore`, `AudioDeviceMediaIO` (mic, speaker, Opus) |
| `conf.ui` | `ConferenceWindow`, `LauncherDialog` |

Measured in tests:

| Test | Result |
|---|---|
| `MeetingSimulationTest`: 41 nodes, invite joins | every node sees all 41 participants |
| same, speaker → 40 listeners at 3% loss | worst listener heard 95.6% of frames (NACK recovery on every hop) |
| crowd mode, 20 open mics | all 18 silent listeners hear the crowd mix |
| kick | kicked node is told and leaves; everyone else gets a new key it lacks |
| host crashes | speaker still heard by everyone; nobody new can join |
| tree runtime, 400 nodes | worst node 99.2% at 2% loss, 98.4% at 5% loss (3–7% overhead); relay crash recovery ≈ 160 ms |
| `UdpPeerNetwork` on loopback | ~130k packets/s of 1000 B; control round trip ≈ 0.1 ms |
| two real processes (host + joiner, `--cli`) | join, chat both ways, participant list, leave, end |

## Try it

```bash
mvn -q test -Dtest='com.kartikey.p2pchat.conf.**'      # conference unit tests
mvn -q compile && java -cp target/classes com.kartikey.p2pchat.conf.overlay.OverlaySim nodes=100000
```

Measured on a laptop (100 000 nodes, 4 stripes × 400 kbps, fanout ≤ 16, 15% leaf-only nodes):

```
join time: 155 ms
after join : maxDepth=5 avgDepth=4.75 forwarding=85.0% unplaced=0 latency@40ms/hop=200ms
removed 5000 nodes, 22497 reattachments, repair 52 ms
after churn: maxDepth=9 avgDepth=4.94 forwarding=82.1% unplaced=0 latency@40ms/hop=360ms
```

`ConferenceFlowTest` runs the modules together: the host admits two people, hands out the epoch
key, the speaker streams through a relay that flips one byte, and the listener hears every frame
except the tampered one (which is counted).

## Known limits

- Relays learn the IP addresses of their tree neighbours; that is inherent to peer-to-peer.
- Traffic sizes and timing are visible to relays (padding may be added later).
- Latency grows with tree depth: about 300–600 ms speaker-to-audience at 100k.
- Repair after churn hangs orphaned subtrees under the shallowest free slot, so depth creeps up
  (5 → 9 after 5% churn); a periodic rebalance is still to do.
- Opus concealment of a lost packet is a fading repeat: FFmpeg's API can't call libopus' own
  concealment. A direct libopus binding would sound better on lossy links.
- Camera capture is implemented for all three OSes but has only been unit-tested without a real
  camera so far.
- Media sessions are numbered by the sender's clock. If that clock jumps backwards across a
  restart, receivers drop the restarted stream until they reconnect to that sender (keys and
  nonces still never repeat).
- A revocation or kick freezes the target's admin commands to the ones the revoker had seen; a
  legitimate command still in flight at that moment is dropped (safety over liveness).
- Epoch keys are wrapped per member (6.4 MB per rotation at 100k); an MLS tree (RFC 9420) would
  cut that, and is the planned upgrade.
- The conference node listens on UDP **7302** by default.
