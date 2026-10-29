package com.github.auties00.smalt.video.theora;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.ogg.OggMuxer;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Quality;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Encodes synthetic pictures with {@link TheoraEncoder}, decodes them with {@link TheoraDecoder} and, through an Ogg
 * file, with ffmpeg, whose Theora decoder matches libtheora's: both decodes must agree sample for sample and stay
 * close to the source.
 */
@DisplayName("Theora encoder")
class TheoraEncoderTest {
    static YuvPicture picture(int width, int height, ChromaFormat format, int frame) {
        var picture = YuvPicture.allocate(width, height, format, false, ColorInfo.BT601_LIMITED);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var value = 128 + 90 * Math.sin((x + 2 * frame) / 9.0) * Math.cos((y - frame) / 13.0);
                picture.y()[y * picture.yStride() + x] = (byte) Math.clamp(Math.round(value + (x * y % 7)), 16, 235);
            }
        }
        for (var y = 0; y < picture.chromaHeight(); y++) {
            for (var x = 0; x < picture.chromaWidth(); x++) {
                picture.u()[y * picture.uvStride() + x] = (byte) (128 + 40 * Math.sin((x + frame) / 7.0));
                picture.v()[y * picture.uvStride() + x] = (byte) (128 - 30 * Math.cos((y + x) / 11.0));
            }
        }
        return picture;
    }

    static byte[] raw(YuvPicture picture) {
        var out = new ByteArrayOutputStream();
        for (var row = 0; row < picture.height(); row++) {
            out.write(picture.y(), row * picture.yStride(), picture.width());
        }
        for (var plane : List.of(picture.u(), picture.v())) {
            for (var row = 0; row < picture.chromaHeight(); row++) {
                out.write(plane, row * picture.uvStride(), picture.chromaWidth());
            }
        }
        return out.toByteArray();
    }

    static Stream<Arguments> layouts() {
        return Stream.of(
                Arguments.of(96, 64, ChromaFormat.YUV420, 48, "yuv420p"),
                Arguments.of(70, 50, ChromaFormat.YUV420, 20, "yuv420p"),
                Arguments.of(80, 48, ChromaFormat.YUV422, 40, "yuv422p"),
                Arguments.of(53, 37, ChromaFormat.YUV444, 63, "yuv444p"),
                // ffmpeg rejects a coded frame of a single macro block
                Arguments.of(32, 24, ChromaFormat.YUV420, 0, "yuv420p"));
    }

    @ParameterizedTest(name = "{0}x{1} {2} quality {3}")
    @MethodSource("layouts")
    @DisplayName("writes streams that Smalt and ffmpeg decode to the same pictures, close to the source")
    void roundTrip(int width, int height, ChromaFormat format, int quality, String pixelFormat) throws IOException {
        var options = TheoraEncoderOptions.builder().quality(quality).chromaFormat(format).build();
        var encoder = new TheoraEncoder(width, height, options);
        var sources = new ArrayList<YuvPicture>();
        var packets = new ArrayList<Packet>();
        for (var i = 0; i < 4; i++) {
            var source = picture(width, height, format, i);
            sources.add(source);
            encoder.encode(new VideoFrame(source, i * 3000L, 3000L, true), packet -> packets.add(packet.withTrackId(1)));
        }
        assertEquals(4, packets.size());
        assertTrue(packets.stream().allMatch(Packet::keyframe));
        var track = VideoTrack.builder().id(1).codec(Codec.THEORA).timeBase(encoder.timeBase()).width(width)
                .height(height).frameRate(new Rational(30, 1)).codecConfig(encoder.codecConfig().orElseThrow())
                .build();
        var decoder = new TheoraDecoder(track);
        assertEquals(width + 15 & ~15, decoder.frameWidth());
        var ours = new ByteArrayOutputStream();
        for (var index = 0; index < packets.size(); index++) {
            var frames = new ArrayList<VideoFrame>();
            decoder.decode(packets.get(index), frames::add);
            assertEquals(1, frames.size());
            assertTrue(frames.getFirst().keyframe());
            var decoded = raw((YuvPicture) frames.getFirst().picture());
            var psnr = Quality.psnr(raw(sources.get(index)), decoded);
            assertTrue(psnr > (quality >= 40 ? 34 : 24), "frame " + index + " PSNR " + psnr);
            ours.writeBytes(decoded);
        }
        var sink = MediaSink.memory();
        var muxer = new OggMuxer(sink);
        muxer.addTrack(track);
        packets.forEach(muxer::writePacket);
        muxer.finish();
        try (var demuxer = Containers.openDemuxer(MediaSource.of(sink.toByteArray()))) {
            var read = (VideoTrack) demuxer.tracks().getFirst();
            assertEquals(Codec.THEORA, read.codec());
            assertEquals(width, read.width());
            assertEquals(height, read.height());
        }
        Oracle.assume(Oracle.Tool.FFMPEG);
        var file = Files.createTempFile("theora-encoder", ".ogv");
        try {
            Files.write(file, sink.toByteArray());
            var reference = Oracle.decodeVideo(file, pixelFormat, packets.size());
            assertArrayEquals(ours.toByteArray(), reference, "ffmpeg's decode");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @DisplayName("is created by the registry and converts pictures of another chroma format")
    void registry() {
        var encoder = VideoEncoders.create(Codec.THEORA, VideoEncoderOptions.of(64, 32, new Rational(25, 1), 1_000_000));
        var theora = assertInstanceOf(TheoraEncoder.class, encoder);
        var packets = new ArrayList<Packet>();
        theora.encode(new VideoFrame(picture(64, 32, ChromaFormat.YUV444, 0), 0, 3600, true), packets::add);
        assertEquals(1, packets.size());
        var headers = com.github.auties00.smalt.internal.header.XiphLacing.unlace(theora.codecConfig().orElseThrow());
        assertEquals(3, headers.length);
        assertEquals(0x80, headers[0][0] & 0xFF);
        assertEquals(0x81, headers[1][0] & 0xFF);
        assertEquals(0x82, headers[2][0] & 0xFF);
    }

    @Test
    @DisplayName("declares the frame rate and the sample aspect ratio in the identification header, and clears it")
    void identification() {
        var options = TheoraEncoderOptions.builder().frameRate(new Rational(30000, 1001))
                .sampleAspectRatio(new Rational(4, 3)).quality(20).build();
        var header = com.github.auties00.smalt.internal.header.XiphLacing.unlace(
                new TheoraEncoder(40, 24, options).codecConfig().orElseThrow())[0];
        var fields = java.nio.ByteBuffer.wrap(header);
        assertEquals(3, header[7] & 0xFF);
        assertEquals(3, fields.getShort(10));
        assertEquals(2, fields.getShort(12));
        assertEquals(40, fields.getInt(13) & 0xFFFFFF);
        assertEquals(24, fields.getInt(16) & 0xFFFFFF);
        assertEquals(30000, fields.getInt(22));
        assertEquals(1001, fields.getInt(26));
        assertEquals(4, fields.getInt(29) & 0xFFFFFF);
        assertEquals(3, fields.getInt(32) & 0xFFFFFF);
        assertEquals(20, (header[40] & 0xFF) >> 2);
        var cleared = com.github.auties00.smalt.internal.header.XiphLacing.unlace(new TheoraEncoder(40, 24,
                options.toBuilder().noSampleAspectRatio().build()).codecConfig().orElseThrow())[0];
        assertEquals(0, java.nio.ByteBuffer.wrap(cleared).getInt(29) & 0xFFFFFF);
        assertEquals(0, java.nio.ByteBuffer.wrap(cleared).getInt(32) & 0xFFFFFF);
        assertTrue(TheoraEncoderOptions.defaults().sampleAspectRatio().isEmpty());
    }

    @Test
    @DisplayName("rejects out-of-range options and pictures of another size")
    void invalid() {
        assertThrows(IllegalArgumentException.class, () -> TheoraEncoderOptions.builder().quality(64).build());
        assertThrows(IllegalArgumentException.class,
                () -> TheoraEncoderOptions.builder().chromaFormat(ChromaFormat.YUV440).build());
        assertThrows(IllegalArgumentException.class, () -> new TheoraEncoder(0, 16));
        var encoder = new TheoraEncoder(32, 32);
        assertThrows(IllegalArgumentException.class, () -> encoder.encode(
                new VideoFrame(picture(16, 16, ChromaFormat.YUV420, 0), 0, 3000, true), packet -> {
                }));
        assertTrue(Arrays.equals(encoder.codecConfig().orElseThrow(), encoder.codecConfig().orElseThrow()));
    }
}
