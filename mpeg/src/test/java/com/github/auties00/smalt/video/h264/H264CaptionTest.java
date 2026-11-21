package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.subtitle.ClosedCaptions;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import com.github.auties00.smalt.subtitle.SubtitleReader;
import com.github.auties00.smalt.subtitle.SubtitleReaderOptions;
import com.github.auties00.smalt.subtitle.Subtitles;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.subtitle.CaptionScript;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.transcode.SubtitleTranscodeOptions;
import com.github.auties00.smalt.transcode.TranscodeOptions;
import com.github.auties00.smalt.transcode.Transcoder;
import com.github.auties00.smalt.transcode.VideoTranscodeOptions;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.FrameSideData;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h264.encoder.H264Encoder;
import com.github.auties00.smalt.video.h264.encoder.H264EncoderOptions;
import com.github.auties00.smalt.video.h264.encoder.H264FieldCoding;
import com.github.auties00.smalt.video.h264.encoder.H264PacketFormat;
import com.github.auties00.smalt.video.h264.encoder.H264Profile;
import com.github.auties00.smalt.video.h264.encoder.H264RateControl;
import com.github.auties00.smalt.video.hevc.HevcDecoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Carries ATSC closed captions through the H.264 and HEVC decoders and the H.264 encoder: libx264 and libx265 video
 * whose access units get caption SEI messages naming their picture, decoded into frames whose side data must hold
 * the captions of their own picture in output order; frames with captions encoded by Smalt, whose SEI messages
 * Smalt and ffmpeg's {@code cc_dec} (black box) must read back; and a transcode that keeps the captions in the
 * re-encoded video and writes them as a SubRip track.
 */
@DisplayName("closed captions in H.264 and HEVC")
class H264CaptionTest {
    private static final Rational FRAME = new Rational(1001, 30000);

    static Path directory() {
        return Scratch.directory("h264-captions");
    }

    /**
     * Returns the caption data a picture carries: one valid field 1 construct whose bytes name the picture.
     */
    static byte[] marker(int picture) {
        return new byte[]{(byte) 0xFC, (byte) CaptionScript.parity(0x20 + picture / 90),
                (byte) CaptionScript.parity(0x20 + picture % 90)};
    }

    static int index(Track track, long pts) {
        return (int) Math.round(track.timeBase().toMicros(pts) / (double) FRAME.toMicros(1));
    }

    /**
     * Encodes video with ffmpeg and rewrites it to Matroska with a caption SEI message in every access unit.
     */
    static Path captioned(String codec, int bFrames, Map<Integer, byte[]> data) {
        var raw = directory().resolve("raw-" + codec + "-" + bFrames + ".mkv");
        Oracle.assumeEncoder(codec);
        var arguments = new ArrayList<>(List.of("-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
                "testsrc=s=96x64:r=30000/1001:d=2", "-c:v", codec, "-pix_fmt", "yuv420p"));
        if (codec.equals("libx264")) {
            arguments.addAll(List.of("-bf", Integer.toString(bFrames)));
        } else {
            arguments.addAll(List.of("-x265-params", "bframes=" + bFrames + ":log-level=error"));
        }
        arguments.add(raw.toString());
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), arguments);
        var output = MediaSink.memory();
        try (var demuxer = Containers.openDemuxer(MediaSource.of(raw));
             var muxer = new MatroskaMuxer(output)) {
            var video = demuxer.tracks().getFirst();
            muxer.addTrack(video);
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                var picture = index(video, packet.pts());
                var captions = data.computeIfAbsent(picture, H264CaptionTest::marker);
                muxer.writePacket(packet.withData(CaptionScript.withSei(packet.data(), captions,
                        video.codec() == Codec.HEVC)));
            }
            muxer.finish();
        }
        var file = directory().resolve("captioned-" + codec + "-" + bFrames + ".mkv");
        try {
            Files.write(file, output.toByteArray());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return file;
    }

    static List<VideoFrame> decode(Path file) {
        var frames = new ArrayList<VideoFrame>();
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            var video = (VideoTrack) demuxer.tracks().getFirst();
            try (VideoDecoder decoder = video.codec() == Codec.HEVC ? new HevcDecoder(video) : new H264Decoder(video)) {
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    decoder.decode(packet, frames::add);
                }
                decoder.flush(frames::add);
            }
        }
        return frames;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"libx264", "libx265"})
    @DisplayName("attach the captions of each picture to its frame, in output order, with B pictures")
    void decoders(String codec) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var data = new HashMap<Integer, byte[]>();
        var file = captioned(codec, 2, data);
        var frames = decode(file);
        assertEquals(data.size(), frames.size());
        Track track;
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            track = demuxer.tracks().getFirst();
        }
        for (var frame : frames) {
            var expected = data.get(index(track, frame.pts()));
            assertArrayEquals(expected, frame.sideData().closedCaptions().orElseThrow(), "frame at " + frame.pts());
        }
    }

    static CaptionScript script() {
        return new CaptionScript().command(2, 1, 0x20).command(4, 1, 0x2E).preamble(6, 1, 15, 0)
                .text(8, 1, "Encoded by Smalt").command(20, 1, 0x2F).command(50, 1, 0x2C);
    }

    @Test
    @DisplayName("write the captions of frames without timestamps into the packets of those frames")
    void encoderWithoutTimestamps() {
        var frames = script().build(12, 2);
        var options = H264EncoderOptions.builder().packetFormat(H264PacketFormat.ANNEX_B).frameRate(FRAME.invert())
                .timeBase(FRAME).build();
        var packets = new ArrayList<Packet>();
        try (var encoder = new H264Encoder(64, 48, options)) {
            var track = VideoTrack.builder().id(1).codec(Codec.H264).timeBase(encoder.timeBase()).width(64).height(48)
                    .build();
            for (var frame : frames) {
                var picture = YuvPicture.allocate(64, 48, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
                var side = FrameSideData.builder().closedCaptions(frame).build();
                encoder.encode(new VideoFrame(picture, Packet.NO_TIMESTAMP, 1, false, side), packets::add);
            }
            encoder.flush(packets::add);
            assertEquals(frames.length, packets.size());
            for (var packet : packets) {
                assertArrayEquals(frames[(int) packet.pts()], ClosedCaptions.ccData(track, packet),
                        "packet " + packet.pts());
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"10-bit", "field pictures", "field pictures with a reference decoder"})
    @DisplayName("write the captions of frames the extended engine codes into the packets of those frames, once")
    void extendedEngine(String format) {
        var frames = script().build(24, 2);
        var builder = H264EncoderOptions.builder().packetFormat(H264PacketFormat.ANNEX_B).frameRate(FRAME.invert())
                .timeBase(FRAME).bitrate(400_000);
        var options = switch (format) {
            case "10-bit" -> builder.profile(H264Profile.HIGH_10).bitDepth(10);
            case "field pictures" -> builder.profile(H264Profile.HIGH).fieldOrder(FieldOrder.TOP_FIRST)
                    .fieldCoding(H264FieldCoding.FIELD_PICTURES);
            default -> builder.profile(H264Profile.HIGH).fieldOrder(FieldOrder.BOTTOM_FIRST)
                    .fieldCoding(H264FieldCoding.FIELD_PICTURES).rateControl(H264RateControl.CONSTANT_BITRATE)
                    .hrd(true);
        };
        var packets = new ArrayList<Packet>();
        try (var encoder = new H264Encoder(64, 48, options.build())) {
            var track = VideoTrack.builder().id(1).codec(Codec.H264).timeBase(encoder.timeBase()).width(64).height(48)
                    .build();
            for (var index = 0; index < frames.length; index++) {
                var picture = YuvPicture.allocate(64, 48, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
                var side = FrameSideData.builder().closedCaptions(frames[index]).build();
                encoder.encode(new VideoFrame(picture, index, 1, false, side), packets::add);
            }
            encoder.flush(packets::add);
            assertEquals(frames.length, packets.size());
            for (var packet : packets) {
                assertArrayEquals(frames[(int) packet.pts()], ClosedCaptions.ccData(track, packet),
                        "packet " + packet.pts());
            }
        }
    }

    @Test
    @DisplayName("write the captions of frames back as SEI messages that Smalt and ffmpeg read")
    void encoder() {
        var frames = script().build(60, 2);
        var options = H264EncoderOptions.builder().packetFormat(H264PacketFormat.LENGTH_PREFIXED).frameRate(
                FRAME.invert()).timeBase(FRAME).build();
        var packets = new ArrayList<Packet>();
        var output = MediaSink.memory();
        try (var encoder = new H264Encoder(64, 48, options)) {
            var track = VideoTrack.builder().id(1).codec(Codec.H264).timeBase(encoder.timeBase()).width(64).height(48)
                    .codecConfig(encoder.codecConfig().orElseThrow()).build();
            for (var index = 0; index < frames.length; index++) {
                var picture = YuvPicture.allocate(64, 48, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
                var side = FrameSideData.builder().closedCaptions(frames[index]).build();
                encoder.encode(new VideoFrame(picture, index, 1, false, side), packets::add);
            }
            encoder.flush(packets::add);
            for (var packet : packets) {
                var expected = frames[(int) packet.pts()];
                assertArrayEquals(expected, ClosedCaptions.ccData(track, packet), "packet " + packet.pts());
            }
            try (var muxer = new MatroskaMuxer(output)) {
                muxer.addTrack(track);
                packets.forEach(packet -> muxer.writePacket(packet.withTrackId(track.id())));
                muxer.finish();
            }
        }
        var file = directory().resolve("encoded.mkv");
        try {
            Files.write(file, output.toByteArray());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        var decoded = decode(file);
        for (var index = 0; index < decoded.size(); index++) {
            assertArrayEquals(frames[index], decoded.get(index).sideData().closedCaptions().orElseThrow());
        }
        var cues = Subtitles.read(MediaSource.of(file), SubtitleReaderOptions.builder().embeddedCaptions(true).build());
        assertEquals(List.of("Encoded by Smalt"), cues.stream()
                .map(cue -> Subtitles.plainText(cue.text(), SubtitleMarkup.SUBRIP)).toList());
        Oracle.assume(Oracle.Tool.FFMPEG);
        var srt = directory().resolve("encoded.srt");
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "movie=" + file.toAbsolutePath().toString().replace('\\', '/')
                        .replace(":", "\\\\:") + "[out0+subcc]", "-map", "0:1", srt.toString()));
        try {
            assertTrue(Files.readString(srt).contains("Encoded by Smalt"));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Test
    @DisplayName("keep captions through a transcode, in the H.264 video and as a SubRip track")
    void transcode() {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var data = new HashMap<Integer, byte[]>();
        var frames = new CaptionScript().command(1, 1, 0x20).preamble(3, 1, 15, 0).text(5, 1, "kept")
                .command(15, 1, 0x2F).command(40, 1, 0x2C).build(70, 2);
        for (var index = 0; index < frames.length; index++) {
            data.put(index, frames[index]);
        }
        var input = captioned("libx264", 0, data);
        var reencoded = MediaSink.memory();
        Transcoder.transcode(MediaSource.of(input), reencoded, TranscodeOptions.builder()
                .container(OutputContainer.MATROSKA).video(VideoTranscodeOptions.encode(Codec.H264))
                .subtitles(SubtitleTranscodeOptions.builder().closedCaptions(true).codec(Codec.SUBRIP).build())
                .build());
        var bytes = reencoded.toByteArray();
        var embedded = Subtitles.read(MediaSource.of(bytes), SubtitleReaderOptions.builder().embeddedCaptions(true)
                .build());
        assertEquals(List.of("kept"), embedded.stream().map(cue -> Subtitles.plainText(cue.text(),
                SubtitleMarkup.SUBRIP)).toList());
        try (var reader = SubtitleReader.open(MediaSource.of(bytes))) {
            assertEquals(Codec.SUBRIP, reader.track().codec());
            var cue = reader.read();
            assertEquals("kept", cue.text());
            assertEquals(FRAME.toMicros(15), cue.startMicros(), 1000);
            assertEquals(FRAME.toMicros(40), cue.endMicros(), 1000);
        }
    }
}
