package com.github.auties00.smalt.container.au;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.PcmPackets;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.testkit.Oracle;
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
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link AuDemuxer} and {@link AuMuxer}: every PCM encoding read back by the demuxer and ffprobe, the G.721,
 * G.722 and G.723 codes of ffmpeg's files checked against the most-significant-bit-first codes ffmpeg writes for the
 * same audio, the annotation, the little-endian variant, streaming output and malformed input.
 */
@DisplayName("AU")
class AuTest {
    private static final Path SAMPLES = Path.of("src", "test", "resources", "samples");

    static ContainerProbe.Demuxed read(byte[] file) {
        try (var demuxer = new AuDemuxer(MediaSource.of(file))) {
            return ContainerProbe.demuxAll(demuxer);
        }
    }

    @Nested
    @DisplayName("PCM")
    class Pcm {
        static Stream<Arguments> codecs() {
            return Stream.of(
                    Arguments.of(Codec.PCM_MULAW, Codec.PCM_MULAW, 1, "pcm_mulaw"),
                    Arguments.of(Codec.PCM_S8, Codec.PCM_S8, 2, "pcm_s8"),
                    Arguments.of(Codec.PCM_S16BE, Codec.PCM_S16BE, 3, "pcm_s16be"),
                    Arguments.of(Codec.PCM_S24BE, Codec.PCM_S24BE, 4, "pcm_s24be"),
                    Arguments.of(Codec.PCM_S32BE, Codec.PCM_S32BE, 5, "pcm_s32be"),
                    Arguments.of(Codec.PCM_F32BE, Codec.PCM_F32BE, 6, "pcm_f32be"),
                    Arguments.of(Codec.PCM_F64BE, Codec.PCM_F64BE, 7, "pcm_f64be"),
                    Arguments.of(Codec.PCM_ALAW, Codec.PCM_ALAW, 27, "pcm_alaw"),
                    Arguments.of(Codec.PCM_S16LE, Codec.PCM_S16BE, 3, "pcm_s16be"),
                    Arguments.of(Codec.PCM_S24LE, Codec.PCM_S24BE, 4, "pcm_s24be"),
                    Arguments.of(Codec.PCM_F64LE, Codec.PCM_F64BE, 7, "pcm_f64be"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("codecs")
        @DisplayName("writes the encoding ffprobe reads and samples that ffmpeg decodes as the source")
        void roundTrip(Codec codec, Codec stored, int encoding, String ffmpegName) {
            var input = PcmPackets.tone(codec, 22_050, ChannelLayout.STEREO, 2205);
            var output = MuxSupport.toMemory(List.of(input.track()), input.packets(), AuMuxer::new);
            var header = ByteBuffer.wrap(output);
            assertEquals(0x2E736E64, header.getInt());
            var offset = header.getInt();
            assertEquals(0, offset % 8);
            assertEquals(output.length - offset, header.getInt());
            assertEquals(encoding, header.getInt());
            assertEquals(22_050, header.getInt());
            assertEquals(2, header.getInt());
            var result = read(output);
            var track = (AudioTrack) result.tracks().getFirst();
            assertEquals(stored, track.codec());
            assertEquals(2205, track.duration());
            var original = PcmPackets.tone(stored, 22_050, ChannelLayout.STEREO, 2205);
            assertArrayEquals(original.bytes(), PcmPackets.concatenate(result.order()));
            var name = "au-" + codec.name().toLowerCase() + ".au";
            assertEquals(ffmpegName, ContainerProbe.probe(MuxSupport.save(name, output)).streams().getFirst()
                    .codecName());
            MuxSupport.assertFfmpegClean(name, output);
        }

        @Test
        @DisplayName("writes unsigned 8-bit samples as signed ones and refuses 64-bit integers")
        void eightBits() {
            var input = PcmPackets.tone(Codec.PCM_U8, 8000, ChannelLayout.MONO, 800);
            var result = read(MuxSupport.toMemory(List.of(input.track()), input.packets(), AuMuxer::new));
            assertEquals(Codec.PCM_S8, result.tracks().getFirst().codec());
            var expected = input.bytes().clone();
            for (var index = 0; index < expected.length; index++) {
                expected[index] ^= (byte) 0x80;
            }
            assertArrayEquals(expected, PcmPackets.concatenate(result.order()));
            assertTrue(!AuMuxer.supports(Codec.PCM_S64BE) && !AuMuxer.supports(Codec.AAC));
        }

        @Test
        @DisplayName("streams with an unknown size that is read to the end of the file")
        void streaming() {
            var input = PcmPackets.tone(Codec.PCM_S16BE, 8000, ChannelLayout.STEREO, 999);
            var output = MuxSupport.toStream(List.of(input.track()), input.packets(), AuMuxer::new);
            assertEquals(-1, ByteBuffer.wrap(output, 8, 4).getInt());
            var result = read(output);
            assertEquals(999, ((AudioTrack) result.tracks().getFirst()).duration());
            assertArrayEquals(input.bytes(), PcmPackets.concatenate(result.order()));
        }

        @Test
        @DisplayName("reads the little-endian variant")
        void littleEndian() {
            var samples = new byte[400];
            new Random(5).nextBytes(samples);
            var file = new ByteWriter(424);
            file.fourCc("dns.");
            file.u32le(24);
            file.u32le(samples.length);
            file.u32le(3);
            file.u32le(8000);
            file.u32le(2);
            file.bytes(samples);
            var result = read(file.toByteArray());
            assertEquals(Codec.PCM_S16LE, result.tracks().getFirst().codec());
            assertArrayEquals(samples, PcmPackets.concatenate(result.order()));
            assertEquals(ContainerFormat.AU, Containers.detect(MediaSource.of(file.toByteArray())).orElseThrow());
        }

        @Test
        @DisplayName("reads ffmpeg's files as ffprobe does")
        void ffmpegFiles() throws Exception {
            for (var name : List.of("au/s16.au", "au/mulaw.au")) {
                var file = SAMPLES.resolve(name);
                var bytes = Files.readAllBytes(file);
                var result = ContainerProbe.demux(file, AuDemuxer::new);
                var offset = ByteBuffer.wrap(bytes, 4, 4).getInt();
                assertArrayEquals(Arrays.copyOfRange(bytes, offset, bytes.length), PcmPackets.concatenate(
                        result.order()), name);
                assertArrayEquals(concatenate(ContainerProbe.packetData(file)), PcmPackets.concatenate(
                        result.order()), name);
            }
            assertEquals("Pink", ContainerProbe.demux(SAMPLES.resolve("au/s16.au"), AuDemuxer::new).metadata()
                    .get("Title"));
        }
    }

    @Nested
    @DisplayName("ADPCM")
    class Adpcm {
        /** Encodes a tone with ffmpeg's G.726 encoder in its most-significant-bit-first packing. */
        static byte[] msbFirstCodes(int bits) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-bitexact", "-f",
                    "lavfi", "-i", "sine=frequency=440:sample_rate=8000:duration=0.6", "-c:a", "adpcm_g726",
                    "-code_size", String.valueOf(bits), "-ac", "1", "-f", "g726", "-"));
        }

        @Test
        @DisplayName("reads ffmpeg's G.721 file as the codes ffmpeg's big-endian G.726 encoder writes")
        void g721() {
            var result = ContainerProbe.demux(SAMPLES.resolve("au/g721.au"), AuDemuxer::new);
            var track = (AudioTrack) result.tracks().getFirst();
            assertEquals(Codec.ADPCM_G726, track.codec());
            assertEquals(4, track.bitsPerSample());
            assertEquals(4800, track.duration());
            assertArrayEquals(msbFirstCodes(4), PcmPackets.concatenate(result.order()));
            var start = 0L;
            for (var packet : result.order()) {
                assertEquals(start, packet.pts());
                assertEquals(2L * packet.size(), packet.duration());
                start += packet.duration();
            }
        }

        @Test
        @DisplayName("reads 3-bit G.723 codes as the codes ffmpeg's big-endian G.726 encoder writes")
        void g723() {
            var result = ContainerProbe.demux(SAMPLES.resolve("au/g723-3.au"), AuDemuxer::new);
            var track = (AudioTrack) result.tracks().getFirst();
            assertEquals(3, track.bitsPerSample());
            var expected = msbFirstCodes(3);
            var whole = expected.length / 3 * 3;
            assertArrayEquals(Arrays.copyOf(expected, whole), PcmPackets.concatenate(result.order()));
        }

        @ParameterizedTest(name = "{0}-bit codes")
        @ValueSource(ints = {3, 4, 5})
        @DisplayName("writes G.726 codes that ffmpeg's little-endian decoder decodes as its big-endian one does")
        void writesG726(int bits) {
            var codes = msbFirstCodes(bits);
            var whole = codes.length / bits * bits;
            var track = AudioTrack.builder().id(1).codec(Codec.ADPCM_G726).timeBase(Rational.timeBaseOf(8000))
                    .sampleRate(8000).layout(ChannelLayout.MONO).bitsPerSample(bits).build();
            var packets = new ArrayList<Packet>();
            for (var offset = 0; offset < whole; offset += 15 * bits) {
                var end = Math.min(whole, offset + 15 * bits);
                packets.add(new Packet(1, Arrays.copyOfRange(codes, offset, end), Packet.NO_TIMESTAMP,
                        Packet.NO_TIMESTAMP, Packet.UNKNOWN_DURATION, true));
            }
            var output = MuxSupport.toMemory(List.of(track), packets, AuMuxer::new);
            assertEquals(bits == 4 ? 23 : bits == 3 ? 25 : 26, ByteBuffer.wrap(output, 12, 4).getInt());
            assertArrayEquals(Arrays.copyOf(codes, whole), PcmPackets.concatenate(read(output).order()));
            var raw = MuxSupport.save("au-g726-" + bits + ".g726", Arrays.copyOf(codes, whole));
            var file = MuxSupport.save("au-g726-" + bits + ".au", output);
            var expected = Oracle.decodeAudio(raw, List.of("-f", "g726", "-code_size", String.valueOf(bits),
                    "-sample_rate", "8000"));
            var actual = Oracle.decodeAudio(file);
            assertArrayEquals(expected, actual);
        }

        @Test
        @DisplayName("repacks a partial last unit and the inverse restores every code")
        void packing() {
            var random = new Random(3);
            for (var bits = 2; bits <= 5; bits++) {
                for (var length = 1; length <= 11; length++) {
                    var data = new byte[length];
                    random.nextBytes(data);
                    var copy = data.clone();
                    G726Packing.toMsbFirst(copy, length, bits);
                    G726Packing.toLsbFirst(copy, length, bits);
                    var unit = bits == 3 || bits == 5 ? bits : 1;
                    var whole = length / unit * unit;
                    assertArrayEquals(Arrays.copyOf(data, whole), Arrays.copyOf(copy, whole), bits + " bits");
                }
            }
            var nibbles = new byte[]{0x12, (byte) 0xAB};
            G726Packing.toMsbFirst(nibbles, 2, 4);
            assertArrayEquals(new byte[]{0x21, (byte) 0xBA}, nibbles);
        }

        @Test
        @DisplayName("carries G.722 bytes as they are")
        void g722() {
            var codes = new byte[1000];
            new Random(9).nextBytes(codes);
            var track = AudioTrack.builder().id(1).codec(Codec.ADPCM_G722).timeBase(Rational.timeBaseOf(16_000))
                    .sampleRate(16_000).layout(ChannelLayout.MONO).build();
            var output = MuxSupport.toMemory(List.of(track), List.of(new Packet(1, codes, 0, 0, 2000, true)),
                    AuMuxer::new);
            var result = read(output);
            assertEquals(Codec.ADPCM_G722, result.tracks().getFirst().codec());
            assertEquals(2000, ((AudioTrack) result.tracks().getFirst()).duration());
            assertArrayEquals(codes, PcmPackets.concatenate(result.order()));
            assertEquals("adpcm_g722", ContainerProbe.probe(MuxSupport.save("au-g722.au", output)).streams()
                    .getFirst().codecName());
        }

        @Test
        @DisplayName("refuses ADPCM of several channels and 2-bit G.726")
        void refusals() {
            var stereo = AudioTrack.builder().id(1).codec(Codec.ADPCM_G722).timeBase(Rational.timeBaseOf(16_000))
                    .sampleRate(16_000).layout(ChannelLayout.STEREO).build();
            assertThrows(IllegalArgumentException.class, () -> new AuMuxer(MediaSink.memory()).addTrack(stereo));
            var twoBits = AudioTrack.builder().id(1).codec(Codec.ADPCM_G726).timeBase(Rational.timeBaseOf(8000))
                    .sampleRate(8000).layout(ChannelLayout.MONO).bitsPerSample(2).build();
            assertThrows(IllegalArgumentException.class, () -> new AuMuxer(MediaSink.memory()).addTrack(twoBits));
        }
    }

    @Nested
    @DisplayName("annotation")
    class Annotation {
        @Test
        @DisplayName("stores the tags as key=value lines that the demuxer and ffprobe read")
        void tags() {
            var input = PcmPackets.tone(Codec.PCM_S16BE, 8000, ChannelLayout.MONO, 80);
            var sink = MediaSink.memory();
            try (var muxer = new AuMuxer(sink)) {
                muxer.addTrack(input.track());
                muxer.setTags(MediaTags.builder().set(StandardTag.TITLE, "Two\nlines").set(StandardTag.ARTIST, "Me")
                        .set("mood", "calm").build());
                input.packets().forEach(muxer::writePacket);
                muxer.finish();
            }
            var output = sink.toByteArray();
            assertEquals(0, ByteBuffer.wrap(output, 4, 4).getInt() % 8);
            var metadata = read(output).metadata();
            assertEquals("Two lines", metadata.get("Title"));
            assertEquals("Me", metadata.get("Artist"));
            assertEquals("calm", metadata.get("mood"));
            var probe = ContainerProbe.probe(MuxSupport.save("au-tags.au", output));
            assertEquals("Two lines", probe.format().get("tag:title"));
            assertEquals("Me", probe.format().get("tag:artist"));
        }

        @Test
        @DisplayName("keeps free text as a comment")
        void freeText() {
            assertEquals("Recorded at noon", AuDemuxer.metadata("Recorded at noon").get("comment"));
            assertEquals("a=b\nnot keyed", AuDemuxer.metadata("a=b\nnot keyed").get("comment"));
            assertTrue(AuDemuxer.metadata("").isEmpty());
        }
    }

    @Nested
    @DisplayName("malformed input")
    class Malformed {
        static byte[] header(int offset, long encoding, long rate, long channels) {
            var file = new ByteWriter(offset + 8);
            file.fourCc(".snd");
            file.u32be(offset);
            file.u32be(8);
            file.u32be(encoding);
            file.u32be(rate);
            file.u32be(channels);
            file.zeros(offset - 24 + 8);
            return file.toByteArray();
        }

        @Test
        @DisplayName("rejects small headers, zero rates and channels, and names an unknown encoding")
        void headers() {
            assertThrows(MediaException.Malformed.class, () -> new AuDemuxer(MediaSource.of(new byte[30])));
            assertThrows(MediaException.Malformed.class, () -> new AuDemuxer(MediaSource.of(header(24, 3, 0, 1))));
            assertThrows(MediaException.Malformed.class, () -> new AuDemuxer(MediaSource.of(header(24, 3, 8000, 0))));
            var small = header(24, 3, 8000, 1);
            small[7] = 16;
            assertThrows(MediaException.Malformed.class, () -> new AuDemuxer(MediaSource.of(small)));
            var unsupported = assertThrows(MediaException.Unsupported.class, () -> new AuDemuxer(MediaSource.of(
                    header(24, 9, 8000, 1))));
            assertEquals("au encoding 9", unsupported.format());
            assertThrows(MediaException.Unsupported.class, () -> new AuDemuxer(MediaSource.of(header(24, 23, 8000,
                    2))));
        }

        @Test
        @DisplayName("reads a size past the end of the file up to the end")
        void oversized() {
            var file = header(32, 3, 8000, 1);
            ByteBuffer.wrap(file).putInt(8, 1 << 20);
            var result = read(file);
            assertEquals(4, ((AudioTrack) result.tracks().getFirst()).duration());
        }

        @ParameterizedTest
        @ValueSource(strings = {"au/s16.au", "au/g721.au", "au/g723-3.au"})
        @DisplayName("reads or rejects every truncation of a file with a MediaException")
        void truncations(String sample) throws Exception {
            var bytes = Files.readAllBytes(SAMPLES.resolve(sample));
            for (var length = 0; length < Math.min(bytes.length, 200); length++) {
                try (var demuxer = new AuDemuxer(MediaSource.of(Arrays.copyOf(bytes, length)))) {
                    while (demuxer.read() != null) {
                        assertTrue(true);
                    }
                } catch (MediaException expected) {
                    assertInstanceOf(MediaException.class, expected);
                }
            }
        }
    }

    @Test
    @DisplayName("is recognized by its magic and opened by Containers")
    void containers() {
        var input = PcmPackets.tone(Codec.PCM_S16BE, 8000, ChannelLayout.MONO, 80);
        var output = MuxSupport.toMemory(List.of(input.track()), input.packets(), AuMuxer::new);
        assertEquals(ContainerFormat.AU, Containers.detect(MediaSource.of(output)).orElseThrow());
        try (var demuxer = Containers.openDemuxer(MediaSource.of(output))) {
            assertInstanceOf(AuDemuxer.class, demuxer);
        }
        var text = ".snd is not an AU file".getBytes(StandardCharsets.US_ASCII);
        assertTrue(Containers.detect(MediaSource.of(Arrays.copyOf(text, 64))).isEmpty());
    }

    private static byte[] concatenate(List<byte[]> parts) {
        var total = 0;
        for (var part : parts) {
            total += part.length;
        }
        var result = new byte[total];
        var offset = 0;
        for (var part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }
}
