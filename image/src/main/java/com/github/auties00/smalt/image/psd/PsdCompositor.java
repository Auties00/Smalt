/* Ports psd-tools (main branch, commit 9a2e523: src/psd_tools/composite/composite.py). Copyright (c) 2019
 * Kota Yamaguchi; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.psd;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.psd.PsdLayers.Layer;
import com.github.auties00.smalt.image.psd.PsdLayers.Mask;
import com.github.auties00.smalt.image.psd.PsdLayers.Rect;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Renders the composite of a Photoshop document from its layers.
 *
 * <p>Colors are composited as straight (not premultiplied) values scaled to 0..1, CMYK as the stored complement of
 * the ink amounts. A source of color {@code cs} and alpha {@code as} over a backdrop of color {@code cb} and alpha
 * {@code ab} gives alpha {@code ar = ab + as - ab * as} and color
 * {@code ((1 - as) * ab * cb + as * (1 - ab) * cs + as * ab * B(cb, cs)) / ar}, {@code B} being the layer's blend
 * mode. A layer's alpha is its transparency times its masks, opacity and fill opacity. A group is isolated: its
 * layers composite onto a transparent canvas, which then composites as one layer; a pass-through group composites
 * its layers onto the canvas below it and then mixes the canvas before and after by its opacity and mask. A
 * clipping group composites its clipped layers over its base's color, whose alpha there is the base's fill opacity;
 * the result, masked by the base's transparency and masks, composites with the base's blend mode and opacity. A
 * pass-through group that is the base of a clipping group composites its layers onto a copy of the canvas below it
 * and takes the result of that non-isolated group as the base.
 *
 * <p>Instances are not thread-safe.
 */
final class PsdCompositor {
    private final byte[] data;
    private final boolean large;
    private final int depth;
    private final int colors;
    private final int model;
    private final Rect document;
    private final MediaLimits limits;
    private long allocated;

    /**
     * Constructs a compositor of a document's layers.
     *
     * @param data the document
     * @param large whether the document is a PSB
     * @param width the document width
     * @param height the document height
     * @param depth the bits per sample, 8, 16 or 32
     * @param colors the color channels of the document's mode: 1, 3 or 4
     * @param limits the allocation limits
     */
    PsdCompositor(byte[] data, boolean large, int width, int height, int depth, int colors, MediaLimits limits) {
        this.data = data;
        this.large = large;
        this.depth = depth;
        this.colors = colors;
        this.model = colors;
        this.document = new Rect(0, 0, height, width);
        this.limits = limits;
    }

    /**
     * Composites the layers onto a transparent canvas covering the document.
     *
     * @param layers the top-level layers, bottom first
     * @return the canvas
     * @throws MediaException.Unsupported if a visible layer uses a feature that is not composited
     */
    Raster composite(List<Layer> layers) {
        var canvas = raster(document);
        render(layers, canvas);
        return canvas;
    }

    /**
     * A rectangle of straight colors and alphas.
     */
    static final class Raster {
        final Rect rect;
        final int colors;
        final float[] color;
        final float[] alpha;
        float[] shape;

        Raster(Rect rect, int colors) {
            this.rect = rect;
            this.colors = colors;
            this.color = new float[rect.width() * rect.height() * colors];
            this.alpha = new float[rect.width() * rect.height()];
        }
    }

    private Raster raster(Rect rect) {
        var pixels = (long) rect.width() * rect.height();
        allocated += pixels * (colors + 1) * 4;
        LimitChecks.checkAllocation(limits, allocated);
        return new Raster(rect, colors);
    }

    private void release(Raster raster) {
        allocated -= (long) raster.alpha.length * (colors + 1) * 4;
    }

    /**
     * Composites a list of sibling layers, bottom first, onto a canvas.
     */
    private void render(List<Layer> layers, Raster canvas) {
        var count = layers.size();
        var index = 0;
        while (index < count) {
            var base = layers.get(index);
            var end = index + 1;
            while (end < count && layers.get(end).clipping) {
                end++;
            }
            if (!base.hidden) {
                var members = layers.subList(index + 1, end).stream().filter(layer -> !layer.hidden).toList();
                check(base);
                for (var member : members) {
                    check(member);
                }
                if (!members.isEmpty() && !base.blendClippedAsGroup) {
                    throw new MediaException.Unsupported("psd", "Photoshop clipping groups whose clipped layers do"
                            + " not blend as a group");
                }
                draw(base, members, canvas);
            }
            index = end;
        }
    }

    private static void check(Layer layer) {
        if (layer.unsupported != null) {
            throw new MediaException.Unsupported("psd", "compositing Photoshop layers with " + layer.unsupported
                    + " (layer '" + layer.name + "')");
        }
        if (layer.fill < 255 && fillSensitive(layer.blend)) {
            // TODO: composite the fill opacity of these modes, which Photoshop applies inside the blend
            throw new MediaException.Unsupported("psd", "compositing Photoshop layers with a fill opacity below 100%"
                    + " in the color burn, linear burn, color dodge, linear dodge, vivid light, linear light, hard mix"
                    + " or difference mode (layer '" + layer.name + "')");
        }
    }

    /**
     * Returns whether a blend mode is one whose fill opacity Photoshop does not apply as an opacity.
     */
    private static boolean fillSensitive(String blend) {
        return switch (blend) {
            case "idiv", "lbrn", "div ", "lddg", "vLit", "lLit", "hMix", "diff" -> true;
            default -> false;
        };
    }

    /**
     * Composites one layer or group, with the layers clipped to it, onto a canvas.
     */
    private void draw(Layer layer, List<Layer> members, Raster canvas) {
        var mode = PsdBlend.of(layer.blend);
        var scale = layer.opacity / 255f * (layer.fill / 255f);
        Raster source;
        if (layer.group && layer.blend.equals("pass") && layer.artboard == null) {
            if (members.isEmpty()) {
                passThrough(layer, canvas, scale);
                return;
            }
            source = nonIsolated(layer, canvas);
        } else {
            source = source(layer);
        }
        if (source == null) {
            return;
        }
        if (!members.isEmpty()) {
            // The base's transparency masks the group; inside it the base's own color has the fill opacity as its
            // alpha, and the clipped layers composite over it.
            var shape = source.alpha.clone();
            Arrays.fill(source.alpha, layer.fill / 255f);
            for (var member : members) {
                var clipped = source(member);
                if (clipped == null) {
                    continue;
                }
                applyMasks(member, clipped);
                var memberScale = member.opacity / 255f * (member.fill / 255f);
                composite(source, clipped, PsdBlend.of(member.blend), memberScale);
                release(clipped);
            }
            for (var index = 0; index < shape.length; index++) {
                source.alpha[index] *= shape[index];
            }
            scale = layer.opacity / 255f;
        }
        applyMasks(layer, source);
        composite(canvas, source, mode, scale);
        release(source);
    }

    /**
     * Composites a pass-through group: its layers onto the canvas itself, then the canvas before and after mixed by
     * the group's opacity and masks.
     */
    private void passThrough(Layer group, Raster canvas, float scale) {
        var extent = intersect(extent(group), canvas.rect);
        if (extent.isEmpty()) {
            return;
        }
        if (scale >= 1 && group.masks.isEmpty()) {
            render(group.children, canvas);
            return;
        }
        var before = raster(extent);
        copy(canvas, before);
        float[] shapeBefore = null;
        if (canvas.shape != null) {
            shapeBefore = new float[extent.width() * extent.height()];
            for (var y = 0; y < extent.height(); y++) {
                System.arraycopy(canvas.shape, (y + extent.top() - canvas.rect.top()) * canvas.rect.width()
                        + extent.left() - canvas.rect.left(), shapeBefore, y * extent.width(), extent.width());
            }
        }
        var shapes = shapeBefore;
        render(group.children, canvas);
        var mix = raster(extent);
        Arrays.fill(mix.alpha, scale);
        applyMasks(group, mix);
        var width = extent.width();
        var c = colors;
        var bands = ParallelBands.bands(extent.height(), (long) width * extent.height() * (c + 1));
        ParallelBands.run(bands, band -> {
            var from = ParallelBands.start(band, bands, extent.height());
            var to = ParallelBands.start(band + 1, bands, extent.height());
            for (var y = from; y < to; y++) {
                var canvasRow = (y + extent.top() - canvas.rect.top()) * canvas.rect.width()
                        + extent.left() - canvas.rect.left();
                for (var x = 0; x < width; x++) {
                    var at = canvasRow + x;
                    var local = y * width + x;
                    var m = mix.alpha[local];
                    var a0 = before.alpha[local];
                    var a1 = canvas.alpha[at];
                    var a = (1 - m) * a0 + m * a1;
                    canvas.alpha[at] = a;
                    if (shapes != null) {
                        canvas.shape[at] = (1 - m) * shapes[local] + m * canvas.shape[at];
                    }
                    for (var channel = 0; channel < c; channel++) {
                        var p = (1 - m) * a0 * before.color[local * c + channel]
                                + m * a1 * canvas.color[at * c + channel];
                        canvas.color[at * c + channel] = a > 0 ? p / a : canvas.color[at * c + channel];
                    }
                }
            }
        });
        release(before);
        release(mix);
    }

    /**
     * Returns the result of a pass-through group as an isolated source: its layers composited onto a copy of the
     * canvas, the canvas's contribution then removed, so that the group composites with the normal mode as one
     * layer (the result of a non-isolated group, ISO 32000-1 section 11.4.8).
     */
    private Raster nonIsolated(Layer group, Raster canvas) {
        var extent = intersect(extent(group), canvas.rect);
        if (extent.isEmpty()) {
            return null;
        }
        var work = raster(extent);
        copy(canvas, work);
        var backdrop = work.color.clone();
        var backdropAlpha = work.alpha.clone();
        work.shape = new float[backdropAlpha.length];
        render(group.children, work);
        var c = colors;
        var clamp = depth != 32;
        for (var index = 0; index < backdropAlpha.length; index++) {
            var shape = work.shape[index];
            var a0 = backdropAlpha[index];
            var factor = shape > 0 ? a0 / shape - a0 : 0;
            for (var channel = 0; channel < c; channel++) {
                var at = index * c + channel;
                var value = work.color[at] + (work.color[at] - backdrop[at]) * factor;
                work.color[at] = clamp ? Math.clamp(value, 0f, 1f) : value;
            }
            work.alpha[index] = shape;
        }
        work.shape = null;
        return work;
    }

    private static void copy(Raster from, Raster to) {
        var width = to.rect.width();
        var c = from.colors;
        for (var y = 0; y < to.rect.height(); y++) {
            var at = (y + to.rect.top() - from.rect.top()) * from.rect.width() + to.rect.left() - from.rect.left();
            System.arraycopy(from.alpha, at, to.alpha, y * width, width);
            System.arraycopy(from.color, at * c, to.color, y * width * c, width * c);
        }
    }

    /**
     * Returns the colors and alphas of a layer before its masks and opacities, or {@code null} when it covers
     * nothing.
     */
    private Raster source(Layer layer) {
        if (layer.group) {
            var extent = layer.artboard != null ? intersect(layer.artboard, document)
                    : intersect(extent(layer), document);
            if (extent.isEmpty()) {
                return null;
            }
            var canvas = raster(extent);
            if (layer.artboard != null && layer.artboardColor != null) {
                fill(canvas, artboardColor(layer));
            }
            render(layer.children, canvas);
            return canvas;
        }
        var rect = intersect(layer.rect, document);
        if (rect.isEmpty()) {
            return null;
        }
        var raster = raster(rect);
        for (var channel = 0; channel < colors; channel++) {
            var index = layer.channel(channel);
            if (index >= 0) {
                read(layer, index, layer.rect, raster, channel, colors);
            }
        }
        var transparency = layer.channel(PsdLayers.TRANSPARENCY);
        if (transparency >= 0) {
            read(layer, transparency, layer.rect, raster, -1, 1);
        } else {
            Arrays.fill(raster.alpha, 1);
        }
        return raster;
    }

    private float[] artboardColor(Layer layer) {
        var rgb = layer.artboardColor;
        return switch (model) {
            case PsdBlend.GRAY -> new float[]{0.3f * rgb[0] + 0.59f * rgb[1] + 0.11f * rgb[2]};
            case PsdBlend.CMYK -> {
                if (rgb[0] < 1 || rgb[1] < 1 || rgb[2] < 1) {
                    throw new MediaException.Unsupported("psd", "compositing CMYK artboards with a colored"
                            + " background");
                }
                yield new float[]{1, 1, 1, 1};
            }
            default -> rgb;
        };
    }

    private static void fill(Raster raster, float[] color) {
        Arrays.fill(raster.alpha, 1);
        for (var index = 0; index < raster.alpha.length; index++) {
            System.arraycopy(color, 0, raster.color, index * raster.colors, raster.colors);
        }
    }

    /**
     * Returns the part of the document a layer or group can change, its hidden layers excluded.
     */
    private Rect extent(Layer layer) {
        if (!layer.group) {
            return intersect(layer.rect, document);
        }
        if (layer.artboard != null) {
            return intersect(layer.artboard, document);
        }
        Rect result = null;
        for (var child : layer.children) {
            if (child.hidden) {
                continue;
            }
            var rect = extent(child);
            if (rect.isEmpty()) {
                continue;
            }
            result = result == null ? rect : new Rect(Math.min(result.top(), rect.top()),
                    Math.min(result.left(), rect.left()), Math.max(result.bottom(), rect.bottom()),
                    Math.max(result.right(), rect.right()));
        }
        return result == null ? new Rect(0, 0, 0, 0) : result;
    }

    private static Rect intersect(Rect a, Rect b) {
        var top = Math.max(a.top(), b.top());
        var left = Math.max(a.left(), b.left());
        var bottom = Math.min(a.bottom(), b.bottom());
        var right = Math.min(a.right(), b.right());
        return bottom <= top || right <= left ? new Rect(0, 0, 0, 0) : new Rect(top, left, bottom, right);
    }

    /**
     * Multiplies a raster's alphas by a layer's masks.
     */
    private void applyMasks(Layer layer, Raster raster) {
        for (var mask : layer.masks) {
            if (layer.filled && repeatsTransparency(layer, mask)) {
                continue;
            }
            var plane = new Raster(raster.rect, 0);
            var inside = intersect(mask.rect(), raster.rect);
            Arrays.fill(plane.alpha, mask.background() / 255f);
            if (!inside.isEmpty()) {
                read(layer, layer.channel(mask.channel()), mask.rect(), plane, -1, 1);
            }
            var density = (float) mask.density();
            for (var index = 0; index < plane.alpha.length; index++) {
                var value = plane.alpha[index];
                if (mask.invert()) {
                    value = 1 - value;
                }
                raster.alpha[index] *= density * value + (1 - density);
            }
        }
    }

    /**
     * Returns whether a fill layer's mask is its stored transparency again, which its pixels already carry.
     */
    private boolean repeatsTransparency(Layer layer, Mask mask) {
        var transparency = layer.channel(PsdLayers.TRANSPARENCY);
        var index = layer.channel(mask.channel());
        if (transparency < 0 || !mask.rect().equals(layer.rect) || layer.rect.isEmpty()) {
            return false;
        }
        var a = decode(layer, transparency, layer.rect);
        var b = decode(layer, index, mask.rect());
        return Arrays.equals(a, b);
    }

    /**
     * Reads one channel of a layer, stored over {@code stored}, into a raster: into its alphas when
     * {@code channel} is -1, otherwise into color {@code channel} of {@code stride} interleaved colors. Pixels of
     * the raster outside {@code stored} are left as they are.
     */
    private void read(Layer layer, int index, Rect stored, Raster raster, int channel, int stride) {
        var inside = intersect(stored, raster.rect);
        if (inside.isEmpty()) {
            return;
        }
        var plane = decode(layer, index, stored);
        var rowBytes = rowBytes(stored.width());
        var width = raster.rect.width();
        for (var y = inside.top(); y < inside.bottom(); y++) {
            var row = (long) (y - stored.top()) * rowBytes;
            var out = (y - raster.rect.top()) * width;
            for (var x = inside.left(); x < inside.right(); x++) {
                var value = sample(plane, row, x - stored.left());
                var at = out + x - raster.rect.left();
                if (channel < 0) {
                    raster.alpha[at] = value;
                } else {
                    raster.color[at * stride + channel] = value;
                }
            }
        }
    }

    private float sample(byte[] plane, long row, int x) {
        return switch (depth) {
            case 8 -> (plane[(int) (row + x)] & 0xFF) / 255f;
            case 16 -> {
                var at = (int) (row + 2L * x);
                yield ((plane[at] & 0xFF) << 8 | (plane[at + 1] & 0xFF)) / 65535f;
            }
            default -> Float.intBitsToFloat(ByteArrays.i32be(plane, (int) (row + 4L * x)));
        };
    }

    private int rowBytes(int width) {
        return width * (depth / 8);
    }

    /**
     * Decompresses one channel of a layer stored over {@code rect}; data that ends early leaves zeros.
     */
    private byte[] decode(Layer layer, int index, Rect rect) {
        var rowBytes = rowBytes(rect.width());
        var height = rect.height();
        var size = (long) rowBytes * height;
        LimitChecks.checkAllocation(limits, allocated + size);
        if (size > Integer.MAX_VALUE - 8) {
            throw new MediaException.LimitExceeded("a Photoshop layer channel of " + size + " bytes exceeds the"
                    + " largest array");
        }
        var plane = new byte[(int) size];
        var position = layer.channelOffsets[index];
        var end = position + layer.channelLengths[index];
        if (end - position < 2 || size == 0) {
            return plane;
        }
        var compression = ByteArrays.u16be(data, (int) position);
        position += 2;
        switch (compression) {
            case 0 -> System.arraycopy(data, (int) position, plane, 0, (int) Math.min(size, end - position));
            case 1 -> {
                var countBytes = large ? 4 : 2;
                var counts = position;
                position += (long) height * countBytes;
                if (position > end) {
                    throw new MediaException.Malformed("Photoshop layer row byte counts are truncated");
                }
                for (var y = 0; y < height; y++) {
                    var count = countBytes == 2 ? ByteArrays.u16be(data, (int) (counts + 2L * y))
                            : ByteArrays.u32be(data, (int) (counts + 4L * y));
                    var rowEnd = Math.min(end, position + count);
                    PsdDecoder.unpack(data, (int) position, (int) rowEnd, plane, y * rowBytes, rowBytes);
                    position = rowEnd;
                }
            }
            case 2, 3 -> {
                var inflater = new Inflater();
                try {
                    inflater.setInput(data, (int) position, (int) (end - position));
                    var filled = 0;
                    while (filled < plane.length) {
                        var count = inflater.inflate(plane, filled, plane.length - filled);
                        if (count == 0 && (inflater.finished() || inflater.needsInput()
                                || inflater.needsDictionary())) {
                            break;
                        }
                        filled += count;
                    }
                } catch (DataFormatException exception) {
                    throw new MediaException.Malformed("corrupt ZIP data in a Photoshop layer", exception);
                } finally {
                    inflater.end();
                }
                if (compression == 3) {
                    PsdDecoder.unpredict(plane, rowBytes, height, depth, rect.width());
                }
            }
            default -> throw new MediaException.Unsupported("psd", "Photoshop compression " + compression);
        }
        return plane;
    }

    /**
     * Composites a source onto a target over their common rectangle.
     *
     * @param target the backdrop, changed in place
     * @param source the source colors and alphas
     * @param mode the blend mode
     * @param scale the factor of the source alphas
     */
    private void composite(Raster target, Raster source, int mode, float scale) {
        var common = intersect(target.rect, source.rect);
        if (common.isEmpty() || scale <= 0) {
            return;
        }
        var width = common.width();
        var c = colors;
        var bands = ParallelBands.bands(common.height(), (long) width * common.height() * (c + 1));
        ParallelBands.run(bands, band -> {
            var blended = new float[c];
            var from = ParallelBands.start(band, bands, common.height());
            var to = ParallelBands.start(band + 1, bands, common.height());
            for (var y = common.top() + from; y < common.top() + to; y++) {
                var targetRow = (y - target.rect.top()) * target.rect.width() - target.rect.left();
                var sourceRow = (y - source.rect.top()) * source.rect.width() - source.rect.left();
                for (var x = common.left(); x < common.right(); x++) {
                    var s = sourceRow + x;
                    // A dissolving layer shows whole pixels, with their own alpha, where the document's noise is
                    // below its opacity; every dissolving layer shares the noise.
                    var as = mode == PsdBlend.DISSOLVE ? (dissolve(x, y) < scale ? source.alpha[s] : 0)
                            : source.alpha[s] * scale;
                    if (as <= 0) {
                        continue;
                    }
                    var t = targetRow + x;
                    var ab = target.alpha[t];
                    var ar = ab + as - ab * as;
                    var tc = t * c;
                    var sc = s * c;
                    if (ab > 0 && mode != PsdBlend.NORMAL && mode != PsdBlend.DISSOLVE) {
                        PsdBlend.blend(mode, model, target.color, tc, source.color, sc, blended);
                    } else {
                        System.arraycopy(source.color, sc, blended, 0, c);
                    }
                    for (var channel = 0; channel < c; channel++) {
                        var cb = target.color[tc + channel];
                        var cs = source.color[sc + channel];
                        var cr = ((1 - as) * ab * cb + as * (1 - ab) * cs + as * ab * blended[channel]) / ar;
                        target.color[tc + channel] = cr;
                    }
                    target.alpha[t] = ar;
                    if (target.shape != null) {
                        var shape = target.shape[t];
                        target.shape[t] = shape + as - shape * as;
                    }
                }
            }
        });
    }

    /**
     * {@return a deterministic uniform value in [0, 1) for a pixel of a dissolving layer}
     */
    private static float dissolve(int x, int y) {
        var hash = x * 0x9E3779B1 ^ y * 0x85EBCA77;
        hash ^= hash >>> 16;
        hash *= 0x7FEB352D;
        hash ^= hash >>> 15;
        hash *= 0x846CA68B;
        hash ^= hash >>> 16;
        return (hash >>> 8) / (float) (1 << 24);
    }
}
