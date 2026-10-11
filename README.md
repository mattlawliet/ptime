# ptime

Client-side time of day for Minecraft. Set your own lighting without touching the
server, and look up what time it is for other players.

Lock your visual time locally — permanent noon for building, permanent night if you
prefer it — while the server and everyone on it stay unaffected.

- **Loader:** Fabric (client-side only)
- **Minecraft:** 26.2

## Timezones

`ptime` accepts full zone IDs (`America/New_York`), short forms (`EST`, `PST`), and UTC
offsets (`+2`, `-5`). Place names are resolved in three steps:

1. A bundled offline table of ~8,000 places — no network call.
2. A local cache, so any place is looked up at most once per install.
3. The [Open-Meteo geocoding API](https://open-meteo.com/), only if the first two miss.

Open-Meteo needs no API key and no account. Each client makes its own request, so there
is no shared quota and nothing to configure.

## Building

```bash
./gradlew build
```

The jar lands in `build/libs/`.

## License

See [LICENSE](LICENSE).
