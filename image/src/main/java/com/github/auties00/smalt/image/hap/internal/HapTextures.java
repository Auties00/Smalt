package com.github.auties00.smalt.image.hap.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.dds.internal.BcnBlocks;
import com.github.auties00.smalt.image.internal.snappy.Snappy;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Expands the textures of a HAP frame (second-stage decompression) and decodes their blocks into pixels.
 *
 * <p>Instances keep the buffers of the expanded textures and one block decoder per band; they are not thread-safe.
 */
public final class HapTextures {
    /**
     * The expanded data of each texture of the frame.
     */
    private final byte[][] buffers = new byte[2][];

    /**
     * The block decoders of the bands, grown as needed.
     */
    private BcnBlocks[] blocks = new BcnBlocks[0];

    /**
     * The texels of a block, one array per band.
     */
    private int[][] texels = new int[0][];

    /**
     * The half-precision texels of a BC6H block, one array per band.
     */
    private short[][] halves = new short[0][];

    /**
     * Constructs the buffers.
     */
    public HapTextures() {
    }

    /**
     * {@return the number of bytes of a texture's blocks}
     *
     * @param format the texture format code
     * @param width the picture width
     * @param height the picture height
     */
    public static long textureBytes(int format, int width, int height) {
        var blockBytes = format == HapSections.RGB_DXT1 || format == HapSections.ALPHA_RGTC1 ? 8 : 16;
        return (long) ((width + 3) / 4) * ((height + 3) / 4) * blockBytes;
    }

    /**
     * Expands one texture of a parsed frame.
     *
     * @param data the array holding the frame
     * @param texture the parsed texture section
     * @param index the texture's index in the frame, 0 or 1
     * @param width the picture width
     * @param height the picture height
     * @param limits the limits checked before allocating
     * @return the array holding the texture's blocks from index 0, valid until the next frame
     * @throws MediaException.Malformed if the data is shorter than the texture, or a Snappy chunk is damaged
     * @throws MediaException.LimitExceeded if the texture exceeds the limits
     */
    public byte[] expand(byte[] data, HapSections.Texture texture, int index, int width, int height,
            MediaLimits limits) {
        var needed = textureBytes(texture.format, width, height);
        if (needed > Integer.MAX_VALUE - 16) {
            throw new MediaException.LimitExceeded("a HAP texture of " + needed + " bytes");
        }
        var size = (int) needed;
        var buffer = buffers[index];
        if (buffer == null || buffer.length < size) {
            LimitChecks.checkAllocation(limits, size);
            buffer = new byte[size];
            buffers[index] = buffer;
        }
        var chunks = texture.chunks;
        var outStarts = new int[chunks + 1];
        var total = 0L;
        for (var chunk = 0; chunk < chunks; chunk++) {
            outStarts[chunk] = (int) Math.min(total, Integer.MAX_VALUE);
            var start = texture.chunkStarts[chunk];
            var length = texture.chunkSizes[chunk];
            total += texture.chunkCompressors[chunk] == HapSections.SNAPPY
                    ? Snappy.uncompressedLength(data, start, length) : length;
            if (total > size) {
                throw new MediaException.Malformed("the HAP texture holds more than the " + size + " bytes of a "
                        + width + "x" + height + " picture");
            }
        }
        if (total < size) {
            throw new MediaException.Malformed("the HAP texture holds " + total + " bytes, not the " + size
                    + " of a " + width + "x" + height + " picture");
        }
        outStarts[chunks] = size;
        var target = buffer;
        var bands = ParallelBands.bands(chunks, size);
        ParallelBands.run(bands, band -> {
            for (var chunk = ParallelBands.start(band, bands, chunks);
                 chunk < ParallelBands.start(band + 1, bands, chunks); chunk++) {
                var start = texture.chunkStarts[chunk];
                var length = texture.chunkSizes[chunk];
                var outStart = outStarts[chunk];
                var outLength = outStarts[chunk + 1] - outStart;
                if (texture.chunkCompressors[chunk] == HapSections.SNAPPY) {
                    Snappy.decompress(data, start, length, target, outStart, outLength);
                } else {
                    System.arraycopy(data, start, target, outStart, length);
                }
            }
        });
        return buffer;
    }

    /**
     * Makes sure every band has its block decoder and texel arrays.
     *
     * @param bands the number of bands
     */
    private void ensureBands(int bands) {
        if (blocks.length < bands) {
            var grownBlocks = new BcnBlocks[bands];
            var grownTexels = new int[bands][];
            var grownHalves = new short[bands][];
            for (var band = 0; band < bands; band++) {
                grownBlocks[band] = band < blocks.length ? blocks[band] : new BcnBlocks();
                grownTexels[band] = band < texels.length ? texels[band] : new int[16];
                grownHalves[band] = band < halves.length ? halves[band] : new short[48];
            }
            blocks = grownBlocks;
            texels = grownTexels;
            halves = grownHalves;
        }
    }

    /**
     * Decodes the blocks of a color texture into packed RGBA samples.
     *
     * @param format {@link HapSections#RGB_DXT1}, {@link HapSections#RGBA_DXT5}, {@link HapSections#YCOCG_DXT5} or
     * {@link HapSections#RGBA_BPTC}
     * @param texture the expanded texture
     * @param alpha the expanded RGTC1 alpha texture combined with a scaled YCoCg one, or {@code null}
     * @param pixels the destination, 4 bytes per pixel in R, G, B, A order
     * @param width the picture width
     * @param height the picture height
     */
    public void decodeColor(int format, byte[] texture, byte[] alpha, byte[] pixels, int width, int height) {
        var columns = (width + 3) / 4;
        var rows = (height + 3) / 4;
        var bands = ParallelBands.bands(rows, (long) width * height);
        ensureBands(bands);
        ParallelBands.run(bands, band -> {
            var decoder = blocks[band];
            var block = texels[band];
            var opaque = format == HapSections.RGB_DXT1 || format == HapSections.YCOCG_DXT5 && alpha == null;
            for (var by = ParallelBands.start(band, bands, rows); by < ParallelBands.start(band + 1, bands, rows);
                 by++) {
                for (var bx = 0; bx < columns; bx++) {
                    var index = by * columns + bx;
                    switch (format) {
                        case HapSections.RGB_DXT1 -> decoder.color(texture, index * 8, block, false);
                        case HapSections.RGBA_BPTC -> decoder.bc7(texture, index * 16, block);
                        default -> {
                            decoder.color(texture, index * 16 + 8, block, true);
                            decoder.alpha(texture, index * 16, block, false);
                            if (format == HapSections.YCOCG_DXT5) {
                                ycocg(block);
                                if (alpha != null) {
                                    decoder.alpha(alpha, index * 8, block, true);
                                }
                            }
                        }
                    }
                    store(block, pixels, width, height, bx, by, opaque);
                }
            }
        });
    }

    /**
     * Decodes the blocks of an RGTC1 texture into one 8-bit sample per pixel.
     *
     * @param texture the expanded texture
     * @param samples the destination, one byte per pixel
     * @param width the picture width
     * @param height the picture height
     */
    public void decodeSingle(byte[] texture, byte[] samples, int width, int height) {
        var columns = (width + 3) / 4;
        var rows = (height + 3) / 4;
        var bands = ParallelBands.bands(rows, (long) width * height);
        ensureBands(bands);
        ParallelBands.run(bands, band -> {
            var decoder = blocks[band];
            var block = texels[band];
            for (var by = ParallelBands.start(band, bands, rows); by < ParallelBands.start(band + 1, bands, rows);
                 by++) {
                for (var bx = 0; bx < columns; bx++) {
                    decoder.smooth(texture, (by * columns + bx) * 8, block, false, true);
                    var visibleRows = Math.min(4, height - by * 4);
                    var visibleColumns = Math.min(4, width - bx * 4);
                    for (var y = 0; y < visibleRows; y++) {
                        var row = (by * 4 + y) * width + bx * 4;
                        for (var x = 0; x < visibleColumns; x++) {
                            samples[row + x] = (byte) block[y * 4 + x];
                        }
                    }
                }
            }
        });
    }

    /**
     * Decodes the blocks of a BC6H texture into linear RGB floating-point samples.
     *
     * @param texture the expanded texture
     * @param signed whether the texture is of the signed format
     * @param samples the destination, 3 floats per pixel
     * @param width the picture width
     * @param height the picture height
     */
    public void decodeFloat(byte[] texture, boolean signed, float[] samples, int width, int height) {
        var columns = (width + 3) / 4;
        var rows = (height + 3) / 4;
        var bands = ParallelBands.bands(rows, (long) width * height);
        ensureBands(bands);
        ParallelBands.run(bands, band -> {
            var decoder = blocks[band];
            var block = halves[band];
            for (var by = ParallelBands.start(band, bands, rows); by < ParallelBands.start(band + 1, bands, rows);
                 by++) {
                for (var bx = 0; bx < columns; bx++) {
                    decoder.bc6h(texture, (by * columns + bx) * 16, block, signed);
                    var visibleRows = Math.min(4, height - by * 4);
                    var visibleColumns = Math.min(4, width - bx * 4);
                    for (var y = 0; y < visibleRows; y++) {
                        var row = ((by * 4 + y) * width + bx * 4) * 3;
                        for (var x = 0; x < visibleColumns; x++) {
                            var texel = 3 * (y * 4 + x);
                            samples[row + 3 * x] = Float.float16ToFloat(block[texel]);
                            samples[row + 3 * x + 1] = Float.float16ToFloat(block[texel + 1]);
                            samples[row + 3 * x + 2] = Float.float16ToFloat(block[texel + 2]);
                        }
                    }
                }
            }
        });
    }

    /**
     * Converts the texels of a scaled YCoCg block to opaque RGB: the color holds Co in red, Cg in green and the scale
     * code in blue, the luma is in the alpha byte.
     *
     * @param block the texels, packed as {@code 0xAABBGGRR}, replaced by opaque RGB
     */
    private static void ycocg(int[] block) {
        for (var texel = 0; texel < 16; texel++) {
            var value = block[texel];
            var scale = (((value >>> 16) & 0xFF) >>> 3) + 1;
            var co = ((value & 0xFF) - 128) / scale;
            var cg = (((value >>> 8) & 0xFF) - 128) / scale;
            var y = value >>> 24;
            var red = clamp(y + co - cg);
            var green = clamp(y + cg);
            var blue = clamp(y - co - cg);
            block[texel] = 0xFF000000 | blue << 16 | green << 8 | red;
        }
    }

    /**
     * {@return a value clamped to 0 to 255}
     *
     * @param value the sample before clamping
     */
    private static int clamp(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }

    /**
     * Stores the visible texels of a block.
     *
     * @param block the texels, packed as {@code 0xAABBGGRR}
     * @param pixels the destination, 4 bytes per pixel
     * @param width the picture width
     * @param height the picture height
     * @param bx the block column
     * @param by the block row
     * @param opaque whether the alpha byte is written as 255
     */
    private static void store(int[] block, byte[] pixels, int width, int height, int bx, int by, boolean opaque) {
        var visibleRows = Math.min(4, height - by * 4);
        var visibleColumns = Math.min(4, width - bx * 4);
        for (var y = 0; y < visibleRows; y++) {
            var at = ((by * 4 + y) * width + bx * 4) * 4;
            for (var x = 0; x < visibleColumns; x++) {
                var value = block[y * 4 + x];
                pixels[at] = (byte) value;
                pixels[at + 1] = (byte) (value >>> 8);
                pixels[at + 2] = (byte) (value >>> 16);
                pixels[at + 3] = opaque ? (byte) 0xFF : (byte) (value >>> 24);
                at += 4;
            }
        }
    }
}
