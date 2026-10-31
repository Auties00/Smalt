package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.audio.aac.internal.ChannelMapping;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.MediaLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the channel configurations beyond 7.1, above all the 22.2 configuration 13: a stream is
 * assembled from the channel elements of this module's encoder, one tone per coded channel, in the
 * element order of the configuration, so the decoder's output channel of each tone names the speaker
 * it assigned; ffmpeg's decode of the same stream, muxed into MP4, is the black-box reference for the
 * assignment.
 */
class AacLayoutsTest {
    private static final int RATE = 48000;

    private static final int SCE = 0;

    private static final int CPE = 1;

    private static final int LFE = 3;

    /** The element types of configuration 13 in coding order. */
    private static final int[] ELEMENTS_13 = {SCE, CPE, CPE, CPE, CPE, SCE, LFE, LFE, SCE, CPE, CPE, SCE, CPE, SCE, SCE,
            CPE};

    /** The speaker of each coded channel of configuration 13. */
    private static final List<Speaker> SPEAKERS_13 = List.of(Speaker.FRONT_CENTER, Speaker.FRONT_LEFT_OF_CENTER,
            Speaker.FRONT_RIGHT_OF_CENTER, Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.SIDE_LEFT,
            Speaker.SIDE_RIGHT, Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.BACK_CENTER, Speaker.LOW_FREQUENCY,
            Speaker.LOW_FREQUENCY_2, Speaker.TOP_FRONT_CENTER, Speaker.TOP_FRONT_LEFT, Speaker.TOP_FRONT_RIGHT,
            Speaker.TOP_SIDE_LEFT, Speaker.TOP_SIDE_RIGHT, Speaker.TOP_CENTER, Speaker.TOP_BACK_LEFT,
            Speaker.TOP_BACK_RIGHT, Speaker.TOP_BACK_CENTER, Speaker.BOTTOM_FRONT_CENTER, Speaker.BOTTOM_FRONT_LEFT,
            Speaker.BOTTOM_FRONT_RIGHT);

    /**
     * The speaker of each channel of ffmpeg's decode of configuration 13, which follows the channel order of
     * ITU-R BS.2051 sound system H rather than its channel layout's own order.
     */
    static final List<Speaker> FFMPEG_ORDER_13 = List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT,
            Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY, Speaker.BACK_LEFT, Speaker.BACK_RIGHT,
            Speaker.FRONT_LEFT_OF_CENTER, Speaker.FRONT_RIGHT_OF_CENTER, Speaker.BACK_CENTER, Speaker.LOW_FREQUENCY_2,
            Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT, Speaker.TOP_FRONT_LEFT, Speaker.TOP_FRONT_RIGHT,
            Speaker.TOP_FRONT_CENTER, Speaker.TOP_CENTER, Speaker.TOP_BACK_LEFT, Speaker.TOP_BACK_RIGHT,
            Speaker.TOP_SIDE_LEFT, Speaker.TOP_SIDE_RIGHT, Speaker.TOP_BACK_CENTER, Speaker.BOTTOM_FRONT_CENTER,
            Speaker.BOTTOM_FRONT_LEFT, Speaker.BOTTOM_FRONT_RIGHT);

    /** Returns the tone frequency of a coded channel, on a 1024-point DFT bin. */
    static double frequency(int channel) {
        var bin = channel == 10 ? 2 : channel == 11 ? 3 : 8 + 3 * channel;
        return bin * RATE / 1024.0;
    }

    /** Returns the raw data blocks of an encode of tones, one per channel. */
    static List<byte[]> encode(double... frequencies) {
        var samples = RATE;
        var planes = new float[frequencies.length][samples];
        for (var c = 0; c < frequencies.length; c++) {
            for (var i = 0; i < samples; i++) {
                planes[c][i] = (float) (0.2 * Math.sin(2 * Math.PI * frequencies[c] * i / RATE));
            }
        }
        var layout = frequencies.length == 1 ? ChannelLayout.MONO : ChannelLayout.STEREO;
        return AacEncoderTest.encode(AacEncoderOptions.of(RATE, layout, 64000 * frequencies.length), planes).frames();
    }

    /** Appends the elements of a raw data block, without its end marker, retagged and retyped. */
    static void appendElements(List<Boolean> bits, byte[] block, int type, int tag) {
        var whole = new BigInteger(1, block);
        var total = 8 * block.length;
        var endStart = total - 1 - whole.getLowestSetBit() - 2;
        for (var i = 0; i < endStart; i++) {
            var bit = whole.testBit(total - 1 - i);
            if (i < 3) {
                bit = (type >> (2 - i) & 1) != 0;
            } else if (i < 7) {
                bit = (tag >> (6 - i) & 1) != 0;
            }
            bits.add(bit);
        }
    }

    static byte[] toBytes(List<Boolean> bits) {
        var out = new byte[(bits.size() + 7) / 8];
        for (var i = 0; i < bits.size(); i++) {
            if (bits.get(i)) {
                out[i >> 3] |= (byte) (0x80 >>> (i & 7));
            }
        }
        return out;
    }

    /** Assembles a configuration 13 stream from encoded elements. */
    static List<byte[]> twentyTwoTwo() {
        var encodes = new ArrayList<List<byte[]>>();
        var channel = 0;
        for (var type : ELEMENTS_13) {
            encodes.add(type == CPE ? encode(frequency(channel), frequency(channel + 1)) : encode(frequency(channel)));
            channel += type == CPE ? 2 : 1;
        }
        var frames = new ArrayList<byte[]>();
        var count = encodes.stream().mapToInt(List::size).min().orElseThrow();
        for (var frame = 0; frame < count; frame++) {
            var bits = new ArrayList<Boolean>();
            var tags = new int[4];
            for (var element = 0; element < ELEMENTS_13.length; element++) {
                var type = ELEMENTS_13[element];
                appendElements(bits, encodes.get(element).get(frame), type, tags[type]++);
            }
            for (var i = 0; i < 3; i++) {
                bits.add(true);
            }
            frames.add(toBytes(bits));
        }
        return frames;
    }

    /** Returns the amplitude of a tone over the second half of a signal (Goertzel). */
    static double amplitude(float[] signal, double frequency) {
        var from = signal.length / 2;
        double re = 0;
        double im = 0;
        for (var i = from; i < signal.length; i++) {
            var angle = 2 * Math.PI * frequency * i / RATE;
            re += signal[i] * Math.cos(angle);
            im += signal[i] * Math.sin(angle);
        }
        return 2 * Math.hypot(re, im) / (signal.length - from);
    }

    static AudioTrack track(byte[] config) {
        return AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(Rational.timeBaseOf(RATE)).codecConfig(config)
                .sampleRate(RATE).build();
    }

    /** The AudioSpecificConfig of AAC LC at 48 kHz with configuration 13. */
    private static final byte[] CONFIG_13 = {0x11, (byte) 0xE8};

    @Test
    @DisplayName("decodes configuration 13 into the 22.2 layout, each coded channel on its speaker")
    void decode() {
        var frames = twentyTwoTwo();
        var decoded = AacTestSupport.decodeAll(new AacDecoder(track(CONFIG_13)), frames);
        assertEquals(AudioConfigs.aacLayout(13), decoded.layout());
        assertEquals(24, decoded.planes().length);
        for (var coded = 0; coded < 24; coded++) {
            var output = decoded.layout().indexOf(SPEAKERS_13.get(coded));
            var plane = decoded.planes()[output];
            var own = amplitude(plane, frequency(coded));
            assertTrue(own > 0.15, SPEAKERS_13.get(coded) + ": tone amplitude " + own);
            for (var other = 0; other < 24; other++) {
                if (other != coded) {
                    assertTrue(amplitude(plane, frequency(other)) < 0.01, SPEAKERS_13.get(coded) + " carries "
                            + SPEAKERS_13.get(other));
                }
            }
        }
    }

    @Test
    @DisplayName("assigns the 22.2 speakers as ffmpeg does")
    void againstFfmpeg() throws Exception {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var frames = twentyTwoTwo();
        var sink = MediaSink.memory();
        try (var muxer = new Mp4Muxer(sink, Mp4MuxerOptions.m4a())) {
            muxer.addTrack(AudioTrack.builder().id(0).codec(Codec.AAC).timeBase(Rational.timeBaseOf(RATE)).duration(-1)
                    .sampleRate(RATE).layout(AudioConfigs.aacLayout(13)).codecConfig(CONFIG_13).build());
            for (var i = 0; i < frames.size(); i++) {
                muxer.writePacket(new Packet(0, frames.get(i), 1024L * i, 1024L * i, 1024, true));
            }
            muxer.finish();
        }
        var directory = Files.createDirectories(Path.of("target", "aac-layouts"));
        var file = directory.resolve("22.2.m4a");
        Files.write(file, sink.toByteArray());
        com.github.auties00.smalt.testkit.validate.Validators.assertValid(file);
        var probe = Oracle.probe(file);
        assertTrue(probe.contains("\"channels\": 24"), probe);
        var reference = AacTestSupport.deinterleave(Oracle.decodeAudio(file), 24);
        var decoded = AacTestSupport.decodeAll(new AacDecoder(track(CONFIG_13)), frames);
        for (var c = 0; c < 24; c++) {
            var speaker = FFMPEG_ORDER_13.get(c);
            var ours = decoded.planes()[decoded.layout().indexOf(speaker)];
            var theirs = reference[c];
            var length = Math.min(ours.length, theirs.length);
            double error = 0;
            for (var i = 0; i < length; i++) {
                error = Math.max(error, Math.abs(ours[i] - theirs[i]));
            }
            assertTrue(error < 1e-4, speaker + ": ffmpeg differs by " + error);
        }
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(AudioConfigs.aacLayout(13), track.layout().orElseThrow());
        }
    }

    @ParameterizedTest(name = "configuration {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 11, 12, 13, 14})
    @DisplayName("probes the layout the decoder outputs")
    void probedLayout(int configuration) {
        assertEquals(ChannelMapping.ofConfiguration(configuration, MediaLimits.DEFAULT).layout(),
                AudioConfigs.aacLayout(configuration));
    }
}
