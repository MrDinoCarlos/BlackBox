package es.mrdino.blackbox.monitor;

import es.mrdino.blackbox.inventory.InventorySnapshot;
import es.mrdino.blackbox.storage.TelemetryStore;
import es.mrdino.blackbox.util.Csv;

import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Collectors;

/** Persists server boundaries and the software environment present at each boundary. */
public final class SessionHistory {
    private static final String HEADER = "timestamp,event,session_id,server_version,java_version,plugins,datapacks";
    private final TelemetryStore store;
    private final String sessionId;

    public SessionHistory(TelemetryStore store, Instant startedAt) {
        this.store = store;
        this.sessionId = Long.toString(startedAt.toEpochMilli());
    }

    public void record(String event, InventorySnapshot snapshot) {
        Instant now = Instant.now();
        String plugins = snapshot.plugins().stream()
                .sorted(Comparator.comparing(InventorySnapshot.PluginInfo::name, String.CASE_INSENSITIVE_ORDER))
                .map(p -> clean(p.name()) + "@" + clean(p.version()))
                .collect(Collectors.joining(";"));
        String dataPacks = snapshot.dataPacks().stream()
                .filter(InventorySnapshot.DataPackInfo::enabled)
                .map(InventorySnapshot.DataPackInfo::key)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .map(SessionHistory::clean)
                .collect(Collectors.joining(";"));
        store.append("sessions", HEADER, now, Csv.row(now.toEpochMilli(), event, sessionId,
                snapshot.server().version(), snapshot.server().javaVersion(), plugins, dataPacks));
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace(';', '_').replace('@', '_');
    }
}
