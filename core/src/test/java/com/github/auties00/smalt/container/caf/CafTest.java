package com.github.auties00.smalt.container.caf;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.Ambisonics;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.PcmPackets;
import com.github.auties00.smalt.container.adts.AdtsDemuxer;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.container.mpeg.MpegAudioDemuxer;
import com.github.auties00.smalt.container.ac3.Ac3Demuxer;
import com.github.auties00.smalt.container.ogg.OggDemuxer;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link CafDemuxer} and {@link CafMuxer}: every format the muxer writes read back packet for packet by the
 * demuxer and by ffprobe and decoded by ffmpeg exactly as the source, files ffmpeg writes read packet for packet
 * against ffprobe, the packet table (sizes, frames, priming and remainder), channel layouts, the {@code info}
 * chunk, streaming output and malformed input.
 */
@DisplayName("CAF")
class CafTest {
    private static final Path SAMPLES = Path.of("src", "test", "resources", "samples");

    static ContainerProbe.Demuxed demux(String sample, Function<MediaSource, com.github.auties00.smalt.container.Demuxer> factory) {
        return ContainerProbe.demux(SAMPLES.resolve(sample), factory);
    }

    static byte[] mux(ContainerProbe.Demuxed input) {
        return MuxSupport.toMemory(List.of(input.tracks().getFirst()), input.order(), CafMuxer::new);
    }

    static ContainerProbe.Demuxed read(byte[] file) {
        try (var demuxer = new CafDemuxer(MediaSource.of(file))) {
            return ContainerProbe.demuxAll(demuxer);
        }
    }

    static void assertSamePackets(ContainerProbe.Demuxed expected, ContainerProbe.Demuxed actual) {
        assertEquals(expected.order().size(), actual.order().size(), "packet count");
        var frames = 0L;
        for (var index = 0; index < expected.order().size(); index++) {
            var want = expected.order().get(index);
            var got = actual.order().get(index);
            assertArrayEquals(want.data(), got.data(), "packet " + index);
            assertEquals(frames, got.pts(), "pts of packet " + index);
            frames += got.duration();
        }
    }

    /** Decodes a file with ffmpeg and checks it against the decode of another, sample for sample. */
    static void assertSameDecode(Path expected, String name, byte[] actual) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var file = MuxSupport.save(name, actual);
        Oracle.assertFfmpegDecodes(file);
        assertArrayEquals(Oracle.decodeAudio(expected), Oracle.decodeAudio(file), name + " decodes as its source");
    }

    @Nested
    @DisplayName("linear PCM")
    class Pcm {
        static Stream<Arguments> codecs() {
            return Stream.of(
                    Arguments.of(Codec.PCM_S8, "pcm_s8", 0, 8),
                    Arguments.of(Codec.PCM_S16BE, "pcm_s16be", 0, 16),
                    Arguments.of(Codec.PCM_S16LE, "pcm_s16le", 2, 16),
                    Arguments.of(Codec.PCM_S24BE, "pcm_s24be", 0, 24),
                    Arguments.of(Codec.PCM_S24LE, "pcm_s24le", 2, 24),
                    Arguments.of(Codec.PCM_S32BE, "pcm_s32be", 0, 32),
                    Arguments.of(Codec.PCM_S32LE, "pcm_s32le", 2, 32),
                    Arguments.of(Codec.PCM_S64BE, "pcm_s64be", 0, 64),
                    Arguments.of(Codec.PCM_S64LE, "pcm_s64le", 2, 64),
                    Arguments.of(Codec.PCM_F32BE, "pcm_f32be", 1, 32),
                    Arguments.of(Codec.PCM_F32LE, "pcm_f32le", 3, 32),
                    Arguments.of(Codec.PCM_F64BE, "pcm_f64be", 1, 64),
                    Arguments.of(Codec.PCM_F64LE, "pcm_f64le", 3, 64),
                    Arguments.of(Codec.PCM_ALAW, "pcm_alaw", 0, 8),
                    Arguments.of(Codec.PCM_MULAW, "pcm_mulaw", 0, 8));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("codecs")
        @DisplayName("writes a desc chunk ffprobe reads and samples that come back intact")
        void roundTrip(Codec codec, String ffmpegName, int flags, int bits) {
            var input = PcmPackets.tone(codec, 44_100, ChannelLayout.STEREO, 4411);
            var output = MuxSupport.toMemory(List.of(input.track()), input.packets(), CafMuxer::new);
            assertEquals("caff", new String(output, 0, 4, StandardCharsets.US_ASCII));
            var desc = ByteBuffer.wrap(output, 20, 32);
            assertEquals(44_100.0, desc.getDouble());
            desc.getInt();
            assertEquals(flags, desc.getInt());
            assertEquals(bits / 8 * 2, desc.getInt());
            assertEquals(1, desc.getInt());
            assertEquals(2, desc.getInt());
            assertEquals(bits, desc.getInt());
            var result = read(output);
            var track = (AudioTrack) result.tracks().getFirst();
            assertEquals(codec, track.codec());
            assertEquals(44_100, track.sampleRate());
            assertEquals(4411, track.duration());
            assertEquals(ChannelLayout.STEREO, track.layout().orElseThrow());
            assertArrayEquals(input.bytes(), PcmPackets.concatenate(result.order()));
            var name = "caf-" + ffmpegName + ".caf";
            assertEquals(ffmpegName, ContainerProbe.probe(MuxSupport.save(name, output)).streams().getFirst()
                    .codecName());
            MuxSupport.assertFfmpegClean(name, output);
        }

        @Test
        @DisplayName("writes unsigned 8-bit samples as signed ones")
        void unsigned() {
            var input = PcmPackets.tone(Codec.PCM_U8, 8000, ChannelLayout.MONO, 801);
            var result = read(MuxSupport.toMemory(List.of(input.track()), input.packets(), CafMuxer::new));
            assertEquals(Codec.PCM_S8, result.tracks().getFirst().codec());
            var expected = input.bytes().clone();
            for (var index = 0; index < expected.length; index++) {
                expected[index] ^= (byte) 0x80;
            }
            assertArrayEquals(expected, PcmPackets.concatenate(result.order()));
        }

        @Test
        @DisplayName("streams with a data chunk of size -1 that is read to the end of the file")
        void streaming() {
            var input = PcmPackets.tone(Codec.PCM_S16BE, 22_050, ChannelLayout.STEREO, 3001);
            var output = MuxSupport.toStream(List.of(input.track()), input.packets(), CafMuxer::new);
            var data = indexOf(output, "data");
            assertEquals(-1, ByteBuffer.wrap(output, data + 4, 8).getLong());
            var result = read(output);
            assertArrayEquals(input.bytes(), PcmPackets.concatenate(result.order()));
            assertEquals(3001, ((AudioTrack) result.tracks().getFirst()).duration());
        }

        @Test
        @DisplayName("reads ffmpeg's 16-bit file sample for sample")
        void ffmpegFile() throws Exception {
            var result = demux("caf/s16.caf", CafDemuxer::new);
            assertEquals(Codec.PCM_S16BE, result.tracks().getFirst().codec());
            var bytes = Files.readAllBytes(SAMPLES.resolve("caf/s16.caf"));
            var data = indexOf(bytes, "data") + 16;
            assertArrayEquals(Arrays.copyOfRange(bytes, data, data + 4 * (int) ((AudioTrack) result.tracks()
                    .getFirst()).duration()), PcmPackets.concatenate(result.order()));
            assertEquals("Lavf61.7.100", result.metadata().get("encoder"));
        }

        @Test
        @DisplayName("rejects frames that do not fill a packet and a second track")
        void rejections() {
            var input = PcmPackets.tone(Codec.PCM_S24BE, 8000, ChannelLayout.MONO, 4);
            try (var muxer = new CafMuxer(MediaSink.memory())) {
                muxer.addTrack(input.track());
                assertThrows(IllegalStateException.class, () -> muxer.addTrack(input.track()));
                assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(new Packet(input.track().id(),
                        new byte[5], 0, 0, 1, true)));
            }
            assertThrows(IllegalArgumentException.class, () -> new CafMuxer(MediaSink.memory())
                    .addTrack(AudioTrack.builder().codec(Codec.PCM_S16LE).timeBase(Rational.timeBaseOf(8000)).build()));
        }
    }

    @Nested
    @DisplayName("compressed formats")
    class Compressed {
        @Test
        @DisplayName("reads ffmpeg's ALAC file as ffprobe does and writes it back so that ffmpeg decodes it unchanged")
        void alac() {
            var source = SAMPLES.resolve("caf/alac.caf");
            var input = demux("caf/alac.caf", CafDemuxer::new);
            assertFfprobePackets(source, input);
            var track = (AudioTrack) input.tracks().getFirst();
            assertEquals(Codec.ALAC, track.codec());
            assertEquals(24, track.codecConfig().orElseThrow().length);
            assertEquals(24, track.bitsPerSample(), "the bit depth of the ALACSpecificConfig");
            var output = mux(input);
            assertSamePackets(input, read(output));
            assertSameDecode(source, "caf-alac.caf", output);
        }

        @Test
        @DisplayName("carries the ALAC track of an M4A file")
        void alacFromMp4() {
            var source = SAMPLES.resolve("mp4/alac.m4a");
            var input = demux("mp4/alac.m4a", Mp4Demuxer::new);
            var output = mux(input);
            var result = read(output);
            assertSamePackets(input, result);
            assertEquals(input.tracks().getFirst().codecConfig().map(Arrays::toString),
                    result.tracks().getFirst().codecConfig().map(Arrays::toString));
            assertSameDecode(source, "caf-alac-m4a.caf", output);
        }

        @Test
        @DisplayName("writes AAC with its configuration in an elementary stream descriptor that ffmpeg decodes")
        void aac() {
            var source = SAMPLES.resolve("adts/aac.aac");
            var input = demux("adts/aac.aac", AdtsDemuxer::new);
            var output = mux(input);
            var result = read(output);
            assertSamePackets(input, result);
            var config = input.tracks().getFirst().codecConfig().orElseThrow();
            assertArrayEquals(config, result.tracks().getFirst().codecConfig().orElseThrow());
            var probe = ContainerProbe.probe(MuxSupport.save("caf-aac-probe.caf", output));
            assertEquals("aac", probe.streams().getFirst().codecName());
            assertArrayEquals(config, probe.streams().getFirst().extradata());
            assertSameDecode(source, "caf-aac.caf", output);
        }

        @Test
        @DisplayName("stores the priming and the valid frames of an AAC track in the packet table")
        void priming() {
            var input = demux("mp4/faststart.mp4", Mp4Demuxer::new);
            var audio = (AudioTrack) input.tracks().stream().filter(track -> track.codec() == Codec.AAC).findFirst()
                    .orElseThrow();
            var packets = input.packetsOf(audio);
            assertTrue(audio.skipSamples() > 0, "the sample has an encoder delay");
            assertEquals(Rational.timeBaseOf(audio.sampleRate()), audio.timeBase(), "durations in frames");
            // the packets' durations bound the valid frames: the last one of the sample is cut short
            var presented = packets.stream().mapToLong(packet -> packet.duration()).sum();
            var trimmed = audio.toBuilder().duration(presented - audio.skipSamples() - 100).build();
            var output = MuxSupport.toMemory(List.of(trimmed), packets, CafMuxer::new);
            var pakt = indexOf(output, "pakt");
            var table = ByteBuffer.wrap(output, pakt + 12, 24);
            assertEquals(packets.size(), table.getLong());
            assertEquals(trimmed.duration(), table.getLong());
            assertEquals(audio.skipSamples(), table.getInt());
            assertEquals(packets.size() * 1024L - audio.skipSamples() - trimmed.duration(), table.getInt());
            var result = read(output);
            var track = (AudioTrack) result.tracks().getFirst();
            assertEquals(audio.skipSamples(), track.skipSamples());
            assertEquals(trimmed.duration(), track.duration());
            assertEquals(1024, ByteBuffer.wrap(output, 40, 4).getInt(), "constant frames per packet");
        }

        @Test
        @DisplayName("counts the frames an AAC packet codes, not its duration in a millisecond time base")
        void millisecondTimeBase() {
            var input = demux("adts/aac.aac", AdtsDemuxer::new);
            var audio = (AudioTrack) input.tracks().getFirst();
            var milliseconds = Rational.timeBaseOf(1000);
            var track = audio.toBuilder().timeBase(milliseconds).duration(-1).build();
            var packets = input.order().stream().map(packet -> packet.withTiming(
                    audio.timeBase().rescale(packet.pts(), milliseconds),
                    audio.timeBase().rescale(packet.dts(), milliseconds),
                    audio.timeBase().rescale(packet.duration(), milliseconds))).toList();
            var output = MuxSupport.toMemory(List.of(track), packets, CafMuxer::new);
            assertEquals(1024, ByteBuffer.wrap(output, 40, 4).getInt(), "constant frames per packet");
            var result = read(output);
            assertEquals(packets.size() * 1024L - audio.skipSamples(), result.tracks().getFirst().duration());
            var original = read(mux(input));
            assertEquals(original.tracks().getFirst().duration(), result.tracks().getFirst().duration());
            for (var index = 0; index < packets.size(); index++) {
                assertEquals(1024L * index, result.order().get(index).pts(), "pts of packet " + index);
            }
        }

        @Test
        @DisplayName("reads ffmpeg's Opus file, with no magic cookie, and writes its OpusHead as one")
        void opus() {
            var source = SAMPLES.resolve("caf/opus.caf");
            var input = demux("caf/opus.caf", CafDemuxer::new);
            assertFfprobePackets(source, input);
            var track = (AudioTrack) input.tracks().getFirst();
            assertEquals(Codec.OPUS, track.codec());
            assertEquals(1, AudioConfigs.parseOpusHead(track.codecConfig().orElseThrow()).channels());
            var output = mux(input);
            assertSamePackets(input, read(output));
            MuxSupport.assertFfmpegClean("caf-opus.caf", output);
        }

        @Test
        @DisplayName("carries the Opus track of an Ogg file, with its pre-skip as priming")
        void opusFromOgg() {
            var input = demux("ogg/opus.opus", OggDemuxer::new);
            var head = input.tracks().getFirst().codecConfig().orElseThrow();
            var output = mux(input);
            var result = read(output);
            assertEquals(input.order().size(), result.order().size());
            for (var index = 0; index < input.order().size(); index++) {
                assertArrayEquals(input.order().get(index).data(), result.order().get(index).data());
            }
            assertArrayEquals(head, result.tracks().getFirst().codecConfig().orElseThrow());
            var pakt = indexOf(output, "pakt");
            assertEquals(AudioConfigs.opusPreSkip(head), ByteBuffer.wrap(output, pakt + 12 + 16, 4).getInt());
            MuxSupport.assertFfmpegClean("caf-opus-ogg.caf", output);
        }

        @Test
        @DisplayName("reads ffmpeg's IMA4 file in whole blocks and writes them back as ffmpeg decodes them")
        void ima4() throws Exception {
            var source = SAMPLES.resolve("caf/ima4.caf");
            var input = demux("caf/ima4.caf", CafDemuxer::new);
            var track = (AudioTrack) input.tracks().getFirst();
            assertEquals(Codec.ADPCM_IMA_QT, track.codec());
            for (var packet : input.order()) {
                assertEquals(0, packet.size() % 68, "whole stereo blocks");
                assertEquals(packet.size() / 68 * 64, packet.duration());
            }
            var bytes = Files.readAllBytes(source);
            var data = indexOf(bytes, "data") + 16;
            assertArrayEquals(Arrays.copyOfRange(bytes, data, bytes.length - (bytes.length - data) % 68),
                    PcmPackets.concatenate(input.order()));
            var output = mux(input);
            assertArrayEquals(PcmPackets.concatenate(input.order()), PcmPackets.concatenate(read(output).order()));
            assertSameDecode(source, "caf-ima4.caf", output);
        }

        @Test
        @DisplayName("writes the AAC cookie as Core Audio does, with the buffer size and bit rates of the packets")
        void aacCookie() {
            var input = demux("adts/aac.aac", AdtsDemuxer::new);
            var output = mux(input);
            assertEquals(0, ByteBuffer.wrap(output, 32, 4).getInt(), "no format flags");
            var kuki = indexOf(output, "kuki") + 12;
            assertEquals(0x03, output[kuki], "an ES_Descriptor without the esds version and flags");
            var sizes = input.order().stream().mapToInt(Packet::size).toArray();
            var rate = ((AudioTrack) input.tracks().getFirst()).sampleRate();
            var average = Arrays.stream(sizes).asLongStream().sum() * 8 * rate / (sizes.length * 1024L);
            // the most bytes of the packets that fit in one second, every start tried
            var window = Math.max(1, rate / 1024);
            var most = 0L;
            for (var start = 0; start < sizes.length; start++) {
                most = Math.max(most, Arrays.stream(sizes, start, Math.min(sizes.length, start + window))
                        .asLongStream().sum());
            }
            var fields = ByteBuffer.wrap(output, kuki + 15, 11);
            var buffer = (fields.getShort() & 0xFFFF) << 8 | fields.get() & 0xFF;
            assertEquals(Arrays.stream(sizes).max().orElseThrow(), buffer, "bufferSizeDB");
            assertEquals(Math.max(8 * most, average), fields.getInt() & 0xFFFFFFFFL, "maxBitrate");
            assertEquals(average, fields.getInt() & 0xFFFFFFFFL, "avgBitrate");
        }

        @ParameterizedTest(name = "parametric stereo {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("describes HE-AAC by its core coder and lists its layers in an ldsc chunk the demuxer reads")
        void heAac(boolean parametricStereo) {
            var input = demux("adts/aac.aac", AdtsDemuxer::new);
            var audio = (AudioTrack) input.tracks().getFirst();
            var coreChannels = parametricStereo ? 1 : 2;
            var config = AudioConfigs.audioSpecificConfig(2, 22_050, coreChannels, 44_100, parametricStereo);
            var track = audio.toBuilder().codecConfig(config).timeBase(Rational.timeBaseOf(22_050)).sampleRate(44_100)
                    .layout(ChannelLayout.STEREO).skipSamples(2048).duration(-1).build();
            var output = MuxSupport.toMemory(List.of(track), input.order(), CafMuxer::new);
            var desc = ByteBuffer.wrap(output, 20, 32);
            assertEquals(22_050.0, desc.getDouble(), "the rate of the core");
            assertEquals("aac ", new String(output, 28, 4, StandardCharsets.US_ASCII));
            assertEquals(1024, ByteBuffer.wrap(output).getInt(40), "the frames of the core");
            assertEquals(coreChannels, ByteBuffer.wrap(output).getInt(44), "the channels of the core");
            var chan = indexOf(output, "chan") + 12;
            assertEquals(parametricStereo ? 0x00640001 : 0x00650002, ByteBuffer.wrap(output, chan, 4).getInt());
            var ldsc = indexOf(output, "ldsc");
            var layers = ByteBuffer.wrap(output, ldsc + 4, output.length - ldsc - 4);
            var entries = parametricStereo ? 3 : 2;
            assertEquals(36L * entries, layers.getLong(), "ldsc size");
            var expected = new ArrayList<String>();
            if (parametricStereo) {
                expected.add("44100.0 aacp 2048 2 00650002");
            }
            expected.add("44100.0 aach 2048 " + coreChannels + " " + (parametricStereo ? "00640001" : "00650002"));
            expected.add("22050.0 aac  1024 " + coreChannels + " " + (parametricStereo ? "00640001" : "00650002"));
            var actual = new ArrayList<String>();
            for (var index = 0; index < entries; index++) {
                var rate = layers.getDouble();
                var format = new byte[4];
                layers.get(format);
                assertEquals(0, layers.getInt(), "format flags");
                assertEquals(0, layers.getInt(), "bytes per packet");
                var frames = layers.getInt();
                var channels = layers.getInt();
                assertEquals(0, layers.getInt(), "bits per channel");
                actual.add(rate + " " + new String(format, StandardCharsets.US_ASCII) + " " + frames + " " + channels
                        + " " + String.format("%08x", layers.getInt()));
            }
            assertEquals(expected, actual);
            var pakt = indexOf(output, "pakt");
            assertEquals(1024, ByteBuffer.wrap(output, pakt + 12 + 16, 4).getInt(), "priming frames of the core");
            var result = read(output);
            var reread = (AudioTrack) result.tracks().getFirst();
            assertEquals(44_100, reread.sampleRate(), "the rate of the richest layer");
            assertEquals(ChannelLayout.STEREO, reread.layout().orElseThrow());
            assertEquals(2048, reread.skipSamples());
            assertEquals(Rational.timeBaseOf(22_050), reread.timeBase());
            assertArrayEquals(config, reread.codecConfig().orElseThrow());
            assertSamePackets(input, result);
        }

        @Test
        @DisplayName("takes the richest ldsc layer whatever the order the chunk lists the layers in")
        void heAacLayerOrder() {
            var input = demux("adts/aac.aac", AdtsDemuxer::new);
            var audio = (AudioTrack) input.tracks().getFirst();
            var config = AudioConfigs.audioSpecificConfig(2, 22_050, 1, 44_100, true);
            var track = audio.toBuilder().codecConfig(config).timeBase(Rational.timeBaseOf(22_050)).sampleRate(44_100)
                    .layout(ChannelLayout.STEREO).skipSamples(2048).duration(-1).build();
            var output = MuxSupport.toMemory(List.of(track), input.order(), CafMuxer::new);
            var body = indexOf(output, "ldsc") + 12;
            var entries = new byte[3][];
            for (var index = 0; index < 3; index++) {
                entries[index] = Arrays.copyOfRange(output, body + 36 * index, body + 36 * (index + 1));
            }
            // core first, the parametric stereo layer in the middle
            for (var index = 0; index < 3; index++) {
                System.arraycopy(entries[new int[]{2, 0, 1}[index]], 0, output, body + 36 * index, 36);
            }
            assertEquals("aac ", new String(output, body + 8, 4, StandardCharsets.US_ASCII));
            var reread = (AudioTrack) read(output).tracks().getFirst();
            assertEquals(44_100, reread.sampleRate(), "the rate of the richest layer");
            assertEquals(ChannelLayout.STEREO, reread.layout().orElseThrow(), "the channels of the aacp layer");
            assertEquals(2048, reread.skipSamples());
        }

        @Test
        @DisplayName("writes a packet table of packets of constant size that describes no packet, as Core Audio does")
        void constantPacketTable() {
            var input = demux("caf/ima4.caf", CafDemuxer::new);
            var track = (AudioTrack) input.tracks().getFirst();
            var frames = input.order().stream().mapToLong(Packet::duration).sum();
            var trimmed = track.toBuilder().duration(frames - 10).build();
            var output = MuxSupport.toMemory(List.of(trimmed), input.order(), CafMuxer::new);
            var pakt = indexOf(output, "pakt");
            assertTrue(pakt > 0, "remainder frames need a packet table");
            var table = ByteBuffer.wrap(output, pakt + 4, 32);
            assertEquals(24, table.getLong(), "no entries");
            assertEquals(0, table.getLong(), "packets described");
            assertEquals(frames - 10, table.getLong(), "valid frames");
            assertEquals(0, table.getInt(), "priming frames");
            assertEquals(10, table.getInt(), "remainder frames");
            var result = read(output);
            assertEquals(frames - 10, result.tracks().getFirst().duration());
            assertEquals(PcmPackets.concatenate(input.order()).length, PcmPackets.concatenate(result.order()).length);
        }

        static Stream<Arguments> frames() {
            return Stream.of(
                    Arguments.of("mp3/cbr.mp3", Codec.MP3, ".mp3"),
                    Arguments.of("mp3/mp2-id3v1.mp2", Codec.MP2, ".mp2"),
                    Arguments.of("ac3/ac3.ac3", Codec.AC3, "ac-3"),
                    Arguments.of("ac3/eac3.eac3", Codec.EAC3, "ec-3"));
        }

        @ParameterizedTest(name = "{1}")
        @MethodSource("frames")
        @DisplayName("carries MPEG audio and Dolby frames, one per packet, as ffmpeg decodes those it maps")
        void frames(String sample, Codec codec, String format) {
            Function<MediaSource, com.github.auties00.smalt.container.Demuxer> factory = codec == Codec.MP3
                    || codec == Codec.MP2 ? MpegAudioDemuxer::new : Ac3Demuxer::new;
            var input = demux(sample, factory);
            var output = mux(input);
            assertEquals(format, new String(output, 28, 4, StandardCharsets.US_ASCII));
            var result = read(output);
            assertEquals(codec, result.tracks().getFirst().codec());
            assertSamePackets(input, result);
            var name = "caf-" + codec.name().toLowerCase() + ".caf";
            if (codec == Codec.MP3 || codec == Codec.MP2) {
                // ffmpeg trims the gapless delay of an MPEG audio file, so the frames ffprobe reads are compared
                MuxSupport.assertFfmpegClean(name, output);
                assertFfprobePackets(MuxSupport.save(name, output), input);
            } else if (codec == Codec.AC3) {
                assertSameDecode(SAMPLES.resolve(sample), name, output);
            }
            // ffmpeg's CAF demuxer maps no codec to the ec-3 format, so the E-AC-3 file is checked by the round trip
        }

        @Test
        @DisplayName("refuses packets of variable size on a streaming sink and AAC without a configuration")
        void refusals() {
            var aac = demux("adts/aac.aac", AdtsDemuxer::new).tracks().getFirst();
            var stream = MediaSink.of(new ByteArrayOutputStream());
            try (var muxer = new CafMuxer(stream)) {
                assertThrows(MediaException.Unsupported.class, () -> muxer.addTrack(aac));
            }
            var bare = ((AudioTrack) aac).toBuilder().codecConfig(null).build();
            assertThrows(IllegalArgumentException.class, () -> new CafMuxer(MediaSink.memory()).addTrack(bare));
        }

        @Test
        @DisplayName("writes a packet table of frames when the packets differ in length")
        void variableFrames() {
            // ALAC packets may code fewer frames than the frame length, which their durations then give
            var input = demux("mp4/alac.m4a", Mp4Demuxer::new);
            var length = input.order().getFirst().duration();
            var packets = new ArrayList<Packet>();
            for (var index = 0; index < input.order().size(); index++) {
                var packet = input.order().get(index);
                packets.add(packet.withTiming(Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP,
                        index % 2 == 0 ? length : length / 2));
            }
            assertTrue(packets.size() >= 3, "a packet of half length between two of full length");
            var output = MuxSupport.toMemory(List.of(input.tracks().getFirst()), packets, CafMuxer::new);
            assertEquals(0, ByteBuffer.wrap(output, 40, 4).getInt(), "frames per packet vary");
            var result = read(output);
            var start = 0L;
            for (var index = 0; index < packets.size(); index++) {
                var packet = result.order().get(index);
                assertEquals(start, packet.pts());
                assertEquals(index % 2 == 0 ? length : length / 2, packet.duration());
                start += packet.duration();
            }
        }
    }

    @Nested
    @DisplayName("channel layouts")
    class Layouts {
        static Stream<ChannelLayout> layouts() {
            return Stream.of(ChannelLayout.MONO, ChannelLayout.STEREO, ChannelLayout.SURROUND_5_1,
                    ChannelLayout.SURROUND_7_1, ChannelLayout.defaultFor(3), ChannelLayout.defaultFor(7),
                    new ChannelLayout(List.of(Speaker.FRONT_CENTER, Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT,
                            Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT, Speaker.LOW_FREQUENCY)),
                    new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.TOP_CENTER)),
                    new ChannelLayout(List.of(Speaker.LOW_FREQUENCY, Speaker.FRONT_LEFT, Speaker.BACK_LEFT,
                            Speaker.SIDE_LEFT, Speaker.WIDE_RIGHT)),
                    new ChannelLayout(List.of(Speaker.UNKNOWN, Speaker.UNKNOWN, Speaker.UNKNOWN)),
                    ChannelLayout.ofAmbisonics(Ambisonics.ofFurseMalham(4)),
                    ChannelLayout.ofAmbisonics(Ambisonics.of(1)),
                    ChannelLayout.ofAmbisonics(Ambisonics.of(2, Ambisonics.Normalization.N3D)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("layouts")
        @DisplayName("comes back as written")
        void roundTrip(ChannelLayout layout) {
            var input = PcmPackets.tone(Codec.PCM_S16LE, 48_000, layout, 64);
            var result = read(MuxSupport.toMemory(List.of(input.track()), input.packets(), CafMuxer::new));
            assertEquals(layout, ((AudioTrack) result.tracks().getFirst()).layout().orElseThrow());
        }

        @Test
        @DisplayName("uses the layout tags of mono, stereo and 5.1, and ffprobe reads them")
        void tags() {
            assertEquals(101 << 16 | 2, ByteBuffer.wrap(CafChannelLayouts.write(ChannelLayout.STEREO)).getInt());
            assertEquals(100 << 16 | 1, ByteBuffer.wrap(CafChannelLayouts.write(ChannelLayout.MONO)).getInt());
            assertEquals(121 << 16 | 6, ByteBuffer.wrap(CafChannelLayouts.write(ChannelLayout.SURROUND_5_1)).getInt());
            var input = PcmPackets.tone(Codec.PCM_S16LE, 48_000, ChannelLayout.SURROUND_5_1, 480);
            var file = MuxSupport.save("caf-51.caf", MuxSupport.toMemory(List.of(input.track()), input.packets(),
                    CafMuxer::new));
            var stream = ContainerProbe.probe(file).streams().getFirst();
            assertEquals("6", stream.get("channels"));
            assertTrue(stream.get("channel_layout").startsWith("5.1"), stream.fields().toString());
        }

        @Test
        @DisplayName("reads bitmaps and descriptions, and falls back on the default layout")
        void reading() {
            var bitmap = new ByteWriter(12);
            bitmap.u32be(CafChannelLayouts.USE_BITMAP);
            bitmap.u32be(0b1011);
            bitmap.u32be(0);
            assertEquals(new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.LOW_FREQUENCY)),
                    CafChannelLayouts.read(bitmap.toByteArray(), 3));
            assertNull(CafChannelLayouts.read(bitmap.toByteArray(), 2), "a bitmap of another channel count");
            var descriptions = new ByteWriter(52);
            descriptions.u32be(0);
            descriptions.u32be(0);
            descriptions.u32be(2);
            for (var label : new int[]{5, 33}) {
                descriptions.u32be(label);
                descriptions.zeros(16);
            }
            assertEquals(new ChannelLayout(List.of(Speaker.SIDE_LEFT, Speaker.BACK_LEFT)),
                    CafChannelLayouts.read(descriptions.toByteArray(), 2), "surround beside a rear surround");
            var unknown = new ByteWriter(12);
            unknown.u32be(999 << 16 | 2);
            unknown.zeros(8);
            assertNull(CafChannelLayouts.read(unknown.toByteArray(), 2));
            assertNull(CafChannelLayouts.read(new byte[5], 2));
        }
    }

    @Nested
    @DisplayName("info chunk")
    class Info {
        @Test
        @DisplayName("stores the tags under the CAF keys, which the demuxer and ffprobe read")
        void tags() {
            var input = PcmPackets.tone(Codec.PCM_S16BE, 8000, ChannelLayout.MONO, 80);
            var tags = MediaTags.builder().set(StandardTag.TITLE, "Título").set(StandardTag.ARTIST, "A")
                    .set(StandardTag.TRACK, "3").set(StandardTag.COMMENT, "first").add("comment", "second")
                    .set("mood", "calm").build();
            var sink = MediaSink.memory();
            try (var muxer = new CafMuxer(sink)) {
                muxer.addTrack(input.track());
                muxer.setTags(tags);
                input.packets().forEach(muxer::writePacket);
                muxer.finish();
                assertThrows(IllegalStateException.class, () -> muxer.setTags(tags));
            }
            var output = sink.toByteArray();
            var metadata = read(output).metadata();
            assertEquals("Título", metadata.get("title"));
            assertEquals("A", metadata.get("artist"));
            assertEquals("3", metadata.get("track number"));
            assertEquals("first", metadata.get("comments"));
            assertEquals("calm", metadata.get("mood"));
            var probe = ContainerProbe.probe(MuxSupport.save("caf-info.caf", output));
            assertEquals("Título", probe.format().get("tag:title"));
        }

        @Test
        @DisplayName("writes every standard tag under a CAF key that names the same standard tag when read back")
        void standardKeys() {
            var input = PcmPackets.tone(Codec.PCM_S16BE, 8000, ChannelLayout.MONO, 80);
            var standard = List.of(StandardTag.TITLE, StandardTag.ARTIST, StandardTag.ALBUM, StandardTag.TRACK,
                    StandardTag.DATE, StandardTag.COMPOSER, StandardTag.LYRICIST, StandardTag.GENRE,
                    StandardTag.COMMENT, StandardTag.COPYRIGHT, StandardTag.ENCODER, StandardTag.BPM);
            var builder = MediaTags.builder();
            for (var index = 0; index < standard.size(); index++) {
                builder.set(standard.get(index), String.valueOf(index + 1));
            }
            var sink = MediaSink.memory();
            try (var muxer = new CafMuxer(sink)) {
                muxer.addTrack(input.track());
                muxer.setTags(builder.build());
                input.packets().forEach(muxer::writePacket);
                muxer.finish();
            }
            var metadata = read(sink.toByteArray()).metadata();
            assertEquals(standard.size(), metadata.size());
            for (var entry : metadata.entrySet()) {
                var tag = StandardTag.fromName(entry.getKey()).orElseThrow(() -> new AssertionError(entry.getKey()));
                assertEquals(standard.get(Integer.parseInt(entry.getValue()) - 1), tag, entry.getKey());
            }
        }
    }

    @Nested
    @DisplayName("malformed input")
    class Malformed {
        static byte[] file(String format, long bytesPerPacket, long framesPerPacket, byte[]... chunks) {
            var writer = new ByteWriter(256);
            writer.fourCc("caff");
            writer.u16be(1);
            writer.u16be(0);
            writer.fourCc("desc");
            writer.u64be(32);
            writer.u64be(Double.doubleToLongBits(8000));
            writer.fourCc(format);
            writer.u32be(0);
            writer.u32be(bytesPerPacket);
            writer.u32be(framesPerPacket);
            writer.u32be(1);
            writer.u32be(format.equals("lpcm") ? 16 : 0);
            for (var chunk : chunks) {
                writer.bytes(chunk);
            }
            return writer.toByteArray();
        }

        static byte[] chunk(String type, byte[] body) {
            var writer = new ByteWriter(12 + body.length);
            writer.fourCc(type);
            writer.u64be(body.length);
            writer.bytes(body);
            return writer.toByteArray();
        }

        @Test
        @DisplayName("rejects files that are not CAF, of another version, or whose first chunk is not desc")
        void structure() {
            assertThrows(MediaException.Malformed.class, () -> new CafDemuxer(MediaSource.of(new byte[64])));
            var valid = file("lpcm", 2, 1, chunk("data", new byte[8]));
            var version = valid.clone();
            version[5] = 2;
            assertThrows(MediaException.Unsupported.class, () -> new CafDemuxer(MediaSource.of(version)));
            var reordered = new ByteWriter(64);
            reordered.bytes(Arrays.copyOf(valid, 8));
            reordered.bytes(chunk("free", new byte[4]));
            reordered.bytes(Arrays.copyOfRange(valid, 8, valid.length));
            assertThrows(MediaException.Malformed.class, () -> new CafDemuxer(MediaSource.of(reordered.toByteArray())));
            assertThrows(MediaException.Malformed.class, () -> new CafDemuxer(MediaSource.of(file("lpcm", 2, 1))),
                    "no data chunk");
        }

        @Test
        @DisplayName("names a format it cannot read, and rejects variable packets without a table")
        void formats() {
            var unsupported = assertThrows(MediaException.Unsupported.class, () -> new CafDemuxer(MediaSource.of(
                    file("QDM2", 0, 2048, chunk("data", new byte[8])))));
            assertEquals("caf format 'QDM2'", unsupported.format());
            assertThrows(MediaException.Malformed.class, () -> new CafDemuxer(MediaSource.of(
                    file(".mp3", 0, 1152, chunk("data", new byte[8])))));
            var table = new ByteWriter(26);
            table.u64be(1);
            table.u64be(1152);
            table.u32be(0);
            table.u32be(0);
            table.u8(0x81);
            table.u8(0x00);
            assertThrows(MediaException.Malformed.class, () -> new CafDemuxer(MediaSource.of(file(".mp3", 0, 1152,
                    chunk("pakt", table.toByteArray()), chunk("data", new byte[8])))), "a packet past the data");
        }

        @ParameterizedTest
        @ValueSource(strings = {"caf/s16.caf", "caf/alac.caf", "caf/opus.caf", "caf/ima4.caf"})
        @DisplayName("reads or rejects every truncation of a file with a MediaException")
        void truncations(String sample) throws Exception {
            var bytes = Files.readAllBytes(SAMPLES.resolve(sample));
            for (var length = 0; length < Math.min(bytes.length, 400); length++) {
                var prefix = Arrays.copyOf(bytes, length);
                try (var demuxer = new CafDemuxer(MediaSource.of(prefix))) {
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
        var output = MuxSupport.toMemory(List.of(input.track()), input.packets(), CafMuxer::new);
        assertEquals(ContainerFormat.CAF, Containers.detect(MediaSource.of(output)).orElseThrow());
        try (var demuxer = Containers.openDemuxer(MediaSource.of(output))) {
            assertInstanceOf(CafDemuxer.class, demuxer);
        }
        assertInstanceOf(CafMuxer.class, Containers.openMuxer(ContainerFormat.CAF, MediaSink.memory()));
    }

    /** Checks that the demuxer returns the packets ffprobe reads from a file of packets of variable size. */
    static void assertFfprobePackets(Path file, ContainerProbe.Demuxed demuxed) {
        var expected = ContainerProbe.packetData(file);
        assertEquals(expected.size(), demuxed.order().size(), "packet count");
        for (var index = 0; index < expected.size(); index++) {
            assertArrayEquals(expected.get(index), demuxed.order().get(index).data(), "packet " + index);
        }
    }

    /** Returns the offset of the first chunk of a type. */
    static int indexOf(byte[] file, String type) {
        var offset = 8;
        while (offset + 12 <= file.length) {
            if (new String(file, offset, 4, StandardCharsets.US_ASCII).equals(type)) {
                return offset;
            }
            var size = ByteBuffer.wrap(file, offset + 4, 8).getLong();
            if (size < 0) {
                break;
            }
            offset += 12 + (int) size;
        }
        throw new AssertionError("no " + type + " chunk");
    }
}
