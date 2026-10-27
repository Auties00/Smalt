package com.github.auties00.smalt.container.avi;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.Mutations;
import com.github.auties00.smalt.container.PcmPackets;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link AviDemuxer} and {@link AviMuxer}: files written by ffmpeg compared packet by packet
 * with ffprobe, round trips through the muxer read back by both the demuxer and ffprobe (including
 * OpenDML files split into several RIFF forms), index fallbacks, seeking and mutated input.
 */
@DisplayName("AVI")
class AviTest {
    record Sample(String name, List<String> output) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Sample> samples() {
        return Stream.of(
                new Sample("MPEG-4 Part 2 with B-frames and MP3", List.of("-c:v", "mpeg4", "-bf", "2", "-q:v", "4",
                        "-c:a", "libmp3lame", "-b:a", "128k")),
                new Sample("Xvid and PCM", List.of("-c:v", "libxvid", "-q:v", "4", "-c:a", "pcm_s16le")),
                new Sample("H.264 and AC-3", List.of("-c:v", "libx264", "-preset", "ultrafast", "-c:a", "ac3")),
                new Sample("Motion-JPEG and AAC", List.of("-c:v", "mjpeg", "-q:v", "5", "-c:a", "aac")),
                new Sample("Motion-JPEG and unsigned PCM", List.of("-c:v", "mjpeg", "-q:v", "5", "-c:a", "pcm_u8",
                        "-ac", "1")),
                new Sample("Motion JPEG 2000 and PCM", List.of("-c:v", "jpeg2000", "-pix_fmt", "yuv420p", "-c:a",
                        "pcm_s16le")),
                new Sample("Motion JPEG 2000 codestreams and MP3", List.of("-c:v", "libopenjpeg", "-format", "j2k",
                        "-pix_fmt", "yuv422p", "-c:a", "libmp3lame")));
    }

    static Path fixture(Sample sample) {
        var output = new ArrayList<>(List.of("-t", "2", "-g", "12"));
        output.addAll(sample.output());
        return Fixtures.ffmpeg("avi", List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25", "-f", "lavfi",
                "-i", "sine=frequency=440:sample_rate=44100"), output);
    }

    static ContainerProbe.Demuxed demux(Path file) {
        return ContainerProbe.demux(file, source -> new AviDemuxer(source));
    }

    static ContainerProbe.Demuxed demux(byte[] file) {
        try (var demuxer = new AviDemuxer(MediaSource.of(file))) {
            return ContainerProbe.demuxAll(demuxer);
        }
    }

    /**
     * Asserts that every non-empty packet ffprobe lists has the same payload and key flag as ours,
     * stream by stream.
     */
    static void assertMatchesProbe(Path file, ContainerProbe.Demuxed ours) {
        var probe = ContainerProbe.probe(file);
        for (var index = 0; index < ours.tracks().size(); index++) {
            var track = ours.tracks().get(index);
            var expected = probe.packetsOf(track.id() - 1).stream().filter(packet -> packet.size() > 0).toList();
            var actual = ours.packetsOf(track);
            assertEquals(expected.size(), actual.size(), "packet count of " + track.codec());
            if (track.codec() == Codec.H264 || track.codec() == Codec.HEVC) {
                // The demuxer returns AVI's Annex B chunks length-prefixed: the NAL units must be those of the file.
                assertTrue(track.codecConfig().isPresent(), "configuration of " + track.codec());
                var units = new ArrayList<byte[]>();
                for (var i = 0; i < actual.size(); i++) {
                    assertEquals(expected.get(i).key(), actual.get(i).keyframe(), "key flag " + i + " of " + track.codec());
                    var data = actual.get(i).data();
                    var end = com.github.auties00.smalt.internal.nal.NalUnits.forEachLengthPrefixed(data, 4,
                            (unit, offset, length) -> units.add(Arrays.copyOfRange(unit, offset, offset + length)));
                    assertEquals(data.length, end, "length-prefixed packet " + i + " of " + track.codec());
                }
                var raw = com.github.auties00.smalt.testkit.Oracle.run(com.github.auties00.smalt.testkit.Oracle.Tool.FFMPEG,
                        Duration.ofMinutes(1), List.of("-v", "error", "-i", file.toString(), "-map", "0:v:0", "-c", "copy",
                                "-f", track.codec() == Codec.H264 ? "h264" : "hevc", "-"));
                var reference = new ArrayList<byte[]>();
                com.github.auties00.smalt.internal.nal.NalUnits.forEachAnnexB(raw,
                        (unit, offset, length) -> reference.add(Arrays.copyOfRange(unit, offset, offset + length)));
                assertEquals(reference.size(), units.size(), "NAL units of " + track.codec());
                for (var i = 0; i < units.size(); i++) {
                    assertArrayEquals(reference.get(i), units.get(i), "NAL unit " + i + " of " + track.codec());
                }
                continue;
            }
            for (var i = 0; i < expected.size(); i++) {
                assertEquals(expected.get(i).hash(), ContainerProbe.crc32(actual.get(i).data()),
                        "payload " + i + " of " + track.codec());
                if (track instanceof VideoTrack) {
                    assertEquals(expected.get(i).key(), actual.get(i).keyframe(), "key flag " + i + " of " + track.codec());
                }
            }
        }
    }

    @Test
    @DisplayName("reads an Ambisonic B-format WAVEFORMATEXTENSIBLE as the Furse-Malham layout, whatever its mask")
    void ambisonicBFormat() {
        // ffmpeg writes four-channel PCM as WAVEFORMATEXTENSIBLE with the quad mask and KSDATAFORMAT_SUBTYPE_PCM,
        // which is swapped for KSDATAFORMAT_SUBTYPE_AMBISONIC_B_FORMAT_PCM
        var file = Fixtures.read(Fixtures.ffmpeg("avi", List.of("-f", "lavfi", "-i",
                "sine=frequency=440:sample_rate=48000:duration=0.2"), List.of("-af", "pan=quad|c0=c0|c1=c0|c2=c0|c3=c0",
                "-c:a", "pcm_s16le")));
        var standard = java.util.HexFormat.of().parseHex("0100000000001000800000aa00389b71");
        var ambisonic = java.util.HexFormat.of().parseHex("010000002107d3118644c8c1ca000000");
        var at = -1;
        for (var i = 0; i + standard.length <= file.length; i++) {
            if (Arrays.equals(file, i, i + standard.length, standard, 0, standard.length)) {
                at = i;
                break;
            }
        }
        assertTrue(at > 0, "PCM sub-format GUID");
        var plain = MuxSupport.demux(file).tracks().getFirst();
        assertEquals(ChannelLayout.defaultFor(4), ((com.github.auties00.smalt.track.AudioTrack) plain).layout()
                .orElseThrow());
        System.arraycopy(ambisonic, 0, file, at, ambisonic.length);
        var demuxed = MuxSupport.demux(file);
        var track = (com.github.auties00.smalt.track.AudioTrack) demuxed.tracks().getFirst();
        assertEquals(Codec.PCM_S16LE, track.codec());
        assertEquals(ChannelLayout.ofAmbisonics(com.github.auties00.smalt.audio.Ambisonics.ofFurseMalham(4)),
                track.layout().orElseThrow());
        assertFalse(demuxed.order().isEmpty());
    }

    @Nested
    @DisplayName("demuxer")
    class Demuxing {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.container.avi.AviTest#samples")
        @DisplayName("reads the packets ffprobe reads from ffmpeg's files")
        void ffmpegFiles(Sample sample) {
            var file = fixture(sample);
            try (var source = MediaSource.of(file)) {
                assertEquals(ContainerFormat.AVI, Containers.detect(source).orElseThrow());
            }
            var ours = demux(file);
            assertEquals(2, ours.tracks().size());
            var video = (VideoTrack) ours.tracks().getFirst();
            assertEquals(176, video.width());
            assertEquals(144, video.height());
            assertEquals(new Rational(1, 25), video.timeBase());
            assertTrue(Math.abs(ours.durationMicros() - 2_000_000) < 100_000, "duration " + ours.durationMicros());
            assertMatchesProbe(file, ours);
            var times = ours.packetsOf(video).stream().map(Packet::pts).toList();
            for (var i = 1; i < times.size(); i++) {
                assertTrue(times.get(i) > times.get(i - 1), "video timestamps increase");
            }
        }

        @ParameterizedTest(name = "into {0}")
        @org.junit.jupiter.params.provider.EnumSource(value = com.github.auties00.smalt.transcode.OutputContainer.class,
                names = {"MP4", "MATROSKA", "MPEG_TS"})
        @DisplayName("gives Annex B H.264 a configuration record, so that it remuxes into other containers")
        void annexBRemux(com.github.auties00.smalt.transcode.OutputContainer container) {
            var file = fixture(samples().toList().get(2));
            var source = demux(file);
            var video = (VideoTrack) source.tracks().getFirst();
            assertEquals(Codec.H264, video.codec());
            assertTrue(video.codecConfig().isPresent(), "configuration record");
            var output = MediaSink.memory();
            com.github.auties00.smalt.transcode.Transcoder.transcode(MediaSource.of(file), output,
                    com.github.auties00.smalt.transcode.TranscodeOptions.remux(container));
            try (var demuxer = Containers.openDemuxer(MediaSource.of(output.toByteArray()))) {
                var remuxed = demuxer.tracks().stream().filter(track -> track.codec() == Codec.H264).findFirst()
                        .orElseThrow();
                var count = 0;
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    if (packet.trackId() == remuxed.id()) {
                        count++;
                    }
                }
                assertEquals(source.packetsOf(video).size(), count, "remuxed video packets");
            }
        }

        @ParameterizedTest(name = "{0}")
        @org.junit.jupiter.params.provider.CsvSource({"eac3, EAC3", "pcm_s64le, PCM_S64LE", "dca, DTS"})
        @DisplayName("maps the extensible sub-formats ffmpeg writes for E-AC-3 and 64-bit PCM, and the DTS tag")
        void extensibleAudio(String encoder, Codec codec) {
            var options = new java.util.ArrayList<>(List.of("-t", "1", "-c:v", "mjpeg", "-c:a", encoder, "-ac", "2"));
            if (codec == Codec.DTS) {
                options.addAll(0, List.of("-strict", "-2"));
            }
            var file = Fixtures.ffmpeg("avi", List.of("-f", "lavfi", "-i", "testsrc2=size=64x48:rate=25", "-f", "lavfi",
                    "-i", "sine=frequency=440:sample_rate=48000"), options);
            var ours = demux(file);
            assertEquals(codec, ours.tracks().get(1).codec());
            assertMatchesProbe(file, ours);
            if (codec == Codec.PCM_S64LE) {
                var output = Muxing.remux(ours, AviMuxerOptions.defaults());
                var back = demux(output);
                Muxing.assertSame(ours, back);
                assertMatchesProbe(MuxSupport.save("remux-s64.avi", output), back);
            }
        }

        @Test
        @DisplayName("recovers a truncated file without index by walking the movi list")
        void truncated() {
            var file = Fixtures.read(fixture(samples().findFirst().orElseThrow()));
            var full = demux(file);
            var cut = Arrays.copyOf(file, file.length * 2 / 3);
            var partial = demux(cut);
            assertEquals(full.tracks().size(), partial.tracks().size());
            var video = full.tracks().getFirst();
            var got = partial.packetsOf(partial.tracks().getFirst());
            assertTrue(got.size() > 10, "packets " + got.size());
            for (var i = 0; i < got.size(); i++) {
                assertArrayEquals(full.packetsOf(video).get(i).data(), got.get(i).data(), "packet " + i);
            }
            assertTrue(got.getFirst().keyframe());
        }

        @Test
        @DisplayName("seeks every stream to the last key frame at or before the target")
        void seek() {
            var file = fixture(samples().findFirst().orElseThrow());
            try (var demuxer = new AviDemuxer(MediaSource.of(file))) {
                assertTrue(demuxer.isSeekable());
                demuxer.seek(1_100_000);
                var first = demuxer.read();
                var video = demuxer.tracks().getFirst();
                while (first.trackId() != video.id()) {
                    first = demuxer.read();
                }
                assertTrue(first.keyframe());
                var micros = video.timeBase().toMicros(first.pts());
                assertTrue(micros <= 1_100_000 && micros > 1_100_000 - 12 * 40_000 - 1, "landed at " + micros);
                demuxer.seek(0);
                var restart = demuxer.read();
                assertEquals(0, demuxer.tracks().stream().filter(track -> track.id() == restart.trackId())
                        .findFirst().orElseThrow().timeBase().toMicros(restart.pts()));
            }
        }

        @Test
        @DisplayName("only throws MediaException for mutated files")
        void mutations() {
            var file = Fixtures.read(fixture(samples().toList().get(1)));
            var head = Arrays.copyOf(file, Math.min(file.length, 96 * 1024));
            var variants = Mutations.variants(head, List.of(new int[]{4, 4}, new int[]{16, 4}), 0xA51, 300, false);
            var outcomes = Mutations.run(variants, Duration.ofSeconds(20), source -> {
                try (var demuxer = new AviDemuxer(source)) {
                    var count = 0;
                    while (demuxer.read() != null && count++ < 100_000) {
                        continue;
                    }
                    demuxer.seek(500_000);
                    demuxer.read();
                }
            });
            assertTrue(outcomes.containsKey("ok"), outcomes.toString());
        }

        static byte[] chunk(String id, byte[]... parts) {
            var body = new java.io.ByteArrayOutputStream();
            for (var part : parts) {
                body.writeBytes(part);
            }
            var out = java.nio.ByteBuffer.allocate(8 + body.size() + (body.size() & 1)).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            out.put(id.getBytes(StandardCharsets.ISO_8859_1)).putInt(body.size()).put(body.toByteArray());
            return out.array();
        }

        static byte[] list(String id, String type, byte[]... parts) {
            var all = new ArrayList<byte[]>();
            all.add(type.getBytes(StandardCharsets.ISO_8859_1));
            all.addAll(List.of(parts));
            return chunk(id, all.toArray(byte[][]::new));
        }

        static byte[] audioOnly(byte[] format) {
            var avih = java.nio.ByteBuffer.allocate(56).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            avih.putInt(0, 23219).putInt(16, 1).putInt(24, 1);
            var strh = java.nio.ByteBuffer.allocate(56).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            strh.put(0, "auds".getBytes(StandardCharsets.ISO_8859_1)).putInt(20, 1024).putInt(24, 44100).putInt(32, 1);
            var frame = new byte[]{0x21, 0x10, 0x04, 0x60, (byte) 0x8C, 0x1C};
            var movi = list("LIST", "movi", chunk("00wb", frame));
            var index = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            index.put("00wb".getBytes(StandardCharsets.ISO_8859_1)).putInt(0x10).putInt(4).putInt(frame.length);
            return list("RIFF", "AVI ", list("LIST", "hdrl", chunk("avih", avih.array()),
                    list("LIST", "strl", chunk("strh", strh.array()), chunk("strf", format))), movi,
                    chunk("idx1", index.array()));
        }

        @Test
        @DisplayName("reads the AudioSpecificConfig after a WAVEFORMATEXTENSIBLE header, not the header itself")
        void extensibleAac() {
            var config = new byte[]{0x12, 0x10};
            var format = java.nio.ByteBuffer.allocate(42).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            format.putShort((short) 0xFFFE).putShort((short) 2).putInt(44100).putInt(16000).putShort((short) 1)
                    .putShort((short) 0).putShort((short) 24).putShort((short) 0).putInt(3)
                    .put(new byte[]{(byte) 0xFF, 0, 0, 0, 0, 0, 0x10, 0, (byte) 0x80, 0, 0, (byte) 0xAA, 0, 0x38, (byte) 0x9B, 0x71})
                    .put(config);
            var plain = java.nio.ByteBuffer.allocate(20).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            plain.putShort((short) 0x00FF).putShort((short) 2).putInt(44100).putInt(16000).putShort((short) 1)
                    .putShort((short) 0).putShort((short) 2).put(config);
            for (var strf : List.of(format.array(), plain.array())) {
                try (var demuxer = new AviDemuxer(MediaSource.of(audioOnly(strf)))) {
                    var track = demuxer.tracks().getFirst();
                    assertEquals(Codec.AAC, track.codec());
                    assertArrayEquals(config, track.codecConfig().orElseThrow());
                }
            }
        }

        @Test
        @DisplayName("reports an unknown duration when the stream's end does not fit in microseconds")
        void hugeTimeBase() {
            // 5000 chunks of one second end at 5000 s; of 2^31 - 1 seconds each, past Long.MAX_VALUE microseconds
            try (var demuxer = new AviDemuxer(MediaSource.of(chunkedAudio(1, 1, 5000)))) {
                assertEquals(5_000_000_000L, demuxer.durationMicros());
            }
            try (var demuxer = new AviDemuxer(MediaSource.of(chunkedAudio(Integer.MAX_VALUE, 1, 5000)))) {
                assertEquals(-1, demuxer.durationMicros());
            }
        }

        // an AAC audio-only file of one-frame chunks whose stream header has the given scale and rate
        private static byte[] chunkedAudio(int scale, int rate, int count) {
            var format = java.nio.ByteBuffer.allocate(20).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            format.putShort((short) 0x00FF).putShort((short) 2).putInt(44100).putInt(16000).putShort((short) 1)
                    .putShort((short) 0).putShort((short) 2).put(new byte[]{0x12, 0x10});
            var file = audioOnly(format.array());
            var strh = indexOf(file, "auds".getBytes(StandardCharsets.ISO_8859_1));
            java.nio.ByteBuffer.wrap(file).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(strh + 20, scale)
                    .putInt(strh + 24, rate);
            var frame = new byte[]{0x21, 0x10, 0x04, 0x60, (byte) 0x8C, 0x1C};
            var chunks = new byte[count][];
            var index = java.nio.ByteBuffer.allocate(16 * count).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (var i = 0; i < count; i++) {
                chunks[i] = chunk("00wb", frame);
                index.put("00wb".getBytes(StandardCharsets.ISO_8859_1)).putInt(0x10).putInt(4 + 14 * i)
                        .putInt(frame.length);
            }
            // the hdrl list follows the RIFF header and form type
            var hdrlSize = java.nio.ByteBuffer.wrap(file).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(16);
            var body = new java.io.ByteArrayOutputStream();
            body.writeBytes(Arrays.copyOfRange(file, 12, 20 + hdrlSize + (hdrlSize & 1)));
            body.writeBytes(list("LIST", "movi", chunks));
            body.writeBytes(chunk("idx1", index.array()));
            return list("RIFF", "AVI ", body.toByteArray());
        }

        private static int indexOf(byte[] data, byte[] pattern) {
            for (var at = 0; at + pattern.length <= data.length; at++) {
                if (Arrays.equals(data, at, at + pattern.length, pattern, 0, pattern.length)) {
                    return at;
                }
            }
            throw new AssertionError("pattern not found");
        }

        @Test
        @DisplayName("rejects files that are not AVI")
        void notAvi() {
            var wave = "RIFF\0\0\0\0WAVEfmt ".getBytes(StandardCharsets.ISO_8859_1);
            assertThrows(MediaException.Malformed.class, () -> new AviDemuxer(MediaSource.of(wave)));
            assertThrows(MediaException.class, () -> new AviDemuxer(MediaSource.of(new byte[5])));
        }
    }

    @Nested
    @DisplayName("muxer")
    class Muxing {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.container.avi.AviTest#samples")
        @DisplayName("rewrites ffmpeg's files with the same payloads, read back by ffprobe and ffmpeg")
        void remux(Sample sample) {
            var source = demux(fixture(sample));
            var output = remux(source, AviMuxerOptions.defaults());
            var name = "remux-" + sample.name().replaceAll("[^A-Za-z0-9]+", "-") + ".avi";
            var saved = MuxSupport.save(name, output);
            var back = demux(output);
            assertSame(source, back);
            assertMatchesProbe(saved, back);
            MuxSupport.assertFfmpegClean(name, output);
        }

        @Test
        @DisplayName("splits large files into OpenDML RIFF forms that both demuxers read")
        void openDml() {
            var source = demux(fixture(samples().toList().get(1)));
            var output = remux(source, AviMuxerOptions.builder().riffSize(AviMuxerOptions.MIN_RIFF_SIZE).build());
            var forms = 0;
            for (var i = 0; i + 12 <= output.length; i++) {
                if (output[i] == 'A' && output[i + 1] == 'V' && output[i + 2] == 'I' && output[i + 3] == 'X'
                        && output[i - 8] == 'R' && output[i - 7] == 'I') {
                    forms++;
                }
            }
            assertTrue(forms >= 3, "AVIX forms " + forms);
            var back = demux(output);
            assertSame(source, back);
            assertMatchesProbe(MuxSupport.save("remux-opendml.avi", output), back);
            MuxSupport.assertFfmpegClean("remux-opendml.avi", output);
        }

        @Test
        @DisplayName("writes a readable file without the legacy index")
        void withoutLegacyIndex() {
            var source = demux(fixture(samples().toList().get(1)));
            var output = remux(source, AviMuxerOptions.builder().legacyIndex(false).build());
            assertFalse(new String(output, StandardCharsets.ISO_8859_1).contains("idx1"));
            assertSame(source, demux(output));
        }

        @Test
        @DisplayName("keeps PCM samples and fills video timestamp gaps with empty chunks")
        void pcmAndGaps() {
            var audio = PcmPackets.tone(Codec.PCM_S16LE, 8000, ChannelLayout.STEREO, 16_000);
            var video = VideoTrack.builder().id(7).codec(Codec.MJPEG).timeBase(new Rational(1, 1000))
                    .frameRate(new Rational(10, 1)).width(16).height(16).build();
            var jpeg = Fixtures.read(Fixtures.testImage("jpg", 16, 16, List.of()));
            var packets = new ArrayList<Packet>(audio.packets());
            for (var time : new long[]{0, 100, 200, 500, 600}) {
                packets.add(new Packet(7, jpeg, time, time, 100, true));
            }
            packets.sort((a, b) -> Long.compare(micros(a, audio.track(), video), micros(b, audio.track(), video)));
            var output = MuxSupport.toMemory(List.of(video, audio.track()), packets, AviMuxer::new);
            var back = demux(output);
            var backVideo = back.tracks().getFirst();
            var times = back.packetsOf(backVideo).stream().map(packet -> backVideo.timeBase().toMicros(packet.pts())).toList();
            assertEquals(List.of(0L, 100_000L, 200_000L, 500_000L, 600_000L), times);
            assertArrayEquals(audio.bytes(), PcmPackets.concatenate(back.packetsOf(back.tracks().get(1))));
            assertEquals(Codec.PCM_S16LE, back.tracks().get(1).codec());
        }

        @Test
        @DisplayName("needs a seekable sink and supported codecs")
        void invalid() {
            var stream = MediaSink.of(new java.io.ByteArrayOutputStream());
            assertThrows(IllegalArgumentException.class, () -> new AviMuxer(stream));
            var opus = com.github.auties00.smalt.track.AudioTrack.builder().id(1).codec(Codec.OPUS)
                    .timeBase(Rational.timeBaseOf(48000)).sampleRate(48000).layout(ChannelLayout.STEREO).build();
            assertFalse(AviMuxer.supports(Codec.OPUS));
            assertTrue(AviMuxer.supports(Codec.MPEG4_PART2));
            assertThrows(IllegalArgumentException.class, () -> new AviMuxer(MediaSink.memory()).addTrack(opus));
            assertThrows(IllegalArgumentException.class, () -> AviMuxerOptions.builder().riffSize(1000).build());
        }

        static long micros(Packet packet, Track audio, Track video) {
            var track = packet.trackId() == video.id() ? video : audio;
            return track.timeBase().toMicros(packet.dts());
        }

        static byte[] remux(ContainerProbe.Demuxed source, AviMuxerOptions options) {
            return MuxSupport.toMemory(source.tracks(), source.order(), sink -> new AviMuxer(sink, options));
        }

        static void assertSame(ContainerProbe.Demuxed expected, ContainerProbe.Demuxed actual) {
            assertEquals(expected.tracks().size(), actual.tracks().size());
            Map<Integer, Integer> map = new HashMap<>();
            for (var i = 0; i < expected.tracks().size(); i++) {
                var want = expected.tracks().get(i);
                var got = actual.tracks().get(i);
                assertEquals(want.codec(), got.codec());
                map.put(want.id(), got.id());
                if (want instanceof VideoTrack wantVideo && got instanceof VideoTrack gotVideo) {
                    assertEquals(wantVideo.width(), gotVideo.width());
                    assertEquals(wantVideo.height(), gotVideo.height());
                    var wantKeys = expected.packetsOf(want).stream().map(Packet::keyframe).toList();
                    var gotKeys = actual.packetsOf(got).stream().map(Packet::keyframe).toList();
                    assertEquals(wantKeys, gotKeys);
                }
            }
            MuxSupport.assertSamePayloads(expected, actual, map);
        }
    }
}
