package com.github.auties00.smalt.audio.dts.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.dts.DtsDecoder;
import com.github.auties00.smalt.audio.dts.DtsDecoderOptions;
import com.github.auties00.smalt.container.dts.DtsDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks DTS decoder behaviour that needs no oracle: the sum/difference coding AMODE 3 implies (clause C.3.5), the
 * dialogue normalization gains of the core header (Table 5-20) and of the audio asset descriptor
 * ({@code nuDialNormCode}), the order of the asset's dynamic metadata fields (Table 7-6), and the decoder lifecycle.
 * The asset cases rewrite the extension substream headers of FATE streams bit by bit and reseal their CRC.
 */
final class DtsDecoderBehaviorTest {
    private static final AudioTrack TRACK = AudioTrack.builder().codec(Codec.DTS).timeBase(Rational.timeBaseOf(48000))
            .sampleRate(48000).build();

    private static List<float[][]> decode(List<byte[]> units, DtsDecoderOptions options) {
        var decoder = new DtsDecoder(TRACK, options);
        var out = new ArrayList<float[][]>();
        for (var unit : units) {
            decoder.decode(new Packet(0, unit, 0, 0, 0, true), buffer -> out.add(planes(buffer)));
        }
        return out;
    }

    private static float[][] planes(AudioBuffer buffer) {
        var planes = new float[buffer.channels()][];
        for (var ch = 0; ch < planes.length; ch++) {
            planes[ch] = java.util.Arrays.copyOf(buffer.plane(ch), buffer.frames());
        }
        return planes;
    }

    private static List<byte[]> synthetic(int amode, boolean front, int version, int dialnorm) {
        var random = new SplittableRandom(11);
        var test = new DtsSyntheticDifferentialTest.Case("behavior", amode, 0, true, false, front, false, 2, 1,
                DtsSyntheticDifferentialTest.Tool.HUFFMAN);
        var units = new ArrayList<byte[]>();
        for (var f = 0; f < 3; f++) {
            var frame = DtsSyntheticDifferentialTest.frame(test, random);
            frame.version = version;
            frame.dialnorm = dialnorm;
            units.add(CoreStreamWriter.write(frame));
        }
        return units;
    }

    private static void assertScaled(List<float[][]> reference, List<float[][]> scaled, double gain) {
        assertEquals(reference.size(), scaled.size());
        var energy = 0.0;
        for (var f = 0; f < reference.size(); f++) {
            for (var ch = 0; ch < reference.get(f).length; ch++) {
                var r = reference.get(f)[ch];
                var s = scaled.get(f)[ch];
                assertEquals(r.length, s.length);
                for (var i = 0; i < r.length; i++) {
                    assertEquals(r[i] * gain, s[i], 1e-6 * Math.max(1, Math.abs(r[i])));
                    energy += r[i] * r[i];
                }
            }
        }
        assertTrue(energy > 0, "the stream decodes to silence");
    }

    @Test
    @DisplayName("AMODE 3 decodes its pair as sum and difference, like stereo with SUMF")
    void amodeSumDifference() {
        var sumDifference = decode(synthetic(3, false, 7, 0), DtsDecoderOptions.defaults());
        var stereoSumf = decode(synthetic(2, true, 7, 0), DtsDecoderOptions.defaults());
        var stereo = decode(synthetic(2, false, 7, 0), DtsDecoderOptions.defaults());
        assertEquals(stereoSumf.size(), sumDifference.size());
        for (var f = 0; f < stereoSumf.size(); f++) {
            assertArrayEquals(stereoSumf.get(f), sumDifference.get(f));
        }
        assertNotEquals(stereo.getFirst()[0][100], sumDifference.getFirst()[0][100]);
    }

    @ParameterizedTest(name = "VERNUM {0}, DIALNORM {1}: {2} dB")
    @CsvSource({"7, 6, -6", "7, 0, 0", "6, 3, -19", "6, 15, -31", "5, 9, 0"})
    @DisplayName("the core dialogue normalization gain follows DIALNORM and VERNUM when it is enabled")
    void coreDialogueNormalization(int version, int dialnorm, int decibels) {
        var units = synthetic(9, false, version, dialnorm);
        var plain = decode(units, DtsDecoderOptions.defaults());
        var normalized = decode(units, DtsDecoderOptions.builder().dialogueNormalization(true).build());
        assertScaled(plain, normalized, Math.pow(10, decibels / 20.0));
    }

    @Test
    @DisplayName("a lossless stream without core takes the dialogue normalization gain of its audio asset")
    void assetDialogueNormalization() {
        var units = units("xll_51_24_48_none");
        var rewritten = new ArrayList<byte[]>();
        for (var unit : units) {
            rewritten.add(ExssRewriter.withDynamicMetadata(unit, 0, -1, 9));
        }
        var plain = decode(units, DtsDecoderOptions.defaults());
        assertScaled(plain, decode(rewritten, DtsDecoderOptions.defaults()), 1);
        assertScaled(plain, decode(rewritten, DtsDecoderOptions.builder().dialogueNormalization(true).build()),
                Math.pow(10, -9 / 20.0));
        assertScaled(plain, decode(units, DtsDecoderOptions.builder().dialogueNormalization(true).build()), 1);
    }

    @Test
    @DisplayName("the asset's stereo downmix DRC code follows its dialogue normalization code")
    void assetDynamicMetadataOrder() {
        // xll_71_24_48_768_1 signals an embedded stereo downmix, so nuDRC2ChDmixCode is present with a DRC code
        for (var unit : units("xll_71_24_48_768_1")) {
            var coreSize = ((unit[5] & 0x03) << 12 | (unit[6] & 0xFF) << 4 | (unit[7] & 0xF0) >> 4) + 1;
            var rewritten = ExssRewriter.withDynamicMetadata(unit, coreSize, 0x5A, 4);
            var original = parse(unit, coreSize);
            var parsed = parse(rewritten, coreSize);
            assertEquals(original.components, parsed.components);
            assertEquals(original.totalChannels, parsed.totalChannels);
            assertEquals(0, original.dialogueNormalization());
            assertEquals(-4, parsed.dialogueNormalization());
        }
    }

    private static ExtensionSubstream parse(byte[] unit, int coreSize) {
        var core = new CoreDecoder();
        core.decode(unit, 0, coreSize, true);
        var substream = new ExtensionSubstream();
        var size = substream.frameSize(unit, coreSize, unit.length - coreSize);
        assertTrue(size > 0, "the extension substream header is valid");
        try {
            substream.decode(unit, coreSize, size, core);
        } catch (MediaException.Unsupported expected) {
            // the XLL extension of a stream with a core is not decoded; the asset is parsed before it
        }
        return substream;
    }

    private static List<byte[]> units(String vector) {
        var file = Conformance.fetchListed("dts-" + vector + ".dtshd");
        var units = new ArrayList<byte[]>();
        try (var demuxer = new DtsDemuxer(MediaSource.of(file))) {
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                units.add(packet.data());
            }
        }
        return units;
    }

    @Test
    @DisplayName("a closed decoder refuses a reset")
    void resetAfterClose() {
        var decoder = new DtsDecoder(TRACK);
        decoder.reset();
        decoder.close();
        assertThrows(IllegalStateException.class, decoder::reset);
    }

    @Test
    @DisplayName("after a reset a stream decodes as it does from a fresh decoder")
    void resetRestarts() {
        var units = synthetic(9, false, 7, 0);
        var fresh = decode(units, DtsDecoderOptions.defaults());
        var decoder = new DtsDecoder(TRACK);
        for (var unit : units) {
            decoder.decode(new Packet(0, unit, 0, 0, 0, true), buffer -> { });
        }
        decoder.reset();
        var again = new ArrayList<float[][]>();
        for (var unit : units) {
            decoder.decode(new Packet(0, unit, 0, 0, 0, true), buffer -> again.add(planes(buffer)));
        }
        assertEquals(fresh.size(), again.size());
        for (var f = 0; f < fresh.size(); f++) {
            assertArrayEquals(fresh.get(f), again.get(f));
        }
    }

    /**
     * Rewrites the first audio asset descriptor of an extension substream frame to carry dynamic metadata.
     */
    static final class ExssRewriter {
        private ExssRewriter() {
        }

        /**
         * Returns a copy of a unit whose extension substream, at an offset, signals a DRC code (when
         * {@code drc >= 0}) and a dialogue normalization code; the asset must signal neither. The descriptor grows by
         * the whole bytes the fields need, the header and frame sizes with it, and the header CRC is recomputed.
         */
        static byte[] withDynamicMetadata(byte[] unit, int offset, int drc, int dialnorm) {
            var bits = new StringBuilder();
            for (var i = offset; i < unit.length; i++) {
                bits.append(String.format("%8s", Integer.toBinaryString(unit[i] & 0xFF)).replace(' ', '0'));
            }
            var reader = new Cursor(bits);
            reader.skip(32 + 8);
            var index = reader.read(2);
            var wide = reader.read(1) == 1;
            var headerSizeAt = reader.position;
            var headerSize = reader.read(wide ? 12 : 8) + 1;
            var frameSizeAt = reader.position;
            var frameSize = reader.read(wide ? 20 : 16) + 1;
            var sizeBits = wide ? 20 : 16;
            var staticFields = reader.read(1) == 1;
            var assets = 1;
            if (staticFields) {
                reader.skip(2 + 3);
                if (reader.read(1) == 1) {
                    reader.skip(36);
                }
                var presentations = reader.read(3) + 1;
                assets = reader.read(3) + 1;
                var active = new int[presentations];
                for (var p = 0; p < presentations; p++) {
                    active[p] = reader.read(index + 1);
                }
                for (var p = 0; p < presentations; p++) {
                    for (var s = 0; s < index + 1; s++) {
                        if ((active[p] >> s & 1) != 0) {
                            reader.skip(8);
                        }
                    }
                }
                if (reader.read(1) == 1) {
                    throw new IllegalArgumentException("mixing metadata is not rewritten");
                }
            }
            reader.skip((long) assets * sizeBits);
            var descriptorAt = reader.position;
            var descriptorSize = reader.read(9) + 1;
            reader.skip(3);
            var embeddedStereo = false;
            if (staticFields) {
                if (reader.read(1) == 1) {
                    reader.skip(4);
                }
                if (reader.read(1) == 1) {
                    reader.skip(24);
                }
                if (reader.read(1) == 1) {
                    reader.skip((reader.read(10) + 1) * 8L);
                }
                reader.skip(5 + 4);
                var total = reader.read(8) + 1;
                if (reader.read(1) == 1) {
                    embeddedStereo = total > 2 && reader.read(1) == 1;
                    if (total > 6) {
                        reader.skip(1);
                    }
                    var maskBits = 0;
                    if (reader.read(1) == 1) {
                        maskBits = (reader.read(2) + 1) << 2;
                        reader.skip(maskBits);
                    }
                    if (reader.read(3) != 0) {
                        throw new IllegalArgumentException("speaker remapping is not rewritten");
                    }
                } else {
                    reader.skip(3);
                }
            }
            var metadataAt = reader.position;
            if (reader.read(2) != 0) {
                throw new IllegalArgumentException("the asset already signals DRC or dialogue normalization");
            }
            var inserted = new StringBuilder();
            if (drc >= 0) {
                inserted.append('1').append(binary(drc, 8));
            } else {
                inserted.append('0');
            }
            inserted.append('1').append(binary(dialnorm, 5));
            if (drc >= 0 && embeddedStereo) {
                // the third bit is 0, so a parser reading this code before the dialogue normalization fields
                // reads no dialogue normalization code and loses the field alignment
                inserted.append(binary(0x11, 8));
            }
            var grown = inserted.length() - 2;
            var growth = (grown + 7) / 8;
            var padding = growth * 8 - grown;
            var header = new StringBuilder(bits.substring(0, headerSize * 8 - 16));
            header.replace(metadataAt, metadataAt + 2, inserted.toString());
            var descriptorEnd = descriptorAt + descriptorSize * 8 + grown;
            header.insert(descriptorEnd, "0".repeat(padding));
            put(header, headerSizeAt, wide ? 12 : 8, headerSize + growth - 1);
            put(header, frameSizeAt, sizeBits, frameSize + growth - 1);
            put(header, descriptorAt, 9, descriptorSize + growth - 1);
            var out = new byte[unit.length + growth];
            System.arraycopy(unit, 0, out, 0, offset);
            for (var i = 0; i < header.length() / 8; i++) {
                out[offset + i] = (byte) Integer.parseInt(header.substring(i * 8, i * 8 + 8), 2);
            }
            var newHeaderSize = headerSize + growth;
            var crc = DtsCrc.compute(out, offset + 5, newHeaderSize - 7);
            out[offset + newHeaderSize - 2] = (byte) (crc >> 8);
            out[offset + newHeaderSize - 1] = (byte) crc;
            System.arraycopy(unit, offset + headerSize, out, offset + newHeaderSize, unit.length - offset - headerSize);
            return out;
        }

        private static String binary(int value, int bits) {
            return String.format("%" + bits + "s", Integer.toBinaryString(value & ((1 << bits) - 1)))
                    .replace(' ', '0');
        }

        private static void put(StringBuilder bits, int at, int width, int value) {
            bits.replace(at, at + width, binary(value, width));
        }

        /**
         * Reads a string of binary digits.
         */
        private static final class Cursor {
            private final CharSequence bits;
            private int position;

            private Cursor(CharSequence bits) {
                this.bits = bits;
            }

            private int read(int width) {
                var value = 0;
                for (var i = 0; i < width; i++) {
                    value = value << 1 | (bits.charAt(position++) - '0');
                }
                return value;
            }

            private void skip(long width) {
                position += (int) width;
            }
        }
    }
}
