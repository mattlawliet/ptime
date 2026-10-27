# ptime Notes

Repo snapshot for future work on the Minecraft Fabric client mod `ptime`.

## What This Mod Does

- Client-side time tracker for Minecraft players.
- Opens a custom screen with `P` and via `/ptime open`.
- Tracks player timezones locally in `config/ptime.json`.
- Renders player cards with:
  - username
  - timezone label and UTC offset
  - their local time
  - your local time for comparison
  - skin preview when available
- Supports a compact and regular layout toggle.
- Supports vertical scrolling when the card list exceeds the viewport.
- Supports fuzzy name search across tracked players.
- Renders favorites in a section above the rest of the list when any are marked.
- Lets you toggle favorites from a heart on each card (vanilla heart sprite, tooltip on hover).

## Key Files

- [`src/main/java/dev/matt/ptime/PtimeClient.java`](src/main/java/dev/matt/ptime/PtimeClient.java)
  - Client mod entrypoint.
  - Registers the `P` keybind.
  - Registers client commands under `/ptime`.
  - Wires join/disconnect events into the manager.

- [`src/main/java/dev/matt/ptime/PtimeManager.java`](src/main/java/dev/matt/ptime/PtimeManager.java)
  - Owns all persisted state and player tracking.
  - Loads/saves `ptime.json` from the Fabric config directory.
  - Resolves timezone input, including shorthands like `EST`, `CST`, `PST`, `UTC`, and numeric offsets like `+2` or `-5`.
  - Fetches and caches skins for tracked offline players.

- [`src/main/java/dev/matt/ptime/PtimeScreen.java`](src/main/java/dev/matt/ptime/PtimeScreen.java)
  - Renders the UI.
  - Draws regular and compact player cards.
  - Uses the manager for current data and skin lookup.
  - Handles scrolling, search filtering, and favorite toggles.

- [`src/main/resources/assets/ptime/lang/en_us.json`](src/main/resources/assets/ptime/lang/en_us.json)
  - Keybinding/category strings.

- [`src/main/resources/fabric.mod.json`](src/main/resources/fabric.mod.json)
  - Fabric metadata and client entrypoint declaration.

- [`build.gradle`](build.gradle)
  - Fabric Loom build.
  - Java 21 target.
  - Minecraft `1.21.11`.

## Runtime Flow

1. `PtimeClient` initializes the keybind and command tree.
2. `PtimeManager` loads saved state during construction.
3. Joining a world triggers `onSessionStarted`, which queues skin lookups for tracked players.
4. The screen queries `trackedPlayers()` and `getSkin()` each frame.
5. Any add/remove/self-timezone command persists immediately.

## Commands

- `/ptime`
  - Opens the screen.

- `/ptime open`
  - Opens the screen explicitly.

- `/ptime <player> <timezone>`
  - Adds or updates a tracked player.

- `/ptime self <timezone>`
  - Sets your own comparison timezone.

- `/ptime remove <player>`
  - Stops tracking a player.

- `/ptime list`
  - Prints tracked players and the current self timezone.

## Persistence Format

Stored at `config/ptime.json` with:

- `selfTimezone`
- `players`
  - each entry has `name`
  - each entry has `timezone`

Invalid player names or invalid timezone IDs are skipped on load.

## Implementation Notes

- The code silently ignores most IO/network failures. That keeps the UI responsive, but it also means failures are easy to miss when debugging.
- `PtimeManager.resolveTimezone(...)` accepts either:
  - full IANA IDs like `America/New_York`
  - shorthands like `EST`
  - fixed offsets like `+2`
- Offline skin download path goes through Mojang profile and session endpoints.
- `PtimeScreen` keeps a remembered layout mode in a static field so the layout choice survives reopening the screen during a session.
- Build output already exists under `build/`, but those artifacts should be treated as disposable.

## Good First Places To Edit

- UI/layout tweaks: [`PtimeScreen.java`](src/main/java/dev/matt/ptime/PtimeScreen.java)
- Command behavior or persistence: [`PtimeClient.java`](src/main/java/dev/matt/ptime/PtimeClient.java), [`PtimeManager.java`](src/main/java/dev/matt/ptime/PtimeManager.java)
- Text and keybind labels: [`en_us.json`](src/main/resources/assets/ptime/lang/en_us.json)

## Quick Build Reference

- `./gradlew build`
