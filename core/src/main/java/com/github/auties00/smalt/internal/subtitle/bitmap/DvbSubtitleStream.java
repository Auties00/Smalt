package com.github.auties00.smalt.internal.subtitle.bitmap;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.subtitle.BitmapRegion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Decodes DVB subtitles (ETSI EN 300 743): the page, region, CLUT, object and display definition segments of one
 * subtitle service.
 *
 * <p>A display set is shown at its end of display set segment, or at the end of the packet holding its page
 * composition when the stream sends no such segment, at the time of the packet that carried the page composition.
 * Character objects, whose character table the specification leaves to a local agreement, are not drawn, and the
 * disparity and alternative CLUT segments of stereoscopic and high dynamic range services are ignored (the
 * specification requires the CLUT definition segments those services carry too).
 */
public final class DvbSubtitleStream implements BitmapStream {
    private static final int PAGE_COMPOSITION = 0x10;
    private static final int REGION_COMPOSITION = 0x11;
    private static final int CLUT_DEFINITION = 0x12;
    private static final int OBJECT_DATA = 0x13;
    private static final int DISPLAY_DEFINITION = 0x14;
    private static final int END_OF_DISPLAY_SET = 0x80;

    /**
     * The default 2-bit to 4-bit map table (table 39).
     */
    private static final byte[] MAP_2_TO_4 = {0x0, 0x7, 0x8, 0xF};

    /**
     * The default 2-bit to 8-bit map table (table 40).
     */
    private static final byte[] MAP_2_TO_8 = {0x00, 0x77, (byte) 0x88, (byte) 0xFF};

    /**
     * The default 4-bit to 8-bit map table (table 41).
     */
    private static final byte[] MAP_4_TO_8 = {0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88,
            (byte) 0x99, (byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD, (byte) 0xEE, (byte) 0xFF};

    /**
     * The default 4-entry CLUT (table 38).
     */
    private static final int[] DEFAULT_CLUT_4 = default4();

    /**
     * The default 16-entry CLUT (table 37).
     */
    private static final int[] DEFAULT_CLUT_16 = default16();

    /**
     * The default 256-entry CLUT (table 36).
     */
    private static final int[] DEFAULT_CLUT_256 = default256();

    private final MediaLimits limits;

    /**
     * The composition page decoded, or {@code -1} until the first page composition segment fixes it.
     */
    private int compositionPage;

    /**
     * The ancillary page, or {@code -1} when any other page may carry shared data.
     */
    private final int ancillaryPage;

    private final Map<Integer, Region> regions = new HashMap<>();
    private final Map<Integer, int[][]> cluts = new HashMap<>();

    /**
     * The pages met with a page composition, whose data belongs to their own service.
     */
    private final Set<Integer> composers = new HashSet<>();

    /**
     * The regions of the page in force: region identifier, horizontal and vertical address, three ints each.
     */
    private int[] pageRegions = new int[0];

    private int timeoutSeconds;
    private int displayWidth = 720;
    private int displayHeight = 576;
    private int windowX;
    private int windowY;

    /**
     * Whether a page composition waits to be shown.
     */
    private boolean open;

    /**
     * The time of the page composition waiting to be shown.
     */
    private long openMicros;

    /**
     * Constructs a decoder of one subtitle service.
     *
     * @param limits the bounds of the regions
     * @param compositionPage the composition page identifier, or {@code -1} to decode the first page met
     * @param ancillaryPage the ancillary page identifier, or {@code -1}
     */
    public DvbSubtitleStream(MediaLimits limits, int compositionPage, int ancillaryPage) {
        this.limits = limits;
        this.compositionPage = compositionPage;
        this.ancillaryPage = ancillaryPage;
    }

    @Override
    public void reset() {
        regions.clear();
        cluts.clear();
        pageRegions = new int[0];
        composers.clear();
        open = false;
        displayWidth = 720;
        displayHeight = 576;
        windowX = 0;
        windowY = 0;
    }

    @Override
    public void decode(byte[] data, long micros, Sink sink) {
        var position = 0;
        if (data.length >= 2 && (data[0] & 0xFF) == 0x20 && data[1] == 0) {
            position = 2;
        }
        while (position + 6 <= data.length && (data[position] & 0xFF) == 0x0F) {
            var type = data[position + 1] & 0xFF;
            var page = u16(data, position + 2);
            var length = u16(data, position + 4);
            var body = position + 6;
            if (body + length > data.length) {
                break;
            }
            if (type == PAGE_COMPOSITION) {
                if (compositionPage < 0) {
                    compositionPage = page;
                }
                composers.add(page);
            }
            var composition = page == compositionPage;
            var shared = composition || (ancillaryPage >= 0 ? page == ancillaryPage
                    : compositionPage >= 0 && !composers.contains(page));
            switch (type) {
                case PAGE_COMPOSITION -> {
                    if (composition) {
                        if (open) {
                            show(sink);
                        }
                        pageComposition(data, body, length);
                        open = true;
                        openMicros = micros;
                    }
                }
                case REGION_COMPOSITION -> {
                    if (composition) {
                        regionComposition(data, body, length);
                    }
                }
                case CLUT_DEFINITION -> {
                    if (shared) {
                        clutDefinition(data, body, length);
                    }
                }
                case OBJECT_DATA -> {
                    if (shared) {
                        objectData(data, body, length);
                    }
                }
                case DISPLAY_DEFINITION -> {
                    if (composition) {
                        displayDefinition(data, body, length);
                    }
                }
                case END_OF_DISPLAY_SET -> {
                    if (shared && open) {
                        show(sink);
                    }
                }
                default -> {
                }
            }
            position = body + length;
        }
        if (open) {
            show(sink);
        }
    }

    /**
     * Shows the page composition waiting to be shown.
     *
     * @param sink receives the screen
     */
    private void show(Sink sink) {
        open = false;
        var shown = new ArrayList<BitmapRegion>();
        for (var index = 0; index < pageRegions.length; index += 3) {
            var region = regions.get(pageRegions[index]);
            if (region == null) {
                continue;
            }
            var palette = clut(region.clutId)[region.depth == 2 ? 0 : region.depth == 4 ? 1 : 2];
            if (!Palettes.visible(region.pixels, region.pixels.length, palette)) {
                continue;
            }
            var x = windowX + pageRegions[index + 1];
            var y = windowY + pageRegions[index + 2];
            shown.add(new BitmapRegion(x, y, Palettes.paint(region.pixels, region.width, region.height, palette)));
        }
        if (shown.isEmpty()) {
            sink.clear(openMicros);
            return;
        }
        var deadline = timeoutSeconds > 0 ? openMicros + timeoutSeconds * 1_000_000L : NO_DEADLINE;
        sink.show(openMicros, new Screen(displayWidth, displayHeight, shown, false), deadline);
    }

    private void pageComposition(byte[] data, int body, int length) {
        if (length < 2) {
            return;
        }
        timeoutSeconds = data[body] & 0xFF;
        var state = (data[body + 1] >> 2) & 3;
        if (state == 2) {
            regions.clear();
            cluts.clear();
        }
        var count = (length - 2) / 6;
        var entries = new int[count * 3];
        for (var index = 0; index < count; index++) {
            var at = body + 2 + index * 6;
            entries[index * 3] = data[at] & 0xFF;
            entries[index * 3 + 1] = u16(data, at + 2);
            entries[index * 3 + 2] = u16(data, at + 4);
        }
        pageRegions = entries;
    }

    private void regionComposition(byte[] data, int body, int length) {
        if (length < 10) {
            return;
        }
        var id = data[body] & 0xFF;
        var fill = (data[body + 1] & 0x08) != 0;
        var width = u16(data, body + 2);
        var height = u16(data, body + 4);
        var depthCode = (data[body + 6] >> 2) & 7;
        var depth = switch (depthCode) {
            case 1 -> 2;
            case 2 -> 4;
            case 3 -> 8;
            default -> 0;
        };
        if (depth == 0 || width == 0 || height == 0) {
            regions.remove(id);
            return;
        }
        LimitChecks.checkPicture(limits, width, height);
        var region = regions.get(id);
        if (region == null || region.width != width || region.height != height || region.depth != depth) {
            region = new Region(width, height, depth);
            regions.put(id, region);
        }
        region.clutId = data[body + 7] & 0xFF;
        if (fill) {
            var code = switch (depth) {
                case 2 -> (data[body + 9] >> 2) & 3;
                case 4 -> (data[body + 9] >> 4) & 0xF;
                default -> data[body + 8] & 0xFF;
            };
            Arrays.fill(region.pixels, (byte) code);
        }
        var objects = new ArrayList<int[]>();
        var position = body + 10;
        var end = body + length;
        while (position + 6 <= end) {
            var objectId = u16(data, position);
            var type = (data[position + 2] >> 6) & 3;
            var x = (data[position + 2] & 0x0F) << 8 | (data[position + 3] & 0xFF);
            var y = (data[position + 4] & 0x0F) << 8 | (data[position + 5] & 0xFF);
            objects.add(new int[]{objectId, x, y});
            position += type == 1 || type == 2 ? 8 : 6;
        }
        region.objects = objects;
    }

    private void clutDefinition(byte[] data, int body, int length) {
        if (length < 2) {
            return;
        }
        var tables = clut(data[body] & 0xFF);
        var position = body + 2;
        var end = body + length;
        while (position + 2 <= end) {
            var entry = data[position] & 0xFF;
            var flags = data[position + 1] & 0xFF;
            int y;
            int cr;
            int cb;
            int t;
            if ((flags & 1) != 0) {
                if (position + 6 > end) {
                    break;
                }
                y = data[position + 2] & 0xFF;
                cr = data[position + 3] & 0xFF;
                cb = data[position + 4] & 0xFF;
                t = data[position + 5] & 0xFF;
                position += 6;
            } else {
                if (position + 4 > end) {
                    break;
                }
                var packed = u16(data, position + 2);
                y = (packed >> 10) << 2;
                cr = ((packed >> 6) & 0xF) << 4;
                cb = ((packed >> 2) & 0xF) << 4;
                t = (packed & 3) << 6;
                position += 4;
            }
            var color = y == 0 ? Palettes.TRANSPARENT : Palettes.ycbcr(y, cb, cr, 255 - t, false);
            if ((flags & 0x80) != 0 && entry < 4) {
                tables[0][entry] = color;
            }
            if ((flags & 0x40) != 0 && entry < 16) {
                tables[1][entry] = color;
            }
            if ((flags & 0x20) != 0) {
                tables[2][entry] = color;
            }
        }
    }

    private void objectData(byte[] data, int body, int length) {
        if (length < 3) {
            return;
        }
        var id = u16(data, body);
        var method = (data[body + 2] >> 2) & 3;
        var nonModifying = (data[body + 2] & 0x02) != 0;
        var end = body + length;
        for (var region : regions.values()) {
            for (var object : region.objects) {
                if (object[0] != id) {
                    continue;
                }
                if (method == 0 && length >= 7) {
                    var top = u16(data, body + 3);
                    var bottom = u16(data, body + 5);
                    var topStart = body + 7;
                    if (topStart + top > end) {
                        continue;
                    }
                    var bottomStart = topStart + top;
                    if (bottom == 0 || bottomStart + bottom > end) {
                        drawField(region, object[1], object[2], data, topStart, topStart + top, nonModifying);
                        drawField(region, object[1], object[2] + 1, data, topStart, topStart + top, nonModifying);
                    } else {
                        drawField(region, object[1], object[2], data, topStart, topStart + top, nonModifying);
                        drawField(region, object[1], object[2] + 1, data, bottomStart, bottomStart + bottom,
                                nonModifying);
                    }
                } else if (method == 2 && length >= 9) {
                    drawProgressive(region, object[1], object[2], data, body + 3, end, nonModifying);
                }
            }
        }
    }

    private void displayDefinition(byte[] data, int body, int length) {
        if (length < 5) {
            return;
        }
        var window = (data[body] & 0x08) != 0;
        var width = u16(data, body + 1) + 1;
        var height = u16(data, body + 3) + 1;
        LimitChecks.checkPicture(limits, width, height);
        displayWidth = width;
        displayHeight = height;
        windowX = 0;
        windowY = 0;
        if (window && length >= 13) {
            windowX = u16(data, body + 5);
            windowY = u16(data, body + 9);
        }
    }

    /**
     * Draws the pixel-data sub-blocks of one field of an object, every other line from a first one.
     *
     * <p>A byte that starts no known sub-block is skipped. With the code strings ending at the region's edge, this
     * reads the lines of ffmpeg's encoder (7.1, black box), which ends an 8-bit string that fills its line with a
     * single zero byte instead of the two of the end code.
     */
    private static void drawField(Region region, int x0, int y0, byte[] data, int start, int end,
                                  boolean nonModifying) {
        var map24 = MAP_2_TO_4.clone();
        var map28 = MAP_2_TO_8.clone();
        var map48 = MAP_4_TO_8.clone();
        var x = x0;
        var y = y0;
        var position = start;
        var reader = new MsbBitReader(data, start, end - start);
        while (position < end) {
            var type = data[position++] & 0xFF;
            switch (type) {
                case 0x10, 0x11, 0x12 -> {
                    reader.reset(data, position, end - position);
                    var bits = type == 0x10 ? 2 : type == 0x11 ? 4 : 8;
                    x = codeString(region, x, y, reader, bits, map24, map28, map48, nonModifying);
                    reader.alignToByte();
                    position += (int) (reader.bitPosition() >> 3);
                    if (reader.overrun()) {
                        return;
                    }
                }
                case 0x20 -> {
                    if (position + 2 > end) {
                        return;
                    }
                    for (var index = 0; index < 4; index++) {
                        map24[index] = (byte) ((data[position + index / 2] >> (index % 2 == 0 ? 4 : 0)) & 0xF);
                    }
                    position += 2;
                }
                case 0x21 -> {
                    if (position + 4 > end) {
                        return;
                    }
                    System.arraycopy(data, position, map28, 0, 4);
                    position += 4;
                }
                case 0x22 -> {
                    if (position + 16 > end) {
                        return;
                    }
                    System.arraycopy(data, position, map48, 0, 16);
                    position += 16;
                }
                case 0xF0 -> {
                    x = x0;
                    y += 2;
                }
                default -> {
                }
            }
        }
    }

    /**
     * Decodes one pixel code string and draws its runs, up to its end code or the right edge of the region.
     *
     * @return the column after the last pixel drawn
     */
    private static int codeString(Region region, int x, int y, MsbBitReader reader, int bits, byte[] map24,
                                  byte[] map28, byte[] map48, boolean nonModifying) {
        while (!reader.overrun() && x < region.width) {
            int run;
            int code;
            if (bits == 2) {
                code = reader.read(2);
                if (code != 0) {
                    run = 1;
                } else if (reader.readBit() == 1) {
                    run = reader.read(3) + 3;
                    code = reader.read(2);
                } else if (reader.readBit() == 1) {
                    run = 1;
                } else {
                    switch (reader.read(2)) {
                        case 0 -> {
                            return x;
                        }
                        case 1 -> run = 2;
                        case 2 -> {
                            run = reader.read(4) + 12;
                            code = reader.read(2);
                        }
                        default -> {
                            run = reader.read(8) + 29;
                            code = reader.read(2);
                        }
                    }
                }
            } else if (bits == 4) {
                code = reader.read(4);
                if (code != 0) {
                    run = 1;
                } else if (reader.readBit() == 0) {
                    var length = reader.read(3);
                    if (length == 0) {
                        return x;
                    }
                    run = length + 2;
                } else if (reader.readBit() == 0) {
                    run = reader.read(2) + 4;
                    code = reader.read(4);
                } else {
                    switch (reader.read(2)) {
                        case 0 -> run = 1;
                        case 1 -> run = 2;
                        case 2 -> {
                            run = reader.read(4) + 9;
                            code = reader.read(4);
                        }
                        default -> {
                            run = reader.read(8) + 25;
                            code = reader.read(4);
                        }
                    }
                }
            } else {
                code = reader.read(8);
                if (code != 0) {
                    run = 1;
                } else if (reader.readBit() == 0) {
                    run = reader.read(7);
                    if (run == 0) {
                        return x;
                    }
                } else {
                    run = reader.read(7);
                    code = reader.read(8);
                }
            }
            if (reader.overrun()) {
                return x;
            }
            draw(region, x, y, run, mapCode(region.depth, bits, code, map24, map28, map48),
                    nonModifying && code == 1);
            x += run;
        }
        return x;
    }

    /**
     * Maps a pixel code to an entry of the region's CLUT.
     */
    private static int mapCode(int depth, int bits, int code, byte[] map24, byte[] map28, byte[] map48) {
        if (bits == depth) {
            return code;
        }
        return switch (depth) {
            case 2 -> bits == 4 ? (code >> 3) << 1 | ((code & 7) != 0 ? 1 : 0)
                    : (code >> 7) << 1 | ((code & 0x70) != 0 ? 1 : 0);
            case 4 -> bits == 2 ? map24[code] & 0xF : code >> 4;
            default -> bits == 2 ? map28[code] & 0xFF : map48[code] & 0xFF;
        };
    }

    /**
     * Sets a run of pixels of a region line, clipped to the region.
     */
    private static void draw(Region region, int x, int y, int run, int entry, boolean skip) {
        if (skip || y < 0 || y >= region.height || x >= region.width) {
            return;
        }
        var from = y * region.width + Math.max(x, 0);
        var to = y * region.width + Math.min(x + run, region.width);
        if (to > from) {
            Arrays.fill(region.pixels, from, to, (byte) entry);
        }
    }

    /**
     * Draws a progressively coded object: zlib-compressed scanlines with PNG filters, one 8-bit code per pixel.
     */
    private void drawProgressive(Region region, int x0, int y0, byte[] data, int start, int end,
                                 boolean nonModifying) {
        var width = u16(data, start);
        var height = u16(data, start + 2);
        var length = u16(data, start + 4);
        if (width == 0 || height == 0 || start + 6 + length > end) {
            return;
        }
        LimitChecks.checkPicture(limits, width, height);
        var stride = width + 1;
        var raw = new byte[stride * height];
        var inflater = new Inflater();
        try {
            inflater.setInput(data, start + 6, length);
            var filled = 0;
            while (filled < raw.length && !inflater.finished()) {
                var count = inflater.inflate(raw, filled, raw.length - filled);
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                filled += count;
            }
            if (filled < raw.length) {
                throw new MediaException.Malformed("a progressive DVB subtitle object holds " + filled
                        + " of its " + raw.length + " bytes");
            }
        } catch (DataFormatException exception) {
            throw new MediaException.Malformed("a progressive DVB subtitle object is not a zlib stream: "
                    + exception.getMessage());
        } finally {
            inflater.end();
        }
        var line = new byte[width];
        var previous = new byte[width];
        for (var row = 0; row < height; row++) {
            var filter = raw[row * stride] & 0xFF;
            for (var column = 0; column < width; column++) {
                var value = raw[row * stride + 1 + column] & 0xFF;
                var left = column > 0 ? line[column - 1] & 0xFF : 0;
                var up = previous[column] & 0xFF;
                var corner = column > 0 ? previous[column - 1] & 0xFF : 0;
                value += switch (filter) {
                    case 1 -> left;
                    case 2 -> up;
                    case 3 -> (left + up) >> 1;
                    case 4 -> paeth(left, up, corner);
                    default -> 0;
                };
                line[column] = (byte) value;
            }
            for (var column = 0; column < width; column++) {
                var code = line[column] & 0xFF;
                draw(region, x0 + column, y0 + row, 1, mapCode(region.depth, 8, code, null, null, null),
                        nonModifying && code == 1);
            }
            var swap = previous;
            previous = line;
            line = swap;
        }
    }

    private static int paeth(int a, int b, int c) {
        var p = a + b - c;
        var pa = Math.abs(p - a);
        var pb = Math.abs(p - b);
        var pc = Math.abs(p - c);
        return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
    }

    /**
     * Returns the CLUT family with an identifier, creating it with the default contents.
     *
     * @return the 4-, 16- and 256-entry tables
     */
    private int[][] clut(int id) {
        return cluts.computeIfAbsent(id, _ -> new int[][]{DEFAULT_CLUT_4.clone(), DEFAULT_CLUT_16.clone(),
                DEFAULT_CLUT_256.clone()});
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 8 | (data[offset + 1] & 0xFF);
    }

    /**
     * Returns the default 4-entry CLUT.
     */
    static int[] default4() {
        return new int[]{Palettes.TRANSPARENT, Palettes.rgba(255, 255, 255, 255), Palettes.rgba(0, 0, 0, 255),
                Palettes.rgba(128, 128, 128, 255)};
    }

    /**
     * Returns the default 16-entry CLUT.
     */
    static int[] default16() {
        var clut = new int[16];
        for (var entry = 1; entry < 16; entry++) {
            var level = (entry & 8) == 0 ? 255 : 128;
            clut[entry] = Palettes.rgba(level * (entry & 1), level * (entry >> 1 & 1), level * (entry >> 2 & 1), 255);
        }
        return clut;
    }

    /**
     * Returns the default 256-entry CLUT.
     */
    static int[] default256() {
        var clut = new int[256];
        for (var entry = 1; entry < 256; entry++) {
            var b1 = entry >> 7 & 1;
            var b2 = entry >> 6 & 1;
            var b3 = entry >> 5 & 1;
            var b4 = entry >> 4 & 1;
            var b5 = entry >> 3 & 1;
            var b6 = entry >> 2 & 1;
            var b7 = entry >> 1 & 1;
            var b8 = entry & 1;
            if (b1 == 0) {
                if (b5 == 0 && b2 == 0 && b3 == 0 && b4 == 0) {
                    clut[entry] = Palettes.rgba(255 * b8, 255 * b7, 255 * b6, level(25));
                } else {
                    clut[entry] = Palettes.rgba(level(33.3 * b8 + 66.7 * b4), level(33.3 * b7 + 66.7 * b3),
                            level(33.3 * b6 + 66.7 * b2), b5 == 0 ? 255 : level(50));
                }
            } else {
                var base = b5 == 0 ? 50 : 0;
                clut[entry] = Palettes.rgba(level(16.7 * b8 + 33.3 * b4 + base), level(16.7 * b7 + 33.3 * b3 + base),
                        level(16.7 * b6 + 33.3 * b2 + base), 255);
            }
        }
        return clut;
    }

    /**
     * Returns the 8-bit level of a percentage of the tables of clause 10, rounded.
     *
     * @param percent the percentage, 0 to 100
     * @return the level, 0 to 255
     */
    private static int level(double percent) {
        return (int) Math.round(percent * 255 / 100);
    }

    /**
     * Holds one region of the page: its pixel codes and the objects drawn in it.
     */
    private static final class Region {
        final int width;
        final int height;
        final int depth;
        final byte[] pixels;
        int clutId;
        List<int[]> objects = List.of();

        Region(int width, int height, int depth) {
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.pixels = new byte[width * height];
        }
    }
}
