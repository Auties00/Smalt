package com.github.auties00.smalt.container.wav;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.audio.pcm.PcmDecoder;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.audio.AudioMutations;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;
import com.github.auties00.smalt.testkit.audio.ProbedPacket;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the WAVE demuxer with ffmpeg-generated files for every sample format and layout (decoded
 * bit-exactly against ffmpeg), RF64, RIFX, hand-built extensible and streaming headers, chunk
 * padding, metadata, unsupported formats, and mutation robustness.
 */
class WavDemuxerTest {
    /**
     * MPEG audio in WAV against ffmpeg's float decode: two float decoders of the same normative synthesis, within
     * 90 dB of SNR and the ISO/IEC 11172-4 full accuracy limits.
     */
    private static final Tolerance MPEG_AUDIO = Tolerance.snr(90)
            .and(Tolerance.iso11172(Tolerance.Iso11172Accuracy.FULL));

    static Stream<Arguments> formats() {
        var arguments = new ArrayList<Arguments>();
        var codecs = List.of("pcm_u8", "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_s64le", "pcm_f32le", "pcm_f64le", "pcm_alaw",
                "pcm_mulaw");
        for (var codec : codecs) {
            for (var channels : List.of(1, 2, 6)) {
                arguments.add(Arguments.of(codec, channels, 44100));
            }
        }
        arguments.add(Arguments.of("pcm_s16le", 8, 48000));
        arguments.add(Arguments.of("pcm_s16le", 1, 8000));
        arguments.add(Arguments.of("pcm_s24le", 2, 96000));
        arguments.add(Arguments.of("pcm_f32le", 3, 22050));
        return arguments.stream();
    }

    static AudioPipeline.Decoded decode(byte[] data) {
        try (var demuxer = new WavDemuxer(MediaSource.of(data))) {
            return AudioPipeline.decode(demuxer, PcmDecoder::new);
        }
    }

    @ParameterizedTest(name = "{0} {1}ch {2}Hz")
    @MethodSource("formats")
    @DisplayName("decodes ffmpeg's WAVE output bit-exactly and splits packets like ffprobe")
    void ffmpeg(String codec, int channels, int rate) {
        var file = Fixtures.tone("wav", 997, rate, channels, 0.37, List.of("-c:a", codec));
        var bytes = Fixtures.read(file);
        var decoded = decode(bytes);
        assertEquals(rate, decoded.sampleRate());
        assertEquals(channels, decoded.channels());
        assertArrayEquals(Oracle.decodeAudio(file), decoded.interleaved());
        try (var demuxer = new WavDemuxer(MediaSource.of(bytes))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(Math.round(0.37 * rate), track.duration());
            var packets = AudioPipeline.packets(demuxer, 0).stream().map(ProbedPacket::of).toList();
            assertEquals(ProbedPacket.probe(file), packets);
        }
    }

    @Test
    @DisplayName("maps the extensible channel mask to speakers")
    void channelMask() {
        var file = Fixtures.tone("wav", 440, 48000, 6, 0.1, List.of("-c:a", "pcm_s16le", "-channel_layout", "5.1(side)"));
        try (var demuxer = new WavDemuxer(MediaSource.of(Fixtures.read(file)))) {
            var layout = ((AudioTrack) demuxer.tracks().getFirst()).layout().orElseThrow();
            assertEquals(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY,
                    Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT), layout.speakers());
        }
    }

    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.CsvSource({"libmp3lame, MP3", "mp2, MP2"})
    @DisplayName("hands MPEG audio stored in WAVE to the MPEG audio demuxer and decodes it like ffmpeg")
    void mpegInWave(String encoder, Codec codec) {
        var file = Fixtures.tone("wav", 440, 44100, 2, 0.8, List.of("-c:a", encoder));
        var bytes = Fixtures.read(file);
        try (var demuxer = new WavDemuxer(MediaSource.of(bytes))) {
            assertEquals(codec, demuxer.tracks().getFirst().codec());
            var packets = AudioPipeline.packets(demuxer, 0).stream().map(ProbedPacket::of).toList();
            assertEquals(ProbedPacket.probe(file), packets);
        }
        try (var demuxer = new WavDemuxer(MediaSource.of(bytes))) {
            var decoded = AudioPipeline.decode(demuxer, com.github.auties00.smalt.audio.mp3.Mp3Decoder::new);
            // ffmpeg's default MP2 decoder is fixed-point; its float decoder is the like-for-like reference
            var raw = Oracle.run(Oracle.Tool.FFMPEG, java.time.Duration.ofMinutes(1), List.of("-v", "error",
                    "-c:a", codec == Codec.MP3 ? "mp3float" : "mp2float", "-i", file.toString(), "-f", "f32le", "-"));
            var expected = new float[raw.length / 4];
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(expected);
            MPEG_AUDIO.assertPcm(codec + " in WAV against ffmpeg", expected, decoded.interleaved());
        }
    }

    @Test
    @DisplayName("reads RF64 files written by ffmpeg")
    void rf64() {
        var file = Fixtures.tone("wav", 440, 48000, 2, 0.5, List.of("-c:a", "pcm_s16le", "-rf64", "always"));
        var bytes = Fixtures.read(file);
        assertEquals("RF64", new String(bytes, 0, 4, StandardCharsets.US_ASCII));
        assertArrayEquals(Oracle.decodeAudio(file), decode(bytes).interleaved());
    }

    /** Builds WAVE files by hand. */
    static final class WavBuilder {
        private final ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        private String riff = "RIFF";
        private boolean bigEndian;
        private long riffSize = -1;

        WavBuilder riff(String id, boolean bigEndian) {
            this.riff = id;
            this.bigEndian = bigEndian;
            return this;
        }

        WavBuilder riffSize(long size) {
            this.riffSize = size;
            return this;
        }

        WavBuilder chunk(String id, long declaredSize, byte[] body, boolean pad) {
            chunks.writeBytes(id.getBytes(StandardCharsets.US_ASCII));
            chunks.writeBytes(int32(declaredSize));
            chunks.writeBytes(body);
            if (pad && (body.length & 1) != 0) {
                chunks.write(0);
            }
            return this;
        }

        WavBuilder chunk(String id, byte[] body) {
            return chunk(id, body.length, body, true);
        }

        byte[] int32(long value) {
            return ByteBuffer.allocate(4).order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN).putInt((int) value).array();
        }

        byte[] format(int tag, int channels, int rate, int blockAlign, int bits) {
            return ByteBuffer.allocate(16).order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN)
                    .putShort((short) tag).putShort((short) channels).putInt(rate).putInt(rate * blockAlign)
                    .putShort((short) blockAlign).putShort((short) bits).array();
        }

        byte[] extensible(int subTag, int channels, int rate, int blockAlign, int bits, int validBits, int mask) {
            var buffer = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
                    .putShort((short) 0xFFFE).putShort((short) channels).putInt(rate).putInt(rate * blockAlign)
                    .putShort((short) blockAlign).putShort((short) bits).putShort((short) 22).putShort((short) validBits)
                    .putInt(mask).putShort((short) subTag).putShort((short) 0);
            buffer.put(new byte[]{0x00, 0x00, 0x10, 0x00, (byte) 0x80, 0x00, 0x00, (byte) 0xAA, 0x00, 0x38, (byte) 0x9B, 0x71});
            return buffer.array();
        }

        byte[] build() {
            var body = chunks.toByteArray();
            var output = new ByteArrayOutputStream();
            output.writeBytes(riff.getBytes(StandardCharsets.US_ASCII));
            output.writeBytes(int32(riffSize >= 0 ? riffSize : body.length + 4));
            output.writeBytes("WAVE".getBytes(StandardCharsets.US_ASCII));
            output.writeBytes(body);
            return output.toByteArray();
        }
    }

    static byte[] s16(boolean bigEndian, int... samples) {
        var buffer = ByteBuffer.allocate(samples.length * 2).order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        for (var sample : samples) {
            buffer.putShort((short) sample);
        }
        return buffer.array();
    }

    @Nested
    @DisplayName("hand-built headers")
    class HandBuilt {
        @Test
        @DisplayName("decodes 24-bit samples in 32-bit extensible containers, left-justified")
        void extensible24in32() {
            var builder = new WavBuilder();
            var data = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(0x40000000).putInt(0xC0000000)
                    .putInt(0x12345600).putInt(0).array();
            var bytes = builder.chunk("fmt ", builder.extensible(1, 2, 48000, 8, 32, 24, 0x3)).chunk("data", data).build();
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                assertEquals(Codec.PCM_S32LE, track.codec());
                assertEquals(24, track.bitsPerSample());
                assertEquals(2, track.duration());
            }
            var decoded = decode(bytes).interleaved();
            assertArrayEquals(new float[]{0.5f, -0.5f, 0x123456 / (float) (1 << 23), 0f}, decoded);
        }

        @Test
        @DisplayName("assigns speakers from a sparse mask and unknown speakers beyond it")
        void sparseMask() {
            var builder = new WavBuilder();
            var bytes = builder.chunk("fmt ", builder.extensible(1, 3, 8000, 6, 16, 16, 0x104))
                    .chunk("data", s16(false, 1, 2, 3)).build();
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes))) {
                var layout = ((AudioTrack) demuxer.tracks().getFirst()).layout().orElseThrow();
                assertEquals(List.of(Speaker.FRONT_CENTER, Speaker.BACK_CENTER, Speaker.UNKNOWN), layout.speakers());
            }
        }

        @Test
        @DisplayName("reads big-endian RIFX files")
        void rifx() {
            var builder = new WavBuilder().riff("RIFX", true);
            var bytes = builder.chunk("fmt ", builder.format(1, 1, 8000, 2, 16)).chunk("data", s16(true, 16384, -32768)).build();
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes))) {
                assertEquals(Codec.PCM_S16BE, demuxer.tracks().getFirst().codec());
            }
            assertArrayEquals(new float[]{0.5f, -1f}, decode(bytes).interleaved());
        }

        @Test
        @DisplayName("treats data sizes 0 and 0xFFFFFFFF as streaming headers that run to the end")
        void streaming() {
            for (var size : new long[]{0, 0xFFFFFFFFL}) {
                var builder = new WavBuilder().riffSize(0xFFFFFFFFL);
                var bytes = builder.chunk("fmt ", builder.format(1, 1, 8000, 2, 16))
                        .chunk("data", size, s16(false, 1, 2, 3, 4, 5), false).build();
                assertEquals(5, decode(bytes).frames(), "size " + size);
            }
        }

        @Test
        @DisplayName("clamps a data size larger than the file and drops a trailing partial frame")
        void truncated() {
            var builder = new WavBuilder();
            var data = s16(false, 1, 2, 3, 4, 5, 6, 7);
            var bytes = builder.chunk("fmt ", builder.format(1, 2, 8000, 4, 16)).chunk("data", 1_000_000, data, false).build();
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes))) {
                assertEquals(3, demuxer.tracks().getFirst().duration());
            }
            assertEquals(3, decode(bytes).frames());
        }

        @Test
        @DisplayName("honors the pad byte after odd chunks and tolerates writers that omit it")
        void padding() {
            var info = infoList("INAM", "Odd title");
            var padded = new WavBuilder();
            var bytes = padded.chunk("fmt ", padded.format(1, 1, 8000, 2, 16)).chunk("junk", new byte[3])
                    .chunk("LIST", info).chunk("data", s16(false, 7, 8)).build();
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes))) {
                assertEquals("Odd title", demuxer.metadata().get("INAM"));
                assertEquals(2, demuxer.tracks().getFirst().duration());
            }
            var unpadded = new WavBuilder();
            var sloppy = unpadded.chunk("fmt ", unpadded.format(1, 1, 8000, 2, 16)).chunk("junk", 3, new byte[3], false)
                    .chunk("data", s16(false, 7, 8)).build();
            assertEquals(2, decode(sloppy).frames());
        }

        @Test
        @DisplayName("reads metadata from a LIST chunk after the data")
        void trailingList() {
            var builder = new WavBuilder();
            var bytes = builder.chunk("fmt ", builder.format(1, 1, 8000, 2, 16)).chunk("data", s16(false, 1, 2, 3))
                    .chunk("LIST", infoList("IART", "Artist")).build();
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes))) {
                assertEquals("Artist", demuxer.metadata().get("IART"));
                assertEquals(3, AudioPipeline.packets(demuxer, 0).getFirst().duration());
            }
        }

        @Test
        @DisplayName("reads a hand-built RF64 file whose sizes live in ds64")
        void handRf64() {
            var builder = new WavBuilder().riff("BW64", false).riffSize(0xFFFFFFFFL);
            var ds64 = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN).putLong(100).putLong(6).putLong(3).putInt(0).array();
            var bytes = builder.chunk("ds64", ds64).chunk("fmt ", builder.format(1, 1, 8000, 2, 16))
                    .chunk("data", 0xFFFFFFFFL, s16(false, 1, 2, 3, 4), false).build();
            assertEquals(3, decode(bytes).frames(), "ds64 data size wins over the trailing sample");
        }

        @Test
        @DisplayName("rejects unsupported and malformed files with the right exception")
        void rejects() {
            // DSP Group TrueSpeech, a format tag no reader of Smalt handles
            var trueSpeech = new WavBuilder();
            var trueSpeechBytes = trueSpeech.chunk("fmt ", trueSpeech.format(0x22, 1, 8000, 32, 1))
                    .chunk("data", new byte[256]).build();
            var unsupported = assertThrows(MediaException.Unsupported.class,
                    () -> new WavDemuxer(MediaSource.of(trueSpeechBytes)));
            assertEquals("wav format 0x0022", unsupported.format());
            var s40 = new WavBuilder();
            var s40Bytes = s40.chunk("fmt ", s40.format(1, 1, 8000, 5, 40)).chunk("data", new byte[10]).build();
            assertEquals("pcm_s40", assertThrows(MediaException.Unsupported.class,
                    () -> new WavDemuxer(MediaSource.of(s40Bytes))).format());
            var s64 = new WavBuilder();
            var s64Bytes = s64.chunk("fmt ", s64.format(1, 1, 8000, 8, 64)).chunk("data", new byte[16]).build();
            try (var demuxer = new WavDemuxer(MediaSource.of(s64Bytes))) {
                assertEquals(Codec.PCM_S64LE, demuxer.tracks().getFirst().codec());
            }
            var noFormat = new WavBuilder().chunk("data", new byte[16]).build();
            assertThrows(MediaException.Malformed.class, () -> new WavDemuxer(MediaSource.of(noFormat)));
            var zeroChannels = new WavBuilder();
            var zeroBytes = zeroChannels.chunk("fmt ", zeroChannels.format(1, 0, 8000, 2, 16)).chunk("data", new byte[4]).build();
            assertThrows(MediaException.Malformed.class, () -> new WavDemuxer(MediaSource.of(zeroBytes)));
            var manyChannels = new WavBuilder();
            var manyBytes = manyChannels.chunk("fmt ", manyChannels.format(1, 500, 8000, 1000, 16)).chunk("data", new byte[4]).build();
            assertThrows(MediaException.LimitExceeded.class, () -> new WavDemuxer(MediaSource.of(manyBytes)));
            assertThrows(MediaException.Malformed.class,
                    () -> new WavDemuxer(MediaSource.of("RIFF\0\0\0\0AVI ".getBytes())));
            assertThrows(MediaException.Malformed.class, () -> new WavDemuxer(MediaSource.of(new byte[3])));
        }
    }

    @Test
    @DisplayName("reads the sample data of a file source in blocks that never reach the chunks after it")
    void blockReads() {
        var samples = new byte[2_000_002];
        new java.util.SplittableRandom(5).nextBytes(samples);
        var builder = new WavBuilder();
        var bytes = builder.chunk("fmt ", builder.format(1, 2, 44100, 4, 16)).chunk("data", samples)
                .chunk("LIST", infoList("IART", "Artist")).chunk("junk", new byte[300_000]).build();
        var dataEnd = 12 + 8 + 16 + 8 + samples.length;
        var source = new com.github.auties00.smalt.testkit.container.CountingSource(bytes);
        try (var counted = new WavDemuxer(source);
             var memory = new WavDemuxer(MediaSource.of(bytes))) {
            assertEquals("Artist", counted.metadata().get("IART"));
            source.reset();
            var expected = AudioPipeline.packets(memory, 0);
            var actual = AudioPipeline.packets(counted, 0);
            assertEquals(expected.size(), actual.size());
            for (var index = 0; index < expected.size(); index++) {
                assertArrayEquals(expected.get(index).data(), actual.get(index).data());
                assertEquals(expected.get(index).pts(), actual.get(index).pts());
            }
            assertTrue(source.reads <= samples.length / (256 * 1024) + 2, source.toString());
            assertTrue(source.furthest <= dataEnd, "read up to " + source.furthest + " past the data ending at " + dataEnd);
        }
    }

    static byte[] infoList(String key, String value) {
        var text = (value + "\0").getBytes(StandardCharsets.UTF_8);
        var buffer = ByteBuffer.allocate(4 + 8 + text.length + (text.length & 1)).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("INFO".getBytes(StandardCharsets.US_ASCII)).put(key.getBytes(StandardCharsets.US_ASCII)).putInt(text.length).put(text);
        return buffer.array();
    }

    @Test
    @DisplayName("resists hundreds of random mutations")
    void mutations() {
        var inputs = List.of(
                Fixtures.read(Fixtures.tone("wav", 440, 8000, 2, 0.2, List.of("-c:a", "pcm_s16le"))),
                Fixtures.read(Fixtures.tone("wav", 440, 8000, 6, 0.1, List.of("-c:a", "pcm_s24le"))),
                Fixtures.read(Fixtures.tone("wav", 440, 8000, 1, 0.1, List.of("-c:a", "pcm_f64le", "-rf64", "always"))));
        var stats = AudioMutations.assertRobust(inputs, 150, 0x7A7L, Duration.ofSeconds(5), bytes -> decode(bytes));
        System.out.println("WavDemuxer mutation outcomes: " + stats);
    }

    static AudioPipeline.Decoded decodeAny(byte[] data, java.util.function.Function<MediaSource, com.github.auties00.smalt.container.Demuxer> factory) {
        try (var demuxer = factory.apply(MediaSource.of(data))) {
            return AudioPipeline.decode(demuxer, com.github.auties00.smalt.audio.AudioDecoders::create);
        }
    }

    static byte[] spdifWav(Path raw, int rate) {
        var bursts = Fixtures.read(Fixtures.ffmpeg("spdif", List.of("-i", raw.toString()), List.of("-c", "copy", "-f", "spdif")));
        var builder = new WavBuilder();
        return builder.chunk("fmt ", builder.format(1, 2, rate, 4, 16)).chunk("data", bursts).build();
    }

    @Nested
    @DisplayName("coded payloads")
    class Coded {
        @ParameterizedTest(name = "{0}")
        @org.junit.jupiter.params.provider.CsvSource({"ac3, 48000", "eac3, 192000"})
        @DisplayName("decodes IEC 61937 bursts to the samples of the raw stream")
        void iec61937(String codec, int rate) {
            var raw = Fixtures.tone(codec, 440, 48000, 2, 0.9, List.of("-c:a", codec));
            var expected = decodeAny(Fixtures.read(raw), com.github.auties00.smalt.container.ac3.Ac3Demuxer::new);
            var actual = decodeAny(spdifWav(raw, rate), WavDemuxer::new);
            assertEquals(expected.sampleRate(), actual.sampleRate());
            assertEquals(expected.channels(), actual.channels());
            assertTrue(expected.interleaved().length > 40_000);
            assertArrayEquals(expected.interleaved(), actual.interleaved());
        }

        @ParameterizedTest(name = "{0} at {1} Hz")
        @org.junit.jupiter.params.provider.CsvSource({"mp2, mp2, 48000", "mp2, mp2, 24000", "mp3, libmp3lame, 44100",
                "mp3, libmp3lame, 22050", "aac, aac, 48000"})
        @DisplayName("decodes IEC 61937 bursts of MPEG audio and AAC to the samples of the raw stream")
        void iec61937Mpeg(String extension, String encoder, int rate) {
            var raw = Fixtures.tone(extension, 440, rate, 2, 0.9, List.of("-c:a", encoder));
            var expected = extension.equals("aac")
                    ? decodeAny(Fixtures.read(raw), com.github.auties00.smalt.container.adts.AdtsDemuxer::new)
                    : decodeAny(Fixtures.read(raw), com.github.auties00.smalt.container.mpeg.MpegAudioDemuxer::new);
            var actual = decodeAny(spdifWav(raw, 48000), WavDemuxer::new);
            assertEquals(expected.sampleRate(), actual.sampleRate());
            assertEquals(expected.channels(), actual.channels());
            assertTrue(actual.interleaved().length > 20_000);
            assertArrayEquals(expected.interleaved(), actual.interleaved());
        }

        @Test
        @DisplayName("decodes ffmpeg's FLAC in WAVE bit-exactly like ffmpeg")
        void flac() {
            var file = Fixtures.tone("wav", 997, 44100, 2, 0.7, List.of("-c:a", "flac", "-sample_fmt", "s16"));
            var decoded = decodeAny(Fixtures.read(file), WavDemuxer::new);
            assertArrayEquals(Oracle.decodeAudio(file), decoded.interleaved());
        }

        @Test
        @DisplayName("decodes ffmpeg's AC-3 and AMR-NB in WAVE like the raw streams")
        void rawStreams() {
            var ac3 = decodeAny(Fixtures.read(Fixtures.tone("wav", 440, 48000, 2, 0.5, List.of("-c:a", "ac3"))), WavDemuxer::new);
            var ac3Raw = decodeAny(Fixtures.read(Fixtures.tone("ac3", 440, 48000, 2, 0.5, List.of("-c:a", "ac3"))),
                    com.github.auties00.smalt.container.ac3.Ac3Demuxer::new);
            assertArrayEquals(ac3Raw.interleaved(), ac3.interleaved());
            var amr = decodeAny(Fixtures.read(Fixtures.tone("wav", 440, 8000, 1, 0.5, List.of("-c:a", "libopencore_amrnb"))),
                    WavDemuxer::new);
            var amrRaw = decodeAny(Fixtures.read(Fixtures.tone("amr", 440, 8000, 1, 0.5, List.of("-c:a", "libopencore_amrnb"))),
                    com.github.auties00.smalt.container.amr.AmrDemuxer::new);
            assertTrue(amr.interleaved().length >= 3840);
            assertArrayEquals(amrRaw.interleaved(), amr.interleaved());
        }

        @Test
        @DisplayName("decodes AAC in LOAS framing like the ADTS stream of the same encode")
        void loas() {
            var latm = Fixtures.read(Fixtures.tone("latm", 440, 44100, 2, 0.8, List.of("-c:a", "aac", "-b:a", "96k")));
            var adts = Fixtures.read(Fixtures.tone("aac", 440, 44100, 2, 0.8, List.of("-c:a", "aac", "-b:a", "96k")));
            var builder = new WavBuilder();
            var wav = builder.chunk("fmt ", builder.format(0x1602, 2, 44100, 1, 0)).chunk("data", latm).build();
            var expected = decodeAny(adts, com.github.auties00.smalt.container.adts.AdtsDemuxer::new);
            var actual = decodeAny(wav, WavDemuxer::new);
            assertTrue(expected.interleaved().length > 60_000);
            assertArrayEquals(expected.interleaved(), actual.interleaved());
        }

        /** Returns a WAVE file of one format chunk with extra bytes and one data chunk. */
        static byte[] codedWav(int tag, int channels, int rate, int blockAlign, int bits, byte[] extra, byte[] data) {
            var fmt = ByteBuffer.allocate(18 + extra.length).order(ByteOrder.LITTLE_ENDIAN).putShort((short) tag)
                    .putShort((short) channels).putInt(rate).putInt(rate * blockAlign).putShort((short) blockAlign)
                    .putShort((short) bits).putShort((short) extra.length).put(extra).array();
            return new WavBuilder().chunk("fmt ", fmt).chunk("data", data).build();
        }

        @Test
        @DisplayName("seeks implicit HE-AAC v2 as raw blocks, ADTS and IEC 61937 bursts in the time base of the core rate")
        void heAacSeek() {
            // implicit signaling: a 22.05 kHz mono Low Complexity configuration that decodes to 44.1 kHz stereo
            var mp4 = com.github.auties00.smalt.testkit.Conformance.fetch("mpeg4audio-al_sbr_i_44_1.mp4",
                    "https://standards.iso.org/ittf/PubliclyAvailableStandards/ISO_IEC_14496-26_2010_Bitstreams/DVD1/"
                            + "mpeg4audio-conformance/compressedMp4/al_sbr_i_44_1.mp4",
                    "93f9a8e33ca65f529139ed4827bf37214fe28a17aa786f3a23ba6cfd571cf36c");
            byte[] config;
            List<com.github.auties00.smalt.track.Packet> blocks;
            try (var demuxer = new com.github.auties00.smalt.container.mp4.Mp4Demuxer(MediaSource.of(Fixtures.read(mp4)))) {
                config = demuxer.tracks().getFirst().codecConfig().orElseThrow();
                blocks = AudioPipeline.packets(demuxer, demuxer.tracks().getFirst().id());
            }
            var index = (config[0] & 0x07) << 1 | (config[1] & 0xFF) >> 7;
            var channels = (config[1] >> 3) & 0x0F;
            assertEquals(2, (config[0] & 0xFF) >> 3);
            assertEquals(7, index);
            var raw = new ByteArrayOutputStream();
            var adts = new ByteArrayOutputStream();
            var bursts = new ByteArrayOutputStream();
            for (var block : blocks) {
                raw.writeBytes(block.data());
                var length = block.size() + 7;
                var frame = new byte[length];
                frame[0] = (byte) 0xFF;
                frame[1] = (byte) 0xF1;
                frame[2] = (byte) (1 << 6 | index << 2 | channels >> 2);
                frame[3] = (byte) ((channels & 3) << 6 | length >> 11);
                frame[4] = (byte) (length >> 3);
                frame[5] = (byte) ((length & 7) << 5 | 0x1F);
                frame[6] = (byte) 0xFC;
                System.arraycopy(block.data(), 0, frame, 7, block.size());
                adts.writeBytes(frame);
                var burst = ByteBuffer.allocate(1024 * 4).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 0xF872)
                        .putShort((short) 0x4E1F).putShort((short) 7).putShort((short) (length * 8));
                for (var at = 0; at < length; at += 2) {
                    burst.put(at + 1 < length ? frame[at + 1] : 0).put(frame[at]);
                }
                bursts.writeBytes(burst.array());
            }
            var files = List.of(codedWav(0x00FF, channels, 22050, 1, 0, config, raw.toByteArray()),
                    codedWav(0x1600, channels, 22050, 1, 0, new byte[0], adts.toByteArray()),
                    codedWav(1, 2, 22050, 4, 16, new byte[0], bursts.toByteArray()));
            for (var file : files) {
                try (var demuxer = new WavDemuxer(MediaSource.of(file))) {
                    var track = (AudioTrack) demuxer.tracks().getFirst();
                    assertEquals(Codec.AAC, track.codec());
                    assertEquals(44100, track.sampleRate());
                    assertEquals(com.github.auties00.smalt.track.Rational.timeBaseOf(22050), track.timeBase());
                    assertEquals(18_575_964, demuxer.durationMicros(), 50_000);
                    for (var micros : new long[]{0, 3_000_000, 9_250_000, 17_000_000}) {
                        demuxer.seek(micros);
                        var packet = demuxer.read();
                        var target = micros * 22050 / 1_000_000;
                        assertTrue(packet.pts() <= target && target < packet.pts() + packet.duration(),
                                "seek to " + micros + " us returned the block at " + packet.pts());
                    }
                }
            }
        }

        @ParameterizedTest(name = "{0} Hz, {1} channels")
        @org.junit.jupiter.params.provider.CsvSource({"44100, 2, 128k", "22050, 1, 32k", "48000, 6, 320k"})
        @DisplayName("decodes ffmpeg's raw AAC in WAVE like the ADTS stream of the same encode")
        void rawAac(int rate, int channels, String bitrate) {
            var options = List.of("-c:a", "aac", "-b:a", bitrate);
            var wav = Fixtures.read(Fixtures.tone("wav", 440, rate, channels, 0.8, options));
            var adts = Fixtures.read(Fixtures.tone("aac", 440, rate, channels, 0.8, options));
            var expected = decodeAny(adts, com.github.auties00.smalt.container.adts.AdtsDemuxer::new);
            var actual = decodeAny(wav, WavDemuxer::new);
            assertEquals(expected.sampleRate(), actual.sampleRate());
            assertEquals(expected.channels(), actual.channels());
            assertTrue(expected.interleaved().length > 15_000);
            assertArrayEquals(expected.interleaved(), actual.interleaved());
        }

        /** Packs fields of {@code {value, bits}} most significant bit first, padded with zeros to whole bytes. */
        static byte[] pack(int[]... fields) {
            var out = new ByteArrayOutputStream();
            var accumulator = 0L;
            var count = 0;
            for (var field : fields) {
                accumulator = accumulator << field[1] | (field[0] & ((1L << field[1]) - 1));
                count += field[1];
                while (count >= 8) {
                    out.write((int) (accumulator >>> (count - 8)));
                    count -= 8;
                }
            }
            if (count > 0) {
                out.write((int) (accumulator << (8 - count)));
            }
            return out.toByteArray();
        }

        @Test
        @DisplayName("decodes an ADIF stream through the program configuration of its header")
        void adif() {
            var options = List.of("-c:a", "aac", "-b:a", "128k");
            var adts = Fixtures.read(Fixtures.tone("aac", 440, 44100, 2, 0.8, options));
            var wav = Fixtures.read(Fixtures.tone("wav", 440, 44100, 2, 0.8, options));
            // ADIF, no copyright, variable rate, one program: tag 0, Low Complexity, 44.1 kHz, one front channel pair
            var header = pack(new int[]{0x41444946, 32}, new int[]{0, 3}, new int[]{1, 1}, new int[]{128000, 23},
                    new int[]{0, 4}, new int[]{0, 4}, new int[]{1, 2}, new int[]{4, 4}, new int[]{1, 4}, new int[]{0, 17},
                    new int[]{0, 3}, new int[]{1, 1}, new int[]{0, 4}, new int[]{0, 2}, new int[]{0, 8});
            var data = java.nio.ByteBuffer.wrap(wav);
            var payload = new ByteArrayOutputStream();
            payload.writeBytes(header);
            var position = 12;
            while (true) {
                var size = data.order(ByteOrder.LITTLE_ENDIAN).getInt(position + 4);
                if (data.getInt(position) == 0x61746164) {
                    payload.write(wav, position + 8, size);
                    break;
                }
                position += 8 + size + (size & 1);
            }
            var builder = new WavBuilder();
            var file = builder.chunk("fmt ", builder.format(0x00FF, 2, 44100, 1, 0)).chunk("data", payload.toByteArray())
                    .build();
            var expected = decodeAny(adts, com.github.auties00.smalt.container.adts.AdtsDemuxer::new);
            var actual = decodeAny(file, WavDemuxer::new);
            assertEquals(2, actual.channels());
            assertTrue(expected.interleaved().length > 60_000);
            assertArrayEquals(expected.interleaved(), actual.interleaved());
        }

        @Test
        @DisplayName("decodes self-delimited Opus like the Ogg stream its packets come from")
        void opus() {
            var ogg = Fixtures.read(Fixtures.tone("opus", 440, 48000, 2, 0.8, List.of("-c:a", "libopus", "-b:a", "64k")));
            var data = new ByteArrayOutputStream();
            byte[] head;
            try (var demuxer = new com.github.auties00.smalt.container.ogg.OggDemuxer(MediaSource.of(ogg))) {
                head = demuxer.tracks().getFirst().codecConfig().orElseThrow();
                for (var packet : AudioPipeline.packets(demuxer, demuxer.tracks().getFirst().id())) {
                    data.writeBytes(selfDelimited(packet.data()));
                }
            }
            var builder = new WavBuilder();
            var fmt = ByteBuffer.allocate(18 + head.length).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 0x704F)
                    .putShort((short) 2).putInt(48000).putInt(8000).putShort((short) 1).putShort((short) 0)
                    .putShort((short) head.length).put(head).array();
            var wav = builder.chunk("fmt ", fmt).chunk("data", data.toByteArray()).build();
            var expected = decodeAny(ogg, com.github.auties00.smalt.container.ogg.OggDemuxer::new);
            var actual = decodeAny(wav, WavDemuxer::new);
            var keep = Math.min(expected.interleaved().length, actual.interleaved().length);
            assertTrue(keep > 70_000);
            assertArrayEquals(Arrays.copyOf(expected.interleaved(), keep), Arrays.copyOf(actual.interleaved(), keep));
        }

        /** Converts a code 0 to 3 Opus packet of up to 251-byte lengths into the self-delimiting framing. */
        static byte[] selfDelimited(byte[] packet) {
            var out = new ByteArrayOutputStream();
            var code = packet[0] & 3;
            if (code == 0) {
                out.write(packet[0]);
                writeLength(out, packet.length - 1);
                out.write(packet, 1, packet.length - 1);
                return out.toByteArray();
            }
            throw com.github.auties00.smalt.testkit.Skip.now("input:opus-code", "libopus emitted a code " + code + " packet");
        }

        static void writeLength(ByteArrayOutputStream out, int length) {
            if (length < 252) {
                out.write(length);
            } else {
                var first = 252 + (length & 3);
                out.write(first);
                out.write((length - first) / 4);
            }
        }
    }

    @Test
    @DisplayName("maps ffmpeg's 7.1 channel mask to known speakers")
    void surround71() {
        Path file = Fixtures.tone("wav", 440, 48000, 8, 0.1, List.of("-c:a", "pcm_s16le"));
        try (var demuxer = new WavDemuxer(MediaSource.of(Fixtures.read(file)))) {
            var layout = ((AudioTrack) demuxer.tracks().getFirst()).layout().orElseThrow();
            assertEquals(8, layout.channels());
            assertFalse(layout.speakers().contains(Speaker.UNKNOWN));
        }
    }
}
