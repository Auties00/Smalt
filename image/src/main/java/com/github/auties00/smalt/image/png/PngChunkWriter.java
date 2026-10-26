package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.PixelDensity;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.RenderingIntent;
import com.github.auties00.smalt.image.internal.density.DensityTerms;
import com.github.auties00.smalt.image.internal.zlib.Zlib;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Writes a PNG datastream: the signature, then chunks framed with their length, type and CRC.
 *
 * <p>The datastream must fit a Java array; a chunk that would make it larger fails with
 * {@link MediaException.LimitExceeded} and is not written. Instances are not thread-safe.
 */
final class PngChunkWriter {
    /**
     * The largest datastream written, the largest Java array.
     */
    private static final long MAX_SIZE = Integer.MAX_VALUE - 16;

    /**
     * The keyword of the {@code iCCP} chunks written.
     */
    private static final byte[] ICC_NAME = "ICC profile".getBytes(StandardCharsets.ISO_8859_1);

    /**
     * The image gamma of the {@code gAMA} chunk that accompanies {@code sRGB}, stored as 45455.
     */
    private static final Double SRGB_GAMMA = 0.45455;

    /**
     * The bytes written.
     */
    private final ByteWriter out;

    /**
     * The CRC calculator reused for every chunk.
     */
    private final CRC32 crc = new CRC32();

    /**
     * The largest datastream accepted.
     */
    private final long maxSize;

    /**
     * The color type of the {@code IHDR} chunk written, or {@code -1} before it.
     */
    private int colorType = -1;

    /**
     * Constructs a writer and writes the signature.
     *
     * @param capacity the initial capacity in bytes
     */
    PngChunkWriter(int capacity) {
        this(capacity, MAX_SIZE);
    }

    /**
     * Constructs a writer with a smaller size limit and writes the signature.
     *
     * @param capacity the initial capacity in bytes
     * @param maxSize the largest datastream accepted, at most the largest Java array
     */
    PngChunkWriter(int capacity, long maxSize) {
        this.out = new ByteWriter(capacity);
        this.maxSize = Math.min(maxSize, MAX_SIZE);
        out.bytes(PngChunks.SIGNATURE);
    }

    /**
     * Writes the {@code IHDR} chunk.
     *
     * @param width the image width in pixels
     * @param height the image height in pixels
     * @param bitDepth the bits per sample or palette index
     * @param colorType the color type
     * @param interlaced whether the image data is Adam7 interlaced
     * @throws MediaException.LimitExceeded if the datastream would exceed the size limit
     */
    void header(int width, int height, int bitDepth, int colorType, boolean interlaced) {
        var ihdr = new byte[13];
        ByteArrays.put32be(ihdr, 0, width);
        ByteArrays.put32be(ihdr, 4, height);
        ihdr[8] = (byte) bitDepth;
        ihdr[9] = (byte) colorType;
        ihdr[12] = (byte) (interlaced ? 1 : 0);
        chunk(PngChunks.IHDR, ihdr, 0, ihdr.length);
        this.colorType = colorType;
    }

    /**
     * Writes the color space chunks, in this order: {@code cICP}, {@code mDCV} and {@code cLLI}
     * for a high-dynamic-range profile; {@code iCCP} for an ICC profile, and for a high-dynamic-range
     * profile unless the {@code IHDR} color type is grayscale (an RGB profile cannot describe it);
     * {@code sRGB} with its {@code gAMA} and {@code cHRM}; then {@code gAMA} and {@code cHRM}.
     *
     * @param profile the color profile, or {@code null} for none
     * @param srgbIntent the intent of an {@code sRGB} chunk, or {@code null} for none
     * @param gamma the image gamma of a {@code gAMA} chunk, or {@code null} for none
     * @param chromaticities the values of a {@code cHRM} chunk, or {@code null} for none
     * @throws MediaException.LimitExceeded if the datastream would exceed the size limit
     */
    void colorSpace(ColorProfile profile, RenderingIntent srgbIntent, Double gamma, PngChromaticities chromaticities) {
        if (profile != null && profile.isHighDynamicRange()) {
            var color = profile.colorInfo().orElseThrow();
            var cicp = new byte[]{(byte) color.primaries().code(), (byte) color.transfer().code(), 0, 1};
            chunk(PngChunks.CICP, cicp, 0, cicp.length);
            color.masteringDisplay().ifPresent(display -> {
                var mdcv = new byte[24];
                var primaries = new Chromaticity[]{display.red(), display.green(), display.blue(), display.whitePoint()};
                for (var i = 0; i < primaries.length; i++) {
                    ByteArrays.put16be(mdcv, i * 4, fixed(primaries[i].x(), 50000, 0xFFFF));
                    ByteArrays.put16be(mdcv, i * 4 + 2, fixed(primaries[i].y(), 50000, 0xFFFF));
                }
                ByteArrays.put32be(mdcv, 16, fixed(display.maxLuminance(), 10000, Integer.MAX_VALUE));
                ByteArrays.put32be(mdcv, 20, fixed(display.minLuminance(), 10000, Integer.MAX_VALUE));
                chunk(PngChunks.MDCV, mdcv, 0, mdcv.length);
            });
            color.contentLightLevel().ifPresent(level -> {
                var clli = new byte[8];
                ByteArrays.put32be(clli, 0, level.maxContentLightLevel() * 10000);
                ByteArrays.put32be(clli, 4, level.maxFrameAverageLightLevel() * 10000);
                chunk(PngChunks.CLLI, clli, 0, clli.length);
            });
        }
        var gray = colorType == PngHeader.GRAY || colorType == PngHeader.GRAY_ALPHA;
        if (profile != null && !(profile.isHighDynamicRange() && gray)) {
            var compressed = Zlib.deflate(profile.toByteArray(), Deflater.BEST_COMPRESSION);
            var data = new byte[ICC_NAME.length + 2 + compressed.length];
            System.arraycopy(ICC_NAME, 0, data, 0, ICC_NAME.length);
            System.arraycopy(compressed, 0, data, ICC_NAME.length + 2, compressed.length);
            chunk(PngChunks.ICCP, data, 0, data.length);
        }
        if (srgbIntent != null) {
            chunk(PngChunks.SRGB, new byte[]{(byte) srgbIntent.ordinal()}, 0, 1);
            gamma = SRGB_GAMMA;
            chromaticities = PngChromaticities.ofSrgb();
        }
        if (gamma != null) {
            var gama = new byte[4];
            ByteArrays.put32be(gama, 0, (int) Math.round(gamma * 100000));
            chunk(PngChunks.GAMA, gama, 0, gama.length);
        }
        if (chromaticities != null) {
            var chrm = new byte[32];
            var values = new Chromaticity[]{chromaticities.white(), chromaticities.red(), chromaticities.green(),
                    chromaticities.blue()};
            for (var i = 0; i < values.length; i++) {
                ByteArrays.put32be(chrm, i * 8, (int) Math.round(values[i].x() * 100000));
                ByteArrays.put32be(chrm, i * 8 + 4, (int) Math.round(values[i].y() * 100000));
            }
            chunk(PngChunks.CHRM, chrm, 0, chrm.length);
        }
    }

    /**
     * Rounds a real value to a fixed-point field, saturating at its largest value.
     *
     * @param value the value, not negative
     * @param scale the units per 1.0
     * @param max the largest field value
     * @return the field value
     */
    private static int fixed(double value, double scale, int max) {
        return (int) Math.min(max, Math.round(value * scale));
    }

    /**
     * Writes an {@code sBIT} chunk.
     *
     * @param bits the significant bits of each channel the chunk holds for the color type
     * @throws MediaException.LimitExceeded if the datastream would exceed the size limit
     */
    void significantBits(byte[] bits) {
        chunk(PngChunks.SBIT, bits, 0, bits.length);
    }

    /**
     * Writes a {@code pHYs} chunk.
     *
     * @param density the pixel density, representable as {@link #physicalTerms(PixelDensity)} requires
     * @throws MediaException.LimitExceeded if the datastream would exceed the size limit
     */
    void physicalDimensions(PixelDensity density) {
        var terms = physicalTerms(density);
        var phys = new byte[9];
        ByteArrays.put32be(phys, 0, (int) terms[0]);
        ByteArrays.put32be(phys, 4, (int) terms[1]);
        phys[8] = (byte) terms[2];
        chunk(PngChunks.PHYS, phys, 0, phys.length);
    }

    /**
     * Returns the {@code pHYs} values of a pixel density, as {@link PngEncoderOptions#density()} describes them.
     *
     * @param density the pixel density to write
     * @return the pixels per unit along x and y, from 1 to {@code 2^31 - 1}, and the unit specifier, 0 for an
     * aspect ratio and 1 for the meter
     * @throws IllegalArgumentException if the chunk cannot hold the density
     */
    static long[] physicalTerms(PixelDensity density) {
        if (!density.isPhysical()) {
            var terms = DensityTerms.ratio(density, Integer.MAX_VALUE);
            return new long[]{terms[0], terms[1], 0};
        }
        var terms = DensityTerms.rounded(density, PixelDensity.Unit.DOTS_PER_METER, Integer.MAX_VALUE);
        return new long[]{terms[0], terms[1], 1};
    }

    /**
     * Writes {@code PLTE} and, when some entry is translucent, {@code tRNS}.
     *
     * @param palette the ordered palette
     * @throws MediaException.LimitExceeded if the datastream would exceed the size limit
     */
    void palette(PngPalette palette) {
        var plte = new byte[palette.size() * 3];
        for (var i = 0; i < palette.size(); i++) {
            var color = palette.color(i);
            plte[i * 3] = (byte) color;
            plte[i * 3 + 1] = (byte) (color >>> 8);
            plte[i * 3 + 2] = (byte) (color >>> 16);
        }
        chunk(PngChunks.PLTE, plte, 0, plte.length);
        if (palette.translucentCount() > 0) {
            var trns = new byte[palette.translucentCount()];
            for (var i = 0; i < trns.length; i++) {
                trns[i] = (byte) (palette.color(i) >>> 24);
            }
            chunk(PngChunks.TRNS, trns, 0, trns.length);
        }
    }

    /**
     * Writes the {@code tRNS} chunk of a grayscale or truecolor image.
     *
     * @param key the transparent gray level, or the transparent red, green and blue samples, at
     * the image's bit depth
     * @throws MediaException.LimitExceeded if the datastream would exceed the size limit
     */
    void transparencyKey(int[] key) {
        var trns = new byte[key.length * 2];
        for (var i = 0; i < key.length; i++) {
            ByteArrays.put16be(trns, i * 2, key[i]);
        }
        chunk(PngChunks.TRNS, trns, 0, trns.length);
    }

    /**
     * Writes a chunk.
     *
     * @param type the chunk type
     * @param data the array holding the chunk data
     * @param offset the index of the first data byte
     * @param length the number of data bytes
     * @throws MediaException.LimitExceeded if the datastream would exceed the size limit
     */
    void chunk(int type, byte[] data, int offset, int length) {
        chunk(type, -1, data, offset, length);
    }

    /**
     * Writes a chunk whose data starts with a sequence number, as {@code fcTL} and {@code fdAT}
     * do.
     *
     * @param type the chunk type
     * @param sequence the sequence number written before the data, or -1 for none
     * @param data the array holding the rest of the chunk data
     * @param offset the index of the first data byte
     * @param length the number of data bytes
     * @throws MediaException.LimitExceeded if the datastream would exceed the size limit
     */
    void chunk(int type, int sequence, byte[] data, int offset, int length) {
        var prefix = sequence >= 0 ? 4 : 0;
        var start = out.size();
        if (start + 12L + prefix + length > maxSize) {
            throw new MediaException.LimitExceeded("encoded PNG exceeds " + maxSize + " bytes");
        }
        out.u32be(length + prefix);
        out.u32be(type);
        if (prefix > 0) {
            out.u32be(sequence);
        }
        out.bytes(data, offset, length);
        crc.reset();
        crc.update(out.buffer(), start + 4, length + prefix + 4);
        out.u32be(crc.getValue());
    }

    /**
     * Writes image data split into chunks.
     *
     * @param type {@code IDAT} or {@code fdAT}
     * @param data the zlib stream
     * @param sequence the sequence number of the first {@code fdAT}, or -1 for {@code IDAT}
     * @param chunkSize the largest number of data bytes per chunk
     * @return the sequence number after the last chunk, or -1 for {@code IDAT}
     * @throws MediaException.LimitExceeded if the datastream would exceed the size limit
     */
    int data(int type, byte[] data, int sequence, int chunkSize) {
        var offset = 0;
        do {
            var length = Math.min(chunkSize, data.length - offset);
            chunk(type, sequence, data, offset, length);
            offset += length;
            if (sequence >= 0) {
                sequence++;
            }
        } while (offset < data.length);
        return sequence;
    }

    /**
     * Writes the {@code IEND} chunk.
     *
     * @throws MediaException.LimitExceeded if the datastream would exceed the size limit
     */
    void end() {
        chunk(PngChunks.IEND, new byte[0], 0, 0);
    }

    /**
     * {@return a copy of the datastream written so far}
     */
    byte[] toByteArray() {
        return out.toByteArray();
    }
}
