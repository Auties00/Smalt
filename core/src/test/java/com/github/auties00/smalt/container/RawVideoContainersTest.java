package com.github.auties00.smalt.container;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.internal.RawVideoTags;
import com.github.auties00.smalt.container.y4m.Y4mDemuxer;
import com.github.auties00.smalt.container.y4m.Y4mMuxer;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Interlacing;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.transcode.TranscodeOptions;
import com.github.auties00.smalt.transcode.Transcoder;
import com.github.auties00.smalt.transcode.VideoTranscodeOptions;
import com.github.auties00.smalt.video.RawPixelFormat;
import com.github.auties00.smalt.video.RawVideoDecoder;
import com.github.auties00.smalt.video.RawVideoEncoder;
import com.github.auties00.smalt.video.RawVideoFormat;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers uncompressed video: {@link RawVideoFormat}, {@link RawVideoDecoder} and {@link RawVideoEncoder}, the raw
 * video tracks of MOV, Matroska and AVI files, and YUV4MPEG2 ({@link Y4mDemuxer}, {@link Y4mMuxer}). Every decode is
 * compared exactly with ffmpeg's decode of the same file, converted by ffmpeg to a format that holds the same
 * samples (planar YUV for packed YUV, RGBA for RGB and palettes).
 */
@DisplayName("Uncompressed video")
class RawVideoContainersTest {
    private static final String SOURCE = "testsrc2=size=64x48:rate=10:duration=0.3";

    static Path file(String extension, int width, int height, List<String> options) {
        var output = new ArrayList<>(List.of("-vf", "scale=" + width + ":" + height, "-frames:v", "3"));
        output.addAll(options);
        return Fixtures.ffmpeg(extension, List.of("-f", "lavfi", "-i", SOURCE), output);
    }

    /** Decodes every frame of the first track and returns the pictures' samples, tightly packed. */
    static List<byte[]> decode(Demuxer demuxer) {
        var track = (VideoTrack) demuxer.tracks().getFirst();
        assertEquals(Codec.RAW_VIDEO, track.codec());
        var frames = new ArrayList<byte[]>();
        try (var decoder = VideoDecoders.create(track)) {
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                decoder.decode(packet, frame -> frames.add(samples(frame.picture())));
            }
        }
        return frames;
    }

    /**
     * Returns the samples of a picture in ffmpeg's matching raw layout: RGBA bytes, RGBA 16-bit little-endian
     * samples, or planar YUV (Y, U, V, then alpha) of one or two bytes per sample.
     */
    static byte[] samples(Picture picture) {
        var out = new ByteArrayOutputStream();
        switch (picture) {
            case RgbaPicture rgba -> {
                for (var y = 0; y < rgba.height(); y++) {
                    out.write(rgba.pixels(), y * rgba.stride(), 4 * rgba.width());
                }
            }
            case Rgba16Picture rgba -> {
                for (var y = 0; y < rgba.height(); y++) {
                    for (var x = 0; x < 4 * rgba.width(); x++) {
                        var value = rgba.pixels()[y * rgba.stride() + x];
                        out.write(value);
                        out.write(value >> 8);
                    }
                }
            }
            case YuvPicture yuv -> {
                for (var y = 0; y < yuv.height(); y++) {
                    out.write(yuv.y(), y * yuv.yStride(), yuv.width());
                }
                if (yuv.chromaFormat() != ChromaFormat.MONOCHROME) {
                    for (var plane : new byte[][]{yuv.u(), yuv.v()}) {
                        for (var y = 0; y < yuv.chromaHeight(); y++) {
                            out.write(plane, y * yuv.uvStride(), yuv.chromaWidth());
                        }
                    }
                }
                if (yuv.alpha() != null) {
                    for (var y = 0; y < yuv.height(); y++) {
                        out.write(yuv.alpha(), y * yuv.alphaStride(), yuv.width());
                    }
                }
            }
            case Yuv16Picture yuv -> {
                var planes = new ArrayList<short[]>();
                planes.add(yuv.y());
                if (yuv.chromaFormat() != ChromaFormat.MONOCHROME) {
                    planes.add(yuv.u());
                    planes.add(yuv.v());
                }
                if (yuv.alpha() != null) {
                    planes.add(yuv.alpha());
                }
                for (var index = 0; index < planes.size(); index++) {
                    var luma = index == 0 || index == 3;
                    var width = luma ? yuv.width() : yuv.chromaWidth();
                    var height = luma ? yuv.height() : yuv.chromaHeight();
                    var stride = index == 0 ? yuv.yStride() : index == 3 ? yuv.alphaStride() : yuv.uvStride();
                    for (var y = 0; y < height; y++) {
                        for (var x = 0; x < width; x++) {
                            var value = planes.get(index)[y * stride + x];
                            out.write(value);
                            out.write(value >> 8);
                        }
                    }
                }
            }
            default -> throw new AssertionError(picture.getClass());
        }
        return out.toByteArray();
    }

    /** Compares our frames with ffmpeg's decode converted to a pixel format. */
    static void assertMatchesFfmpeg(Path file, List<byte[]> ours, String pixelFormat) {
        var reference = Oracle.decodeVideo(file, pixelFormat, 0);
        var frame = ours.getFirst().length;
        assertEquals(reference.length, frame * ours.size(), "decoded size against ffmpeg's " + pixelFormat);
        for (var i = 0; i < ours.size(); i++) {
            assertArrayEquals(Arrays.copyOfRange(reference, i * frame, (i + 1) * frame), ours.get(i),
                    "frame " + i + " against ffmpeg's " + pixelFormat);
        }
    }

    @Nested
    @DisplayName("containers")
    class Containers {
        @ParameterizedTest(name = "{0} {1} as {2}")
        @CsvSource({
                "mov, uyvy422, yuv422p", "mov, yuyv422, yuv422p", "mov, rgb24, rgba", "mov, argb, rgba",
                "mov, bgra, rgba", "mov, abgr, rgba", "mov, rgba, rgba", "mov, bgr24, rgba",
                "mov, rgb555be, rgba", "mov, rgb555le, rgba", "mov, rgb565le, rgba", "mov, rgb565be, rgba",
                "mov, gray, rgba", "mov, pal8, rgba", "mov, gray16be, gray16le", "mov, rgb48be, rgba64le",
                "avi, yuv420p, yuv420p", "avi, uyvy422, yuv422p", "avi, yuyv422, yuv422p", "avi, yvyu422, yuv422p",
                "avi, bgr24, rgba", "avi, bgra, rgba", "avi, rgb555le, rgba", "avi, gray, gray", "avi, pal8, rgba",
                "avi, nv12, yuv420p", "avi, nv21, yuv420p", "avi, yuv422p, yuv422p", "avi, rgba, rgba",
                "mkv, yuv420p, yuv420p", "mkv, uyvy422, yuv422p", "mkv, yuyv422, yuv422p", "mkv, yvyu422, yuv422p",
                "mkv, gray, gray", "mkv, nv12, yuv420p", "mkv, nv21, yuv420p", "mkv, yuv422p, yuv422p",
                "mkv, rgba, rgba"
        })
        @DisplayName("decode exactly as ffmpeg decodes them")
        void decodes(String container, String pixelFormat, String reference) {
            var file = file(container, 33, 17, List.of("-c:v", "rawvideo", "-pix_fmt", pixelFormat));
            try (var demuxer = com.github.auties00.smalt.container.Containers.openDemuxer(
                    MediaSource.of(Fixtures.read(file)))) {
                var track = (VideoTrack) demuxer.tracks().getFirst();
                assertEquals(33, track.width());
                assertEquals(17, track.height());
                var frames = decode(demuxer);
                assertEquals(3, frames.size());
                assertMatchesFfmpeg(file, frames, reference);
            }
        }

        @Test
        @DisplayName("decode 4:1:1 to 4:2:2 by repeating each chroma sample")
        void yuv411() {
            for (var container : List.of("avi", "mkv")) {
                var file = file(container, 33, 17, List.of("-c:v", "rawvideo", "-pix_fmt", "yuv411p"));
                List<byte[]> frames;
                try (var demuxer = com.github.auties00.smalt.container.Containers.openDemuxer(
                        MediaSource.of(Fixtures.read(file)))) {
                    frames = decode(demuxer);
                }
                var reference = Oracle.decodeVideo(file, "yuv411p", 0);
                var lumaSize = 33 * 17;
                var quarter = 9 * 17;
                var half = 17 * 17;
                for (var i = 0; i < frames.size(); i++) {
                    var base = i * (lumaSize + 2 * quarter);
                    var frame = frames.get(i);
                    assertArrayEquals(Arrays.copyOfRange(reference, base, base + lumaSize),
                            Arrays.copyOfRange(frame, 0, lumaSize));
                    for (var plane = 0; plane < 2; plane++) {
                        for (var y = 0; y < 17; y++) {
                            for (var x = 0; x < 17; x++) {
                                assertEquals(reference[base + lumaSize + plane * quarter + y * 9 + x / 2],
                                        frame[lumaSize + plane * half + y * 17 + x], container + " chroma " + plane);
                            }
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("decode v210 exactly as ffmpeg decodes it")
        void v210() {
            var file = file("mov", 34, 17, List.of("-c:v", "v210"));
            try (var demuxer = com.github.auties00.smalt.container.Containers.openDemuxer(
                    MediaSource.of(Fixtures.read(file)))) {
                var frames = decode(demuxer);
                assertMatchesFfmpeg(file, frames, "yuv422p10le");
            }
        }
    }

    @Nested
    @DisplayName("YUV4MPEG2")
    class Y4m {
        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "yuv420p, 420jpeg", "yuv422p, 422", "yuv444p, 444", "yuv411p, 411", "gray, mono",
                "yuv420p10le, 420p10", "yuv422p12le, 422p12", "yuv444p16le, 444p16", "gray16le, mono16",
                "yuva444p, 444alpha", "gray10le, mono10", "yuv420p9le, 420p9"
        })
        @DisplayName("decodes exactly as ffmpeg does and writes back a file ffmpeg reads identically")
        void roundTrip(String pixelFormat, String colorspace) {
            var file = file("y4m", 33, 17, List.of("-pix_fmt", pixelFormat, "-strict", "-1"));
            var data = Fixtures.read(file);
            assertEquals(ContainerFormat.Y4M, com.github.auties00.smalt.container.Containers.detect(
                    MediaSource.of(data)).orElseThrow());
            List<Packet> packets;
            VideoTrack track;
            try (var demuxer = com.github.auties00.smalt.container.Containers.openDemuxer(MediaSource.of(data))) {
                track = (VideoTrack) demuxer.tracks().getFirst();
                assertEquals(new Rational(10, 1), track.frameRate().orElseThrow());
                assertEquals(new Rational(1, 10), track.timeBase());
                assertEquals(300_000, demuxer.durationMicros());
                packets = ElementaryDemuxersTest.readAll(demuxer);
                assertEquals(3, packets.size());
                for (var i = 0; i < 3; i++) {
                    assertEquals(i, packets.get(i).pts());
                    assertTrue(packets.get(i).keyframe());
                }
            }
            try (var demuxer = new Y4mDemuxer(MediaSource.of(data))) {
                var frames = decode(demuxer);
                if (pixelFormat.equals("yuv411p")) {
                    assertEquals(3, frames.size());
                } else {
                    assertMatchesFfmpeg(file, frames, pixelFormat);
                }
            }
            var written = MuxSupport.toStream(List.of(track), packets, Y4mMuxer::new);
            var header = new String(written, 0, written.length >= 80 ? 80 : written.length,
                    StandardCharsets.ISO_8859_1).split("\n")[0];
            assertTrue(header.startsWith("YUV4MPEG2 W33 H17 F10:1 Ip "), header);
            assertTrue(header.contains(" C" + colorspace), header);
            var path = MuxSupport.save("ours.y4m", written);
            assertArrayEquals(Oracle.decodeVideo(file, pixelFormat, 0), Oracle.decodeVideo(path, pixelFormat, 0),
                    "ffmpeg reads our file as the original");
            try (var demuxer = new Y4mDemuxer(MediaSource.of(written))) {
                var again = ElementaryDemuxersTest.readAll(demuxer);
                assertEquals(packets.size(), again.size());
                for (var i = 0; i < again.size(); i++) {
                    assertArrayEquals(packets.get(i).data(), again.get(i).data());
                }
                assertEquals(track.codecConfig().map(Arrays::toString), demuxer.tracks().getFirst().codecConfig()
                        .map(Arrays::toString));
            }
        }

        @Test
        @DisplayName("is a transcode target: an I420 stream is copied, packed 4:2:2 frames are converted to 4:2:0")
        void transcode() throws java.io.IOException {
            var i420 = file("avi", 33, 17, List.of("-c:v", "rawvideo", "-pix_fmt", "yuv420p"));
            var copied = Scratch.directory("y4m-transcode").resolve("copied.y4m");
            Transcoder.transcode(i420, copied, TranscodeOptions.of(OutputContainer.Y4M));
            assertEquals(OutputContainer.Y4M, OutputContainer.forFileName("x.y4m").orElseThrow());
            var written = java.nio.file.Files.readAllBytes(copied);
            var header = new String(written, 0, Math.min(written.length, 100), StandardCharsets.ISO_8859_1);
            assertArrayEquals(Oracle.decodeVideo(i420, "yuv420p", 0), Oracle.decodeVideo(copied, "yuv420p", 0),
                    "copied frames of " + written.length + " bytes: " + header.lines().findFirst().orElse(""));
            // an even height: ffmpeg leaves the last chroma row of an odd-height 4:2:2 to 4:2:0 conversion at zero
            var packed = file("mov", 33, 18, List.of("-c:v", "rawvideo", "-pix_fmt", "uyvy422"));
            var converted = Scratch.directory("y4m-transcode").resolve("converted.y4m");
            // the odd size is kept, as YUV4MPEG2 stores 4:2:0 frames of any size
            Transcoder.transcode(packed, converted, TranscodeOptions.of(OutputContainer.Y4M).toBuilder()
                    .video(VideoTranscodeOptions.builder().evenDimensions(false).build()).build());
            var expected = Oracle.decodeVideo(packed, "yuv420p", 0);
            var actual = Oracle.decodeVideo(converted, "yuv420p", 0);
            var convertedBytes = java.nio.file.Files.readAllBytes(converted);
            var convertedHeader = new String(convertedBytes, 0, Math.min(convertedBytes.length, 100),
                    StandardCharsets.ISO_8859_1).lines().findFirst().orElse("");
            // the source's non-square samples are kept, declared by the A parameter
            assertTrue(convertedHeader.startsWith("YUV4MPEG2 W33 H18 F10:1 Ip A8:11 C420jpeg"), convertedHeader);
            assertEquals(expected.length, actual.length, "converted frames: " + convertedHeader);
            // luma is copied; chroma is downsampled to 4:2:0, by a filter of its own, so it compares by PSNR
            var frame = 33 * 18 + 2 * 17 * 9;
            var squared = 0.0;
            var count = 0;
            for (var i = 0; i < actual.length; i++) {
                if (i % frame < 33 * 18) {
                    assertEquals(expected[i], actual[i], "luma sample " + i);
                } else {
                    var difference = (expected[i] & 0xFF) - (actual[i] & 0xFF);
                    squared += difference * difference;
                    count++;
                }
            }
            var psnr = 10 * StrictMath.log10(255.0 * 255.0 / Math.max(1e-9, squared / count));
            assertTrue(psnr > 35, "chroma PSNR " + psnr);
        }

        @Test
        @DisplayName("reads the header parameters, frame parameters and chroma siting, and seeks to frames")
        void parameters() {
            var frame = new byte[4 * 2 + 2 * 2 * 1];
            var text = "YUV4MPEG2 W4 H2 F30000:1001 It A10:11 C420mpeg2 XCOLORRANGE=FULL XFOO=1\n"
                    + "FRAME\n" + "x".repeat(frame.length)
                    + "FRAME Ixyz\n" + "y".repeat(frame.length)
                    + "FRAME\n" + "z".repeat(frame.length);
            var data = text.getBytes(StandardCharsets.ISO_8859_1);
            try (var demuxer = new Y4mDemuxer(MediaSource.of(data))) {
                var track = (VideoTrack) demuxer.tracks().getFirst();
                assertEquals(4, track.width());
                assertEquals(2, track.height());
                assertEquals(new Rational(30000, 1001), track.frameRate().orElseThrow());
                assertEquals(new Rational(10, 11), track.sampleAspectRatio().orElseThrow());
                assertEquals(Interlacing.TOP_FIELD_FIRST, track.interlacing().orElseThrow());
                var color = track.color().orElseThrow();
                assertEquals(ColorRange.FULL, color.range());
                assertEquals(ChromaSiting.LEFT, color.chromaSiting());
                var packets = ElementaryDemuxersTest.readAll(demuxer);
                assertEquals(3, packets.size());
                assertEquals('y', packets.get(1).data()[0]);
                demuxer.seek(66_734);
                assertEquals('z', demuxer.read().data()[0]);
                demuxer.seek(1001 * 1000 / 30 + 1);
                assertEquals('y', demuxer.read().data()[0]);
                demuxer.seek(10_000_000);
                assertEquals(null, demuxer.read());
            }
            var cut = Arrays.copyOf(data, data.length - 3);
            try (var demuxer = new Y4mDemuxer(MediaSource.of(cut))) {
                assertEquals(2, ElementaryDemuxersTest.readAll(demuxer).size(), "a cut frame ends the stream");
            }
            assertThrows(MediaException.Malformed.class, () -> new Y4mDemuxer(MediaSource.of(
                    "YUV4MPEG2 H2 F25:1\n".getBytes(StandardCharsets.US_ASCII))));
            assertThrows(MediaException.Unsupported.class, () -> new Y4mDemuxer(MediaSource.of(
                    "YUV4MPEG2 W2 H2 Cfoo\n".getBytes(StandardCharsets.US_ASCII))));
            assertThrows(MediaException.Malformed.class, () -> new Y4mDemuxer(MediaSource.of(
                    "YUV4MPEG W2 H2\n".getBytes(StandardCharsets.US_ASCII))));
            try (var demuxer = new Y4mDemuxer(MediaSource.of(
                    "YUV4MPEG2 W2 H2\nFRAMX\n".getBytes(StandardCharsets.US_ASCII)))) {
                assertThrows(MediaException.Malformed.class, demuxer::read);
            }
        }

        @Test
        @DisplayName("refuses formats and packets it cannot write")
        void refusals() {
            var rgb = VideoTrack.builder().id(1).codec(Codec.RAW_VIDEO).timeBase(new Rational(1, 25)).width(4)
                    .height(4).codecConfig(RawVideoFormat.of(RawPixelFormat.RGB24).toCodecConfig()).build();
            assertFalse(Y4mMuxer.supports(rgb));
            assertThrows(IllegalArgumentException.class, () -> new Y4mMuxer(MediaSink.memory()).addTrack(rgb));
            var aligned = rgb.toBuilder().codecConfig(RawVideoFormat.of(RawPixelFormat.YUV420P).withRowAlignment(4)
                    .toCodecConfig()).build();
            assertFalse(Y4mMuxer.supports(aligned));
            var yuv = rgb.toBuilder().codecConfig(RawVideoFormat.of(RawPixelFormat.YUV420P).toCodecConfig()).build();
            assertTrue(Y4mMuxer.supports(yuv));
            try (var muxer = new Y4mMuxer(MediaSink.memory())) {
                muxer.addTrack(yuv);
                assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(new Packet(1, new byte[23], 0,
                        0, 1, true)));
            }
            assertTrue(com.github.auties00.smalt.container.Containers.isMuxable(ContainerFormat.Y4M,
                    Codec.RAW_VIDEO));
        }
    }

    @Nested
    @DisplayName("palette indices")
    class Palettes {
        private static final int WIDTH = 33;

        private static final int HEIGHT = 5;

        /** Packs random indices of a depth into rows padded to a stride, the first pixel in the high bits. */
        private static byte[] frame(Random random, int bits, int stride, int limit) {
            var frame = new byte[stride * HEIGHT];
            for (var y = 0; y < HEIGHT; y++) {
                for (var x = 0; x < WIDTH; x++) {
                    var index = random.nextInt(limit);
                    var bit = x * bits;
                    frame[y * stride + bit / 8] |= (byte) (index << (8 - bits - bit % 8));
                }
            }
            return frame;
        }

        private static byte[] concat(byte[]... parts) {
            var out = new ByteArrayOutputStream();
            for (var part : parts) {
                out.writeBytes(part);
            }
            return out.toByteArray();
        }

        private static byte[] box(String type, byte[]... parts) {
            var body = concat(parts);
            return concat(ints(8 + body.length), type.getBytes(StandardCharsets.ISO_8859_1), body);
        }

        private static byte[] ints(int... values) {
            var buffer = ByteBuffer.allocate(4 * values.length);
            for (var value : values) {
                buffer.putInt(value);
            }
            return buffer.array();
        }

        private static byte[] shorts(int... values) {
            var buffer = ByteBuffer.allocate(2 * values.length);
            for (var value : values) {
                buffer.putShort((short) value);
            }
            return buffer.array();
        }

        /**
         * Writes a QuickTime movie of one {@code raw } track at 10 frames a second, with an embedded color table
         * (color table identifier 0) when {@code colorTable} is not null and the default table (-1) otherwise.
         */
        static byte[] mov(int depth, int[] colorTable, List<byte[]> frames) {
            var entry = new ByteArrayOutputStream();
            entry.writeBytes(new byte[6]);
            entry.writeBytes(shorts(1));
            entry.writeBytes(new byte[16]);
            entry.writeBytes(shorts(WIDTH, HEIGHT));
            entry.writeBytes(ints(0x480000, 0x480000, 0));
            entry.writeBytes(shorts(1));
            entry.writeBytes(new byte[32]);
            entry.writeBytes(shorts(depth, colorTable == null ? -1 : 0));
            if (colorTable != null) {
                entry.writeBytes(ints(0));
                entry.writeBytes(shorts(0x8000, colorTable.length - 1));
                for (var i = 0; i < colorTable.length; i++) {
                    var color = colorTable[i];
                    entry.writeBytes(shorts(i, (color >>> 16 & 0xFF) * 257, (color >>> 8 & 0xFF) * 257,
                            (color & 0xFF) * 257));
                }
            }
            var count = frames.size();
            var stsd = box("stsd", ints(0, 1), box("raw ", entry.toByteArray()));
            var stts = box("stts", ints(0, 1, count, 1));
            var stsc = box("stsc", ints(0, 1, 1, count, 1));
            var stsz = box("stsz", ints(0, frames.getFirst().length, count));
            var qt = "qt  ".getBytes(StandardCharsets.ISO_8859_1);
            var ftyp = box("ftyp", qt, ints(0x200), qt);
            var matrix = ints(0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000);
            var moovSize = 0;
            byte[] moov = null;
            for (var pass = 0; pass < 2; pass++) {
                var offset = pass == 0 ? 0 : ftyp.length + moovSize + 8;
                var stbl = box("stbl", stsd, stts, stsc, stsz, box("stco", ints(0, 1, offset)));
                var dinf = box("dinf", box("dref", ints(0, 1), box("url ", ints(1))));
                var minf = box("minf", box("vmhd", ints(1, 0, 0)), dinf, stbl);
                var hdlr = box("hdlr", ints(0), "mhlrvide".getBytes(StandardCharsets.ISO_8859_1), new byte[13]);
                var mdhd = box("mdhd", ints(0, 0, 0, 10, count, 0));
                var tkhd = box("tkhd", ints(3, 0, 0, 1, 0, count, 0, 0, 0, 0), matrix,
                        ints(WIDTH << 16, HEIGHT << 16));
                var mvhd = box("mvhd", ints(0, 0, 0, 10, count, 0x10000), shorts(0x100), new byte[10], matrix,
                        new byte[24], ints(2));
                moov = box("moov", mvhd, box("trak", tkhd, box("mdia", mdhd, hdlr, minf)));
                moovSize = moov.length;
            }
            return concat(ftyp, moov, box("mdat", concat(frames.toArray(byte[][]::new))));
        }

        /** Writes an AVI of one uncompressed bitmap stream at 10 frames a second, rows bottom to top. */
        static byte[] avi(int bits, int[] palette, List<byte[]> frames) {
            var size = frames.getFirst().length;
            var avih = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN).putInt(100_000).putInt(0).putInt(0)
                    .putInt(0x10).putInt(frames.size()).putInt(0).putInt(1).putInt(size).putInt(WIDTH)
                    .putInt(HEIGHT).array();
            var strh = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN)
                    .put("vids".getBytes(StandardCharsets.ISO_8859_1)).putInt(0).putInt(0).putInt(0).putInt(0)
                    .putInt(1).putInt(10).putInt(0).putInt(frames.size()).putInt(size).putInt(-1).putInt(0).array();
            var strf = ByteBuffer.allocate(40 + 4 * palette.length).order(ByteOrder.LITTLE_ENDIAN);
            strf.putInt(40).putInt(WIDTH).putInt(HEIGHT).putShort((short) 1).putShort((short) bits).putInt(0)
                    .putInt(size).putInt(0).putInt(0).putInt(palette.length).putInt(0);
            for (var color : palette) {
                strf.putInt(color & 0xFFFFFF);
            }
            var movi = new ByteArrayOutputStream();
            movi.writeBytes("movi".getBytes(StandardCharsets.ISO_8859_1));
            for (var frame : frames) {
                movi.writeBytes(chunk("00db", frame));
            }
            var strl = list("strl", chunk("strh", strh), chunk("strf", strf.array()));
            var hdrl = list("hdrl", chunk("avih", avih), strl);
            return chunk("RIFF", concat("AVI ".getBytes(StandardCharsets.ISO_8859_1), hdrl,
                    chunk("LIST", movi.toByteArray())));
        }

        private static byte[] chunk(String id, byte[] body) {
            return ByteBuffer.allocate(8 + body.length + (body.length & 1)).order(ByteOrder.LITTLE_ENDIAN)
                    .put(id.getBytes(StandardCharsets.ISO_8859_1)).putInt(body.length).put(body).array();
        }

        private static byte[] list(String type, byte[]... parts) {
            return chunk("LIST", concat(type.getBytes(StandardCharsets.ISO_8859_1), concat(parts)));
        }

        /** Decodes a file with Smalt and with ffmpeg, compares the RGBA frames and returns the track format. */
        private static RawVideoFormat assertDecodes(String name, byte[] file) {
            var path = Scratch.share(Scratch.directory("raw-palettes").resolve(name), file);
            RawVideoFormat format;
            List<byte[]> frames;
            try (var demuxer = com.github.auties00.smalt.container.Containers.openDemuxer(MediaSource.of(file))) {
                var track = (VideoTrack) demuxer.tracks().getFirst();
                assertEquals(WIDTH, track.width());
                assertEquals(HEIGHT, track.height());
                format = RawVideoFormat.fromCodecConfig(track.codecConfig().orElseThrow());
                frames = decode(demuxer);
            }
            assertEquals(2, frames.size());
            assertMatchesFfmpeg(path, frames, "rgba");
            return format;
        }

        private static int[] randomPalette(Random random, int entries) {
            var palette = new int[entries];
            for (var i = 0; i < entries; i++) {
                palette[i] = 0xFF000000 | random.nextInt(0x1000000);
            }
            return palette;
        }

        @ParameterizedTest(name = "depth {0}")
        @CsvSource({"1, PAL1", "2, PAL2", "4, PAL4", "8, PAL8", "33, PAL1", "34, PAL2", "36, PAL4", "40, PAL8"})
        @DisplayName("QuickTime indices into the default Macintosh tables and gray ramps decode as ffmpeg does")
        void quickTimeDefault(int depth, RawPixelFormat expected) {
            var bits = depth & 0x1F;
            var stride = ((WIDTH * bits + 7) / 8 + 1) / 2 * 2;
            var random = new Random(depth);
            var frames = List.of(frame(random, bits, stride, 1 << bits), frame(random, bits, stride, 1 << bits));
            var format = assertDecodes("default-" + depth + ".mov", mov(depth, null, frames));
            assertEquals(expected, format.pixelFormat());
            assertEquals(1 << bits, format.palette().orElseThrow().length);
        }

        @Test
        @DisplayName("pin the Macintosh tables: white first, black last, the 4-bit colors and the 8-bit cube")
        void macintoshTables() {
            assertArrayEquals(new int[]{0xFFFFFFFF, 0xFF000000},
                    RawVideoTags.sampleEntry("raw ", 1, null).palette().orElseThrow());
            assertArrayEquals(new int[]{0xFFFFFFFF, 0xFFACACAC, 0xFF555555, 0xFF000000},
                    RawVideoTags.sampleEntry("raw ", 2, null).palette().orElseThrow());
            var four = RawVideoTags.sampleEntry("raw ", 4, null).palette().orElseThrow();
            assertEquals(0xFFFCF305, four[1], "yellow");
            assertEquals(0xFF0000D4, four[6], "blue");
            var eight = RawVideoTags.sampleEntry("raw ", 8, null).palette().orElseThrow();
            assertEquals(0xFFFFFFFF, eight[0]);
            assertEquals(0xFFFFFFCC, eight[1], "the cube varies blue fastest");
            assertEquals(0xFFCCFFFF, eight[36], "and red slowest");
            assertEquals(0xFF000033, eight[214], "the last cube color before black");
            assertEquals(0xFFEE0000, eight[215], "the red ramp follows the cube");
            assertEquals(0xFF111111, eight[254]);
            assertEquals(0xFF000000, eight[255]);
            assertArrayEquals(new int[]{0xFFFFFFFF, 0xFFAAAAAA, 0xFF555555, 0xFF000000},
                    RawVideoTags.sampleEntry("raw ", 34, null).palette().orElseThrow());
            assertEquals(null, RawVideoTags.sampleEntry("raw ", 3, null));
        }

        @ParameterizedTest(name = "depth {0}")
        @CsvSource({"1", "2", "4", "8"})
        @DisplayName("QuickTime indices into an embedded color table decode as ffmpeg does")
        void quickTimeEmbedded(int bits) {
            var random = new Random(100 + bits);
            var entries = bits == 8 ? 37 : 1 << bits;
            var table = randomPalette(random, entries);
            var stride = ((WIDTH * bits + 7) / 8 + 1) / 2 * 2;
            var frames = List.of(frame(random, bits, stride, entries), frame(random, bits, stride, entries));
            var format = assertDecodes("embedded-" + bits + ".mov", mov(bits, table, frames));
            assertArrayEquals(table, format.palette().orElseThrow());
        }

        @ParameterizedTest(name = "{0} bits")
        @CsvSource({"1", "4", "8"})
        @DisplayName("AVI bitmaps of 1, 4 and 8-bit indices decode as ffmpeg does")
        void avi(int bits) {
            var random = new Random(200 + bits);
            var entries = 1 << bits;
            var palette = randomPalette(random, entries);
            var stride = ((WIDTH * bits + 7) / 8 + 3) / 4 * 4;
            var frames = List.of(frame(random, bits, stride, entries), frame(random, bits, stride, entries));
            var format = assertDecodes("indices-" + bits + ".avi", avi(bits, palette, frames));
            assertTrue(format.bottomUp());
            assertEquals(bits, format.pixelFormat().indexBits());
            assertArrayEquals(palette, format.palette().orElseThrow());
        }

        @Test
        @DisplayName("unpack sub-byte indices with the first pixel in the high bits and check palette sizes")
        void unpacking() {
            var format = RawVideoFormat.of(RawPixelFormat.PAL2).withPalette(new int[]{0xFF000001, 0xFF000002,
                    0xFF000003});
            assertEquals(2, format.rowBytes(0, 5));
            var track = VideoTrack.builder().id(1).codec(Codec.RAW_VIDEO).timeBase(new Rational(1, 25)).width(5)
                    .height(1).codecConfig(format.toCodecConfig()).build();
            try (var decoder = new RawVideoDecoder(track)) {
                // indices 0, 1, 2, 3 then 2: index 3 is past the three entries and decodes to opaque black
                decoder.decode(new Packet(1, new byte[]{0b00_01_10_11, (byte) 0b10_000000}, 0, 0, 1, true),
                        frame -> assertArrayEquals(new byte[]{0, 0, 1, -1, 0, 0, 2, -1, 0, 0, 3, -1, 0, 0, 0, -1,
                                0, 0, 3, -1}, samples(frame.picture())));
            }
            assertArrayEquals(new int[]{0xFF000000, 0xFF555555, 0xFFAAAAAA, 0xFFFFFFFF},
                    RawVideoFormat.of(RawPixelFormat.PAL2).palette().orElseThrow());
            assertEquals(5, RawVideoFormat.of(RawPixelFormat.PAL1).rowBytes(0, 33));
            assertEquals(17, RawVideoFormat.of(RawPixelFormat.PAL4).rowBytes(0, 33));
            assertThrows(IllegalArgumentException.class, () -> RawVideoFormat.of(RawPixelFormat.PAL1)
                    .withPalette(new int[3]));
            assertThrows(IllegalArgumentException.class, () -> new RawVideoEncoder(RawVideoFormat.of(
                    RawPixelFormat.PAL4), 4, 4, new Rational(25, 1), null));
            assertEquals(format, RawVideoFormat.fromCodecConfig(format.toCodecConfig()));
            assertEquals(0, RawPixelFormat.RGB24.indexBits());
        }
    }

    @Nested
    @DisplayName("format, decoder and encoder")
    class Codecs {
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = RawPixelFormat.class, names = {"PAL8", "PAL4", "PAL2", "PAL1"},
                mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("encode a decoded frame back to the same bytes, with padded and bottom-up rows")
        void roundTrip(RawPixelFormat pixelFormat) {
            var random = new java.util.Random(pixelFormat.ordinal());
            for (var depth : pixelFormat.hasVariableDepth() ? new int[]{8, 10, 16} : new int[]{
                    pixelFormat.defaultBitDepth()}) {
                for (var alignment : new int[]{1, 4}) {
                    var format = RawVideoFormat.of(pixelFormat, depth).withRowAlignment(alignment)
                            .withBottomUp(alignment == 4);
                    if (depth > 8 && alignment == 4 && pixelFormat.hasVariableDepth()) {
                        format = format.withBigEndian(true);
                    }
                    assertEquals(format, RawVideoFormat.fromCodecConfig(format.toCodecConfig()));
                    // v210 rows of whole groups of six pixels, so that no group holds components past the edge
                    var width = pixelFormat == RawPixelFormat.V210 ? 48 : 13;
                    var height = 7;
                    var frame = new byte[(int) format.frameSize(width, height)];
                    random.nextBytes(frame);
                    canonicalize(format, frame, width, height);
                    var track = VideoTrack.builder().id(1).codec(Codec.RAW_VIDEO).timeBase(new Rational(1, 25))
                            .width(width).height(height).codecConfig(format.toCodecConfig()).build();
                    var encoder = new RawVideoEncoder(format, width, height, new Rational(25, 1), null);
                    assertEquals(format.toCodecConfig().length, encoder.codecConfig().orElseThrow().length);
                    var output = new ArrayList<Packet>();
                    try (var decoder = new RawVideoDecoder(track)) {
                        decoder.decode(new Packet(1, frame, 4, 4, 1, true), decoded -> {
                            assertEquals(4, decoded.pts());
                            encoder.encode(new VideoFrame(decoded.picture(), decoded.pts(), 1, true), output::add);
                        });
                    }
                    assertEquals(1, output.size());
                    assertArrayEquals(frame, output.getFirst().data(), format.toString());
                }
            }
        }

        /**
         * Zeroes what a decode does not keep: row padding, the unused bits of 16-bit RGB and v210 words and of
         * samples deeper than the format, the unused byte of RGB0 and BGR0, and the components past an odd width.
         */
        private static void canonicalize(RawVideoFormat format, byte[] frame, int width, int height) {
            var offset = 0;
            for (var plane = 0; plane < format.planes(); plane++) {
                var stride = (int) format.stride(plane, width);
                var row = (int) format.rowBytes(plane, width);
                for (var y = 0; y < format.rows(plane, height); y++) {
                    var start = offset + y * stride;
                    Arrays.fill(frame, start + row, start + stride, (byte) 0);
                    switch (format.pixelFormat()) {
                        case RGB0, BGR0 -> {
                            for (var x = 0; x < width; x++) {
                                frame[start + 4 * x + 3] = 0;
                            }
                        }
                        case RGB555LE -> {
                            for (var x = 0; x < width; x++) {
                                frame[start + 2 * x + 1] &= 0x7F;
                            }
                        }
                        case RGB555BE -> {
                            for (var x = 0; x < width; x++) {
                                frame[start + 2 * x] &= 0x7F;
                            }
                        }
                        case V210 -> {
                            for (var x = 0; x < row; x += 4) {
                                frame[start + x + 3] &= 0x3F;
                            }
                        }
                        case UYVY422, YUYV422, YVYU422 -> {
                            if ((width & 1) != 0) {
                                var last = start + 4 * (width / 2);
                                var lumaFirst = format.pixelFormat() == RawPixelFormat.UYVY422 ? 1 : 0;
                                frame[last + lumaFirst + 2] = frame[last + lumaFirst];
                            }
                        }
                        default -> {
                            if (format.bitDepth() > 8) {
                                for (var x = 0; x < row; x += 2) {
                                    var high = format.bigEndian() ? start + x : start + x + 1;
                                    frame[high] &= (byte) ((1 << (format.bitDepth() - 8)) - 1);
                                }
                            }
                        }
                    }
                }
                offset += stride * format.rows(plane, height);
            }
        }

        @Test
        @DisplayName("checks configurations, sizes and packets")
        void validation() {
            assertEquals(33 * 17 + 2 * 17 * 9, RawVideoFormat.of(RawPixelFormat.YUV420P).frameSize(33, 17));
            assertEquals(100 * 17, RawVideoFormat.of(RawPixelFormat.RGB24).withRowAlignment(2).frameSize(33, 17));
            assertEquals(128 * 2 * 17, RawVideoFormat.of(RawPixelFormat.V210).frameSize(50, 17));
            assertEquals(2 * (33 * 17 + 2 * 17 * 9), RawVideoFormat.of(RawPixelFormat.YUV420P, 10).frameSize(33, 17));
            assertThrows(IllegalArgumentException.class, () -> RawVideoFormat.of(RawPixelFormat.RGB24, 10));
            assertThrows(IllegalArgumentException.class, () -> RawVideoFormat.of(RawPixelFormat.YUV420P, 17));
            assertThrows(IllegalArgumentException.class, () -> RawVideoFormat.of(RawPixelFormat.YUV420P)
                    .withBigEndian(true));
            assertThrows(IllegalArgumentException.class, () -> RawVideoFormat.of(RawPixelFormat.RGB24)
                    .withPalette(new int[4]));
            assertThrows(IllegalArgumentException.class, () -> RawVideoFormat.of(RawPixelFormat.RGB24)
                    .withRowAlignment(0));
            assertThrows(MediaException.Malformed.class, () -> RawVideoFormat.fromCodecConfig(new byte[3]));
            var unknown = RawVideoFormat.of(RawPixelFormat.GRAY).toCodecConfig();
            unknown[6] = 'X';
            assertThrows(MediaException.Unsupported.class, () -> RawVideoFormat.fromCodecConfig(unknown));
            var truncated = Arrays.copyOf(RawVideoFormat.of(RawPixelFormat.PAL8).toCodecConfig(), 30);
            assertThrows(MediaException.Malformed.class, () -> RawVideoFormat.fromCodecConfig(truncated));
            var track = VideoTrack.builder().id(1).codec(Codec.RAW_VIDEO).timeBase(new Rational(1, 25)).width(4)
                    .height(4).codecConfig(RawVideoFormat.of(RawPixelFormat.YUV420P).toCodecConfig()).build();
            try (var decoder = new RawVideoDecoder(track)) {
                assertThrows(MediaException.Malformed.class, () -> decoder.decode(new Packet(1, new byte[23], 0, 0,
                        1, true), frame -> { }));
            }
            assertThrows(MediaException.Malformed.class, () -> new RawVideoDecoder(track.toBuilder().width(0)
                    .build()));
            assertThrows(IllegalArgumentException.class, () -> new RawVideoEncoder(RawVideoFormat.of(
                    RawPixelFormat.PAL8), 4, 4, new Rational(25, 1), null));
            var palette = RawVideoFormat.of(RawPixelFormat.PAL8).withPalette(new int[]{0x80102030, 0xFF405060});
            var paletted = track.toBuilder().width(2).height(1).codecConfig(palette.toCodecConfig()).build();
            try (var decoder = new RawVideoDecoder(paletted)) {
                decoder.decode(new Packet(1, new byte[]{1, 7}, 0, 0, 1, true), frame -> assertArrayEquals(
                        new byte[]{0x40, 0x50, 0x60, (byte) 0xFF, 0, 0, 0, (byte) 0xFF}, samples(frame.picture())));
            }
        }

        @Test
        @DisplayName("is found through the codec registries")
        void registries() {
            assertTrue(VideoDecoders.isSupported(Codec.RAW_VIDEO));
            var options = VideoEncoderOptions.of(8, 6, new Rational(25, 1), 1_000_000);
            try (var encoder = VideoEncoders.create(Codec.RAW_VIDEO, options)) {
                assertEquals(Codec.RAW_VIDEO, encoder.codec());
                assertEquals(RawVideoFormat.of(RawPixelFormat.YUV420P),
                        RawVideoFormat.fromCodecConfig(encoder.codecConfig().orElseThrow()));
                var picture = RgbaPicture.allocate(8, 6, false);
                Arrays.fill(picture.pixels(), (byte) 0xFF);
                var packets = new ArrayList<Packet>();
                encoder.encode(new VideoFrame(picture, 0, 1, true), packets::add);
                assertEquals(1, packets.size());
                assertEquals(8 * 6 * 3 / 2, packets.getFirst().size());
                assertEquals(235, packets.getFirst().data()[0] & 0xFF, "white is luma 235 in limited range");
            }
        }
    }
}
