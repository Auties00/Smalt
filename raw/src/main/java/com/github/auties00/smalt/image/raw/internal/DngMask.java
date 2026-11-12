/* Ports the Digital Negative (DNG) Specification 1.7.1.0, Adobe. This product includes DNG technology under license
   by Adobe. */
package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.tiff.internal.IfdBuilder;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.internal.tiff.TiffTypes;

import java.io.ByteArrayOutputStream;
import java.util.zip.Deflater;

/**
 * Holds a semantic mask a DNG file converted from a DNG source carries over (DNG 1.6, {@code NewSubFileType}
 * 65540): its name, instance and XMP packet, its 8-bit samples, and where they lie in the full mask, which spans
 * the written image. The samples are written deflate-compressed in strips.
 *
 * @implNote This implementation writes 8-bit samples whatever the source's depth, 16-bit and floating-point masks
 * rounded to the nearest of 256 levels: the specification also allows 16-bit and floating-point masks, but the DNG
 * SDK's validator rejects every mask of other than 8 bits.
 */
final class DngMask {
    /**
     * The TIFF compression code of deflate.
     */
    private static final int DEFLATE = 8;

    /**
     * The approximate number of uncompressed bytes in one strip.
     */
    private static final int STRIP_BYTES = 1 << 16;

    /**
     * The name ({@code SemanticName}).
     */
    final String name;

    /**
     * The instance ({@code SemanticInstanceID}), or {@code null}.
     */
    final String instanceId;

    /**
     * The XMP packet, or {@code null}.
     */
    final byte[] xmp;

    /**
     * The stored width.
     */
    final int width;

    /**
     * The stored height.
     */
    final int height;

    /**
     * The row of the full mask the stored mask starts at.
     */
    final int top;

    /**
     * The column of the full mask the stored mask starts at.
     */
    final int left;

    /**
     * The width of the full mask, which spans the written image.
     */
    final int fullWidth;

    /**
     * The height of the full mask.
     */
    final int fullHeight;

    /**
     * The samples, row by row, from 0 (not part of the scene the mask names) to 255.
     */
    final byte[] samples;

    /**
     * The mask of the source the samples come from, sampled when the mask is resampled.
     */
    private final SemanticMask source;

    /**
     * Constructs a mask.
     *
     * @param source the mask of the source
     * @param width the stored width
     * @param height the stored height
     * @param top the row of the full mask the stored mask starts at
     * @param left the column of the full mask the stored mask starts at
     * @param fullWidth the width of the full mask
     * @param fullHeight the height of the full mask
     * @param samples the 8-bit samples, row by row
     */
    private DngMask(SemanticMask source, int width, int height, int top, int left, int fullWidth, int fullHeight,
                    byte[] samples) {
        this.source = source;
        this.name = source.name;
        this.instanceId = source.instanceId();
        this.xmp = source.xmp();
        this.width = width;
        this.height = height;
        this.top = top;
        this.left = left;
        this.fullWidth = fullWidth;
        this.fullHeight = fullHeight;
        this.samples = samples;
    }

    /**
     * Returns the mask carried over from a source mask, at its own resolution and sub-area.
     *
     * @param mask the mask of the source
     * @param limits the resource bounds
     * @return the mask
     * @throws com.github.auties00.smalt.MediaException.Malformed if the mask cannot be decoded
     * @throws com.github.auties00.smalt.MediaException.Unsupported if the mask uses a feature the decoder lacks
     */
    static DngMask of(SemanticMask mask, MediaLimits limits) {
        return new DngMask(mask, mask.width, mask.height, mask.top, mask.left, mask.fullWidth, mask.fullHeight,
                mask.stored(limits));
    }

    /**
     * Returns the mask of an image widened by edge photosites, so that it still covers the image's own area: the
     * full mask grows by the widening at the mask's resolution when the widening is a whole number of mask samples
     * on each side, with the stored samples unchanged; otherwise the mask is resampled at the image's resolution,
     * each sample the value the source mask gives at the center of its pixel. The added area is 0, outside every
     * part of the scene.
     *
     * @param width the image width before the widening
     * @param height the image height before the widening
     * @param padLeft the columns added on the left
     * @param padTop the rows added on top
     * @param padRight the columns added on the right
     * @param padBottom the rows added at the bottom
     * @param limits the resource bounds
     * @return the mask
     * @throws com.github.auties00.smalt.MediaException.LimitExceeded if a resampled mask exceeds the limits
     */
    DngMask padded(int width, int height, int padLeft, int padTop, int padRight, int padBottom, MediaLimits limits) {
        var fullW = (long) fullWidth;
        var fullH = (long) fullHeight;
        if (fullW * padLeft % width == 0 && fullW * padRight % width == 0 && fullH * padTop % height == 0
                && fullH * padBottom % height == 0) {
            return new DngMask(source, this.width, this.height, top + (int) (fullH * padTop / height),
                    left + (int) (fullW * padLeft / width), (int) (fullW + fullW * (padLeft + padRight) / width),
                    (int) (fullH + fullH * (padTop + padBottom) / height), samples);
        }
        var columns = span(left, this.width, fullWidth, width);
        var rows = span(top, this.height, fullHeight, height);
        var storedWidth = Math.max(1, columns[1] - columns[0]);
        var storedHeight = Math.max(1, rows[1] - rows[0]);
        LimitChecks.checkAllocation(limits, (long) storedWidth * storedHeight);
        var values = source.values(limits);
        var resampled = new byte[storedWidth * storedHeight];
        var bands = ParallelBands.bands(storedHeight, (long) storedWidth * storedHeight * 16);
        ParallelBands.run(bands, band -> {
            var last = ParallelBands.start(band + 1, bands, storedHeight);
            for (var row = ParallelBands.start(band, bands, storedHeight); row < last; row++) {
                var v = (rows[0] + row + 0.5) / height;
                for (var column = 0; column < storedWidth; column++) {
                    var value = source.sample(values, (columns[0] + column + 0.5) / width, v);
                    resampled[row * storedWidth + column] = (byte) Math.round(Math.clamp(value, 0f, 1f) * 255);
                }
            }
        });
        return new DngMask(source, storedWidth, storedHeight, padTop + rows[0], padLeft + columns[0],
                width + padLeft + padRight, height + padTop + padBottom, resampled);
    }

    /**
     * Returns the pixels of an image along one axis that a stored mask can give a value other than 0.
     *
     * @param start the first stored sample in the full mask
     * @param stored the stored samples
     * @param full the samples of the full mask
     * @param size the image's pixels
     * @return the first pixel and the pixel past the last
     */
    private static int[] span(int start, int stored, int full, int size) {
        var scale = (double) size / full;
        var first = (int) Math.floor((start - 0.5) * scale) - 1;
        var last = (int) Math.ceil((start + stored + 0.5) * scale) + 1;
        return new int[]{Math.clamp(first, 0, size), Math.clamp(last, 0, size)};
    }

    /**
     * {@return the rows of each strip}
     */
    int rowsPerStrip() {
        return Math.clamp(STRIP_BYTES / width, 1, height);
    }

    /**
     * Codes the strips of the mask.
     *
     * @return the deflate-compressed data of each strip, top to bottom
     */
    byte[][] strips() {
        var rows = rowsPerStrip();
        var count = Math.ceilDiv(height, rows);
        var strips = new byte[count][];
        var bands = ParallelBands.bands(count, (long) width * height * 8);
        ParallelBands.run(bands, band -> {
            var deflater = new Deflater();
            var buffer = new byte[8192];
            var last = ParallelBands.start(band + 1, bands, count);
            for (var strip = ParallelBands.start(band, bands, count); strip < last; strip++) {
                var first = strip * rows * width;
                var length = Math.min(rows, height - strip * rows) * width;
                deflater.reset();
                deflater.setInput(samples, first, length);
                deflater.finish();
                var out = new ByteArrayOutputStream(Math.max(64, length / 4));
                while (!deflater.finished()) {
                    out.write(buffer, 0, deflater.deflate(buffer));
                }
                strips[strip] = out.toByteArray();
            }
            deflater.end();
        });
        return strips;
    }

    /**
     * Builds the mask's directory, with placeholder strip offsets.
     *
     * @param strips the coded strips
     * @return the directory
     */
    IfdBuilder directory(byte[][] strips) {
        var ifd = new IfdBuilder(true, false);
        ifd.longs(254, DngParser.NEW_SUBFILE_SEMANTIC_MASK);
        ifd.longs(256, width);
        ifd.longs(257, height);
        ifd.shorts(258, 8);
        ifd.shorts(259, DEFLATE);
        ifd.shorts(262, DngParser.PHOTOMETRIC_MASK);
        ifd.longs(273, new long[strips.length]);
        ifd.shorts(277, 1);
        ifd.longs(278, rowsPerStrip());
        var counts = new long[strips.length];
        for (var index = 0; index < strips.length; index++) {
            counts[index] = strips[index].length;
        }
        ifd.longs(279, counts);
        ifd.shorts(284, 1);
        if (xmp != null) {
            ifd.raw(SemanticMask.XMP, TiffTypes.BYTE, xmp.length, xmp);
        }
        ifd.ascii(SemanticMask.SEMANTIC_NAME, name);
        if (instanceId != null) {
            ifd.ascii(SemanticMask.SEMANTIC_INSTANCE_ID, instanceId);
        }
        if (top != 0 || left != 0 || fullWidth != width || fullHeight != height) {
            ifd.longs(SemanticMask.MASK_SUB_AREA, top, left, fullWidth, fullHeight);
        }
        return ifd;
    }
}
