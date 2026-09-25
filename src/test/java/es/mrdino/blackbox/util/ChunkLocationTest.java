package es.mrdino.blackbox.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChunkLocationTest {
    @Test
    void convertsPositiveAndNegativeChunksToTheirBlockCentre() {
        ChunkLocation positive = ChunkLocation.parse("world:309:67");
        assertEquals(4952, positive.centerX());
        assertEquals(1080, positive.centerZ());
        assertEquals("/tp @s 4952 ~ 1080", positive.tpCommand());

        ChunkLocation negative = ChunkLocation.parse("world_nether:-1:-68");
        assertEquals(-8, negative.centerX());
        assertEquals(-1080, negative.centerZ());
    }

    @Test
    void rejectsLocationsWithoutChunkCoordinates() {
        assertNull(ChunkLocation.parse("Servidor"));
        assertNull(ChunkLocation.parse("logger:name"));
    }
}
