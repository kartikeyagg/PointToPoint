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
| `--java-capture` | host on macOS: use Java's screen capture even when the native helper is built | native helper if present |
| `--desktop bench` | measure this computer's screen capture and encoding speed (see *Slow picture?* below) | |
| `--max-rate <MB/s>` | host: upload cap for screen updates. Lower it (e.g. `2`) when the host's upload is slow and the picture keeps missing tiles | `8` |
| `--peer`, `--peer-port`, `--port`, `--key`, `--no-stun` | same as for the chat | ports `7284` |

The remote desktop uses UDP port **7284** by default, so it can run next to the chat (7283).
Everything in the README's *Will it connect?* section applies here too.

## Fast screen capture (macOS and Linux)

Java's own screen capture (`java.awt.Robot`) is slow: about 60 ms per frame on a Mac and about
100 ms for a 1920x1080 Linux screen, which limits sharing to 8–15 frames/s. On Linux it also holds
up the mouse and keyboard that the viewer sends, so dragging lags. `./run.sh` and `./desktop.sh`
therefore also build a small native helper, `target/screenstream`, and the host uses it to run at
30 frames/s:

| Shared computer | Helper source | How it captures | Needs to build |
|---|---|---|---|
| macOS | `native/macos/ScreenStream.swift` | ScreenCaptureKit, sends only regions that changed | Xcode command-line tools (`xcode-select --install`) |
| Linux (X11) | `native/linux/screenstream.c` | X11 shared memory (MIT-SHM), sends only tiles that changed | a C compiler and X11 headers (`sudo apt install build-essential libx11-dev libxext-dev`) |

- Without the build tools, or if the helper fails, the host uses Java capture. Nothing else changes.
- macOS: the terminal app that runs it needs **Screen Recording** permission, the same as for Java capture.
- Linux: X11 sessions only. On Wayland the helper declines and Java capture is used.
- `./desktop.sh bench` prints `capture source: native helper` when the helper is in use.
- `--java-capture` turns the helper off. `P2P_SCREENSTREAM=/path/to/helper` points at a different build.

## Slow picture?

Run `./desktop.sh bench` on the computer that **shares** its screen. It connects to nobody and prints:

```
screen:            1920 x 1080
capture:           38.0 ms per frame
full-screen change: 24.0 ms to find, encode and send 131 tiles (220 KB)
=> about 16 frames/s while the whole screen changes (limit 20)
```

- **capture** is how long a screen grab takes: a few milliseconds with the native helper, often
  100 ms or more with `java.awt.Robot`. Everything else waits on it, so it sets the frame rate limit.
  If it says `Java Robot`, see *Fast screen capture* above.
- **full-screen change** is the host's own work for a dragged window or a scrolled page.
- Start the viewer with `P2P_STATS=1 ./desktop.sh view …` to print, every second, what arrives
  (KB/s, tiles drawn) and how many tiles stayed incomplete because a datagram was lost.
- If both are small but tiles keep arriving incomplete, the network is dropping datagrams: lower
  `--max-rate`. On Linux, `sudo sysctl -w net.core.rmem_max=8388608` also lets the app use its full
  4 MB receive buffer (the default limit is about 200 KB).

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
host: capture screen (native helper 30×/s, or java.awt.Robot 20×/s)
      → cut into 128×128 tiles → keep the tiles that changed
      → JPEG the changed tiles on all CPU cores → split into ≤1087-byte fragments → encrypted UDP datagrams
viewer: reassemble fragments → decode → draw into its copy of the screen → paint scaled
viewer input → MouseMove (datagram) / buttons, wheel, keys (reliable, in order) → host Robot
```

- **Screen updates are datagrams.** A lost fragment just means that tile waits. The host resends
  every tile at least every 3 s, and the viewer's **Refresh** button asks for a full frame.
  Old updates that arrive late never overwrite newer ones, because tiles carry a version number.
- **Input must not be lost**, so clicks, wheel and keys go over the link's reliable channel
  (acknowledged, retransmitted, in order). Pointer moves are datagrams, because only the
  latest position matters.
- **Bandwidth**: the host sends at most 8 MB/s of tiles by default (`--max-rate`). That fits a whole
  changed 1080p screen (about 220 KB) in one frame, so a dragged window arrives at once instead of
  in bands. Changes beyond the cap are sent in the next frames, so a busy screen gets less smooth
  but never floods the link.
- **Latency**: input is applied on the network thread as soon as it arrives. It never waits for an
  encode in progress. On a 1080p screen a full-screen change takes about 25 ms to encode. With
  `java.awt.Robot` the screen capture is the slowest step, and on Linux each mouse move also
  waits for the capture in progress (up to ~100 ms), which the native helper avoids.
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
| `remote/NativeScreen`, `native/macos`, `native/linux` | fast capture through the `screenstream` helper process |
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
