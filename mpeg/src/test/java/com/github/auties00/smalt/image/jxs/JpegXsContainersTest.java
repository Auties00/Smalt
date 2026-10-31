package com.github.auties00.smalt.image.jxs;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.validate.Validators;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Interlacing;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Carries JPEG XS video through the video codec registries and the containers that map it: MPEG-2 transport streams
 * (H.222.0 Annex W), MP4 ({@code jxsm}) and MXF (SMPTE ST 2124). Every container written is checked by the structural
 * validators of the test kit, read back and decoded.
 */
class JpegXsContainersTest {
    private static final int WIDTH = 96;
    private static final int HEIGHT = 64;
    private static final Rational RATE = new Rational(25, 1);

    private static List<YuvPicture> pictures() {
        var pictures = new ArrayList<YuvPicture>();
        for (var index = 0; index < 4; index++) {
            pictures.add(JxsSamples.yuv(WIDTH, HEIGHT, ChromaFormat.YUV422, false, 40 + index));
        }
        return pictures;
    }

    private static double psnr(byte[] expected, byte[] actual) {
        var error = 0.0;
        for (var i = 0; i < expected.length; i++) {
            var difference = (expected[i] & 0xFF) - (actual[i] & 0xFF);
            error += difference * difference;
        }
        error /= expected.length;
        return error == 0 ? 99 : 10 * Math.log10(255.0 * 255.0 / error);
    }

    private record Encoded(VideoTrack track, List<Packet> packets) {
    }

    private static Encoded encode(long bitrate) {
        var encoder = VideoEncoders.create(Codec.JPEG_XS, VideoEncoderOptions.of(WIDTH, HEIGHT, RATE, bitrate));
        var packets = new ArrayList<Packet>();
        for (var picture : pictures()) {
            encoder.encode(new VideoFrame(picture, Packet.NO_TIMESTAMP, 0, true), packet -> packets.add(new Packet(1,
                    packet.data(), packet.pts(), packet.dts(), packet.duration(), true)));
        }
        encoder.flush(packets::add);
        var track = VideoTrack.builder().id(1).codec(Codec.JPEG_XS).timeBase(encoder.timeBase()).width(WIDTH)
                .height(HEIGHT).frameRate(RATE).codecConfig(encoder.codecConfig().orElseThrow()).build();
        return new Encoded(track, packets);
    }

    private static List<YuvPicture> decode(VideoTrack track, List<Packet> packets) {
        var pictures = new ArrayList<YuvPicture>();
        try (var decoder = VideoDecoders.create(track)) {
            for (var packet : packets) {
                decoder.decode(packet, frame -> {
                    assertTrue(frame.keyframe());
                    pictures.add(assertInstanceOf(YuvPicture.class, frame.picture()));
                });
            }
            decoder.flush(frame -> pictures.add((YuvPicture) frame.picture()));
        }
        return pictures;
    }

    @Test
    @DisplayName("encodes through the registry to 4:2:2 key frames the registry decodes near the source")
    void registry() {
        var encoded = encode(60_000_000);
        assertEquals(4, encoded.packets().size());
        var config = encoded.track().codecConfig().orElseThrow();
        // jxpl box at offset 22: size, type, then Ppih and Plev
        assertEquals(JpegXsProfile.MAIN_422_10.code(), (config[30] & 0xFF) << 8 | config[31] & 0xFF);
        var decoded = decode(encoded.track(), encoded.packets());
        var sources = pictures();
        assertEquals(4, decoded.size());
        for (var index = 0; index < 4; index++) {
            var picture = decoded.get(index);
            assertEquals(WIDTH, picture.width());
            assertEquals(HEIGHT, picture.height());
            assertEquals(ChromaFormat.YUV422, picture.chromaFormat());
            var quality = psnr(sources.get(index).y(), picture.y());
            assertTrue(quality > 35, "frame " + index + " luma PSNR " + quality);
        }
    }

    @ParameterizedTest
    @EnumSource(value = ContainerFormat.class, names = {"MPEG_TS", "MP4", "MXF"})
    @DisplayName("muxes valid files that demux to the same JPEG XS packets and decode")
    void containers(ContainerFormat format) {
        var encoded = encode(30_000_000);
        var sink = MediaSink.memory();
        try (var muxer = Containers.openMuxer(format, sink)) {
            muxer.addTrack(encoded.track());
            for (var packet : encoded.packets()) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        var file = sink.toByteArray();
        Validators.assertValid("jpeg-xs-" + format.name().toLowerCase() + "." + switch (format) {
            case MPEG_TS -> "ts";
            case MP4 -> "mp4";
            default -> "mxf";
        }, file);
        var packets = new ArrayList<Packet>();
        VideoTrack track;
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            track = (VideoTrack) demuxer.tracks().getFirst();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
        }
        assertEquals(Codec.JPEG_XS, track.codec());
        assertEquals(WIDTH, track.width());
        assertEquals(HEIGHT, track.height());
        if (format != ContainerFormat.MXF) {
            // the jpvi and jxpl boxes (MP4) or the jxes header (MPEG-TS) carry the whole configuration
            assertArrayEquals(encoded.track().codecConfig().orElseThrow(), track.codecConfig().orElseThrow());
            assertEquals(RATE, track.frameRate().orElseThrow().reduce());
        }
        assertEquals(encoded.packets().size(), packets.size());
        for (var index = 0; index < packets.size(); index++) {
            assertTrue(packets.get(index).keyframe(), "packet " + index);
            assertArrayEquals(encoded.packets().get(index).data(), packets.get(index).data(), "packet " + index);
        }
        assertEquals(encoded.packets().size(), decode(track, packets).size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("probes a JXS file and a bare codestream to their format, size and depth")
    void probe(boolean file) {
        var picture = JxsSamples.yuv16(98, 50, ChromaFormat.YUV422, 10, 3);
        var data = JpegXsEncoder.encode(picture, JpegXsEncoderOptions.defaults().toBuilder().fileFormat(file).build());
        var info = Media.probe(MediaSource.of(data));
        assertEquals(ImageFormat.JPEG_XS, info.imageFormat().orElseThrow());
        var image = info.image().orElseThrow();
        assertEquals(98, image.width());
        assertEquals(50, image.height());
        assertEquals(10, image.bitDepth().orElseThrow());
        var mosaic = JpegXsEncoder.encode(JxsSamples.bayer(32, 16, 12, JpegXsImage.CfaPattern.RGGB, 5),
                JpegXsEncoderOptions.defaults().toBuilder().fileFormat(file).build());
        var bayer = Media.probe(MediaSource.of(mosaic)).image().orElseThrow();
        assertEquals(32, bayer.width());
        assertEquals(16, bayer.height());
    }

    @Test
    @DisplayName("decodes a sequence of frames of changing content and size as each decodes alone")
    void sequence() {
        var options = JpegXsEncoderOptions.defaults().toBuilder().fileFormat(false).build();
        var frames = List.of(JpegXsEncoder.encode(JxsSamples.yuv(WIDTH, HEIGHT, ChromaFormat.YUV422, false, 1), options),
                JpegXsEncoder.encode(JxsSamples.yuv(WIDTH, HEIGHT, ChromaFormat.YUV422, false, 2), options),
                JpegXsEncoder.encode(JxsSamples.yuv(48, 32, ChromaFormat.YUV420, false, 3), options),
                JpegXsEncoder.encode(JxsSamples.yuv(WIDTH, HEIGHT, ChromaFormat.YUV422, false, 4), options));
        var track = VideoTrack.builder().id(1).codec(Codec.JPEG_XS).timeBase(RATE.invert()).width(WIDTH)
                .height(HEIGHT).build();
        var packets = new ArrayList<Packet>();
        for (var index = 0; index < frames.size(); index++) {
            packets.add(new Packet(1, frames.get(index), index, index, 1, true));
        }
        var decoded = decode(track, packets);
        assertEquals(frames.size(), decoded.size());
        for (var index = 0; index < frames.size(); index++) {
            var alone = assertInstanceOf(YuvPicture.class, JpegXsDecoder.decode(MediaSource.of(frames.get(index)))
                    .picture());
            var picture = decoded.get(index);
            assertEquals(alone.width(), picture.width(), "frame " + index);
            assertEquals(alone.chromaFormat(), picture.chromaFormat(), "frame " + index);
            assertArrayEquals(alone.y(), picture.y(), "luma of frame " + index);
            assertArrayEquals(alone.u(), picture.u(), "Cb of frame " + index);
            assertArrayEquals(alone.v(), picture.v(), "Cr of frame " + index);
        }
    }

    @ParameterizedTest
    @EnumSource(value = Interlacing.class, names = {"TOP_FIELD_FIRST", "BOTTOM_FIELD_FIRST"})
    @DisplayName("weaves the two field codestreams of a packet in the track's field order")
    void fields(Interlacing scan) {
        int[] sx = {1, 2, 2};
        int[] sy = {1, 1, 1};
        var first = JxsSamples.image(WIDTH, HEIGHT / 2, 8, sx, sy, 7);
        var second = JxsSamples.image(WIDTH, HEIGHT / 2, 8, sx, sy, 8);
        var options = JpegXsEncoderOptions.forLossless().toBuilder().fileFormat(false).build();
        var a = JpegXsEncoder.encode(first, options);
        var b = JpegXsEncoder.encode(second, options);
        var data = new byte[a.length + b.length];
        System.arraycopy(a, 0, data, 0, a.length);
        System.arraycopy(b, 0, data, a.length, b.length);
        var track = VideoTrack.builder().id(1).codec(Codec.JPEG_XS).timeBase(RATE.invert()).width(WIDTH)
                .height(HEIGHT).interlacing(scan).build();
        var pictures = decode(track, List.of(new Packet(1, data, 0, 0, 1, true)));
        var picture = pictures.getFirst();
        assertEquals(HEIGHT, picture.height());
        var top = scan == Interlacing.TOP_FIELD_FIRST ? first : second;
        var bottom = scan == Interlacing.TOP_FIELD_FIRST ? second : first;
        for (var y = 0; y < HEIGHT; y++) {
            var field = (y & 1) == 0 ? top : bottom;
            var samples = field.components().getFirst().samples();
            for (var x = 0; x < WIDTH; x++) {
                assertEquals(samples[(y >> 1) * WIDTH + x], picture.y()[y * picture.yStride() + x] & 0xFF,
                        "sample " + x + "," + y);
            }
        }
    }
}
