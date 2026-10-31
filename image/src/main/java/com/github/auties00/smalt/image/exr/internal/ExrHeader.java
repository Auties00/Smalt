/* Ports OpenEXR 3.3.5 (src/lib/OpenEXRCore: the header attributes, level counts and chunk
   tables). Copyright Contributors to the OpenEXR Project;
   BSD-3-Clause. Modified for Smalt. */
package com.github.auties00.smalt.image.exr.internal;

import com.github.auties00.smalt.MediaException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds the header of one OpenEXR part: its channels, windows, compression, storage and tiling.
 */
public final class ExrHeader {
    /**
     * The scanline storage.
     */
    public static final int SCANLINE = 0;

    /**
     * The tiled storage.
     */
    public static final int TILED = 1;

    /**
     * The deep scanline storage.
     */
    public static final int DEEP_SCANLINE = 2;

    /**
     * The deep tiled storage.
     */
    public static final int DEEP_TILED = 3;

    /**
     * The tiling of one resolution level only.
     */
    public static final int ONE_LEVEL = 0;

    /**
     * The tiling of square mipmap levels.
     */
    public static final int MIPMAP_LEVELS = 1;

    /**
     * The tiling of independently halved width and height levels.
     */
    public static final int RIPMAP_LEVELS = 2;

    /**
     * Describes one channel.
     *
     * @param name the channel name
     * @param pixelType 0 for 32-bit unsigned integers, 1 for 16-bit floats, 2 for 32-bit floats
     * @param linear whether the channel is perceptually linear, which B44 codes logarithmically
     * @param xSampling the horizontal sampling period
     * @param ySampling the vertical sampling period
     */
    public record Channel(String name, int pixelType, boolean linear, int xSampling, int ySampling) {
        /**
         * {@return the bytes of one sample}
         */
        public int bytes() {
            return pixelType == 1 ? 2 : 4;
        }
    }

    private final List<Channel> channels;

    private final Map<String, byte[]> attributes;

    private final Map<String, String> types;

    private final int compression;

    private final int xMin;

    private final int yMin;

    private final int xMax;

    private final int yMax;

    private final int storage;

    private final int tileWidth;

    private final int tileHeight;

    private final int levelMode;

    private final boolean roundUp;

    private final int lineOrder;

    private ExrHeader(List<Channel> channels, Map<String, byte[]> attributes, Map<String, String> types,
                      int storage) {
        this.channels = channels;
        this.attributes = attributes;
        this.types = types;
        var compressionValue = attributes.get("compression");
        if (compressionValue == null || compressionValue.length < 1) {
            throw new MediaException.Malformed("the OpenEXR header has no compression attribute");
        }
        this.compression = compressionValue[0] & 0xFF;
        if (compression > 13) {
            throw new MediaException.Unsupported("exr", "compression method " + compression);
        }
        var window = attributes.get("dataWindow");
        if (window == null || window.length < 16) {
            throw new MediaException.Malformed("the OpenEXR header has no data window");
        }
        this.xMin = i32(window, 0);
        this.yMin = i32(window, 4);
        this.xMax = i32(window, 8);
        this.yMax = i32(window, 12);
        if ((long) xMax - xMin + 1 <= 0 || (long) yMax - yMin + 1 <= 0
                || (long) xMax - xMin + 1 > Integer.MAX_VALUE || (long) yMax - yMin + 1 > Integer.MAX_VALUE) {
            throw new MediaException.Malformed("invalid OpenEXR data window " + xMin + "," + yMin + " to " + xMax
                    + "," + yMax);
        }
        var order = attributes.get("lineOrder");
        this.lineOrder = order != null && order.length >= 1 ? order[0] & 0xFF : 0;
        this.storage = storage;
        var tiles = attributes.get("tiles");
        if (storage == TILED || storage == DEEP_TILED) {
            if (tiles == null || tiles.length < 9) {
                throw new MediaException.Malformed("the tiled OpenEXR part has no tile description");
            }
            this.tileWidth = i32(tiles, 0);
            this.tileHeight = i32(tiles, 4);
            this.levelMode = tiles[8] & 0x0F;
            this.roundUp = (tiles[8] & 0xF0) != 0;
            if (tileWidth <= 0 || tileHeight <= 0 || levelMode > RIPMAP_LEVELS) {
                throw new MediaException.Malformed("invalid OpenEXR tile description " + tileWidth + "x"
                        + tileHeight + " mode " + levelMode);
            }
        } else {
            this.tileWidth = 0;
            this.tileHeight = 0;
            this.levelMode = ONE_LEVEL;
            this.roundUp = false;
        }
        for (var channel : channels) {
            if (channel.xSampling() < 1 || channel.ySampling() < 1 || channel.pixelType() < 0
                    || channel.pixelType() > 2) {
                throw new MediaException.Malformed("invalid OpenEXR channel " + channel.name());
            }
            if ((storage != SCANLINE && (channel.xSampling() != 1 || channel.ySampling() != 1))) {
                throw new MediaException.Malformed("the OpenEXR channel " + channel.name()
                        + " is subsampled in a tiled or deep part");
            }
        }
    }

    /**
     * Reads one header.
     *
     * @param data the file
     * @param position the offset of the first attribute
     * @param versionFlags the flags of the version field
     * @param multipart whether the file holds several parts, whose headers carry their type
     * @param end receives the offset past the header's terminating null byte in its first element
     * @return the header
     * @throws MediaException.Malformed if the header is invalid
     */
    public static ExrHeader read(byte[] data, int position, int versionFlags, boolean multipart, int[] end) {
        var attributes = new LinkedHashMap<String, byte[]>();
        var types = new LinkedHashMap<String, String>();
        List<Channel> channels = null;
        while (true) {
            if (position >= data.length) {
                throw new MediaException.Malformed("the OpenEXR header ends early");
            }
            if (data[position] == 0) {
                position++;
                break;
            }
            var name = string(data, position);
            position = skip(data, position);
            var type = string(data, position);
            position = skip(data, position);
            if (position + 4 > data.length) {
                throw new MediaException.Malformed("the OpenEXR attribute " + name + " ends early");
            }
            var size = i32(data, position);
            position += 4;
            if (size < 0 || position + (long) size > data.length) {
                throw new MediaException.Malformed("the OpenEXR attribute " + name + " runs past the file");
            }
            var value = java.util.Arrays.copyOfRange(data, position, position + size);
            position += size;
            attributes.putIfAbsent(name, value);
            types.putIfAbsent(name, type);
            if (name.equals("channels") && type.equals("chlist")) {
                channels = channels(value);
            }
        }
        end[0] = position;
        if (channels == null) {
            throw new MediaException.Malformed("the OpenEXR header has no channel list");
        }
        int storage;
        var typeValue = attributes.get("type");
        if (typeValue != null) {
            var typeName = string(typeValue, 0);
            storage = switch (typeName) {
                case "scanlineimage" -> SCANLINE;
                case "tiledimage" -> TILED;
                case "deepscanline" -> DEEP_SCANLINE;
                case "deeptile" -> DEEP_TILED;
                default -> throw new MediaException.Unsupported("exr", "parts of type " + typeName);
            };
        } else if (multipart) {
            throw new MediaException.Malformed("an OpenEXR part of a multi-part file has no type");
        } else {
            var tiled = (versionFlags & 0x200) != 0;
            var deep = (versionFlags & 0x800) != 0;
            storage = deep ? (tiled ? DEEP_TILED : DEEP_SCANLINE) : tiled ? TILED : SCANLINE;
        }
        return new ExrHeader(channels, attributes, types, storage);
    }

    /**
     * Parses a channel list, whose channels are sorted by name.
     */
    private static List<Channel> channels(byte[] value) {
        var channels = new ArrayList<Channel>();
        var position = 0;
        while (position < value.length && value[position] != 0) {
            var name = string(value, position);
            position = skip(value, position);
            if (position + 16 > value.length) {
                throw new MediaException.Malformed("the OpenEXR channel " + name + " ends early");
            }
            channels.add(new Channel(name, i32(value, position), value[position + 4] != 0, i32(value, position + 8),
                    i32(value, position + 12)));
            position += 16;
        }
        if (channels.isEmpty()) {
            throw new MediaException.Malformed("the OpenEXR channel list is empty");
        }
        channels.sort((a, b) -> compareNames(a.name(), b.name()));
        return Collections.unmodifiableList(channels);
    }

    /**
     * Compares channel names as OpenEXR orders them, by their bytes.
     */
    private static int compareNames(String a, String b) {
        return java.util.Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads a null-terminated string.
     */
    static String string(byte[] data, int position) {
        var end = position;
        while (end < data.length && data[end] != 0) {
            end++;
        }
        return new String(data, position, end - position, StandardCharsets.UTF_8);
    }

    /**
     * {@return the offset past the null byte ending a string}
     */
    static int skip(byte[] data, int position) {
        var end = position;
        while (end < data.length && data[end] != 0) {
            end++;
        }
        return end + 1;
    }

    /**
     * Reads a little-endian 32-bit integer.
     */
    public static int i32(byte[] data, int position) {
        return (data[position] & 0xFF) | (data[position + 1] & 0xFF) << 8 | (data[position + 2] & 0xFF) << 16
                | (data[position + 3] & 0xFF) << 24;
    }

    /**
     * Reads a little-endian 64-bit integer.
     */
    public static long i64(byte[] data, int position) {
        return (i32(data, position) & 0xFFFFFFFFL) | (long) i32(data, position + 4) << 32;
    }

    /**
     * {@return the channels, sorted by name}
     */
    public List<Channel> channels() {
        return channels;
    }

    /**
     * {@return the raw value of an attribute, or {@code null}}
     *
     * @param name the attribute name
     */
    public byte[] attribute(String name) {
        var value = attributes.get(name);
        return value == null ? null : value.clone();
    }

    /**
     * {@return the type name of an attribute, or {@code null}}
     *
     * @param name the attribute name
     */
    public String attributeType(String name) {
        return types.get(name);
    }

    /**
     * {@return the value of a string attribute, or {@code null}}
     *
     * @param name the attribute name
     */
    public String stringAttribute(String name) {
        var value = attributes.get(name);
        return value == null || !"string".equals(types.get(name)) ? null
                : new String(value, StandardCharsets.UTF_8);
    }

    /**
     * {@return the part name, or {@code null}}
     */
    public String name() {
        return stringAttribute("name");
    }

    /**
     * {@return the compression method: 0 none, 1 RLE, 2 ZIPS, 3 ZIP, 4 PIZ, 5 PXR24, 6 B44, 7 B44A, 8 DWAA, 9 DWAB,
     * 10 HTJ2K256, 11 HTJ2K32, 12 LJ2K, 13 ZSTD}
     */
    public int compression() {
        return compression;
    }

    /**
     * {@return the storage: {@link #SCANLINE}, {@link #TILED}, {@link #DEEP_SCANLINE} or {@link #DEEP_TILED}}
     */
    public int storage() {
        return storage;
    }

    /**
     * {@return whether the part is tiled}
     */
    public boolean tiled() {
        return storage == TILED || storage == DEEP_TILED;
    }

    /**
     * {@return whether the part holds deep data}
     */
    public boolean deep() {
        return storage == DEEP_SCANLINE || storage == DEEP_TILED;
    }

    /**
     * {@return the left column of the data window}
     */
    public int xMin() {
        return xMin;
    }

    /**
     * {@return the top row of the data window}
     */
    public int yMin() {
        return yMin;
    }

    /**
     * {@return the width of the data window}
     */
    public int width() {
        return xMax - xMin + 1;
    }

    /**
     * {@return the height of the data window}
     */
    public int height() {
        return yMax - yMin + 1;
    }

    /**
     * {@return the line order: 0 increasing, 1 decreasing, 2 random}
     */
    public int lineOrder() {
        return lineOrder;
    }

    /**
     * {@return the tile width}
     */
    public int tileWidth() {
        return tileWidth;
    }

    /**
     * {@return the tile height}
     */
    public int tileHeight() {
        return tileHeight;
    }

    /**
     * {@return the level mode: {@link #ONE_LEVEL}, {@link #MIPMAP_LEVELS} or {@link #RIPMAP_LEVELS}}
     */
    public int levelMode() {
        return levelMode;
    }

    /**
     * {@return the scanlines of one chunk of a scanline part}
     */
    public int linesPerChunk() {
        return switch (compression) {
            case 0, 1, 2, 13 -> 1;
            case 3, 5 -> 16;
            case 4, 6, 7, 8, 11 -> 32;
            case 9, 10, 12 -> 256;
            default -> throw new MediaException.Unsupported("exr", "compression method " + compression);
        };
    }

    /**
     * {@return the number of horizontal levels}
     */
    public int levelsX() {
        return switch (levelMode) {
            case ONE_LEVEL -> 1;
            case MIPMAP_LEVELS -> levelCount(Math.max(width(), height()));
            default -> levelCount(width());
        };
    }

    /**
     * {@return the number of vertical levels}
     */
    public int levelsY() {
        return switch (levelMode) {
            case ONE_LEVEL -> 1;
            case MIPMAP_LEVELS -> levelCount(Math.max(width(), height()));
            default -> levelCount(height());
        };
    }

    /**
     * {@return the number of levels of a size: its binary logarithm, rounded down or up, plus one}
     */
    private int levelCount(int size) {
        var log = 31 - Integer.numberOfLeadingZeros(size);
        if (roundUp && Integer.bitCount(size) > 1) {
            log++;
        }
        return log + 1;
    }

    /**
     * {@return the size of a level, halved {@code level} times, rounded down or up, at least 1}
     *
     * @param size the full size
     * @param level the level index, 0 for full resolution
     */
    public int levelSize(int size, int level) {
        var divided = roundUp ? (size + (1 << level) - 1) >> level : size >> level;
        return Math.max(1, divided);
    }

    /**
     * {@return the number of chunks of the part's offset table}
     */
    public long chunkCount() {
        if (!tiled()) {
            return ((long) height() + linesPerChunk() - 1) / linesPerChunk();
        }
        var total = 0L;
        if (levelMode == RIPMAP_LEVELS) {
            for (var ly = 0; ly < levelsY(); ly++) {
                for (var lx = 0; lx < levelsX(); lx++) {
                    total += tiles(levelSize(width(), lx), tileWidth) * tiles(levelSize(height(), ly), tileHeight);
                }
            }
        } else {
            for (var level = 0; level < levelsX(); level++) {
                total += tiles(levelSize(width(), level), tileWidth) * tiles(levelSize(height(), level), tileHeight);
            }
        }
        return total;
    }

    /**
     * {@return the number of tiles covering a size}
     *
     * @param size the level size
     * @param tile the tile size
     */
    public static long tiles(int size, int tile) {
        return ((long) size + tile - 1) / tile;
    }

    /**
     * {@return the number of samples of a sampling period in a range of coordinates}
     *
     * @param period the sampling period
     * @param from the first coordinate
     * @param count the number of coordinates
     */
    public static int samples(int period, int from, int count) {
        var to = (long) from + count - 1;
        return (int) (Math.floorDiv(to, period) - Math.floorDiv((long) from - 1, period));
    }
}
