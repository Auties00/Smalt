package com.github.auties00.smalt.container.aiff;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.pcm.PcmDecoder;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the AIFF/AIFF-C demuxer with ffmpeg-generated files for every supported compression type
 * (decoded bit-exactly against ffmpeg), hand-built headers for the 80-bit sample rate,
 * left-justified odd sample sizes, streaming sizes and metadata, and mutation robustness.
 */
class AiffDemuxerTest {
    static Stream<Arguments> formats() {
        var arguments = new ArrayList<Arguments>();
        for (var codec : List.of("pcm_s8", "pcm_s16be", "pcm_s16le", "pcm_s24be", "pcm_s32be", "pcm_f32be", "pcm_f64be",
                "pcm_alaw", "pcm_mulaw")) {
            for (var channels : List.of(1, 2, 6)) {
                arguments.add(Arguments.of(codec, channels, 44100));
            }
        }
        arguments.add(Arguments.of("pcm_s16be", 2, 8000));
        arguments.add(Arguments.of("pcm_s24be", 2, 96000));
        return arguments.stream();
    }

    static AudioPipeline.Decoded decode(byte[] data) {
        try (var demuxer = new AiffDemuxer(MediaSource.of(data))) {
            return AudioPipeline.decode(demuxer, PcmDecoder::new);
        }
    }

    @ParameterizedTest(name = "{0} {1}ch {2}Hz")
    @MethodSource("formats")
    @DisplayName("decodes ffmpeg's AIFF output bit-exactly and splits packets like ffprobe")
    void ffmpeg(String codec, int channels, int rate) {
        var file = Fixtures.tone("aiff", 997, rate, channels, 0.31, List.of("-c:a", codec));
        var bytes = Fixtures.read(file);
        var decoded = decode(bytes);
        assertEquals(rate, decoded.sampleRate());
        assertArrayEquals(Oracle.decodeAudio(file), decoded.interleaved());
        try (var demuxer = new AiffDemuxer(MediaSource.of(bytes))) {
            assertEquals(Math.round(0.31 * rate), demuxer.tracks().getFirst().duration());
            var packets = AudioPipeline.packets(demuxer, 0).stream().map(ProbedPacket::of).toList();
            assertEquals(ProbedPacket.probe(file), packets);
        }
    }

    /** Builds AIFF files by hand. */
    static byte[] aiff(boolean aifc, String compression, int channels, long frames, int bits, byte[] rate, byte[] sound,
                       long soundSize, byte[]... extraChunks) {
        var chunks = new ByteArrayOutputStream();
        var comm = ByteBuffer.allocate(aifc ? 24 : 18).putShort((short) channels).putInt((int) frames).putShort((short) bits).put(rate);
        if (aifc) {
            comm.put(compression.getBytes(StandardCharsets.US_ASCII)).put(new byte[]{0, 0});
        }
        chunks.writeBytes(chunk("COMM", comm.array()));
        for (var extra : extraChunks) {
            chunks.writeBytes(extra);
        }
        var ssnd = ByteBuffer.allocate(8 + 8 + sound.length).put("SSND".getBytes(StandardCharsets.US_ASCII))
                .putInt((int) soundSize).putInt(0).putInt(0).put(sound);
        chunks.writeBytes(ssnd.array());
        var body = chunks.toByteArray();
        return ByteBuffer.allocate(12 + body.length).put("FORM".getBytes(StandardCharsets.US_ASCII)).putInt(body.length + 4)
                .put((aifc ? "AIFC" : "AIFF").getBytes(StandardCharsets.US_ASCII)).put(body).array();
    }

    static byte[] chunk(String id, byte[] body) {
        var buffer = ByteBuffer.allocate(8 + body.length + (body.length & 1)).put(id.getBytes(StandardCharsets.US_ASCII))
                .putInt(body.length).put(body);
        return buffer.array();
    }

    /** Encodes a positive integer rate as an 80-bit IEEE extended float. */
    static byte[] extended(double value) {
        var exponent = Math.getExponent(value);
        var mantissa = (long) Math.scalb(value, 62 - exponent) << 1;
        return ByteBuffer.allocate(10).putShort((short) (exponent + 16383)).putLong(mantissa).array();
    }

    @Test
    @DisplayName("reads the sound data of a file source in blocks that never reach the frames past the COMM count")
    void blockReads() {
        var sound = new byte[1_000_000];
        new java.util.SplittableRandom(9).nextBytes(sound);
        var frames = 200_000;
        var bytes = aiff(false, null, 2, frames, 16, extended(44100), sound, 8 + sound.length);
        var soundStart = 12 + 8 + 18 + 16;
        var source = new com.github.auties00.smalt.testkit.container.CountingSource(bytes);
        try (var counted = new AiffDemuxer(source);
             var memory = new AiffDemuxer(MediaSource.of(bytes))) {
            source.reset();
            var expected = AudioPipeline.packets(memory, 0);
            var actual = AudioPipeline.packets(counted, 0);
            assertEquals(expected.size(), actual.size());
            for (var index = 0; index < expected.size(); index++) {
                assertArrayEquals(expected.get(index).data(), actual.get(index).data());
            }
            assertTrue(source.reads <= frames * 4 / (256 * 1024) + 2, source.toString());
            assertEquals(soundStart + frames * 4L, source.furthest);
        }
    }

    @Nested
    @DisplayName("hand-built headers")
    class HandBuilt {
        @ParameterizedTest(name = "{0} Hz")
        @CsvSource({"8000, 8000", "44100, 44100", "48000, 48000", "192000, 192000", "22254.545454, 22255", "11025, 11025", "1, 1"})
        @DisplayName("reads 80-bit extended sample rates, rounding to the nearest hertz")
        void sampleRates(double rate, int expected) {
            var bytes = aiff(false, null, 1, 1, 16, extended(rate), new byte[2], 10);
            try (var demuxer = new AiffDemuxer(MediaSource.of(bytes))) {
                assertEquals(expected, ((AudioTrack) demuxer.tracks().getFirst()).sampleRate());
            }
        }

        @Test
        @DisplayName("decodes 12-bit samples left-justified in two bytes")
        void twelveBit() {
            var sound = ByteBuffer.allocate(4).putShort((short) 0x4000).putShort((short) 0x8000).array();
            var bytes = aiff(false, null, 1, 2, 12, extended(8000), sound, 12);
            try (var demuxer = new AiffDemuxer(MediaSource.of(bytes))) {
                assertEquals(Codec.PCM_S16BE, demuxer.tracks().getFirst().codec());
                assertEquals(12, ((AudioTrack) demuxer.tracks().getFirst()).bitsPerSample());
            }
            assertArrayEquals(new float[]{0.5f, -1f}, decode(bytes).interleaved());
        }

        @Test
        @DisplayName("honors the COMM frame count over trailing sound data and reads streaming files to the end")
        void frameCounts() {
            var sound = new byte[20];
            assertEquals(3, decode(aiff(false, null, 1, 3, 16, extended(8000), sound, 28)).frames());
            assertEquals(10, decode(aiff(true, "NONE", 1, 0, 16, extended(8000), sound, 0)).frames());
            assertEquals(10, decode(aiff(false, null, 1, 999, 16, extended(8000), sound, 28)).frames(), "truncated");
        }

        @Test
        @DisplayName("collects NAME, AUTH and ANNO text chunks")
        void metadata() {
            var bytes = aiff(false, null, 1, 1, 16, extended(8000), new byte[2], 10,
                    chunk("NAME", "Name".getBytes(StandardCharsets.US_ASCII)),
                    chunk("AUTH", "Someone".getBytes(StandardCharsets.US_ASCII)),
                    chunk("ANNO", "odd".getBytes(StandardCharsets.US_ASCII)));
            try (var demuxer = new AiffDemuxer(MediaSource.of(bytes))) {
                assertEquals("Name", demuxer.metadata().get("NAME"));
                assertEquals("Someone", demuxer.metadata().get("AUTH"));
                assertEquals("odd", demuxer.metadata().get("ANNO"));
            }
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"twos, PCM_S16BE", "sowt, PCM_S16LE", "in24, PCM_S24BE", "in32, PCM_S32BE", "fl32, PCM_F32BE",
                "FL64, PCM_F64BE", "ulaw, PCM_MULAW", "ALAW, PCM_ALAW", "'raw ', PCM_U8"})
        @DisplayName("maps AIFF-C compression types to PCM codecs")
        void compression(String type, Codec codec) {
            var bytes = aiff(true, type, 1, 1, 16, extended(8000), new byte[8], 16);
            try (var demuxer = new AiffDemuxer(MediaSource.of(bytes))) {
                assertEquals(codec, demuxer.tracks().getFirst().codec());
            }
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"NONE, false", "twos, false", "sowt, true"})
        @DisplayName("reads 64-bit integer samples in eight bytes and rejects other sizes past 32 bits")
        void sixtyFourBits(String type, boolean littleEndian) {
            var values = new long[]{Long.MIN_VALUE, Long.MAX_VALUE, 0, -1, 0x0123_4567_89AB_CDEFL, 1L << 40};
            var sound = ByteBuffer.allocate(values.length * 8)
                    .order(littleEndian ? java.nio.ByteOrder.LITTLE_ENDIAN : java.nio.ByteOrder.BIG_ENDIAN);
            for (var value : values) {
                sound.putLong(value);
            }
            var bytes = aiff(true, type, 2, 3, 64, extended(48000), sound.array(), 56);
            try (var demuxer = new AiffDemuxer(MediaSource.of(bytes))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                assertEquals(littleEndian ? Codec.PCM_S64LE : Codec.PCM_S64BE, track.codec());
                assertEquals(64, track.bitsPerSample());
                assertEquals(3, track.duration());
            }
            var decoded = decode(bytes).interleaved();
            for (var index = 0; index < values.length; index++) {
                var expected = new java.math.BigDecimal(values[index])
                        .divide(new java.math.BigDecimal(java.math.BigInteger.ONE.shiftLeft(63))).floatValue();
                assertEquals(expected, decoded[index], "sample " + index);
            }
            var forty = aiff(true, "NONE", 1, 1, 40, extended(8000), new byte[5], 13);
            assertThrows(MediaException.Unsupported.class, () -> new AiffDemuxer(MediaSource.of(forty)));
        }

        @Test
        @DisplayName("rejects unsupported and malformed files with the right exception")
        void rejects() {
            var ima = aiff(true, "ima4", 1, 1, 16, extended(8000), new byte[34], 42);
            var unsupported = assertThrows(MediaException.Unsupported.class,
                    () -> new AiffDemuxer(MediaSource.of(ima)));
            assertEquals("aifc compression 'ima4'", unsupported.format());
            var zeroRate = aiff(false, null, 1, 1, 16, new byte[10], new byte[2], 10);
            assertThrows(MediaException.Malformed.class, () -> new AiffDemuxer(MediaSource.of(zeroRate)));
            var noChannels = aiff(false, null, 0, 1, 16, extended(8000), new byte[2], 10);
            assertThrows(MediaException.Malformed.class, () -> new AiffDemuxer(MediaSource.of(noChannels)));
            assertThrows(MediaException.Malformed.class,
                    () -> new AiffDemuxer(MediaSource.of("FORM\0\0\0\4WAVE".getBytes())));
            assertThrows(MediaException.Malformed.class, () -> new AiffDemuxer(MediaSource.of(new byte[0])));
        }
    }

    @Test
    @DisplayName("reads the zero-size streaming AIFF-C that ffmpeg writes to a pipe")
    void pipe() {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var bytes = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-f", "lavfi", "-i",
                "sine=frequency=440:sample_rate=8000:duration=0.25", "-c:a", "pcm_s16le", "-f", "aiff", "-"));
        assertEquals(2000, decode(bytes).frames());
    }

    @Test
    @DisplayName("resists hundreds of random mutations")
    void mutations() {
        var inputs = List.of(
                Fixtures.read(Fixtures.tone("aiff", 440, 8000, 2, 0.2, List.of("-c:a", "pcm_s16be"))),
                Fixtures.read(Fixtures.tone("aiff", 440, 8000, 1, 0.1, List.of("-c:a", "pcm_f32be"))));
        var stats = AudioMutations.assertRobust(inputs, 200, 0xA1FFL, Duration.ofSeconds(5), bytes -> decode(bytes));
        System.out.println("AiffDemuxer mutation outcomes: " + stats);
    }
}
