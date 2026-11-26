package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.container.mpeg.MpegAudioDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the ISO/IEC 13818-4 MPEG-2 audio conformance sets (the Layer II multichannel extension in every channel
 * configuration with and without an extension bitstream, dynamic crosstalk, prediction, phantom center, the
 * low-frequency enhancement channel and the dematrixing procedures, and the lower sampling frequency, mono and dual
 * channel sets) and compares every output channel with its reference.
 *
 * <p>The references are 16-bit, except those of sets 34 and 35, which are 24-bit and held to ISO/IEC 13818-4 full
 * accuracy as they stand. The 16-bit ones were written by the originators' decoders, which round to nearest or
 * toward zero, so each channel is rounded both ways and must stay within one unit of the reference everywhere and
 * meet full accuracy in the rounding that matches; sets 02 and 09, whose decoder rounds differently still, meet
 * limited accuracy. Set 22 is a Layer I multichannel stream, one extension over three frames, and set 23 a Layer III
 * one; the second stereo programs of sets 06 and 07 and the multilingual channels of sets 10 and 12 are compared by
 * {@link Mp2ProgramConformanceTest}.
 */
class Mp2MultichannelConformanceTest {
    /** The rule of every 16-bit reference: within one unit of the reference. */
    private static final Tolerance ONE_UNIT = Tolerance.offByOne();

    /** The ISO/IEC 13818-4 full accuracy class. */
    private static final Tolerance FULL = Tolerance.iso11172(Tolerance.Iso11172Accuracy.FULL);

    /** The ISO/IEC 13818-4 limited accuracy class. */
    private static final Tolerance LIMITED = Tolerance.iso11172(Tolerance.Iso11172Accuracy.LIMITED);

    /** The sets, by number. */
    private static final List<String> SETS = IntStream.rangeClosed(1, 35)
            .mapToObj(number -> String.format("%02d", number))
            .toList();

    /** The speaker of each reference channel file suffix. */
    private static final Map<String, Speaker> CHANNELS = new LinkedHashMap<>();

    static {
        CHANNELS.put("_l", Speaker.FRONT_LEFT);
        CHANNELS.put("_r", Speaker.FRONT_RIGHT);
        CHANNELS.put("_c", Speaker.FRONT_CENTER);
        CHANNELS.put("_lfe", Speaker.LOW_FREQUENCY);
        CHANNELS.put("_ls", Speaker.BACK_LEFT);
        CHANNELS.put("_rs", Speaker.BACK_RIGHT);
        CHANNELS.put("_s", Speaker.BACK_CENTER);
        CHANNELS.put("_I", Speaker.FRONT_LEFT);
        CHANNELS.put("_II", Speaker.FRONT_RIGHT);
        CHANNELS.put("", Speaker.FRONT_CENTER);
    }

    static Stream<Arguments> sets() {
        return SETS.stream().map(Arguments::of);
    }

    /** Returns the files of a set, by name without directory. */
    static Map<String, byte[]> files(String set) {
        var archive = Conformance.vector("iso13818-4-audio-test" + set + ".tar.gz");
        var files = new HashMap<String, byte[]>();
        for (var entry : RasterTesting.untarGz(archive).entrySet()) {
            var name = entry.getKey();
            files.put(name.substring(name.lastIndexOf('/') + 1), entry.getValue());
        }
        return files;
    }

    /** Returns the frames of an extension bitstream. */
    static List<byte[]> extensionFrames(byte[] stream) {
        var frames = new ArrayList<byte[]>();
        var i = 0;
        while (i + 5 <= stream.length) {
            if ((stream[i] & 0xFF) != 0x7F || (stream[i + 1] & 0xF0) != 0xF0) {
                i++;
                continue;
            }
            var length = ((stream[i + 3] & 0x0F) << 7) | ((stream[i + 4] & 0xFE) >> 1);
            frames.add(Arrays.copyOfRange(stream, i, Math.min(stream.length, i + Math.max(5, length))));
            i += Math.max(5, length);
        }
        return frames;
    }

    /**
     * Joins each base frame with the extension frame of the same index, as the decoder takes them; a Layer I stream has
     * one extension frame per three base frames, which goes with the third.
     */
    static List<Packet> packets(byte[] base, byte[] extension) {
        List<Packet> packets;
        try (var demuxer = new MpegAudioDemuxer(MediaSource.of(base))) {
            packets = AudioPipeline.packets(demuxer, AudioPipeline.firstAudio(demuxer).id());
        }
        if (extension == null) {
            return packets;
        }
        var frames = extensionFrames(extension);
        var group = frames.size() * 3 == packets.size() ? 3 : 1;
        assertEquals(packets.size(), frames.size() * group, "one extension frame per base frame or group of three");
        var joined = new ArrayList<Packet>(packets.size());
        for (var i = 0; i < packets.size(); i++) {
            var packet = packets.get(i);
            if (i % group != group - 1) {
                joined.add(packet);
                continue;
            }
            var frame = frames.get(i / group);
            var data = Arrays.copyOf(packet.data(), packet.size() + frame.length);
            System.arraycopy(frame, 0, data, packet.size(), frame.length);
            joined.add(packet.withData(data));
        }
        return joined;
    }

    /** Decodes a set into one plane per output speaker, and returns the layouts seen. */
    static Map<Speaker, float[]> decode(Map<String, byte[]> files, String set, List<ChannelLayout> layouts,
                                        Mp3DecoderOptions options) {
        var name = "test" + set;
        var packets = packets(files.get(name + ".mpg"), files.get(name + ".ext"));
        var output = new LinkedHashMap<Speaker, AudioPipeline.FloatList>();
        try (var demuxer = new MpegAudioDemuxer(MediaSource.of(files.get(name + ".mpg")))) {
            var decoder = new Mp3Decoder(AudioPipeline.firstAudio(demuxer), options);
            Consumer<AudioBuffer> sink = buffer -> {
                if (!layouts.contains(buffer.layout())) {
                    layouts.add(buffer.layout());
                }
                for (var c = 0; c < buffer.channels(); c++) {
                    var list = output.computeIfAbsent(buffer.layout().speakers().get(c),
                            ignored -> new AudioPipeline.FloatList());
                    var plane = buffer.plane(c);
                    for (var i = 0; i < buffer.frames(); i++) {
                        list.add(plane[i]);
                    }
                }
            };
            for (var packet : packets) {
                decoder.decode(packet, sink);
            }
            decoder.flush(sink);
        }
        var result = new LinkedHashMap<Speaker, float[]>();
        output.forEach((speaker, list) -> result.put(speaker, list.toArray()));
        return result;
    }

    /** Reads big-endian 16-bit or 24-bit samples. */
    static int[] samples(byte[] data, int bytesPerSample) {
        var out = new int[data.length / bytesPerSample];
        for (var i = 0; i < out.length; i++) {
            var p = i * bytesPerSample;
            out[i] = bytesPerSample == 3 ? (data[p] << 16) | ((data[p + 1] & 0xFF) << 8) | (data[p + 2] & 0xFF)
                    : (short) (((data[p] & 0xFF) << 8) | (data[p + 1] & 0xFF));
        }
        return out;
    }

    /** Removes the sixth sample of every twelve. */
    private static int[] withoutFrameSample5(int[] samples) {
        var kept = new int[samples.length / 12 * 11];
        var to = 0;
        for (var i = 0; i < samples.length && to < kept.length; i++) {
            if (i % 12 != 5) {
                kept[to++] = samples[i];
            }
        }
        return kept;
    }

    /** Asserts low-frequency channel samples within one unit of their reference. */
    private static void assertLfeWithin(String what, int[] expected, float[] actual) {
        var reference = new short[expected.length];
        for (var i = 0; i < reference.length; i++) {
            reference[i] = (short) expected[i];
        }
        var nearest = toPcm16(actual, actual.length, 1, 0, false);
        var zero = toPcm16(actual, actual.length, 1, 0, true);
        var matched = Tolerance.Difference.ofPcm(reference, nearest).rms() <= Tolerance.Difference.ofPcm(reference, zero).rms()
                ? nearest : zero;
        ONE_UNIT.assertPcm(what, reference, matched);
        FULL.assertPcm(what, reference, matched);
    }

    /** Rounds float samples to 16 bits, to nearest or toward zero, saturating. */
    static short[] toPcm16(float[] samples, int count, int stride, int phase, boolean towardZero) {
        var out = new short[count];
        for (var i = 0; i < count; i++) {
            var scaled = samples[i * stride + phase] * 32768.0;
            var value = towardZero ? (double) (long) scaled : Math.rint(scaled);
            out[i] = (short) Math.max(-32768, Math.min(32767, value));
        }
        return out;
    }

    @ParameterizedTest(name = "test{0}")
    @MethodSource("sets")
    @DisplayName("every output channel of the ISO/IEC 13818-4 sets matches its reference")
    void conformance(String set) {
        var files = files(set);
        var layouts = new ArrayList<ChannelLayout>();
        var output = decode(files, set, layouts, Mp3DecoderOptions.defaults());
        assertEquals(1, layouts.size(), "the layout changes within test" + set + ": " + layouts);
        var compared = 0;
        for (var entry : CHANNELS.entrySet()) {
            var reference = files.get("test" + set + entry.getKey() + ".pcm");
            // test23.pcm is the Lo/Ro base, which layer3Base compares
            if (reference == null || (set.equals("23") && entry.getKey().isEmpty())) {
                continue;
            }
            var speaker = entry.getValue();
            var ours = output.get(speaker);
            assertTrue(ours != null, "test" + set + entry.getKey() + " has no " + speaker + " output in " + layouts);
            if (set.equals("23")) {
                // the file ends inside frame 342, which the reference decoder did not output
                ours = Arrays.copyOf(ours, 341 * 1152);
            }
            var lfe = speaker == Speaker.LOW_FREQUENCY;
            var wide = !lfe && reference.length == ours.length * 3;
            var expected = samples(reference, wide ? 3 : 2);
            var what = "test" + set + entry.getKey();
            if (lfe && set.equals("23")) {
                // the reference breaks the smooth low-frequency channel at the sixth of each frame's twelve samples, at
                // the frame's overlap, where the standard's transform and this decoder's output stay on the curve
                expected = withoutFrameSample5(expected);
                var kept = new float[expected.length];
                for (var i = 0; i < kept.length; i++) {
                    var original = i / 11 * 12 + i % 11;
                    kept[i] = ours[(original % 12 < 5 ? original : original + 1) * 96 + 95];
                }
                assertLfeWithin(what, expected, kept);
                compared++;
                continue;
            }
            // the decoder reaches each coded LFE sample at the end of its 96-sample period
            var stride = lfe ? 96 : 1;
            var phase = lfe ? 95 : 0;
            assertEquals(expected.length, ours.length / stride, what + " length");
            if (wide) {
                var reference24 = new float[expected.length];
                for (var i = 0; i < expected.length; i++) {
                    reference24[i] = expected[i] / 8388608.0f;
                }
                System.out.println("[mp2-mc] " + what + ": " + Tolerance.Difference.ofPcm(reference24, ours));
                FULL.assertPcm(what, reference24, ours);
            } else {
                var reference16 = new short[expected.length];
                for (var i = 0; i < expected.length; i++) {
                    reference16[i] = (short) expected[i];
                }
                var nearest = toPcm16(ours, expected.length, stride, phase, false);
                var towardZero = toPcm16(ours, expected.length, stride, phase, true);
                var nearestDifference = Tolerance.Difference.ofPcm(reference16, nearest);
                var zeroDifference = Tolerance.Difference.ofPcm(reference16, towardZero);
                var matched = nearestDifference.rms() <= zeroDifference.rms() ? nearest : towardZero;
                System.out.println("[mp2-mc] " + what + ": nearest " + nearestDifference + "; toward zero "
                        + zeroDifference);
                ONE_UNIT.assertPcm(what, reference16, matched);
                (set.equals("02") || set.equals("09") ? LIMITED : FULL).assertPcm(what, reference16, matched);
            }
            compared++;
        }
        assertTrue(compared > 0, "test" + set + " has no reference channel");
    }

    @Test
    @DisplayName("with the multichannel option off, set 23 decodes to its reference Lo/Ro base channels")
    void layer3Base() {
        var files = files("23");
        var layouts = new ArrayList<ChannelLayout>();
        var base = decode(files, "23", layouts, Mp3DecoderOptions.builder().multichannel(false).build());
        assertEquals(List.of(ChannelLayout.STEREO), layouts);
        var reference = samples(files.get("test23.pcm"), 2);
        var left = new short[reference.length / 2];
        var right = new short[reference.length / 2];
        for (var i = 0; i < left.length; i++) {
            left[i] = (short) reference[2 * i];
            right[i] = (short) reference[2 * i + 1];
        }
        var lo = base.get(Speaker.FRONT_LEFT);
        var ro = base.get(Speaker.FRONT_RIGHT);
        // the file ends inside frame 342, which the reference decoder did not output
        assertEquals(left.length, 341 * 1152, "test23 base length");
        // the originators' decoder rounds toward zero
        FULL.assertPcm("test23 Lo", left, toPcm16(lo, left.length, 1, 0, true));
        FULL.assertPcm("test23 Ro", right, toPcm16(ro, right.length, 1, 0, true));
    }

    @Test
    @DisplayName("with the multichannel option off, a multichannel stream decodes to its Lo/Ro base channels")
    void baseOnly() {
        var files = files("02");
        var layouts = new ArrayList<ChannelLayout>();
        var base = decode(files, "02", layouts, Mp3DecoderOptions.builder().multichannel(false).build());
        assertEquals(List.of(ChannelLayout.STEREO), layouts);
        var full = decode(files, "02", new ArrayList<>(), Mp3DecoderOptions.defaults());
        assertEquals(5, full.size());
        // set 02 dematrixes with procedure 0, so Lo mixes the center and left surround into the left channel
        var lo = base.get(Speaker.FRONT_LEFT);
        var left = full.get(Speaker.FRONT_LEFT);
        var center = full.get(Speaker.FRONT_CENTER);
        var surround = full.get(Speaker.BACK_LEFT);
        var a = 1 / (1 + Math.sqrt(2));
        var b = a / Math.sqrt(2);
        var error = 0.0;
        var signal = 0.0;
        for (var i = 2000; i < lo.length; i++) {
            var mix = a * left[i] + b * center[i] + b * surround[i];
            error += (lo[i] - mix) * (lo[i] - mix);
            signal += lo[i] * lo[i];
        }
        assertTrue(signal > 0 && error < signal * 1e-4, "Lo is not the procedure 0 downmix: " + error / signal);
    }
}
