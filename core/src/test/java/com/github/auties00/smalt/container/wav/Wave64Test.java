package com.github.auties00.smalt.container.wav;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.PcmPackets;
import com.github.auties00.smalt.container.ac3.Ac3Demuxer;
import com.github.auties00.smalt.container.adts.AdtsDemuxer;
import com.github.auties00.smalt.container.mpeg.MpegAudioDemuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers Sony Wave64: {@link Wave64Muxer} files read back by {@link WavDemuxer} and by ffprobe and decoded by ffmpeg
 * as their source, ffmpeg's files read as ffprobe reads them, the GUID chunk layout, streaming output and malformed
 * input.
 */
@DisplayName("Sony Wave64")
class Wave64Test {
    private static final Path SAMPLES = Path.of("src", "test", "resources", "samples");

    private static final byte[] RIFF = HexFormat.of().parseHex("726966662e91cf11a5d628db04c10000");

    private static final byte[] WAVE = HexFormat.of().parseHex("77617665f3acd3118cd100c04f8edb8a");

    private static final byte[] FMT = HexFormat.of().parseHex("666d7420f3acd3118cd100c04f8edb8a");

    private static final byte[] DATA = HexFormat.of().parseHex("64617461f3acd3118cd100c04f8edb8a");

    static ContainerProbe.Demuxed read(byte[] file) {
        try (var demuxer = new WavDemuxer(MediaSource.of(file))) {
            assertEquals(ContainerFormat.W64, demuxer.format());
            return ContainerProbe.demuxAll(demuxer);
        }
    }

    /** Returns the offset of the chunk of a GUID, walking the chunks. */
    static int chunk(byte[] file, byte[] guid) {
        var offset = 40;
        while (offset + 24 <= file.length) {
            if (Arrays.equals(file, offset, offset + 16, guid, 0, 16)) {
                return offset;
            }
            var size = ByteBuffer.wrap(file, offset + 16, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
            assertTrue(size >= 24, "chunk size " + size);
            offset += (int) (size + 7 & ~7L);
        }
        throw new AssertionError("no chunk");
    }

    @Nested
    @DisplayName("PCM")
    class Pcm {
        static Stream<Arguments> codecs() {
            return Stream.of(
                    Arguments.of(Codec.PCM_U8, "pcm_u8"),
                    Arguments.of(Codec.PCM_S16LE, "pcm_s16le"),
                    Arguments.of(Codec.PCM_S24LE, "pcm_s24le"),
                    Arguments.of(Codec.PCM_S32LE, "pcm_s32le"),
                    Arguments.of(Codec.PCM_F32LE, "pcm_f32le"),
                    Arguments.of(Codec.PCM_F64LE, "pcm_f64le"),
                    Arguments.of(Codec.PCM_ALAW, "pcm_alaw"),
                    Arguments.of(Codec.PCM_MULAW, "pcm_mulaw"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("codecs")
        @DisplayName("writes GUID chunks of 64-bit sizes that WavDemuxer and ffprobe read back")
        void roundTrip(Codec codec, String ffmpegName) {
            var input = PcmPackets.tone(codec, 44_100, ChannelLayout.STEREO, 4411);
            var output = MuxSupport.toMemory(List.of(input.track()), input.packets(), Wave64Muxer::new);
            assertArrayEquals(RIFF, Arrays.copyOf(output, 16));
            assertEquals(output.length, ByteBuffer.wrap(output, 16, 8).order(ByteOrder.LITTLE_ENDIAN).getLong());
            assertArrayEquals(WAVE, Arrays.copyOfRange(output, 24, 40));
            assertEquals(40, chunk(output, FMT));
            var data = chunk(output, DATA);
            assertEquals(0, data % 8);
            assertEquals(24 + input.bytes().length, ByteBuffer.wrap(output, data + 16, 8)
                    .order(ByteOrder.LITTLE_ENDIAN).getLong());
            assertEquals(0, output.length % 8);
            var result = read(output);
            var track = (AudioTrack) result.tracks().getFirst();
            assertEquals(codec, track.codec());
            assertEquals(4411, track.duration());
            assertArrayEquals(input.bytes(), PcmPackets.concatenate(result.order()));
            var name = "w64-" + ffmpegName + ".w64";
            var probe = ContainerProbe.probe(MuxSupport.save(name, output));
            assertEquals("w64", probe.format().get("format_name"));
            assertEquals(ffmpegName, probe.streams().getFirst().codecName());
            MuxSupport.assertFfmpegClean(name, output);
        }

        @Test
        @DisplayName("converts big-endian samples as WavMuxer does and stores a 64-bit sample count for floats")
        void conversionsAndFact() {
            var input = PcmPackets.tone(Codec.PCM_F32BE, 8000, ChannelLayout.MONO, 801);
            var output = MuxSupport.toMemory(List.of(input.track()), input.packets(), Wave64Muxer::new);
            var fact = chunk(output, HexFormat.of().parseHex("66616374f3acd3118cd100c04f8edb8a"));
            assertEquals(32, ByteBuffer.wrap(output, fact + 16, 8).order(ByteOrder.LITTLE_ENDIAN).getLong());
            assertEquals(801, ByteBuffer.wrap(output, fact + 24, 8).order(ByteOrder.LITTLE_ENDIAN).getLong());
            var result = read(output);
            assertEquals(Codec.PCM_F32LE, result.tracks().getFirst().codec());
            assertArrayEquals(PcmPackets.tone(Codec.PCM_F32LE, 8000, ChannelLayout.MONO, 801).bytes(),
                    PcmPackets.concatenate(result.order()));
        }

        @Test
        @DisplayName("streams with zero sizes and data that is read to the end of the file")
        void streaming() {
            var input = PcmPackets.tone(Codec.PCM_S16LE, 22_050, ChannelLayout.STEREO, 3001);
            var output = MuxSupport.toStream(List.of(input.track()), input.packets(), Wave64Muxer::new);
            assertEquals(0, ByteBuffer.wrap(output, 16, 8).getLong());
            var result = read(output);
            assertArrayEquals(input.bytes(), PcmPackets.concatenate(result.order()));
            assertEquals(3001, ((AudioTrack) result.tracks().getFirst()).duration());
        }

        @Test
        @DisplayName("reads ffmpeg's 24-bit file as ffprobe does")
        void ffmpegFile() throws Exception {
            var file = SAMPLES.resolve("w64/s24.w64");
            var result = ContainerProbe.demux(file, WavDemuxer::new);
            assertEquals(Codec.PCM_S24LE, result.tracks().getFirst().codec());
            var bytes = Files.readAllBytes(file);
            var data = chunk(bytes, DATA) + 24;
            var size = (int) ByteBuffer.wrap(bytes, data - 8, 8).order(ByteOrder.LITTLE_ENDIAN).getLong() - 24;
            // ffmpeg counts the padding to eight bytes in the data size, so its last 4 bytes are no whole frame
            assertEquals(4, size % 6);
            assertArrayEquals(Arrays.copyOfRange(bytes, data, data + size - size % 6),
                    PcmPackets.concatenate(result.order()));
        }
    }

    @Nested
    @DisplayName("coded audio")
    class Coded {
        static Stream<Arguments> streams() {
            return Stream.of(
                    Arguments.of("mp3/cbr.mp3", Codec.MP3),
                    Arguments.of("ac3/ac3.ac3", Codec.AC3),
                    Arguments.of("adts/aac.aac", Codec.AAC));
        }

        @ParameterizedTest(name = "{1}")
        @MethodSource("streams")
        @DisplayName("carries the coded payloads of WAVE, packet for packet, as ffmpeg decodes them")
        void payloads(String sample, Codec codec) {
            Function<MediaSource, com.github.auties00.smalt.container.Demuxer> factory = switch (codec) {
                case MP3 -> MpegAudioDemuxer::new;
                case AC3 -> Ac3Demuxer::new;
                default -> AdtsDemuxer::new;
            };
            var input = ContainerProbe.demux(SAMPLES.resolve(sample), factory);
            var output = MuxSupport.toMemory(List.of(input.tracks().getFirst()), input.order(), Wave64Muxer::new);
            var result = read(output);
            assertEquals(codec, result.tracks().getFirst().codec());
            assertEquals(input.order().size(), result.order().size());
            for (var index = 0; index < input.order().size(); index++) {
                assertArrayEquals(input.order().get(index).data(), result.order().get(index).data());
            }
            Oracle.assume(Oracle.Tool.FFMPEG);
            var file = MuxSupport.save("w64-" + codec.name().toLowerCase() + ".w64", output);
            Oracle.assertFfmpegDecodes(file);
            if (codec == Codec.MP3) {
                // ffmpeg trims the gapless delay of the MP3 file, so the frames ffprobe reads are compared instead
                var frames = ContainerProbe.packetData(file);
                assertEquals(input.order().size(), frames.size());
                for (var index = 0; index < frames.size(); index++) {
                    assertArrayEquals(input.order().get(index).data(), frames.get(index), "frame " + index);
                }
            } else {
                assertArrayEquals(Oracle.decodeAudio(SAMPLES.resolve(sample)), Oracle.decodeAudio(file));
            }
        }

        @Test
        @DisplayName("reads ffmpeg's IMA ADPCM file in its blocks")
        void imaAdpcm() throws Exception {
            var file = SAMPLES.resolve("w64/ima.w64");
            var result = ContainerProbe.demux(file, WavDemuxer::new);
            assertEquals(Codec.ADPCM_IMA_WAV, result.tracks().getFirst().codec());
            var bytes = Files.readAllBytes(file);
            var data = chunk(bytes, DATA) + 24;
            var size = (int) ByteBuffer.wrap(bytes, data - 8, 8).order(ByteOrder.LITTLE_ENDIAN).getLong() - 24;
            assertArrayEquals(Arrays.copyOfRange(bytes, data, data + size), PcmPackets.concatenate(result.order()));
            var output = MuxSupport.toMemory(List.of(result.tracks().getFirst()), result.order(), Wave64Muxer::new);
            assertArrayEquals(PcmPackets.concatenate(result.order()), PcmPackets.concatenate(read(output).order()));
            Oracle.assume(Oracle.Tool.FFMPEG);
            assertArrayEquals(Oracle.decodeAudio(file), Oracle.decodeAudio(MuxSupport.save("w64-ima.w64", output)));
        }
    }

    @Nested
    @DisplayName("malformed input")
    class Malformed {
        @Test
        @DisplayName("rejects a riff header without its GUID, a missing fmt chunk and chunks smaller than their header")
        void structure() {
            var input = PcmPackets.tone(Codec.PCM_S16LE, 8000, ChannelLayout.MONO, 80);
            var valid = MuxSupport.toMemory(List.of(input.track()), input.packets(), Wave64Muxer::new);
            var guid = valid.clone();
            guid[6] ^= 1;
            assertThrows(MediaException.Malformed.class, () -> new WavDemuxer(MediaSource.of(guid)));
            assertTrue(Containers.detect(MediaSource.of(guid)).isEmpty());
            var small = valid.clone();
            ByteBuffer.wrap(small, 56, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(8);
            assertThrows(MediaException.Malformed.class, () -> new WavDemuxer(MediaSource.of(small)));
            var noFormat = valid.clone();
            noFormat[40] = 'x';
            assertThrows(MediaException.Malformed.class, () -> new WavDemuxer(MediaSource.of(noFormat)));
        }

        @ParameterizedTest
        @ValueSource(strings = {"w64/s24.w64", "w64/ima.w64"})
        @DisplayName("reads or rejects every truncation of a file with a MediaException")
        void truncations(String sample) throws Exception {
            var bytes = Files.readAllBytes(SAMPLES.resolve(sample));
            for (var length = 0; length < Math.min(bytes.length, 300); length++) {
                try (var demuxer = new WavDemuxer(MediaSource.of(Arrays.copyOf(bytes, length)))) {
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
    @DisplayName("is recognized by its GUIDs and opened by Containers")
    void containers() {
        var input = PcmPackets.tone(Codec.PCM_S16LE, 8000, ChannelLayout.MONO, 80);
        var output = MuxSupport.toMemory(List.of(input.track()), input.packets(), Wave64Muxer::new);
        assertEquals(ContainerFormat.W64, Containers.detect(MediaSource.of(output)).orElseThrow());
        try (var demuxer = Containers.openDemuxer(MediaSource.of(output))) {
            assertEquals(ContainerFormat.W64, demuxer.format());
        }
        assertInstanceOf(Wave64Muxer.class, Containers.openMuxer(ContainerFormat.W64, MediaSink.memory()));
        assertTrue(Containers.isMuxable(ContainerFormat.W64, Codec.MP3));
        assertTrue(!Containers.isMuxable(ContainerFormat.W64, Codec.H264));
    }
}
