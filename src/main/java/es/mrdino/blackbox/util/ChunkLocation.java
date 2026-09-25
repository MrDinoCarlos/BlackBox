package es.mrdino.blackbox.util;

/** Converts a world:chunkX:chunkZ key into block coordinates at the chunk centre. */
public record ChunkLocation(String world, int chunkX, int chunkZ) {
    public static ChunkLocation parse(String value) {
        if (value == null) return null;
        int zSeparator = value.lastIndexOf(':');
        int xSeparator = zSeparator < 0 ? -1 : value.lastIndexOf(':', zSeparator - 1);
        if (xSeparator < 1 || zSeparator <= xSeparator + 1) return null;
        try {
            return new ChunkLocation(value.substring(0, xSeparator),
                    Integer.parseInt(value.substring(xSeparator + 1, zSeparator)),
                    Integer.parseInt(value.substring(zSeparator + 1)));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public long centerX() { return (long) chunkX * 16L + 8L; }
    public long centerZ() { return (long) chunkZ * 16L + 8L; }
    public String center() { return centerX() + ", " + centerZ(); }
    public String tpCommand() { return "/tp @s " + centerX() + " ~ " + centerZ(); }
    public String navigation() { return world + ":" + chunkX + ":" + chunkZ + "\nCentro " + center() + "\n" + tpCommand(); }
}
