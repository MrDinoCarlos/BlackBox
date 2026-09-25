package es.mrdino.blackbox.monitor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McaChunkInspectorTest {
    @Test
    void readsEntitiesBlockEntitiesAndScheduledTicks(@TempDir Path directory) throws Exception {
        ByteArrayOutputStream nbtBytes = new ByteArrayOutputStream();
        try (DataOutputStream nbt = new DataOutputStream(nbtBytes)) {
            nbt.writeByte(10); nbt.writeUTF("");
            nbt.writeByte(9); nbt.writeUTF("Entities"); nbt.writeByte(10); nbt.writeInt(1);
            nbt.writeByte(8); nbt.writeUTF("id"); nbt.writeUTF("minecraft:zombie");
            nbt.writeByte(9); nbt.writeUTF("Passengers"); nbt.writeByte(10); nbt.writeInt(1);
            nbt.writeByte(8); nbt.writeUTF("id"); nbt.writeUTF("minecraft:creeper"); nbt.writeByte(0);
            nbt.writeByte(0);
            nbt.writeByte(9); nbt.writeUTF("block_entities"); nbt.writeByte(10); nbt.writeInt(1);
            nbt.writeByte(8); nbt.writeUTF("id"); nbt.writeUTF("minecraft:chest"); nbt.writeByte(0);
            nbt.writeByte(9); nbt.writeUTF("block_ticks"); nbt.writeByte(10); nbt.writeInt(2);
            nbt.writeByte(0); nbt.writeByte(0);
            nbt.writeByte(9); nbt.writeUTF("fluid_ticks"); nbt.writeByte(10); nbt.writeInt(1); nbt.writeByte(0);
            nbt.writeByte(0);
        }
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) { deflater.write(nbtBytes.toByteArray()); }
        Path file = directory.resolve("chunk.bin");
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            channel.position(8192);
            ByteBuffer payload = ByteBuffer.allocate(5 + compressed.size());
            payload.putInt(compressed.size() + 1).put((byte) 2).put(compressed.toByteArray()).flip();
            while (payload.hasRemaining()) channel.write(payload);
            McaChunkInspector.Summary summary = McaChunkInspector.inspect(channel, 2, 1);
            assertEquals(2, summary.entities());
            assertEquals(1, summary.blockEntities());
            assertEquals(2, summary.blockTicks());
            assertEquals(1, summary.fluidTicks());
            assertEquals(1, summary.entityTypes().get("minecraft:zombie"));
            assertEquals(1, summary.entityTypes().get("minecraft:creeper"));
            assertTrue(summary.error().isBlank());
        }
    }
}
