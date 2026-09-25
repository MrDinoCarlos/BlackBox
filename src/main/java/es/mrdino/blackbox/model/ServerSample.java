package es.mrdino.blackbox.model;

import es.mrdino.blackbox.util.Csv;

import java.time.Instant;
import java.util.List;

public record ServerSample(
        Instant timestamp,
        double tps1m,
        double tps5m,
        double tps15m,
        double averageMspt,
        double tickP50Ms,
        double tickP95Ms,
        double tickP99Ms,
        double tickMaxMs,
        long heapUsedBytes,
        long heapMaxBytes,
        double processCpuPercent,
        double systemCpuPercent,
        double systemLoadAverage,
        int threadCount,
        long gcCount,
        long gcTimeMs,
        int players,
        int loadedChunks,
        int scannedEntities,
        long diskUsableBytes,
        long nonHeapUsedBytes,
        long directBufferBytes,
        long mappedBufferBytes,
        long physicalMemoryBytes,
        long freePhysicalMemoryBytes,
        long swapBytes,
        long freeSwapBytes,
        long committedVirtualMemoryBytes,
        int loadedClasses,
        int cpuCores,
        long uptimeMs,
        int deadlockedThreads,
        long openFileDescriptors,
        long maxFileDescriptors,
        long telemetryDroppedRows,
        int telemetryQueuedRows
) {
    public static final String HEADER = "timestamp,tps_1m,tps_5m,tps_15m,average_mspt,tick_p50_ms,tick_p95_ms,tick_p99_ms,tick_max_ms,heap_used_bytes,heap_max_bytes,process_cpu_percent,system_cpu_percent,system_load_average,thread_count,gc_count,gc_time_ms,players,loaded_chunks,scanned_entities,disk_usable_bytes,non_heap_used_bytes,direct_buffer_bytes,mapped_buffer_bytes,physical_memory_bytes,free_physical_memory_bytes,swap_bytes,free_swap_bytes,committed_virtual_memory_bytes,loaded_classes,cpu_cores,uptime_ms,deadlocked_threads,open_file_descriptors,max_file_descriptors,telemetry_dropped_rows,telemetry_queued_rows";

    public String toCsv() {
        return Csv.row(timestamp.toEpochMilli(), tps1m, tps5m, tps15m, averageMspt, tickP50Ms,
                tickP95Ms, tickP99Ms, tickMaxMs, heapUsedBytes, heapMaxBytes, processCpuPercent,
                systemCpuPercent, systemLoadAverage, threadCount, gcCount, gcTimeMs, players,
                loadedChunks, scannedEntities, diskUsableBytes, nonHeapUsedBytes, directBufferBytes,
                mappedBufferBytes, physicalMemoryBytes, freePhysicalMemoryBytes, swapBytes, freeSwapBytes,
                committedVirtualMemoryBytes, loadedClasses, cpuCores, uptimeMs, deadlockedThreads,
                openFileDescriptors, maxFileDescriptors, telemetryDroppedRows, telemetryQueuedRows);
    }

    public static ServerSample parse(String line) {
        List<String> v = Csv.parse(line);
        if (v.size() < 21) throw new IllegalArgumentException("Fila de servidor incompleta");
        return new ServerSample(Instant.ofEpochMilli(Long.parseLong(v.get(0))),
                d(v, 1), d(v, 2), d(v, 3), d(v, 4), d(v, 5), d(v, 6), d(v, 7), d(v, 8),
                l(v, 9), l(v, 10), d(v, 11), d(v, 12), d(v, 13), i(v, 14), l(v, 15), l(v, 16),
                i(v, 17), i(v, 18), i(v, 19), l(v, 20), ol(v, 21), ol(v, 22), ol(v, 23),
                ol(v, 24), ol(v, 25), ol(v, 26), ol(v, 27), ol(v, 28), oi(v, 29), oi(v, 30),
                ol(v, 31), oi(v, 32), ol(v, 33), ol(v, 34), ol(v, 35), oi(v, 36));
    }

    private static double d(List<String> v, int i) { return Double.parseDouble(v.get(i)); }
    private static long l(List<String> v, int i) { return Long.parseLong(v.get(i)); }
    private static int i(List<String> v, int i) { return Integer.parseInt(v.get(i)); }
    private static long ol(List<String> v, int i) { return i < v.size() && !v.get(i).isBlank() ? Long.parseLong(v.get(i)) : -1; }
    private static int oi(List<String> v, int i) { return i < v.size() && !v.get(i).isBlank() ? Integer.parseInt(v.get(i)) : -1; }
}
