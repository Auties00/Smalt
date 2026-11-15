package com.github.auties00.smalt.audio.dts.internal;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.dts.DtsDecoder;
import com.github.auties00.smalt.audio.dts.DtsDecoderOptions;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Compares the DTS core decoder with ffmpeg's, used as a black box, on synthetic streams that exercise one coding
 * tool each: every Huffman code book of the audio codes, the scale factors, bit allocations and transient modes,
 * the block codes, the plain codes of every width, ADPCM prediction, high frequency vectors, transients, joint
 * intensity, sum/difference coding, the LFE channel at both interpolation factors, several subframes, a DSYNC word
 * after every subsubframe, both synthesis filter banks, the two-channel arrangements (dual mono, Lt/Rt), frames that
 * reset the ADPCM predictor history and frames carrying dynamic range coefficients.
 */
final class DtsSyntheticDifferentialTest {
    /**
     * Two implementations of the same integer dequantization and float synthesis differ by float rounding only.
     */
    private static final Tolerance FLOAT = Tolerance.snr(90);

    record Case(String name, int amode, int lfe, boolean perfect, boolean sync, boolean front, boolean surround,
                int subsubframes, int subframes, Tool tool, boolean history, boolean dynamicRange) {
        Case(String name, int amode, int lfe, boolean perfect, boolean sync, boolean front, boolean surround,
             int subsubframes, int subframes, Tool tool) {
            this(name, amode, lfe, perfect, sync, front, surround, subsubframes, subframes, tool, true, false);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    enum Tool {
        HUFFMAN, BLOCK, PLAIN, ADPCM, VECTORS, TRANSIENTS, JOINT, ALLOCATION_HUFFMAN, SCALE_HUFFMAN, D65
    }

    static Stream<Case> cases() {
        return Stream.of(
                new Case("huffman audio codes", 9, 0, true, false, false, false, 2, 2, Tool.HUFFMAN),
                new Case("block codes", 9, 0, false, false, false, false, 4, 1, Tool.BLOCK),
                new Case("plain codes", 9, 2, true, true, false, false, 2, 1, Tool.PLAIN),
                new Case("adpcm prediction", 9, 0, true, false, false, false, 1, 4, Tool.ADPCM),
                new Case("high frequency vectors", 9, 1, true, false, false, false, 4, 1, Tool.VECTORS),
                new Case("transients", 9, 0, true, false, false, false, 4, 1, Tool.TRANSIENTS),
                new Case("joint intensity", 9, 0, true, false, false, false, 2, 1, Tool.JOINT),
                new Case("sum and difference", 8, 0, true, false, true, true, 2, 1, Tool.PLAIN),
                new Case("stereo sum and difference", 2, 0, false, false, true, false, 2, 1, Tool.HUFFMAN),
                new Case("mono", 0, 0, true, false, false, false, 1, 2, Tool.PLAIN),
                new Case("allocation code books", 5, 0, true, false, false, false, 2, 1, Tool.ALLOCATION_HUFFMAN),
                new Case("scale factor code books", 9, 0, false, false, false, false, 2, 1, Tool.SCALE_HUFFMAN),
                new Case("three front and one surround channels", 7, 0, false, false, false, false, 2, 1, Tool.PLAIN),
                new Case("D65 levels 31 and 32", 9, 0, true, false, false, false, 4, 1, Tool.D65),
                new Case("dual mono", 1, 0, true, false, false, false, 2, 1, Tool.HUFFMAN),
                new Case("left and right total", 4, 0, false, false, false, false, 2, 1, Tool.PLAIN),
                new Case("adpcm without predictor history", 6, 0, true, false, false, false, 1, 3, Tool.ADPCM, false,
                        false),
                new Case("dynamic range coefficients", 5, 1, true, false, false, false, 2, 2, Tool.HUFFMAN, true,
                        true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("synthetic core streams decode as ffmpeg decodes them")
    void synthetic(Case test) throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var random = new SplittableRandom(test.name().hashCode());
        var stream = new ByteArrayOutputStream();
        var units = new ArrayList<byte[]>();
        for (var f = 0; f < 6; f++) {
            var frame = frame(test, random);
            var bytes = CoreStreamWriter.write(frame);
            units.add(bytes);
            stream.writeBytes(bytes);
        }
        var file = Scratch.directory("dts-synthetic").resolve(test.name().replace(' ', '-') + ".dts");
        Files.write(file, stream.toByteArray());
        var reference = ffmpeg(file);
        var decoded = decode(units);
        assertEquals(reference.length, decoded.length, "sample count");
        report(test.name(), reference, decoded, channels);
        FLOAT.assertPcm(test.name(), reference, decoded);
    }

    static CoreStreamWriter.Frame frame(Case test, SplittableRandom random) {
        var channelCount = new int[]{1, 2, 2, 2, 2, 3, 3, 4, 4, 5}[test.amode()];
        var channels = new CoreStreamWriter.Channels(channelCount);
        var frame = new CoreStreamWriter.Frame(channels);
        frame.amode = test.amode();
        frame.lfe = test.lfe();
        frame.perfect = test.perfect();
        frame.syncEverySubsubframe = test.sync();
        frame.frontSumDifference = test.front();
        frame.surroundSumDifference = test.surround();
        frame.history = test.history();
        frame.dynamicRange = test.dynamicRange();
        for (var ch = 0; ch < channelCount; ch++) {
            channels.subbands[ch] = 20 + random.nextInt(13);
            channels.vqStart[ch] = channels.subbands[ch];
            channels.scaleBook[ch] = 6;
            channels.allocationBook[ch] = 6;
            channels.transientBook[ch] = random.nextInt(4);
            for (var n = 0; n < 10; n++) {
                var huffman = n == 0 ? 1 : n < 5 ? 3 : 7;
                channels.select[ch][n] = switch (test.tool()) {
                    case HUFFMAN, ALLOCATION_HUFFMAN, SCALE_HUFFMAN, TRANSIENTS -> random.nextInt(huffman);
                    case D65 -> n == 8 ? 3 : random.nextInt(huffman);
                    case BLOCK -> n < 7 ? huffman : 7;
                    default -> n < 7 ? huffman : 7;
                };
                channels.adjust[ch][n] = random.nextInt(4);
            }
            if (test.tool() == Tool.ALLOCATION_HUFFMAN) {
                channels.allocationBook[ch] = ch % 5;
            }
            if (test.tool() == Tool.SCALE_HUFFMAN) {
                channels.scaleBook[ch] = ch % 5;
            }
            if (test.tool() == Tool.VECTORS) {
                channels.vqStart[ch] = 8 + random.nextInt(8);
            }
            if (test.tool() == Tool.JOINT && ch > 0) {
                channels.joint[ch] = 1;
                channels.subbands[ch] = 12;
                channels.vqStart[ch] = 12;
                channels.subbands[0] = 32;
                channels.vqStart[0] = 32;
            }
        }
        var maximum = switch (test.tool()) {
            case ALLOCATION_HUFFMAN -> 12;
            case BLOCK -> 7;
            case D65 -> 9;
            case PLAIN -> 26;
            default -> 10;
        };
        for (var s = 0; s < test.subframes(); s++) {
            var subframe = new CoreStreamWriter.Subframe(test.subsubframes());
            var previous = 0;
            for (var ch = 0; ch < channelCount; ch++) {
                for (var n = 0; n < channels.subbands[ch]; n++) {
                    var abits = test.tool() == Tool.D65 ? 9 : 1 + random.nextInt(maximum);
                    if (test.tool() == Tool.PLAIN) {
                        abits = 11 + random.nextInt(16);
                    }
                    if (n >= channels.vqStart[ch]) {
                        abits = 0;
                        subframe.vector[ch][n] = random.nextInt(1024);
                    }
                    subframe.allocation[ch][n] = abits;
                    var index = 20 + random.nextInt(60);
                    if (channels.scaleBook[ch] < 5) {
                        // a random walk over the 6-bit levels below those where scale factor, step size and code
                        // overflow the 24-bit subband samples, where Smalt and ffmpeg's decoder saturate differently
                        index = Math.clamp(previous + random.nextInt(-8, 9), 0, 48);
                        previous = index;
                    }
                    subframe.scaleIndex[ch][n][0] = index;
                    subframe.scaleIndex[ch][n][1] = Math.clamp(index + random.nextInt(-4, 5), 0, 62);
                    if (test.subsubframes() > 1 && abits > 0 && (test.tool() == Tool.TRANSIENTS
                            || random.nextInt(4) == 0)) {
                        subframe.transientMode[ch][n] = random.nextInt(test.subsubframes());
                    }
                    if (test.tool() == Tool.ADPCM && n < channels.vqStart[ch] && random.nextBoolean()) {
                        subframe.predictionMode[ch][n] = 1;
                        subframe.predictionVector[ch][n] = random.nextInt(4096);
                    }
                    for (var m = 0; m < 8 * test.subsubframes(); m++) {
                        subframe.codes[ch][n][m] = code(abits, abits >= 1 && abits <= 10 ? channels.select[ch][abits - 1] : 0,
                                random, test.tool() == Tool.D65);
                    }
                }
                if (channels.joint[ch] > 0) {
                    subframe.jointBook[ch] = random.nextInt(5);
                    for (var n = channels.subbands[ch]; n < channels.subbands[0]; n++) {
                        subframe.jointIndex[ch][n] = 64 + random.nextInt(-20, 21);
                    }
                }
            }
            for (var n = 0; n < 2 * Math.max(1, frame.lfe) * test.subsubframes(); n++) {
                subframe.lfe[n] = random.nextInt(-100, 101);
            }
            subframe.lfeScale = 40 + random.nextInt(40);
            if (test.dynamicRange()) {
                subframe.dynamicRange = random.nextInt(256);
            }
            frame.subframes.add(subframe);
        }
        return frame;
    }

    /**
     * Draws a quantization index the code book of {@code abits} and {@code sel} can code.
     */
    private static int code(int abits, int sel, SplittableRandom random, boolean d65) {
        if (abits == 0) {
            return 0;
        }
        if (d65) {
            return random.nextInt(4) < 3 ? random.nextInt(-32, 33) : (random.nextBoolean() ? 31 : -32);
        }
        if (abits <= 10) {
            var levels = new int[]{0, 3, 5, 7, 9, 13, 17, 25, 33, 65, 129}[abits];
            var huffman = abits == 1 ? 1 : abits <= 5 ? 3 : 7;
            if (sel >= huffman && abits >= 8) {
                var bits = abits - 3;
                return random.nextInt(-(1 << (bits - 1)), 1 << (bits - 1));
            }
            var half = levels / 2;
            return random.nextInt(-half, half + 1);
        }
        var bits = abits - 3;
        return random.nextInt(-(1 << Math.min(bits - 1, 12)), 1 << Math.min(bits - 1, 12));
    }

    private static float[] decode(List<byte[]> units) {
        var track = AudioTrack.builder().codec(Codec.DTS).timeBase(Rational.timeBaseOf(48000)).sampleRate(48000)
                .build();
        var decoder = new DtsDecoder(track, DtsDecoderOptions.defaults());
        var buffers = new ArrayList<AudioBuffer>();
        for (var unit : units) {
            decoder.decode(new Packet(0, unit, 0, 0, 0, true), buffer -> buffers.add(buffer.copy()));
        }
        var channels = buffers.getFirst().channels();
        DtsSyntheticDifferentialTest.channels = channels;
        var total = buffers.stream().mapToInt(AudioBuffer::frames).sum();
        var out = new float[total * channels];
        var position = 0;
        for (var buffer : buffers) {
            for (var i = 0; i < buffer.frames(); i++) {
                for (var ch = 0; ch < channels; ch++) {
                    out[position++] = buffer.plane(ch)[i];
                }
            }
        }
        return out;
    }

    /**
     * The channel count of the last stream {@link #decode} decoded.
     */
    private static int channels;

    /**
     * Prints, per channel, the SNR of the decoded samples against the reference, to locate a mismatch from a CI log.
     */
    private static void report(String name, float[] reference, float[] decoded, int channels) {
        var frames = reference.length / channels;
        for (var ch = 0; ch < channels; ch++) {
            double rr = 0;
            double ee = 0;
            for (var i = 0; i < frames; i++) {
                double r = reference[i * channels + ch];
                double e = r - decoded[i * channels + ch];
                rr += r * r;
                ee += e * e;
            }
            System.out.printf("DTS-SYNTH %s ch %d snr %.2f dB%n", name, ch, 10 * Math.log10(rr / Math.max(ee, 1e-30)));
        }
    }

    private static float[] ffmpeg(java.nio.file.Path file) {
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-cpuflags", "0", "-v", "error", "-f", "dts", "-i",
                file.toString(), "-f", "f32le", "-"));
        var samples = new float[raw.length / 4];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples);
        return samples;
    }
}
