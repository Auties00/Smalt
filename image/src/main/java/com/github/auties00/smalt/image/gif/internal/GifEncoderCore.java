package com.github.auties00.smalt.image.gif.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.gif.GifEncoderOptions;
import com.github.auties00.smalt.image.ops.Dithering;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.quantize.ExactColors;
import com.github.auties00.smalt.internal.quantize.IndexMapper;
import com.github.auties00.smalt.internal.quantize.MedianCut;
import com.github.auties00.smalt.internal.quantize.NeuQuant;
import com.github.auties00.smalt.internal.quantize.Palette;

import java.util.Arrays;

/**
 * Encodes a GIF89a datastream frame by frame: the shared engine of the still and animation
 * encoders.
 *
 * <p>Each added frame is reduced to binary alpha (a pixel below the alpha threshold becomes
 * transparent) and compared with the canvas a decoder shows before it, which is tracked as the
 * source colors of the frames that painted it. A frame is coded as the smallest rectangle that
 * covers its changes; inside it, pixels whose color the canvas already shows are coded with the
 * transparent index, so the decoder keeps them. The disposal of each frame is chosen when the next
 * frame arrives: "do not dispose" unless the next frame turns opaque pixels transparent, which only
 * "restore to background" can do, or restoring to background makes the next frame cheaper. A
 * frame identical to the previous one is not coded: its delay is added to the previous frame's.
 *
 * <p>The first frame's palette is the global color table. Every other frame either uses it or
 * carries a local color table, as {@link GifEncoderOptions#paletteMode()} decides. A palette is
 * exact whenever the coded pixels have few enough distinct colors; otherwise it is built by
 * {@link NeuQuant} or {@link MedianCut} and the pixels are mapped with {@link IndexMapper}. The
 * image data is compressed with {@link GifLzwEncoder}.
 *
 * <p>Nothing is written before the first frame is final. A datastream of a single frame is a
 * still image: no looping extension, and a graphic control extension only when the image has
 * transparent pixels. The ICC profile of {@link GifEncoderOptions#colorProfile()} follows the
 * global color table and the looping extension, in a {@link GifIccExtension}. An instance is
 * single-use and not thread-safe.
 *
 * @implNote This implementation keeps three canvas-sized arrays of packed colors: the frame
 * waiting for its disposal decision, the canvas under it, and the incoming frame. Choosing the
 * disposal one frame late is how libwebp's animation encoder chooses it as well. The cost model
 * of the choice counts each coded opaque pixel as one unit and each transparent pixel inside a
 * rectangle as an eighth, since runs of the transparent index compress to a few bits.
 */
public final class GifEncoderCore {
    /**
     * The largest delay of a frame, in hundredths of a second.
     */
    public static final int MAX_DELAY = 65_535;

    /**
     * The largest canvas dimension a GIF stores.
     */
    public static final int MAX_DIMENSION = 65_535;

    /**
     * The disposal method that leaves the frame in place.
     */
    private static final int DISPOSE_NONE = 1;

    /**
     * The disposal method that clears the frame's rectangle to transparent.
     */
    private static final int DISPOSE_BACKGROUND = 2;

    /**
     * How much larger, relatively, the error of mapping a frame to the global palette may be
     * than to a palette of its own, for {@link GifEncoderOptions.PaletteMode#AUTO} to still choose
     * the global palette.
     */
    private static final double REUSE_RATIO = 1.15;

    /**
     * The squared error per sampled pixel that {@link GifEncoderOptions.PaletteMode#AUTO}
     * tolerates on top of {@link #REUSE_RATIO}, below what the eye can tell.
     */
    private static final int REUSE_SLACK = 4;

    /**
     * The largest number of pixels sampled to compare palette errors.
     */
    private static final int ERROR_SAMPLES = 16_384;

    /**
     * The canvas width.
     */
    private final int width;

    /**
     * The canvas height.
     */
    private final int height;

    /**
     * The encoder settings.
     */
    private final GifEncoderOptions options;

    /**
     * The dithering method of the options.
     */
    private final Dithering method;

    /**
     * The datastream.
     */
    private final GifOutput out;

    /**
     * The LZW compressor.
     */
    private final GifLzwEncoder lzw = new GifLzwEncoder();

    /**
     * The index mapper.
     */
    private final IndexMapper mapper = new IndexMapper();

    /**
     * The exact color collector.
     */
    private final ExactColors exact = new ExactColors();

    /**
     * The canvas a decoder shows before the pending frame, as packed colors (0 for
     * transparent).
     */
    private int[] base;

    /**
     * The pending frame: the last added frame whose disposal is not decided yet.
     */
    private int[] pending;

    /**
     * The scratch canvas the incoming frame is classified into.
     */
    private int[] incoming;

    /**
     * Whether a frame is pending.
     */
    private boolean hasPending;

    /**
     * The delay of the pending frame, in hundredths of a second.
     */
    private int pendingDelay;

    /**
     * The left edge of the pending frame's rectangle.
     */
    private int left;

    /**
     * The top edge of the pending frame's rectangle.
     */
    private int top;

    /**
     * The right edge (exclusive) of the pending frame's rectangle.
     */
    private int right;

    /**
     * The bottom edge (exclusive) of the pending frame's rectangle.
     */
    private int bottom;

    /**
     * Whether the datastream is animated: decided when the first frame is written.
     */
    private boolean animated;

    /**
     * Whether the header has been written.
     */
    private boolean started;

    /**
     * The global palette, or {@code null} before the first frame is written.
     */
    private Palette global;

    /**
     * The transparent index of the global color table, or {@code -1} when it has none.
     */
    private int globalTransparent = -1;

    /**
     * The coded pixels of a rectangle: the color of each opaque pixel, 0 for a transparent one.
     */
    private int[] work = new int[0];

    /**
     * The colors of the opaque pixels of a rectangle, in raster order.
     */
    private int[] emitted = new int[0];

    /**
     * The palette indices of a rectangle, row-major.
     */
    private byte[] indices = new byte[0];

    /**
     * The palette indices of a rectangle in interlaced row order.
     */
    private byte[] interlacedIndices = new byte[0];

    /**
     * Constructs an encoder.
     *
     * @param width   the canvas width, from 1 to {@link #MAX_DIMENSION}
     * @param height  the canvas height, from 1 to {@link #MAX_DIMENSION}
     * @param options the encoder settings
     */
    public GifEncoderCore(int width, int height, GifEncoderOptions options) {
        this.width = width;
        this.height = height;
        this.options = options;
        this.method = options.dithering();
        var pixels = Math.multiplyExact(width, height);
        this.base = new int[pixels];
        this.pending = new int[pixels];
        this.incoming = new int[pixels];
        this.out = new GifOutput((int) Math.min(1 << 20, pixels / 2L + 1024));
    }

    /**
     * Adds a frame.
     *
     * @param pixels   the packed RGBA samples of a canvas-sized picture
     * @param stride   the distance in bytes between rows
     * @param hasAlpha whether the alpha samples are meaningful
     * @param delay    the delay in hundredths of a second, from 0 to {@link #MAX_DELAY}
     * @throws MediaException.Unsupported if lossless coding is requested and the frame has
     *                                    translucent pixels or too many colors
     */
    public void add(byte[] pixels, int stride, boolean hasAlpha, int delay) {
        classify(pixels, stride, hasAlpha, incoming);
        if (!hasPending) {
            swapIncoming();
            left = 0;
            top = 0;
            right = width;
            bottom = height;
            pendingDelay = delay;
            hasPending = true;
            return;
        }
        if (pendingDelay + delay <= MAX_DELAY && Arrays.equals(incoming, pending)) {
            pendingDelay += delay;
            return;
        }
        advance(delay);
    }

    /**
     * Writes the pending frame and the trailer and returns the datastream.
     *
     * @return the GIF datastream
     * @throws IllegalStateException      if no frame was added
     * @throws MediaException.Unsupported if lossless coding is requested and the last frame
     *                                    cannot be coded exactly
     */
    public byte[] finish() {
        if (!hasPending) {
            throw new IllegalStateException("no frame was added");
        }
        write(pending, base, left, top, right, bottom, DISPOSE_NONE, pendingDelay, true);
        out.write(0x3B);
        return out.toByteArray();
    }

    /**
     * Decides the disposal of the pending frame from the incoming one, writes the pending frame,
     * and makes the incoming frame pending.
     *
     * @param delay the delay of the incoming frame
     */
    private void advance(int delay) {
        var next = incoming;
        var previous = pending;
        var noneLeft = width;
        var noneTop = height;
        var noneRight = 0;
        var noneBottom = 0;
        var noneCount = 0L;
        var clearLeft = left;
        var clearTop = top;
        var clearRight = right;
        var clearBottom = bottom;
        var unpaint = false;
        for (var y = 0; y < height; y++) {
            var row = y * width;
            for (var x = 0; x < width; x++) {
                var a = next[row + x];
                var b = previous[row + x];
                if (a != b) {
                    noneCount++;
                    noneLeft = Math.min(noneLeft, x);
                    noneRight = Math.max(noneRight, x + 1);
                    noneTop = Math.min(noneTop, y);
                    noneBottom = y + 1;
                    if (a == 0) {
                        unpaint = true;
                        clearLeft = Math.min(clearLeft, x);
                        clearRight = Math.max(clearRight, x + 1);
                        clearTop = Math.min(clearTop, y);
                        clearBottom = Math.max(clearBottom, y + 1);
                    }
                }
            }
        }
        var backLeft = width;
        var backTop = height;
        var backRight = 0;
        var backBottom = 0;
        var backCount = 0L;
        for (var y = 0; y < height; y++) {
            var row = y * width;
            var insideRows = y >= clearTop && y < clearBottom;
            for (var x = 0; x < width; x++) {
                var a = next[row + x];
                var changed = insideRows && x >= clearLeft && x < clearRight ? a != 0 : a != previous[row + x];
                if (changed) {
                    backCount++;
                    backLeft = Math.min(backLeft, x);
                    backRight = Math.max(backRight, x + 1);
                    backTop = Math.min(backTop, y);
                    backBottom = y + 1;
                }
            }
        }
        var noneCost = cost(noneLeft, noneTop, noneRight, noneBottom, noneCount);
        var clearGrowth = area(clearLeft, clearTop, clearRight, clearBottom) - area(left, top, right, bottom);
        var backCost = cost(backLeft, backTop, backRight, backBottom, backCount) + clearGrowth / 8.0;
        var background = unpaint || backCost < noneCost;
        if (background) {
            write(previous, base, clearLeft, clearTop, clearRight, clearBottom, DISPOSE_BACKGROUND, pendingDelay, false);
        } else {
            write(previous, base, left, top, right, bottom, DISPOSE_NONE, pendingDelay, false);
        }
        var recycled = base;
        base = previous;
        pending = next;
        incoming = recycled;
        if (background) {
            for (var y = clearTop; y < clearBottom; y++) {
                Arrays.fill(base, y * width + clearLeft, y * width + clearRight, 0);
            }
            setRectangle(backLeft, backTop, backRight, backBottom);
        } else {
            setRectangle(noneLeft, noneTop, noneRight, noneBottom);
        }
        pendingDelay = delay;
    }

    /**
     * Makes the incoming canvas pending, recycling the pending one.
     */
    private void swapIncoming() {
        var recycled = pending;
        pending = incoming;
        incoming = recycled;
    }

    /**
     * Sets the pending frame's rectangle, using the top-left pixel when the rectangle is empty
     * (the frame changes nothing but must still carry its delay).
     *
     * @param x0 the left edge
     * @param y0 the top edge
     * @param x1 the right edge, exclusive
     * @param y1 the bottom edge, exclusive
     */
    private void setRectangle(int x0, int y0, int x1, int y1) {
        if (x1 <= x0 || y1 <= y0) {
            left = 0;
            top = 0;
            right = 1;
            bottom = 1;
        } else {
            left = x0;
            top = y0;
            right = x1;
            bottom = y1;
        }
    }

    /**
     * Returns the area of a rectangle.
     *
     * @param x0 the left edge
     * @param y0 the top edge
     * @param x1 the right edge, exclusive
     * @param y1 the bottom edge, exclusive
     * @return the area, {@code 0} for an empty rectangle
     */
    private static long area(int x0, int y0, int x1, int y1) {
        return x1 <= x0 || y1 <= y0 ? 0 : (long) (x1 - x0) * (y1 - y0);
    }

    /**
     * Estimates the coding cost of a frame rectangle.
     *
     * @param x0      the left edge
     * @param y0      the top edge
     * @param x1      the right edge, exclusive
     * @param y1      the bottom edge, exclusive
     * @param changed the number of opaque pixels coded in it
     * @return the estimate, in units of one coded pixel
     */
    private static double cost(int x0, int y0, int x1, int y1, long changed) {
        return changed + (area(x0, y0, x1, y1) - changed) / 8.0;
    }

    /**
     * Reduces the pixels of a picture to packed colors with binary alpha.
     *
     * @param pixels   the packed RGBA samples
     * @param stride   the distance in bytes between rows
     * @param hasAlpha whether the alpha samples are meaningful
     * @param target   the canvas: a color with alpha byte 255 for each opaque pixel, 0 for each
     *                 transparent one
     * @throws MediaException.Unsupported if lossless coding is requested and a pixel is translucent
     */
    private void classify(byte[] pixels, int stride, boolean hasAlpha, int[] target) {
        var threshold = hasAlpha ? options.alphaThreshold() : 0;
        var strict = hasAlpha && options.isLossless();
        for (var y = 0; y < height; y++) {
            var source = y * stride;
            var row = y * width;
            for (var x = 0; x < width; x++) {
                var pixel = ByteArrays.i32le(pixels, source + x * 4);
                var alpha = pixel >>> 24;
                if (strict && alpha != 0 && alpha != 0xFF) {
                    throw new MediaException.Unsupported("gif",
                            "translucent pixels cannot be coded losslessly: GIF transparency is binary");
                }
                target[row + x] = alpha >= threshold ? pixel | 0xFF00_0000 : 0;
            }
        }
    }

    /**
     * Codes one frame: chooses its palette, maps its pixels, and writes its graphic control
     * extension, image descriptor, color table and image data. The first frame written also
     * writes the header, with its palette as the global color table.
     *
     * @param frame   the frame's canvas
     * @param under   the canvas a decoder shows before it
     * @param x0      the left edge of the rectangle
     * @param y0      the top edge of the rectangle
     * @param x1      the right edge of the rectangle, exclusive
     * @param y1      the bottom edge of the rectangle, exclusive
     * @param dispose the disposal method
     * @param delay   the delay in hundredths of a second
     * @param last    whether no frame follows
     */
    private void write(int[] frame, int[] under, int x0, int y0, int x1, int y1, int dispose, int delay,
                       boolean last) {
        var first = !started;
        if (first) {
            animated = !last;
        }
        var w = x1 - x0;
        var h = y1 - y0;
        var area = w * h;
        if (work.length < area) {
            work = new int[area];
            emitted = new int[area];
            indices = new byte[area];
        }
        var emitCount = 0;
        for (var y = 0; y < h; y++) {
            var row = (y0 + y) * width + x0;
            var target = y * w;
            for (var x = 0; x < w; x++) {
                var color = frame[row + x];
                if (color != 0 && color != under[row + x]) {
                    work[target + x] = color;
                    emitted[emitCount++] = color;
                } else {
                    work[target + x] = 0;
                }
            }
        }
        var needsTransparency = emitCount < area || dispose == DISPOSE_BACKGROUND;
        var limit = options.maxColors() - (needsTransparency ? 1 : 0);
        Palette palette;
        var exactPalette = true;
        if (emitCount == 0) {
            palette = usable(true) ? global : new Palette(new int[]{0});
        } else if (exact.collect(emitted, emitCount, limit)) {
            var inGlobal = usable(needsTransparency) && global.containsAll(exact.colors(), exact.size());
            if (inGlobal) {
                palette = global;
            } else if (usable(needsTransparency) && options.paletteMode() == GifEncoderOptions.PaletteMode.GLOBAL
                    && !options.isLossless()) {
                palette = global;
                exactPalette = false;
            } else {
                palette = new Palette(exact.colors(), exact.size());
            }
        } else {
            if (options.isLossless()) {
                throw new MediaException.Unsupported("gif", "an image of more than " + limit
                        + " colors cannot be coded losslessly");
            }
            exactPalette = false;
            if (usable(needsTransparency) && options.paletteMode() == GifEncoderOptions.PaletteMode.GLOBAL) {
                palette = global;
            } else {
                var reserve = first && animated && !needsTransparency ? 1 : 0;
                palette = choose(new Palette(quantize(emitCount, limit - reserve)), emitCount, needsTransparency);
            }
        }
        var isGlobal = palette == global;
        int transparent;
        if (isGlobal) {
            transparent = needsTransparency ? globalTransparent : -1;
        } else {
            var slot = needsTransparency || (first && animated && palette.size() < 256) ? palette.size() : -1;
            if (first) {
                global = palette;
                globalTransparent = slot;
                isGlobal = true;
                writeHeader();
            }
            transparent = needsTransparency ? slot : -1;
        }
        mapper.map(work, w, h, palette, Math.max(transparent, 0), exactPalette ? Dithering.NONE : method, indices);
        if (animated || transparent >= 0) {
            out.write(0x21);
            out.write(0xF9);
            out.write(4);
            out.write((animated ? dispose << 2 : 0) | (transparent >= 0 ? 1 : 0));
            out.write16(animated ? delay : 0);
            out.write(Math.max(transparent, 0));
            out.write(0);
        }
        var tableBits = isGlobal ? tableBits(global, globalTransparent) : tableBits(palette, transparent);
        out.write(0x2C);
        out.write16(x0);
        out.write16(y0);
        out.write16(w);
        out.write16(h);
        out.write((isGlobal ? 0 : 0x80 | (tableBits - 1)) | (options.isInterlaced() ? 0x40 : 0));
        if (!isGlobal) {
            writeTable(palette, tableBits);
        }
        var data = indices;
        if (options.isInterlaced() && h > 1) {
            data = interlace(w, h);
        }
        lzw.encode(data, 0, area, Math.max(2, tableBits), out);
    }

    /**
     * Returns the palette a quantized frame uses: under
     * {@link GifEncoderOptions.PaletteMode#AUTO}, the global palette when mapping a sample of the
     * frame's pixels to it is about as accurate as to the frame's own palette, and the frame's own
     * palette otherwise.
     *
     * @param fresh             the palette built for the frame
     * @param count             the number of opaque pixels in {@link #emitted}
     * @param needsTransparency whether the frame needs a transparent index
     * @return the palette to use
     */
    private Palette choose(Palette fresh, int count, boolean needsTransparency) {
        if (!usable(needsTransparency) || options.paletteMode() != GifEncoderOptions.PaletteMode.AUTO) {
            return fresh;
        }
        var step = Math.max(1, count / ERROR_SAMPLES);
        var globalError = 0L;
        var freshError = 0L;
        var samples = 0L;
        for (var i = 0; i < count; i += step) {
            globalError += global.nearest(emitted[i]) >>> 8;
            freshError += fresh.nearest(emitted[i]) >>> 8;
            samples++;
        }
        return globalError <= freshError * REUSE_RATIO + samples * REUSE_SLACK ? global : fresh;
    }

    /**
     * Returns whether the global palette can code a frame.
     *
     * @param needsTransparency whether the frame needs a transparent index
     * @return {@code true} when the global palette exists and has a transparent entry if needed
     */
    private boolean usable(boolean needsTransparency) {
        return global != null && (!needsTransparency || globalTransparent >= 0);
    }

    /**
     * Builds a palette for the opaque pixels collected in {@link #emitted}.
     *
     * @param count    the number of pixels
     * @param capacity the largest palette size
     * @return the palette entries, packed RGB
     */
    private int[] quantize(int count, int capacity) {
        return switch (options.method()) {
            case NEUQUANT -> NeuQuant.quantize(emitted, count, capacity, options.sampleFactor());
            case MEDIAN_CUT -> MedianCut.quantize(emitted, count, capacity);
        };
    }

    /**
     * Returns the number of bits of the color table holding a palette and its transparent entry.
     *
     * @param palette     the palette the table holds
     * @param transparent the transparent index, or {@code -1}
     * @return the bits, from 1 to 8: the table holds {@code 1 << bits} entries
     */
    private static int tableBits(Palette palette, int transparent) {
        var entries = palette.size() + (transparent >= 0 ? 1 : 0);
        var bits = 1;
        while (1 << bits < entries) {
            bits++;
        }
        return bits;
    }

    /**
     * Writes a color table padded with black to its power-of-two size.
     *
     * @param palette the palette to write
     * @param bits    the table bits
     */
    private void writeTable(Palette palette, int bits) {
        var entries = 1 << bits;
        for (var i = 0; i < entries; i++) {
            var color = i < palette.size() ? palette.color(i) : 0;
            out.write(color & 0xFF);
            out.write((color >>> 8) & 0xFF);
            out.write((color >>> 16) & 0xFF);
        }
    }

    /**
     * Writes the signature, the logical screen descriptor with the global color table, the
     * looping extension of an animation, and the ICC profile extension.
     */
    private void writeHeader() {
        started = true;
        out.write('G');
        out.write('I');
        out.write('F');
        out.write('8');
        out.write('9');
        out.write('a');
        out.write16(width);
        out.write16(height);
        var bits = tableBits(global, globalTransparent);
        out.write(0x80 | 0x70 | (bits - 1));
        out.write(Math.max(globalTransparent, 0));
        out.write(0);
        writeTable(global, bits);
        var plays = options.loopCount();
        if (animated && plays != 1) {
            out.write(0x21);
            out.write(0xFF);
            out.write(11);
            for (var c : "NETSCAPE2.0".toCharArray()) {
                out.write(c);
            }
            out.write(3);
            out.write(1);
            out.write16(plays == 0 ? 0 : plays - 1);
            out.write(0);
        }
        options.colorProfile().ifPresent(profile -> GifIccExtension.write(out, profile.toByteArray()));
    }

    /**
     * Reorders the rows of {@link #indices} into the four passes of GIF interlacing: every eighth
     * row from row 0, every eighth from row 4, every fourth from row 2, every second from row 1.
     *
     * @param w the rectangle width
     * @param h the rectangle height
     * @return the reordered indices
     */
    private byte[] interlace(int w, int h) {
        var area = w * h;
        if (interlacedIndices.length < area) {
            interlacedIndices = new byte[area];
        }
        var target = 0;
        for (var pass = 0; pass < 4; pass++) {
            var start = pass == 0 ? 0 : pass == 1 ? 4 : pass == 2 ? 2 : 1;
            var step = pass == 0 ? 8 : pass == 1 ? 8 : pass == 2 ? 4 : 2;
            for (var y = start; y < h; y += step) {
                System.arraycopy(indices, y * w, interlacedIndices, target, w);
                target += w;
            }
        }
        return interlacedIndices;
    }
}
