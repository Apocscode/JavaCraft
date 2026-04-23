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
