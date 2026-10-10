# PointToPoint — serverless P2P chat in Java

Two devices that know each other's address chat directly over the internet.
Chat packets go **straight from one device to the other**. There is no chat server, relay or account.

- Pure Java 17, no runtime dependencies
- UDP hole punching over **IPv4 and IPv6**
- Optional **public address lookup** (STUN) that tells you the exact ip:port to give your peer
- Reliable delivery: messages are acknowledged, retransmitted and de-duplicated (✓ when delivered)
- Optional end-to-end encryption: AES-256-GCM with a key derived from a shared passphrase
- Small Swing GUI, plus a terminal mode

## Quick start

```bash
./run.sh                 # builds on first run, opens the GUI
./run.sh --whoami        # terminal only: print this device's public address(es)
```

On **both** devices:

1. Press **My public address** (or run `./run.sh --whoami`). The app prints something like:
   ```
   IPv4: Your public address is 203.0.113.10:48720. Give it to your peer (ip AND port).
   IPv6: Your address is [2001:db8:8845:546f:2b1d:b2f9:1cdd:cdc8]:7283 (no NAT). Give it to your peer; ...
   ```
2. Send that **ip:port** to the other person, preferring **IPv6** if both of you have it.
   The port matters: behind most routers your public port is *not* 7283.
3. Paste the other person's address into **Peer address** (`1.2.3.4:5000` or `[2001:db8::1]:7283`).
   Optionally, set the same **Passphrase** on both devices.
4. Press **Connect** on both devices at roughly the same time (within ~20 s of each other).
5. When the status changes to `CONNECTED`, type a message and press Enter.

Terminal mode:

```bash
./run.sh --cli --peer '[2001:db8::1]:7283' --key "our shared secret"
./run.sh --cli --peer 203.0.113.7:48720  --key "our shared secret"
# type a line + Enter to send, /quit to exit
```

Or run the jar directly with Java 17 or newer: `mvn package && java -jar target/p2p-chat.jar --help`.

| Option | Meaning | Default |
|---|---|---|
| `--peer <addr>` | other device's **public** address: `1.2.3.4`, `1.2.3.4:5000`, `2001:db8::1`, `[2001:db8::1]:5000` | (asked in GUI) |
| `--peer-port <n>` | other device's public UDP port (if not given in `--peer`) | `7283` |
| `--port <n>` | UDP port to use locally | `7283` |
| `--key <passphrase>` | shared secret; turns on encryption | none (plain text) |
| `--whoami` | print this device's public IPv4/IPv6 address and exit | |
| `--no-stun` | don't look up the public address when connecting | lookup on |
| `--cli` | terminal instead of GUI | GUI |

## Will it connect? Read your `--whoami` result

| Your result | Meaning | Connects when |
|---|---|---|
| **IPv6: … (no NAT)** | Your IPv6 address is public; the port stays 7283 | The peer also has IPv6. **This is the best option.** |
| **IPv4: public address is x:y** | Your router keeps one public port for all destinations (most home routers, cloud VMs) | The peer gives you *its* public ip:port too, and its result is also this row, "no NAT", or a forwarded port |
| **IPv4: NAT picks a new public port for every destination** | Symmetric / carrier-grade NAT (common on mobile data and some ISPs) | Only if the **peer is directly reachable**: IPv6, a public IP, or a forwarded port. Two of these can never connect without a relay. |
| **Could not reach any STUN server** | UDP is blocked on this network | Not over this network |

Fixes when it doesn't connect:

1. **Use IPv6.** Most phones on mobile data and many home ISPs have it. Run `--whoami` on both sides.
2. **Port forwarding.** On one router, forward UDP `7283` to that device's LAN IP.
   That device becomes directly reachable at `<public-ip>:7283`, and the other side can be behind any NAT.
3. **Windows:** allow Java when Windows Defender Firewall asks. Inbound packets are blocked until then.
4. If a port is busy, use different ports: `--port` sets your side and the peer uses your `--whoami` result.

## How it works without a chat server

```
 Device A (behind NAT A)                         Device B (behind NAT B)
 sends HELLO to B_public  ───────────────╳       (NAT B drops it: no mapping yet)
                                  ╳──────────── sends HELLO to A_public
   NAT A now has a mapping for B                  NAT B now has a mapping for A
 sends HELLO again ─────────────────────────────▶ received → CONNECTED
              ◀───────────────────────────────── PING     → CONNECTED
```

Each side keeps sending `HELLO` probes from its UDP port to the other side's **public** address.
An outgoing probe makes the router or firewall let replies from that address back in. Once both have
such a mapping, traffic flows directly. If the peer's packets arrive from a different port than the one
dialled, the app switches to that port. After that, a `PING` every 5 s keeps the mapping open, and if
nothing is heard for 20 s the link drops back to punching until the peer returns.

**The public address lookup (STUN)** sends one tiny UDP request to a public STUN server
(`stun.l.google.com:19302` and `stun.cloudflare.com:3478`). The server only replies with the ip:port it
saw, so this is the UDP equivalent of "what is my ip". It uses the same socket the chat will use, so the
reported port is the real one. Two servers on different ports reveal whether your NAT changes ports per
destination. No chat data ever goes to them. Use `--no-stun` (or untick *STUN lookup*) to skip it.

Chat messages (`MSG`) carry a sequence number. The receiver always answers with an `ACK` and
ignores duplicates. The sender retransmits every second, for up to 30 s, until it receives the `ACK`.
Packets from any address other than the peer's are ignored. For IPv6 the peer's whole `/64` is accepted,
because privacy extensions make hosts send from rotating temporary addresses.

Wire format: `magic "P2" | version | type | session(8) | seq(8) | payload`. With a passphrase,
the whole frame is sealed with AES-256-GCM (random 12-byte nonce, PBKDF2-SHA256 key, 200k iterations).
A wrong passphrase means packets are dropped and the app shows a warning.

## Tested over the internet

See [TESTING.md](TESTING.md) for the real-network tests and their results.

## Development

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 mvn test   # codec, crypto, STUN parsing, link behaviour, options
```

| File | Role |
|---|---|
| `PeerLink` | socket, hole punching, keep-alive, ACK/retransmit, de-dup, STUN on the chat socket |
| `StunClient` | public-address lookup (RFC 5389, IPv4 + IPv6) and NAT classification |
| `PacketCodec` / `Packet` | wire format and optional encryption |
| `ChatWindow` | Swing GUI |
| `ConsoleChat` | terminal client and `--whoami` |
| `Options` / `Main` | argument parsing and entry point |
