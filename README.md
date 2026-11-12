# Ignix Client

A free PvP client for Minecraft: Java Edition **1.8.9**, made in Poland.

Ignix is a small hobby project. The goal is simple: a fast 1.8.9 client with the mods
people actually use, without looking like every other client out there.

## What's in it

- **FPS Boost** – skips rendering things hidden behind walls, far entities and extra
  particles, and caps FPS when the game is in the background
- **HUD mods** – FPS, CPS, ping, keystrokes, armor & potion status, coordinates,
  combo counter, reach display and more, all draggable
- **Quality of life** – toggle sprint, zoom, custom crosshair, cleaner scoreboard,
  1.9-style boss bars, block overlay, borderless fullscreen
- **Discord Rich Presence**
- **Anti-injection protection** – the game blocks the Java Attach API and checks
  itself against a signed blacklist, so cheats can't be injected into Ignix

## This repository

This repo holds the data files the client downloads at startup:

| File | What it is |
|------|------------|
| `blacklist.json` | detection rules for injected cheats |
| `update.json` | latest client version + download link |
| `*.sig` | signatures – the client only accepts files signed with our key |

## Ignix Launcher

A launcher is in the works. It will sign you in with your own Microsoft account
(or let you play on offline-mode servers), download the official game files from
Mojang and keep Ignix up to date. Your login tokens stay on your computer and are
only sent to Microsoft / Xbox / Minecraft services.

## Contact

Questions or bug reports – open an issue in this repository.

---

Ignix Client is not affiliated with Mojang Studios or Microsoft.
"Minecraft" is a trademark of Mojang Studios.
