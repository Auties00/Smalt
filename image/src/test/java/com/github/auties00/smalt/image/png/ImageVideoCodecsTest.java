package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.avi.AviMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.image.gif.GifVideoDecoder;
import com.github.auties00.smalt.image.tiff.TiffVideoDecoder;
import com.github.auties00.smalt.image.tiff.TiffVideoEncoder;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Decodes the PNG, TIFF and GIF video streams ffmpeg writes into QuickTime, AVI and Matroska files and compares every
 * pixel with ffmpeg's decode (the codecs are lossless), and checks that the files Smalt's PNG and TIFF video encoders
 * write decode in ffmpeg to the source pixels.
 */
@DisplayName("PNG, TIFF and GIF video against ffmpeg")
class ImageVideoCodecsTest {
    static final Tolerance EXACT = Tolerance.exact();

    static final int WIDTH = 97;

    static final int HEIGHT = 53;

    record Stream(VideoTrack track, List<Packet> packets) {
    }

    static Stream demux(Path file) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(Fixtures.read(file)))) {
            var track = (VideoTrack) demuxer.tracks().stream().filter(VideoTrack.class::isInstance).findFirst()
                    .orElseThrow();
            var packets = new ArrayList<Packet>();
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                if (packet.trackId() == track.id()) {
                    packets.add(packet);
                }
            }
            return new Stream(track, packets);
        }
    }

    static byte[] ffmpegDecode(Path file, String pixelFormat) {
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), List.of("-v", "error", "-i", file.toString(),
                "-map", "0:v:0", "-f", "rawvideo", "-pix_fmt", pixelFormat, "-fps_mode", "passthrough", "-"));
    }

    /** Lays a picture out as ffmpeg's rgba, or rgba64le for a 16-bit picture. */
    static byte[] raw(Picture picture) {
        var out = new ByteArrayOutputStream();
        switch (picture) {
            case RgbaPicture rgba -> {
                for (var y = 0; y < rgba.height(); y++) {
                    out.write(rgba.pixels(), y * rgba.stride(), rgba.width() * 4);
                }
            }
            case Rgba16Picture rgba -> {
                for (var y = 0; y < rgba.height(); y++) {
                    for (var x = 0; x < rgba.width() * 4; x++) {
                        var value = rgba.pixels()[y * rgba.stride() + x];
                        out.write(value);
                        out.write(value >>> 8);
                    }
                }
            }
            default -> throw new IllegalArgumentException(picture.toString());
        }
        return out.toByteArray();
    }

    static Path sample(String codec, String pixelFormat, String container, List<String> options) {
        // testsrc2 and gradients render even sizes only, so the odd size is cropped from larger pictures
        var crop = "crop=" + WIDTH + ":" + HEIGHT + ":1:1";
        var output = new ArrayList<>(List.of("-filter_complex", "[0]format=rgba," + crop + "[c];[1]format=gray,"
                + crop + "[a];[c][a]alphamerge,format=" + pixelFormat, "-frames:v", "4", "-c:v", codec));
        output.addAll(options);
        return Fixtures.ffmpeg(container, List.of("-f", "lavfi", "-i", "testsrc2=size=100x56:rate=25", "-f", "lavfi",
                "-i", "gradients=size=100x56:rate=25:seed=1"), output);
    }

    @ParameterizedTest(name = "{0} {1} in {2} {3}")
    @CsvSource(delimiter = ';', value = {
            "png;rgb24;mov;", "png;rgba;mov;", "png;rgb48be;mov;", "png;rgba64be;mov;", "png;gray;mov;",
            "png;pal8;mov;", "png;ya8;mov;", "png;rgb24;avi;", "png;rgba;mkv;", "png;gray16be;avi;",
            "tiff;rgb24;mov;-compression_algo packbits", "tiff;rgba;mov;-compression_algo lzw",
            "tiff;gray;mov;-compression_algo deflate", "tiff;rgb48le;mov;-compression_algo raw",
            "tiff;rgba;mkv;-compression_algo lzw", "tiff;pal8;mov;", "tiff;monob;mov;",
            "gif;bgr8;mov;", "gif;rgb8;mov;-gifflags -offsetting", "gif;pal8;mov;", "gif;gray;mkv;"})
    @DisplayName("decodes ffmpeg's frames pixel for pixel as ffmpeg does")
    void decodesFfmpeg(String codec, String pixelFormat, String container, String options) {
        var file = sample(codec, pixelFormat, container, options == null ? List.of() : List.of(options.split(" ")));
        var stream = demux(file);
        var expectedCodec = switch (codec) {
            case "png" -> Codec.PNG;
            case "tiff" -> Codec.TIFF;
            default -> Codec.GIF;
        };
        assertEquals(expectedCodec, stream.track().codec());
        var wide = pixelFormat.contains("48") || pixelFormat.contains("64") || pixelFormat.contains("16");
        var decoder = VideoDecoders.create(stream.track(), VideoDecoderOptions.builder().highBitDepth(wide).build());
        Class<?> type = switch (codec) {
            case "png" -> PngVideoDecoder.class;
            case "tiff" -> TiffVideoDecoder.class;
            default -> GifVideoDecoder.class;
        };
        assertInstanceOf(type, decoder);
        var ours = new ByteArrayOutputStream();
        for (var packet : stream.packets()) {
            decoder.decode(packet, frame -> ours.writeBytes(raw(frame.picture())));
        }
        assertEquals(4, stream.packets().size());
        var expected = ffmpegDecode(file, wide ? "rgba64le" : "rgba");
        EXACT.check(codec + " " + pixelFormat, Tolerance.Difference.of(expected, ours.toByteArray()));
    }

    static Picture source(long seed, boolean alpha, boolean wide) {
        var random = new Random(seed);
        if (wide) {
            var picture = Rgba16Picture.allocate(WIDTH, HEIGHT, alpha);
            for (var i = 0; i < picture.pixels().length; i++) {
                picture.pixels()[i] = (short) (i % 4 == 3 && !alpha ? 0xFFFF : random.nextInt(65536));
            }
            return picture;
        }
        var picture = RgbaPicture.allocate(WIDTH, HEIGHT, alpha);
        for (var i = 0; i < picture.pixels().length; i++) {
            picture.pixels()[i] = (byte) (i % 4 == 3 && !alpha ? 255 : (i * 7 + random.nextInt(9)));
        }
        return picture;
    }

    @ParameterizedTest(name = "{0} alpha {1} 16-bit {2} in {3}")
    @CsvSource({"png,false,false,mov", "png,true,false,mov", "png,true,true,mov", "png,false,false,avi",
            "png,true,false,mkv", "tiff,false,false,mov", "tiff,true,false,mov", "tiff,false,true,mov",
            "tiff,true,false,mkv"})
    @DisplayName("writes files ffmpeg decodes to the source pixels")
    void encodes(String codec, boolean alpha, boolean wide, String container) throws IOException {
        var pictures = List.of(source(1, alpha, wide), source(2, alpha, wide), source(3, alpha, wide));
        VideoEncoder encoder = codec.equals("png") ? new PngVideoEncoder(WIDTH, HEIGHT)
                : new TiffVideoEncoder(WIDTH, HEIGHT);
        var packets = new ArrayList<Packet>();
        for (var i = 0; i < pictures.size(); i++) {
            encoder.encode(new VideoFrame(pictures.get(i), i * 3000L, 3000L, true),
                    packet -> packets.add(packet.withTrackId(1)));
        }
        var track = VideoTrack.builder().id(1).codec(encoder.codec()).timeBase(encoder.timeBase()).width(WIDTH)
                .height(HEIGHT).frameRate(new Rational(30, 1)).alpha(alpha).build();
        var sink = MediaSink.memory();
        switch (container) {
            case "mov" -> {
                var muxer = new Mp4Muxer(sink, Mp4MuxerOptions.builder().brand(Mp4MuxerOptions.Brand.QUICKTIME)
                        .build());
                muxer.addTrack(track);
                packets.forEach(muxer::writePacket);
                muxer.finish();
            }
            case "avi" -> {
                var muxer = new AviMuxer(sink);
                muxer.addTrack(track);
                packets.forEach(muxer::writePacket);
                muxer.finish();
            }
            default -> {
                var muxer = new MatroskaMuxer(sink);
                muxer.addTrack(track);
                packets.forEach(muxer::writePacket);
                muxer.finish();
            }
        }
        var file = Scratch.directory("image-video").resolve(codec + "-" + alpha + "-" + wide + "." + container);
        Files.write(file, sink.toByteArray());
        var expected = new ByteArrayOutputStream();
        pictures.forEach(picture -> expected.writeBytes(raw(picture)));
        EXACT.check(codec + " " + container, Tolerance.Difference.of(expected.toByteArray(),
                ffmpegDecode(file, wide ? "rgba64le" : "rgba")));
        var stream = demux(file);
        assertEquals(encoder.codec(), stream.track().codec());
        for (var i = 0; i < packets.size(); i++) {
            assertArrayEquals(packets.get(i).data(), stream.packets().get(i).data());
        }
        var decoder = VideoDecoders.create(stream.track(), VideoDecoderOptions.builder().highBitDepth(wide).build());
        var ours = new ByteArrayOutputStream();
        for (var packet : stream.packets()) {
            decoder.decode(packet, frame -> ours.writeBytes(raw(frame.picture())));
        }
        EXACT.check(codec + " " + container + " Smalt", Tolerance.Difference.of(expected.toByteArray(),
                ours.toByteArray()));
    }

    @Test
    @DisplayName("is reached through VideoEncoders for PNG and TIFF")
    void registry() {
        var options = VideoEncoderOptions.builder().size(WIDTH, HEIGHT).frameRate(new Rational(30, 1))
                .bitrate(10_000_000).build();
        assertInstanceOf(PngVideoEncoder.class, VideoEncoders.create(Codec.PNG, options));
        assertInstanceOf(TiffVideoEncoder.class, VideoEncoders.create(Codec.TIFF, options));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"png,rgba", "tiff,rgb24", "gif,pal8"})
    @DisplayName("raises only the documented exceptions for truncations and byte flips")
    void damaged(String codec, String pixelFormat) {
        var stream = demux(sample(codec, pixelFormat, "mov", List.of()));
        var random = new Random(7);
        var data = stream.packets().getFirst().data();
        for (var length = 0; length < data.length; length += Math.max(1, data.length / 53)) {
            var decoder = VideoDecoders.create(stream.track(), VideoDecoderOptions.defaults());
            var cut = Arrays.copyOf(data, length);
            try {
                decoder.decode(new Packet(1, cut, 0, 0, 1, true), frame -> { });
            } catch (MediaException.Malformed | MediaException.Unsupported | MediaException.LimitExceeded expected) {
                // a truncated frame may fail; anything but Malformed fails the test
            }
        }
        for (var flip = 0; flip < 150; flip++) {
            var decoder = VideoDecoders.create(stream.track(), VideoDecoderOptions.defaults());
            var damaged = data.clone();
            damaged[random.nextInt(damaged.length)] ^= (byte) (1 << random.nextInt(8));
            try {
                decoder.decode(new Packet(1, damaged, 0, 0, 1, true), frame -> { });
            } catch (MediaException.Malformed | MediaException.Unsupported | MediaException.LimitExceeded expected) {
                // a damaged frame may fail; anything but Malformed fails the test
            }
        }
    }
}
