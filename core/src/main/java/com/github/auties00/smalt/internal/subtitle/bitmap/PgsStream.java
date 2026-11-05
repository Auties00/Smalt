package com.github.auties00.smalt.internal.subtitle.bitmap;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.subtitle.BitmapRegion;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decodes Blu-ray presentation graphic streams: the presentation composition, window, palette and object
 * definition segments of each display set.
 *
 * <p>A display set is shown at its end segment, at the time of the packet that carried its presentation
 * composition; each composition object becomes one region, cut to its cropping rectangle. Palette entries are
 * BT.709 colors for a composition of more than 576 lines and BT.601 colors otherwise; entries a palette does not
 * define are transparent.
 */
public final class PgsStream implements BitmapStream {
    private static final int PALETTE = 0x14;
    private static final int OBJECT = 0x15;
    private static final int COMPOSITION = 0x16;
    private static final int WINDOW = 0x17;
    private static final int END = 0x80;

    /**
     * The largest object, in bytes of run-length data, kept while its fragments arrive.
     */
    private static final int MAX_OBJECT_BYTES = 1 << 24;

    private final MediaLimits limits;
    private final Map<Integer, int[]> palettes = new HashMap<>();
    private final Map<Integer, PgsObject> objects = new HashMap<>();

    /**
     * The composition waiting for its end segment, or {@code null}.
     */
    private Composition composition;

    /**
     * Constructs a decoder.
     *
     * @param limits the bounds of the composition and the objects
     */
    public PgsStream(MediaLimits limits) {
        this.limits = limits;
    }

    @Override
    public void reset() {
        palettes.clear();
        objects.clear();
        composition = null;
    }

    @Override
    public void decode(byte[] data, long micros, Sink sink) {
        var position = 0;
        while (position + 3 <= data.length) {
            var type = data[position] & 0xFF;
            var length = (data[position + 1] & 0xFF) << 8 | (data[position + 2] & 0xFF);
            var body = position + 3;
            if (body + length > data.length) {
                break;
            }
            switch (type) {
                case COMPOSITION -> composition(data, body, length, micros);
                case PALETTE -> palette(data, body, length);
                case OBJECT -> object(data, body, length);
                case END -> {
                    if (composition != null) {
                        show(sink);
                    }
                }
                default -> {
                }
            }
            position = body + length;
        }
    }

    private void composition(byte[] data, int body, int length, long micros) {
        if (length < 11) {
            composition = null;
            return;
        }
        var width = u16(data, body);
        var height = u16(data, body + 2);
        LimitChecks.checkPicture(limits, width, height);
        var state = data[body + 7] & 0xC0;
        if (state == 0x80) {
            palettes.clear();
            objects.clear();
        }
        var next = new Composition(width, height, data[body + 9] & 0xFF, micros);
        var count = data[body + 10] & 0xFF;
        var position = body + 11;
        var end = body + length;
        for (var index = 0; index < count && position + 8 <= end; index++) {
            var flags = data[position + 3] & 0xFF;
            var cropped = (flags & 0x80) != 0;
            if (cropped && position + 16 > end) {
                break;
            }
            var item = new int[]{u16(data, position), u16(data, position + 4), u16(data, position + 6),
                    cropped ? u16(data, position + 8) : 0, cropped ? u16(data, position + 10) : 0,
                    cropped ? u16(data, position + 12) : Integer.MAX_VALUE,
                    cropped ? u16(data, position + 14) : Integer.MAX_VALUE};
            next.objects.add(item);
            next.forced |= (flags & 0x40) != 0;
            position += cropped ? 16 : 8;
        }
        composition = next;
    }

    private void palette(byte[] data, int body, int length) {
        if (length < 2) {
            return;
        }
        var palette = palettes.computeIfAbsent(data[body] & 0xFF, _ -> new int[256]);
        var hd = composition == null || composition.height > 576;
        for (var position = body + 2; position + 5 <= body + length; position += 5) {
            var entry = data[position] & 0xFF;
            var y = data[position + 1] & 0xFF;
            var cr = data[position + 2] & 0xFF;
            var cb = data[position + 3] & 0xFF;
            var alpha = data[position + 4] & 0xFF;
            palette[entry] = Palettes.ycbcr(y, cb, cr, alpha, hd);
        }
    }

    private void object(byte[] data, int body, int length) {
        if (length < 4) {
            return;
        }
        var id = u16(data, body);
        var sequence = data[body + 3] & 0xC0;
        var start = body + 4;
        var end = body + length;
        PgsObject object;
        if ((sequence & 0x80) != 0) {
            if (length < 11) {
                return;
            }
            var width = u16(data, body + 7);
            var height = u16(data, body + 9);
            if (width == 0 || height == 0) {
                objects.remove(id);
                return;
            }
            LimitChecks.checkPicture(limits, width, height);
            object = new PgsObject(width, height);
            objects.put(id, object);
            start = body + 11;
        } else {
            object = objects.get(id);
            if (object == null || object.complete) {
                return;
            }
        }
        if (object.rle.size() + (end - start) > MAX_OBJECT_BYTES) {
            objects.remove(id);
            return;
        }
        object.rle.write(data, start, end - start);
        if ((sequence & 0x40) != 0) {
            object.complete = true;
        }
    }

    private void show(Sink sink) {
        var shown = composition;
        composition = null;
        var palette = palettes.getOrDefault(shown.paletteId, new int[256]);
        var regions = new ArrayList<BitmapRegion>();
        for (var item : shown.objects) {
            var object = objects.get(item[0]);
            if (object == null) {
                continue;
            }
            var indices = object.decode();
            var cropX = Math.min(item[3], object.width);
            var cropY = Math.min(item[4], object.height);
            var width = Math.min(item[5], object.width - cropX);
            var height = Math.min(item[6], object.height - cropY);
            if (width <= 0 || height <= 0) {
                continue;
            }
            var cut = indices;
            if (width != object.width || height != object.height) {
                cut = new byte[width * height];
                for (var row = 0; row < height; row++) {
                    System.arraycopy(indices, (cropY + row) * object.width + cropX, cut, row * width, width);
                }
            }
            if (Palettes.visible(cut, cut.length, palette)) {
                regions.add(new BitmapRegion(item[1], item[2], Palettes.paint(cut, width, height, palette)));
            }
        }
        if (regions.isEmpty()) {
            sink.clear(shown.micros);
        } else {
            sink.show(shown.micros, new Screen(shown.width, shown.height, regions, shown.forced), NO_DEADLINE);
        }
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 8 | (data[offset + 1] & 0xFF);
    }

    /**
     * Holds a presentation composition: the canvas, the palette and the composition objects (object
     * identifier, position, cropping rectangle, seven ints each).
     */
    private static final class Composition {
        final int width;
        final int height;
        final int paletteId;
        final long micros;
        final List<int[]> objects = new ArrayList<>();
        boolean forced;

        Composition(int width, int height, int paletteId, long micros) {
            this.width = width;
            this.height = height;
            this.paletteId = paletteId;
            this.micros = micros;
        }
    }

    /**
     * Holds the run-length coded pixels of an object as its fragments arrive.
     */
    private static final class PgsObject {
        final int width;
        final int height;
        final ByteArrayOutputStream rle = new ByteArrayOutputStream();
        boolean complete;
        byte[] decoded;

        PgsObject(int width, int height) {
            this.width = width;
            this.height = height;
        }

        /**
         * Decodes the run-length data, once the object is complete and on every call before: every line ends with a
         * zero byte followed by a zero byte; a line cut short leaves its other pixels at index 0, a line too long is
         * cut.
         *
         * @return the palette index of every pixel
         */
        byte[] decode() {
            if (decoded != null) {
                return decoded;
            }
            var pixels = new byte[width * height];
            var data = rle.toByteArray();
            var x = 0;
            var y = 0;
            var position = 0;
            while (position < data.length && y < height) {
                var value = data[position++] & 0xFF;
                int run;
                int color;
                if (value != 0) {
                    run = 1;
                    color = value;
                } else {
                    if (position >= data.length) {
                        break;
                    }
                    var flags = data[position++] & 0xFF;
                    if (flags == 0) {
                        x = 0;
                        y++;
                        continue;
                    }
                    run = flags & 0x3F;
                    if ((flags & 0x40) != 0) {
                        if (position >= data.length) {
                            break;
                        }
                        run = run << 8 | (data[position++] & 0xFF);
                    }
                    if ((flags & 0x80) != 0) {
                        if (position >= data.length) {
                            break;
                        }
                        color = data[position++] & 0xFF;
                    } else {
                        color = 0;
                    }
                }
                var to = Math.min(x + run, width);
                if (to > x && color != 0) {
                    Arrays.fill(pixels, y * width + x, y * width + to, (byte) color);
                }
                x = Math.max(x, to);
            }
            if (complete) {
                decoded = pixels;
            }
            return pixels;
        }
    }
}
