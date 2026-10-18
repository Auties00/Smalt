package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.internal.header.MpegAudioHeader;
import com.github.auties00.smalt.container.mpeg.MpegAudioDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Quality;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Differential tests of {@link Mp3Decoder} against ffmpeg's float MPEG audio decoders (the
 * oracle): every fixture must decode to the same number of samples as ffmpeg after the reader
 * trimming rule, with an SNR of at least 90 dB.
 */
class Mp3DecoderTest {
    static final double MIN_SNR = 90;


    static AudioPipeline.Decoded decode(Path file) {
        try (var demuxer = new MpegAudioDemuxer(MediaSource.of(Fixtures.read(file)))) {
            return AudioPipeline.decode(demuxer, Mp3Decoder::new);
        }
    }

    static double assertMatchesFfmpeg(Path file) {
        return assertMatchesFfmpeg(file, MIN_SNR);
    }

    static double assertMatchesFfmpeg(Path file, double minimum) {
        var ours = decode(file);
        var trimmed = ours.trimmed();
        // ffmpeg's default Layer I/II decoder is fixed point (97.7 dB PSNR against ISO l2-test32, ours
        // 119.7 dB); its float decoders are the like-for-like oracle for every layer
        var reference = floatReference(file, ours.track().codec() == Codec.MP3 ? "mp3float" : "mp2float");
        assertEquals(reference.length, trimmed.length, "sample count differs from ffmpeg");
        var snr = Quality.snr(reference, trimmed);
        System.out.printf("SNR vs ffmpeg %s %s: %.2f dB over %d samples%n", ours.track().codec(), file.getFileName(), snr, trimmed.length);
        assertTrue(snr >= minimum, "SNR " + snr + " dB below " + minimum);
        return snr;
    }

    static float[] floatReference(Path file, String decoder) {
        var raw = Oracle.run(Oracle.Tool.FFMPEG, java.time.Duration.ofMinutes(2), List.of("-v", "error", "-c:a", decoder,
                "-i", file.toString(), "-map", "0:a:0", "-f", "f32le", "-"));
        var samples = new float[raw.length / 4];
        java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples);
        return samples;
    }

    static Stream<Arguments> constantBitrate() {
        return Stream.of(
                Arguments.of(44100, 2, 32), Arguments.of(44100, 2, 64), Arguments.of(44100, 2, 96),
                Arguments.of(44100, 2, 128), Arguments.of(44100, 2, 160), Arguments.of(44100, 2, 192),
                Arguments.of(44100, 2, 224), Arguments.of(44100, 2, 256), Arguments.of(44100, 2, 320),
                Arguments.of(48000, 2, 320), Arguments.of(48000, 1, 64), Arguments.of(48000, 2, 40),
                Arguments.of(32000, 2, 128), Arguments.of(32000, 1, 32), Arguments.of(44100, 1, 128),
                Arguments.of(24000, 2, 96), Arguments.of(24000, 1, 8), Arguments.of(22050, 2, 64),
                Arguments.of(22050, 2, 160), Arguments.of(16000, 1, 16), Arguments.of(16000, 2, 32),
                Arguments.of(12000, 2, 32), Arguments.of(12000, 1, 16), Arguments.of(11025, 1, 16),
                Arguments.of(11025, 2, 48), Arguments.of(8000, 1, 8), Arguments.of(8000, 2, 16),
                Arguments.of(8000, 1, 64));
    }

    @ParameterizedTest(name = "{0} Hz, {1} ch, {2} kbit/s")
    @MethodSource("constantBitrate")
    @DisplayName("constant bitrate LAME streams match ffmpeg at every sample rate")
    void cbr(int rate, int channels, int kbps) {
        assertMatchesFfmpeg(Mp3Fixtures.lame(rate, channels, 1.7, "-b:a", kbps + "k"));
    }

    static Stream<Arguments> variableBitrate() {
        return Stream.of(Arguments.of(44100, 2, 0), Arguments.of(44100, 2, 2), Arguments.of(44100, 2, 4),
                Arguments.of(44100, 2, 6), Arguments.of(44100, 2, 9), Arguments.of(48000, 1, 3),
                Arguments.of(22050, 2, 5), Arguments.of(16000, 1, 7), Arguments.of(8000, 2, 4),
                Arguments.of(11025, 1, 9));
    }

    @ParameterizedTest(name = "{0} Hz, {1} ch, V{2}")
    @MethodSource("variableBitrate")
    @DisplayName("variable bitrate LAME streams match ffmpeg")
    void vbr(int rate, int channels, int quality) {
        assertMatchesFfmpeg(Mp3Fixtures.lame(rate, channels, 1.9, "-q:a", Integer.toString(quality)));
    }

    @Test
    @DisplayName("an average bitrate stream matches ffmpeg")
    void abr() {
        assertMatchesFfmpeg(Mp3Fixtures.lame(44100, 2, 2.1, "-abr", "1", "-b:a", "112k"));
    }

    @Test
    @DisplayName("a stream coded with plain (not joint) stereo matches ffmpeg")
    void plainStereo() {
        assertMatchesFfmpeg(Mp3Fixtures.lame(44100, 2, 1.3, "-b:a", "128k", "-joint_stereo", "0"));
    }

    @ParameterizedTest(name = "{0} Hz")
    @org.junit.jupiter.params.provider.ValueSource(ints = {44100, 22050, 8000})
    @DisplayName("a stream without a Xing frame matches ffmpeg untrimmed")
    void withoutXing(int rate) {
        var file = Mp3Fixtures.lame(rate, 2, 1.4, "-b:a", "64k", "-write_xing", "0");
        var decoded = decode(file);
        assertEquals(0, decoded.track().skipSamples());
        assertEquals(decoded.frames(), decoded.track().duration(), "the frame scan must give the exact duration");
        assertMatchesFfmpeg(file);
    }

    @Test
    @DisplayName("a very short stream of a few frames matches ffmpeg")
    void shortStream() {
        assertMatchesFfmpeg(Mp3Fixtures.lame(44100, 2, 0.05, "-b:a", "128k"));
    }

    static Stream<Arguments> layer2() {
        return Stream.of(Arguments.of(48000, 2, 192), Arguments.of(44100, 2, 384), Arguments.of(32000, 1, 64),
                Arguments.of(44100, 1, 32), Arguments.of(24000, 2, 96), Arguments.of(22050, 1, 48),
                Arguments.of(16000, 2, 160), Arguments.of(16000, 1, 8));
    }

    @ParameterizedTest(name = "{0} Hz, {1} ch, {2} kbit/s")
    @MethodSource("layer2")
    @DisplayName("Layer II streams from ffmpeg's mp2 encoder match ffmpeg")
    void mp2(int rate, int channels, int kbps) {
        var file = Mp3Fixtures.encode("mp2", rate, channels, 1.6, List.of("-c:a", "mp2", "-b:a", kbps + "k"));
        var decoded = decode(file);
        assertEquals(Codec.MP2, decoded.track().codec());
        assertMatchesFfmpeg(file);
    }

    @Test
    @DisplayName("dual-channel Layer III frames decode exactly like the same frames marked stereo")
    void dualChannel() {
        var stereo = Fixtures.read(Mp3Fixtures.lame(44100, 2, 1.0, "-b:a", "160k", "-joint_stereo", "0", "-write_xing", "0",
                "-id3v2_version", "0"));
        var dual = stereo.clone();
        var marked = 0;
        for (var offset = 0; offset + 4 <= dual.length; ) {
            var header = MpegAudioHeader.read(dual, offset);
            if (!MpegAudioHeader.isValid(header)) {
                break;
            }
            assertEquals(0, MpegAudioHeader.mode(header), "fixture must use plain stereo");
            dual[offset + 3] = (byte) ((dual[offset + 3] & 0x3F) | 0x80);
            marked++;
            offset += MpegAudioHeader.frameBytes(header, 0) + MpegAudioHeader.padding(header);
        }
        assertTrue(marked > 10);
        var expected = decodeBytes(stereo);
        var actual = decodeBytes(dual);
        assertArrayEquals(expected, actual);
    }

    static float[] decodeBytes(byte[] data) {
        try (var demuxer = new MpegAudioDemuxer(MediaSource.of(data))) {
            return AudioPipeline.decode(demuxer, Mp3Decoder::new).interleaved();
        }
    }

    @Nested
    @DisplayName("packet handling")
    class Packets {
        private AudioTrack track(Codec codec) {
            return AudioTrack.builder().codec(codec).timeBase(Rational.timeBaseOf(44100)).build();
        }

        @Test
        @DisplayName("several frames in one packet decode like one frame per packet")
        void multipleFramesPerPacket() {
            var file = Mp3Fixtures.lame(44100, 2, 0.5, "-b:a", "128k", "-write_xing", "0", "-id3v2_version", "0");
            var data = Fixtures.read(file);
            var perFrame = decodeBytes(data);
            var output = new AudioPipeline.FloatList();
            try (var decoder = new Mp3Decoder(track(Codec.MP3))) {
                decoder.decode(new Packet(0, data, 0, 0, 0, true), buffer -> append(buffer, output));
                decoder.flush(buffer -> append(buffer, output));
            }
            assertArrayEquals(perFrame, output.toArray());
        }

        @Test
        @DisplayName("reports the first frame's format and rejects a packet without a header")
        void formatAndGarbage() {
            var data = Fixtures.read(Mp3Fixtures.lame(22050, 1, 0.2, "-b:a", "32k", "-write_xing", "0", "-id3v2_version", "0"));
            try (var decoder = new Mp3Decoder(track(Codec.MP3))) {
                assertEquals(0, decoder.sampleRate());
                decoder.decode(new Packet(0, data, 0, 0, 0, true), buffer -> {
                });
                assertEquals(22050, decoder.sampleRate());
                assertEquals(ChannelLayout.MONO, decoder.layout());
                assertThrows(MediaException.Malformed.class,
                        () -> decoder.decode(new Packet(0, new byte[]{1, 2, 3, 4, 5}, 0, 0, 0, true), buffer -> {
                        }));
                assertThrows(MediaException.Malformed.class,
                        () -> decoder.decode(new Packet(0, new byte[2], 0, 0, 0, true), buffer -> {
                        }));
            }
        }

        @Test
        @DisplayName("an exception of the output consumer reaches the caller unchanged")
        void consumerFailure() {
            var data = Fixtures.read(Mp3Fixtures.lame(44100, 2, 0.2, "-b:a", "128k", "-write_xing", "0", "-id3v2_version", "0"));
            var failure = new IllegalStateException("consumer");
            try (var decoder = new Mp3Decoder(track(Codec.MP3))) {
                var thrown = assertThrows(IllegalStateException.class,
                        () -> decoder.decode(new Packet(0, data, 0, 0, 0, true), buffer -> {
                            throw failure;
                        }));
                assertSame(failure, thrown);
            }
        }

        @Test
        @DisplayName("rejects tracks of other codecs")
        void wrongCodec() {
            assertThrows(IllegalArgumentException.class, () -> new Mp3Decoder(track(Codec.AAC)));
        }

        @Test
        @DisplayName("a frame whose reservoir precedes the stream decodes to a full frame of silence")
        void missingReservoir() {
            var data = Fixtures.read(Mp3Fixtures.lame(44100, 2, 1.0, "-b:a", "96k", "-write_xing", "0", "-id3v2_version", "0"));
            var packets = new ArrayList<Packet>();
            try (var demuxer = new MpegAudioDemuxer(MediaSource.of(data))) {
                packets.addAll(AudioPipeline.packets(demuxer, 0));
            }
            var found = false;
            for (var start = 1; start < packets.size() && !found; start++) {
                var packet = packets.get(start).data();
                var mainDataBegin = ((packet[4] & 0xFF) << 1) | ((packet[5] & 0xFF) >> 7);
                if (mainDataBegin == 0) {
                    continue;
                }
                found = true;
                var blocks = new ArrayList<float[]>();
                try (var decoder = new Mp3Decoder(track(Codec.MP3))) {
                    decoder.decode(packets.get(start), buffer -> blocks.add(buffer.plane(0).clone()));
                    assertEquals(1, blocks.size());
                    for (var i = 0; i < 1152; i++) {
                        assertEquals(0.0f, blocks.getFirst()[i]);
                    }
                }
            }
            assertTrue(found, "no frame uses the bit reservoir");
        }

        private static void append(AudioBuffer buffer, AudioPipeline.FloatList output) {
            for (var i = 0; i < buffer.frames(); i++) {
                for (var c = 0; c < buffer.channels(); c++) {
                    output.add(buffer.plane(c)[i]);
                }
            }
        }
    }

    @Nested
    @DisplayName("codec-independent options")
    class IndependentOptions {
        // MPEG-1 Layer III, 128 kb/s, 44.1 kHz, stereo, no CRC: 417 bytes, all-zero side information
        private static Packet stereoFrame() {
            var frame = new byte[417];
            frame[0] = (byte) 0xFF;
            frame[1] = (byte) 0xFB;
            frame[2] = (byte) 0x90;
            return new Packet(0, frame, 0, 0, 0, true);
        }

        private static AudioTrack track() {
            return AudioTrack.builder().codec(Codec.MP3).timeBase(Rational.timeBaseOf(44100)).build();
        }

        @Test
        @DisplayName("apply their limits to every frame and ignore the preferred sample rate")
        void limits() {
            try (var decoder = new Mp3Decoder(track(), AudioDecoderOptions.builder().preferredSampleRate(8000).build())) {
                var frames = new int[1];
                decoder.decode(stereoFrame(), buffer -> frames[0] += buffer.frames());
                assertEquals(1152, frames[0]);
                assertEquals(2, decoder.layout().channels());
                assertEquals(44100, decoder.sampleRate());
            }
            var mono = AudioDecoderOptions.builder().limits(MediaLimits.builder().maxChannels(1).build()).build();
            try (var decoder = new Mp3Decoder(track(), mono)) {
                assertThrows(MediaException.LimitExceeded.class, () -> decoder.decode(stereoFrame(), buffer -> {
                }));
            }
            assertThrows(NullPointerException.class, () -> new Mp3Decoder(track(), (AudioDecoderOptions) null));
        }
    }
}
