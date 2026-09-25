package es.mrdino.blackbox.monitor;

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

final class McaChunkInspector {
    private static final int MAX_ARRAY = 64 * 1024 * 1024;
    private static final int MAX_LIST = 2_000_000;
    private static final int MAX_DEPTH = 64;

    private McaChunkInspector() {}

    static Summary inspect(FileChannel channel, int sectorOffset, int sectorCount) {
        try {
            long available = sectorCount * 4096L;
            if (available < 5 || available > Integer.MAX_VALUE) return Summary.error("invalid_sector_size");
            ByteBuffer header = ByteBuffer.allocate(5);
            if (!readFully(channel, header, sectorOffset * 4096L)) return Summary.error("short_chunk_header");
            header.flip();
            int length = header.getInt();
            int compression = header.get() & 0xFF;
            if ((compression & 0x80) != 0) return Summary.error("external_stream");
            if (length < 2 || length - 1 > available - 5 || length > MAX_ARRAY) return Summary.error("invalid_chunk_length");
            ByteBuffer payload = ByteBuffer.allocate(length - 1);
            if (!readFully(channel, payload, sectorOffset * 4096L + 5)) return Summary.error("short_chunk_payload");
            InputStream bytes = new ByteArrayInputStream(payload.array());
            InputStream decompressed = switch (compression & 0x7F) {
                case 1 -> new GZIPInputStream(bytes);
                case 2 -> new InflaterInputStream(bytes);
                case 3 -> bytes;
                default -> null;
            };
            if (decompressed == null) return Summary.error("compression_" + compression);
            try (DataInputStream input = new DataInputStream(decompressed)) {
                int rootType = input.readUnsignedByte();
                if (rootType != 10) return Summary.error("root_tag_" + rootType);
                readString(input);
                Mutable result = new Mutable();
                readCompound(input, result, 0);
                return result.freeze();
            }
        } catch (Exception error) {
            return Summary.error(error.getClass().getSimpleName() + ":" + safe(error.getMessage()));
        }
    }

    private static void readCompound(DataInput input, Mutable result, int depth) throws IOException {
        requireDepth(depth);
        while (true) {
            int type = input.readUnsignedByte();
            if (type == 0) return;
            String name = readString(input);
            if (type == 9 && (name.equals("Entities") || name.equals("entities"))) {
                readIdentifiedList(input, result.entityTypes, result, true, depth + 1);
            } else if (type == 9 && (name.equals("block_entities") || name.equals("TileEntities"))) {
                readIdentifiedList(input, result.blockEntityTypes, result, false, depth + 1);
            } else if (type == 9 && (name.equals("block_ticks") || name.equals("TileTicks"))) {
                result.blockTicks += readAndSkipList(input, depth + 1);
            } else if (type == 9 && name.equals("fluid_ticks")) {
                result.fluidTicks += readAndSkipList(input, depth + 1);
            } else {
                skipPayload(input, type, depth + 1);
            }
        }
    }

    private static void readIdentifiedList(DataInput input, Map<String, Integer> target, Mutable result,
                                           boolean entity, int depth) throws IOException {
        int elementType = input.readUnsignedByte();
        int length = checkedLength(input.readInt(), MAX_LIST);
        if (elementType != 10) {
            for (int i = 0; i < length; i++) skipPayload(input, elementType, depth + 1);
            return;
        }
        for (int i = 0; i < length; i++) readIdentifiedCompound(input, target, result, entity, depth + 1);
    }

    private static void readIdentifiedCompound(DataInput input, Map<String, Integer> target, Mutable result,
                                               boolean entity, int depth) throws IOException {
        requireDepth(depth);
        String id = "unknown";
        while (true) {
            int type = input.readUnsignedByte();
            if (type == 0) break;
            String name = readString(input);
            if (type == 8 && (name.equals("id") || name.equals("Id"))) {
                id = readString(input);
            } else if (entity && type == 9 && name.equals("Passengers")) {
                readIdentifiedList(input, target, result, true, depth + 1);
            } else {
                skipPayload(input, type, depth + 1);
            }
        }
        target.merge(id, 1, Integer::sum);
        if (entity) result.entities++; else result.blockEntities++;
    }

    private static int readAndSkipList(DataInput input, int depth) throws IOException {
        int elementType = input.readUnsignedByte();
        int length = checkedLength(input.readInt(), MAX_LIST);
        for (int i = 0; i < length; i++) skipPayload(input, elementType, depth + 1);
        return length;
    }

    private static void skipPayload(DataInput input, int type, int depth) throws IOException {
        requireDepth(depth);
        switch (type) {
            case 0 -> { }
            case 1 -> input.readByte();
            case 2 -> input.readShort();
            case 3 -> input.readInt();
            case 4 -> input.readLong();
            case 5 -> input.readFloat();
            case 6 -> input.readDouble();
            case 7 -> skipBytes(input, checkedLength(input.readInt(), MAX_ARRAY));
            case 8 -> readString(input);
            case 9 -> {
                int elementType = input.readUnsignedByte();
                int length = checkedLength(input.readInt(), MAX_LIST);
                for (int i = 0; i < length; i++) skipPayload(input, elementType, depth + 1);
            }
            case 10 -> {
                while (true) {
                    int nested = input.readUnsignedByte();
                    if (nested == 0) break;
                    readString(input);
                    skipPayload(input, nested, depth + 1);
                }
            }
            case 11 -> skipBytes(input, Math.multiplyExact(checkedLength(input.readInt(), MAX_ARRAY / 4), 4));
            case 12 -> skipBytes(input, Math.multiplyExact(checkedLength(input.readInt(), MAX_ARRAY / 8), 8));
            default -> throw new IOException("Unknown NBT tag " + type);
        }
    }

    private static String readString(DataInput input) throws IOException {
        int length = input.readUnsignedShort();
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void skipBytes(DataInput input, int bytes) throws IOException {
        int remaining = bytes;
        while (remaining > 0) {
            int skipped = input.skipBytes(remaining);
            if (skipped <= 0) throw new EOFException("NBT payload truncated");
            remaining -= skipped;
        }
    }

    private static int checkedLength(int value, int max) throws IOException {
        if (value < 0 || value > max) throw new IOException("NBT length out of range: " + value);
        return value;
    }

    private static void requireDepth(int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("NBT nesting too deep");
    }

    private static boolean readFully(FileChannel channel, ByteBuffer target, long position) throws IOException {
        while (target.hasRemaining()) {
            int read = channel.read(target, position);
            if (read < 0) return false;
            if (read == 0) return false;
            position += read;
        }
        return true;
    }

    private static String safe(String value) { return value == null ? "" : value.replace(',', '_').replace('\n', ' '); }

    record Summary(int entities, int blockEntities, int blockTicks, int fluidTicks,
                   Map<String, Integer> entityTypes, Map<String, Integer> blockEntityTypes, String error) {
        static Summary error(String message) { return new Summary(0, 0, 0, 0, Map.of(), Map.of(), message); }
        String entityTypesText() { return counts(entityTypes); }
        String blockEntityTypesText() { return counts(blockEntityTypes); }
        private static String counts(Map<String, Integer> values) {
            StringBuilder out = new StringBuilder();
            new TreeMap<>(values).forEach((key, value) -> {
                if (!out.isEmpty()) out.append(';');
                out.append(key).append('=').append(value);
            });
            return out.toString();
        }
    }

    private static final class Mutable {
        int entities, blockEntities, blockTicks, fluidTicks;
        final Map<String, Integer> entityTypes = new HashMap<>();
        final Map<String, Integer> blockEntityTypes = new HashMap<>();
        Summary freeze() { return new Summary(entities, blockEntities, blockTicks, fluidTicks,
                Map.copyOf(entityTypes), Map.copyOf(blockEntityTypes), ""); }
    }
}
