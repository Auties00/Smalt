package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
        var frame = decoder.decodeFrame(jpeg, 0, jpeg.length);
        assertEquals(format, frame.chromaFormat());
        assertArrayEquals(planes(still), planes(frame));
        var padded = new byte[jpeg.length + 9];
        System.arraycopy(jpeg, 0, padded, 5, jpeg.length);
        assertArrayEquals(planes(still), planes(decoder.decodeFrame(padded, 5, jpeg.length)));
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
        assertArrayEquals(planes(new MjpegDecoder().decodeFrame(jpeg, 0, jpeg.length)),
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
