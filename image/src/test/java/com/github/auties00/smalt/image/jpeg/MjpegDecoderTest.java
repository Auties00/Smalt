package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.avi.AviDemuxer;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link MjpegDecoder}: frames decode to the planes {@link JpegDecoder} produces, packets
 * become key frames with their timestamps, the registry serves Motion-JPEG tracks, and the limits
 * and the track codec are checked.
 */
@DisplayName("MjpegDecoder")
class MjpegDecoderTest {
    private static byte[] jpeg(ChromaFormat format) {
        var picture = RgbaPicture.allocate(48, 32, false);
        var pixels = picture.pixels();
        for (var i = 0; i < pixels.length; i += 4) {
            pixels[i] = (byte) (i * 7);
            pixels[i + 1] = (byte) (i >> 2);
            pixels[i + 2] = (byte) (i >> 4);
            pixels[i + 3] = (byte) 255;
        }
        return JpegEncoder.encode(picture, JpegEncoderOptions.ofQuality(85).toBuilder().chromaFormat(format).build());
    }

    private static VideoTrack track(Codec codec) {
        return VideoTrack.builder().id(1).codec(codec).timeBase(new Rational(1, 1000)).width(48).height(32).build();
    }

    private static byte[] planes(YuvPicture picture) {
        var out = new ArrayList<Byte>();
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width(); x++) {
                out.add(picture.y()[y * picture.yStride() + x]);
            }
        }
        if (picture.u() != null) {
            for (var y = 0; y < picture.chromaHeight(); y++) {
                for (var x = 0; x < picture.chromaWidth(); x++) {
                    out.add(picture.u()[y * picture.uvStride() + x]);
                    out.add(picture.v()[y * picture.uvStride() + x]);
                }
            }
        }
        var bytes = new byte[out.size()];
        for (var i = 0; i < bytes.length; i++) {
            bytes[i] = out.get(i);
        }
        return bytes;
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ChromaFormat.class)
    @DisplayName("decodes a frame to the planes of the still decoder, also at an offset")
    void sameAsStill(ChromaFormat format) {
        var jpeg = jpeg(format);
        var still = (YuvPicture) JpegDecoder.decode(MediaSource.of(jpeg)).picture();
        var decoder = new MjpegDecoder();
        var frame = (YuvPicture) decoder.decodeFrame(jpeg, 0, jpeg.length);
        assertEquals(format, frame.chromaFormat());
        assertArrayEquals(planes(still), planes(frame));
        var padded = new byte[jpeg.length + 9];
        System.arraycopy(jpeg, 0, padded, 5, jpeg.length);
        assertArrayEquals(planes(still), planes((YuvPicture) decoder.decodeFrame(padded, 5, jpeg.length)));
    }

    @Test
    @DisplayName("turns every packet into one key frame with the packet's timestamps")
    void packets() {
        var jpeg = jpeg(ChromaFormat.YUV422);
        var frames = new ArrayList<VideoFrame>();
        try (var decoder = new MjpegDecoder(track(Codec.MJPEG))) {
            decoder.decode(new Packet(1, jpeg, 40, 40, 20, false), frames::add);
            decoder.decode(new Packet(1, jpeg, 60, 60, 20, false), frames::add);
            decoder.flush(frames::add);
        }
        assertEquals(2, frames.size());
        assertEquals(40, frames.get(0).pts());
        assertEquals(60, frames.get(1).pts());
        assertEquals(20, frames.get(1).duration());
        assertTrue(frames.get(0).keyframe() && frames.get(1).keyframe());
        assertArrayEquals(planes((YuvPicture) new MjpegDecoder().decodeFrame(jpeg, 0, jpeg.length)),
                planes((YuvPicture) frames.get(0).picture()));
    }

    @Test
    @DisplayName("serves Motion-JPEG tracks through VideoDecoders")
    void registry() {
        assertTrue(VideoDecoders.isSupported(Codec.MJPEG));
        try (var decoder = VideoDecoders.create(track(Codec.MJPEG))) {
            var typed = assertInstanceOf(MjpegDecoder.class, decoder);
            assertEquals(Codec.MJPEG, typed.track().codec());
        }
        assertNull(new MjpegDecoder().track());
    }

    private static byte[] resource(String name) {
        try (var input = MjpegDecoderTest.class.getResourceAsStream(name)) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static int[] planes(Yuv16Picture picture) {
        var out = new ArrayList<Integer>();
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width(); x++) {
                out.add((int) picture.y()[y * picture.yStride() + x]);
            }
        }
        if (picture.u() != null) {
            for (var y = 0; y < picture.chromaHeight(); y++) {
                for (var x = 0; x < picture.chromaWidth(); x++) {
                    out.add((int) picture.u()[y * picture.uvStride() + x]);
                    out.add((int) picture.v()[y * picture.uvStride() + x]);
                }
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"227x149-yuv420-baseline.jpg", "227x149-yuv422-progressive.jpg",
            "227x149-monochrome-arithmetic.jpg", "227x149-yuv440-progressive-arithmetic.jpg", "64x48-yuv444-baseline-q100.jpg"})
    @DisplayName("decodes a 12-bit frame like the still decoder: rounded to 8 bits, or at 12 bits with highBitDepth")
    void twelveBit(String file) {
        var jpeg = resource("cjpeg12/" + file);
        var narrow = new MjpegDecoder().decodeFrame(jpeg, 0, jpeg.length);
        assertArrayEquals(planes((YuvPicture) JpegDecoder.decode(MediaSource.of(jpeg)).picture()),
                planes(assertInstanceOf(YuvPicture.class, narrow)));
        var deep = VideoDecoderOptions.builder().highBitDepth(true).build();
        var wide = assertInstanceOf(Yuv16Picture.class, new MjpegDecoder(deep).decodeFrame(jpeg, 0, jpeg.length));
        assertEquals(12, wide.bitDepth());
        assertEquals(ColorInfo.JPEG, wide.color());
        var still = (Yuv16Picture) JpegDecoder.decode(MediaSource.of(jpeg),
                ImageDecoderOptions.builder().highBitDepth(true).build()).picture();
        assertEquals(still.chromaFormat(), wide.chromaFormat());
        assertArrayEquals(planes(still), planes(wide));
    }

    @Test
    @DisplayName("decodes the 12-bit frames of an ffmpeg-muxed AVI through the registry, within ffmpeg's IDCT tolerance")
    void twelveBitAvi() throws IOException {
        var avi = resource("mjpeg12.avi");
        var deep = VideoDecoderOptions.builder().highBitDepth(true).build();
        var decoded = new ArrayList<Yuv16Picture>();
        try (var demuxer = new AviDemuxer(MediaSource.of(avi))) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            assertEquals(Codec.MJPEG, track.codec());
            var packets = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
            assertEquals(2, packets.size());
            try (var decoder = VideoDecoders.create(track, deep)) {
                for (var packet : packets) {
                    decoder.decode(packet, frame -> decoded.add((Yuv16Picture) frame.picture()));
                    var still = (Yuv16Picture) JpegDecoder.decode(MediaSource.of(packet.data()),
                            ImageDecoderOptions.builder().highBitDepth(true).build()).picture();
                    assertArrayEquals(planes(still), planes(decoded.getLast()));
                }
            }
        }
        assertArrayEquals(planes((Yuv16Picture) new MjpegDecoder(deep).decodeFrame(resource("cjpeg12/33x65-yuv420-baseline.jpg"),
                0, resource("cjpeg12/33x65-yuv420-baseline.jpg").length)), planes(decoded.getFirst()));
        if (Oracle.find(Oracle.Tool.FFMPEG).isEmpty()) {
            return;
        }
        var path = Files.createTempFile("smalt-mjpeg12", ".avi");
        try {
            Files.write(path, avi);
            var raw = Oracle.decodeVideo(path, "yuv420p12le", 2);
            var width = 33;
            var height = 65;
            var chromaWidth = 17;
            var chromaHeight = 33;
            var frameSamples = width * height + 2 * chromaWidth * chromaHeight;
            var buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            // ffmpeg's 12-bit IDCT is not libjpeg's: measured on the cjpeg12 vectors, its samples differ from
            // djpeg's by at most 2 and by about 0.45 on average
            for (var f = 0; f < 2; f++) {
                var picture = decoded.get(f);
                var base = f * frameSamples;
                var largest = 0;
                var total = 0L;
                for (var y = 0; y < height; y++) {
                    for (var x = 0; x < width; x++) {
                        var difference = Math.abs(buffer.getShort(2 * (base + y * width + x))
                                - picture.y()[y * picture.yStride() + x]);
                        largest = Math.max(largest, difference);
                        total += difference;
                    }
                }
                var u = base + width * height;
                var v = u + chromaWidth * chromaHeight;
                for (var y = 0; y < chromaHeight; y++) {
                    for (var x = 0; x < chromaWidth; x++) {
                        var du = Math.abs(buffer.getShort(2 * (u + y * chromaWidth + x)) - picture.u()[y * picture.uvStride() + x]);
                        var dv = Math.abs(buffer.getShort(2 * (v + y * chromaWidth + x)) - picture.v()[y * picture.uvStride() + x]);
                        largest = Math.max(largest, Math.max(du, dv));
                        total += du + dv;
                    }
                }
                assertTrue(largest <= 2, "frame " + f + " differs from ffmpeg by " + largest);
                assertTrue(total < 0.6 * frameSamples, "frame " + f + " differs from ffmpeg by " + total + " in all");
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    @DisplayName("rejects other codecs, corrupt frames and frames over the limits")
    void rejects() {
        assertThrows(IllegalArgumentException.class, () -> new MjpegDecoder(track(Codec.VP8)));
        var jpeg = jpeg(ChromaFormat.YUV420);
        var decoder = new MjpegDecoder();
        assertThrows(MediaException.Malformed.class, () -> decoder.decodeFrame(Arrays.copyOf(jpeg, 20), 0, 20));
        assertThrows(IndexOutOfBoundsException.class, () -> decoder.decodeFrame(jpeg, 1, jpeg.length));
        var small = new MjpegDecoder(VideoDecoderOptions.builder().limits(MediaLimits.builder().maxDimension(16)
                .build()).build());
        assertThrows(MediaException.LimitExceeded.class, () -> small.decodeFrame(jpeg, 0, jpeg.length));
        assertEquals(48, decoder.decodeFrame(jpeg, 0, jpeg.length).width());
    }
}
