package com.github.auties00.smalt.audio.aac.internal.ep;

import com.github.auties00.smalt.audio.aac.internal.AacBitReader;
import com.github.auties00.smalt.audio.aac.internal.ep.EpConfigWriter.Set;
import com.github.auties00.smalt.audio.aac.internal.ep.EpConfigWriter.Spec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Builds error protected ER AAC streams for the tests from the access units of unprotected ones: the
 * {@code AudioSpecificConfig} with {@code epConfig} 2 or 3 and its {@code ErrorProtectionSpecificConfig}, and the EP
 * frames, optionally with channel errors the codes correct.
 */
public final class EpStreams {
    /** A built stream: its configuration and one EP frame per access unit. */
    public record Built(byte[] config, List<byte[]> units) {
    }

    private EpStreams() {
    }

    /** Returns the bit position of {@code epConfig} in an ER AAC LC, LTP or LD configuration with extensionFlag 1. */
    private static int epConfigPosition(byte[] config) {
        var reader = new AacBitReader(config);
        var objectType = reader.read(5);
        if (reader.read(4) == 15) {
            reader.read(24);
        }
        reader.read(4);
        reader.read(1);
        if (reader.readFlag()) {
            reader.read(14);
        }
        if (!reader.readFlag() || (objectType != 17 && objectType != 19 && objectType != 23)) {
            throw new IllegalArgumentException("not an ER AAC LC, LTP or LD configuration with its resilience flags");
        }
        reader.read(4);
        return (int) reader.bitPosition();
    }

    private static byte[] config(byte[] source, int epConfig, List<Set> sets, int interleave, int concatenated,
                                 int headerRate, int headerCrc) {
        var position = epConfigPosition(source);
        var bits = new EpFrameEncoder.BitList();
        bits.add(Arrays.copyOf(EpFrameEncoder.unpack(source, source.length * 8), position));
        bits.add(epConfig, 2);
        EpConfigWriter.append(bits, sets, interleave, true, concatenated, headerRate, headerCrc);
        if (epConfig == 3) {
            bits.add(1, 1);
        }
        return EpFrameEncoder.pack(bits.toArray());
    }

    private static ErrorProtectionConfig protection(byte[] source, byte[] config) {
        var bits = EpFrameEncoder.unpack(config, config.length * 8);
        return EpConfigWriter.parse(EpFrameEncoder.pack(Arrays.copyOfRange(bits, epConfigPosition(source) + 2,
                bits.length)));
    }

    private static void damage(byte[] frame, int errors, Random random) {
        // isolated errors, at least 200 bits apart, so each falls in its own trellis span or Reed-Solomon symbol
        var span = frame.length * 8 / Math.max(1, errors);
        for (var e = 0; e < errors && span > 64; e++) {
            var bit = e * span + random.nextInt(span - 32);
            frame[bit >>> 3] ^= (byte) (0x80 >>> (bit & 7));
        }
    }

    /**
     * Builds an {@code epConfig} 2 stream: each access unit split into a terminated SRCPC 8/32 class, a Reed-Solomon
     * class correcting 8 bytes and a terminated SRCPC 8/24 class until the end; with concatenated frames every class
     * has a
     * fixed length and the units are zero-padded to the longest.
     *
     * @param source the configuration of the unprotected stream
     * @param frames its access units
     * @param concatenated the number of access units per EP frame
     * @param random the source of the error positions
     * @param errors the number of bit errors put in each EP frame
     * @return the stream
     */
    public static Built unmapped(byte[] source, List<byte[]> frames, int concatenated, Random random, int errors) {
        List<Set> sets;
        var longest = 0;
        for (var frame : frames) {
            longest = Math.max(longest, frame.length * 8);
        }
        var fixed = Math.max(longest, 448);
        if (concatenated == 1) {
            sets = EpConfigWriter.sets(new Set(List.of(Spec.srcpc(6, 24, 8).terminated().interleaved(2),
                    Spec.reedSolomon(12, 1, 8, 10).interleaved(1), Spec.srcpc(0, 16, 0).terminated()), null));
        } else {
            sets = EpConfigWriter.sets(new Set(List.of(Spec.fixed(40, 0, 24, 8).concatenated().interleaved(2),
                    Spec.fixed(400, 1, 8, 10).interleaved(1), Spec.fixed(fixed - 440, 0, 16, 6).interleaved(2)),
                    null));
        }
        var configBytes = config(source, 2, sets, 2, concatenated, -1, 0);
        var protection = protection(source, configBytes);
        var units = new ArrayList<byte[]>();
        for (var start = 0; start < frames.size(); start += concatenated) {
            var classes = new byte[concatenated][3][];
            for (var f = 0; f < concatenated; f++) {
                var frame = frames.get(Math.min(start + f, frames.size() - 1));
                var bits = EpFrameEncoder.unpack(frame, frame.length * 8);
                if (concatenated > 1) {
                    bits = Arrays.copyOf(bits, fixed);
                }
                var first = Math.min(bits.length, 40);
                var second = Math.min(bits.length - first, 400);
                classes[f][0] = Arrays.copyOfRange(bits, 0, first);
                classes[f][1] = Arrays.copyOfRange(bits, first, first + second);
                classes[f][2] = Arrays.copyOfRange(bits, first + second, bits.length);
            }
            var unit = EpFrameEncoder.encode(protection, 0, classes, null, null);
            damage(unit, errors, random);
            units.add(unit);
        }
        return new Built(configBytes, units);
    }

    /**
     * Builds an {@code epConfig} 3 stream whose classes are the category instances of an {@code epConfig} 1 stream,
     * alternately under terminated SRCPC 8/20 and Reed-Solomon codes correcting 4 bytes, the last until the end at
     * terminated SRCPC 8/12.
     *
     * @param source the configuration of the stream
     * @param instances the category instances of each access unit, in stream order
     * @param random the source of the error positions
     * @param errors the number of bit errors put in each EP frame
     * @return the stream
     */
    public static Built mapped(byte[] source, List<List<byte[]>> instances, Random random, int errors) {
        var count = instances.getFirst().size();
        var specs = new ArrayList<Spec>();
        for (var c = 0; c < count - 1; c++) {
            specs.add(c % 2 == 0 ? Spec.srcpc(14, 12, 10).terminated().interleaved(2)
                    : Spec.reedSolomon(14, 1, 4, 8).interleaved(1));
        }
        specs.add(Spec.srcpc(0, 4, 6).terminated().interleaved(1));
        var sets = EpConfigWriter.sets(new Set(specs, null));
        var configBytes = config(source, 3, sets, 2, 1, 20, 10);
        var protection = protection(source, configBytes);
        var units = new ArrayList<byte[]>();
        for (var frame : instances) {
            var classes = new byte[1][count][];
            for (var c = 0; c < count; c++) {
                classes[0][c] = EpFrameEncoder.unpack(frame.get(c), frame.get(c).length * 8);
            }
            var unit = EpFrameEncoder.encode(protection, 0, classes, null, null);
            damage(unit, errors, random);
            units.add(unit);
        }
        return new Built(configBytes, units);
    }
}
