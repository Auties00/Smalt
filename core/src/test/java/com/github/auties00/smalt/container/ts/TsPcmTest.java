package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.testkit.Fixtures;
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

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers uncompressed audio in transport streams: Blu-ray linear PCM and SMPTE ST 302 streams written by ffmpeg
 * are read sample-exactly against ffmpeg's own decoding of them, and the PCM the muxer writes (ST 302 in plain
 * TS at 48 kHz, Blu-ray linear PCM otherwise) reads back unchanged in Smalt and in ffmpeg.
 */
@DisplayName("MPEG-TS PCM")
class TsPcmTest {
    /** Returns an aevalsrc source of a distinct tone per channel. */
    static List<String> tones(String layout, int channels, int rate, double seconds) {
        var expressions = new StringBuilder();
        for (var channel = 0; channel < channels; channel++) {
            if (channel > 0) {
                expressions.append('|');
            }
            expressions.append("0.4*sin(2*PI*").append(200 + 150 * channel).append("*t)+0.0").append(channel + 1);
        }
        return List.of("-f", "lavfi", "-i", "aevalsrc=" + expressions + ":s=" + rate + ":d=" + seconds + ":c=" + layout);
    }

    /** Returns ffmpeg's decoding of a file as raw samples of a format. */
    static byte[] ffmpegSamples(Path file, String format) {
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-i", file.toString(),
                "-map", "0:a:0", "-f", format, "-"));
    }

    /** Returns the 24-bit samples of 32-bit ffmpeg samples, dropping their low byte. */
    static byte[] top24(byte[] samples, boolean bigEndian) {
        var out = new byte[samples.length / 4 * 3];
        for (var index = 0; index < samples.length / 4; index++) {
            System.arraycopy(samples, index * 4 + (bigEndian ? 0 : 1), out, index * 3, 3);
        }
        return out;
    }

    /** Returns every packet's samples of the only audio track, concatenated. */
    static byte[] demuxedSamples(ContainerProbe.Demuxed demuxed) {
        var out = new ByteArrayOutputStream();
        var track = demuxed.tracks().getFirst();
        for (var packet : demuxed.packetsOf(track)) {
            out.writeBytes(packet.data());
        }
        return out.toByteArray();
    }

    /** Checks that packets follow each other without a gap. */
    static void assertContiguous(List<Packet> packets) {
        var expected = packets.getFirst().pts();
        assertEquals(0, expected);
        for (var packet : packets) {
            assertEquals(expected, packet.pts(), "packet timestamps follow each other");
            expected += packet.duration();
        }
    }

    static Stream<Arguments> lpcmLayouts() {
        return Stream.of(Arguments.of("mono", 1), Arguments.of("stereo", 2), Arguments.of("3.0", 3),
                Arguments.of("3.0(back)", 3), Arguments.of("4.0", 4), Arguments.of("quad(side)", 4),
                Arguments.of("5.0(side)", 5), Arguments.of("5.1(side)", 6), Arguments.of("7.0", 7), Arguments.of("7.1", 8));
    }

    @Nested
    @DisplayName("Blu-ray linear PCM from ffmpeg")
    class Lpcm {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.container.ts.TsPcmTest#lpcmLayouts")
        @DisplayName("returns ffmpeg's 16-bit samples in its channel order")
        void sixteen(String layout, int channels) {
            var file = Fixtures.ffmpeg("m2ts", tones(layout, channels, 48_000, 0.5),
                    List.of("-c:a", "pcm_bluray", "-sample_fmt", "s16", "-f", "mpegts", "-mpegts_m2ts_mode", "1"));
            var demuxed = ContainerProbe.demux(file, TsDemuxer::new);
            var track = (AudioTrack) demuxed.tracks().getFirst();
            assertEquals(Codec.PCM_S16BE, track.codec());
            assertEquals(48_000, track.sampleRate());
            assertEquals(channels, track.layout().orElseThrow().channels());
            var probe = ContainerProbe.probe(file);
            assertEquals(Integer.parseInt(probe.streams().getFirst().get("channels")), track.layout().orElseThrow().channels());
            assertArrayEquals(ffmpegSamples(file, "s16be"), demuxedSamples(demuxed));
            assertContiguous(demuxed.packetsOf(track));
        }

        @ParameterizedTest(name = "{0} at {1} Hz")
        @MethodSource("com.github.auties00.smalt.container.ts.TsPcmTest#rates")
        @DisplayName("returns ffmpeg's 24-bit samples at every rate")
        void wide(String layout, int rate, int channels) {
            var file = Fixtures.ffmpeg("m2ts", tones(layout, channels, rate, 0.3),
                    List.of("-c:a", "pcm_bluray", "-sample_fmt", "s32", "-f", "mpegts", "-mpegts_m2ts_mode", "1"));
            var demuxed = ContainerProbe.demux(file, TsDemuxer::new);
            var track = (AudioTrack) demuxed.tracks().getFirst();
            assertEquals(Codec.PCM_S24BE, track.codec());
            assertEquals(24, track.bitsPerSample());
            assertEquals(rate, track.sampleRate());
            assertArrayEquals(top24(ffmpegSamples(file, "s32be"), true), demuxedSamples(demuxed));
            assertContiguous(demuxed.packetsOf(track));
        }
    }

    static Stream<Arguments> rates() {
        return Stream.of(Arguments.of("stereo", 48_000, 2), Arguments.of("5.1(side)", 96_000, 6),
                Arguments.of("stereo", 192_000, 2), Arguments.of("7.1", 96_000, 8));
    }

    static Stream<Arguments> aes3() {
        return Stream.of(Arguments.of(2, 16), Arguments.of(4, 16), Arguments.of(6, 20), Arguments.of(8, 24),
                Arguments.of(2, 24), Arguments.of(2, 20));
    }

    @Nested
    @DisplayName("SMPTE ST 302 from ffmpeg")
    class Aes3 {
        @ParameterizedTest(name = "{0} channels of {1} bits")
        @MethodSource("com.github.auties00.smalt.container.ts.TsPcmTest#aes3")
        @DisplayName("returns ffmpeg's samples")
        void samples(int channels, int bits) {
            var layout = switch (channels) {
                case 2 -> "stereo";
                case 4 -> "quad";
                case 6 -> "5.1";
                default -> "7.1";
            };
            var output = new ArrayList<>(List.of("-c:a", "s302m", "-strict", "-2", "-sample_fmt", bits == 16 ? "s16" : "s32"));
            if (bits == 20) {
                output.addAll(List.of("-bits_per_raw_sample", "20"));
            }
            var file = Fixtures.ffmpeg("ts", tones(layout, channels, 48_000, 0.5), output);
            var demuxed = ContainerProbe.demux(file, TsDemuxer::new);
            var track = (AudioTrack) demuxed.tracks().getFirst();
            assertEquals(bits == 16 ? Codec.PCM_S16LE : Codec.PCM_S24LE, track.codec());
            assertEquals(bits, track.bitsPerSample());
            assertEquals(48_000, track.sampleRate());
            assertEquals(channels, track.layout().orElseThrow().channels());
            var expected = bits == 16 ? ffmpegSamples(file, "s16le") : top24(ffmpegSamples(file, "s32le"), false);
            assertArrayEquals(expected, demuxedSamples(demuxed));
            assertContiguous(demuxed.packetsOf(track));
        }
    }

    /** Returns a ramp of PCM frames that exercises every bit of the samples. */
    static byte[] ramp(int frames, int channels, int width, boolean littleEndian, int bits) {
        var data = new byte[frames * channels * width];
        var position = 0;
        for (var frame = 0; frame < frames; frame++) {
            for (var channel = 0; channel < channels; channel++) {
                var value = (int) ((frame * 2654435761L + channel * 40503L) >> 7);
                value = (value << (32 - bits)) >> (32 - bits);
                if (width == 3 && bits == 20) {
                    value <<= 4;
                }
                for (var index = 0; index < width; index++) {
                    var shift = 8 * (littleEndian ? index : width - 1 - index);
                    data[position++] = (byte) (value >> shift);
                }
            }
        }
        return data;
    }

    /** Converts interleaved samples between byte orders of the same width. */
    static byte[] swap(byte[] data, int width) {
        var out = new byte[data.length];
        for (var index = 0; index < data.length; index += width) {
            for (var b = 0; b < width; b++) {
                out[index + b] = data[index + width - 1 - b];
            }
        }
        return out;
    }

    static Stream<Arguments> written() {
        return Stream.of(
                Arguments.of("st302 16-bit stereo", Codec.PCM_S16LE, 48_000, ChannelLayout.STEREO, 16, false, Codec.PCM_S16LE),
                Arguments.of("st302 20-bit 5.1", Codec.PCM_S24BE, 48_000, ChannelLayout.SURROUND_5_1, 20, false, Codec.PCM_S24LE),
                Arguments.of("st302 24-bit 7.1", Codec.PCM_S24LE, 48_000, ChannelLayout.SURROUND_7_1, 24, false, Codec.PCM_S24LE),
                Arguments.of("lpcm mono", Codec.PCM_S16LE, 48_000, ChannelLayout.MONO, 16, false, Codec.PCM_S16BE),
                Arguments.of("lpcm 96 kHz", Codec.PCM_S24BE, 96_000, ChannelLayout.STEREO, 24, false, Codec.PCM_S24BE),
                Arguments.of("lpcm m2ts 3.0", Codec.PCM_S16BE, 48_000, ChannelLayout.defaultFor(3), 16, true, Codec.PCM_S16BE),
                Arguments.of("lpcm m2ts 7.1", Codec.PCM_S24LE, 48_000, ChannelLayout.SURROUND_7_1, 24, true, Codec.PCM_S24BE));
    }

    @Nested
    @DisplayName("muxer")
    class Muxer {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.container.ts.TsPcmTest#written")
        @DisplayName("writes PCM that Smalt and ffmpeg read back unchanged")
        void roundTrip(String name, Codec codec, int rate, ChannelLayout layout, int bits, boolean m2ts, Codec read) {
            var width = codec == Codec.PCM_S16LE || codec == Codec.PCM_S16BE ? 2 : 3;
            var little = codec == Codec.PCM_S16LE || codec == Codec.PCM_S24LE;
            var channels = layout.channels();
            var track = AudioTrack.builder().id(1).codec(codec).timeBase(Rational.timeBaseOf(rate)).sampleRate(rate)
                    .layout(layout).bitsPerSample(bits).build();
            var frames = rate / 2;
            var data = ramp(frames, channels, width, little, bits);
            var sink = MediaSink.memory();
            try (var muxer = new TsMuxer(sink, m2ts ? TsMuxerOptions.m2ts() : TsMuxerOptions.defaults())) {
                muxer.addTrack(track);
                var chunk = 1000;
                for (var first = 0; first < frames; first += chunk) {
                    var count = Math.min(chunk, frames - first);
                    var slice = java.util.Arrays.copyOfRange(data, first * channels * width, (first + count) * channels * width);
                    muxer.writePacket(new Packet(1, slice, first, first, count, true));
                }
                muxer.finish();
            }
            var ts = sink.toByteArray();
            var file = TsTesting.save("pcm-" + name.replace(' ', '-') + (m2ts ? ".m2ts" : ".ts"), ts);
            var demuxed = TsTesting.demux(ts);
            var back = (AudioTrack) demuxed.tracks().getFirst();
            assertEquals(read, back.codec());
            assertEquals(bits, back.bitsPerSample());
            assertEquals(rate, back.sampleRate());
            assertEquals(channels, back.layout().orElseThrow().channels());
            var readLittle = read == Codec.PCM_S16LE || read == Codec.PCM_S24LE;
            var expected = little == readLittle ? data : swap(data, width);
            assertArrayEquals(expected, demuxedSamples(demuxed));
            assertContiguous(demuxed.packetsOf(back));
            var ffmpeg = width == 2 ? ffmpegSamples(file, little ? "s16le" : "s16be")
                    : top24(ffmpegSamples(file, little ? "s32le" : "s32be"), !little);
            assertArrayEquals(data, ffmpeg, "ffmpeg's decoding of the written stream");
        }

        @Test
        @DisplayName("rejects PCM no transport stream carriage supports")
        void rejects() {
            var sink = MediaSink.memory();
            try (var muxer = new TsMuxer(sink)) {
                assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(AudioTrack.builder().id(1)
                        .codec(Codec.PCM_S16LE).timeBase(Rational.timeBaseOf(44_100)).sampleRate(44_100)
                        .layout(ChannelLayout.STEREO).build()));
                assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(AudioTrack.builder().id(2)
                        .codec(Codec.PCM_S16LE).timeBase(Rational.timeBaseOf(48_000)).sampleRate(48_000)
                        .layout(ChannelLayout.defaultFor(10)).build()));
                assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(AudioTrack.builder().id(3)
                        .codec(Codec.PCM_F32LE).timeBase(Rational.timeBaseOf(48_000)).sampleRate(48_000)
                        .layout(ChannelLayout.STEREO).build()));
            }
        }
    }

    @Test
    @DisplayName("drops packets whose header changes the format and survives truncated payloads")
    void robustness() throws Exception {
        var file = Fixtures.ffmpeg("ts", tones("stereo", 2, 48_000, 0.3),
                List.of("-c:a", "s302m", "-strict", "-2", "-sample_fmt", "s16"));
        var bytes = Files.readAllBytes(file);
        var payloads = TsWriter.pesPayloads(bytes, 0x100);
        // a second stream reusing the same PID with a 24-bit header in the middle
        var writer = new TsWriter().pat(1, 0x1000, 0).pmt(0x1000, 1, 0, 0x100, new byte[0],
                List.of(new TsWriter.Stream(0x06, 0x100, TsWriter.registration("BSSD"))));
        var time = 0L;
        var kept = 0;
        for (var index = 0; index < payloads.size(); index++) {
            var payload = payloads.get(index).clone();
            if (index == 2) {
                payload[3] = 0x20;
            } else if (index == 4) {
                payload = java.util.Arrays.copyOf(payload, 3);
            } else {
                kept += (payload.length - 4) / 5;
            }
            writer.pes(0x100, 0xBD, time, -1, payload, true);
            time += 3600;
        }
        var demuxed = TsTesting.demux(writer.toByteArray());
        assertEquals(kept * 4, demuxedSamples(demuxed).length);
    }
}
