package com.github.auties00.smalt.audio.ac3.internal;

import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;

/**
 * Adds transient pre-noise processing data to an E-AC-3 frame of Smalt's encoder (an independent
 * substream without mixing, informational or additional bit stream information), setting
 * {@code transproce} and inserting {@code chintransproc}, {@code transprocloc} and
 * {@code transproclen} after the frame SNR offsets, then growing the frame to whole words and
 * recomputing {@code frmsiz} and the CRC. No other E-AC-3 encoder output in the test corpus uses
 * the tool, so the decoder is exercised with frames forged this way.
 */
public final class TransientForging {
    private TransientForging() {
    }

    /**
     * Returns the frame with transient data for one full-bandwidth channel.
     *
     * @param frame    the frame, written by the E-AC-3 encoder with the frame SNR offset strategy
     * @param channel  the channel carrying the data
     * @param location the transient location in units of 4 samples ({@code transprocloc})
     * @param length   the processing length ({@code transproclen})
     */
    public static byte[] forge(byte[] frame, int channel, int location, int length) {
        var positions = positions(frame);
        var transproce = positions[0];
        var insertion = positions[1];
        var channels = positions[2];
        var payload = new MsbBitWriter(64);
        for (var ch = 0; ch < channels; ch++) {
            payload.write(ch == channel ? 1 : 0, 1);
            if (ch == channel) {
                payload.write(location, 10);
                payload.write(length, 8);
            }
        }
        var inserted = (int) payload.bitCount();
        var extra = payload.toByteArray();
        var content = frame.length * 8 - 18;
        var words = (content + inserted + 18 + 15) / 16;
        var writer = new MsbBitWriter(words * 2);
        copy(frame, 0, transproce, writer);
        writer.write(1, 1);
        copy(frame, transproce + 1, insertion, writer);
        copy(extra, 0, inserted, writer);
        copy(frame, insertion, content, writer);
        var padding = words * 16 - 18 - (int) writer.bitCount();
        while (padding > 0) {
            var chunk = Math.min(31, padding);
            writer.write(0, chunk);
            padding -= chunk;
        }
        writer.write(0, 2);
        writer.write(0, 16);
        var forged = writer.toByteArray();
        forged[2] = (byte) ((forged[2] & 0xF8) | ((words - 1) >> 8));
        forged[3] = (byte) (words - 1);
        var crc = Ac3Crc.compute(forged, 2, forged.length - 4);
        forged[forged.length - 2] = (byte) (crc >> 8);
        forged[forged.length - 1] = (byte) crc;
        return forged;
    }

    /**
     * Returns a copy of a frame of Smalt's encoder whose transient pre-noise processing data, if
     * any, has every transient location set to {@code location} (in units of 4 samples), with the
     * CRC recomputed.
     *
     * @param frame the frame, written by the E-AC-3 encoder with the frame SNR offset strategy
     * @param location the new {@code transprocloc}
     */
    public static byte[] relocate(byte[] frame, int location) {
        var positions = positions(frame);
        var out = frame.clone();
        if (((frame[positions[0] >> 3] >> (7 - (positions[0] & 7))) & 1) == 0) {
            return out;
        }
        var bit = positions[1];
        for (var ch = 0; ch < positions[2]; ch++) {
            var present = (frame[bit >> 3] >> (7 - (bit & 7))) & 1;
            bit++;
            if (present != 0) {
                for (var k = 0; k < 10; k++) {
                    var value = (location >> (9 - k)) & 1;
                    var index = bit + k;
                    out[index >> 3] = (byte) ((out[index >> 3] & ~(0x80 >> (index & 7))) | (value << (7 - (index & 7))));
                }
                bit += 18;
            }
        }
        var crc = Ac3Crc.compute(out, 2, out.length - 4);
        out[out.length - 2] = (byte) (crc >> 8);
        out[out.length - 1] = (byte) crc;
        return out;
    }

    /**
     * Returns the bit position of {@code transproce}, the bit position where the per-channel data
     * goes, and the number of full-bandwidth channels of a frame.
     */
    private static int[] positions(byte[] frame) {
        var reader = new MsbBitReader(frame, 0, frame.length);
        reader.skip(16 + 2 + 3 + 11);
        var fscod = reader.read(2);
        var numblkscod = fscod == 3 ? 3 : reader.read(2);
        if (fscod == 3) {
            reader.read(2);
        }
        var blocks = new int[]{1, 2, 3, 6}[numblkscod];
        var acmod = reader.read(3);
        var lfe = reader.read(1) != 0;
        reader.skip(5 + 5);
        if (reader.read(1) != 0) {
            reader.skip(8);
        }
        if (reader.read(1) != 0 || reader.read(1) != 0) {
            throw new IllegalArgumentException("mixing or informational metadata present");
        }
        if (numblkscod != 3) {
            reader.skip(1);
        }
        if (reader.read(1) != 0) {
            throw new IllegalArgumentException("additional bit stream information present");
        }
        var expstre = true;
        if (numblkscod == 3) {
            expstre = reader.read(1) != 0;
            if (reader.read(1) != 0) {
                throw new IllegalArgumentException("adaptive hybrid transform present");
            }
        }
        if (reader.read(2) != 0) {
            throw new IllegalArgumentException("not the frame SNR offset strategy");
        }
        var transproce = (int) reader.bitPosition();
        reader.skip(1 + 7);
        var channels = Ac3Tables.CHANNELS[acmod];
        var coupling = new boolean[blocks];
        if (acmod > 1) {
            coupling[0] = reader.read(1) != 0;
            for (var b = 1; b < blocks; b++) {
                coupling[b] = reader.read(1) != 0 ? reader.read(1) != 0 : coupling[b - 1];
            }
        }
        var couplingBlocks = 0;
        for (var b = 0; b < blocks; b++) {
            couplingBlocks += coupling[b] ? 1 : 0;
        }
        if (expstre) {
            for (var b = 0; b < blocks; b++) {
                reader.skip((coupling[b] ? 2 : 0) + 2L * channels);
            }
        } else {
            reader.skip((acmod > 1 && couplingBlocks > 0 ? 5 : 0) + 5L * channels);
        }
        if (lfe) {
            reader.skip(blocks);
        }
        if (numblkscod != 3) {
            if (reader.read(1) != 0) {
                reader.skip(5L * channels);
            }
        } else {
            reader.skip(5L * channels);
        }
        reader.skip(10);
        return new int[]{transproce, (int) reader.bitPosition(), channels};
    }

    private static void copy(byte[] source, int from, int to, MsbBitWriter writer) {
        for (var bit = from; bit < to; bit++) {
            writer.write((source[bit >> 3] >> (7 - (bit & 7))) & 1, 1);
        }
    }
}
