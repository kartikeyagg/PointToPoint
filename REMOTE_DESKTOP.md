# Remote desktop (see and control the other computer)

The remote desktop service works like AnyDesk, but there is no server in the middle. It uses
the same direct, hole-punched UDP link as the chat (see [README.md](README.md)).

- **Host**: shares its screen. It shows a small always-on-top notice with an **Allow remote
  control** switch and a **Stop sharing** button.
- **Viewer**: sees the host's screen in a window that scales it to fit, and can use the mouse
  and keyboard on it when the host allows control.

It always runs **encrypted**. Both sides need the same passphrase, and without one it refuses to start.

## Quick start (one click)

```bash
./desktop.sh             # opens the launcher window
```

In the launcher:

1. Press **Find my address** and send the printed ip:port to the other person. IPv6 is the best
   option when both sides have it.
2. Pick **Share this screen** on the computer that will be controlled and **Control the other
   screen** on the other one.
3. Enter the other side's address and the **same passphrase** (at least 6 characters) on both sides.
4. Press **Start** on both sides within a few seconds of each other.

From the terminal:

```bash
# on the computer to be controlled
./desktop.sh host --peer '[2001:db8::1]' --key "our shared secret"
./desktop.sh host --peer 203.0.113.7:48720 --key "our shared secret" --view-only   # watch only

# on the controlling computer
./desktop.sh view --peer '[2001:db8::2]' --key "our shared secret"
```

`./desktop.sh …` is the same as `./run.sh --desktop …`. If the peer or the passphrase is missing, the launcher opens pre-filled instead.

| Option | Meaning | Default |
|---|---|---|
| `--desktop` | open the remote desktop launcher | |
| `--desktop host` | share this screen | |
| `--desktop view` | watch and control the peer's screen | |
| `--view-only` | host: the viewer can watch but not control (can be switched on later in the host window) | control allowed |
| `--peer`, `--peer-port`, `--port`, `--key`, `--no-stun` | same as for the chat | ports `7284` |

The remote desktop uses UDP port **7284** by default, so it can run next to the chat (7283).
Everything in the README's *Will it connect?* section applies here too.

## Safety

- The host sees the notice *"● Your screen is visible to …"* for as long as sharing is on.
  **Stop sharing**, or closing that window, ends it at once.
- Unticking **Allow remote control** blocks all mouse and keyboard input from the viewer
  straight away. The viewer's window then shows *View only*.
- Every packet is encrypted with a key that is new for each connection. Someone without the
  passphrase can neither watch nor send input. A recorded session also stays secret if the
  passphrase leaks later.

## How it works

```
host: capture screen (java.awt.Robot) every 100 ms
      → cut into 64×64 tiles → keep the tiles that changed
      → JPEG each tile → split into ≤1087-byte fragments → encrypted UDP datagrams
viewer: reassemble fragments → decode → draw into its copy of the screen → paint scaled
viewer input → MouseMove (datagram) / buttons, wheel, keys (reliable, in order) → host Robot
```

- **Screen updates are datagrams.** A lost fragment just means that tile waits. The host resends
  every tile at least every 3 s, and the viewer's **Refresh** button asks for a full frame.
  Old updates that arrive late never overwrite newer ones, because tiles carry a version number.
- **Input must not be lost**, so clicks, wheel and keys go over the link's reliable channel
  (acknowledged, retransmitted, in order). Pointer moves are datagrams, because only the
  latest position matters.
- **Bandwidth**: the host sends at most about 2 MB/s of tiles. Changes beyond that are sent in
  the next frames, so a busy screen gets less smooth but never floods the link.
- The viewer's coordinates are host-screen pixels. The window letterboxes the image and maps
  clicks back.

Messages are defined in `remote/RemoteProtocol.java`:

| Direction | Message | Channel |
|---|---|---|
| host → viewer | `ScreenInfo(width, height, tileSize, controlAllowed)` | reliable |
| host → viewer | `Tile(version, tileX, tileY, fragIndex, fragCount, jpegBytes)` | datagram |
| host → viewer | `ControlState(allowed)` | reliable |
| viewer → host | `ViewRequest(version)`, `Refresh` | reliable |
| viewer → host | `MouseMove(x, y)` | datagram |
| viewer → host | `MouseButton(x, y, button, pressed)`, `MouseWheel(x, y, rotation)`, `Key(vk, pressed)` | reliable |

## Limits

- Only the primary monitor is shared. There is no audio, clipboard or file transfer yet.
- Keys are sent as Java key codes, which covers letters, digits, arrows, function and modifier keys.
  Characters that need a different keyboard layout on the host may come out differently.
  System shortcuts such as Ctrl+Alt+Del are handled by the viewer's OS and are not forwarded.
- On Linux the host needs an X11 session. Wayland blocks screen capture and input injection
  through `java.awt.Robot`, so log in with "GNOME on Xorg" or similar. On macOS, grant the
  Java process *Screen Recording* and *Accessibility* permission.
- Both computers need Java 17+ and a display. A headless server cannot be a host.

| File | Role |
|---|---|
| `remote/DesktopApp` | starts host or viewer, wires the link (`LinkBridge`) |
| `remote/DesktopHost`, `TileEncoder` | capture loop, tile diff, JPEG + fragments, bandwidth budget, input replay |
| `remote/RobotScreen`, `RobotInput` | real screen capture and input injection via `java.awt.Robot` |
| `remote/DesktopViewer`, `TileAssembler` | fragment reassembly, version ordering, the viewer's copy of the screen |
| `remote/ViewerWindow`, `HostWindow`, `DesktopLauncher` | Swing windows |
| `remote/RemoteProtocol`, `Transport`, `ScreenSource`, `InputSink`, `HostSettings` | shared contracts |

## From an iPad or phone (browser, no Java needed)

Run on the computer to be controlled:

```bash
./run.sh --web --key "a long passphrase" --web-bind <tailscale-or-lan-address>
# --web [port] (default 7285), --view-only to watch only
```

Then open `http://<that address>:7285/` in Safari on the iPad and enter the passphrase.
One finger: tap = click, drag = drag, hold = right-click. Two fingers: scroll. A trackpad/mouse
works too. **Keyboard** opens the iPad keyboard; **Right-click** applies to your next tap.

Safety:
- It listens on `127.0.0.1` only unless you pass `--web-bind`; a passphrase of 6+ characters is required.
- The page is plain HTTP, so the passphrase and screen are not encrypted on the network. Reach it only
  over a private network (Tailscale/VPN) or an SSH tunnel, never an open port on the internet.
- Wrong passphrases are slowed down; on macOS grant Screen Recording and Accessibility to Java.
