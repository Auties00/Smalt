package com.github.auties00.smalt.property;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.video.VideoReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seeking then decoding equals decoding linearly: for every video codec Smalt encodes, in every container that
 * carries it, a {@link VideoReader} seeked to a sampled time (frame starts, instants inside a frame and inside a group
 * of pictures, the first and last frames, before the start and past the end) returns the frame a linear read shows at
 * that time, pixel for pixel, and then the frames that follow it in the linear read.
 */
@DisplayName("Video seek then decode")
class VideoSeekTest {
    private static final int FRAMES = 24;

    record Frame(long micros, String digest) {
    }

    static Stream<Arguments> cases() {
        var arguments = new ArrayList<Arguments>();
        for (var codec : List.of(Codec.H264, Codec.HEVC, Codec.VP8, Codec.VP9, Codec.AV1, Codec.MJPEG,
                Codec.JPEG2000, Codec.PRORES, Codec.DNXHD)) {
            for (var container : OutputContainer.values()) {
                if (container.supports(codec) && container != OutputContainer.H264 && container != OutputContainer.AV1
                        && container != OutputContainer.HEVC) {
                    arguments.add(Arguments.of(codec, container));
                }
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0} in {1}")
    @MethodSource("cases")
    @DisplayName("returns after a seek the frame and the frames a linear read returns from the target on")
    void seek(Codec codec, OutputContainer container) {
        // H.264 and HEVC in file mode: length-prefixed packets with their configuration record, which MP4 needs
        var stream = Streams.video(codec, 96, 64, FRAMES, builder -> builder
                .realtime(codec != Codec.H264 && codec != Codec.HEVC).keyframeInterval(6));
        var sink = MediaSink.memory();
        try (var muxer = Containers.openMuxer(container, sink)) {
            muxer.addTrack(stream.track());
            stream.packets().forEach(muxer::writePacket);
            muxer.finish();
        }
        var file = sink.toByteArray();
        com.github.auties00.smalt.testkit.validate.Validators.assertValid("seek-" + container.name().toLowerCase(java.util.Locale.ROOT) + "."
                + container.extension(), file);
        var linear = new ArrayList<Frame>();
        try (var reader = VideoReader.open(MediaSource.of(file))) {
            for (var frame = reader.read(); frame != null; frame = reader.read()) {
                linear.add(new Frame(reader.track().timeBase().toMicros(frame.pts()), digest(frame)));
            }
        }
        assertEquals(FRAMES, linear.size(), codec + " in " + container + " linear frames");
        var period = 40_000L;
        var targets = new ArrayList<Long>(List.of(-100_000L, 0L, 1L, period - 1, period, 5 * period + 20_000,
                6 * period, 6 * period - 1, 17 * period + 39_999, (FRAMES - 1) * period, (FRAMES - 1) * period + 39_000,
                FRAMES * period + 10_000));
        var random = new Random(codec.ordinal() * 31L + container.ordinal());
        for (var index = 0; index < 6; index++) {
            targets.add((long) random.nextInt(FRAMES * 40_000));
        }
        try (var reader = VideoReader.open(MediaSource.of(file))) {
            assertTrue(reader.isSeekable(), codec + " in " + container + " is seekable");
            for (var target : targets) {
                reader.seek(target);
                var expected = shownAt(linear, target);
                var what = codec + " in " + container + " seeked to " + target + " us";
                if (expected < 0) {
                    assertNull(reader.read(), what + " returns nothing past the end");
                    continue;
                }
                for (var next = expected; next < Math.min(linear.size(), expected + 3); next++) {
                    var frame = reader.read();
                    assertTrue(frame != null, what + " ended early");
                    var got = new Frame(reader.track().timeBase().toMicros(frame.pts()), digest(frame));
                    assertEquals(linear.get(next), got, what + ", frame " + (next - expected) + " after the seek");
                }
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "into {0}")
    @org.junit.jupiter.params.provider.EnumSource(value = OutputContainer.class, names = {"WEBM", "MP4", "MATROSKA"})
    @DisplayName("a transcode of an AVI with B-frames, whose timestamps are in decoding order, keeps every frame")
    void decodeOrderTimestamps(OutputContainer target) {
        var stream = Streams.video(Codec.H264, 96, 64, FRAMES, builder -> builder.realtime(false).keyframeInterval(6));
        var sink = MediaSink.memory();
        try (var muxer = Containers.openMuxer(OutputContainer.AVI, sink)) {
            muxer.addTrack(stream.track());
            stream.packets().forEach(muxer::writePacket);
            muxer.finish();
        }
        var avi = sink.toByteArray();
        var times = new ArrayList<Long>();
        try (var reader = VideoReader.open(MediaSource.of(avi))) {
            for (var frame = reader.read(); frame != null; frame = reader.read()) {
                times.add(reader.track().timeBase().toMicros(frame.pts()));
            }
        }
        var expected = new ArrayList<Long>();
        for (var index = 0; index < FRAMES; index++) {
            expected.add(index * 40_000L);
        }
        assertEquals(expected, times, "presentation times of the AVI frames");
        var output = MediaSink.memory();
        com.github.auties00.smalt.transcode.Transcoder.transcode(MediaSource.of(avi), output,
                com.github.auties00.smalt.transcode.TranscodeOptions.of(target));
        var count = 0;
        try (var reader = VideoReader.open(MediaSource.of(output.toByteArray()))) {
            while (reader.read() != null) {
                count++;
            }
        }
        assertEquals(FRAMES, count, "frames after transcoding to " + target);
    }

    /** Returns the index of the frame a linear read shows at a time, or -1 past the last frame. */
    private static int shownAt(List<Frame> frames, long micros) {
        if (micros < 0) {
            return 0;
        }
        var end = frames.getLast().micros() + (frames.size() > 1 ? frames.getLast().micros()
                - frames.get(frames.size() - 2).micros() : 40_000);
        if (micros >= end) {
            return -1;
        }
        var shown = 0;
        for (var index = 0; index < frames.size(); index++) {
            if (frames.get(index).micros() <= micros) {
                shown = index;
            }
        }
        return shown;
    }

    private static String digest(com.github.auties00.smalt.video.VideoFrame frame) {
        return ThreadDigest.sha256(ImageOps.toRgba(frame.picture()).pixels());
    }
}
