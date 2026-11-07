package com.github.auties00.smalt.container.wav;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the ADPCM and telephony formats of {@link WavMuxer} and {@link WavDemuxer}: IMA and Microsoft ADPCM, GSM
 * 06.10, G.722 and G.726 files written by ffmpeg (and the committed libgsm sample) are read packet for packet, written
 * back with the {@code fmt }, {@code fact} and {@code data} chunks ffmpeg wrote (the average byte rate checked against
 * the Microsoft definitions, since ffmpeg's is not), and decoded by ffmpeg (black box) to the same samples; shortened
 * last blocks decode to a prefix of the original samples, since blocks decode independently. The demuxer's handling of
 * truncated data, {@code fact} counts, seeking and malformed formats runs over files the test assembles itself.
 */
@DisplayName("WavMuxer ADPCM and telephony formats")
class WavMuxerAdpcmTest {
    private static final Path SAMPLES = Path.of("src", "test", "resources", "samples", "wav");

    /** A WAVE file of an ADPCM codec and the format tag it declares. */
    record Source(String name, Supplier<byte[]> file, Codec codec, int tag) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Supplier<byte[]> ffmpegWav(int rate, int channels, String... options) {
        return () -> {
            var arguments = new ArrayList<>(List.of("-ac", Integer.toString(channels)));
            arguments.addAll(List.of(options));
            arguments.addAll(List.of("-fflags", "+bitexact", "-flags:a", "+bitexact"));
            return Fixtures.read(Fixtures.ffmpeg("wav", List.of("-f", "lavfi", "-i",
                    "anoisesrc=color=pink:amplitude=0.4:sample_rate=" + rate + ":seed=11:duration=0.7"), arguments));
        };
    }

    static Supplier<byte[]> sample(String name) {
        return () -> {
            try {
                return Files.readAllBytes(SAMPLES.resolve(name));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        };
    }

    static Stream<Source> sources() {
        return Stream.of(
                new Source("IMA mono 8 kHz", ffmpegWav(8000, 1, "-c:a", "adpcm_ima_wav"), Codec.ADPCM_IMA_WAV, 0x0011),
                new Source("IMA stereo 44.1 kHz, 2048-byte blocks", ffmpegWav(44100, 2, "-c:a", "adpcm_ima_wav",
                        "-block_size", "2048"), Codec.ADPCM_IMA_WAV, 0x0011),
                new Source("IMA stereo 22.05 kHz sample", sample("ima-adpcm.wav"), Codec.ADPCM_IMA_WAV, 0x0011),
                new Source("Microsoft ADPCM mono", ffmpegWav(8000, 1, "-c:a", "adpcm_ms"), Codec.ADPCM_MS, 0x0002),
                new Source("Microsoft ADPCM stereo, 512-byte blocks", ffmpegWav(22050, 2, "-c:a", "adpcm_ms",
                        "-block_size", "512"), Codec.ADPCM_MS, 0x0002),
                new Source("GSM 06.10 libgsm sample", sample("gsm.wav"), Codec.GSM_MS, 0x0031),
                new Source("G.722", ffmpegWav(16000, 1, "-c:a", "g722"), Codec.ADPCM_G722, 0x028F),
                new Source("G.726 16 kbit/s", ffmpegWav(8000, 1, "-c:a", "g726", "-b:a", "16k"), Codec.ADPCM_G726, 0x0045),
                new Source("G.726 24 kbit/s", ffmpegWav(8000, 1, "-c:a", "g726", "-b:a", "24k"), Codec.ADPCM_G726, 0x0045),
                new Source("G.726 32 kbit/s", ffmpegWav(8000, 1, "-c:a", "g726", "-b:a", "32k"), Codec.ADPCM_G726, 0x0045),
                new Source("G.726 40 kbit/s", ffmpegWav(8000, 1, "-c:a", "g726", "-b:a", "40k"), Codec.ADPCM_G726, 0x0045));
    }

    /** A track and its packets, as a demuxer returns them. */
    record Read(AudioTrack track, List<Packet> packets) {
    }

    static Read demux(byte[] file) {
        try (var demuxer = new WavDemuxer(MediaSource.of(file))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            return new Read(track, AudioPipeline.packets(demuxer, track.id()));
        }
    }

    static byte[] mux(AudioTrack track, List<Packet> packets) {
        return MuxSupport.toMemory(List.of(track), packets, WavMuxer::new);
    }

    static byte[] chunk(byte[] wav, String id) {
        return WavMuxerCodedTest.chunks(wav).get(id);
    }

    static ByteBuffer le(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    static long fact(byte[] wav) {
        return le(chunk(wav, "fact")).getInt(0) & 0xFFFFFFFFL;
    }

    static byte[] concatenate(List<Packet> packets) {
        var out = new ByteArrayOutputStream();
        packets.forEach(packet -> out.writeBytes(packet.data()));
        return out.toByteArray();
    }

    /** Decodes a file with ffmpeg to interleaved signed 16-bit little-endian samples. */
    static byte[] ffmpegDecode(String name, byte[] wav) {
        var file = MuxSupport.save(name, wav);
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-i", file.toString(), "-f",
                "s16le", "-"));
    }

    /** Returns the average byte rate the Microsoft structures define for a format, from its other fields. */
    static long byteRate(byte[] format) {
        var fields = le(format);
        var tag = fields.getShort(0) & 0xFFFF;
        var rate = fields.getInt(4);
        var blockAlign = fields.getShort(12) & 0xFFFF;
        return switch (tag) {
            // nSamplesPerSec * nBlockAlign / wSamplesPerBlock
            case 0x0011, 0x0002, 0x0031 -> (long) rate * blockAlign / (fields.getShort(18) & 0xFFFF);
            case 0x028F -> rate / 2;
            default -> (long) rate * (fields.getShort(14) & 0xFFFF) / 8;
        };
    }

    /** Returns the samples per channel a block of IMA or Microsoft ADPCM codes, computed in the test. */
    static long blockSamples(Codec codec, int bytes, int channels) {
        return codec == Codec.ADPCM_IMA_WAV ? 1 + (bytes - 4L * channels) * 2 / channels
                : 2 + (bytes - 7L * channels) * 2 / channels;
    }

    /** Assembles a little-endian WAVE file with a plain {@code WAVEFORMATEX}, an optional {@code fact} and data. */
    static byte[] wave(int tag, int channels, int rate, long byteRate, int blockAlign, int bits, byte[] extra, long fact,
                       byte[] data, boolean streaming) {
        var format = ByteBuffer.allocate(18 + extra.length).order(ByteOrder.LITTLE_ENDIAN).putShort((short) tag)
                .putShort((short) channels).putInt(rate).putInt((int) byteRate).putShort((short) blockAlign)
                .putShort((short) bits).putShort((short) extra.length).put(extra).array();
        var body = new ByteArrayOutputStream();
        body.writeBytes("WAVE".getBytes());
        writeChunk(body, "fmt ", format, format.length);
        if (fact >= 0) {
            writeChunk(body, "fact", le(new byte[4]).putInt((int) fact).array(), 4);
        }
        writeChunk(body, "data", data, streaming ? 0xFFFFFFFFL : data.length);
        var out = new ByteArrayOutputStream();
        out.writeBytes("RIFF".getBytes());
        out.writeBytes(le(new byte[4]).putInt(streaming ? -1 : body.size()).array());
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String id, byte[] body, long size) {
        out.writeBytes(id.getBytes());
        out.writeBytes(le(new byte[4]).putInt((int) size).array());
        out.writeBytes(body);
        if ((body.length & 1) != 0) {
            out.write(0);
        }
    }

    static AudioTrack track(Codec codec, int rate, int channels, int bits) {
        return AudioTrack.builder().id(1).codec(codec).timeBase(Rational.timeBaseOf(rate)).sampleRate(rate)
                .layout(ChannelLayout.defaultFor(channels)).bitsPerSample(bits).build();
    }

    static Packet packet(byte[] data, long pts, long duration) {
        return new Packet(1, data, pts, pts, duration, true);
    }

    static byte[] random(long seed, int length) {
        var bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    /** Returns IMA ADPCM blocks with valid headers (sample, step index 0 to 88, zero byte) and random codes. */
    static byte[] imaBlock(long seed, int bytes, int channels) {
        var block = random(seed, bytes);
        for (var channel = 0; channel < channels; channel++) {
            block[channel * 4 + 2] = (byte) Math.floorMod(block[channel * 4 + 2], 89);
            block[channel * 4 + 3] = 0;
        }
        return block;
    }

    @Nested
    @DisplayName("files written by ffmpeg")
    class FfmpegFiles {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.container.wav.WavMuxerAdpcmTest#sources")
        @DisplayName("reads the blocks or codes, writes the same chunks back and decodes in ffmpeg to the same samples")
        void roundTrip(Source source) {
            var original = source.file().get();
            var format = chunk(original, "fmt ");
            var fields = le(format);
            var data = chunk(original, "data");
            assertEquals(source.tag(), fields.getShort(0) & 0xFFFF, "the format ffmpeg wrote");
            var channels = fields.getShort(2);
            var blockAlign = fields.getShort(12) & 0xFFFF;

            var input = demux(original);
            var track = input.track();
            assertEquals(source.codec(), track.codec());
            assertEquals(fields.getInt(4), track.sampleRate());
            assertEquals(channels, track.layout().orElseThrow().channels());
            assertEquals(fact(original), track.duration(), "duration from the fact chunk");
            var expectedBits = switch (source.codec()) {
                case ADPCM_IMA_WAV, ADPCM_MS -> 4;
                case ADPCM_G726 -> fields.getShort(14);
                default -> 0;
            };
            assertEquals(expectedBits, track.bitsPerSample(), "bits per sample");
            if (source.codec() == Codec.ADPCM_MS) {
                assertArrayEquals(Arrays.copyOfRange(format, 18, format.length), track.codecConfig().orElseThrow());
            } else {
                assertTrue(track.codecConfig().isEmpty(), "no configuration");
            }
            assertArrayEquals(data, concatenate(input.packets()), "the packets hold the data chunk");
            var next = 0L;
            for (var index = 0; index < input.packets().size(); index++) {
                var packet = input.packets().get(index);
                assertEquals(next, packet.pts(), "pts of packet " + index);
                if (source.codec() == Codec.ADPCM_IMA_WAV || source.codec() == Codec.ADPCM_MS) {
                    assertEquals(blockAlign, packet.size(), "one block per packet");
                    var samples = blockSamples(source.codec(), blockAlign, channels);
                    assertEquals(Math.min(samples, fact(original) - next), packet.duration(), "duration of block " + index);
                    next += samples;
                } else {
                    next += packet.duration();
                }
            }
            assertEquals(fact(original), input.packets().stream().mapToLong(Packet::duration).sum());

            var wav = mux(track, input.packets());
            var written = chunk(wav, "fmt ");
            assertArrayEquals(Arrays.copyOf(format, 8), Arrays.copyOf(written, 8), "tag, channels and rate");
            assertArrayEquals(Arrays.copyOfRange(format, 12, format.length), Arrays.copyOfRange(written, 12,
                    written.length), "block alignment, bits per sample, cbSize and the extra bytes");
            assertEquals(byteRate(written), le(written).getInt(8) & 0xFFFFFFFFL, "average byte rate");
            assertArrayEquals(data, chunk(wav, "data"));
            assertEquals(fact(original), fact(wav));

            var output = demux(wav);
            assertEquals(track, output.track().toBuilder().id(track.id()).build());
            assertEquals(input.packets().size(), output.packets().size());
            for (var index = 0; index < input.packets().size(); index++) {
                var expected = input.packets().get(index);
                var actual = output.packets().get(index);
                assertArrayEquals(expected.data(), actual.data(), "packet " + index);
                assertEquals(expected.pts(), actual.pts(), "pts of packet " + index);
                assertEquals(expected.duration(), actual.duration(), "duration of packet " + index);
            }

            var name = "adpcm-" + source.name().replaceAll("[^A-Za-z0-9]+", "-");
            var reference = ffmpegDecode(name + "-original.wav", original);
            assertTrue(reference.length >= fact(original) * channels * 2, "ffmpeg decodes the original");
            assertArrayEquals(reference, ffmpegDecode(name + ".wav", wav), "ffmpeg's decode of the written file");
            MuxSupport.assertFfmpegClean(name + "-clean.wav", wav);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.container.wav.WavMuxerAdpcmTest#sources")
        @DisplayName("writes a non-seekable sink with the final format, which WavDemuxer and ffmpeg read")
        void streaming(Source source) {
            var original = source.file().get();
            var input = demux(original);
            var seekable = mux(input.track(), input.packets());
            var wav = MuxSupport.toStream(List.of(input.track()), input.packets(), WavMuxer::new);
            assertArrayEquals(chunk(seekable, "fmt "), chunk(wav, "fmt "), "the format a streaming sink gets");
            assertEquals(0, fact(wav));
            var output = demux(wav);
            assertArrayEquals(concatenate(input.packets()), concatenate(output.packets()));
            assertEquals(output.packets().stream().mapToLong(Packet::duration).sum(), output.track().duration(),
                    "without a fact count, the samples the data codes");
            var reference = ffmpegDecode("adpcm-stream-reference.wav", seekable);
            var decoded = ffmpegDecode("adpcm-stream.wav", wav);
            assertArrayEquals(reference, Arrays.copyOf(decoded, reference.length), "the samples of the stream");
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Codec.class, names = {"ADPCM_IMA_WAV", "ADPCM_MS"})
        @DisplayName("stores a shorter last block, which ffmpeg decodes to a prefix of the original samples")
        void shortLastBlock(Codec codec) {
            var source = ffmpegWav(22050, 2, "-c:a", codec == Codec.ADPCM_MS ? "adpcm_ms" : "adpcm_ima_wav",
                    "-block_size", "1024").get();
            var input = demux(source);
            var packets = new ArrayList<>(input.packets());
            var last = packets.removeLast();
            // the header and 25 groups of eight samples (IMA), or the header and 200 code bytes, each one sample of
            // both channels (Microsoft)
            var size = codec == Codec.ADPCM_IMA_WAV ? 8 + 25 * 8 : 14 + 200;
            var shortened = Arrays.copyOf(last.data(), size);
            var shortSamples = blockSamples(codec, size, 2);
            packets.add(new Packet(last.trackId(), shortened, last.pts(), last.dts(), shortSamples, true));
            var wav = mux(input.track(), packets);
            var spb = blockSamples(codec, 1024, 2);
            assertEquals(1024, le(chunk(wav, "fmt ")).getShort(12), "the block alignment of the full blocks");
            assertEquals((packets.size() - 1) * spb + shortSamples, fact(wav));
            var output = demux(wav);
            assertEquals(packets.size(), output.packets().size());
            assertArrayEquals(shortened, output.packets().getLast().data());
            assertEquals(shortSamples, output.packets().getLast().duration());
            var whole = ffmpegDecode("adpcm-short-reference.wav", mux(input.track(), input.packets()));
            var decoded = ffmpegDecode("adpcm-short.wav", wav);
            var expected = ((packets.size() - 1) * spb + shortSamples) * 2 * 2;
            assertEquals(expected, decoded.length, "the samples of the blocks");
            assertArrayEquals(Arrays.copyOf(whole, (int) expected), decoded, "blocks decode independently");
        }
    }

    @Nested
    @DisplayName("writing")
    class Writing {
        @Test
        @DisplayName("supports the ADPCM codecs natively and none of them in IEC 61937 bursts")
        void supports() {
            for (var codec : List.of(Codec.ADPCM_IMA_WAV, Codec.ADPCM_MS, Codec.GSM_MS, Codec.ADPCM_G722,
                    Codec.ADPCM_G726)) {
                assertTrue(WavMuxer.supports(codec), codec.name());
                assertFalse(WavMuxer.supports(codec, WavMuxerOptions.forIec61937()), codec.name());
            }
        }

        @Test
        @DisplayName("writes GSM 06.10 blocks of any content, which ffmpeg decodes")
        void gsmBlocks() {
            var packets = new ArrayList<Packet>();
            for (var index = 0; index < 20; index++) {
                packets.add(packet(random(index, 65 * (1 + index % 3)), -1, Packet.UNKNOWN_DURATION));
            }
            var wav = mux(track(Codec.GSM_MS, 8000, 1, 0), packets);
            var fields = le(chunk(wav, "fmt "));
            assertEquals(0x0031, fields.getShort(0));
            assertEquals(1625, fields.getInt(8), "average byte rate");
            assertEquals(65, fields.getShort(12));
            assertEquals(2, fields.getShort(16), "cbSize");
            assertEquals(320, fields.getShort(18), "wSamplesPerBlock");
            var blocks = packets.stream().mapToInt(Packet::size).sum() / 65;
            assertEquals(blocks * 320L, fact(wav));
            assertEquals(blocks * 320L * 2, ffmpegDecode("adpcm-gsm-blocks.wav", wav).length);
            assertArrayEquals(concatenate(packets), concatenate(demux(wav).packets()));
        }

        @Test
        @DisplayName("writes the coefficient table of a Microsoft ADPCM configuration")
        void msCoefficients() {
            // wSamplesPerBlock (wrong on purpose: the muxer writes the one of its blocks), wNumCoef, eight pairs
            var config = le(new byte[4 + 32]).putShort((short) 1).putShort((short) 8);
            for (var value : new int[]{256, 0, 512, -256, 0, 0, 192, 64, 240, 0, 460, -208, 392, -232, 300, -50}) {
                config.putShort((short) value);
            }
            var bytes = config.array();
            var track = track(Codec.ADPCM_MS, 8000, 1, 4).toBuilder().codecConfig(bytes).build();
            var block = random(7, 256);
            block[0] = 7;
            var wav = mux(track, List.of(packet(block, 0, Packet.UNKNOWN_DURATION)));
            var format = chunk(wav, "fmt ");
            assertEquals(36, le(format).getShort(16), "cbSize");
            assertEquals(2 + (256 - 7) * 2, le(format).getShort(18), "wSamplesPerBlock of the block");
            assertArrayEquals(Arrays.copyOfRange(bytes, 2, bytes.length), Arrays.copyOfRange(format, 20, format.length));
            var read = demux(wav).track().codecConfig().orElseThrow();
            assertArrayEquals(Arrays.copyOfRange(format, 18, format.length), read);
            var standard = mux(track(Codec.ADPCM_MS, 8000, 1, 4), List.of(packet(block, 0, Packet.UNKNOWN_DURATION)));
            assertArrayEquals(Arrays.copyOfRange(chunk(Fixtures.read(Fixtures.ffmpeg("wav", List.of("-f", "lavfi", "-i",
                    "sine=sample_rate=8000:duration=0.1"), List.of("-c:a", "adpcm_ms", "-block_size", "256"))), "fmt "), 20,
                    50), Arrays.copyOfRange(chunk(standard, "fmt "), 20, 50), "the seven standard pairs, as ffmpeg writes them");
        }

        @Test
        @DisplayName("switches to RF64 with the ADPCM sample count in ds64")
        void rf64() {
            var packets = new ArrayList<Packet>();
            for (var index = 0; index < 8; index++) {
                packets.add(packet(imaBlock(index, 256, 1), index * 505L, 505));
            }
            var sink = MediaSink.memory();
            MuxSupport.mux(List.of(track(Codec.ADPCM_IMA_WAV, 8000, 1, 4)), packets,
                    new WavMuxer(sink, WavMuxerOptions.defaults(), 1000));
            var wav = sink.toByteArray();
            assertEquals("RF64", new String(wav, 0, 4));
            assertEquals(8 * 505L, le(wav).getLong(36), "ds64 sample count");
            assertEquals(8, demux(wav).packets().size());
        }

        @Test
        @DisplayName("counts the samples of the packets, cut to their known durations")
        void durations() {
            var packets = List.of(packet(imaBlock(1, 36, 1), 0, Packet.UNKNOWN_DURATION),
                    packet(imaBlock(2, 36, 1), 65, 40));
            assertEquals(65 + 40, fact(mux(track(Codec.ADPCM_IMA_WAV, 8000, 1, 4), packets)));
            // durations in another time base are rescaled to the sampling rate
            var micros = AudioTrack.builder().id(1).codec(Codec.ADPCM_G722).timeBase(Rational.MICROSECONDS)
                    .sampleRate(16000).layout(ChannelLayout.MONO).build();
            assertEquals(1000 + 500, fact(mux(micros, List.of(packet(new byte[500], 0, Packet.UNKNOWN_DURATION),
                    packet(new byte[500], 62_500, 31_250)))));
            // in milliseconds, a 505-sample block (63.125 ms) lasts 63 or 64 ms, which cuts none of its samples,
            // while a last block of 20 ms codes 160
            var millis = track(Codec.ADPCM_IMA_WAV, 8000, 1, 4).toBuilder().timeBase(new Rational(1, 1000)).build();
            var blocks = new ArrayList<Packet>();
            for (var index = 0; index < 10; index++) {
                var start = Math.round(index * 63.125);
                blocks.add(packet(imaBlock(10 + index, 256, 1), start, Math.round((index + 1) * 63.125) - start));
            }
            blocks.add(packet(imaBlock(20, 256, 1), 631, 20));
            var wav = mux(millis, blocks);
            assertEquals(10 * 505 + 160, fact(wav));
            var read = demux(wav);
            assertEquals(10 * 505 + 160, read.track().duration());
            assertEquals(160, read.packets().getLast().duration());
        }

        @Test
        @DisplayName("writes a header a reader accepts when no packet arrives")
        void empty() {
            for (var codec : List.of(Codec.ADPCM_IMA_WAV, Codec.ADPCM_MS)) {
                var wav = mux(track(codec, 8000, 2, 4), List.of());
                var read = demux(wav);
                assertEquals(codec, read.track().codec());
                assertEquals(512, le(chunk(wav, "fmt ")).getShort(12));
                assertTrue(read.packets().isEmpty());
                assertEquals(0, read.track().duration());
            }
            // 256 bytes per channel would overflow the 16-bit block alignment, so the blocks are their headers
            for (var codec : List.of(Codec.ADPCM_IMA_WAV, Codec.ADPCM_MS)) {
                var fields = le(chunk(mux(track(codec, 8000, 300, 4), List.of()), "fmt "));
                assertEquals(codec == Codec.ADPCM_IMA_WAV ? 4 * 300 : 7 * 300, fields.getShort(12), codec.name());
                assertEquals(codec == Codec.ADPCM_IMA_WAV ? 1 : 2, fields.getShort(18), "wSamplesPerBlock");
            }
        }

        @Test
        @DisplayName("rejects tracks the formats cannot describe")
        void rejectsTracks() {
            assertThrows(IllegalArgumentException.class, () -> new WavMuxer(MediaSink.memory())
                    .addTrack(track(Codec.ADPCM_G722, 16000, 2, 0)), "G.722 is mono");
            assertThrows(IllegalArgumentException.class, () -> new WavMuxer(MediaSink.memory())
                    .addTrack(track(Codec.GSM_MS, 8000, 2, 0)), "GSM 06.10 is mono");
            for (var bits : new int[]{0, 1, 6}) {
                assertThrows(IllegalArgumentException.class, () -> new WavMuxer(MediaSink.memory())
                        .addTrack(track(Codec.ADPCM_G726, 8000, 1, bits)), "G.726 of " + bits + " bits");
            }
            for (var codec : List.of(Codec.ADPCM_IMA_WAV, Codec.ADPCM_MS)) {
                for (var bits : new int[]{3, 8, 16}) {
                    assertThrows(IllegalArgumentException.class, () -> new WavMuxer(MediaSink.memory())
                            .addTrack(track(codec, 8000, 1, bits)), codec + " of " + bits + " bits");
                }
            }
            // the block headers alone (4 or 7 bytes per channel) exceed the 16-bit block alignment
            assertThrows(IllegalArgumentException.class, () -> new WavMuxer(MediaSink.memory())
                    .addTrack(track(Codec.ADPCM_IMA_WAV, 8000, 16384, 4)));
            assertThrows(IllegalArgumentException.class, () -> new WavMuxer(MediaSink.memory())
                    .addTrack(track(Codec.ADPCM_MS, 8000, 9363, 4)));
            new WavMuxer(MediaSink.memory()).addTrack(track(Codec.ADPCM_MS, 8000, 9362, 4));
            for (var config : List.of(new byte[]{0, 1}, new byte[]{0, 1, 0, 0}, new byte[]{0, 1, 2, 0, 1, 2, 3, 4})) {
                var track = track(Codec.ADPCM_MS, 8000, 1, 4).toBuilder().codecConfig(config).build();
                assertThrows(IllegalArgumentException.class, () -> new WavMuxer(MediaSink.memory()).addTrack(track),
                        "a coefficient table shorter than declared");
            }
        }

        @Test
        @DisplayName("rejects packets that are not blocks or whole codes, leaving the file as it was")
        void rejectsPackets() {
            assertRejected(track(Codec.ADPCM_IMA_WAV, 8000, 2, 4), List.of(imaBlock(1, 72, 2)), imaBlock(2, 70, 2));
            assertRejected(track(Codec.ADPCM_IMA_WAV, 8000, 2, 4), List.of(imaBlock(1, 72, 2)), random(2, 6));
            assertRejected(track(Codec.ADPCM_IMA_WAV, 8000, 1, 4), List.of(imaBlock(1, 36, 1)), imaBlock(2, 68, 1));
            assertRejected(track(Codec.ADPCM_IMA_WAV, 8000, 1, 4), List.of(imaBlock(1, 68, 1), imaBlock(2, 36, 1)),
                    imaBlock(3, 36, 1));
            assertRejected(track(Codec.ADPCM_IMA_WAV, 8000, 1, 4), List.of(), new byte[0]);
            // IMA blocks code at most 65535 samples per block, as wSamplesPerBlock holds
            assertRejected(track(Codec.ADPCM_IMA_WAV, 8000, 1, 4), List.of(), imaBlock(4, 32772, 1));
            assertRejected(track(Codec.ADPCM_MS, 8000, 3, 4), List.of(), random(1, 21 + 4));
            assertRejected(track(Codec.ADPCM_MS, 8000, 1, 4), List.of(), random(1, 6));
            assertRejected(track(Codec.GSM_MS, 8000, 1, 0), List.of(random(1, 65)), random(2, 64));
            assertRejected(track(Codec.ADPCM_G726, 8000, 1, 3), List.of(random(1, 4)), random(2, 3));
            assertRejected(track(Codec.ADPCM_G726, 8000, 1, 5), List.of(random(1, 10)), random(2, 0));
        }

        private void assertRejected(AudioTrack track, List<byte[]> accepted, byte[] rejected) {
            var sink = MediaSink.memory();
            try (var muxer = new WavMuxer(sink)) {
                muxer.addTrack(track);
                for (var data : accepted) {
                    muxer.writePacket(packet(data, Packet.NO_TIMESTAMP, Packet.UNKNOWN_DURATION));
                }
                assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(packet(rejected,
                        Packet.NO_TIMESTAMP, Packet.UNKNOWN_DURATION)), track.codec() + " packet of " + rejected.length);
                muxer.finish();
            }
            var expected = mux(track, accepted.stream().map(data -> packet(data, Packet.NO_TIMESTAMP,
                    Packet.UNKNOWN_DURATION)).toList());
            assertArrayEquals(expected, sink.toByteArray(), "a rejected packet changes nothing");
        }
    }

    @Nested
    @DisplayName("reading")
    class Reading {
        @Test
        @DisplayName("ends the stream at the fact count, cutting the last packet")
        void factCount() {
            var data = new byte[3 * 36];
            for (var block = 0; block < 3; block++) {
                System.arraycopy(imaBlock(block, 36, 1), 0, data, block * 36, 36);
            }
            var cut = demux(wave(0x11, 1, 8000, 4000, 36, 4, new byte[]{65, 0}, 100, data, false));
            assertEquals(100, cut.track().duration());
            assertEquals(List.of(65L, 35L), cut.packets().stream().map(Packet::duration).toList(),
                    "the third block lies past the fact count");
            var longer = demux(wave(0x11, 1, 8000, 4000, 36, 4, new byte[]{65, 0}, 1000, data, false));
            assertEquals(195, longer.track().duration(), "a fact count past the data is ignored");
            assertEquals(3, longer.packets().size());
            var none = demux(wave(0x11, 1, 8000, 4000, 36, 4, new byte[]{65, 0}, -1, data, false));
            assertEquals(195, none.track().duration());
        }

        @Test
        @DisplayName("keeps the decodable part of a truncated last block")
        void truncatedBlock() {
            // two 72-byte stereo blocks, then 8 header bytes, a group of each channel and 5 more bytes
            var data = new byte[72 * 2 + 8 + 8 + 5];
            System.arraycopy(imaBlock(1, 72, 2), 0, data, 0, 72);
            System.arraycopy(imaBlock(2, 72, 2), 0, data, 72, 72);
            System.arraycopy(imaBlock(3, 21, 2), 0, data, 144, 21);
            var read = demux(wave(0x11, 2, 8000, 8000, 72, 4, new byte[]{65, 0}, -1, data, false));
            assertEquals(3, read.packets().size());
            assertArrayEquals(Arrays.copyOfRange(data, 144, 160), read.packets().getLast().data());
            assertEquals(9, read.packets().getLast().duration(), "the first sample and one group of eight");
            assertEquals(65 + 65 + 9, read.track().duration());
            // a truncated block shorter than its header codes nothing
            var headerOnly = demux(wave(0x11, 2, 8000, 8000, 72, 4, new byte[]{65, 0}, -1,
                    Arrays.copyOf(data, 72 + 7), false));
            assertEquals(1, headerOnly.packets().size());
            // Microsoft ADPCM: 7 header bytes per channel, then nibbles of whole samples of the three channels, so
            // 12 of the 13 code bytes (eight samples per channel)
            var ms = random(4, 21 + 13);
            var msRead = demux(wave(0x02, 3, 8000, 8000, 63, 4, new byte[0], -1, ms, false));
            assertEquals(1, msRead.packets().size());
            assertEquals(21 + 12, msRead.packets().getFirst().size());
            assertEquals(2 + 8, msRead.packets().getFirst().duration());
        }

        @Test
        @DisplayName("ends a G.726 stream with the codes after its last whole group, and seeks into them")
        void g726Tail() {
            // 3-bit codes: ten groups of eight codes in 30 bytes, then two bytes holding five codes
            var data = random(9, 32);
            var file = wave(0x45, 1, 8000, 3000, 3, 3, new byte[0], -1, data, false);
            var read = demux(file);
            assertEquals(3, read.track().bitsPerSample());
            assertEquals(List.of(80L, 5L), read.packets().stream().map(Packet::duration).toList());
            assertEquals(List.of(0L, 80L), read.packets().stream().map(Packet::pts).toList());
            assertArrayEquals(data, concatenate(read.packets()));
            assertEquals(85, read.track().duration());
            try (var demuxer = new WavDemuxer(MediaSource.of(file))) {
                demuxer.seek(82 * 125L);
                assertEquals(80, demuxer.read().pts(), "the target lies in the codes after the last group");
                demuxer.seek(40 * 125L);
                assertEquals(0, demuxer.read().pts());
                demuxer.seek(85 * 125L);
                assertNull(demuxer.read(), "a seek to the end ends the stream");
            }
            var written = mux(read.track(), read.packets());
            assertArrayEquals(data, chunk(written, "data"));
            assertEquals(3, le(chunk(written, "fmt ")).getShort(12), "the block alignment of eight 3-bit codes");
            assertEquals(85, fact(written));
            // without its code size, G.726 states it through its bit rate
            assertEquals(3, demux(wave(0x45, 1, 8000, 3000, 1, 0, new byte[0], -1, data, false)).track().bitsPerSample());
        }

        @Test
        @DisplayName("seeks to the block holding the target")
        void seekBlocks() {
            var data = new byte[5 * 36];
            for (var block = 0; block < 5; block++) {
                System.arraycopy(imaBlock(block, 36, 1), 0, data, block * 36, 36);
            }
            var file = wave(0x11, 1, 8000, 4000, 36, 4, new byte[]{65, 0}, -1, data, false);
            try (var demuxer = new WavDemuxer(MediaSource.of(file))) {
                assertTrue(demuxer.isSeekable());
                demuxer.seek(140 * 125L);
                var packet = demuxer.read();
                assertEquals(130, packet.pts());
                assertArrayEquals(Arrays.copyOfRange(data, 72, 108), packet.data());
                demuxer.seek(-5);
                assertEquals(0, demuxer.read().pts());
                demuxer.seek(10_000_000);
                assertNull(demuxer.read());
            }
        }

        @Test
        @DisplayName("reads a code stream whose data size is unknown to the end of the file")
        void streamingData() {
            var data = random(5, 3000);
            var read = demux(wave(0x028F, 1, 16000, 8000, 1, 4, new byte[0], 0, data, true));
            assertEquals(Codec.ADPCM_G722, read.track().codec());
            assertEquals(6000, read.track().duration(), "the samples up to the end of the file");
            assertArrayEquals(data, concatenate(read.packets()));
            assertEquals(512, read.packets().getFirst().size(), "1024 samples, the largest power of two in 100 ms");
            assertEquals(6000, read.packets().stream().mapToLong(Packet::duration).sum());
        }

        @Test
        @DisplayName("reads and writes back IMA blocks of only their headers, one sample each")
        void headerOnlyBlocks() {
            var packets = List.of(packet(imaBlock(1, 8, 2), 0, 1), packet(imaBlock(2, 8, 2), 1, 1));
            var wav = mux(track(Codec.ADPCM_IMA_WAV, 8000, 2, 4), packets);
            assertEquals(8, le(chunk(wav, "fmt ")).getShort(12));
            assertEquals(1, le(chunk(wav, "fmt ")).getShort(18), "wSamplesPerBlock");
            var read = demux(wav);
            assertEquals(2, read.track().duration());
            assertEquals(List.of(1L, 1L), read.packets().stream().map(Packet::duration).toList());
            assertArrayEquals(concatenate(packets), concatenate(read.packets()));
        }

        @Test
        @DisplayName("keeps a Microsoft ADPCM coefficient table without the bytes after it, and no incomplete one")
        void msConfig() {
            var table = le(new byte[4 + 28 + 3]).putShort((short) 500).putShort((short) 7);
            for (var index = 0; index < 14; index++) {
                table.putShort((short) (index * 17));
            }
            var extra = table.array();
            var block = random(3, 256);
            block[0] = 1;
            var trailing = demux(wave(0x02, 1, 8000, 4096, 256, 4, extra, -1, block, false));
            assertArrayEquals(Arrays.copyOf(extra, 32), trailing.track().codecConfig().orElseThrow(),
                    "the bytes after the table are not part of the configuration");
            var written = mux(trailing.track(), trailing.packets());
            assertArrayEquals(Arrays.copyOfRange(extra, 2, 32), Arrays.copyOfRange(chunk(written, "fmt "), 20, 50));
            assertArrayEquals(trailing.track().codecConfig().orElseThrow(),
                    demux(written).track().codecConfig().orElseThrow());
            for (var incomplete : List.of(Arrays.copyOf(extra, 31), new byte[]{(byte) 244, 1, 0, 0}, new byte[]{1, 2})) {
                var read = demux(wave(0x02, 1, 8000, 4096, 256, 4, incomplete, -1, block, false));
                assertTrue(read.track().codecConfig().isEmpty(), "an incomplete table of " + incomplete.length);
                assertArrayEquals(block, chunk(mux(read.track(), read.packets()), "data"), "and it writes back");
            }
        }

        @Test
        @DisplayName("takes the ds64 sample count of an RF64 file whose fact count refers to it")
        void rf64SampleCount() {
            var packets = new ArrayList<Packet>();
            for (var index = 0; index < 8; index++) {
                packets.add(packet(imaBlock(index, 256, 1), index * 505L, 505));
            }
            var sink = MediaSink.memory();
            MuxSupport.mux(List.of(track(Codec.ADPCM_IMA_WAV, 8000, 1, 4)), packets,
                    new WavMuxer(sink, WavMuxerOptions.defaults(), 1000));
            var wav = sink.toByteArray();
            var factAt = indexOf(wav, "fact") + 8;
            assertEquals(8 * 505L, le(wav).getInt(factAt));
            le(wav).putInt(factAt, -1).putLong(36, 8 * 505L - 100);
            var read = demux(wav);
            assertEquals(8 * 505L - 100, read.track().duration());
            assertEquals(505 - 100, read.packets().getLast().duration());
            // a fact count other than 0xFFFFFFFF is the count, whatever ds64 holds
            le(wav).putInt(factAt, 1000);
            assertEquals(1000, demux(wav).track().duration());
        }

        private static int indexOf(byte[] bytes, String id) {
            var pattern = id.getBytes();
            for (var index = 0; index + pattern.length <= bytes.length; index++) {
                if (Arrays.equals(bytes, index, index + pattern.length, pattern, 0, pattern.length)) {
                    return index;
                }
            }
            throw new AssertionError(id + " not found");
        }

        @Test
        @DisplayName("bounds the packets of a code stream whose sampling rate is extreme")
        void packetBound() {
            var data = random(6, (1 << 20) + 300);
            var read = demux(wave(0x028F, 1, 2_000_000_000, 1_000_000_000, 1, 4, new byte[0], -1, data, true));
            assertEquals(1 << 20, read.packets().getFirst().size());
            assertArrayEquals(data, concatenate(read.packets()));
        }

        @Test
        @DisplayName("rejects ADPCM in a big-endian RIFX file")
        void rifx() {
            var format = ByteBuffer.allocate(20).putShort((short) 0x11).putShort((short) 1).putInt(8000).putInt(4055)
                    .putShort((short) 36).putShort((short) 4).putShort((short) 2).putShort((short) 65).array();
            var file = ByteBuffer.allocate(12 + 8 + 20 + 8 + 36);
            file.put("RIFX".getBytes()).putInt(file.capacity() - 8).put("WAVE".getBytes());
            file.put("fmt ".getBytes()).putInt(20).put(format);
            file.put("data".getBytes()).putInt(36).put(imaBlock(1, 36, 1));
            var exception = assertThrows(MediaException.Unsupported.class, () -> demux(file.array()));
            assertEquals("rifx format 0x0011", exception.format());
        }

        @Test
        @DisplayName("rejects variants it cannot read and malformed formats")
        void rejects() {
            var ima3 = wave(0x11, 1, 8000, 3000, 256, 3, new byte[]{2, 0}, -1, new byte[256], false);
            assertEquals("adpcm_ima_wav 3-bit", assertThrows(MediaException.Unsupported.class, () -> demux(ima3))
                    .format());
            var gsm33 = wave(0x31, 1, 8000, 1650, 33, 0, new byte[0], -1, new byte[66], false);
            assertThrows(MediaException.Unsupported.class, () -> demux(gsm33));
            var stereo722 = wave(0x028F, 2, 16000, 16000, 2, 4, new byte[0], -1, new byte[64], false);
            assertThrows(MediaException.Unsupported.class, () -> demux(stereo722));
            var badBlock = wave(0x11, 2, 8000, 8000, 75, 4, new byte[0], -1, new byte[150], false);
            assertThrows(MediaException.Malformed.class, () -> demux(badBlock));
            var tinyBlock = wave(0x02, 1, 8000, 8000, 6, 4, new byte[0], -1, new byte[12], false);
            assertThrows(MediaException.Malformed.class, () -> demux(tinyBlock));
            var noCodeSize = wave(0x45, 1, 8000, 0, 1, 0, new byte[0], -1, new byte[16], false);
            assertThrows(MediaException.Malformed.class, () -> demux(noCodeSize));
            var noChannels = wave(0x11, 0, 8000, 0, 36, 4, new byte[0], -1, new byte[36], false);
            assertThrows(MediaException.Malformed.class, () -> demux(noChannels));
        }
    }
}
