package com.github.auties00.smalt.container;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.adts.AdtsMuxer;
import com.github.auties00.smalt.container.ivf.IvfMuxer;
import com.github.auties00.smalt.container.raw.RawMuxer;
import com.github.auties00.smalt.container.raw.RawMuxerOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.TrackKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the elementary-stream muxers ({@link AdtsMuxer}, {@link RawMuxer}, {@link IvfMuxer})
 * against ffmpeg's own output and decoding, and the muxer side of {@link Containers}.
 */
@DisplayName("Elementary-stream muxers")
class ElementaryMuxersTest {
    private static ContainerProbe.Demuxed only(ContainerProbe.Demuxed demuxed, TrackKind kind) {
        var track = demuxed.tracks().stream().filter(candidate -> candidate.kind() == kind).findFirst().orElseThrow();
        return new ContainerProbe.Demuxed(List.of(track), java.util.Map.of(track.id(), demuxed.packetsOf(track)),
                demuxed.packetsOf(track), demuxed.metadata(), demuxed.durationMicros());
    }

    private static byte[] ffmpeg(byte[] input, String extension, List<String> options) {
        var source = MuxSupport.save("ffmpeg-in." + extension, input);
        var arguments = new java.util.ArrayList<>(List.of("-v", "error", "-i", source.toString()));
        arguments.addAll(options);
        arguments.add("-");
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), arguments);
    }

    @Nested
    @DisplayName("ADTS")
    class Adts {
        @Test
        @DisplayName("writes the same bytes as ffmpeg's ADTS muxer and reads back through AdtsDemuxer")
        void matchesFfmpeg() {
            var input = Fixtures.read(Fixtures.tone("m4a", 440, 44_100, 2, 1, List.of("-c:a", "aac")));
            var source = MuxSupport.demux(input);
            var output = MuxSupport.toStream(source.tracks(), source.order(), AdtsMuxer::new);
            assertArrayEquals(ffmpeg(input, "m4a", List.of("-c", "copy", "-f", "adts")), output);
            var result = MuxSupport.demux(output);
            assertEquals(ContainerFormat.ADTS, Containers.detect(MediaSource.of(output)).orElseThrow());
            MuxSupport.assertSamePayloads(source, result, java.util.Map.of(source.tracks().getFirst().id(), result.tracks().getFirst().id()));
        }

        @Test
        @DisplayName("signals HE-AAC by its core object type and rejects what ADTS cannot express")
        void configurations() {
            // AOT 5 (SBR), core index 6 (24 kHz), stereo, extension index 3 (48 kHz), core AOT 2
            var heAac = new byte[]{(byte) 0x2B, 0x11, (byte) 0x88, 0x00};
            var sink = MediaSink.memory();
            try (var muxer = new AdtsMuxer(sink)) {
                muxer.addTrack(aac(heAac));
                muxer.writePacket(new Packet(0, new byte[10], 0, 0, 1024, true));
                muxer.finish();
            }
            var frame = sink.toByteArray();
            assertEquals(1, (frame[2] & 0xFF) >> 6, "LC profile");
            assertEquals(6, (frame[2] >> 2) & 0x0F, "core sampling index");
            assertEquals(17, ((frame[3] & 3) << 11) | ((frame[4] & 0xFF) << 3) | ((frame[5] & 0xFF) >> 5));
            assertThrows(IllegalArgumentException.class, () -> new AdtsMuxer(MediaSink.memory()).addTrack(aac(new byte[]{0x12, 0x00})));
            assertThrows(IllegalArgumentException.class,
                    () -> new AdtsMuxer(MediaSink.memory()).addTrack(aac(new byte[]{0x17, (byte) 0x80, 0x00, 0x00, 0x10})));
            try (var muxer = new AdtsMuxer(MediaSink.memory())) {
                muxer.addTrack(aac(new byte[]{0x12, 0x10}));
                assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(new Packet(0, new byte[9000], 0, 0, 1024, true)));
            }
        }

        private static AudioTrack aac(byte[] config) {
            return AudioTrack.builder()
                    .id(0)
                    .codec(Codec.AAC)
                    .timeBase(Rational.timeBaseOf(44_100))
                    .duration(-1)
                    .codecConfig(config)
                    .sampleRate(0)
                    .bitsPerSample(0)
                    .skipSamples(0)
                    .build();
        }
    }

    @Nested
    @DisplayName("raw streams")
    class Raw {
        @Test
        @DisplayName("writes H.264 and HEVC as Annex B that ffmpeg decodes frame for frame")
        void annexB() {
            for (var encoder : List.of(List.of("-c:v", "libx264", "-bf", "2", "-g", "10"),
                    List.of("-c:v", "libx265", "-x265-params", "log-level=none:keyint=10"))) {
                var options = new java.util.ArrayList<>(encoder);
                options.add("-an");
                var input = Fixtures.read(Fixtures.testVideo("mp4", 96, 64, 1, options));
                var source = only(MuxSupport.demux(input), TrackKind.VIDEO);
                var output = MuxSupport.toStream(source.tracks(), source.order(), RawMuxer::new);
                assertEquals(1, output[3], "starts with a start code");
                var extension = source.tracks().getFirst().codec() == Codec.H264 ? "h264" : "hevc";
                var path = MuxSupport.save("raw." + extension, output);
                var frames = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-f", extension, "-i",
                        path.toString(), "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "gray", "-"));
                assertEquals(96 * 64 * 30, frames.length, extension + " frames");
            }
        }

        @Test
        @DisplayName("writes AV1 as a low-overhead OBU stream with temporal delimiters")
        void av1() {
            var input = Fixtures.read(Fixtures.testVideo("mp4", 96, 64, 1, List.of("-c:v", "libaom-av1", "-cpu-used", "8", "-usage",
                    "realtime", "-g", "10", "-an")));
            var source = only(MuxSupport.demux(input), TrackKind.VIDEO);
            var output = MuxSupport.toStream(source.tracks(), source.order(), RawMuxer::new);
            assertEquals(0x12, output[0]);
            var path = MuxSupport.save("raw.obu", output);
            var frames = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-f", "obu", "-i", path.toString(),
                    "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "gray", "-"));
            assertEquals(96 * 64 * 30, frames.length);
        }

        @Test
        @DisplayName("writes native FLAC with the STREAMINFO counts filled in, and MP3 frames unchanged")
        void audio() {
            var flacInput = Fixtures.read(Fixtures.tone("mkv", 440, 44_100, 1, 1, List.of("-c:a", "flac")));
            var flac = MuxSupport.demux(flacInput);
            var output = MuxSupport.toMemory(flac.tracks(), flac.order(), RawMuxer::new);
            assertEquals("fLaC", new String(output, 0, 4, StandardCharsets.US_ASCII));
            assertEquals(ContainerFormat.FLAC, Containers.detect(MediaSource.of(output)).orElseThrow());
            var total = ((output[21] & 0x0FL) << 32) | (ByteBuffer.wrap(output).getInt(22) & 0xFFFFFFFFL);
            assertEquals(44_100, total);
            assertEquals(44_100, Oracle.decodeAudio(MuxSupport.save("raw.flac", output)).length);
            MuxSupport.assertFfmpegClean("raw.flac", output);
            var mp3Input = Fixtures.read(Fixtures.tone("mp3", 440, 44_100, 2, 1, List.of("-c:a", "libmp3lame", "-write_xing", "0",
                    "-id3v2_version", "0")));
            var mp3 = MuxSupport.demux(mp3Input);
            var mp3Output = MuxSupport.toStream(mp3.tracks(), mp3.order(), RawMuxer::new);
            assertArrayEquals(PcmPackets.concatenate(mp3.order()), mp3Output);
            var untagged = MuxSupport.toMemory(mp3.tracks(), mp3.order(),
                    sink -> new RawMuxer(sink, RawMuxerOptions.builder().xingTag(false).build()));
            assertArrayEquals(PcmPackets.concatenate(mp3.order()), untagged);
        }

        @Test
        @DisplayName("puts an Info or Xing tag frame with frame count, size and seek table before MP3 frames on a seekable sink")
        void xingTag() {
            for (var variable : new boolean[]{false, true}) {
                var encoder = variable ? List.of("-c:a", "libmp3lame", "-q:a", "2", "-write_xing", "0", "-id3v2_version", "0")
                        : List.of("-c:a", "libmp3lame", "-b:a", "128k", "-write_xing", "0", "-id3v2_version", "0");
                var mp3 = MuxSupport.demux(Fixtures.read(Fixtures.tone("mp3", 440, 44_100, 2, 3, encoder)));
                var frames = PcmPackets.concatenate(mp3.order());
                var output = MuxSupport.toMemory(mp3.tracks(), mp3.order(), RawMuxer::new);
                var tag = output.length - frames.length;
                assertTrue(tag > 0);
                assertArrayEquals(frames, java.util.Arrays.copyOfRange(output, tag, output.length));
                var side = 4 + 32;
                var name = new String(output, side, 4, StandardCharsets.US_ASCII);
                var constant = mp3.order().stream().map(packet -> packet.data()[2] & 0xF0).distinct().count() == 1;
                assertEquals(constant ? "Info" : "Xing", name);
                assertEquals(7, ByteBuffer.wrap(output).getInt(side + 4));
                assertEquals(mp3.order().size(), ByteBuffer.wrap(output).getInt(side + 8));
                assertEquals(output.length, ByteBuffer.wrap(output).getInt(side + 12), "byte count");
                var path = MuxSupport.save(variable ? "xing.mp3" : "info.mp3", output);
                MuxSupport.assertFfmpegClean(variable ? "xing.mp3" : "info.mp3", output);
                assertEquals(Oracle.decodeAudio(MuxSupport.save("plain.mp3", frames)).length, Oracle.decodeAudio(path).length);
            }
        }

        @Test
        @DisplayName("writes PCM samples and AMR frames behind their magic")
        void pcmAndAmr() {
            var pcm = PcmPackets.tone(Codec.PCM_S16LE, 8000, ChannelLayout.MONO, 800);
            assertArrayEquals(pcm.bytes(), MuxSupport.toStream(List.of(pcm.track()), pcm.packets(), RawMuxer::new));
            var amr = AudioTrack.builder()
                    .id(0)
                    .codec(Codec.AMR_NB)
                    .timeBase(Rational.timeBaseOf(8000))
                    .duration(-1)
                    .sampleRate(8000)
                    .layout(ChannelLayout.MONO)
                    .bitsPerSample(0)
                    .skipSamples(0)
                    .build();
            var frame = new byte[13];
            frame[0] = 0x04;
            var output = MuxSupport.toStream(List.of(amr), List.of(new Packet(0, frame, 0, 0, 160, true)), RawMuxer::new);
            assertEquals("#!AMR\n", new String(output, 0, 6, StandardCharsets.US_ASCII));
            assertEquals(6 + 13, output.length);
            assertEquals(ContainerFormat.AMR, Containers.detect(MediaSource.of(output)).orElseThrow());
        }

        @Test
        @DisplayName("rejects codecs without a raw form")
        void rejections() {
            assertFalse(RawMuxer.supports(Codec.OPUS));
            assertFalse(RawMuxer.supports(Codec.VP9));
            assertThrows(IllegalArgumentException.class, () -> new RawMuxer(MediaSink.memory()).addTrack(
                    AudioTrack.builder()
                            .id(0)
                            .codec(Codec.AAC)
                            .timeBase(Rational.timeBaseOf(8000))
                            .duration(-1)
                            .sampleRate(8000)
                            .bitsPerSample(0)
                            .skipSamples(0)
                            .build()));
        }
    }

    @Nested
    @DisplayName("IVF")
    class Ivf {
        @ParameterizedTest
        @EnumSource(value = Codec.class, names = {"VP8", "VP9", "AV1"})
        @DisplayName("rewrites an ffmpeg IVF file byte for byte")
        void matchesFfmpeg(Codec codec) {
            var encoder = switch (codec) {
                case VP8 -> List.of("-c:v", "libvpx");
                case VP9 -> List.of("-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8");
                default -> List.of("-c:v", "libaom-av1", "-cpu-used", "8", "-usage", "realtime");
            };
            var input = Fixtures.read(Fixtures.ffmpeg("ivf", List.of("-f", "lavfi", "-i", "testsrc2=size=96x64:rate=10:duration=1"), encoder));
            var source = MuxSupport.demux(input);
            var output = MuxSupport.toMemory(source.tracks(), source.order(), IvfMuxer::new);
            var result = MuxSupport.demux(output);
            MuxSupport.assertSamePayloads(source, result, java.util.Map.of(0, 0));
            assertArrayEquals(java.util.Arrays.copyOfRange(input, 32, input.length), java.util.Arrays.copyOfRange(output, 32, output.length),
                    "frames");
            assertEquals(10, ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).getInt(24), "frame count");
            MuxSupport.assertFfmpegClean("out-" + codec.displayName() + ".ivf", output);
        }
    }

    @Nested
    @DisplayName("Containers registry")
    class Registry {
        @Test
        @DisplayName("creates a muxer for every writable format and reports which codecs it carries")
        void createMuxer() {
            for (var format : ContainerFormat.values()) {
                assertInstanceOf(Muxer.class, Containers.openMuxer(format, MediaSink.memory()));
            }
            assertTrue(Containers.isMuxable(ContainerFormat.AVI, Codec.MPEG4_PART2));
            assertFalse(Containers.isMuxable(ContainerFormat.AVI, Codec.OPUS));
            assertTrue(Containers.isMuxable(ContainerFormat.MATROSKA, Codec.H264));
            assertTrue(Containers.isMuxable(ContainerFormat.MP4, Codec.AV1));
            assertTrue(Containers.isMuxable(ContainerFormat.WAV, Codec.PCM_F32LE));
            assertFalse(Containers.isMuxable(ContainerFormat.WAV, Codec.PCM_S16BE));
            assertTrue(Containers.isMuxable(ContainerFormat.AIFF, Codec.PCM_S16BE));
            assertTrue(Containers.isMuxable(ContainerFormat.OGG, Codec.VORBIS));
            assertFalse(Containers.isMuxable(ContainerFormat.OGG, Codec.FLAC));
            assertFalse(Containers.isMuxable(ContainerFormat.FLAC, Codec.MP3));
            assertThrows(IllegalArgumentException.class, () -> Containers.openMuxer(ContainerFormat.FLAC,
                    MediaSink.memory())
                    .addTrack(PcmPackets.tone(Codec.PCM_S16LE, 8000, ChannelLayout.MONO, 1).track()));
        }

        @Test
        @DisplayName("writes files that detection recognizes as their format")
        void detection() {
            var pcm = PcmPackets.tone(Codec.PCM_S16LE, 8000, ChannelLayout.MONO, 800);
            for (var format : List.of(ContainerFormat.WAV, ContainerFormat.MATROSKA)) {
                var sink = MediaSink.memory();
                MuxSupport.mux(List.of(pcm.track()), pcm.packets(), Containers.openMuxer(format, sink));
                assertEquals(format, Containers.detect(MediaSource.of(sink.toByteArray())).orElseThrow());
            }
            var big = PcmPackets.tone(Codec.PCM_S16BE, 8000, ChannelLayout.MONO, 800);
            var sink = MediaSink.memory();
            MuxSupport.mux(List.of(big.track()), big.packets(), Containers.openMuxer(ContainerFormat.AIFF, sink));
            assertEquals(ContainerFormat.AIFF, Containers.detect(MediaSource.of(sink.toByteArray())).orElseThrow());
        }
    }
}
