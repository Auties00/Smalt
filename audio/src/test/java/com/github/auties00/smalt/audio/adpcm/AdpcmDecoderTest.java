package com.github.auties00.smalt.audio.adpcm;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.telephony.TelephonyStreams;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes IMA and Microsoft ADPCM files whose reference decodes were made with Windows' Audio Compression Manager
 * codecs ({@code imaadp32.acm} and {@code msadp32.acm}) and an {@code ima4} AIFF-C file whose reference decode is
 * ffmpeg's; the files are in {@code src/test/resources/com/github/auties00/smalt/audio/adpcm} (see
 * {@code adpcm.sha256} and its README), the references pinned here as SHA-256 digests of their 16-bit
 * little-endian samples. {@code ms-random.wav} holds random block contents, whose deltas grow until the codec's
 * 32-bit arithmetic wraps.
 */
@DisplayName("IMA and Microsoft ADPCM decoder")
class AdpcmDecoderTest {
    private static byte[] resource(String name) {
        try (var in = AdpcmDecoderTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + name);
            }
            return in.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static AudioTrack track(Codec codec, int channels, byte[] config) {
        return AudioTrack.builder().id(1).codec(codec).timeBase(Rational.timeBaseOf(8000)).sampleRate(8000)
                .layout(ChannelLayout.defaultFor(channels)).bitsPerSample(4).codecConfig(config).build();
    }

    private static Packet packet(byte[] data) {
        return new Packet(1, data, 0, 0, 0, true);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "ima-ffmpeg.wav, ADPCM_IMA_WAV, a0cb7a29220d9d2b11a158319c87492c01b71cedecec3e74877727f2d89a585d",
            "ms-acm.wav, ADPCM_MS, d43937d96cafa8b5a6abfd641b882340bd35f36407c127aa7c17be2d13f57f0b",
            "ms-random.wav, ADPCM_MS, 19c8f7544cb5eba4c1f4c2f75caae198df8f4516a3319f968ca7ecd94d497eb9",
            "ima4-ffmpeg.aif, ADPCM_IMA_QT, e03fbb37a709bce0e1a064f3645171af755ceca2dea527eb272b33e4c980e325"})
    @DisplayName("decodes the reference files sample for sample")
    void reference(String file, Codec codec, String sha256) {
        var stream = TelephonyStreams.demux(resource(file));
        assertEquals(codec, stream.track().codec());
        var decoder = AudioDecoders.create(stream.track());
        assertInstanceOf(AdpcmDecoder.class, decoder);
        var samples = TelephonyStreams.decodeShorts(decoder, stream.packets());
        assertEquals(sha256, TelephonyStreams.sha256(samples), file);
    }

    @Test
    @DisplayName("float and double outputs are the integer samples scaled by 2^-15")
    void outputs() {
        var stream = TelephonyStreams.demux(resource("ms-acm.wav"));
        var integers = TelephonyStreams.decodeShorts(new AdpcmDecoder(stream.track()), stream.packets());
        var floats = TelephonyStreams.decodeFloats(new AdpcmDecoder(stream.track()), stream.packets());
        assertArrayEquals(TelephonyStreams.floats(integers), floats);
        var doubles = new ArrayList<Double>();
        var decoder = new AdpcmDecoder(stream.track());
        for (var packet : stream.packets()) {
            decoder.decodeDoubles(packet, block -> {
                for (var i = 0; i < block.frames(); i++) {
                    for (var c = 0; c < block.layout().channels(); c++) {
                        doubles.add(block.planes()[c][i]);
                    }
                }
            });
        }
        assertEquals(integers.length, doubles.size());
        for (var i = 0; i < integers.length; i++) {
            assertEquals(integers[i] * 0x1p-15, doubles.get(i), "sample " + i);
        }
    }

    @Test
    @DisplayName("an IMA ADPCM block decodes to its header sample then its codes, low nibble first")
    void imaBlock() {
        // one channel: first sample 1000, step index 0, then 4 bytes of codes 0x10 0x32 0x54 0x76
        var block = new byte[]{(byte) 0xE8, 0x03, 0, 0, 0x10, 0x32, 0x54, 0x76};
        var decoder = new AdpcmDecoder(track(Codec.ADPCM_IMA_WAV, 1, null));
        var samples = TelephonyStreams.decodeShorts(decoder, List.of(packet(block)));
        assertEquals(9, samples.length);
        assertEquals(1000, samples[0]);
        // code 0 at step 7: difference 7 >> 3 = 0, step index stays 0
        assertEquals(1000, samples[1]);
        // code 1 at step 7: 0 + 7 >> 2 = 1
        assertEquals(1001, samples[2]);
    }

    @Test
    @DisplayName("an ima4 frame continues the predictor when its header matches it and restarts otherwise")
    void ima4Continuation() {
        // header: predictor 0x1000, step index 0; every code 1 adds 7 >> 2 = 1 at step 7 and keeps the index at 0
        var frame = new byte[34];
        frame[0] = 0x10;
        for (var i = 2; i < 34; i++) {
            frame[i] = 0x11;
        }
        var restart = frame.clone();
        restart[1] = 1;
        var decoder = new AdpcmDecoder(track(Codec.ADPCM_IMA_QT, 1, null));
        var samples = TelephonyStreams.decodeShorts(decoder, List.of(packet(frame), packet(frame), packet(restart)));
        assertEquals(192, samples.length);
        for (var k = 0; k < 64; k++) {
            assertEquals(4097 + k, samples[k], "first frame sample " + k);
            // 4160 & 0xFF80 and step index 0 match the second header, so the full predictor 4160 carries over
            assertEquals(4161 + k, samples[64 + k], "continued frame sample " + k);
        }
        // step index 1 differs from the state's 0, so the third frame restarts at 0x1000 with step 8, adding 1 + 2
        assertEquals(4096 + 3, samples[128]);
    }

    @Test
    @DisplayName("a Microsoft ADPCM configuration supplies its own coefficient table")
    void customCoefficients() {
        // wSamplesPerBlock 4, one pair (512, -256): the prediction is 2 * s1 - s2
        var config = new byte[]{4, 0, 1, 0, 0, 2, 0, (byte) 0xFF};
        var decoder = new AdpcmDecoder(track(Codec.ADPCM_MS, 1, config));
        // predictor 0, delta 16, sample1 = 20, sample2 = 10, codes 0 and 0
        var block = new byte[]{0, 16, 0, 20, 0, 10, 0, 0};
        var samples = TelephonyStreams.decodeShorts(decoder, List.of(packet(block)));
        assertArrayEquals(new short[]{10, 20, 30, 40}, samples);
    }

    @Nested
    @DisplayName("malformed input")
    class Malformed {
        @Test
        @DisplayName("an IMA ADPCM block shorter than its headers")
        void shortImaBlock() {
            var decoder = new AdpcmDecoder(track(Codec.ADPCM_IMA_WAV, 2, null));
            assertThrows(MediaException.Malformed.class, () -> decoder.decode(packet(new byte[7]), block -> {
            }));
        }

        @Test
        @DisplayName("an IMA ADPCM step index above 88")
        void stepIndex() {
            var decoder = new AdpcmDecoder(track(Codec.ADPCM_IMA_WAV, 1, null));
            assertThrows(MediaException.Malformed.class, () -> decoder.decode(packet(new byte[]{0, 0, 89, 0}),
                    block -> {
                    }));
        }

        @Test
        @DisplayName("a Microsoft ADPCM predictor the table lacks")
        void predictor() {
            var decoder = new AdpcmDecoder(track(Codec.ADPCM_MS, 1, null));
            assertThrows(MediaException.Malformed.class, () -> decoder.decode(packet(new byte[]{7, 16, 0, 0, 0, 0, 0}),
                    block -> {
                    }));
        }

        @Test
        @DisplayName("a Microsoft ADPCM configuration shorter than its table")
        void configuration() {
            assertThrows(MediaException.Malformed.class, () -> new AdpcmDecoder(track(Codec.ADPCM_MS, 1,
                    new byte[]{4, 0, 2, 0, 0, 1})));
        }

        @Test
        @DisplayName("an ima4 packet that does not hold whole frames")
        void partialFrame() {
            var decoder = new AdpcmDecoder(track(Codec.ADPCM_IMA_QT, 2, null));
            assertThrows(MediaException.Malformed.class, () -> decoder.decode(packet(new byte[34]), block -> {
            }));
        }
    }

    @Test
    @DisplayName("a lost span is produced exactly")
    void concealment() {
        var stream = TelephonyStreams.demux(resource("ms-acm.wav"));
        var decoder = new AdpcmDecoder(stream.track());
        assertTrue(decoder.supportsConcealment());
        TelephonyStreams.decodeFloats(decoder, stream.packets().subList(0, 4));
        var produced = new int[1];
        assertEquals(333, decoder.conceal(333, (AudioBuffer block) -> produced[0] += block.frames()));
        assertEquals(333, produced[0]);
    }

    @Test
    @DisplayName("rejects tracks of other codecs")
    void otherCodec() {
        assertThrows(IllegalArgumentException.class, () -> new AdpcmDecoder(track(Codec.ADPCM_G726, 1, null)));
    }
}
