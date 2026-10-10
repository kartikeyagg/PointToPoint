# PointToPoint — serverless P2P chat in Java

Two devices that know each other's IP address chat directly over the internet.
There is **no server, relay, or signalling service**: packets go straight from one device to the other.

- Pure Java 17, no runtime dependencies
- UDP hole punching, so it usually works even when both devices are behind home routers (NAT)
- Reliable delivery: messages are acknowledged, retransmitted, and de-duplicated (✓ when delivered)
- Optional end-to-end encryption: AES-256-GCM with a key derived from a shared passphrase
- Small Swing GUI, plus a terminal mode

## Quick start

```bash
./run.sh                       # builds on first run, opens the GUI
```

On **both** devices:

1. Find your **public** IP address (search "what is my ip" in a browser) and send it to the other person.
2. Open the app and set **Peer IP** to the other device's public IP. Leave both ports at `7283`.
   Optionally, set the same **Passphrase** on both devices.
3. Press **Connect** on both devices at roughly the same time (within ~20 s of each other).
4. When the status changes to `CONNECTED`, type a message and press Enter.

Terminal mode:

```bash
./run.sh --cli --peer 203.0.113.7 --key "our shared secret"
# type a line + Enter to send, /quit to exit
```

Or run the jar directly with Java 17 or newer: `mvn package && java -jar target/p2p-chat.jar --help`.

| Option | Meaning | Default |
|---|---|---|
| `--peer <ip\|host>` | other device's public IP | (asked in GUI) |
| `--peer-port <n>` | other device's UDP port | `7283` |
| `--port <n>` | UDP port to listen on locally | `7283` |
| `--key <passphrase>` | shared secret; turns on encryption | none (plain text) |
| `--cli` | terminal instead of GUI | GUI |

## How it works without a server

```
 Device A (behind NAT A)                         Device B (behind NAT B)
 sends HELLO to B_public:7283  ──────────╳       (NAT B drops it: no mapping yet)
                                  ╳──────────── sends HELLO to A_public:7283
   NAT A now has a mapping for B                  NAT B now has a mapping for A
 sends HELLO again ─────────────────────────────▶ received → CONNECTED
              ◀───────────────────────────────── PING     → CONNECTED
```

Each side keeps sending `HELLO` probes from its fixed UDP port to the other side's address.
An outgoing probe makes a router let replies from that address back in. Once both routers
have such a mapping, traffic flows directly. After that, a `PING` every 5 s keeps the mapping open,
and if nothing is heard for 20 s the link drops back to punching until the peer returns.

Chat messages (`MSG`) carry a sequence number. The receiver always answers with an `ACK` and
ignores duplicates. The sender retransmits every second, for up to 30 s, until it receives the `ACK`.
Packets from any IP other than the configured peer are ignored.

Wire format: `magic "P2" | version | type | session(8) | seq(8) | payload`. With a passphrase,
the whole frame is sealed with AES-256-GCM (random 12-byte nonce, PBKDF2-SHA256 key, 200k iterations).
A wrong passphrase means packets are dropped and the GUI shows a warning.

## When it can't connect

Hole punching works with most home routers. It cannot get through:

- **Symmetric NAT / carrier-grade NAT (CGNAT)**, which is common on mobile data and some ISPs.
  This is the case if your router's WAN IP differs from your "what is my ip" address.
- Firewalls that block inbound UDP entirely.

To fix it, choose one:

1. **Port forwarding** (most reliable): on one device's router, forward **UDP 7283** to that device's LAN IP.
   That device becomes directly reachable, and the other device just points at it.
2. Use a public IPv6 address on both ends (`--peer` accepts IPv6). There is usually no NAT, but you may need to allow UDP 7283 in the firewall.
3. Use different ports if 7283 is busy or remapped: `--port` sets your side and `--peer-port` sets theirs.
   If the peer's router changes the port, the app learns the real port from the first packet it receives.

The GUI shows your local (LAN) IPs. Use those only when both devices are on the same network.

## Development

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 mvn test   # 13 tests: codec, crypto, link behaviour, options
```

| File | Role |
|---|---|
| `PeerLink` | socket, hole punching, keep-alive, ACK/retransmit, de-dup |
| `PacketCodec` / `Packet` | wire format and optional encryption |
| `ChatWindow` | Swing GUI |
| `ConsoleChat` | terminal client |
| `Options` / `Main` | argument parsing and entry point |
