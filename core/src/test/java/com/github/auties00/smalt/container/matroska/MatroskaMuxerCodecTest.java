package com.github.auties00.smalt.container.matroska;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.tags.EbmlIds;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the codecs {@link MatroskaMuxer} writes beyond the common ones: MPEG-4 Part 2,
 * Motion-JPEG, H.263, ALAC, AMR, G.711, the converted PCM formats and generic data tracks. Each
 * ffmpeg-made input is remuxed through Smalt's demuxers into Matroska and read back by Smalt and by
 * ffprobe (packet CRCs), and ffmpeg must decode the output to exactly what it decodes from the input.
 */
@DisplayName("MatroskaMuxer codec coverage")
class MatroskaMuxerCodecTest {
    record Case(String name, Supplier<Path> file, Codec codec, String codecId, Codec read, String probeName,
                int conversion) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<String> video(int width, int height, double seconds) {
        return List.of("-f", "lavfi", "-i", "testsrc2=size=" + width + "x" + height + ":rate=25:duration=" + seconds);
    }

    static List<String> audio(int rate, double seconds) {
        return List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=" + rate + ":duration=" + seconds);
    }

    static List<Case> cases() {
        var cases = new ArrayList<Case>();
        cases.add(new Case("mpeg4 b-frames mp4", () -> Fixtures.ffmpeg("mp4", video(160, 120, 1.2),
                List.of("-c:v", "mpeg4", "-bf", "2", "-g", "12", "-q:v", "5")), Codec.MPEG4_PART2, "V_MPEG4/ISO/ASP",
                Codec.MPEG4_PART2, "mpeg4", 0));
        cases.add(new Case("mpeg4 avi", () -> Fixtures.ffmpeg("avi", video(160, 120, 1),
                List.of("-c:v", "mpeg4", "-bf", "0", "-g", "10")), Codec.MPEG4_PART2, "V_MPEG4/ISO/ASP",
                Codec.MPEG4_PART2, "mpeg4", 0));
        cases.add(new Case("mjpeg avi", () -> Fixtures.ffmpeg("avi", video(96, 64, 0.6),
                List.of("-c:v", "mjpeg", "-q:v", "5")), Codec.MJPEG, "V_MJPEG", Codec.MJPEG, "mjpeg", 0));
        cases.add(new Case("mjpeg mov", () -> Fixtures.ffmpeg("mov", video(96, 64, 0.6),
                List.of("-c:v", "mjpeg", "-q:v", "3")), Codec.MJPEG, "V_MJPEG", Codec.MJPEG, "mjpeg", 0));
        cases.add(new Case("h263 3gp", () -> Fixtures.ffmpeg("3gp", video(176, 144, 1),
                List.of("-c:v", "h263", "-g", "10")), Codec.H263, "V_MS/VFW/FOURCC", Codec.H263, "h263", 0));
        cases.add(new Case("alac 16-bit m4a", () -> Fixtures.ffmpeg("m4a", audio(44100, 1),
                List.of("-ac", "2", "-c:a", "alac")), Codec.ALAC, "A_ALAC", Codec.ALAC, "alac", 0));
        cases.add(new Case("alac 24-bit m4a", () -> Fixtures.ffmpeg("m4a", audio(48000, 0.7),
                List.of("-ac", "1", "-c:a", "alac", "-sample_fmt", "s32p")), Codec.ALAC, "A_ALAC", Codec.ALAC, "alac", 0));
        cases.add(new Case("amr-nb", () -> Fixtures.ffmpeg("amr", audio(8000, 1),
                List.of("-ac", "1", "-c:a", "libopencore_amrnb", "-b:a", "12.2k")), Codec.AMR_NB, "A_MS/ACM",
                Codec.AMR_NB, "amr_nb", 0));
        cases.add(new Case("amr-wb", () -> Fixtures.ffmpeg("amr", audio(16000, 1),
                List.of("-ac", "1", "-c:a", "libvo_amrwbenc", "-b:a", "23.85k")), Codec.AMR_WB, "A_MS/ACM",
                Codec.AMR_WB, "amr_wb", 0));
        cases.add(new Case("a-law wav", () -> Fixtures.ffmpeg("wav", audio(8000, 0.8),
                List.of("-ac", "1", "-c:a", "pcm_alaw")), Codec.PCM_ALAW, "A_MS/ACM", Codec.PCM_ALAW, "pcm_alaw", 0));
        cases.add(new Case("mu-law wav", () -> Fixtures.ffmpeg("wav", audio(8000, 0.8),
                List.of("-ac", "2", "-c:a", "pcm_mulaw")), Codec.PCM_MULAW, "A_MS/ACM", Codec.PCM_MULAW, "pcm_mulaw", 0));
        cases.add(new Case("s8 aiff", () -> Fixtures.ffmpeg("aiff", audio(22050, 0.5),
                List.of("-ac", "2", "-c:a", "pcm_s8")), Codec.PCM_S8, "A_PCM/INT/LIT", Codec.PCM_U8, "pcm_u8", 1));
        cases.add(new Case("f32be aiff", () -> Fixtures.ffmpeg("aiff", audio(22050, 0.5),
                List.of("-ac", "2", "-c:a", "pcm_f32be")), Codec.PCM_F32BE, "A_PCM/FLOAT/IEEE", Codec.PCM_F32LE,
                "pcm_f32le", 4));
        cases.add(new Case("s64le wav", () -> Fixtures.ffmpeg("wav", audio(22050, 0.5),
                List.of("-ac", "2", "-c:a", "pcm_s64le")), Codec.PCM_S64LE, "A_MS/ACM", Codec.PCM_S64LE,
                "pcm_s64le", 0));
        cases.add(new Case("f64be aiff", () -> Fixtures.ffmpeg("aiff", audio(22050, 0.5),
                List.of("-ac", "1", "-c:a", "pcm_f64be")), Codec.PCM_F64BE, "A_PCM/FLOAT/IEEE", Codec.PCM_F64LE,
                "pcm_f64le", 8));
        return cases;
    }

    static byte[] convert(byte[] data, int conversion) {
        var out = data.clone();
        if (conversion == 1) {
            for (var index = 0; index < out.length; index++) {
                out[index] ^= (byte) 0x80;
            }
        } else if (conversion > 1) {
            for (var sample = 0; sample + conversion <= out.length; sample += conversion) {
                for (var index = 0; index < conversion; index++) {
                    out[sample + index] = data[sample + conversion - 1 - index];
                }
            }
        }
        return out;
    }

    static byte[] decodeFrames(Path file) {
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), List.of("-v", "error", "-i", file.toString(),
                "-map", "0:v:0", "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("remuxes into Matroska and back with equal packets, and ffmpeg decodes the same stream")
    void remux(Case fixture) {
        var file = fixture.file().get();
        var source = MuxSupport.demux(Fixtures.read(file));
        var track = source.tracks().stream().filter(candidate -> candidate.codec() == fixture.codec()).findFirst()
                .orElseThrow(() -> new AssertionError("the fixture has no " + fixture.codec() + " track: " + source.tracks()));
        var packets = source.packetsOf(track);
        assertFalse(packets.isEmpty());
        var output = MuxSupport.toMemory(List.of(track), packets, MatroskaMuxer::new);
        var structure = MatroskaStructure.check(output);
        var entry = structure.tracks.getFirst();
        assertEquals(fixture.codecId(), new String(MatroskaStructure.child(entry, EbmlIds.CODEC_ID).payload(),
                StandardCharsets.US_ASCII));

        var result = MuxSupport.demux(output);
        var read = result.tracks().getFirst();
        assertEquals(fixture.read(), read.codec());
        var after = result.packetsOf(read);
        assertEquals(packets.size(), after.size(), "packet count");
        for (var index = 0; index < packets.size(); index++) {
            assertArrayEquals(convert(packets.get(index).data(), fixture.conversion()), after.get(index).data(),
                    "payload " + index);
            assertEquals(packets.get(index).keyframe(), after.get(index).keyframe(), "key flag " + index);
        }
        MuxSupport.assertTimesClose(MuxSupport.ptsMicros(source, track), MuxSupport.ptsMicros(result, read), 1000);
        if (track instanceof AudioTrack audio) {
            var readAudio = (AudioTrack) read;
            assertEquals(audio.sampleRate(), readAudio.sampleRate());
            assertEquals(audio.layout().orElseThrow().channels(), readAudio.layout().orElseThrow().channels());
        } else {
            var video = (VideoTrack) track;
            assertEquals(video.width(), ((VideoTrack) read).width());
            assertEquals(video.height(), ((VideoTrack) read).height());
        }
        switch (track.codec()) {
            case ALAC -> assertArrayEquals(AudioConfigs.alacConfig(track.codecConfig().orElseThrow()),
                    read.codecConfig().orElseThrow());
            case MPEG4_PART2 -> assertArrayEquals(track.codecConfig().orElse(null), read.codecConfig().orElse(null));
            case H263 -> {
                var bitmap = MatroskaStructure.child(entry, EbmlIds.CODEC_PRIVATE).payload();
                assertEquals(40, bitmap.length);
                assertEquals("H263", new String(bitmap, 16, 4, StandardCharsets.US_ASCII));
            }
            case AMR_NB, AMR_WB, PCM_ALAW, PCM_MULAW -> {
                var wave = ByteBuffer.wrap(MatroskaStructure.child(entry, EbmlIds.CODEC_PRIVATE).payload())
                        .order(ByteOrder.LITTLE_ENDIAN);
                assertEquals(18, wave.capacity());
                var tag = switch (track.codec()) {
                    case AMR_NB -> 0x38;
                    case AMR_WB -> 0x58;
                    case PCM_ALAW -> 6;
                    default -> 7;
                };
                assertEquals(tag, wave.getShort(0));
                assertEquals(((AudioTrack) track).sampleRate(), wave.getInt(4));
                assertEquals(0, wave.getShort(16), "cbSize");
            }
            default -> {
            }
        }

        var path = MuxSupport.save(fixture.name().replace(' ', '-') + ".mkv", output);
        var probe = ContainerProbe.probe(path);
        var stream = probe.streams().getFirst();
        assertEquals(fixture.probeName(), stream.codecName());
        var probed = probe.packetsOf(0);
        assertEquals(after.size(), probed.size(), "packets ffprobe reads");
        for (var index = 0; index < after.size(); index++) {
            assertEquals(ContainerProbe.crc32(after.get(index).data()), probed.get(index).hash(), "ffprobe packet " + index);
        }
        if (track.codec() == Codec.ALAC) {
            assertArrayEquals(read.codecConfig().orElseThrow(), Arrays.copyOfRange(stream.extradata(), 12, 36));
        }
        MuxSupport.assertFfmpegClean(fixture.name().replace(' ', '-') + "-clean.mkv", output);
        if (track.kind() == TrackKind.AUDIO) {
            assertArrayEquals(Oracle.decodeAudio(file), Oracle.decodeAudio(path), "decoded samples");
        } else {
            assertArrayEquals(decodeFrames(file), decodeFrames(path), "decoded frames");
        }
    }

    @Nested
    @DisplayName("converted PCM")
    class ConvertedPcm {
        static AudioTrack pcm(Codec codec, int channels) {
            return AudioTrack.builder().id(1).codec(codec).timeBase(Rational.timeBaseOf(8000)).sampleRate(8000)
                    .layout(ChannelLayout.defaultFor(channels)).bitsPerSample(codec.pcmBitsPerSample().orElseThrow())
                    .build();
        }

        @ParameterizedTest(name = "live {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("flips the sign bit of signed 8-bit samples and byte-swaps big-endian floats and 64-bit integers")
        void conversions(boolean live) {
            var options = MatroskaMuxerOptions.matroska().toBuilder().live(live).build();
            var signed = new byte[256];
            for (var index = 0; index < signed.length; index++) {
                signed[index] = (byte) (index - 128);
            }
            var floats = ByteBuffer.allocate(8 * 4).order(ByteOrder.BIG_ENDIAN);
            for (var value : new float[]{0f, 1f, -1f, 0.5f, -0.25f, Float.MIN_VALUE, 3.4e38f, -1e-20f}) {
                floats.putFloat(value);
            }
            var doubles = ByteBuffer.allocate(4 * 8).order(ByteOrder.BIG_ENDIAN);
            for (var value : new double[]{0, 1, -0.999, Math.PI}) {
                doubles.putDouble(value);
            }
            var longs = ByteBuffer.allocate(6 * 8).order(ByteOrder.BIG_ENDIAN);
            for (var value : new long[]{0, Long.MIN_VALUE, Long.MAX_VALUE, -1, 0x0102030405060708L, 1L << 40}) {
                longs.putLong(value);
            }
            record Input(Codec codec, int channels, byte[] data, Codec read) {
            }
            for (var input : List.of(new Input(Codec.PCM_S8, 2, signed, Codec.PCM_U8),
                    new Input(Codec.PCM_F32BE, 2, floats.array(), Codec.PCM_F32LE),
                    new Input(Codec.PCM_F64BE, 1, doubles.array(), Codec.PCM_F64LE),
                    new Input(Codec.PCM_S64BE, 2, longs.array(), Codec.PCM_S64LE))) {
                var packets = new ArrayList<Packet>();
                var frameBytes = input.channels() * (input.codec().pcmBitsPerSample().orElseThrow() / 8);
                var pts = 0L;
                for (var block = 0; block < 6; block++) {
                    // every other packet is shorter, so a reused conversion buffer longer than the payload is covered
                    var payload = block % 2 == 1 ? Arrays.copyOf(input.data(), input.data().length / 2) : input.data();
                    var frame = payload.length / frameBytes;
                    packets.add(new Packet(1, payload, pts, pts, frame, true));
                    pts += frame;
                }
                var output = MuxSupport.toMemory(List.of(pcm(input.codec(), input.channels())), packets,
                        sink -> new MatroskaMuxer(sink, options));
                var result = MuxSupport.demux(output);
                assertEquals(input.read(), result.tracks().getFirst().codec());
                var expected = input.read() == Codec.PCM_U8 ? convert(input.data(), 1)
                        : convert(input.data(), input.read().pcmBitsPerSample().orElseThrow() / 8);
                var read = result.order();
                assertEquals(packets.size(), read.size());
                for (var index = 0; index < read.size(); index++) {
                    assertArrayEquals(Arrays.copyOf(expected, packets.get(index).size()), read.get(index).data(),
                            "packet " + index);
                }
                if (input.read() == Codec.PCM_U8) {
                    for (var index = 0; index < 256; index++) {
                        assertEquals(index, expected[index] & 0xFF, "unsigned sample of " + (index - 128));
                    }
                } else if (input.read() == Codec.PCM_F32LE) {
                    var little = ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN);
                    floats.rewind();
                    for (var index = 0; index < 8; index++) {
                        assertEquals(floats.getFloat(), little.getFloat());
                    }
                } else if (input.read() == Codec.PCM_S64LE) {
                    var little = ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN);
                    longs.rewind();
                    for (var index = 0; index < 6; index++) {
                        assertEquals(longs.getLong(), little.getLong());
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("data tracks")
    class DataTracks {
        static DataTrack data(int id, String handler, byte[] config) {
            return DataTrack.builder().id(id).timeBase(Rational.MILLISECONDS).handler(handler).codecConfig(config).build();
        }

        static AudioTrack pcm(int id) {
            return AudioTrack.builder().id(id).codec(Codec.PCM_S16LE).timeBase(Rational.timeBaseOf(48_000))
                    .sampleRate(48_000).layout(ChannelLayout.MONO).bitsPerSample(16).build();
        }

        @Test
        @DisplayName("writes the handler as codec identifier, the configuration and timed blocks, with a track type per prefix")
        void roundTrip() {
            var config = new byte[]{1, 2, 3, 4, 5};
            var tracks = List.<Track>of(pcm(1), data(2, "S_HDMV/PGS", null), data(3, "M_SMALT/TEST", config),
                    data(4, "B_VOBBTN", null));
            var packets = new ArrayList<Packet>();
            for (var block = 0; block < 100; block++) {
                packets.add(new Packet(1, new byte[960 * 2], block * 960L, block * 960L, 960, true));
                if (block % 10 == 0) {
                    for (var id = 2; id <= 4; id++) {
                        var payload = new byte[7 + block + id];
                        Arrays.fill(payload, (byte) (block + id));
                        packets.add(new Packet(id, payload, block * 20L, block * 20L, 150, true));
                    }
                }
            }
            var output = MuxSupport.toMemory(tracks, packets, MatroskaMuxer::new);
            var structure = MatroskaStructure.check(output);
            var types = new long[]{2, 17, 33, 18};
            for (var index = 0; index < 4; index++) {
                var type = MatroskaStructure.child(structure.tracks.get(index), EbmlIds.TRACK_TYPE).payload();
                assertEquals(types[index], type[type.length - 1] & 0xFF, "TrackType of track " + index);
            }
            assertTrue(structure.cues.stream().allMatch(cue -> cue[1] == 1), "cue points only for the audio track");
            var result = MuxSupport.demux(output);
            for (var index = 1; index < 4; index++) {
                var written = (DataTrack) tracks.get(index);
                var read = assertInstanceOf(DataTrack.class, result.tracks().get(index));
                assertEquals(Codec.DATA, read.codec());
                assertEquals(written.handler(), read.handler());
                assertArrayEquals(written.codecConfig().orElse(null), read.codecConfig().orElse(null));
                var before = packets.stream().filter(packet -> packet.trackId() == written.id()).toList();
                var after = result.packetsOf(read);
                assertEquals(before.size(), after.size());
                for (var block = 0; block < before.size(); block++) {
                    assertArrayEquals(before.get(block).data(), after.get(block).data());
                    assertEquals(before.get(block).pts(), read.timeBase().rescale(after.get(block).pts(), Rational.MILLISECONDS));
                    assertEquals(150, read.timeBase().rescale(after.get(block).duration(), Rational.MILLISECONDS));
                }
            }
            var probe = ContainerProbe.probe(MuxSupport.save("data-tracks.mkv", output));
            var pgs = probe.streams().get(1);
            assertEquals("hdmv_pgs_subtitle", pgs.codecName());
            assertEquals("subtitle", pgs.get("codec_type"));
            var probed = probe.packetsOf(1);
            var written = packets.stream().filter(packet -> packet.trackId() == 2).toList();
            assertEquals(written.size(), probed.size());
            for (var index = 0; index < written.size(); index++) {
                assertEquals(ContainerProbe.crc32(written.get(index).data()), probed.get(index).hash());
                assertEquals(150, probed.get(index).duration());
            }
        }

        @Test
        @DisplayName("writes WebVTT kinds other than metadata as subtitle tracks")
        void webVttKinds() {
            var tracks = List.<Track>of(data(1, "D_WEBVTT/CAPTIONS", null), data(2, "D_WEBVTT/METADATA", null),
                    data(3, "D_WEBVTT/DESCRIPTIONS", null));
            var structure = MatroskaStructure.check(MuxSupport.toMemory(tracks, List.of(), MatroskaMuxer::new));
            var types = new long[]{17, 33, 17};
            for (var index = 0; index < types.length; index++) {
                var type = MatroskaStructure.child(structure.tracks.get(index), EbmlIds.TRACK_TYPE).payload();
                assertEquals(types[index], type[type.length - 1] & 0xFF, "TrackType of track " + index);
            }
        }

        @Test
        @DisplayName("rejects data tracks without a usable codec identifier, and in WebM")
        void rejections() {
            assertTrue(MatroskaMuxer.supports(MatroskaDocType.MATROSKA, Codec.DATA));
            assertFalse(MatroskaMuxer.supports(MatroskaDocType.WEBM, Codec.DATA));
            for (var handler : Arrays.asList(null, "V_FOO", "A_FOO", "s_text", "S_", "S_A B", "SX_FOO", "meta",
                    "S_" + (char) 0xE9)) {
                try (var muxer = new MatroskaMuxer(MediaSink.memory())) {
                    assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(data(0, handler, null)),
                            "handler " + handler);
                }
            }
            try (var muxer = new MatroskaMuxer(MediaSink.memory(), MatroskaMuxerOptions.webm())) {
                assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(data(0, "S_HDMV/PGS", null)));
            }
        }
    }

    @Nested
    @DisplayName("track validation")
    class Validation {
        static AudioTrack.Builder audio(Codec codec) {
            return AudioTrack.builder().id(0).codec(codec).timeBase(Rational.timeBaseOf(8000));
        }

        @Test
        @DisplayName("rejects AMR tracks that are not mono at the codec rate, and ALAC without a valid configuration")
        void rejections() {
            var invalid = List.of(
                    audio(Codec.AMR_NB).sampleRate(16_000).layout(ChannelLayout.MONO).build(),
                    audio(Codec.AMR_WB).sampleRate(16_000).layout(ChannelLayout.STEREO).build(),
                    audio(Codec.ALAC).sampleRate(44_100).layout(ChannelLayout.STEREO).build(),
                    audio(Codec.ALAC).sampleRate(44_100).layout(ChannelLayout.STEREO).codecConfig(new byte[10]).build(),
                    audio(Codec.PCM_ALAW).layout(ChannelLayout.MONO).build());
            for (var track : invalid) {
                try (var muxer = new MatroskaMuxer(MediaSink.memory())) {
                    assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(track), track.toString());
                }
            }
            for (var codec : List.of(Codec.MPEG4_PART2, Codec.MJPEG, Codec.H263, Codec.ALAC, Codec.AMR_NB, Codec.AMR_WB,
                    Codec.PCM_ALAW, Codec.PCM_MULAW, Codec.PCM_S8, Codec.PCM_F32BE, Codec.PCM_F64BE)) {
                assertTrue(MatroskaMuxer.supports(MatroskaDocType.MATROSKA, codec), codec.toString());
                assertFalse(MatroskaMuxer.supports(MatroskaDocType.WEBM, codec), codec.toString());
            }
        }

        @Test
        @DisplayName("declares AMR tracks without a rate or layout as mono at the codec rate")
        void amrDefaults() {
            var track = AudioTrack.builder().id(0).codec(Codec.AMR_WB).timeBase(Rational.timeBaseOf(16_000)).build();
            var frame = new byte[61];
            frame[0] = 0x44;
            var output = MuxSupport.toMemory(List.of(track), List.of(new Packet(0, frame, 0, 0, 320, true)),
                    MatroskaMuxer::new);
            var read = (AudioTrack) MuxSupport.demux(output).tracks().getFirst();
            assertEquals(Codec.AMR_WB, read.codec());
            assertEquals(16_000, read.sampleRate());
            assertEquals(1, read.layout().orElseThrow().channels());
        }

        @Test
        @DisplayName("writes a BITMAPINFOHEADER of the frame size for H.263")
        void h263Header() {
            var track = VideoTrack.builder().id(0).codec(Codec.H263).timeBase(Rational.MILLISECONDS).width(352)
                    .height(288).orientation(Orientation.NORMAL).build();
            var output = MuxSupport.toMemory(List.of(track), List.of(), MatroskaMuxer::new);
            var entry = MatroskaStructure.check(output).tracks.getFirst();
            var bitmap = ByteBuffer.wrap(MatroskaStructure.child(entry, EbmlIds.CODEC_PRIVATE).payload())
                    .order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(40, bitmap.getInt(0));
            assertEquals(352, bitmap.getInt(4));
            assertEquals(288, bitmap.getInt(8));
            assertEquals(1, bitmap.getShort(12));
            assertEquals(352 * 288 * 3, bitmap.getInt(20));
            var read = (VideoTrack) MuxSupport.demux(output).tracks().getFirst();
            assertEquals(Codec.H263, read.codec());
            assertTrue(read.codecConfig().isEmpty());
            assertNotNull(read);
        }
    }
}
