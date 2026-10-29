package com.github.auties00.smalt.container.matroska;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.DemuxAssertions;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.internal.compress.CompressVectors;
import com.github.auties00.smalt.internal.tags.EbmlIds;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.container.DemuxerAsserts;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.zip.Deflater;

import static com.github.auties00.smalt.container.matroska.EbmlWriter.binary;
import static com.github.auties00.smalt.container.matroska.EbmlWriter.block;
import static com.github.auties00.smalt.container.matroska.EbmlWriter.file;
import static com.github.auties00.smalt.container.matroska.EbmlWriter.floating;
import static com.github.auties00.smalt.container.matroska.EbmlWriter.master;
import static com.github.auties00.smalt.container.matroska.EbmlWriter.string;
import static com.github.auties00.smalt.container.matroska.EbmlWriter.uint;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Differential tests of {@link MatroskaDemuxer} against ffprobe, on ffmpeg-generated files and on
 * hand-written EBML exercising lacing, content encodings, unknown sizes and damage. Opus
 * timestamps differ from ffprobe's by the {@code CodecDelay}, which ffprobe subtracts and this
 * demuxer leaves to the in-band pre-skip; decoding timestamps of reordering codecs are unknown in
 * Matroska and not compared.
 */
@DisplayName("MatroskaDemuxer")
class MatroskaDemuxerTest {
    record Case(String name, Supplier<Path> file) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<String> both(double seconds) {
        return List.of("-f", "lavfi", "-i", "testsrc2=size=160x120:rate=30:duration=" + seconds,
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=" + seconds);
    }

    static List<String> video(double seconds) {
        return List.of("-f", "lavfi", "-i", "testsrc2=size=96x64:rate=25:duration=" + seconds);
    }

    static List<String> audio(int rate, double seconds) {
        return List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=" + rate + ":duration=" + seconds);
    }

    static Path h264Aac(String extension, boolean bFrames) {
        return Fixtures.ffmpeg(extension, both(2), List.of("-c:v", "libx264", "-preset", "veryfast", "-g", "15",
                "-bf", bFrames ? "3" : "0", "-c:a", "aac"));
    }

    static List<Case> cases() {
        var cases = new ArrayList<Case>();
        cases.add(new Case("h264 b-frames + aac", () -> h264Aac("mkv", true)));
        cases.add(new Case("h264 no b-frames + aac", () -> h264Aac("mkv", false)));
        cases.add(new Case("hevc + aac", () -> Fixtures.ffmpeg("mkv", both(1.5),
                List.of("-c:v", "libx265", "-x265-params", "log-level=error", "-c:a", "aac"))));
        cases.add(new Case("vp9 + opus webm", () -> Fixtures.ffmpeg("webm", both(1.5),
                List.of("-c:v", "libvpx-vp9", "-b:v", "200k", "-deadline", "realtime", "-c:a", "libopus"))));
        cases.add(new Case("vp8 + vorbis webm", () -> Fixtures.ffmpeg("webm", both(1.5),
                List.of("-c:v", "libvpx", "-b:v", "200k", "-c:a", "libvorbis"))));
        cases.add(new Case("av1", () -> Fixtures.ffmpeg("mkv", video(0.5), List.of("-c:v", "libaom-av1", "-cpu-used", "8", "-b:v", "100k"))));
        cases.add(new Case("mpeg4 + mp3", () -> Fixtures.ffmpeg("mkv", both(1), List.of("-c:v", "mpeg4", "-c:a", "libmp3lame"))));
        cases.add(new Case("mjpeg + mp2", () -> Fixtures.ffmpeg("mkv", both(0.5), List.of("-c:v", "mjpeg", "-c:a", "mp2"))));
        cases.add(new Case("flac", () -> Fixtures.ffmpeg("mkv", audio(44100, 1), List.of("-c:a", "flac"))));
        cases.add(new Case("alac", () -> Fixtures.ffmpeg("mkv", audio(44100, 1), List.of("-c:a", "alac"))));
        cases.add(new Case("ac3", () -> Fixtures.ffmpeg("mkv", audio(48000, 0.6), List.of("-c:a", "ac3"))));
        cases.add(new Case("eac3", () -> Fixtures.ffmpeg("mkv", audio(48000, 0.6), List.of("-c:a", "eac3"))));
        cases.add(new Case("dts", () -> Fixtures.ffmpeg("mkv", audio(48000, 0.6), List.of("-strict", "-2", "-c:a", "dca"))));
        cases.add(new Case("truehd", () -> Fixtures.ffmpeg("mkv", audio(48000, 0.6), List.of("-strict", "-2", "-c:a",
                "truehd"))));
        for (var pcm : List.of("pcm_s16le", "pcm_s16be", "pcm_s24le", "pcm_s32le", "pcm_s64le", "pcm_f32le", "pcm_f64le",
                "pcm_u8")) {
            cases.add(new Case(pcm, () -> Fixtures.ffmpeg("mkv", audio(22050, 0.5), List.of("-ac", "2", "-c:a", pcm))));
        }
        cases.add(new Case("opus webm audio only", () -> Fixtures.ffmpeg("webm", audio(48000, 1), List.of("-c:a", "libopus"))));
        cases.add(new Case("live webm", () -> Fixtures.ffmpeg("webm", both(2),
                List.of("-c:v", "libvpx-vp9", "-b:v", "200k", "-deadline", "realtime", "-c:a", "libopus",
                        "-live", "1", "-cluster_size_limit", "4000", "-cluster_time_limit", "200"))));
        cases.add(new Case("tags + subtitles", () -> Fixtures.ffmpeg("mkv", List.of(
                        "-f", "lavfi", "-i", "testsrc2=size=160x120:rate=30:duration=1",
                        "-i", com.github.auties00.smalt.container.TestFiles.subtitles().toString()),
                List.of("-map", "0", "-map", "1", "-c:v", "libx264", "-c:s", "srt", "-metadata", "title=Matroska title",
                        "-metadata", "artist=Someone"))));
        return cases;
    }

    static ContainerProbe.Demuxed demux(Path file) {
        return ContainerProbe.demux(file, source -> new MatroskaDemuxer(source));
    }

    static ContainerProbe.Demuxed demux(byte[] data) {
        try (var source = MediaSource.of(data); var demuxer = new MatroskaDemuxer(source)) {
            return ContainerProbe.demuxAll(demuxer);
        }
    }

    /** The difference between our timestamps and ffprobe's: the CodecDelay ffprobe subtracts. */
    static long expectedOffset(Track track) {
        if (!(track instanceof AudioTrack audio)) {
            return 0;
        }
        if (audio.codec() == Codec.OPUS && audio.codecConfig().isPresent()) {
            var head = audio.codecConfig().orElseThrow();
            var preSkip = (head[10] & 0xFF) | (head[11] & 0xFF) << 8;
            return Math.round(preSkip / 48.0);
        }
        return audio.sampleRate() == 0 ? 0 : Math.round(audio.skipSamples() * 1000.0 / audio.sampleRate());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("matches ffprobe packet by packet")
    void matchesFfprobe(Case fixture) {
        var file = fixture.file().get();
        var probe = ContainerProbe.probe(file);
        var ours = demux(file);
        DemuxAssertions.assertPacketsMatch(DemuxAssertions.inProbeTimeBase(ours, probe), probe, MatroskaDemuxerTest::expectedOffset, false);
        DemuxAssertions.assertStreamsMatch(ours.tracks(), probe);
        for (var index = 0; index < ours.tracks().size(); index++) {
            var track = ours.tracks().get(index);
            var stream = probe.streams().get(index);
            var rate = track instanceof AudioTrack audio ? (track.codec() == Codec.OPUS ? 48_000 : audio.sampleRate()) : 1000;
            assertEquals(rate, track.timeBase().denominator(), "sample or millisecond time base");
            var config = track.codecConfig().orElse(new byte[0]);
            switch (track.codec()) {
                case H264, HEVC, AV1, AAC, OPUS, VORBIS, MPEG4_PART2 -> assertArrayEquals(stream.extradata(), config, "codec config");
                case ALAC -> assertArrayEquals(Arrays.copyOfRange(stream.extradata(), 12, 36), config, "ALAC config");
                case FLAC -> assertTrue(Arrays.equals(stream.extradata(), config)
                        || Arrays.equals(stream.extradata(), Arrays.copyOfRange(config, 8, 42)), "FLAC config");
                default -> {
                }
            }
            for (var packet : ours.packetsOf(track)) {
                if (track.codec() == Codec.H264 || track.codec() == Codec.HEVC || track.codec() == Codec.MPEG4_PART2) {
                    assertEquals(Packet.NO_TIMESTAMP, packet.dts());
                } else {
                    assertEquals(packet.pts(), packet.dts());
                }
            }
        }
    }

    @Nested
    @DisplayName("headers")
    class Headers {
        @Test
        @DisplayName("reports tags, title, duration and the doc type")
        void metadata() {
            var file = cases().stream().filter(item -> item.name().equals("tags + subtitles")).findFirst().orElseThrow().file().get();
            var ours = demux(file);
            assertEquals("Matroska title", ours.metadata().get("title"));
            assertEquals("Someone", ours.metadata().get("ARTIST"));
            assertEquals(1_000_000, ours.durationMicros(), 50_000);
            var subtitles = ours.tracks().get(1);
            assertEquals(Codec.SUBRIP, subtitles.codec());
            try (var source = MediaSource.of(file); var demuxer = new MatroskaDemuxer(source)) {
                assertEquals(MatroskaDocType.MATROSKA, demuxer.docType());
            }
        }

        @Test
        @DisplayName("maps color and frame rate")
        void video() {
            var file = Fixtures.ffmpeg("mkv", MatroskaDemuxerTest.video(0.4), List.of("-c:v", "libx264", "-color_range", "pc",
                    "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709"));
            var track = assertInstanceOf(VideoTrack.class, demux(file).tracks().getFirst());
            assertEquals(25, track.frameRate().orElseThrow().toDouble(), 1e-9);
            var color = track.color().orElseThrow();
            assertEquals(com.github.auties00.smalt.image.ColorRange.FULL, color.range());
            assertEquals(com.github.auties00.smalt.image.MatrixCoefficients.BT709, color.matrix());
            assertEquals(96, track.width());
        }

        @Test
        @DisplayName("rejects inputs that are not Matroska")
        void rejects() {
            assertThrows(MediaException.Malformed.class, () -> demux(new byte[0]));
            assertThrows(MediaException.Malformed.class, () -> demux(new byte[]{0, 0, 0, 0x20, 'f', 't', 'y', 'p'}));
            var other = file("dvd", false, master(EbmlIds.TRACKS));
            assertThrows(MediaException.Unsupported.class, () -> demux(other));
            var noTracks = file("webm", false, master(EbmlIds.INFO, uint(EbmlIds.TIMESTAMP_SCALE, 1_000_000)));
            assertThrows(MediaException.Malformed.class, () -> demux(noTracks));
            // a SeekHead whose Tracks entry points back at the SeekHead itself, then far past the end
            for (var position : List.of(0L, 1L << 40)) {
                var cyclic = file("webm", false, master(EbmlIds.SEEK_HEAD, master(EbmlIds.SEEK,
                        binary(EbmlIds.SEEK_ID, EbmlWriter.encodeId(EbmlIds.TRACKS)), uint(EbmlIds.SEEK_POSITION, position))),
                        master(EbmlIds.CLUSTER, uint(EbmlIds.TIMESTAMP, 0)));
                assertThrows(MediaException.Malformed.class, () -> demux(cyclic));
            }
        }
    }

    @Nested
    @DisplayName("bounds")
    class Bounds {
        @Test
        @DisplayName("honors a custom TimestampScale")
        void timestampScale() {
            var data = file("matroska", false, master(EbmlIds.INFO, uint(EbmlIds.TIMESTAMP_SCALE, 100_000),
                            floating(EbmlIds.DURATION, 20_000), string(EbmlIds.TITLE, "scaled")),
                    pcmTrack(1, 0),
                    master(EbmlIds.CLUSTER, uint(EbmlIds.TIMESTAMP, 1000),
                            binary(EbmlIds.SIMPLE_BLOCK, block(1, 5, 0x80, new byte[4])),
                            master(EbmlIds.BLOCK_GROUP, binary(EbmlIds.BLOCK, block(1, 205, 0, new byte[4])),
                                    uint(EbmlIds.BLOCK_DURATION, 200))));
            var ours = demux(data);
            // ticks of 100 us rescaled to the 8 kHz sample period: 1005 ticks = 100.5 ms = 804 samples
            assertEquals(new com.github.auties00.smalt.track.Rational(1, 8000), ours.tracks().getFirst().timeBase());
            assertEquals(List.of(804L, 964L), ours.order().stream().map(Packet::pts).toList());
            assertEquals(160, ours.order().get(1).duration());
            assertEquals(2_000_000, ours.durationMicros());
            assertEquals("scaled", ours.metadata().get("title"));
        }

        @Test
        @DisplayName("bounds header elements by the allocation limit and nested tags by depth")
        void limits() {
            var small = MediaLimits.builder().maxDimension(4096).maxPixels(1 << 20).maxFrames(1000).maxChannels(8)
                    .maxBytes(1024).build();
            var bigTracks = file("matroska", false, pcmTrack(1, 0, binary(EbmlIds.CODEC_PRIVATE, new byte[4000])));
            assertThrows(MediaException.LimitExceeded.class, () -> {
                try (var source = MediaSource.of(bigTracks); var demuxer = new MatroskaDemuxer(source,
                        DemuxerOptions.builder().limits(small).build())) {
                    demuxer.read();
                }
            });
            EbmlWriter.Node tag = master(EbmlIds.SIMPLE_TAG, string(EbmlIds.TAG_NAME, "LEAF"), string(EbmlIds.TAG_STRING, "deep"));
            for (var depth = 0; depth < 200; depth++) {
                tag = master(EbmlIds.SIMPLE_TAG, string(EbmlIds.TAG_NAME, "N"), tag);
            }
            var nested = file("matroska", false, pcmTrack(1, 0), master(EbmlIds.TAGS, master(EbmlIds.TAG, master(EbmlIds.TARGETS), tag)));
            var ours = demux(nested);
            assertEquals(1, ours.tracks().size());
            assertTrue(ours.metadata().keySet().stream().allMatch(key -> key.length() < 64), "nesting stops at the depth bound");
        }

        @Test
        @DisplayName("synthesizes an AudioSpecificConfig for legacy AAC identifiers")
        void legacyAac() {
            var cases = List.of(
                    new Object[]{"A_AAC/MPEG4/LC", 48000.0, 0.0, 2, new byte[]{0x11, (byte) 0x90}},
                    new Object[]{"A_AAC/MPEG2/MAIN", 44100.0, 0.0, 1, new byte[]{0x0A, 0x08}},
                    new Object[]{"A_AAC/MPEG4/LC/SBR", 24000.0, 48000.0, 2, new byte[]{0x13, 0x10, 0x56, (byte) 0xE5, (byte) 0x98}},
                    new Object[]{"A_AAC", 32000.0, 0.0, 6, new byte[]{0x12, (byte) 0xB0}});
            for (var item : cases) {
                var audio = new ArrayList<EbmlWriter.Node>(List.of(floating(EbmlIds.SAMPLING_FREQUENCY, (double) item[1]),
                        uint(EbmlIds.CHANNELS, (int) item[3])));
                if ((double) item[2] > 0) {
                    audio.add(floating(EbmlIds.OUTPUT_SAMPLING_FREQUENCY, (double) item[2]));
                }
                var data = file("matroska", false, master(EbmlIds.TRACKS, master(EbmlIds.TRACK_ENTRY, uint(EbmlIds.TRACK_NUMBER, 1),
                        uint(EbmlIds.TRACK_TYPE, 2), string(EbmlIds.CODEC_ID, (String) item[0]), master(EbmlIds.AUDIO, audio))));
                var track = assertInstanceOf(AudioTrack.class, demux(data).tracks().getFirst());
                assertEquals(Codec.AAC, track.codec(), (String) item[0]);
                assertArrayEquals((byte[]) item[4], track.codecConfig().orElseThrow(), (String) item[0]);
            }
        }

        @Test
        @DisplayName("marks blocks with a ReferenceBlock as non-key")
        void referenceBlock() {
            var data = file("matroska", false, pcmTrack(1, 0),
                    master(EbmlIds.CLUSTER, uint(EbmlIds.TIMESTAMP, 0),
                            master(EbmlIds.BLOCK_GROUP, binary(EbmlIds.BLOCK, block(1, 0, 0, new byte[]{1}))),
                            master(EbmlIds.BLOCK_GROUP, binary(EbmlIds.BLOCK, block(1, 10, 0, new byte[]{2})),
                                    binary(EbmlIds.REFERENCE_BLOCK, new byte[]{(byte) 0xF6})),
                            binary(EbmlIds.SIMPLE_BLOCK, block(1, 20, 0x00, new byte[]{3})),
                            binary(EbmlIds.SIMPLE_BLOCK, block(1, 30, 0x80, new byte[]{4}))));
            assertEquals(List.of(true, false, false, true), demux(data).order().stream().map(Packet::keyframe).toList());
        }

        @Test
        @DisplayName("skips blocks of undeclared tracks and unknown elements")
        void unknownTracks() {
            var data = file("matroska", false, pcmTrack(1, 0),
                    master(EbmlIds.CLUSTER, uint(EbmlIds.TIMESTAMP, 0), binary(0x4ABC, new byte[]{1, 2, 3}),
                            binary(EbmlIds.SIMPLE_BLOCK, block(9, 0, 0x80, new byte[4])),
                            binary(EbmlIds.SIMPLE_BLOCK, block(1, 1, 0x80, new byte[]{7}))));
            var packets = demux(data).order();
            assertEquals(1, packets.size());
            assertArrayEquals(new byte[]{7}, packets.getFirst().data());
        }
    }

    @Nested
    @DisplayName("track entries")
    class TrackEntries {
        static byte[] alacFile(byte[] codecPrivate) {
            return file("matroska", false, master(EbmlIds.TRACKS, master(EbmlIds.TRACK_ENTRY, uint(EbmlIds.TRACK_NUMBER, 1),
                    uint(EbmlIds.TRACK_TYPE, 2), string(EbmlIds.CODEC_ID, "A_ALAC"), binary(EbmlIds.CODEC_PRIVATE, codecPrivate),
                    master(EbmlIds.AUDIO, floating(EbmlIds.SAMPLING_FREQUENCY, 44100), uint(EbmlIds.CHANNELS, 2)))));
        }

        static byte[] alacConfig() {
            var config = new byte[24];
            for (var i = 0; i < config.length; i++) {
                config[i] = (byte) (i + 1);
            }
            return config;
        }

        @Test
        @DisplayName("reads the ALACSpecificConfig of every magic cookie layout")
        void alacCookies() {
            var config = alacConfig();
            var versioned = new byte[28];
            System.arraycopy(config, 0, versioned, 4, 24);
            // version and flags, the configuration, then a 24-byte channel layout atom
            var withLayout = new byte[52];
            System.arraycopy(config, 0, withLayout, 4, 24);
            java.util.Arrays.fill(withLayout, 28, 52, (byte) 0x77);
            var atom = new byte[36];
            atom[3] = 36;
            atom[4] = 'a';
            atom[5] = 'l';
            atom[6] = 'a';
            atom[7] = 'c';
            System.arraycopy(config, 0, atom, 12, 24);
            for (var cookie : List.of(config, versioned, withLayout, atom)) {
                var track = demux(alacFile(cookie)).tracks().getFirst();
                assertArrayEquals(config, track.codecConfig().orElseThrow(), cookie.length + "-byte cookie");
            }
        }

        @Test
        @DisplayName("rejects an ALAC cookie too short to hold a configuration")
        void shortAlacCookie() {
            assertThrows(MediaException.Malformed.class, () -> demux(alacFile(new byte[20])));
        }

        @Test
        @DisplayName("reports the ISO 639-2 bibliographic code of a LanguageBCP47 over Language")
        void languageBcp47() {
            for (var item : List.of(new String[]{"fr-CA", "fre"}, new String[]{"de", "ger"}, new String[]{"ja-JP", "jpn"},
                    new String[]{"und", null}, new String[]{"zxx-Latn", "zxx"})) {
                var data = file("matroska", false, pcmTrack(1, 0, string(EbmlIds.LANGUAGE, "eng"),
                        string(EbmlIds.LANGUAGE_BCP47, item[0])));
                assertEquals(java.util.Optional.ofNullable(item[1]), demux(data).tracks().getFirst().language(), item[0]);
            }
            var plain = file("matroska", false, pcmTrack(1, 0, string(EbmlIds.LANGUAGE, "spa")));
            assertEquals(java.util.Optional.of("spa"), demux(plain).tracks().getFirst().language());
        }

        @Test
        @DisplayName("keeps a disabled track but lists it after the enabled ones")
        void disabledTrack() {
            var data = file("matroska", false, master(EbmlIds.TRACKS,
                    master(EbmlIds.TRACK_ENTRY, uint(EbmlIds.TRACK_NUMBER, 1), uint(EbmlIds.TRACK_TYPE, 2),
                            uint(EbmlIds.FLAG_ENABLED, 0), string(EbmlIds.CODEC_ID, "A_PCM/INT/LIT"),
                            master(EbmlIds.AUDIO, floating(EbmlIds.SAMPLING_FREQUENCY, 8000), uint(EbmlIds.CHANNELS, 1), uint(EbmlIds.BIT_DEPTH, 16))),
                    master(EbmlIds.TRACK_ENTRY, uint(EbmlIds.TRACK_NUMBER, 2), uint(EbmlIds.TRACK_TYPE, 2),
                            string(EbmlIds.CODEC_ID, "A_PCM/INT/LIT"),
                            master(EbmlIds.AUDIO, floating(EbmlIds.SAMPLING_FREQUENCY, 8000), uint(EbmlIds.CHANNELS, 1), uint(EbmlIds.BIT_DEPTH, 16)))));
            assertEquals(List.of(2, 1), demux(data).tracks().stream().map(Track::id).toList());
        }

        @Test
        @DisplayName("treats tags whose unique identifiers are zero as global")
        void zeroUidTags() {
            var tag = master(EbmlIds.TAG, master(EbmlIds.TARGETS, uint(EbmlIds.TAG_TRACK_UID, 0)),
                    master(EbmlIds.SIMPLE_TAG, string(EbmlIds.TAG_NAME, "ARTIST"), string(EbmlIds.TAG_STRING, "All")));
            var trackTag = master(EbmlIds.TAG, master(EbmlIds.TARGETS, uint(EbmlIds.TAG_TRACK_UID, 1)),
                    master(EbmlIds.SIMPLE_TAG, string(EbmlIds.TAG_NAME, "TITLE"), string(EbmlIds.TAG_STRING, "One")));
            var data = file("matroska", false, pcmTrack(1, 0), master(EbmlIds.TAGS, tag, trackTag));
            var ours = demux(data);
            assertEquals("All", ours.metadata().get("ARTIST"));
            assertTrue(!ours.metadata().containsKey("TITLE"));
        }
    }

    static EbmlWriter.Node pcmTrack(int number, long defaultDuration, EbmlWriter.Node... extra) {
        var children = new ArrayList<EbmlWriter.Node>(List.of(uint(EbmlIds.TRACK_NUMBER, number), uint(EbmlIds.TRACK_UID, number),
                uint(EbmlIds.TRACK_TYPE, 2), string(EbmlIds.CODEC_ID, "A_PCM/INT/LIT"),
                master(EbmlIds.AUDIO, floating(EbmlIds.SAMPLING_FREQUENCY, 8000), uint(EbmlIds.CHANNELS, 1), uint(EbmlIds.BIT_DEPTH, 16))));
        if (defaultDuration > 0) {
            children.add(uint(EbmlIds.DEFAULT_DURATION, defaultDuration));
        }
        children.addAll(List.of(extra));
        return master(EbmlIds.TRACKS, master(EbmlIds.TRACK_ENTRY, children));
    }

    static List<byte[]> frames(int... sizes) {
        var frames = new ArrayList<byte[]>();
        var seed = 1;
        for (var size : sizes) {
            var frame = new byte[size];
            for (var index = 0; index < size; index++) {
                seed = seed * 1103515245 + 12345;
                frame[index] = (byte) (seed >>> 16);
            }
            frames.add(frame);
        }
        return frames;
    }

    static Path write(String name, byte[] data) {
        try {
            var directory = Files.createDirectories(Path.of("target", "matroska-handmade"));
            var path = directory.resolve(name + ".mka");
            Files.write(path, data);
            return path;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Nested
    @DisplayName("lacing")
    class Lacing {
        byte[] laced(int lacingFlag, byte[] lace, long defaultDuration) {
            return file("matroska", false, master(EbmlIds.INFO, uint(EbmlIds.TIMESTAMP_SCALE, 1_000_000)),
                    pcmTrack(1, defaultDuration),
                    master(EbmlIds.CLUSTER, uint(EbmlIds.TIMESTAMP, 100),
                            binary(EbmlIds.SIMPLE_BLOCK, block(1, 0, 0x80 | lacingFlag, lace)),
                            binary(EbmlIds.SIMPLE_BLOCK, block(1, 100, 0x80, frames(64).getFirst()))));
        }

        void assertLaced(String name, byte[] data, List<byte[]> frames) {
            var ours = demux(data);
            var packets = ours.order();
            assertEquals(frames.size() + 1, packets.size());
            for (var index = 0; index < frames.size(); index++) {
                assertArrayEquals(frames.get(index), packets.get(index).data(), "frame " + index);
                // the 8 kHz track counts samples: the 100 ms block timestamp is 800, a 20 ms frame 160
                assertEquals(800 + 160L * index, packets.get(index).pts(), "interpolated timestamp of frame " + index);
                assertEquals(160, packets.get(index).duration());
                assertTrue(packets.get(index).keyframe());
            }
            var probe = ContainerProbe.probe(write(name, data));
            DemuxAssertions.assertPacketsMatch(DemuxAssertions.inProbeTimeBase(ours, probe), probe, track -> 0, false);
        }

        @Test
        @DisplayName("splits Xiph lacing with sizes above 255")
        void xiph() {
            var frames = frames(320, 600, 17, 255, 510);
            assertLaced("xiph", laced(0x02, EbmlWriter.xiphLace(frames), 20_000_000), frames);
        }

        @Test
        @DisplayName("splits EBML lacing with signed size differences")
        void ebml() {
            var frames = frames(320, 200, 2000, 1, 160, 90);
            assertLaced("ebml", laced(0x06, EbmlWriter.ebmlLace(frames), 20_000_000), frames);
        }

        @Test
        @DisplayName("splits fixed-size lacing")
        void fixed() {
            var frames = frames(160, 160, 160, 160);
            assertLaced("fixed", laced(0x04, EbmlWriter.fixedLace(frames), 20_000_000), frames);
        }

        @Test
        @DisplayName("divides the block duration among laced frames without a default duration")
        void blockDuration() {
            var frames = frames(100, 100, 100, 100);
            var data = file("matroska", false, master(EbmlIds.INFO, uint(EbmlIds.TIMESTAMP_SCALE, 1_000_000)),
                    pcmTrack(1, 0),
                    master(EbmlIds.CLUSTER, uint(EbmlIds.TIMESTAMP, 0),
                            master(EbmlIds.BLOCK_GROUP, binary(EbmlIds.BLOCK, block(1, 0, 0x04, EbmlWriter.fixedLace(frames))),
                                    uint(EbmlIds.BLOCK_DURATION, 80))));
            var packets = demux(data).order();
            assertEquals(List.of(0L, 160L, 320L, 480L), packets.stream().map(Packet::pts).toList());
            assertTrue(packets.stream().allMatch(packet -> packet.duration() == 160));
        }

        @Test
        @DisplayName("skips blocks whose lacing does not fit")
        void invalid() {
            var data = file("matroska", false, master(EbmlIds.INFO, uint(EbmlIds.TIMESTAMP_SCALE, 1_000_000)),
                    pcmTrack(1, 20_000_000),
                    master(EbmlIds.CLUSTER, uint(EbmlIds.TIMESTAMP, 0),
                            binary(EbmlIds.SIMPLE_BLOCK, block(1, 0, 0x82, new byte[]{3, (byte) 255, (byte) 255, 1, 2})),
                            binary(EbmlIds.SIMPLE_BLOCK, block(1, 0, 0x84, new byte[]{2, 1, 2, 3, 4})),
                            binary(EbmlIds.SIMPLE_BLOCK, block(1, 20, 0x80, new byte[]{9}))));
            var packets = demux(data).order();
            assertEquals(1, packets.size());
            assertEquals(160, packets.getFirst().pts());
        }
    }

    @Nested
    @DisplayName("content encodings")
    class Encodings {
        static List<byte[]> mp3Frames() {
            var mp3 = Fixtures.read(Fixtures.ffmpeg("mp3", audio(44100, 0.5), List.of("-c:a", "libmp3lame", "-b:a", "128k", "-write_xing", "0",
                    "-id3v2_version", "0")));
            var frames = new ArrayList<byte[]>();
            var position = 0;
            while (position + 4 <= mp3.length) {
                var b1 = mp3[position + 1] & 0xFF;
                var b2 = mp3[position + 2] & 0xFF;
                if ((mp3[position] & 0xFF) != 0xFF || (b1 & 0xE0) != 0xE0) {
                    break;
                }
                var bitrate = new int[]{0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320}[b2 >> 4];
                var length = 144 * bitrate * 1000 / 44100 + ((b2 >> 1) & 1);
                frames.add(Arrays.copyOfRange(mp3, position, Math.min(mp3.length, position + length)));
                position += length;
            }
            return frames;
        }

        @Test
        @DisplayName("restores header-stripped frames")
        void headerStripping() {
            var frames = mp3Frames();
            assertTrue(frames.size() > 5);
            var stripped = Arrays.copyOf(frames.getFirst(), 2);
            var blocks = new ArrayList<EbmlWriter.Node>();
            blocks.add(uint(EbmlIds.TIMESTAMP, 0));
            for (var index = 0; index < frames.size(); index++) {
                var frame = frames.get(index);
                blocks.add(binary(EbmlIds.SIMPLE_BLOCK, block(1, (int) Math.round(index * 1152 * 1000.0 / 44100), 0x80,
                        Arrays.copyOfRange(frame, 2, frame.length))));
            }
            var data = file("matroska", false, master(EbmlIds.INFO, uint(EbmlIds.TIMESTAMP_SCALE, 1_000_000)),
                    master(EbmlIds.TRACKS, master(EbmlIds.TRACK_ENTRY, uint(EbmlIds.TRACK_NUMBER, 1), uint(EbmlIds.TRACK_UID, 7),
                            uint(EbmlIds.TRACK_TYPE, 2), string(EbmlIds.CODEC_ID, "A_MPEG/L3"),
                            master(EbmlIds.AUDIO, floating(EbmlIds.SAMPLING_FREQUENCY, 44100), uint(EbmlIds.CHANNELS, 1)),
                            master(EbmlIds.CONTENT_ENCODINGS, master(EbmlIds.CONTENT_ENCODING,
                                    master(EbmlIds.CONTENT_COMPRESSION, uint(EbmlIds.CONTENT_COMP_ALGO, 3),
                                            binary(EbmlIds.CONTENT_COMP_SETTINGS, stripped)))))),
                    master(EbmlIds.CLUSTER, blocks));
            var ours = demux(data);
            assertEquals(Codec.MP3, ours.tracks().getFirst().codec());
            var packets = ours.order();
            assertEquals(frames.size(), packets.size());
            for (var index = 0; index < frames.size(); index++) {
                assertArrayEquals(frames.get(index), packets.get(index).data());
            }
            var probe = ContainerProbe.probe(write("header-stripping", data));
            DemuxAssertions.assertPacketsMatch(DemuxAssertions.inProbeTimeBase(ours, probe), probe, track -> 0, false);
        }

        @Test
        @DisplayName("inflates zlib-compressed frames")
        void zlib() {
            var frames = frames(320, 320, 640, 100);
            var blocks = new ArrayList<EbmlWriter.Node>();
            blocks.add(uint(EbmlIds.TIMESTAMP, 0));
            for (var index = 0; index < frames.size(); index++) {
                var deflater = new Deflater();
                deflater.setInput(frames.get(index));
                deflater.finish();
                var compressed = new byte[4096];
                var length = deflater.deflate(compressed);
                deflater.end();
                blocks.add(binary(EbmlIds.SIMPLE_BLOCK, block(1, index * 20, 0x80, Arrays.copyOf(compressed, length))));
            }
            var data = file("matroska", false, master(EbmlIds.INFO, uint(EbmlIds.TIMESTAMP_SCALE, 1_000_000)),
                    pcmTrack(1, 0, master(EbmlIds.CONTENT_ENCODINGS, master(EbmlIds.CONTENT_ENCODING,
                            uint(EbmlIds.CONTENT_ENCODING_SCOPE, 1),
                            master(EbmlIds.CONTENT_COMPRESSION, uint(EbmlIds.CONTENT_COMP_ALGO, 0))))),
                    master(EbmlIds.CLUSTER, blocks));
            var ours = demux(data);
            for (var index = 0; index < frames.size(); index++) {
                assertArrayEquals(frames.get(index), ours.order().get(index).data());
            }
            var probe = ContainerProbe.probe(write("zlib", data));
            DemuxAssertions.assertPacketsMatch(DemuxAssertions.inProbeTimeBase(ours, probe), probe, track -> 0, false);
        }

        /**
         * Builds a PCM track whose frames are the frame-* streams of the compression vectors, compressed with one
         * algorithm, and checks the demuxed frames against the SHA-256 of their content and against ffprobe, which
         * reads bzlib and LZO1X tracks with decoders of its own.
         */
        void compressedFrames(String format, String extension, int algorithm) {
            var vectors = CompressVectors.of(format).stream().filter(vector -> vector.name().startsWith("frame-"))
                    .toList();
            assertEquals(15, vectors.size());
            var blocks = new ArrayList<EbmlWriter.Node>();
            blocks.add(uint(EbmlIds.TIMESTAMP, 0));
            for (var index = 0; index < vectors.size(); index++) {
                assertTrue(vectors.get(index).name().endsWith(extension));
                blocks.add(binary(EbmlIds.SIMPLE_BLOCK, block(1, index * 20, 0x80, vectors.get(index).stream())));
            }
            var data = file("matroska", false, master(EbmlIds.INFO, uint(EbmlIds.TIMESTAMP_SCALE, 1_000_000)),
                    pcmTrack(1, 0, master(EbmlIds.CONTENT_ENCODINGS, master(EbmlIds.CONTENT_ENCODING,
                            uint(EbmlIds.CONTENT_ENCODING_SCOPE, 1),
                            master(EbmlIds.CONTENT_COMPRESSION, uint(EbmlIds.CONTENT_COMP_ALGO, algorithm))))),
                    master(EbmlIds.CLUSTER, blocks));
            var ours = demux(data);
            assertEquals(vectors.size(), ours.order().size());
            for (var index = 0; index < vectors.size(); index++) {
                var packet = ours.order().get(index).data();
                assertEquals(vectors.get(index).size(), packet.length, "frame " + index);
                assertEquals(vectors.get(index).sha256(), CompressVectors.sha256(packet), "frame " + index);
            }
            DemuxerAsserts.of("matroska/" + format + "-frames", data).noDump().run();
            var probe = ContainerProbe.probe(write(format, data));
            DemuxAssertions.assertPacketsMatch(DemuxAssertions.inProbeTimeBase(ours, probe), probe, track -> 0, false);
        }

        @Test
        @DisplayName("decompresses bzlib-compressed frames (ContentCompAlgo 1) as ffprobe does")
        void bzlib() {
            compressedFrames("bzip2", ".bz2", 1);
        }

        @Test
        @DisplayName("decompresses LZO1X-compressed frames (ContentCompAlgo 2) as ffprobe does")
        void lzo() {
            compressedFrames("lzo", ".lzo", 2);
        }

        @ParameterizedTest(name = "ContentCompAlgo {0}")
        @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2})
        @DisplayName("decompresses codec private data compressed with zlib, bzlib or LZO1X as ffprobe does")
        void codecPrivate(int algorithm) {
            var head = new byte[]{'O', 'p', 'u', 's', 'H', 'e', 'a', 'd', 1, 1, 0x38, 0x01, (byte) 0x80, (byte) 0xBB, 0, 0, 0,
                    0, 0};
            var compressed = switch (algorithm) {
                case 0 -> {
                    var deflater = new Deflater();
                    deflater.setInput(head);
                    deflater.finish();
                    var out = new byte[256];
                    var length = deflater.deflate(out);
                    deflater.end();
                    yield Arrays.copyOf(out, length);
                }
                case 1 -> CompressVectors.named("bzip2", "opus-head-9.bz2").stream();
                default -> CompressVectors.named("lzo", "opus-head.lzo").stream();
            };
            var frame = new byte[]{(byte) 0xF8, 0x01, 0x02};
            var data = file("webm", false, master(EbmlIds.INFO, uint(EbmlIds.TIMESTAMP_SCALE, 1_000_000)),
                    master(EbmlIds.TRACKS, master(EbmlIds.TRACK_ENTRY, uint(EbmlIds.TRACK_NUMBER, 1), uint(EbmlIds.TRACK_UID, 1),
                            uint(EbmlIds.TRACK_TYPE, 2), string(EbmlIds.CODEC_ID, "A_OPUS"),
                            binary(EbmlIds.CODEC_PRIVATE, compressed),
                            master(EbmlIds.AUDIO, floating(EbmlIds.SAMPLING_FREQUENCY, 48000), uint(EbmlIds.CHANNELS, 1)),
                            master(EbmlIds.CONTENT_ENCODINGS, master(EbmlIds.CONTENT_ENCODING,
                                    uint(EbmlIds.CONTENT_ENCODING_SCOPE, 2),
                                    master(EbmlIds.CONTENT_COMPRESSION, uint(EbmlIds.CONTENT_COMP_ALGO, algorithm)))))),
                    master(EbmlIds.CLUSTER, uint(EbmlIds.TIMESTAMP, 0), binary(EbmlIds.SIMPLE_BLOCK, block(1, 0, 0x80, frame))));
            var ours = demux(data);
            assertArrayEquals(head, ours.tracks().getFirst().codecConfig().orElseThrow());
            assertArrayEquals(frame, ours.order().getFirst().data(), "the frames are not compressed");
            var probe = ContainerProbe.probe(write("private-" + algorithm, data));
            assertArrayEquals(head, probe.streams().getFirst().extradata(), "ffprobe's codec private data");
        }

        @Test
        @DisplayName("rejects a damaged bzlib or LZO1X frame as malformed")
        void damagedFrames() {
            for (var algorithm : new int[]{1, 2}) {
                var vector = CompressVectors.named(algorithm == 1 ? "bzip2" : "lzo", algorithm == 1 ? "frame-00.bz2"
                        : "frame-00.lzo");
                var truncated = Arrays.copyOf(vector.stream(), vector.stream().length / 2);
                var data = file("matroska", false, master(EbmlIds.INFO, uint(EbmlIds.TIMESTAMP_SCALE, 1_000_000)),
                        pcmTrack(1, 0, master(EbmlIds.CONTENT_ENCODINGS, master(EbmlIds.CONTENT_ENCODING,
                                master(EbmlIds.CONTENT_COMPRESSION, uint(EbmlIds.CONTENT_COMP_ALGO, algorithm))))),
                        master(EbmlIds.CLUSTER, uint(EbmlIds.TIMESTAMP, 0),
                                binary(EbmlIds.SIMPLE_BLOCK, block(1, 0, 0x80, truncated))));
                assertThrows(MediaException.Malformed.class, () -> demux(data), "algorithm " + algorithm);
            }
        }

        @Test
        @DisplayName("rejects encrypted tracks and unsupported compression")
        void unsupported() {
            var encrypted = file("webm", false, master(EbmlIds.INFO, uint(EbmlIds.TIMESTAMP_SCALE, 1_000_000)),
                    pcmTrack(1, 0, master(EbmlIds.CONTENT_ENCODINGS, master(EbmlIds.CONTENT_ENCODING,
                            uint(EbmlIds.CONTENT_ENCODING_TYPE, 1), master(EbmlIds.CONTENT_ENCRYPTION)))));
            var failure = assertThrows(MediaException.Unsupported.class, () -> demux(encrypted));
            assertEquals("matroska encryption", failure.format());
            var undefined = file("webm", false, pcmTrack(1, 0, master(EbmlIds.CONTENT_ENCODINGS, master(EbmlIds.CONTENT_ENCODING,
                    master(EbmlIds.CONTENT_COMPRESSION, uint(EbmlIds.CONTENT_COMP_ALGO, 4))))));
            var compression = assertThrows(MediaException.Unsupported.class, () -> demux(undefined));
            assertEquals("matroska compression 4", compression.format());
        }
    }

    @Test
    @DisplayName("trims a DiscardPadding that is not a whole number of milliseconds to the exact sample")
    void discardPadding() {
        // TOC 0xF8: CELT fullband 20 ms, one frame, so 960 samples at 48 kHz
        var frame = new byte[]{(byte) 0xF8, 0x01, 0x02};
        var head = new byte[]{'O', 'p', 'u', 's', 'H', 'e', 'a', 'd', 1, 1, 0x38, 0x01, (byte) 0x80, (byte) 0xBB, 0, 0, 0, 0, 0};
        var data = file("webm", false, master(EbmlIds.INFO, uint(EbmlIds.TIMESTAMP_SCALE, 1_000_000)),
                master(EbmlIds.TRACKS, master(EbmlIds.TRACK_ENTRY, uint(EbmlIds.TRACK_NUMBER, 1), uint(EbmlIds.TRACK_UID, 1),
                        uint(EbmlIds.TRACK_TYPE, 2), string(EbmlIds.CODEC_ID, "A_OPUS"), binary(EbmlIds.CODEC_PRIVATE, head),
                        master(EbmlIds.AUDIO, floating(EbmlIds.SAMPLING_FREQUENCY, 48000), uint(EbmlIds.CHANNELS, 1)))),
                master(EbmlIds.CLUSTER, uint(EbmlIds.TIMESTAMP, 0),
                        binary(EbmlIds.SIMPLE_BLOCK, block(1, 0, 0x80, frame)),
                        // 13.5 ms of padding is 648 samples; 0xCDFE60 is written on four bytes to stay positive
                        master(EbmlIds.BLOCK_GROUP, binary(EbmlIds.BLOCK, block(1, 20, 0, frame)),
                                binary(EbmlIds.DISCARD_PADDING, new byte[]{0, (byte) 0xCD, (byte) 0xFE, 0x60}))));
        var ours = demux(data);
        assertEquals(new com.github.auties00.smalt.track.Rational(1, 48_000), ours.tracks().getFirst().timeBase());
        assertEquals(List.of(0L, 960L), ours.order().stream().map(Packet::pts).toList());
        assertEquals(List.of(960L, 312L), ours.order().stream().map(Packet::duration).toList());
        assertEquals(List.of(0L, 648L), ours.order().stream().map(packet -> packet.sideData().discardEnd()).toList());
    }

    @Test
    @DisplayName("reports block flags, block additions and a leading DiscardPadding as packet side data")
    void sideData() {
        // TOC 0xF8: CELT fullband 20 ms, one frame, so 960 samples at 48 kHz
        var frame = new byte[]{(byte) 0xF8, 0x01, 0x02};
        var head = new byte[]{'O', 'p', 'u', 's', 'H', 'e', 'a', 'd', 1, 1, 0x38, 0x01, (byte) 0x80, (byte) 0xBB, 0, 0, 0, 0, 0};
        var hdr10Plus = new byte[]{(byte) 0xB5, 0, 0x3C, 0, 1, 4, 1, 0x40};
        var other = new byte[]{9, 8, 7};
        var data = file("webm", false, master(EbmlIds.INFO, uint(EbmlIds.TIMESTAMP_SCALE, 1_000_000)),
                master(EbmlIds.TRACKS, master(EbmlIds.TRACK_ENTRY, uint(EbmlIds.TRACK_NUMBER, 1), uint(EbmlIds.TRACK_UID, 1),
                        uint(EbmlIds.TRACK_TYPE, 2), string(EbmlIds.CODEC_ID, "A_OPUS"), binary(EbmlIds.CODEC_PRIVATE, head),
                        master(EbmlIds.AUDIO, floating(EbmlIds.SAMPLING_FREQUENCY, 48000), uint(EbmlIds.CHANNELS, 1)))),
                master(EbmlIds.CLUSTER, uint(EbmlIds.TIMESTAMP, 0),
                        // -13.5 ms of padding at the start is 648 samples; 0xFF3201A0 is its four-byte two's complement
                        master(EbmlIds.BLOCK_GROUP, binary(EbmlIds.BLOCK, block(1, 0, 0, frame)),
                                binary(EbmlIds.DISCARD_PADDING, new byte[]{(byte) 0xFF, 0x32, 0x01, (byte) 0xA0}),
                                master(EbmlIds.BLOCK_ADDITIONS,
                                        master(EbmlIds.BLOCK_MORE, uint(EbmlIds.BLOCK_ADD_ID, 4),
                                                binary(EbmlIds.BLOCK_ADDITIONAL, hdr10Plus)),
                                        master(EbmlIds.BLOCK_MORE, binary(EbmlIds.BLOCK_ADDITIONAL, other)))),
                        binary(EbmlIds.SIMPLE_BLOCK, block(1, 20, 0x81, frame)),
                        binary(EbmlIds.SIMPLE_BLOCK, block(1, 40, 0x08, frame))));
        var packets = demux(data).order();
        var first = packets.getFirst().sideData();
        assertEquals(648, first.discardStart());
        assertEquals(960, packets.getFirst().duration());
        assertArrayEquals(hdr10Plus, first.hdr10Plus().orElseThrow());
        assertArrayEquals(other, first.extra("matroska:BlockAdditional:1").orElseThrow());
        assertEquals(Set.of(PacketFlag.KEY), packets.getFirst().flags());
        assertEquals(Set.of(PacketFlag.KEY, PacketFlag.DISCARDABLE), packets.get(1).flags());
        assertEquals(Set.of(PacketFlag.DISCARD), packets.get(2).flags());
        assertTrue(packets.get(1).sideData().isEmpty());
    }

    @Test
    @DisplayName("reads the chroma siting and HDR metadata of a Color element")
    void color() {
        var colorElement = master(EbmlIds.COLOR, uint(EbmlIds.MATRIX_COEFFICIENTS, 9), uint(EbmlIds.RANGE, 1),
                uint(EbmlIds.TRANSFER_CHARACTERISTICS, 16), uint(EbmlIds.PRIMARIES, 9),
                uint(EbmlIds.CHROMA_SITING_HORZ, 1), uint(EbmlIds.CHROMA_SITING_VERT, 1),
                uint(EbmlIds.MAX_CLL, 1000), uint(EbmlIds.MAX_FALL, 400),
                master(EbmlIds.MASTERING_METADATA,
                        floating(EbmlIds.PRIMARY_R_CHROMATICITY_X, 0.708), floating(EbmlIds.PRIMARY_R_CHROMATICITY_Y, 0.292),
                        floating(EbmlIds.PRIMARY_G_CHROMATICITY_X, 0.17), floating(EbmlIds.PRIMARY_G_CHROMATICITY_Y, 0.797),
                        floating(EbmlIds.PRIMARY_B_CHROMATICITY_X, 0.131), floating(EbmlIds.PRIMARY_B_CHROMATICITY_Y, 0.046),
                        floating(EbmlIds.WHITE_POINT_CHROMATICITY_X, 0.3127),
                        floating(EbmlIds.WHITE_POINT_CHROMATICITY_Y, 0.329),
                        floating(EbmlIds.LUMINANCE_MAX, 1000), floating(EbmlIds.LUMINANCE_MIN, 0.005)));
        var data = file("webm", false, master(EbmlIds.INFO, uint(EbmlIds.TIMESTAMP_SCALE, 1_000_000)),
                master(EbmlIds.TRACKS, master(EbmlIds.TRACK_ENTRY, uint(EbmlIds.TRACK_NUMBER, 1), uint(EbmlIds.TRACK_UID, 1),
                        uint(EbmlIds.TRACK_TYPE, 1), string(EbmlIds.CODEC_ID, "V_VP9"),
                        master(EbmlIds.VIDEO, uint(EbmlIds.PIXEL_WIDTH, 64), uint(EbmlIds.PIXEL_HEIGHT, 48), colorElement))),
                master(EbmlIds.CLUSTER, uint(EbmlIds.TIMESTAMP, 0), binary(EbmlIds.SIMPLE_BLOCK, block(1, 0, 0x80, new byte[]{0}))));
        var track = (VideoTrack) demux(data).tracks().getFirst();
        var color = track.color().orElseThrow();
        assertEquals(MatrixCoefficients.BT2020_NCL, color.matrix());
        assertEquals(TransferCharacteristics.PQ, color.transfer());
        assertEquals(ChromaSiting.TOP_LEFT, color.chromaSiting());
        assertEquals(new ContentLightLevel(1000, 400), color.contentLightLevel().orElseThrow());
        var display = color.masteringDisplay().orElseThrow();
        assertEquals(new Chromaticity(0.708, 0.292), display.red());
        assertEquals(new Chromaticity(0.3127, 0.329), display.whitePoint());
        assertEquals(1000, display.maxLuminance());
        assertEquals(0.005, display.minLuminance());
    }

    @Test
    @DisplayName("remuxes audio whose timestamps jitter and step backwards into exact frame steps")
    void remuxRepair() {
        var frames = Encodings.mp3Frames();
        var blocks = new ArrayList<EbmlWriter.Node>();
        blocks.add(uint(EbmlIds.TIMESTAMP, 0));
        for (var index = 0; index < frames.size(); index++) {
            var exact = index * 1152 * 1000.0 / 44100;
            var jitter = index % 7 == 3 ? -30 : index % 5 == 1 ? 1 : 0;
            blocks.add(binary(EbmlIds.SIMPLE_BLOCK, block(1, (int) Math.max(0, Math.round(exact) + jitter), 0x80, frames.get(index))));
        }
        var data = file("matroska", false, master(EbmlIds.INFO, uint(EbmlIds.TIMESTAMP_SCALE, 1_000_000)),
                master(EbmlIds.TRACKS, master(EbmlIds.TRACK_ENTRY, uint(EbmlIds.TRACK_NUMBER, 1), uint(EbmlIds.TRACK_UID, 7),
                        uint(EbmlIds.TRACK_TYPE, 2), string(EbmlIds.CODEC_ID, "A_MPEG/L3"),
                        master(EbmlIds.AUDIO, floating(EbmlIds.SAMPLING_FREQUENCY, 44100), uint(EbmlIds.CHANNELS, 1)))),
                master(EbmlIds.CLUSTER, blocks));
        var output = new com.github.auties00.smalt.container.MemoryChannel();
        try (var source = MediaSource.of(data)) {
            var result = com.github.auties00.smalt.container.mp4.Mp4Remuxer.remux(source,
                    com.github.auties00.smalt.io.MediaSink.of(output),
                    com.github.auties00.smalt.container.mp4.RemuxOptions.audio());
            assertEquals(frames.size(), result.packets());
        }
        com.github.auties00.smalt.testkit.validate.Validators.assertValid("matroska-remux.mp4", output.toByteArray());
        try (var source = MediaSource.of(output.toByteArray());
             var demuxer = new com.github.auties00.smalt.container.mp4.Mp4Demuxer(source)) {
            var packets = ContainerProbe.demuxAll(demuxer).order();
            for (var index = 0; index < packets.size(); index++) {
                assertEquals(index * 1152L, packets.get(index).pts(), "packet " + index);
                assertArrayEquals(frames.get(index), packets.get(index).data());
            }
        }
    }

    @Nested
    @DisplayName("structure")
    class Structure {
        /** Rewrites a file with an unknown-size segment and unknown-size clusters, dropping indexes. */
        static byte[] liveRewrite(byte[] original) {
            var cursor = new EbmlCursor(original);
            var header = cursor.next();
            var start = original.length - cursor.remaining();
            var segmentHeader = com.github.auties00.smalt.internal.tags.Ebml.header(original, start, original.length);
            assertEquals(EbmlIds.SEGMENT, segmentHeader.id());
            var size = segmentHeader.size();
            var dataStart = start + segmentHeader.length();
            var children = new ArrayList<EbmlWriter.Node>();
            var segment = new EbmlCursor(original, dataStart, size < 0 ? original.length : (int) (dataStart + size));
            while (segment.hasMore()) {
                var element = segment.next();
                if (element.id() == EbmlIds.CLUSTER) {
                    var body = element.payload();
                    var clusterChildren = new ArrayList<EbmlWriter.Node>();
                    while (body.hasMore()) {
                        var child = body.next();
                        clusterChildren.add(EbmlWriter.binary(child.id(), child.payload().bytes()));
                    }
                    children.add(new EbmlWriter.Master(EbmlIds.CLUSTER, true, clusterChildren));
                } else if (element.id() != EbmlIds.CUES && element.id() != EbmlIds.SEEK_HEAD) {
                    children.add(EbmlWriter.binary(element.id(), element.payload().bytes()));
                }
            }
            var out = new java.io.ByteArrayOutputStream();
            out.writeBytes(EbmlWriter.binary(header.id(), header.payload().bytes()).encode());
            out.writeBytes(new EbmlWriter.Master(EbmlIds.SEGMENT, true, children).encode());
            return out.toByteArray();
        }

        @Test
        @DisplayName("reads unknown-size segments and clusters like the original")
        void unknownSizes() {
            var original = Fixtures.ffmpeg("webm", both(1.5), List.of("-c:v", "libvpx-vp9", "-b:v", "200k", "-deadline", "realtime",
                    "-c:a", "libopus", "-cluster_time_limit", "300"));
            var rewritten = liveRewrite(Fixtures.read(original));
            var expected = demux(original);
            var actual = demux(rewritten);
            assertEquals(expected.order().size(), actual.order().size());
            for (var index = 0; index < expected.order().size(); index++) {
                var a = expected.order().get(index);
                var b = actual.order().get(index);
                assertArrayEquals(a.data(), b.data());
                assertEquals(a.pts(), b.pts());
                assertEquals(a.keyframe(), b.keyframe());
            }
            var probe = ContainerProbe.probe(write("live-rewrite", rewritten));
            DemuxAssertions.assertPacketsMatch(DemuxAssertions.inProbeTimeBase(actual, probe), probe,
                    MatroskaDemuxerTest::expectedOffset, false);
        }

        @Test
        @DisplayName("resynchronizes at the next cluster after damage")
        void resync() {
            var original = Fixtures.read(h264Aac("mkv", false));
            var expected = demux(original).order();
            var damaged = original.clone();
            var clusters = new ArrayList<Integer>();
            for (var index = 0; index + 4 <= damaged.length; index++) {
                if (damaged[index] == 0x1F && damaged[index + 1] == 0x43 && (damaged[index + 2] & 0xFF) == 0xB6 && damaged[index + 3] == 0x75) {
                    clusters.add(index);
                }
            }
            assertTrue(clusters.size() >= 3, "fixture has several clusters");
            var start = clusters.get(1);
            // zero the identifier of the first block after the cluster Timestamp, an invalid EBML identifier
            var sizeLength = Integer.numberOfLeadingZeros(damaged[start + 4] & 0xFF) - 23;
            var timestamp = start + 4 + sizeLength;
            if (damaged[timestamp] == (byte) 0xBF) {
                timestamp += 6; // the CRC-32 element ffmpeg writes first in each cluster
            }
            assertEquals((byte) 0xE7, damaged[timestamp]);
            var firstBlock = timestamp + 2 + (damaged[timestamp + 1] & 0x7F);
            damaged[firstBlock] = 0;
            var actual = demux(damaged).order();
            assertTrue(actual.size() < expected.size(), "the damaged cluster is skipped");
            var tail = expected.subList(expected.size() - 10, expected.size());
            var actualTail = actual.subList(actual.size() - 10, actual.size());
            for (var index = 0; index < 10; index++) {
                assertArrayEquals(tail.get(index).data(), actualTail.get(index).data());
            }
        }

        @Test
        @DisplayName("ends a truncated file with Malformed after the complete blocks")
        void truncated() {
            var original = Fixtures.read(h264Aac("mkv", false));
            var cut = Arrays.copyOf(original, original.length * 2 / 3);
            try (var source = MediaSource.of(cut); var demuxer = new MatroskaDemuxer(source)) {
                var count = 0;
                try {
                    while (demuxer.read() != null) {
                        count++;
                    }
                } catch (MediaException.Malformed expected) {
                    // the block cut in half
                }
                assertTrue(count > 10);
            }
        }
    }
}
