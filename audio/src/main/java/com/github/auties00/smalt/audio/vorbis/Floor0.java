/* Ports libvorbis 1.3.7 (lib/floor0.c, lib/lsp.c). Copyright Xiph.Org Foundation; BSD 3-Clause. Modified for Smalt. */
package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.vorbis.internal.Ilog;

/**
 * Implements Vorbis floor type 0: the frequency response of an LSP filter on a Bark-scale
 * frequency axis and a dB amplitude axis (Vorbis I section 6).
 *
 * <p>Only pre-1.0 encoders produced floor 0, but the format remains part of Vorbis I.
 *
 * @implNote This implementation uses the exact (non lookup) {@code vorbis_lsp_to_curve} and reproduces
 * libvorbis's mix of single and double precision, so the curve matches the reference decoder: the Bark
 * map is computed in single precision with a double precision arc tangent, and the LSP products in single
 * precision. A book number beyond the floor's book list makes the channel unused, as in libvorbis.
 */
final class Floor0 extends Floor {
    /**
     * The codebooks of the floor's book list.
     */
    private final Codebook[] books;

    /**
     * The number of LSP coefficients.
     */
    private final int order;

    /**
     * The amplitude width in bits.
     */
    private final int amplitudeBits;

    /**
     * The amplitude offset in decibels.
     */
    private final int amplitudeOffset;

    /**
     * The number of Bark bands.
     */
    private final int barkMapSize;

    /**
     * The Bark band of every spectral line, for short ({@code [0]}) and long ({@code [1]})
     * blocks, each terminated by {@code -1}.
     */
    private final int[][] maps;

    /**
     * Constructs a floor from its parsed fields.
     *
     * @param books the codebooks of the book list
     * @param order the number of LSP coefficients
     * @param rate the sampling rate the Bark map is laid out for
     * @param barkMapSize the number of Bark bands
     * @param amplitudeBits the amplitude width
     * @param amplitudeOffset the amplitude offset
     * @param blocksizes the short and long block sizes
     */
    private Floor0(Codebook[] books, int order, int rate, int barkMapSize, int amplitudeBits, int amplitudeOffset,
                   int[] blocksizes) {
        this.books = books;
        this.order = order;
        this.amplitudeBits = amplitudeBits;
        this.amplitudeOffset = amplitudeOffset;
        this.barkMapSize = barkMapSize;
        this.maps = new int[2][];
        var scale = (float) (barkMapSize / toBark(rate / 2.0f));
        for (var w = 0; w < 2; w++) {
            var n = blocksizes[w] / 2;
            var map = new int[n + 1];
            for (var j = 0; j < n; j++) {
                var value = (int) Math.floor(toBark((rate / 2.0f) / n * j) * scale);
                map[j] = Math.min(value, barkMapSize - 1);
            }
            map[n] = -1;
            maps[w] = map;
        }
    }

    /**
     * Reads a floor 0 configuration from the setup header (Vorbis I section 6.2.1).
     *
     * @param reader the setup header reader, positioned after the floor type
     * @param books the codebooks of the stream
     * @param blocksizes the short and long block sizes
     * @param budget the allocation budget of the stream
     * @return the floor
     * @throws MediaException.Malformed if the configuration is truncated, references a missing
     * codebook or one without values, or has a zero rate or
     * Bark map size
     * @throws MediaException.LimitExceeded if the Bark maps exceed the allocation budget
     */
    static Floor0 read(BitReader reader, Codebook[] books, int[] blocksizes, AllocationBudget budget) {
        var order = reader.read(8);
        var rate = reader.read(16);
        var barkMapSize = reader.read(16);
        var amplitudeBits = reader.read(6);
        var amplitudeOffset = reader.read(8);
        var count = reader.read(4) + 1;
        var list = new Codebook[count];
        for (var i = 0; i < count; i++) {
            var number = reader.read(8);
            if (number >= books.length) {
                throw new MediaException.Malformed("Vorbis floor 0 references missing codebook " + number);
            }
            var book = books[number];
            if (!book.hasValues()) {
                throw new MediaException.Malformed("Vorbis floor 0 uses codebook " + number + " without values");
            }
            list[i] = book;
        }
        VorbisSetup.checkNotTruncated(reader);
        if (rate == 0 || barkMapSize == 0) {
            throw new MediaException.Malformed("Vorbis floor 0 with zero rate or Bark map size");
        }
        budget.charge(blocksizes[0] / 2 + blocksizes[1] / 2 + 2, 4);
        return new Floor0(list, order, rate, barkMapSize, amplitudeBits, amplitudeOffset, blocksizes);
    }

    /**
     * Returns the Bark scale value of a frequency (Vorbis I section 6.2.3, errata 20150227).
     *
     * @param frequency the frequency in hertz
     * @return the Bark value
     */
    private static double toBark(float frequency) {
        return 13.1f * StrictMath.atan(0.00074f * frequency)
                + 2.24f * StrictMath.atan(frequency * frequency * 1.85e-8f)
                + 1e-4f * frequency;
    }

    @Override
    boolean decode(BitReader reader, FloorData data) {
        var raw = readLong(reader, amplitudeBits);
        if (raw <= 0 || reader.overrun()) {
            return false;
        }
        var maximum = (1L << amplitudeBits) - 1;
        data.amplitude = (float) raw / (float) maximum * amplitudeOffset;
        var number = reader.read(Ilog.ilog(books.length));
        if (reader.overrun() || number >= books.length) {
            return false;
        }
        var book = books[number];
        var values = book.values;
        var dimensions = book.dimensions;
        var coefficients = data.coefficients;
        var last = 0.0f;
        var count = 0;
        while (count < order) {
            var entry = book.decode(reader);
            if (entry < 0) {
                return false;
            }
            var base = entry * dimensions;
            for (var k = 0; k < dimensions && count < order; k++) {
                coefficients[count++] = values[base + k] + last;
            }
            last = coefficients[count - 1];
        }
        return true;
    }

    /**
     * Reads an unsigned field of up to 63 bits.
     *
     * @param reader the packet reader
     * @param bits the field width, from 0 to 63
     * @return the field value
     */
    private static long readLong(BitReader reader, int bits) {
        if (bits <= 32) {
            return reader.read(bits) & 0xFFFFFFFFL;
        }
        var low = reader.read(32) & 0xFFFFFFFFL;
        return low | (long) reader.read(bits - 32) << 32;
    }

    @Override
    void apply(FloorData data, float[] spectrum, int n, boolean longBlock) {
        var map = maps[longBlock ? 1 : 0];
        var lsp = data.coefficients;
        var m = order;
        for (var i = 0; i < m; i++) {
            lsp[i] = (float) (2.0f * StrictMath.cos(lsp[i]));
        }
        var amplitude = data.amplitude;
        var offset = (float) amplitudeOffset;
        var step = (float) (Math.PI / barkMapSize);
        var i = 0;
        while (i < n) {
            var k = map[i];
            var p = 0.5f;
            var q = 0.5f;
            var w = (float) (2.0f * StrictMath.cos(step * k));
            var j = 1;
            for (; j < m; j += 2) {
                q *= w - lsp[j - 1];
                p *= w - lsp[j];
            }
            if (j == m) {
                q *= w - lsp[j - 1];
                p *= p * (4.0f - w * w);
                q *= q;
            } else {
                p *= p * (2.0f - w);
                q *= q * (2.0f + w);
            }
            var linear = (float) StrictMath.exp((amplitude / Math.sqrt(p + q) - offset) * 0.11512925f);
            spectrum[i] *= linear;
            while (map[++i] == k) {
                spectrum[i] *= linear;
            }
        }
    }

    @Override
    int intCapacity() {
        return 0;
    }

    @Override
    int floatCapacity() {
        return order;
    }
}
