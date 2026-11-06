package com.github.auties00.smalt.container.dv;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.PcmPackets;
import com.github.auties00.smalt.container.Timecode;
import com.github.auties00.smalt.container.avi.AviDemuxer;
import com.github.auties00.smalt.container.avi.AviMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.internal.header.DifFrames;
import com.github.auties00.smalt.internal.header.DifProfile;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers DV in its containers: raw DIF streams ({@link DvDemuxer}, {@link DvMuxer}), type-1 and type-2 DV AVI,
 * QuickTime and Matroska. ffmpeg is the black-box oracle: its DV files are demuxed and compared with its own
 * packets and audio, and the files Smalt writes are read back by ffprobe and decoded by ffmpeg. Synthetic frames
 * are DIF frames whose video blocks are empty, made with {@link DifFrames#writeSkeleton}.
 */
@DisplayName("DV containers")
class DvContainerTest {
    private static List<String> lavfi(String size, String rate) {
        return List.of("-f", "lavfi", "-i", "testsrc2=size=" + size + ":rate=" + rate, "-f", "lavfi", "-i",
                "sine=frequency=440:sample_rate=48000");
    }

    /** Encodes a third of a second of DV video and 48 kHz stereo audio with ffmpeg. */
    static Path ffmpegFile(String extension, String size, String rate, String pixelFormat, boolean audio) {
        var output = new ArrayList<>(List.of("-t", "0.3", "-c:v", "dvvideo", "-pix_fmt", pixelFormat));
        if (audio) {
            output.addAll(List.of("-c:a", "pcm_s16le", "-ac", "2"));
        } else {
            output.add("-an");
        }
        if (extension.equals("dv")) {
            output.addAll(List.of("-f", "dv"));
        }
        return Fixtures.ffmpeg(extension, lavfi(size, rate), output);
    }

    static Stream<Arguments> rawFiles() {
        return Stream.of(
                Arguments.of("DV 525/60 4:1:1", "720x480", "30000/1001", "yuv411p", 120000),
                Arguments.of("DV 625/50 4:2:0", "720x576", "25", "yuv420p", 144000),
                Arguments.of("DVCPRO 625/50 4:1:1", "720x576", "25", "yuv411p", 144000),
                Arguments.of("DVCPRO50 625/50", "720x576", "25", "yuv422p", 288000),
                Arguments.of("DVCPRO HD 1080/60i", "1280x1080", "30000/1001", "yuv422p", 480000),
                Arguments.of("DVCPRO HD 1080/50i", "1440x1080", "25", "yuv422p", 576000));
    }

    private static byte[] ffmpegAudio(Path file, int stream) {
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-i", file.toString(),
                "-map", "0:a:" + stream, "-f", "s16le", "-"));
    }

    private static byte[] concatenate(List<Packet> packets) {
        var out = new ByteArrayOutputStream();
        packets.forEach(packet -> out.writeBytes(packet.data()));
        return out.toByteArray();
    }

    @Nested
    @DisplayName("raw DIF streams")
    class Raw {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.container.dv.DvContainerTest#rawFiles")
        @DisplayName("demuxes ffmpeg's DV files: whole frames as video packets and the audio ffmpeg extracts")
        void ffmpegFiles(String label, String size, String rate, String pixelFormat, int frameSize) {
            var file = ffmpegFile("dv", size, rate, pixelFormat, true);
            var bytes = Fixtures.read(file);
            try (var source = MediaSource.of(bytes)) {
                assertEquals(ContainerFormat.DV, Containers.detect(source).orElseThrow());
            }
            var demuxed = MuxSupport.demux(bytes);
            var video = (VideoTrack) demuxed.tracks().getFirst();
            assertEquals(Codec.DV, video.codec());
            assertEquals(size, video.width() + "x" + video.height());
            var frameRate = video.frameRate().orElseThrow();
            assertEquals(rate.contains("/") ? rate : rate + "/1", frameRate.numerator() + "/" + frameRate.denominator());
            var frames = demuxed.packetsOf(video);
            assertEquals(bytes.length / frameSize, frames.size());
            for (var i = 0; i < frames.size(); i++) {
                assertArrayEquals(Arrays.copyOfRange(bytes, i * frameSize, (i + 1) * frameSize),
                        frames.get(i).data(), "frame " + i);
                assertEquals(i, frames.get(i).pts());
                assertTrue(frames.get(i).keyframe());
            }
            var audio = (AudioTrack) demuxed.tracks().get(1);
            assertEquals(Codec.PCM_S16LE, audio.codec());
            assertEquals(48000, audio.sampleRate());
            assertEquals(2, audio.layout().orElseThrow().channels());
            var ours = concatenate(demuxed.packetsOf(audio));
            var reference = ffmpegAudio(file, 0);
            assertEquals(reference.length, ours.length, label);
            assertArrayEquals(reference, ours, label);
            var sum = 0L;
            for (var packet : demuxed.packetsOf(audio)) {
                assertEquals(sum, packet.pts());
                sum += packet.duration();
            }
        }

        @Test
        @DisplayName("reports the format, aspect ratio, scan and time code of the first frame")
        void description() {
            var frames = new ArrayList<byte[]>();
            for (var i = 0; i < 4; i++) {
                frames.add(skeleton(DifProfile.DV_625, true, 1000 + i));
            }
            try (var demuxer = new DvDemuxer(MediaSource.of(join(frames)))) {
                var video = (VideoTrack) demuxer.tracks().getFirst();
                assertEquals(new Rational(64, 45), video.sampleAspectRatio().orElseThrow());
                assertEquals(com.github.auties00.smalt.track.Interlacing.BOTTOM_FIELD_FIRST,
                        video.interlacing().orElseThrow());
                assertEquals(4, video.duration());
                assertEquals(160_000, demuxer.durationMicros());
                assertEquals(Timecode.ofFrameNumber(1000, 25, false), demuxer.timecode().orElseThrow());
                assertEquals(1, demuxer.tracks().size());
            }
            try (var demuxer = new DvDemuxer(MediaSource.of(skeleton(DifProfile.DVCPRO_525, false, 1000)))) {
                // 1000 frames of 29.97 Hz drop-frame time code
                assertEquals(new Timecode(0, 0, 33, 10, 30, true), demuxer.timecode().orElseThrow());
                assertEquals(new Rational(8, 9), ((VideoTrack) demuxer.tracks().getFirst()).sampleAspectRatio()
                        .orElseThrow());
            }
        }

        @Test
        @DisplayName("skips data between frames, drops a truncated last frame and seeks to any frame")
        void resyncAndSeek() {
            var profile = DifProfile.DV_525;
            var frames = new ArrayList<byte[]>();
            for (var i = 0; i < 6; i++) {
                frames.add(skeleton(profile, false, i));
            }
            var out = new ByteArrayOutputStream();
            for (var i = 0; i < frames.size(); i++) {
                out.writeBytes(frames.get(i));
                if (i == 2) {
                    out.writeBytes(new byte[7 * 80]);
                }
            }
            out.writeBytes(Arrays.copyOf(frames.getFirst(), 5000));
            try (var demuxer = new DvDemuxer(MediaSource.of(out.toByteArray()))) {
                var read = new ArrayList<Packet>();
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    read.add(packet);
                }
                assertEquals(6, read.size());
                for (var i = 0; i < 6; i++) {
                    assertArrayEquals(frames.get(i), read.get(i).data());
                }
            }
            try (var demuxer = new DvDemuxer(MediaSource.of(join(frames)))) {
                assertTrue(demuxer.isSeekable());
                demuxer.seek(4 * 1_001_000L / 30 + 1);
                var packet = demuxer.read();
                assertEquals(4, packet.pts());
                assertArrayEquals(frames.get(4), packet.data());
                demuxer.seek(10_000_000);
                assertNull(demuxer.read());
            }
        }

        @Test
        @DisplayName("opens a stream whose time code names a frame past the rate, reporting no time code")
        void invalidTimecode() {
            // frame 27 of a 25 Hz time code
            var pal = patchTimecode(skeleton(DifProfile.DV_625, false, 0), 1, 0x27);
            // 00:01:00;00, an address drop-frame counting skips
            var ntsc = patchTimecode(skeleton(DifProfile.DV_525, false, 0), 3, 0x01);
            for (var frame : List.of(pal, ntsc)) {
                try (var demuxer = new DvDemuxer(MediaSource.of(frame))) {
                    assertTrue(demuxer.timecode().isEmpty());
                    assertArrayEquals(frame, demuxer.read().data());
                }
            }
        }

        private static byte[] patchTimecode(byte[] frame, int index, int value) {
            for (var at = 0; at + DifProfile.BLOCK <= frame.length; at += DifProfile.BLOCK) {
                if (((frame[at] & 0xFF) >>> 5) != DifProfile.SECTION_SUBCODE) {
                    continue;
                }
                for (var sync = 0; sync < 6; sync++) {
                    var pack = at + 3 + 8 * sync + 3;
                    if ((frame[pack] & 0xFF) == DifProfile.PACK_TIMECODE) {
                        frame[pack + index] = (byte) value;
                    }
                }
            }
            return frame;
        }

        @Test
        @DisplayName("stops at the frame limit with LimitExceeded")
        void frameLimit() {
            var frames = new ArrayList<byte[]>();
            for (var i = 0; i < 6; i++) {
                frames.add(skeleton(DifProfile.DV_625, false, i));
            }
            var options = com.github.auties00.smalt.container.DemuxerOptions.builder().limits(
                    com.github.auties00.smalt.MediaLimits.defaults().toBuilder().maxFrames(3).build()).build();
            try (var demuxer = new DvDemuxer(MediaSource.of(join(frames)), options)) {
                for (var i = 0; i < 3; i++) {
                    demuxer.read();
                }
                assertThrows(MediaException.LimitExceeded.class, demuxer::read);
            }
        }

        @Test
        @DisplayName("rejects a stream that is not DV")
        void notDv() {
            var random = new byte[200_000];
            new java.util.Random(3).nextBytes(random);
            assertThrows(MediaException.Malformed.class, () -> new DvDemuxer(MediaSource.of(random)));
            assertThrows(MediaException.Malformed.class, () -> new DvDemuxer(MediaSource.of(
                    Arrays.copyOf(skeleton(DifProfile.DV_525, false, 0), 50_000))));
            assertFalse(DvDemuxer.matches(new byte[100]));
        }
    }

    /** Returns a DIF frame with the sections of {@link DifFrames#writeSkeleton} and empty video. */
    static byte[] skeleton(DifProfile profile, boolean wide, long frame) {
        var data = new byte[profile.frameSize()];
        DifFrames.writeSkeleton(data, 0, profile, wide, profile.progressive() && frame % 2 == 1, frame);
        return data;
    }

    private static byte[] join(List<byte[]> frames) {
        var out = new ByteArrayOutputStream();
        frames.forEach(out::writeBytes);
        return out.toByteArray();
    }

    static Stream<Arguments> muxCases() {
        return Stream.of(
                Arguments.of(DifProfile.DV_525, List.of(48000), 2, true),
                Arguments.of(DifProfile.DV_525, List.of(32000), 1, true),
                Arguments.of(DifProfile.DV_625, List.of(44100), 2, true),
                Arguments.of(DifProfile.DVCPRO_625, List.of(48000), 2, true),
                Arguments.of(DifProfile.DVCPRO50_525, List.of(48000, 48000), 2, true),
                Arguments.of(DifProfile.DVCPRO_HD_1080I50, List.of(48000, 48000, 48000, 48000), 2, true),
                Arguments.of(DifProfile.DVCPRO_HD_720P60, List.of(48000, 48000, 48000), 2, false));
    }

    @ParameterizedTest(name = "{0} {1} {2} channels")
    @MethodSource("muxCases")
    @DisplayName("lays PCM tracks into the frames, read back exactly by Smalt and by ffmpeg")
    void muxAudio(DifProfile profile, List<Integer> rates, int channels, boolean ffmpegReads) {
        var frameCount = 12;
        var frames = new ArrayList<byte[]>();
        for (var i = 0; i < frameCount; i++) {
            frames.add(skeleton(profile, false, i));
        }
        var rate = profile.frameRate();
        var tracks = new ArrayList<Track>();
        var video = VideoTrack.builder().id(1).codec(Codec.DV).timeBase(new Rational(rate.denominator(),
                rate.numerator())).width(profile.width()).height(profile.height()).build();
        tracks.add(video);
        var layout = channels == 2 ? ChannelLayout.STEREO : ChannelLayout.MONO;
        var sources = new ArrayList<PcmPackets>();
        var packets = new ArrayList<Packet>();
        for (var t = 0; t < rates.size(); t++) {
            // as many samples as the frames carry, so that no frame waits for audio that never comes
            var needed = 0;
            for (var f = 0; f < (profile.progressive() ? frameCount / 2 : frameCount); f++) {
                needed += com.github.auties00.smalt.internal.header.DifAudio.samplesPerFrame(profile, rates.get(t), f);
            }
            var tone = PcmPackets.tone(Codec.PCM_S16LE, rates.get(t), layout, needed);
            var track = tone.track().toBuilder().id(2 + t).build();
            tracks.add(track);
            sources.add(tone);
            for (var packet : tone.packets()) {
                packets.add(new Packet(track.id(), packet.data(), packet.pts(), packet.dts(), packet.duration(), true));
            }
        }
        for (var i = 0; i < frameCount; i++) {
            packets.add(new Packet(1, frames.get(i), i, i, 1, true));
        }
        var written = MuxSupport.toMemory(tracks, packets, DvMuxer::new);
        assertEquals(frameCount * profile.frameSize(), written.length);
        var demuxed = MuxSupport.demux(written);
        assertEquals(1 + rates.size(), demuxed.tracks().size());
        for (var t = 0; t < rates.size(); t++) {
            var expected = stereo(sources.get(t).bytes(), channels);
            var track = demuxed.tracks().get(1 + t);
            var actual = concatenate(demuxed.packetsOf(track));
            assertEquals(rates.get(t).intValue(), ((AudioTrack) track).sampleRate());
            assertArrayEquals(expected, actual, "track " + t);
            if (ffmpegReads && Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                var file = MuxSupport.save("dv-mux-" + profile + "-" + t + ".dv", written);
                var reference = ffmpegAudio(file, t);
                assertArrayEquals(Arrays.copyOf(expected, reference.length), reference, "ffmpeg track " + t);
                assertTrue(reference.length >= expected.length * 9 / 10, "ffmpeg read " + reference.length);
            }
        }
        var video0 = demuxed.packetsOf(demuxed.tracks().getFirst());
        assertEquals(frameCount, video0.size());
    }

    /** Duplicates mono 16-bit samples into both channels of a pair, as the muxer lays them. */
    private static byte[] stereo(byte[] samples, int channels) {
        if (channels == 2) {
            return samples;
        }
        var out = new byte[2 * samples.length];
        for (var i = 0; i + 1 < samples.length; i += 2) {
            out[2 * i] = samples[i];
            out[2 * i + 1] = samples[i + 1];
            out[2 * i + 2] = samples[i];
            out[2 * i + 3] = samples[i + 1];
        }
        return out;
    }

    @Test
    @DisplayName("rejects tracks and packets a DV stream cannot carry")
    void muxerRejects() {
        var muxer = new DvMuxer(MediaSink.memory());
        assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(AudioTrack.builder().id(2)
                .codec(Codec.AAC).timeBase(new Rational(1, 48000)).sampleRate(48000).layout(ChannelLayout.STEREO)
                .build()));
        assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(AudioTrack.builder().id(2)
                .codec(Codec.PCM_S16LE).timeBase(new Rational(1, 22050)).sampleRate(22050)
                .layout(ChannelLayout.STEREO).build()));
        assertThrows(IllegalStateException.class, () -> muxer.writePacket(new Packet(1, new byte[10], 0, 0, 1, true)));
        muxer.addTrack(VideoTrack.builder().id(1).codec(Codec.DV).timeBase(new Rational(1, 25)).width(720)
                .height(576).build());
        assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(new Packet(1, new byte[10], 0, 0, 1,
                true)));
        var two = new DvMuxer(MediaSink.memory());
        two.addTrack(VideoTrack.builder().id(1).codec(Codec.DV).timeBase(new Rational(1, 25)).width(720)
                .height(576).build());
        for (var id = 2; id <= 3; id++) {
            two.addTrack(AudioTrack.builder().id(id).codec(Codec.PCM_S16LE).timeBase(new Rational(1, 48000))
                    .sampleRate(48000).layout(ChannelLayout.STEREO).build());
        }
        assertThrows(IllegalArgumentException.class, () -> two.writePacket(new Packet(1,
                skeleton(DifProfile.DV_625, false, 0), 0, 0, 1, true)));
        assertTrue(DvMuxer.supports(Codec.DV));
        assertFalse(DvMuxer.supports(Codec.H264));
        assertTrue(Containers.isMuxable(ContainerFormat.DV, Codec.PCM_S16BE));
    }

    @Nested
    @DisplayName("AVI")
    class Avi {
        @Test
        @DisplayName("reads type-2 DV AVI files of ffmpeg and writes ones ffmpeg decodes")
        void typeTwo() {
            var file = ffmpegFile("avi", "720x576", "25", "yuv420p", true);
            var demuxed = ContainerProbe.demux(file, AviDemuxer::new);
            var video = demuxed.tracks().stream().filter(track -> track instanceof VideoTrack).findFirst()
                    .orElseThrow();
            assertEquals(Codec.DV, video.codec());
            var reference = ContainerProbe.packetData(file);
            assertEquals(demuxed.packetsOf(video).size() + demuxed.packetsOf(demuxed.tracks().get(1)).size(),
                    reference.size());
            for (var packet : demuxed.packetsOf(video)) {
                assertEquals(144000, packet.data().length);
                assertEquals(com.github.auties00.smalt.internal.header.DifProfile.DV_625,
                        DifProfile.detect(packet.data(), 0, packet.data().length));
            }
            var written = MuxSupport.toMemory(demuxed.tracks(), demuxed.order(), AviMuxer::new);
            MuxSupport.assertFfmpegClean("dv-type2.avi", written);
            var probe = ContainerProbe.probe(MuxSupport.save("dv-type2-probe.avi", written));
            assertTrue(probe.toString().contains("dvvideo"), probe.toString());
            var ids = demuxed.tracks().stream().map(Track::id).toList();
            MuxSupport.assertSamePayloads(demuxed, MuxSupport.demux(written), java.util.Map.of(ids.get(0), ids.get(0),
                    ids.get(1), ids.get(1)));
        }

        @Test
        @DisplayName("splits the audio of a type-1 DV AVI file out of its interleaved stream, as ffmpeg does")
        void typeOne() {
            var dv = Fixtures.read(ffmpegFile("dv", "720x480", "30000/1001", "yuv411p", true));
            var avi = DvTestFiles.typeOneAvi(dv, 120000, 1001, 30000);
            var file = MuxSupport.save("dv-type1.avi", avi);
            try (var demuxer = new AviDemuxer(MediaSource.of(avi))) {
                var demuxed = ContainerProbe.demuxAll(demuxer);
                assertEquals(2, demuxed.tracks().size());
                var video = (VideoTrack) demuxed.tracks().getFirst();
                assertEquals(Codec.DV, video.codec());
                assertEquals(720, video.width());
                assertEquals(480, video.height());
                assertEquals(dv.length / 120000, demuxed.packetsOf(video).size());
                var audio = (AudioTrack) demuxed.tracks().get(1);
                assertEquals(Codec.PCM_S16LE, audio.codec());
                var ours = concatenate(demuxed.packetsOf(audio));
                var reference = ffmpegAudio(file, 0);
                assertArrayEquals(reference, ours);
                demuxer.seek(0);
                assertEquals(video.id(), demuxer.read().trackId());
                assertEquals(0, demuxer.read().pts());
            }
        }
    }

    static Stream<Arguments> movFiles() {
        return Stream.of(
                Arguments.of("720x480", "30000/1001", "yuv411p", "dvc "),
                Arguments.of("720x576", "25", "yuv420p", "dvcp"),
                Arguments.of("720x576", "25", "yuv411p", "dvpp"),
                Arguments.of("720x480", "30000/1001", "yuv422p", "dv5n"),
                Arguments.of("720x576", "25", "yuv422p", "dv5p"),
                Arguments.of("1280x1080", "30000/1001", "yuv422p", "dvh6"),
                Arguments.of("1440x1080", "25", "yuv422p", "dvh5"),
                Arguments.of("960x720", "60000/1001", "yuv422p", "dvhp"),
                Arguments.of("960x720", "50", "yuv422p", "dvhq"));
    }

    @ParameterizedTest(name = "{3}")
    @MethodSource("movFiles")
    @DisplayName("reads QuickTime DV of ffmpeg and writes the sample entry type of the format")
    void quickTime(String size, String rate, String pixelFormat, String type) {
        var file = ffmpegFile("mov", size, rate, pixelFormat, false);
        var demuxed = ContainerProbe.demux(file, source -> Containers.openDemuxer(source));
        var video = demuxed.tracks().getFirst();
        assertEquals(Codec.DV, video.codec());
        var reference = ContainerProbe.packetData(file);
        var packets = demuxed.packetsOf(video);
        assertEquals(reference.size(), packets.size());
        for (var i = 0; i < packets.size(); i++) {
            assertArrayEquals(reference.get(i), packets.get(i).data());
        }
        var written = MuxSupport.toMemory(demuxed.tracks(), demuxed.order(), sink -> new Mp4Muxer(sink,
                Mp4MuxerOptions.builder().brand(Mp4MuxerOptions.Brand.QUICKTIME).build()));
        var saved = MuxSupport.save("dv-" + type.strip() + ".mov", written);
        var tag = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error",
                "-select_streams", "v:0", "-show_entries", "stream=codec_name,codec_tag_string", "-of", "csv=p=0",
                saved.toString())), StandardCharsets.US_ASCII).strip();
        assertEquals("dvvideo," + type.strip(), tag.replace("[32]", "").strip());
        MuxSupport.assertFfmpegClean("dv-" + type.strip() + "-clean.mov", written);
    }

    @Test
    @DisplayName("reads Matroska DV of ffmpeg and writes a V_MS/VFW/FOURCC track ffmpeg decodes")
    void matroska() {
        var file = ffmpegFile("mkv", "720x576", "25", "yuv420p", false);
        var demuxed = ContainerProbe.demux(file, source -> Containers.openDemuxer(source));
        assertEquals(Codec.DV, demuxed.tracks().getFirst().codec());
        var written = MuxSupport.toMemory(demuxed.tracks(), demuxed.order(), MatroskaMuxer::new);
        MuxSupport.assertFfmpegClean("dv.mkv", written);
        var id = demuxed.tracks().getFirst().id();
        MuxSupport.assertSamePayloads(demuxed, MuxSupport.demux(written), java.util.Map.of(id, id));
    }

    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.CsvSource({"720x576, 25, yuv420p", "720x480, 30000/1001, yuv422p",
            "1280x1080, 30000/1001, yuv422p"})
    @DisplayName("reads the DV-DIF essence of ffmpeg's MXF files, one frame per packet")
    void mxf(String size, String rate, String pixelFormat) {
        var file = ffmpegFile("mxf", size, rate, pixelFormat, true);
        var demuxed = ContainerProbe.demux(file, source -> Containers.openDemuxer(source));
        var video = demuxed.tracks().stream().filter(track -> track instanceof VideoTrack).findFirst()
                .orElseThrow();
        assertEquals(Codec.DV, video.codec());
        var probe = ContainerProbe.probe(file);
        var videoIndex = probe.streams().stream().filter(stream -> "dvvideo".equals(stream
                .codecName())).findFirst().orElseThrow().index();
        var expected = probe.packetsOf(videoIndex);
        var packets = demuxed.packetsOf(video);
        assertEquals(expected.size(), packets.size());
        for (var i = 0; i < packets.size(); i++) {
            assertEquals(expected.get(i).size(), packets.get(i).data().length);
            assertEquals(expected.get(i).hash(), ContainerProbe.crc32(packets.get(i).data()));
            assertTrue(packets.get(i).keyframe());
        }
    }

    /**
     * Muxes empty-video frames with tone tracks; a 720-line stream alternates its packets between DIF channels 0-1
     * and 2-3 when {@code alternating}, otherwise every packet takes channels 0 and 1.
     */
    private static byte[] withAudio(DifProfile profile, int trackCount, int frameCount, boolean alternating) {
        var rate = profile.frameRate();
        var tracks = new ArrayList<Track>();
        tracks.add(VideoTrack.builder().id(1).codec(Codec.DV).timeBase(new Rational(rate.denominator(),
                rate.numerator())).width(profile.width()).height(profile.height()).build());
        var packets = new ArrayList<Packet>();
        for (var t = 0; t < trackCount; t++) {
            var audioFrames = profile.progressive() && alternating ? frameCount / 2 : frameCount;
            var needed = 0;
            for (var f = 0; f < audioFrames; f++) {
                needed += com.github.auties00.smalt.internal.header.DifAudio.samplesPerFrame(profile, 48000, f);
            }
            var tone = PcmPackets.tone(Codec.PCM_S16LE, 48000, ChannelLayout.STEREO, needed);
            var track = tone.track().toBuilder().id(2 + t).build();
            tracks.add(track);
            for (var packet : tone.packets()) {
                packets.add(new Packet(track.id(), packet.data(), packet.pts(), packet.dts(), packet.duration(), true));
            }
        }
        for (var i = 0; i < frameCount; i++) {
            var data = new byte[profile.frameSize()];
            DifFrames.writeSkeleton(data, 0, profile, false, alternating && profile.progressive() && i % 2 == 1, i);
            packets.add(new Packet(1, data, i, i, 1, true));
        }
        return MuxSupport.toMemory(tracks, packets, DvMuxer::new);
    }

    private static List<Packet> readAll(Demuxer demuxer) {
        var packets = new ArrayList<Packet>();
        for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
            packets.add(packet);
        }
        return packets;
    }

    /**
     * Checks that what a demuxer reads after a seek to frame {@code frame} is, track by track, the tail of what a
     * front-to-back read gives: the same data under the same timestamps.
     */
    private static void assertSeekResumes(java.util.function.Supplier<Demuxer> open, Rational frameRate, int frame,
                                          boolean audioCoversTarget) {
        List<Track> tracks;
        List<Packet> linear;
        try (var demuxer = open.get()) {
            tracks = demuxer.tracks();
            linear = readAll(demuxer);
        }
        var micros = Math.floorDiv(frame * 1_000_000L * frameRate.denominator(), frameRate.numerator()) + 1;
        try (var demuxer = open.get()) {
            demuxer.seek(micros);
            var resumed = readAll(demuxer);
            for (var track : tracks) {
                var all = linear.stream().filter(packet -> packet.trackId() == track.id()).toList();
                var after = resumed.stream().filter(packet -> packet.trackId() == track.id()).toList();
                assertFalse(after.isEmpty(), "track " + track.id() + " has no packet after the seek");
                var skip = all.size() - after.size();
                assertTrue(skip >= 0, "track " + track.id() + " reads more after the seek");
                for (var i = 0; i < after.size(); i++) {
                    var expected = all.get(skip + i);
                    assertEquals(expected.pts(), after.get(i).pts(), "track " + track.id() + " packet " + i);
                    assertArrayEquals(expected.data(), after.get(i).data(), "track " + track.id() + " packet " + i);
                }
                var first = after.getFirst();
                if (track instanceof VideoTrack) {
                    assertEquals(frame, first.pts());
                } else if (audioCoversTarget) {
                    var target = Math.floorDiv(micros * ((AudioTrack) track).sampleRate(), 1_000_000L);
                    assertTrue(first.pts() <= target && target < first.pts() + first.duration(),
                            "track " + track.id() + " restarts at " + first.pts() + " for sample " + target);
                }
            }
        }
    }

    static Stream<Arguments> seekCases() {
        return Stream.of(
                Arguments.of(DifProfile.DV_525, 1, true, 7),
                Arguments.of(DifProfile.DVCPRO50_625, 2, true, 5),
                Arguments.of(DifProfile.DVCPRO_HD_720P60, 4, true, 6),
                Arguments.of(DifProfile.DVCPRO_HD_720P60, 4, true, 7),
                Arguments.of(DifProfile.DVCPRO_HD_720P50, 2, false, 5));
    }

    @ParameterizedTest(name = "{0} {1} tracks, alternating {2}, frame {3}")
    @MethodSource("seekCases")
    @DisplayName("resumes every audio track after a seek with the timestamps of a front-to-back read")
    void seekWithAudio(DifProfile profile, int trackCount, boolean alternating, int frame) {
        var bytes = withAudio(profile, trackCount, 12, alternating);
        // an alternating 720-line stream resumes the pairs of the other channels at their next packet, and one
        // whose packets all carry channels 0 and 1 holds an audio frame of two video frames in each
        var covers = !profile.progressive();
        assertSeekResumes(() -> new DvDemuxer(MediaSource.of(bytes)), profile.frameRate(), frame, covers);
    }

    @Test
    @DisplayName("resumes the audio of a type-1 DV AVI file after a seek with the timestamps of a front-to-back read")
    void typeOneSeek() {
        var dv = withAudio(DifProfile.DV_525, 1, 12, true);
        var avi = DvTestFiles.typeOneAvi(dv, DifProfile.DV_525.frameSize(), 1001, 30000);
        assertSeekResumes(() -> new AviDemuxer(MediaSource.of(avi)), DifProfile.DV_525.frameRate(), 8, true);
    }

    @Test
    @DisplayName("reads the four 12-bit 32 kHz channels of a 25 Mb/s frame, expanded to 16 bits")
    void twelveBitAudio() {
        var profile = DifProfile.DV_625;
        var frameCount = 3;
        var frames = new ArrayList<byte[]>();
        var expected = new ByteArrayOutputStream[]{new ByteArrayOutputStream(), new ByteArrayOutputStream()};
        var random = new java.util.Random(12);
        for (var f = 0; f < frameCount; f++) {
            var frame = skeleton(profile, false, f);
            var count = com.github.auties00.smalt.internal.header.DifAudio.samplesPerFrame(profile, 32000, f);
            for (var pair = 0; pair < 2; pair++) {
                var samples = new short[2 * count];
                for (var i = 0; i < samples.length; i++) {
                    samples[i] = (short) (random.nextInt(65536) - 32768);
                }
                com.github.auties00.smalt.internal.header.DifAudio.write(frame, 0, profile, pair, 32000, true,
                        samples, 0, count);
                for (var sample : samples) {
                    var value = com.github.auties00.smalt.internal.header.DifAudio.expand(
                            com.github.auties00.smalt.internal.header.DifAudio.compress(sample));
                    // the nonlinear code keeps every sample within its quantization step, two steps for the
                    // samples below -32704, which avoid the error code
                    assertTrue(Math.abs(value - sample) < (sample < -32704 ? 128 : 64)
                            && Math.abs(value) <= Math.abs(sample) + 1, sample + " -> " + value);
                    expected[pair].write(value);
                    expected[pair].write(value >> 8);
                }
            }
            frames.add(frame);
        }
        try (var demuxer = new DvDemuxer(MediaSource.of(join(frames)))) {
            assertEquals(3, demuxer.tracks().size());
            var packets = readAll(demuxer);
            for (var pair = 0; pair < 2; pair++) {
                var track = (AudioTrack) demuxer.tracks().get(1 + pair);
                assertEquals(32000, track.sampleRate());
                var id = track.id();
                assertArrayEquals(expected[pair].toByteArray(),
                        concatenate(packets.stream().filter(packet -> packet.trackId() == id).toList()), "pair " + pair);
            }
        }
        if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
            // ffmpeg expands the 12-bit codes on its own: the two must agree sample for sample
            var file = MuxSupport.save("dv-12bit.dv", join(frames));
            for (var pair = 0; pair < 2; pair++) {
                var reference = ffmpegAudio(file, pair);
                var ours = expected[pair].toByteArray();
                assertTrue(reference.length >= ours.length * 9 / 10, "ffmpeg read " + reference.length);
                assertArrayEquals(Arrays.copyOf(ours, reference.length), reference, "ffmpeg pair " + pair);
            }
        }
    }

    @Test
    @DisplayName("expands the 12-bit nonlinear codes of IEC 61834 at their segment boundaries")
    void twelveBitCodes() {
        // code -> 16-bit value: linear below 512, then steps of 2, 4, ..., 64 per segment of 256 codes
        int[][] pairs = {{0, 0}, {511, 511}, {512, 512}, {767, 1022}, {768, 1024}, {1024, 2048}, {1280, 4096},
                {1536, 8192}, {1792, 16384}, {2047, 32704}, {4095, -1}, {4096 - 512, -512}, {4096 - 513, -513},
                {4096 - 769, -1025}, {2049, -32641}, {2048, 0}};
        for (var pair : pairs) {
            assertEquals(pair[1], com.github.auties00.smalt.internal.header.DifAudio.expand(pair[0]),
                    "code " + pair[0]);
        }
        for (var code = 0; code < 4096; code++) {
            if (code == 0x800) {
                continue;
            }
            var value = com.github.auties00.smalt.internal.header.DifAudio.expand(code);
            assertEquals(code, com.github.auties00.smalt.internal.header.DifAudio.compress(value), "code " + code);
        }
        for (var sample = (int) Short.MIN_VALUE; sample <= Short.MAX_VALUE; sample++) {
            assertTrue(com.github.auties00.smalt.internal.header.DifAudio.compress(sample) != 0x800,
                    "sample " + sample + " takes the error code");
        }
    }

    @Test
    @DisplayName("reads the 16-bit audio error code as silence, as ffmpeg does")
    void errorSamples() {
        var profile = DifProfile.DV_525;
        var frame = skeleton(profile, false, 0);
        var count = com.github.auties00.smalt.internal.header.DifAudio.samplesPerFrame(profile, 48000, 0);
        var samples = new short[2 * count];
        Arrays.fill(samples, (short) 1000);
        com.github.auties00.smalt.internal.header.DifAudio.write(frame, 0, profile, 0, 48000, false, samples, 0, count);
        // the left sample 0 of the pair: sequence 0, first audio block, first sample position
        var at = profile.blockOffset(0, 0, DifProfile.audioBlock(0)) + 8;
        frame[at] = (byte) 0x80;
        frame[at + 1] = 0;
        try (var demuxer = new DvDemuxer(MediaSource.of(frame))) {
            demuxer.read();
            var audio = demuxer.read().data();
            assertEquals(0, audio[0] | audio[1] << 8);
            assertEquals(1000, (audio[2] & 0xFF) | audio[3] << 8);
            assertEquals(1000, (audio[4] & 0xFF) | audio[5] << 8);
        }
        if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
            var reference = ffmpegAudio(MuxSupport.save("dv-error.dv", frame), 0);
            assertEquals(0, reference[0] | reference[1] << 8);
        }
    }

    @ParameterizedTest
    @EnumSource(DifProfile.class)
    @DisplayName("round trips every format through a raw DV stream")
    void everyProfile(DifProfile profile) {
        var frames = new ArrayList<byte[]>();
        for (var i = 0; i < 4; i++) {
            frames.add(skeleton(profile, false, i));
        }
        try (var demuxer = new DvDemuxer(MediaSource.of(join(frames)))) {
            var video = (VideoTrack) demuxer.tracks().getFirst();
            assertEquals(profile.width(), video.width());
            assertEquals(profile.frameRate(), video.frameRate().orElseThrow());
            for (var i = 0; i < 4; i++) {
                assertArrayEquals(frames.get(i), demuxer.read().data());
            }
            assertNull(demuxer.read());
        }
    }
}
