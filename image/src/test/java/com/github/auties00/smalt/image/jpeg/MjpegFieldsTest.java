package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Interlacing;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the interlaced frames of {@link MjpegDecoder}: two field images marked by OpenDML {@code AVI1} segments or
 * QuickTime Motion-JPEG format A {@code APP1 mjpg} segments decode woven in the order the track and the polarity
 * give, a single field fills an interlaced track, and second images that do not pair are ignored.
 */
@DisplayName("MjpegDecoder fields")
class MjpegFieldsTest {
    private static final JpegEncoderOptions OPTIONS = JpegEncoderOptions.ofQuality(90);

    private static byte[] field(int width, int height, ChromaFormat format, int frame) {
        return JpegEncoder.encode(MotionJpegTesting.yuvPattern(width, height, format, frame),
                OPTIONS.toBuilder().chromaFormat(format).build());
    }

    private static YuvPicture still(byte[] jpeg) {
        return (YuvPicture) JpegDecoder.decode(MediaSource.of(jpeg)).picture();
    }

    private static VideoTrack track(int width, int height, Interlacing scan) {
        var builder = VideoTrack.builder().id(1).codec(Codec.MJPEG).timeBase(new Rational(1, 1000)).width(width)
                .height(height);
        return scan == null ? builder.build() : builder.interlacing(scan).build();
    }

    private static Picture decode(VideoTrack track, byte[] frame) {
        var pictures = new ArrayList<Picture>();
        new MjpegDecoder(track).decode(new Packet(1, frame, 0, 0, 1, true), video -> pictures.add(video.picture()));
        return pictures.getFirst();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ChromaFormat.class)
    @DisplayName("weave two AVI1 fields, the first stored on top unless the track stores the bottom field first")
    void avi1(ChromaFormat format) {
        var first = field(48, 16, format, 1);
        var second = field(48, 16, format, 2);
        var frame = MotionJpegTesting.concat(MotionJpegTesting.withAvi1(first, 1, first.length),
                MotionJpegTesting.withAvi1(second, 2, second.length));
        var topFirst = MotionJpegTesting.planes(MotionJpegTesting.weave(still(first), still(second), 32));
        var bottomFirst = MotionJpegTesting.planes(MotionJpegTesting.weave(still(second), still(first), 32));
        assertArrayEquals(topFirst, MotionJpegTesting.planes(new MjpegDecoder().decodeFrame(frame, 0, frame.length)));
        for (var scan : Interlacing.values()) {
            var expected = scan == Interlacing.BOTTOM_FIELD_FIRST || scan == Interlacing.BOTTOM_FIELD_FIRST_SEPARATED
                    ? bottomFirst : topFirst;
            assertArrayEquals(expected, MotionJpegTesting.planes(decode(track(48, 32, scan), frame)), scan.name());
        }
        assertArrayEquals(topFirst, MotionJpegTesting.planes(decode(track(48, 32, null), frame)));
        var plain = MotionJpegTesting.concat(first, second);
        assertArrayEquals(topFirst, MotionJpegTesting.planes(new MjpegDecoder().decodeFrame(plain, 0, plain.length)));
    }

    @Test
    @DisplayName("take the field an AVI1 segment marks as first in time as the first field")
    void polarity() {
        var a = field(32, 8, ChromaFormat.YUV422, 1);
        var b = field(32, 8, ChromaFormat.YUV422, 2);
        var frame = MotionJpegTesting.concat(MotionJpegTesting.withAvi1(a, 2, a.length),
                MotionJpegTesting.withAvi1(b, 1, b.length));
        assertArrayEquals(MotionJpegTesting.planes(MotionJpegTesting.weave(still(b), still(a), 16)),
                MotionJpegTesting.planes(decode(track(32, 16, Interlacing.INTERLACED), frame)));
    }

    @Test
    @DisplayName("find the second field through the AVI1 field size across padding that is not fill bytes")
    void fieldSize() {
        var a = field(32, 8, ChromaFormat.YUV420, 1);
        var b = field(32, 8, ChromaFormat.YUV420, 2);
        var padding = new byte[]{1, 2, 3, 4, 5};
        var frame = MotionJpegTesting.concat(MotionJpegTesting.withAvi1(a, 1, a.length + padding.length), padding,
                MotionJpegTesting.withAvi1(b, 2, b.length), new byte[]{(byte) 0xFF, (byte) 0xFF, 0, 0});
        assertArrayEquals(MotionJpegTesting.planes(MotionJpegTesting.weave(still(a), still(b), 16)),
                MotionJpegTesting.planes(new MjpegDecoder().decodeFrame(frame, 0, frame.length)));
        var unmarked = MotionJpegTesting.concat(a, padding, b);
        assertArrayEquals(MotionJpegTesting.planes(still(a)),
                MotionJpegTesting.planes(new MjpegDecoder().decodeFrame(unmarked, 0, unmarked.length)));
    }

    @Test
    @DisplayName("weave QuickTime Motion-JPEG format A fields through their APP1 next-field offsets")
    void formatA() {
        var a = field(40, 12, ChromaFormat.YUV420, 3);
        var b = field(40, 12, ChromaFormat.YUV420, 4);
        var firstField = MotionJpegTesting.withMjpgSegment(a, a.length + 44 + 7, 7);
        // padding that is not fill bytes, so the second field is found only through the next-field offset
        Arrays.fill(firstField, firstField.length - 7, firstField.length, (byte) 0x11);
        var frame = MotionJpegTesting.concat(firstField, MotionJpegTesting.withMjpgSegment(b, 0, 0));
        var expected = MotionJpegTesting.planes(MotionJpegTesting.weave(still(a), still(b), 24));
        assertArrayEquals(expected, MotionJpegTesting.planes(decode(track(40, 24, Interlacing.TOP_FIELD_FIRST_SEPARATED),
                frame)));
        assertArrayEquals(MotionJpegTesting.planes(MotionJpegTesting.weave(still(b), still(a), 24)),
                MotionJpegTesting.planes(decode(track(40, 24, Interlacing.BOTTOM_FIELD_FIRST_SEPARATED), frame)));
    }

    @Test
    @DisplayName("go into QuickTime as mjpa when they are format A, and ffmpeg weaves them as Smalt does")
    void formatAInQuickTime(@TempDir Path directory) throws IOException {
        var frames = new ArrayList<byte[]>();
        for (var i = 0; i < 3; i++) {
            var a = field(64, 24, ChromaFormat.YUV422, 2 * i);
            var b = field(64, 24, ChromaFormat.YUV422, 2 * i + 1);
            var first = MotionJpegTesting.withMjpgSegment(a, a.length + 44, 0);
            frames.add(MotionJpegTesting.concat(first, MotionJpegTesting.withMjpgSegment(b, 0, 0)));
        }
        var sink = MediaSink.memory();
        var track = track(64, 48, Interlacing.TOP_FIELD_FIRST_SEPARATED).toBuilder().timeBase(new Rational(1, 25))
                .build();
        try (var muxer = new Mp4Muxer(sink, Mp4MuxerOptions.quickTime())) {
            muxer.addTrack(track);
            for (var i = 0; i < frames.size(); i++) {
                muxer.writePacket(new Packet(1, frames.get(i), i, i, 1, true));
            }
            muxer.finish();
        }
        var file = sink.toByteArray();
        assertTrue(new String(file, StandardCharsets.ISO_8859_1).contains("mjpa"));
        var stream = MotionJpegTesting.demux(file);
        assertEquals(Interlacing.TOP_FIELD_FIRST_SEPARATED, stream.track().interlacing().orElseThrow());
        var pictures = new ArrayList<Picture>();
        try (var decoder = VideoDecoders.create(stream.track())) {
            for (var packet : stream.packets()) {
                decoder.decode(packet, frame -> pictures.add(frame.picture()));
            }
        }
        for (var i = 0; i < frames.size(); i++) {
            var data = frames.get(i);
            assertArrayEquals(MotionJpegTesting.planes(new MjpegDecoder().decodeFrame(data, 0, data.length)),
                    MotionJpegTesting.planes(pictures.get(i)));
            assertEquals(48, pictures.get(i).height());
        }
        Oracle.assume(Oracle.Tool.FFMPEG);
        var path = directory.resolve("mjpa.mov");
        Files.write(path, file);
        MotionJpegTesting.assertCloseToFfmpeg(Oracle.decodeVideo(path, "yuvj422p", 0), pictures, 3, 0.5);
    }

    @Test
    @DisplayName("weave fields of odd height and crop to a lower track")
    void oddHeights() {
        var top = MotionJpegTesting.yuvPattern(24, 13, ChromaFormat.YUV420, 1);
        var bottom = MotionJpegTesting.yuvPattern(24, 12, ChromaFormat.YUV420, 2);
        var a = JpegEncoder.encode(top, OPTIONS);
        var b = JpegEncoder.encode(bottom, OPTIONS);
        var frame = MotionJpegTesting.concat(a, b);
        var woven = assertInstanceOf(YuvPicture.class, decode(track(24, 25, Interlacing.INTERLACED), frame));
        assertEquals(25, woven.height());
        assertArrayEquals(MotionJpegTesting.planes(MotionJpegTesting.weave(still(a), still(b), 25)),
                MotionJpegTesting.planes(woven));
        var cropped = decode(track(24, 24, Interlacing.INTERLACED), frame);
        assertArrayEquals(MotionJpegTesting.planes(MotionJpegTesting.weave(still(a), still(b), 24)),
                MotionJpegTesting.planes(cropped));
        var swapped = MotionJpegTesting.concat(b, a);
        assertArrayEquals(MotionJpegTesting.planes(MotionJpegTesting.weave(still(a), still(b), 25)),
                MotionJpegTesting.planes(decode(track(24, 25, Interlacing.BOTTOM_FIELD_FIRST_SEPARATED), swapped)));
    }

    @Test
    @DisplayName("fill an interlaced track from a single field, and leave progressive frames alone")
    void singleField() {
        var a = field(32, 12, ChromaFormat.YUV422, 5);
        var marked = MotionJpegTesting.withAvi1(a, 1, a.length);
        var doubled = MotionJpegTesting.planes(MotionJpegTesting.weave(still(a), still(a), 24));
        assertArrayEquals(doubled, MotionJpegTesting.planes(decode(track(32, 24, null), marked)));
        assertArrayEquals(doubled, MotionJpegTesting.planes(decode(track(32, 24, Interlacing.INTERLACED), a)));
        var odd = decode(track(32, 23, Interlacing.INTERLACED), a);
        assertEquals(23, odd.height());
        var single = MotionJpegTesting.planes(still(a));
        assertArrayEquals(single, MotionJpegTesting.planes(decode(track(32, 24, null), a)));
        assertArrayEquals(single, MotionJpegTesting.planes(decode(track(32, 12, Interlacing.INTERLACED), marked)));
        assertArrayEquals(single, MotionJpegTesting.planes(decode(track(32, 25, Interlacing.INTERLACED), marked)));
        assertArrayEquals(single, MotionJpegTesting.planes(new MjpegDecoder().decodeFrame(marked, 0, marked.length)));
    }

    @Test
    @DisplayName("ignore a second image that does not pair with the first or when the first fills the track")
    void unpaired() {
        var a = field(32, 12, ChromaFormat.YUV422, 1);
        var single = MotionJpegTesting.planes(still(a));
        var cases = List.of(MotionJpegTesting.concat(a, field(16, 12, ChromaFormat.YUV422, 2)),
                MotionJpegTesting.concat(a, field(32, 12, ChromaFormat.YUV444, 2)),
                MotionJpegTesting.concat(a, field(32, 9, ChromaFormat.YUV422, 2)));
        for (var frame : cases) {
            assertArrayEquals(single, MotionJpegTesting.planes(new MjpegDecoder().decodeFrame(frame, 0, frame.length)));
        }
        var paired = MotionJpegTesting.concat(a, field(32, 12, ChromaFormat.YUV422, 2));
        assertArrayEquals(single, MotionJpegTesting.planes(decode(track(32, 12, Interlacing.INTERLACED), paired)));
        assertEquals(24, new MjpegDecoder().decodeFrame(paired, 0, paired.length).height());
    }

    @Test
    @DisplayName("decode the first field alone, doubled in an interlaced track, when the second image is damaged")
    void damagedSecondField() {
        var a = field(32, 12, ChromaFormat.YUV420, 1);
        var b = field(32, 12, ChromaFormat.YUV420, 2);
        // a stale or damaged second image: SOI, then a segment whose length runs past the end of the frame
        var header = Arrays.copyOf(b, 40);
        header[4] = (byte) 0xFF;
        header[5] = (byte) 0xDB;
        header[6] = (byte) 0x7F;
        header[7] = (byte) 0xFF;
        var frame = MotionJpegTesting.concat(MotionJpegTesting.withAvi1(a, 1, a.length), header);
        var unmarked = MotionJpegTesting.concat(a, header);
        var single = MotionJpegTesting.planes(still(a));
        var doubled = MotionJpegTesting.planes(MotionJpegTesting.weave(still(a), still(a), 24));
        assertArrayEquals(single, MotionJpegTesting.planes(new MjpegDecoder().decodeFrame(unmarked, 0,
                unmarked.length)));
        assertArrayEquals(single, MotionJpegTesting.planes(new MjpegDecoder().decodeFrame(frame, 0, frame.length)));
        assertArrayEquals(doubled, MotionJpegTesting.planes(decode(track(32, 24, null), frame)));
        assertArrayEquals(doubled, MotionJpegTesting.planes(decode(track(32, 24, Interlacing.INTERLACED), unmarked)));
        // a second image that does not pair is treated the same way
        var unpaired = MotionJpegTesting.concat(a, field(32, 12, ChromaFormat.YUV444, 2));
        assertArrayEquals(doubled, MotionJpegTesting.planes(decode(track(32, 24, Interlacing.INTERLACED), unpaired)));
        var shorterTop = MotionJpegTesting.concat(field(32, 11, ChromaFormat.YUV420, 1), b);
        assertEquals(22, decode(track(32, 22, Interlacing.TOP_FIELD_FIRST_SEPARATED), shorterTop).height());
    }

    @Test
    @DisplayName("weave 12-bit fields at 12 bits under highBitDepth")
    void twelveBit() throws IOException {
        byte[] jpeg;
        try (var input = MjpegFieldsTest.class.getResourceAsStream("cjpeg12/33x65-yuv420-baseline.jpg")) {
            jpeg = input.readAllBytes();
        }
        var frame = MotionJpegTesting.concat(jpeg, jpeg);
        var options = VideoDecoderOptions.builder().highBitDepth(true).build();
        var woven = assertInstanceOf(Yuv16Picture.class, new MjpegDecoder(options).decodeFrame(frame, 0, frame.length));
        assertEquals(130, woven.height());
        assertEquals(12, woven.bitDepth());
        var field = (Yuv16Picture) JpegDecoder.decode(MediaSource.of(jpeg),
                ImageDecoderOptions.builder().highBitDepth(true).build()).picture();
        for (var y = 0; y < 130; y++) {
            for (var x = 0; x < 33; x++) {
                assertEquals(field.y()[(y >> 1) * field.yStride() + x], woven.y()[y * woven.yStride() + x]);
            }
        }
        for (var y = 0; y < woven.chromaHeight(); y++) {
            for (var x = 0; x < woven.chromaWidth(); x++) {
                assertEquals(field.u()[(y >> 1) * field.uvStride() + x], woven.u()[y * woven.uvStride() + x]);
                assertEquals(field.v()[(y >> 1) * field.uvStride() + x], woven.v()[y * woven.uvStride() + x]);
            }
        }
        var eightBit = assertInstanceOf(YuvPicture.class, new MjpegDecoder().decodeFrame(frame, 0, frame.length));
        assertEquals(130, eightBit.height());
    }

    @Nested
    @DisplayName("damaged frames")
    class Damaged {
        @Test
        @DisplayName("raise nothing but MediaException")
        void fuzz() {
            var a = field(32, 16, ChromaFormat.YUV420, 1);
            var b = field(32, 16, ChromaFormat.YUV420, 2);
            var frames = List.of(MotionJpegTesting.concat(MotionJpegTesting.withAvi1(a, 1, a.length),
                            MotionJpegTesting.withAvi1(b, 2, b.length)),
                    MotionJpegTesting.concat(MotionJpegTesting.withMjpgSegment(a, a.length + 44, 0),
                            MotionJpegTesting.withMjpgSegment(b, 0, 0)));
            var random = new Random(7);
            var interlaced = new MjpegDecoder(track(32, 32, Interlacing.INTERLACED));
            for (var trial = 0; trial < 600; trial++) {
                var damaged = frames.get(trial % 2).clone();
                for (var k = 0; k < 1 + random.nextInt(8); k++) {
                    damaged[random.nextInt(damaged.length)] = (byte) random.nextInt(256);
                }
                var length = random.nextInt(4) == 0 ? random.nextInt(damaged.length) : damaged.length;
                var input = Arrays.copyOf(damaged, length);
                try {
                    interlaced.decodeFrame(input, 0, input.length);
                } catch (MediaException expected) {
                    assertTrue(expected.getMessage() != null);
                }
            }
        }
    }
}
