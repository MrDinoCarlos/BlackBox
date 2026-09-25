package es.mrdino.blackbox.model;

import es.mrdino.blackbox.util.Csv;

import java.time.Instant;
import java.util.List;

public record ChunkObservation(
        Instant timestamp,
        String world,
        int x,
        int z,
        int entities,
        int livingEntities,
        int itemEntities,
        int itemFrames,
        int players,
        int blockEntities,
        int hoppers,
        int spawners,
        int commandBlocks,
        boolean forceLoaded,
        String entityTypes,
        String blockEntityTypes,
        int tickingEntities,
        int persistentEntities,
        int spawnerOriginEntities,
        int unawareMobs,
        int namedEntities,
        int passengers,
        int trackedByPlayers,
        long totalTicksLived,
        int maxTicksLived,
        long inhabitedTicks,
        String loadLevel,
        String pluginTickets,
        long scanMicros
) {
    public static final String HEADER = "timestamp,world,chunk_x,chunk_z,entities,living_entities,item_entities,item_frames,players,block_entities,hoppers,spawners,command_blocks,force_loaded,entity_types,block_entity_types,ticking_entities,persistent_entities,spawner_origin_entities,unaware_mobs,named_entities,passengers,tracked_by_players,total_ticks_lived,max_ticks_lived,inhabited_ticks,load_level,plugin_tickets,scan_micros";

    public String toCsv() {
        return Csv.row(timestamp.toEpochMilli(), world, x, z, entities, livingEntities, itemEntities,
                itemFrames, players, blockEntities, hoppers, spawners, commandBlocks, forceLoaded,
                entityTypes, blockEntityTypes, tickingEntities, persistentEntities, spawnerOriginEntities,
                unawareMobs, namedEntities, passengers, trackedByPlayers, totalTicksLived, maxTicksLived,
                inhabitedTicks, loadLevel, pluginTickets, scanMicros);
    }

    public static ChunkObservation parse(String line) {
        List<String> v = Csv.parse(line);
        if (v.size() < 16) throw new IllegalArgumentException("Fila de chunk incompleta");
        return new ChunkObservation(Instant.ofEpochMilli(Long.parseLong(v.get(0))), v.get(1),
                Integer.parseInt(v.get(2)), Integer.parseInt(v.get(3)), Integer.parseInt(v.get(4)),
                Integer.parseInt(v.get(5)), Integer.parseInt(v.get(6)), Integer.parseInt(v.get(7)),
                Integer.parseInt(v.get(8)), Integer.parseInt(v.get(9)), Integer.parseInt(v.get(10)),
                Integer.parseInt(v.get(11)), Integer.parseInt(v.get(12)), Boolean.parseBoolean(v.get(13)),
                v.get(14), v.get(15), optionalInt(v, 16), optionalInt(v, 17), optionalInt(v, 18),
                optionalInt(v, 19), optionalInt(v, 20), optionalInt(v, 21), optionalInt(v, 22),
                optionalLong(v, 23), optionalInt(v, 24), optionalLong(v, 25), optional(v, 26), optional(v, 27),
                optionalLong(v, 28));
    }

    public String key() { return world + ":" + x + ":" + z; }
    private static String optional(List<String> v, int index) { return index < v.size() ? v.get(index) : ""; }
    private static int optionalInt(List<String> v, int index) { return index < v.size() && !v.get(index).isBlank() ? Integer.parseInt(v.get(index)) : 0; }
    private static long optionalLong(List<String> v, int index) { return index < v.size() && !v.get(index).isBlank() ? Long.parseLong(v.get(index)) : 0; }
}
