# Real-network tests

Unit tests (`mvn test`) cover the codec, encryption, STUN parsing and link behaviour on loopback.
This file records tests over the **real internet**, between machines on different networks.

## Results (2026-10-10)

| # | Peer A | Peer B | Path | Result |
|---|---|---|---|---|
| 1 | Linux PC, Airtel mobile hotspot, IPv6 `2001:4490:…` (no NAT) | Windows 11 PC, Airtel home broadband, IPv6 `2401:4900:…` (no NAT) | **IPv6** | ✅ **Connected, encrypted. Messages delivered both ways**, link held ~3 min on keep-alives |
| 2 | same Linux PC (hotspot), IPv4 symmetric NAT | same Windows PC (home), IPv4 symmetric NAT | IPv4 | ❌ Expected. Both NATs change ports per destination; impossible without a relay |
| 3 | Linux PC (home broadband), IPv4 symmetric NAT | GitHub Codespace (Azure SE Asia), IPv4 stable mapping `13.71.3.98:48720`, port-restricted | IPv4 | ❌ Expected. One symmetric side plus one port-restricted side cannot meet |

What each side's `--whoami` reported:

```
Linux PC (hotspot)   IPv4: NAT picks a new public port for every destination (ports 1619/1622)
                     IPv6: [2001:4490:dc8:4249:fab1:bba4:263c:1b6a]:7283 (no NAT)
Windows PC (home)    IPv4: NAT picks a new public port for every destination (ports 29211/16139)
                     IPv6: [2401:4900:8845:546f:3121:aa0f:974e:e567]:7283 (no NAT)
Codespace (Azure)    IPv4: 13.71.3.98:48720 (stable)
                     IPv6: none
```

Logs from test 1 (no relays, no tunnels; chat packets went directly between the two PCs):

```
Linux PC (A)                                              Windows PC (B)
* CONNECTING: punching towards [2401:4900:…:e567]:7283    * CONNECTING: punching towards [2001:4490:…:1b6a]:7283
* not delivered: hello from PC-A #1   (see fix below)     * not delivered: hello from PC-B #1   (see fix below)
* CONNECTED: … (encrypted)                                * CONNECTED: … (encrypted)
peer> hello from PC-B #2                                  peer> hello from PC-A #2
* DISCONNECTED: peer left …                               peer> hello from PC-A #3
                                                          * CLOSED: closed
```

On the Windows PC, the **Windows Defender Firewall prompt for Java** appeared during test 1. Inbound packets
were blocked until it was allowed, which is why the link came up only after both `#1` messages had expired.
**Allow Java on the network type in use (Public for hotspots)**, or the first run may not connect.

### Found and fixed by these tests

- **A message typed before the connection opened was lost.** Its 30 s retry budget ran out while the two
  sides were still punching. Retries are now counted only once connected. Earlier messages wait for up to
  5 minutes.
- **Both peers dialled port 7283, but NATs rarely keep the local port.** `--whoami`, the GUI button and the
  on-connect report now show the real public ip:port to share.
- **The CLI gave no delivery confirmation and printed no timestamps.** It now prints `delivered ✓ <text>`, and every line
  has `[HH:mm:ss]`, so logs from two machines can be lined up.

### Not tested

- IPv4 between two endpoints with *stable* NAT mappings (e.g. two typical home routers). This is the case
  STUN discovery is designed for. A Codespace ↔ Codespace run was set up but not completed.
- IPv6 on Codespaces: GitHub Codespaces have no IPv6. A Teredo tunnel would add one, but it relays traffic
  through a third party, so it is not a serverless test.

## How to repeat

On each machine (on **different** networks; check that `--whoami` shows different public addresses):

```bash
java -jar target/p2p-chat.jar --whoami
# exchange the IPv6 (preferred) or stable IPv4 address, then start both within ~1 minute:
java -jar target/p2p-chat.jar --cli --peer '[<their IPv6>]:7283' --key test
```
