package com.github.auties00.smalt.audio.ac3;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.ac3.internal.PreNoiseReference;
import com.github.auties00.smalt.audio.ac3.internal.TransientForging;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the output holdback of {@link Ac3DecoderOptions#preNoiseHoldback()}: a held back decode
 * emits the same samples as a plain one, in the same layouts, 512 samples later; and with transient
 * pre-noise data forged into Smalt's E-AC-3 frames ({@link TransientForging}) it corrects every
 * sample the one-shot pseudo-code ({@link PreNoiseReference}) corrects, including the samples
 * before the frame that completes the synthesis buffer, which a plain decode leaves as decoded.
 */
class Ac3PreNoiseHoldbackTest {
    private static final ChannelLayout L51 = ChannelLayout.SURROUND_5_1;

    private static final ChannelLayout L71 = ChannelLayout.SURROUND_7_1;

    private static final Ac3DecoderOptions HELD = Ac3DecoderOptions.fullRange().toBuilder().preNoiseHoldback(true)
            .build();

    /**
     * A run of output samples of one layout.
     */
    private record Run(ChannelLayout layout, float[][] planes) {
        int frames() {
            return planes[0].length;
        }
    }

    /**
     * A decode: the runs of output samples, the samples each call emitted and the reported delay.
     */
    private record Decode(List<Run> runs, List<Integer> calls, int delay) {
        float[] plane(int ch) {
            var total = runs.stream().mapToInt(Run::frames).sum();
            var out = new float[total];
            var at = 0;
            for (var run : runs) {
                System.arraycopy(run.planes()[ch], 0, out, at, run.frames());
                at += run.frames();
            }
            return out;
        }

        int frames() {
            return runs.stream().mapToInt(Run::frames).sum();
        }
    }

    private static AudioBuffer noise(ChannelLayout layout, int samples, long seed) {
        var random = new Random(seed);
        var planes = new float[layout.channels()][samples];
        for (var ch = 0; ch < planes.length; ch++) {
            for (var i = 0; i < samples; i++) {
                var t = i / 48000.0;
                planes[ch][i] = (float) (0.05 * random.nextGaussian() + 0.2 * Math.sin(2 * Math.PI * (300 + 170 * ch) * t)
                        + (i % 3000 < 60 ? 0.3 : 0));
            }
        }
        return new AudioBuffer(layout, 48000, planes, samples);
    }

    private static List<Packet> encode(ChannelLayout layout, int blocks, int frames, long seed) {
        var options = Eac3EncoderOptions.of(48000, layout).toBuilder().channelCoupling(false).spectralExtension(false)
                .blocksPerFrame(blocks).build();
        return Eac3EncoderTest.encode(options, noise(layout, frames * 256 * blocks, seed));
    }

    /**
     * Returns the packets with transient data for channel 0 forged into the independent frame of the
     * packets at the keys, each {@code {transprocloc, transproclen}}.
     */
    private static List<Packet> forge(List<Packet> packets, Map<Integer, int[]> data) {
        var out = new ArrayList<Packet>();
        for (var i = 0; i < packets.size(); i++) {
            var packet = packets.get(i);
            var entry = data.get(i);
            if (entry == null) {
                out.add(packet);
                continue;
            }
            var bytes = packet.data();
            var length = 2 * ((((bytes[2] & 0x07) << 8) | (bytes[3] & 0xFF)) + 1);
            var forged = TransientForging.forge(Arrays.copyOf(bytes, length), 0, entry[0], entry[1]);
            var joined = new byte[forged.length + bytes.length - length];
            System.arraycopy(forged, 0, joined, 0, forged.length);
            System.arraycopy(bytes, length, joined, forged.length, bytes.length - length);
            out.add(new Packet(packet.trackId(), joined, packet.pts(), packet.dts(), packet.duration(), true));
        }
        return out;
    }

    private static Decode decode(List<Packet> packets, Ac3DecoderOptions options) {
        return decode(packets, options, Codec.EAC3);
    }

    private static Decode decode(List<Packet> packets, Ac3DecoderOptions options, Codec codec) {
        var runs = new ArrayList<Run>();
        var calls = new ArrayList<Integer>();
        try (var decoder = new Ac3Decoder(Ac3TestSupport.track(codec), options)) {
            for (var packet : packets) {
                calls.add(collect(runs, emit -> decoder.decode(packet, emit)));
            }
            calls.add(collect(runs, decoder::flush));
            return new Decode(runs, calls, decoder.delay());
        }
    }

    private static int collect(List<Run> runs, java.util.function.Consumer<java.util.function.Consumer<AudioBuffer>> step) {
        var emitted = new int[1];
        step.accept(buffer -> {
            emitted[0] += buffer.frames();
            var planes = new float[buffer.channels()][];
            for (var ch = 0; ch < planes.length; ch++) {
                planes[ch] = Arrays.copyOf(buffer.plane(ch), buffer.frames());
            }
            if (!runs.isEmpty() && runs.getLast().layout().equals(buffer.layout())) {
                var last = runs.removeLast();
                var joined = new float[planes.length][];
                for (var ch = 0; ch < planes.length; ch++) {
                    joined[ch] = Arrays.copyOf(last.planes()[ch], last.frames() + buffer.frames());
                    System.arraycopy(planes[ch], 0, joined[ch], last.frames(), buffer.frames());
                }
                runs.add(new Run(buffer.layout(), joined));
            } else {
                runs.add(new Run(buffer.layout(), planes));
            }
        });
        return emitted[0];
    }

    private static void assertSameOutput(Decode expected, Decode actual) {
        assertEquals(expected.runs().size(), actual.runs().size(), "runs of one layout");
        for (var i = 0; i < expected.runs().size(); i++) {
            var a = expected.runs().get(i);
            var b = actual.runs().get(i);
            assertEquals(a.layout(), b.layout(), "layout of run " + i);
            for (var ch = 0; ch < a.planes().length; ch++) {
                assertArrayEquals(a.planes()[ch], b.planes()[ch], "run " + i + " channel " + ch);
            }
        }
    }

    private static void assertDelayed(Decode plain, Decode held, int unit) {
        assertEquals(0, plain.delay());
        assertEquals(512, held.delay());
        var calls = held.calls();
        var emitted = 0;
        for (var i = 0; i < calls.size() - 1; i++) {
            emitted += calls.get(i);
            assertEquals(Math.max(0, (i + 1) * unit - 512), emitted, "samples emitted after call " + i);
            assertEquals(unit, (int) plain.calls().get(i), "plain call " + i);
        }
        assertEquals(Math.min(512, (calls.size() - 1) * unit), (int) calls.getLast(), "flushed samples");
        assertEquals(0, (int) plain.calls().getLast());
        assertEquals(plain.frames(), held.frames());
    }

    @ParameterizedTest(name = "{0} channels, {1} blocks per frame")
    @CsvSource({"1, 6", "2, 6", "6, 6", "8, 6", "2, 3", "2, 2", "2, 1", "6, 1", "8, 3"})
    @DisplayName("emit the samples of a plain decode 512 samples later")
    void unchangedWithoutData(int channels, int blocks) {
        var layout = switch (channels) {
            case 1 -> ChannelLayout.MONO;
            case 2 -> ChannelLayout.STEREO;
            case 6 -> L51;
            default -> L71;
        };
        var packets = encode(layout, blocks, 24, channels * 10L + blocks);
        var plain = decode(packets, Ac3DecoderOptions.fullRange());
        var held = decode(packets, HELD);
        assertEquals(1, plain.runs().size());
        assertEquals(channels, plain.runs().getFirst().layout().channels());
        assertSameOutput(plain, held);
        assertDelayed(plain, held, 256 * blocks);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"STEREO", "MATRIX_STEREO", "MONO"})
    @DisplayName("emit the samples of a plain downmix 512 samples later")
    void unchangedDownmix(Ac3Downmix downmix) {
        var packets = encode(L51, 6, 16, 3);
        var plain = decode(packets, Ac3DecoderOptions.fullRange().toBuilder().downmix(downmix).build());
        var held = decode(packets, HELD.toBuilder().downmix(downmix).build());
        assertSameOutput(plain, held);
        assertDelayed(plain, held, 1536);
    }

    @Test
    @DisplayName("keep the layout of each sample across a change of channel configuration")
    void layoutChange() {
        var packets = new ArrayList<Packet>();
        packets.addAll(encode(ChannelLayout.STEREO, 6, 6, 1));
        var change = packets.size();
        packets.addAll(encode(L51, 6, 6, 2));
        packets.addAll(encode(L71, 6, 6, 3));
        packets.addAll(encode(ChannelLayout.MONO, 6, 6, 4));
        var plain = decode(packets, Ac3DecoderOptions.fullRange());
        var held = decode(packets, HELD);
        assertEquals(4, plain.runs().size());
        assertSameOutput(plain, held);
        assertDelayed(plain, held, 1536);
        // the call after each change emits the end of the old layout and the start of the new one
        assertEquals(1536, (int) held.calls().get(change));
    }

    @Test
    @DisplayName("hold back nothing for AC-3 tracks or without the option")
    void notHeld() {
        var packets = Ac3EncoderTest.encode(Ac3EncoderOptions.of(48000, ChannelLayout.STEREO),
                noise(ChannelLayout.STEREO, 8 * 1536, 9));
        var ac3 = decode(packets, HELD, Codec.AC3);
        assertEquals(0, ac3.delay());
        assertTrue(ac3.calls().subList(0, ac3.calls().size() - 1).stream().allMatch(count -> count == 1536));
        assertEquals(0, (int) ac3.calls().getLast());
        assertFalse(Ac3DecoderOptions.defaults().preNoiseHoldback());
        assertTrue(HELD.preNoiseHoldback());
        assertEquals(HELD, HELD.toBuilder().build());
        assertFalse(HELD.equals(Ac3DecoderOptions.fullRange()));
        assertTrue(HELD.toString().contains("preNoiseHoldback=true"));
    }

    /**
     * Returns the cases {@code blocks, frame, transprocloc, transproclen}; the late ones correct
     * samples before the frame that completes the synthesis buffer.
     */
    @ParameterizedTest(name = "{0} blocks, data in frame {1}: transprocloc {2}, transproclen {3}")
    @CsvSource({
            "6, 4, 480, 100",
            "6, 4, 450, 255",
            "6, 4, 511, 1",
            "6, 4, 448, 37",
            "3, 7, 300, 200",
            "3, 7, 500, 255",
            "2, 9, 400, 255",
            "1, 20, 300, 100",
            "1, 20, 600, 255",
            "6, 4, 300, 100",
            "6, 4, 600, 255"})
    @DisplayName("correct every sample the pseudo-code corrects")
    void correctsEverySample(int blocks, int frame, int location, int length) {
        var unit = 256 * blocks;
        var packets = encode(ChannelLayout.STEREO, blocks, 48, 21);
        var forged = forge(packets, Map.of(frame, new int[]{location, length}));
        var plain = decode(packets, HELD);
        var held = decode(forged, HELD);
        var expected = PreNoiseReference.apply(plain.plane(0), frame * unit, 4 * location, length);
        assertArrayEquals(expected, held.plane(0));
        assertArrayEquals(plain.plane(1), held.plane(1));
        var changed = 0;
        for (var i = 0; i < expected.length; i++) {
            changed += expected[i] != plain.plane(0)[i] ? 1 : 0;
        }
        assertTrue(changed > 200, changed + " corrected samples");
        // without holdback the samples decoded before the frame that completes the buffer stay as they are
        var start = frame * unit + PreNoiseReference.correctionStart(4 * location, length);
        var ready = frame * unit + (PreNoiseReference.bufferEnd(4 * location) - 1) / unit * unit;
        var late = decode(forged, Ac3DecoderOptions.fullRange()).plane(0);
        var differing = 0;
        for (var i = 0; i < late.length; i++) {
            if (late[i] != expected[i]) {
                differing++;
                assertTrue(i >= start && i < ready, "sample " + i + " differs outside [" + start + ", " + ready + ")");
            }
        }
        assertEquals(start < ready, differing > 0, differing + " samples left uncorrected without holdback");
    }

    @Test
    @DisplayName("correct samples of earlier frames for several frames carrying data")
    void severalFrames() {
        var packets = encode(ChannelLayout.STEREO, 6, 32, 5);
        var data = Map.of(3, new int[]{480, 200}, 5, new int[]{450, 255}, 9, new int[]{300, 50}, 11, new int[]{500, 1});
        var forged = forge(packets, data);
        var plain = decode(packets, HELD);
        var held = decode(forged, HELD);
        var expected = plain.plane(0);
        for (var f : new int[]{3, 5, 9, 11}) {
            expected = PreNoiseReference.apply(expected, f * 1536, 4 * data.get(f)[0], data.get(f)[1]);
        }
        assertArrayEquals(expected, held.plane(0));
        assertArrayEquals(plain.plane(1), held.plane(1));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"MONO", "STEREO"})
    @DisplayName("downmix the corrected samples of earlier frames")
    void downmixedCorrection(Ac3Downmix downmix) {
        var packets = encode(ChannelLayout.STEREO, 6, 16, 8);
        var forged = forge(packets, Map.of(4, new int[]{480, 255}));
        var native_ = decode(forged, HELD);
        var plainNative = decode(packets, HELD);
        var options = HELD.toBuilder().downmix(downmix).build();
        var mixed = decode(forged, options);
        var plainMixed = decode(packets, options);
        var outputs = plainMixed.runs().getFirst().planes().length;
        for (var out = 0; out < outputs; out++) {
            // the downmix of the plain decode gives each coded channel's coefficient
            var coefficients = fit(plainNative.plane(0), plainNative.plane(1), plainMixed.plane(out));
            var expected = new float[native_.frames()];
            for (var i = 0; i < expected.length; i++) {
                expected[i] = (float) (coefficients[0] * native_.plane(0)[i] + coefficients[1] * native_.plane(1)[i]);
            }
            Tolerance.maxAbs(1e-6).assertPcm("downmixed output " + out, expected, mixed.plane(out));
            var unheld = decode(forged, Ac3DecoderOptions.fullRange().toBuilder().downmix(downmix).build());
            // 4 * 480 + 256 = 2176: the correction starts at 1792 - 255 - 256 = 1281, in frame 4
            var differs = false;
            for (var i = 4 * 1536 + 1281; i < 5 * 1536; i++) {
                differs |= Math.abs(unheld.plane(out)[i] - mixed.plane(out)[i]) > 1e-4;
            }
            assertEquals(Math.abs(coefficients[0]) > 0.1, differs, "the held back downmix corrects frame 4");
        }
    }

    /**
     * Fits {@code mix = a * left + b * right} by least squares and checks that the fit is exact to
     * float precision.
     */
    private static double[] fit(float[] left, float[] right, float[] mix) {
        double ll = 0, lr = 0, rr = 0, lm = 0, rm = 0;
        for (var i = 0; i < mix.length; i++) {
            ll += (double) left[i] * left[i];
            lr += (double) left[i] * right[i];
            rr += (double) right[i] * right[i];
            lm += (double) left[i] * mix[i];
            rm += (double) right[i] * mix[i];
        }
        var determinant = ll * rr - lr * lr;
        var a = (lm * rr - rm * lr) / determinant;
        var b = (rm * ll - lm * lr) / determinant;
        for (var i = 0; i < mix.length; i++) {
            assertEquals(mix[i], a * left[i] + b * right[i], 1e-6, "downmix sample " + i);
        }
        return new double[]{a, b};
    }

    @Test
    @DisplayName("correct the core channels of a program with dependent substreams")
    void dependentSubstreams() {
        var packets = encode(L71, 6, 16, 12);
        var forged = forge(packets, Map.of(5, new int[]{480, 200}));
        var plain = decode(packets, HELD);
        var held = decode(forged, HELD);
        assertEquals(1, held.runs().size());
        assertEquals(L71, held.runs().getFirst().layout());
        var expected = PreNoiseReference.apply(plain.plane(0), 5 * 1536, 4 * 480, 200);
        assertArrayEquals(expected, held.plane(0));
        for (var ch = 1; ch < 8; ch++) {
            assertArrayEquals(plain.plane(ch), held.plane(ch), "channel " + ch);
        }
    }

    /**
     * Returns the offset and length of each frame of a packet.
     */
    private static List<int[]> frames(byte[] data) {
        var frames = new ArrayList<int[]>();
        for (var at = 0; at + 4 <= data.length; ) {
            var length = 2 * (((data[at + 2] & 0x07) << 8 | data[at + 3] & 0xFF) + 1);
            frames.add(new int[]{at, length});
            at += length;
        }
        return frames;
    }

    /**
     * Returns the packet with transient data {@code {transprocloc, transproclen}} for a channel forged
     * into one of its frames, or with that frame removed when {@code data} is {@code null}.
     */
    private static Packet edit(Packet packet, int index, int channel, int[] data) {
        var bytes = packet.data();
        var frame = frames(bytes).get(index);
        var replacement = data == null ? new byte[0] : TransientForging.forge(
                Arrays.copyOfRange(bytes, frame[0], frame[0] + frame[1]), channel, data[0], data[1]);
        var joined = new byte[bytes.length - frame[1] + replacement.length];
        System.arraycopy(bytes, 0, joined, 0, frame[0]);
        System.arraycopy(replacement, 0, joined, frame[0], replacement.length);
        System.arraycopy(bytes, frame[0] + frame[1], joined, frame[0] + replacement.length,
                bytes.length - frame[0] - frame[1]);
        return new Packet(packet.trackId(), joined, packet.pts(), packet.dts(), packet.duration(), true);
    }

    /**
     * Returns the packet with a header no decoder reads: a reserved reduced sampling rate.
     */
    private static Packet unreadable(Packet packet) {
        var bytes = packet.data().clone();
        bytes[4] |= (byte) 0xF0;
        return new Packet(packet.trackId(), bytes, packet.pts(), packet.dts(), packet.duration(), true);
    }

    /**
     * Checks that a decode differs from the plain decode in one plane only, the expected one unless
     * negative, and that this plane is what the one-shot pseudo-code gives.
     */
    private static void assertOnePlaneCorrected(Decode plain, Decode held, int expectedPlane, int frameStart,
                                                int location, int length) {
        var corrected = -1;
        for (var plane = 0; plane < plain.runs().getFirst().planes().length; plane++) {
            if (!Arrays.equals(plain.plane(plane), held.plane(plane))) {
                assertEquals(-1, corrected, "more than one plane corrected");
                corrected = plane;
            }
        }
        assertTrue(corrected >= 0, "no plane corrected");
        if (expectedPlane >= 0) {
            assertEquals(expectedPlane, corrected);
        }
        assertArrayEquals(PreNoiseReference.apply(plain.plane(corrected), frameStart, location, length),
                held.plane(corrected));
    }

    @ParameterizedTest(name = "coded channel {0} into plane {1}")
    @CsvSource({"0, 0", "1, 2", "2, 1", "3, 4", "4, 5"})
    @DisplayName("write a late correction into the plane its coded channel feeds")
    void correctsMappedPlane(int channel, int plane) {
        var packets = encode(L51, 6, 12, 31);
        var forged = new ArrayList<>(packets);
        forged.set(4, edit(packets.get(4), 0, channel, new int[]{480, 200}));
        assertOnePlaneCorrected(decode(packets, HELD), decode(forged, HELD), plane, 4 * 1536, 4 * 480, 200);
    }

    @ParameterizedTest(name = "coded channel {0}")
    @CsvSource({"0", "1"})
    @DisplayName("write a late correction of a dependent substream into the plane it feeds")
    void correctsDependentPlane(int channel) {
        var packets = encode(L71, 6, 12, 32);
        assertEquals(2, frames(packets.getFirst().data()).size());
        var forged = new ArrayList<>(packets);
        forged.set(4, edit(packets.get(4), 1, channel, new int[]{480, 200}));
        assertOnePlaneCorrected(decode(packets, HELD), decode(forged, HELD), -1, 4 * 1536, 4 * 480, 200);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"MONO", "STEREO", "MATRIX_STEREO"})
    @DisplayName("downmix the late correction of the center channel with that channel's coefficient")
    void downmixedSurroundCorrection(Ac3Downmix downmix) {
        var packets = encode(L51, 6, 12, 33);
        var forged = new ArrayList<>(packets);
        forged.set(4, edit(packets.get(4), 0, 1, new int[]{480, 255}));
        var center = decode(packets, HELD).plane(2);
        var corrected = PreNoiseReference.apply(center, 4 * 1536, 4 * 480, 255);
        var options = HELD.toBuilder().downmix(downmix).build();
        var plain = decode(packets, options);
        var held = decode(forged, options);
        for (var out = 0; out < plain.runs().getFirst().planes().length; out++) {
            var plainOut = plain.plane(out);
            var heldOut = held.plane(out);
            double projection = 0;
            double energy = 0;
            for (var i = 0; i < plainOut.length; i++) {
                var delta = (double) corrected[i] - center[i];
                projection += ((double) heldOut[i] - plainOut[i]) * delta;
                energy += delta * delta;
            }
            var coefficient = projection / energy;
            assertTrue(Math.abs(coefficient) > 0.1, "center coefficient " + coefficient);
            for (var i = 0; i < plainOut.length; i++) {
                var delta = (double) corrected[i] - center[i];
                assertEquals(coefficient * delta, (double) heldOut[i] - plainOut[i], 1e-5, "output " + out + " sample " + i);
            }
        }
    }

    @Test
    @DisplayName("leave the held samples alone when silence follows a frame reporting a late correction")
    void silenceAfterLateCorrection() {
        var packets = encode(ChannelLayout.STEREO, 6, 12, 41);
        var forged = new ArrayList<>(packets);
        forged.set(4, edit(packets.get(4), 0, 0, new int[]{480, 200}));
        var expected = PreNoiseReference.apply(decode(packets, HELD).plane(0), 4 * 1536, 4 * 480, 200);
        try (var decoder = new Ac3Decoder(Ac3TestSupport.track(Codec.EAC3), HELD)) {
            var runs = new ArrayList<Run>();
            // frame 5 completes the buffer and reports the corrected samples of frame 4
            for (var index = 0; index <= 5; index++) {
                var packet = forged.get(index);
                collect(runs, emit -> decoder.decode(packet, emit));
            }
            assertEquals(0, collect(runs, emit -> decoder.decode(unreadable(packets.get(6)), emit)));
            assertEquals(1536, collect(runs, emit -> decoder.conceal(1536, emit)));
            assertEquals(512, collect(runs, decoder::flush));
            var output = new Decode(runs, List.of(), 512).plane(0);
            assertEquals(7 * 1536, output.length);
            assertArrayEquals(Arrays.copyOf(expected, 6 * 1536), Arrays.copyOf(output, 6 * 1536));
            for (var i = 6 * 1536; i < 7 * 1536; i++) {
                assertEquals(0, output[i], "silent sample " + i);
            }
        }
    }

    @Test
    @DisplayName("drop a pending correction at a silent concealment instead of applying it across the silence")
    void pendingAcrossSilence() {
        var packets = encode(ChannelLayout.STEREO, 6, 12, 42);
        var forged = new ArrayList<>(packets);
        forged.set(4, edit(packets.get(4), 0, 0, new int[]{480, 200}));
        assertArrayEquals(silenceAfterFrame4(packets), silenceAfterFrame4(forged));
    }

    private static float[] silenceAfterFrame4(List<Packet> packets) {
        try (var decoder = new Ac3Decoder(Ac3TestSupport.track(Codec.EAC3), HELD)) {
            var runs = new ArrayList<Run>();
            for (var index = 0; index <= 4; index++) {
                var packet = packets.get(index);
                collect(runs, emit -> decoder.decode(packet, emit));
            }
            collect(runs, emit -> decoder.decode(unreadable(packets.get(5)), emit));
            collect(runs, emit -> decoder.conceal(1536, emit));
            for (var index = 5; index < packets.size(); index++) {
                var packet = packets.get(index);
                collect(runs, emit -> decoder.decode(packet, emit));
            }
            collect(runs, decoder::flush);
            return new Decode(runs, List.of(), 512).plane(0);
        }
    }

    @Test
    @DisplayName("drop a pending correction at an AC-3 frame instead of writing it over that frame")
    void pendingAcrossAc3Frame() {
        var enhanced = encode(ChannelLayout.STEREO, 6, 12, 43);
        var core = Ac3EncoderTest.encode(Ac3EncoderOptions.of(48000, ChannelLayout.STEREO),
                noise(ChannelLayout.STEREO, 4 * 1536, 44));
        var plain = new ArrayList<>(enhanced.subList(0, 5));
        plain.addAll(core);
        plain.addAll(enhanced.subList(5, 12));
        var forged = new ArrayList<>(plain);
        forged.set(4, edit(plain.get(4), 0, 0, new int[]{480, 200}));
        assertSameOutput(decode(plain, HELD), decode(forged, HELD));
    }

    @Test
    @DisplayName("drop a pending correction of a dependent substream an access unit lacks")
    void pendingAcrossMissingDependent() {
        var packets = encode(L71, 1, 40, 45);
        // data in frame 8: the buffer ends 1024 samples after the start of that frame, at the end of frame 11
        var data = new int[]{256, 255};
        var whole = new ArrayList<>(packets);
        whole.set(8, edit(packets.get(8), 1, 0, data));
        assertOnePlaneCorrected(decode(packets, HELD), decode(whole, HELD), -1, 8 * 256, 4 * 256, 255);
        var gap = new ArrayList<>(packets);
        gap.set(10, edit(packets.get(10), 1, 0, null));
        var forged = new ArrayList<>(gap);
        forged.set(8, edit(gap.get(8), 1, 0, data));
        assertSameOutput(decode(gap, HELD), decode(forged, HELD));
    }

    @Test
    @DisplayName("correct the samples after a flush and leave the flushed ones as emitted")
    void flushWithPendingCorrection() {
        var packets = encode(ChannelLayout.STEREO, 6, 12, 46);
        var forged = new ArrayList<>(packets);
        forged.set(4, edit(packets.get(4), 0, 0, new int[]{480, 200}));
        var plain = decode(packets, HELD).plane(0);
        var expected = PreNoiseReference.apply(plain, 4 * 1536, 4 * 480, 200);
        try (var decoder = new Ac3Decoder(Ac3TestSupport.track(Codec.EAC3), HELD)) {
            var runs = new ArrayList<Run>();
            for (var index = 0; index <= 4; index++) {
                var packet = forged.get(index);
                collect(runs, emit -> decoder.decode(packet, emit));
            }
            assertEquals(512, collect(runs, decoder::flush));
            for (var index = 5; index < forged.size(); index++) {
                var packet = forged.get(index);
                collect(runs, emit -> decoder.decode(packet, emit));
            }
            collect(runs, decoder::flush);
            var output = new Decode(runs, List.of(), 512).plane(0);
            assertEquals(plain.length, output.length);
            assertArrayEquals(Arrays.copyOf(plain, 5 * 1536), Arrays.copyOf(output, 5 * 1536));
            assertArrayEquals(Arrays.copyOfRange(expected, 5 * 1536, expected.length),
                    Arrays.copyOfRange(output, 5 * 1536, output.length));
        }
    }

    @Test
    @DisplayName("return the samples a concealment hands over")
    void concealReturnsHanded() {
        try (var plain = new Ac3Decoder(Ac3TestSupport.track(Codec.EAC3), Ac3DecoderOptions.fullRange());
             var held = new Ac3Decoder(Ac3TestSupport.track(Codec.EAC3), HELD)) {
            var handed = new int[1];
            java.util.function.Consumer<AudioBuffer> count = buffer -> handed[0] += buffer.frames();
            assertEquals(1536, plain.conceal(1536, count));
            assertEquals(1536, handed[0]);
            handed[0] = 0;
            assertEquals(1024, held.conceal(1536, count));
            assertEquals(1024, handed[0]);
            handed[0] = 0;
            assertEquals(3072, held.conceal(3072, count));
            assertEquals(3072, handed[0]);
        }
    }

    @Test
    @DisplayName("hold back concealed samples and drop the held samples on reset")
    void concealAndReset() {
        var packets = encode(ChannelLayout.STEREO, 6, 8, 6);
        try (var decoder = new Ac3Decoder(Ac3TestSupport.track(Codec.EAC3), HELD)) {
            var runs = new ArrayList<Run>();
            assertEquals(1536 - 512, collect(runs, emit -> decoder.conceal(1536, emit)));
            assertEquals(1536, collect(runs, emit -> decoder.decode(packets.get(0), emit)));
            assertEquals(1536, collect(runs, emit -> decoder.conceal(1536, emit)));
            assertEquals(512, collect(runs, decoder::flush));
            assertEquals(0, collect(runs, decoder::flush));
            assertEquals(1536 - 512, collect(runs, emit -> decoder.decode(packets.get(1), emit)));
            decoder.reset();
            assertEquals(0, collect(runs, decoder::flush));
            assertEquals(1536 - 512, collect(runs, emit -> decoder.decode(packets.get(0), emit)));
            assertEquals(3 * 1536 + 1024 + 1024, runs.stream().mapToInt(Run::frames).sum());
        }
    }
}
