# BlackBox

**Server observability, telemetry and diagnostics for Paper.**

BlackBox records the context needed to investigate lag, crashes, overloaded chunks, plugin activity and storage problems without performing blocking I/O on the main server thread.

It is compatible with **Paper 1.21.4–26.3** and produces professional PDF reports, searchable Markdown reports and detailed CSV attachments.

## Highlights

- Live TPS, MSPT, tick percentiles, CPU, memory, heap, GC, threads and disk monitoring.
- Persistent telemetry with retention, rotation, compression and storage quotas.
- Batched loaded-chunk scanning distributed across server ticks.
- Entity, block entity, hopper, spawner, item frame and command block analysis.
- Chunk hotspots with coordinates, evidence and ready-to-use `/tp` commands.
- Player latency, client protocol, locale, view distance and connection tracking.
- Redstone, hopper, piston, explosion, inventory and block event monitoring.
- Offline Anvil region census without loading chunks into Paper.
- Plugin, dependency, command, world, datapack and resource pack inventory.
- Configuration and asset inspection with sensitive values redacted.
- Startup diagnostics, warning aggregation, watchdog detection and crash analysis.
- Manual and automatic Java Flight Recorder profiling.
- Cause-and-effect analysis with evidence, confidence and verification steps.
- PDF, Markdown and normalized CSV reports.
- English and Spanish localization through editable YAML files.

## Commands

| Command | Description |
| --- | --- |
| `/blackbox status` | Show the latest server, chunk, disk and profiling status. |
| `/blackbox scan` | Queue an immediate scan of loaded chunks and offline regions. |
| `/blackbox report 30m` | Generate a report for the selected time window. |
| `/blackbox profile 60` | Capture a 60-second Java Flight Recorder profile. |
| `/blackbox help` | Show the available commands. |

All commands require the `blackbox.admin` permission, which is granted to operators by default.

## Reports

The `report` command generates a report directory under:

```text
plugins/BlackBox/reports/
└── blackbox-<timestamp>-<period>/
    ├── BlackBox-report.pdf
    ├── report.md
    ├── performance-episodes.csv
    ├── incidents.csv
    ├── startup-diagnostics.csv
    ├── crash-analysis.csv
    ├── plugin-analysis.csv
    ├── profile-io.csv
    ├── chunk-analysis.csv
    ├── entity-summary.csv
    ├── hotspots.csv
    ├── warnings.csv
    └── ...
```

The PDF includes:

- Executive overview and health index.
- TPS, MSPT, CPU, heap, RAM, GC, disk and thread charts.
- Historical comparison with the previous period.
- Cause-and-effect performance episodes.
- Incident timeline and severity markers.
- Chunk density, entity lifecycle and geographic hotspots.
- Player connectivity and command block analysis.
- Plugin topology, dependencies and JFR evidence.
- Datapack, resource pack and file inventory.
- Offline Anvil census.
- Coverage notes, limitations and a technical glossary.

CSV files keep the full data when a PDF table is intentionally limited for readability.

## Java Flight Recorder

Manual profiling is available through:

```text
/blackbox profile <seconds>
```

Automatic profiling can start after consecutive samples cross the configured TPS or tick-p95 thresholds. BlackBox records:

- CPU execution samples.
- Plugin frames found in sampled stacks.
- Garbage collection events.
- File and socket reads/writes.
- Total and maximum I/O duration.
- Top application methods.

The resulting `.jfr` file can be inspected with IntelliJ Profiler or JDK Mission Control.

## Telemetry and storage

Telemetry is written to:

```text
plugins/BlackBox/telemetry/
plugins/BlackBox/profiles/
plugins/BlackBox/reports/
```

Default protections include:

- Five-minute telemetry segments.
- GZIP compression for closed segments.
- A 256 MiB telemetry quota.
- Independent 256 MiB quotas for profiles and reports.
- Per-stream row limits.
- Maximum row length limits.
- Asynchronous queues between Paper and disk storage.

These values are configurable in `config.yml`.

## Localization

The default locale is `en_US`. Spanish is available as `es_ES`.

Set the locale in `config.yml`:

```yaml
language: en_US
```

Translation files are stored in `src/main/resources/lang/`:

```text
lang/
├── en_US.yml
└── es_ES.yml
```

The selected locale applies to command messages, generated logs, PDF reports, Markdown reports and JFR analysis. The YAML keys should remain unchanged so MrDinoBot can update translated values safely.

## Privacy

Player data can be configured in `config.yml`:

- Player addresses: `hash`, `plain` or `off`.
- Player names.
- Player UUIDs.
- Player locations.
- Entity UUIDs.

Sensitive configuration values such as passwords, tokens, credentials, private keys and database URLs are replaced with `<redacted>`.

## Compatibility

| Component | Support |
| --- | --- |
| Paper | 1.21.4–26.3 |
| Runtime bytecode | Java 21 |
| Paper 26.3 compilation | Java 25 or newer |
| Report engine | Apache PDFBox 3.0.8 |

Paper 26.3 requires Java 25 or newer. The normal plugin JAR keeps Java 21 bytecode for Paper 1.21.4 compatibility.

## Important limits

BlackBox cannot receive client FPS, GPU usage, shaders, mods or client memory through the normal Minecraft protocol.

Paper does not expose exact CPU usage per entity or chunk, and public APIs cannot always identify the plugin responsible for every call. BlackBox therefore combines telemetry, correlations, logs, chunk evidence and JFR stack samples. Correlation is presented as evidence or a hypothesis, not as automatic proof of causality.

The offline region census describes data stored on disk. It does not measure activity while a chunk was unloaded.

## Build

Requirements:

- JDK 21 for the main build.
- JDK 25 or newer for Paper 26.3 compatibility compilation.

Run the full verification suite:

```powershell
.\gradlew.bat clean check
```

Build the plugin JAR:

```powershell
.\gradlew.bat build
```

The JAR is created at:

```text
build/libs/BlackBox-0.6.0.jar
```

Copy it to the server's `plugins/` directory and restart Paper.

## License

BlackBox is released under the [MIT License](LICENSE).
