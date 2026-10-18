package com.github.auties00.smalt.audio.amr;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers {@code AmrEncoderOptions.ofBitrate}, the default-options encoder constructor, the decoder
 * constructor taking the generic {@link AudioDecoderOptions}, {@code frameSize()} and the output room
 * {@code AmrEncoder.encodeFrame} requires.
 */
@DisplayName("AMR API additions")
class AmrApiAdditionsTest {
    private static List<float[]> decode(AmrDecoder decoder, List<Packet> packets) {
        var output = new ArrayList<float[]>();
        for (var packet : packets) {
            decoder.decode(packet, buffer -> {
                var samples = new float[buffer.frames()];
                System.arraycopy(buffer.plane(0), 0, samples, 0, samples.length);
                output.add(samples);
            });
        }
        return output;
    }

    @Nested
    @DisplayName("AmrEncoderOptions.ofBitrate")
    class OfBitrate {
        @ParameterizedTest(name = "{0} at {1} bit/s")
        @CsvSource({"AMR_NB, 4000", "AMR_NB, 7950", "AMR_NB, 64000", "AMR_WB, 6000", "AMR_WB, 15000",
                "AMR_WB, 30000"})
        @DisplayName("picks the closest mode without discontinuous transmission")
        void closest(Codec codec, int bitrate) {
            var options = AmrEncoderOptions.ofBitrate(codec, bitrate);
            assertEquals(AmrMode.closest(codec, bitrate), options.mode());
            assertFalse(options.dtx());
        }

        @Test
        @DisplayName("rejects a codec that is not AMR")
        void rejects() {
            assertThrows(IllegalArgumentException.class, () -> AmrEncoderOptions.ofBitrate(Codec.AAC, 12_200));
        }
    }

    @Test
    @DisplayName("AmrEncoder() encodes as AmrEncoder(AmrEncoderOptions.defaults())")
    void defaultEncoder() {
        try (var encoder = new AmrEncoder()) {
            assertEquals(AmrEncoderOptions.defaults(), encoder.options());
            assertEquals(Codec.AMR_NB, encoder.codec());
        }
        var pcm = AmrTestSupport.signal(8000);
        var expected = AmrTestSupport.encode(AmrEncoderOptions.defaults(), pcm);
        var actual = new ArrayList<Packet>();
        try (var encoder = new AmrEncoder()) {
            var buffer = new AudioBuffer(ChannelLayout.MONO, 8000, 997);
            for (var offset = 0; offset < pcm.length; offset += 997) {
                var count = Math.min(997, pcm.length - offset);
                for (var i = 0; i < count; i++) {
                    buffer.plane(0)[i] = pcm[offset + i] / 32768f;
                }
                buffer.setFrames(count);
                encoder.encode(buffer, actual::add);
            }
            encoder.flush(actual::add);
        }
        assertEquals(expected.size(), actual.size());
        for (var i = 0; i < expected.size(); i++) {
            assertArrayEquals(expected.get(i).data(), actual.get(i).data());
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(AmrMode.class)
    @DisplayName("encodeFrame needs room for mode().frameBytes() bytes only and writes what a larger buffer receives")
    void encodeFrameRoom(AmrMode mode) {
        var pcm = AmrTestSupport.signal(mode.sampleRate());
        try (var exact = new AmrEncoder(AmrEncoderOptions.of(mode));
             var roomy = new AmrEncoder(AmrEncoderOptions.of(mode))) {
            var frame = exact.frameSize();
            var tight = new byte[mode.frameBytes()];
            var large = new byte[61];
            for (var offset = 0; offset + frame <= pcm.length; offset += frame) {
                var written = exact.encodeFrame(pcm, offset, tight, 0);
                assertEquals(written, roomy.encodeFrame(pcm, offset, large, 0));
                assertArrayEquals(java.util.Arrays.copyOf(large, written), java.util.Arrays.copyOf(tight, written));
            }
            assertThrows(IndexOutOfBoundsException.class,
                    () -> exact.encodeFrame(pcm, 0, new byte[mode.frameBytes() - 1], 0));
        }
    }

    @Nested
    @DisplayName("AmrDecoder(AudioTrack, AudioDecoderOptions)")
    class Decoder {
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Codec.class, names = {"AMR_NB", "AMR_WB"})
        @DisplayName("carries the limits over, ignores the preferred rate and decodes as the default options do")
        void sameOutput(Codec codec) {
            var mode = codec == Codec.AMR_NB ? AmrMode.NB_12_2 : AmrMode.WB_23_85;
            var packets = AmrTestSupport.encode(AmrEncoderOptions.of(mode),
                    AmrTestSupport.signal(AmrTestSupport.rate(codec)));
            var limits = MediaLimits.builder().maxChannels(2).build();
            var generic = AudioDecoderOptions.builder().limits(limits).preferredSampleRate(48_000).build();
            try (var expected = new AmrDecoder(AmrTestSupport.track(codec));
                 var actual = new AmrDecoder(AmrTestSupport.track(codec), generic)) {
                assertEquals(limits, actual.options().limits());
                assertEquals(AmrTestSupport.rate(codec), actual.sampleRate());
                var left = decode(expected, packets);
                var right = decode(actual, packets);
                assertEquals(left.size(), right.size());
                for (var i = 0; i < left.size(); i++) {
                    assertArrayEquals(left.get(i), right.get(i));
                }
            }
        }

        @Test
        @DisplayName("rejects a track of another codec and null options")
        void rejects() {
            var aac = AudioTrack.builder().id(0).codec(Codec.AAC).timeBase(new Rational(1, 48_000)).sampleRate(48_000)
                    .layout(ChannelLayout.STEREO).build();
            assertThrows(IllegalArgumentException.class, () -> new AmrDecoder(aac, AudioDecoderOptions.defaults()));
            assertThrows(NullPointerException.class,
                    () -> new AmrDecoder(AmrTestSupport.track(Codec.AMR_NB), (AudioDecoderOptions) null));
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"AMR_NB, 160", "AMR_WB, 320"})
        @DisplayName("frameSize() is the number of samples in a frame")
        void frameSize(Codec codec, int samples) {
            try (var decoder = new AmrDecoder(AmrTestSupport.track(codec), AudioDecoderOptions.defaults())) {
                assertEquals(samples, decoder.frameSize());
            }
        }
    }
}
