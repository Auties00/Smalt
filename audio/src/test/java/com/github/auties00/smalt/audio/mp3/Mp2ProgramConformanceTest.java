package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.container.mpeg.MpegAudioDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Decodes the second stereo programs (sets 06 and 07, {@code 2/0 + 2/0} and {@code 3/0 + 2/0}) and the multilingual
 * channels (set 10 at the full sampling rate, set 12 at half of it) of the ISO/IEC 13818-4 MPEG-2 audio conformance
 * sets through {@link Mp3DecoderOptions#program()}, and compares every channel with its reference. The references are
 * 16-bit, written by decoders that round to nearest or toward zero, so each channel is rounded both ways and must
 * stay within one unit of the reference everywhere and meet ISO/IEC 13818-4 full accuracy in the rounding that
 * matches, as {@link Mp2MultichannelConformanceTest} holds the main programs.
 */
class Mp2ProgramConformanceTest {
    /** The rule of every 16-bit reference: within one unit of the reference. */
    private static final Tolerance ONE_UNIT = Tolerance.offByOne();

    /** The ISO/IEC 13818-4 full accuracy class. */
    private static final Tolerance FULL = Tolerance.iso11172(Tolerance.Iso11172Accuracy.FULL);

    /** Decodes a set into its buffers. */
    private static List<AudioBuffer> decode(Map<String, byte[]> files, String set, Mp3DecoderOptions options) {
        var name = "test" + set;
        var packets = Mp2MultichannelConformanceTest.packets(files.get(name + ".mpg"), files.get(name + ".ext"));
        var buffers = new ArrayList<AudioBuffer>();
        try (var demuxer = new MpegAudioDemuxer(MediaSource.of(files.get(name + ".mpg")))) {
            var decoder = new Mp3Decoder(AudioPipeline.firstAudio(demuxer), options);
            for (var packet : packets) {
                decoder.decode(packet, buffer -> buffers.add(buffer.copy()));
            }
        }
        return buffers;
    }

    /** Joins one channel of the buffers. */
    private static float[] plane(List<AudioBuffer> buffers, int channel) {
        var list = new AudioPipeline.FloatList();
        for (var buffer : buffers) {
            var plane = buffer.plane(channel);
            for (var i = 0; i < buffer.frames(); i++) {
                list.add(plane[i]);
            }
        }
        return list.toArray();
    }

    /** Compares a decoded channel with a 16-bit reference. */
    private static void compare(String what, byte[] reference, float[] ours) {
        assertNotNull(reference, what + " has no reference");
        var expected = Mp2MultichannelConformanceTest.samples(reference, 2);
        assertEquals(expected.length, ours.length, what + " length");
        var reference16 = new short[expected.length];
        for (var i = 0; i < expected.length; i++) {
            reference16[i] = (short) expected[i];
        }
        var nearest = Mp2MultichannelConformanceTest.toPcm16(ours, expected.length, 1, 0, false);
        var towardZero = Mp2MultichannelConformanceTest.toPcm16(ours, expected.length, 1, 0, true);
        var nearestDifference = Tolerance.Difference.ofPcm(reference16, nearest);
        var zeroDifference = Tolerance.Difference.ofPcm(reference16, towardZero);
        var matched = nearestDifference.rms() <= zeroDifference.rms() ? nearest : towardZero;
        System.out.println("[mp2-program] " + what + ": nearest " + nearestDifference + "; toward zero "
                + zeroDifference);
        ONE_UNIT.assertPcm(what, reference16, matched);
        FULL.assertPcm(what, reference16, matched);
    }

    @ParameterizedTest(name = "test{0}")
    @CsvSource({"06", "07"})
    @DisplayName("the second stereo program of the ISO/IEC 13818-4 sets matches its reference")
    void secondStereo(String set) {
        var files = Mp2MultichannelConformanceTest.files(set);
        var buffers = decode(files, set, Mp3DecoderOptions.builder().program(Mp2Program.SECOND_STEREO).build());
        for (var buffer : buffers) {
            assertEquals(ChannelLayout.STEREO, buffer.layout());
            assertEquals(48000, buffer.sampleRate());
        }
        compare("test" + set + "_l2", files.get("test" + set + "_l2.pcm"), plane(buffers, 0));
        compare("test" + set + "_r2", files.get("test" + set + "_r2.pcm"), plane(buffers, 1));
    }

    @ParameterizedTest(name = "test{0}")
    @CsvSource({"10, 48000", "12, 24000"})
    @DisplayName("every multilingual channel of the ISO/IEC 13818-4 sets matches its reference, together and alone")
    void multilingual(String set, int rate) {
        var files = Mp2MultichannelConformanceTest.files(set);
        var all = decode(files, set, Mp3DecoderOptions.builder().program(Mp2Program.MULTILINGUAL).build());
        var layout = new ChannelLayout(Collections.nCopies(7, Speaker.UNKNOWN));
        for (var buffer : all) {
            assertEquals(layout, buffer.layout());
            assertEquals(rate, buffer.sampleRate());
            assertEquals(rate == 48000 ? 1152 : 576, buffer.frames());
        }
        for (var channel = 1; channel <= 7; channel++) {
            var what = "test" + set + "_m" + channel;
            var reference = files.get(what + ".pcm");
            compare(what, reference, plane(all, channel - 1));
            var alone = decode(files, set, Mp3DecoderOptions.builder().program(Mp2Program.MULTILINGUAL)
                    .multilingualChannel(channel).build());
            assertEquals(ChannelLayout.MONO, alone.getFirst().layout());
            compare(what + " alone", reference, plane(alone, 0));
        }
    }
}
