# ByteBlock

An in-game Java computer simulator for Minecraft 1.21.1 (NeoForge 21.1.215, Java 21).

ByteBlock adds programmable **Computer** blocks with a desktop-style OS, a **Bluetooth**-style wireless
networking layer, and a small ecosystem of peripheral blocks — scanners, monitors, redstone relays,
button panels, disks, and more — so you can build everything from a simple lamp switchboard to an
automated factory controller without leaving the game.

## Features

- **Computer block** — right-click to open a fullscreen terminal GUI with a desktop, start menu,
  multi-window app switcher, filesystem, and runnable programs.
- **Bluetooth network** — every device (computer, scanner, relay, button panel, monitor) registers on
  a shared in-world network so programs can discover and message each other by UUID + channel.
- **Redstone Relay** — 6-sided configurable redstone I/O block. Each side can be set to input or
  output for either analog redstone or 16-color bundled-cable signals, all driven from Lua-style
  programs on the computer.
- **Button Panel** — 16-button slab block with per-button mode (TOGGLE / MOMENTARY / TIMER / DELAY /
  INVERTED), label, color, and emissive glow renderer that literally emits world light.
- **Virtual Button Panel on the Computer** — every Computer block also acts as its own 16-button
  panel. The on-screen Button App can drive it, it emits redstone + bundled signals on all 6 sides of
  the computer, and programs can drive it in code via `ButtonsLib`.
- **Scanner, Monitor, Disk Drive, GPS Tool, Glasses** — supporting peripherals.
- **Project Red bundled-cable compatibility** — optional interop when Project Red is installed.

## Three ways to control redstone

1. **Physical** — place a Button Panel block, right-click to toggle buttons, route wires/bundled
   cables to your contraption.
2. **GUI** — open the Button App on a computer terminal; it auto-discovers every panel on the network
   (including the local computer itself) and lets you configure modes/labels/colors remotely.
3. **Programmatic** — write a program that calls `ButtonsLib.setButton(os, 0, true)` or
   `RedstoneLib.setOutput(os, side, 15)` to drive signals directly.

## Blocks & Items — block-by-block reference

Every device registers itself on the in-world **Bluetooth network** so programs can discover it
by `DeviceType` and message it on a numbered channel. Standard blocks have a 15-block range; the
items below note the specific range/role for each.

### Computer

The brain of the system. Every other block is useless without one.

| Property            | Value |
|---------------------|-------|
| Block entity        | `ComputerBlockEntity` |
| BT device type      | `COMPUTER` (range 15) + virtual `BUTTON_PANEL` |
| Block state         | `FACING` (horizontal), `CONNECTED` |

- **Right-click** — opens the fullscreen terminal `ComputerScreen`. Auto-reboots if shut down.
- **Filesystem** — JavaOS persists the in-memory tree to NBT; saves are debounced and only flush
  when the OS marks the FS dirty.
- **Bluetooth** — registers as `COMPUTER` on `os.getBluetoothChannel()` and additionally as a
  `BUTTON_PANEL` on its panel channel so the on-screen Button App discovers it as a device.
- **Redstone & bundled output** — `isSignalSource = true`. The 16 virtual buttons drive an analog
  signal `min(15, popcount(active))` on all 6 sides, plus a 16-color bundled mask. `INVERTED`
  buttons flip their bit before the mask is computed. All outputs are cleared on block removal.
- **Programs** — drive the virtual panel from Lua via `ButtonsLib.setButton(os, i, true)`; drive
  external relays/panels via `RedstoneLib.setOutput(os, side, 0..15)`.

### Button Panel

A thin 14×14 px slab with a 4×4 grid of dye-colored buttons. Mounts on **walls, floors, or
ceilings**.

| Property            | Value |
|---------------------|-------|
| Block entity        | `ButtonPanelBlockEntity` |
| BT device type      | `BUTTON_PANEL` (unlimited range) |
| Block state         | `FACING` (full 6-direction) |
| Light emission      | `0` if all off, otherwise `min(15, 6 + activeCount)` (real world light) |

- **Click a button** — toggles it (mode-aware: TOGGLE / MOMENTARY / TIMER / DELAY / INVERTED).
- **Sneak + click a button** — opens the per-button config GUI (label, color, mode, duration).
- **Click the slab margin** — does nothing (intentional).
- **Bluetooth events** — broadcasts `button_press:<i>:<0|1>:<colorName>` to its channel on every
  state change. Accepts inbound `set_button:<i>:<0|1>` and `set_buttons:<mask>` to drive remotely.
- **Per-button modes** — TOGGLE flips state, MOMENTARY pulses on for 4t then off, TIMER stays
  on for `durations[i]` ticks then off, DELAY waits then toggles, INVERTED is TOGGLE with the
  redstone bit flipped on output.
- **Redstone & bundled output** — analog `popcount(effectiveMask)` clamped to 0–15 on all 6 sides;
  16-bit bundled mask (one bit per dye color) on all 6 sides.
- **Persistence** — NBT stores per-button mode, duration, label (≤16 chars), color override
  (`0xRRGGBB`, `-1` = default wool), panel label (≤24 chars), channel, and the 16-bit state mask.

### Redstone Relay

The bridge between programs and the physical redstone world. Each side is independently
configurable.

| Property            | Value |
|---------------------|-------|
| Block entity        | `RedstoneRelayBlockEntity` |
| BT device type      | `REDSTONE_RELAY` (range 15) |
| Block state         | `FACING` (horizontal), `CONNECTED` |

- **Right-click** — chat status: per-side `out=`, `in=`, `ch=`, `bundled` flags.
- **Sneak + click** — opens the side-config GUI (`RedstoneRelayScreen`).
- **Per-side configuration** — each of the 6 faces has its own BT channel, plus a *bundled* toggle.
  When bundled, channels 1–16 map to the 16 colors of bundled cable; when not bundled, the face
  emits a plain analog signal driven from the assigned channel.
- **Inputs** — re-reads world redstone on neighbor change or every 4 ticks; broadcasts
  `redstone_changed:<d>:<u>:<n>:<s>:<w>:<e>` when any side changes.
- **Inbound commands** — `rs_set:<side>:<power>` sets analog output;
  `rs_bundled:<side>:<colorMask>` sets bundled output.
- **Project Red** — bundled outputs interop with Project Red bundled cables when the mod is
  installed (see `compat/`).
- **Cleanup** — clears its slot in the redstone/bundled output cache when the block is broken.

### Monitor

A flat, multi-block screen that mirrors a linked computer or runs a test pattern.

| Property            | Value |
|---------------------|-------|
| Block entity        | `MonitorBlockEntity` |
| BT device type      | `MONITOR` (range 15) |
| Block state         | `FACING` (horizontal only — wall mounted) |
| Shape               | 4-pixel-thick slab flush with the wall behind it |

- **Right-click** — if a computer is auto-linked, opens that computer's `ComputerScreen` (rebooting
  if needed).
- **Multi-block formation** — placing/breaking a Monitor flood-fills connected same-facing
  Monitors, validates that they form a rectangle, and assigns one origin + per-monitor
  `(offsetX, offsetY)` so the renderer can stitch them into a single screen up to 10×10.
- **Auto-link** — every 40 ticks the origin checks all formation members' adjacent blocks for an
  adjacent `ComputerBlockEntity` and links it.
- **Bluetooth control** — accepts `display_mode:mirror`, `display_mode:test:<pattern>`, and
  `link:x,y,z` messages from programs.

### Drive

A reel-to-reel tape drive for storing programs and data on physical disks.

| Property            | Value |
|---------------------|-------|
| Block entity        | `DriveBlockEntity` |
| BT device type      | `DRIVE` (range 15) |
| Block state         | `FACING` (horizontal), `CONNECTED` |

- **Right-click** — opens the inventory menu; insert a Disk item to mount it.
- **Disk operations** — programs read/write files on the mounted disk; the GUI also lets you rename
  the disk via the C2S `RenameDiskPayload` packet.
- **Drop on break** — inventory contents (any inserted disk) drop on block removal.

### Printer

Prints program-generated text onto paper, books, or Create's clipboard.

| Property            | Value |
|---------------------|-------|
| Block entity        | `PrinterBlockEntity` |
| BT device type      | `PRINTER` (range 15) |
| Block state         | `FACING` (horizontal), `CONNECTED` |
| Slots               | 0 = input media, 1 = output (printed item) |

- **Right-click** — opens the 2-slot printer menu.
- **Valid media** — `minecraft:paper`, `minecraft:writable_book`, `create:clipboard`.
- **Print queue** — programs call `printer.queuePrint(title, content)` (or send equivalent via
  Bluetooth); each tick the printer pops one job, consumes one media item, and writes the printed
  item to slot 1 (only if slot 1 is empty).

### Scanner (LiDAR)

Scans the surrounding world into a persistent block/entity cache that programs can query.

| Property            | Value |
|---------------------|-------|
| Block entity        | `ScannerBlockEntity` |
| BT device type      | `SCANNER` (range 15) |
| Block state         | `CONNECTED` |
| Default radius      | 48 blocks (configurable 1–128) |

- **Right-click** — chat status: scan progress %, total blocks scanned, last entity count, radius.
- **Incremental block scan** — 2 chunk sections per tick to avoid lag. Auto-rescans every 30s.
- **Entity scan** — every 20 ticks (1s).
- **Immediate scan** — Lua `scanner.scan(radius≤16)` does a synchronous full scan in one tick for
  short range queries.
- **Programs** — `WorldScanData` exposes block/entity snapshots; used by drone/robot pathfinding.

### GPS

Place 3+ GPS blocks at known coordinates so computers can triangulate their position.

| Property            | Value |
|---------------------|-------|
| Block entity        | `GpsBlockEntity` |
| BT device type      | `GPS` (unlimited range) |
| Block state         | `CONNECTED` |

- **Right-click** — chat shows the block's broadcast position.
- **Triangulation** — programs use 3+ GPS responses to solve their own coordinates.

### Charging Station

Slab-height pad that charges Robots and Drones standing within 3 blocks.

| Property            | Value |
|---------------------|-------|
| Block entity        | `ChargingStationBlockEntity` |
| BT device type      | `CHARGING_STATION` (range 15) |
| Block state         | `CONNECTED` |
| Energy buffer       | 100 000 FE; in 1000 FE/t, out 200 FE/t per entity |
| Range               | 3 blocks |

- **Right-click** — chat shows current FE / max FE.
- **Robots** — receive FE directly into their `EnergyStorage`.
- **Drones** — `1 FE = 2 fuel ticks`, capped at 72000 fuel ticks.
- **FE input** — accepts up to 1000 FE/t from adjacent FE pipes/cables.

### Universal Peripheral

Place adjacent to any block (chest, furnace, etc.) to expose that block's capabilities to nearby
computers.

| Property            | Value |
|---------------------|-------|
| Block entity        | `PeripheralBlockEntity` |
| BT device type      | `PERIPHERAL` (range 15) |
| Block state         | `CONNECTED` |

- **Right-click** — chat reports the auto-detected peripheral type of the bound block.

### ByteChest

A 27-slot Bluetooth-enabled chest with a status LED.

| Property            | Value |
|---------------------|-------|
| Block entity        | `ByteChestBlockEntity` |
| BT device type      | `BYTE_CHEST` (range 15) |
| Block state         | `FACING` (horizontal, latch faces player), `CONNECTED` |

- **Right-click** — opens the 27-slot chest menu.
- **Drop on break** — full inventory drops as items.
- **LED** — turns blue (`CONNECTED = true`) when a computer is in range.

### GPS Tool (item)

Right-click and Shift+Scroll to program drones and robots with waypoints, routes, areas, or paths.

| Mode      | What it stores                  | How to set                                         |
|-----------|---------------------------------|----------------------------------------------------|
| WAYPOINT  | Single point `A`                | Right-click a block.                               |
| ROUTE     | Source `A` + destination `B`    | Right-click two chests/blocks in sequence.         |
| AREA      | Two AABB corners `A` + `B`      | Right-click two opposite corners.                  |
| PATH      | Ordered list of waypoints       | Right-click each block in sequence.                |

- **Shift + Scroll** — cycles mode.
- **Shift + Right-click drone/robot** — applies the stored data to that entity.
- **Shift + Right-click air** — clears the current mode's stored data.

### Smart Glasses (item)

Head-slot wearable that renders server-pushed HUD widgets generated by the `glasses.*` Lua API.

- **Default channel** — `1` (NBT key `BtChannel`, range 0–255).
- **Shift + Right-click** — cycles channel `(current + 1) & 0xFF`.
- **Equip** — head slot.
- **HUD** — `GlassesHudOverlay` shows widgets when worn; falls back to a "no signal" placeholder
  when no widgets are received. Computers push widgets via `glasses.flush()` which routes through
  the C2S `GlassesPushRequestPayload` and broadcasts to all wearers in BT range on the matching
  channel.

## Lua API

ByteBlock ships a Lua 5.2 sandbox (LuaJ) wired to a CC:Tweaked-style event loop
(`os.pullEvent` / `os.queueEvent` / `os.startTimer`). Most existing CC programs run with
little or no modification.

### Globals & APIs

| API | Highlights |
|-----|-----------|
| `term`        | `write`, `print`, `clear`, `clearLine`, `setCursorPos`, `getCursorPos`, `getSize`, `setTextColor[/our]`, `setBackgroundColor[/our]`, `getTextColor[/our]`, `getBackgroundColor[/our]`, `isColor[/our]`, `scroll`, `blit`, `redirect`, `current`, `native`, `getPaletteColor[/our]`, `setPaletteColor[/our]`, `nativePaletteColor[/our]` |
| `fs`          | `exists`, `isDir`, `isReadOnly`, `list`, `open`, `read`, `write`, `delete`, `move`, `copy`, `makeDir`, `getName`, `getDir`, `getSize`, `combine`, `attributes`, `complete`, `find`, `getDrive`, `getCapacity`, `getFreeSpace` |
| `os`          | `clock`, `time`, `day`, `epoch`, `date`, `getComputerID`/`computerID`, `getComputerLabel`/`computerLabel`, `setComputerLabel`, `startTimer`, `cancelTimer`, `sleep`, `pullEvent`, `pullEventRaw`, `queueEvent`, `shutdown`, `reboot`, `version`, `about` |
| `colors` / `colours` | All 16 CC color constants + `combine`, `subtract`, `test`, `packRGB`, `unpackRGB`, `toBlit` |
| `shell`       | `run`, `exit`, `dir`/`getDir`/`setDir`, `path`, `setPath`, `resolve`, `complete`, `programs`, `aliases`, `setAlias`, `clearAlias` |
| `textutils`   | `serialize`/`unserialize` (+ British spellings), `serializeJSON`/`unserializeJSON`, `tabulate`, `pagedTabulate`, `pagedPrint`, `slowWrite`, `slowPrint`, `formatTime`, `urlEncode` |
| `peripheral`  | `isPresent`, `getType`, `getMethods`, `call`, `wrap`, `find`, `getNames` (returns BT devices in range) |
| `parallel`    | `waitForAny`, `waitForAll` (CC-compatible coroutine scheduler) |
| `vector`      | `new(x,y,z)` with `+ - * / dot cross length normalize round tostring` metatable methods |
| `keys`        | All 100+ CC key constants (`keys.enter`, `keys.space`, `keys.f1`–`keys.f12`, `keys.leftCtrl`, …) plus `keys.getName(code)` |
| `paintutils`  | `loadImage`, `drawImage`, `drawPixel`, `drawLine`, `drawBox`, `drawFilledBox`, `drawCircle`/`drawFilledCircle`, `drawTriangle`/`drawFilledTriangle`, `drawPolygon`, `drawSprite` (with transparency) |
| `image`       | Procedural RGB images: `create(w,h,color)`, `set/get`, `fill`, `loadNFP`, `loadNFT`, `loadPNG(path[, maxW, maxH])` (PNG/JPEG/GIF/BMP via ImageIO), `savePNG(image, path)`, `toGrid` (quantize → CC palette indices for `paintutils.drawImage`) |
| `window`      | `create(parent, x, y, w, h, visible)` returning a redirect-capable child terminal |
| `settings`    | `define`, `set`, `get`, `unset`, `clear`, `getNames`, `load`, `save` (persists to `/.settings`) |
| `http`        | `get`, `post`, `request`, `checkURL`, `websocket` (HTTPS allowed by default) |
| `pastebin`    | `get`, `put`, `run` (delegates to https://pastebin.com/raw) |
| `gist`        | GitHub Gists: `get(id [, file])`, `download(id, path [, file])`, `run(id, ...)`, `put(path [, name [, public]])` (write needs `settings.set("gist.token", "<PAT>")`) |
| `wget`, `read`, `help`, `multishell` | Globals matching CC behavior. `multishell` is a single-tab stub. |

### ByteBlock-only APIs

These have no CC counterpart and expose ByteBlock's hardware:

| API | What it does |
|-----|--------------|
| `bluetooth` | Direct device discovery, send/recv on raw BT channels, GPS registration. |
| `rednet`    | CC-compatible message API layered on `bluetooth`. |
| `redstone` / `rs` | I/O for the **Redstone Relay** block (per-side power, bundled cable colors). |
| `relay`     | Lower-level Redstone Relay control (mode, channel, latch). |
| `buttons`   | Read 12-button **Button Panel** state, configure per-button labels & colors. |
| `gps`       | Locate via **GPS Tool** waypoints/routes/areas/paths, or query nearby GPS beacons. |
| `scanner`   | World-data scan (block IDs, biome, light, heightmap) within a radius using a **Scanner** block. |
| `robot`     | Drive a robot entity: `forward`, `dig`, `place`, `select`, `refuel`, inventory inspection, `getFuel`, `getFacing`, `findCharger`, `goHome`, etc. |
| `turtle`    | Alias of `robot` plus CC-compatible shims (`attack*`, `place*`, `compare*`, `inspect*`, `transferTo`, `getItemSpace`). |
| `drone`     | Issue commands to a linked drone entity (route, hover, follow, return) plus `findCharger` / `goHome` to auto-dock at the nearest **Charging Station**. |
| `glasses`   | Build a HUD widget list (`text`, `box`, `progress`, `image`, `pie`, `compass`, `minimap`, `graph`) **plus** `glasses.canvas()` for true-color free-form 2D rendering at absolute screen coords (`pixel`, `line`, `rect`, `circle`, `triangle`, `poly`, `text`, `gradient`, `bezier`, `image`). |
| `disk`      | CC-compatible API surface backed by adjacent **Drive** blocks (mount path, label, eject). |

### Java REPL (BeanShell)

In addition to Lua, ByteBlock terminals can run **Java syntax** through a sandboxed BeanShell 2.0 interpreter:

```
> java
java> for (int i = 0; i < 5; i++) println("hello " + i);
java> term.setTextColor(4); term.write("yellow!");
java> lua("robot.forward(); robot.dig()");
java> lua("glasses.canvas():circle(50,50,20,0xFF0000):add(); glasses.flush()");
java> exit
```

Shell commands:

| Command | What it does |
|---------|--------------|
| `java`            | Open interactive Java REPL |
| `java <file.bsh>` | Open REPL pre-running a script file |
| `javarun <file.bsh>` | Same as `run <file.bsh>` — execute a Java/BeanShell script |
| `run <file.bsh>`  | Auto-detected by extension (`.lua` → Lua, `.bsh`/`.java` → Java) |

In-script helpers: `print(x)`, `println(x)`, `sleep(seconds)`, `term.*`, `os`, plus `lua("…")` which delegates to a shared `LuaRuntime` so you can drive every ByteBlock API (`robot`, `drone`, `glasses`, `bluetooth`, `redstone`, …) from Java.

**Sandbox blocks:** `java.io.File*`, `java.lang.Runtime`, `ProcessBuilder`, `java.lang.reflect.*`, `java.lang.invoke.*`, `ClassLoader`, `SecurityManager`, `Thread`/`ThreadGroup`, `java.net.*`, `java.nio.file.*`, `java.security.*`, `javax.script.*`, `javax.tools.*`, `sun.*`, `jdk.internal.*`, `com.sun.*`, plus Minecraft/NeoForge internals.

**Watchdog:** scripts are interrupted after 5000 ms by default (configurable per OS instance via `JavaRuntime.scriptTimeoutMs`).

### App store (`apt`)

Bundled programs ship inside the mod jar. Install them into the in-game filesystem with:

```
> apt list
> apt install snake
> snake               # auto-runs /Users/User/snake.lua
```

| Program          | What it does |
|------------------|--------------|
| `clock`          | Animated analog + digital clock |
| `snake`          | Classic snake game (arrows / WASD) |
| `weather`        | Pulls current weather from open-meteo and posts to glasses |
| `power-monitor`  | Glasses-HUD power gauge polling a `peripheral.find("energy")` |
| `paint`          | Click-and-drag paint app, exports `.nfp` files |

History for `Shell`, `Lua`, and `Java` REPLs is now persisted to `/.shell_history`, `/.lua_history`, and `/.java_history` in the in-game filesystem.

### Bundled Lua libraries

These ship inside the mod jar and are auto-registered with `package.preload`, so they Just Work via `require`:

| Module     | What it gives you |
|------------|-------------------|
| `basalt`   | Basalt-style UI framework — `Frame`, `Label`, `Button`, `TextBox`, `List`, `Checkbox`, `ProgressBar`, hover/click/focus, event loop. Drop-in for terminals. |
| `pine3d`   | Pine3D-style 3D wireframe renderer — projects `cube`/`pyramid`/custom meshes onto a `glasses.canvas()`, supports per-object & camera rotation, FOV. |

Example:

```lua
local basalt = require("basalt")
local main = basalt.createFrame()
main:addLabel():setPosition(2,1):setText("Hello!")
main:addButton():setPosition(2,3):setSize(10,1):setText("Quit")
     :onClick(function() basalt.stop() end)
basalt.run()
```

```lua
local pine3d = require("pine3d")
local cam  = pine3d.newCamera({ x=0, y=0, z=-6 })
local cube = pine3d.cube(2, 0xFF8800)
while true do
  cube.ry = (cube.ry or 0) + 0.05
  local c = glasses.canvas("cube"); c:clear()
  pine3d.render(c, cam, { cube }, 200, 100, 160, 120)
  c:add(); glasses.flush(); os.sleep(0.05)
end
```

### CC:Tweaked parity

| Feature                              | Status | Notes |
|--------------------------------------|--------|-------|
| `term.*` core (write/clear/cursor/colors) | ✅    | 80×25 buffer, 16-color palette. |
| `term.blit`                          | ✅    | Per-character fg/bg from hex digits. |
| `term.setPaletteColor`               | ⚠️    | Returns CC defaults; runtime palette mutation is not stored. |
| `term.redirect` / `current` / `native` | ⚠️  | Single terminal context — returns the same `term` table. |
| `fs.*` core                          | ✅    | Full read/write/list/move/copy/mkdir/delete. |
| `fs.combine` / `attributes` / `complete` / `find` | ✅ | Path math, metadata, completion, wildcards. |
| `fs.getDrive` / `getCapacity` / `getFreeSpace` | ⚠️ | Returns sensible defaults; storage is unbounded. |
| `os.*` (timers/events/labels/IDs)    | ✅    | Includes `cancelTimer`, `epoch`, `day`, `date`. |
| `colors` / `colours`                 | ✅    | All constants + helpers. |
| `shell` core                         | ✅    | `run`, `exit`, `dir`, `path`, `resolve`. |
| `shell.complete` / `programs` / `aliases` | ⚠️ | Stubs returning empty tables (CC-compatible signatures). |
| `textutils`                          | ✅    | Serialization, JSON, tabulate, paged output. |
| `parallel`                           | ✅    | `waitForAny` / `waitForAll`. |
| `vector` / `keys` / `paintutils` / `window` / `settings` | ✅ | Behave per CC spec. |
| `peripheral.wrap` / `find` / `getNames` | ⚠️ | Returns ByteBlock BT devices; method tables match the device's capabilities. |
| `http` (get/post/websocket)          | ✅    | Enabled by default — no `http_enable` opt-in needed. |
| `pastebin` (get/put/run)             | ✅    | Uses the public pastebin.com endpoints. |
| `turtle.*` (movement, dig, place, drop, suck, refuel, inventory) | ✅ | Maps to robot commands; needs a linked **Robot Entity**. |
| `turtle.craft` / `equipLeft` / `equipRight` | ❌ | No crafting/equipment slot in robot inventory yet. |
| `disk.*` (drive interop)             | ⚠️    | Methods present; backing **Drive** block integration is partial. |
| `commands` API (creative-only)       | ❌    | Not implemented. |
| `redstone.*`                         | ➕    | ByteBlock variant: targets the **Redstone Relay** block, supports bundled cables. |
| `bluetooth` / `rednet` / `gps` / `scanner` / `robot` / `drone` / `glasses` / `relay` / `buttons` | ➕ | ByteBlock-exclusive hardware APIs. |

Legend: ✅ implemented, ⚠️ partial / stubbed but CC-compatible, ❌ not implemented, ➕ ByteBlock-only.

## Build

Requires JDK 21.

```powershell
Push-Location F:\JavaCraft
.\gradlew.bat compileJava --no-configuration-cache
```

Run the client in a dev environment:

```powershell
.\gradlew.bat runClient --no-configuration-cache
```

> `--no-configuration-cache` is mandatory — Gradle's configuration cache can silently skip
> recompilation of edited sources.

## Project layout

```
src/main/java/com/apocscode/byteblock/
├── block/                  # Block classes (ComputerBlock, ButtonPanelBlock, …)
│   └── entity/             # BlockEntities (state + NBT persistence + client sync)
├── client/                 # Renderers and GUI screens
├── compat/                 # Optional mod interop (Project Red bundled cables)
├── computer/               # JavaOS core + program API libraries
│   └── programs/           # In-game apps (Button App, Bluetooth, Notepad, Monitor, …)
├── init/                   # DeferredRegister / registration
├── item/                   # Items (GPS Tool, Glasses)
└── network/                # NeoForge client↔server payloads + BluetoothNetwork
```

## License

All rights reserved.
