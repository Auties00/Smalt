package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelDensity;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.codec.Jp2Frames;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Interlacing;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link Mj2Encoder} and {@link Mj2Decoder} without a container: lossless round trips in every component
 * layout and depth, the forms of a frame, the JP2 Header box configuration, interlaced fields, the rate control, the
 * registry, the options and damaged frames.
 */
@DisplayName("Motion JPEG 2000 codec")
class Mj2CodecTest {
    private static final int WIDTH = 45;

    private static final int HEIGHT = 31;

    private static VideoTrack track(Mj2Encoder encoder, Interlacing scan) {
        var builder = VideoTrack.builder().id(1).codec(Codec.JPEG2000).timeBase(encoder.timeBase())
                .width(encoder.width()).height(encoder.height()).codecConfig(encoder.codecConfig().orElseThrow());
        if (scan != null) {
            builder.interlacing(scan);
        }
        return builder.build();
    }

    private static Picture source(Mj2PixelFormat format, int depth, int frame) {
        if (format.isRgb()) {
            var alpha = format == Mj2PixelFormat.RGBA;
            return depth == 8 ? Mj2Testing.rgba(WIDTH, HEIGHT, alpha, frame) : Mj2Testing.rgba16(WIDTH, HEIGHT, alpha,
                    frame);
        }
        return depth == 8 ? Mj2Testing.yuv(WIDTH, HEIGHT, format.chromaFormat(), frame)
                : Mj2Testing.yuv16(WIDTH, HEIGHT, format.chromaFormat(), depth, frame);
    }

    static Stream<Arguments> layouts() {
        var arguments = Stream.<Arguments>builder();
        for (var format : Mj2PixelFormat.values()) {
            arguments.add(Arguments.of(format, 8));
            arguments.add(Arguments.of(format, 16));
            if (!format.isRgb()) {
                arguments.add(Arguments.of(format, 10));
                arguments.add(Arguments.of(format, 12));
            }
        }
        return arguments.build();
    }

    @Nested
    @DisplayName("lossless coding")
    class Lossless {
        @ParameterizedTest(name = "{0}, {1} bits")
        @MethodSource("com.github.auties00.smalt.image.jp2.Mj2CodecTest#layouts")
        @DisplayName("decodes every frame back to the samples it coded")
        void roundTrip(Mj2PixelFormat format, int depth) {
            var options = Mj2EncoderOptions.builder().pixelFormat(format).bitDepth(depth).build();
            var encoder = new Mj2Encoder(WIDTH, HEIGHT, options);
            var pictures = List.of(source(format, depth, 0), source(format, depth, 1), source(format, depth, 2));
            var packets = Mj2Testing.encode(encoder, pictures);
            assertEquals(3, packets.size());
            var decoder = new Mj2Decoder(track(encoder, null), VideoDecoderOptions.builder()
                    .highBitDepth(depth > 8).build());
            var decoded = Mj2Testing.decode(decoder, packets);
            for (var i = 0; i < pictures.size(); i++) {
                var packet = packets.get(i);
                assertTrue(packet.keyframe());
                assertTrue(Jp2Frames.isFile(packet.data(), 0, packet.size()), "every frame is a JP2 file");
                var picture = decoded.get(i);
                if (format.isRgb()) {
                    assertEquals(depth == 8 ? RgbaPicture.class : Rgba16Picture.class, picture.getClass());
                    assertEquals(format == Mj2PixelFormat.RGBA, picture.hasAlpha());
                    var expected = format == Mj2PixelFormat.RGB ? opaque(pictures.get(i)) : pictures.get(i);
                    Tolerance.exact().assertWithin("frame " + i, expected, picture);
                } else {
                    var chroma = format.chromaFormat();
                    if (depth == 8) {
                        assertEquals(chroma, assertInstanceOf(YuvPicture.class, picture).chromaFormat());
                    } else {
                        var wide = assertInstanceOf(Yuv16Picture.class, picture);
                        assertEquals(chroma, wide.chromaFormat());
                        assertEquals(depth, wide.bitDepth());
                    }
                    Tolerance.exact().assertWithin("frame " + i, pictures.get(i), picture);
                }
            }
        }

        private static Picture opaque(Picture picture) {
            return switch (picture) {
                case RgbaPicture rgba -> {
                    var pixels = rgba.pixels().clone();
                    for (var i = 3; i < pixels.length; i += 4) {
                        pixels[i] = (byte) 255;
                    }
                    yield RgbaPicture.of(rgba.width(), rgba.height(), rgba.stride(), pixels, false);
                }
                case Rgba16Picture rgba -> {
                    var pixels = rgba.pixels().clone();
                    for (var i = 3; i < pixels.length; i += 4) {
                        pixels[i] = (short) 65535;
                    }
                    yield Rgba16Picture.of(rgba.width(), rgba.height(), rgba.stride(), pixels, false);
                }
                default -> picture;
            };
        }

        @Test
        @DisplayName("rescales samples wider than 8 bits to 8 without highBitDepth")
        void narrowed() {
            var encoder = new Mj2Encoder(WIDTH, HEIGHT, Mj2EncoderOptions.builder().pixelFormat(Mj2PixelFormat.YUV422)
                    .bitDepth(10).build());
            var picture = Mj2Testing.yuv16(WIDTH, HEIGHT, ChromaFormat.YUV422, 10, 3);
            var packets = Mj2Testing.encode(encoder, List.of(picture));
            var decoded = assertInstanceOf(YuvPicture.class,
                    Mj2Testing.decode(new Mj2Decoder(track(encoder, null)), packets).getFirst());
            for (var y = 0; y < HEIGHT; y++) {
                for (var x = 0; x < WIDTH; x++) {
                    var v = picture.y()[y * picture.yStride() + x];
                    assertEquals(Math.round(v * 255.0 / 1023), decoded.y()[y * decoded.yStride() + x] & 0xFF);
                }
            }
        }

        @Test
        @DisplayName("converts a picture of another layout or depth before coding it")
        void converted() {
            var rgb = Mj2Testing.rgba(WIDTH, HEIGHT, false, 0);
            var yuv = ImageOps.toYuv(rgb, ChromaFormat.YUV420, ColorInfo.JPEG);
            var encoder = new Mj2Encoder(WIDTH, HEIGHT);
            var decoded = Mj2Testing.decode(new Mj2Decoder(track(encoder, null)), Mj2Testing.encode(encoder,
                    List.of(rgb, ImageOps.toBitDepth(yuv, 10))));
            Tolerance.exact().assertWithin("RGB input", yuv, decoded.get(0));
            Tolerance.exact().assertWithin("10-bit input", yuv, decoded.get(1));
            var rgbEncoder = new Mj2Encoder(WIDTH, HEIGHT, Mj2EncoderOptions.builder()
                    .pixelFormat(Mj2PixelFormat.RGB).build());
            var fromYuv = Mj2Testing.decode(new Mj2Decoder(track(rgbEncoder, null)), Mj2Testing.encode(rgbEncoder,
                    List.of(yuv))).getFirst();
            Tolerance.exact().assertWithin("Y'CbCr input", ImageOps.toRgba(yuv), fromYuv);
        }
    }

    @Nested
    @DisplayName("frame forms")
    class Forms {
        @Test
        @DisplayName("codes raw codestreams under a codestream format, decoded alike with or without the header")
        void codestreams() {
            var options = Mj2EncoderOptions.builder().coding(Jp2EncoderOptions.lossless().toBuilder()
                    .format(Jp2Format.CODESTREAM).build()).build();
            var encoder = new Mj2Encoder(WIDTH, HEIGHT, options);
            var picture = Mj2Testing.yuv(WIDTH, HEIGHT, ChromaFormat.YUV420, 0);
            var packet = Mj2Testing.encode(encoder, List.of(picture)).getFirst();
            assertEquals(Jp2Frames.CODESTREAM, Jp2Frames.form(packet.data(), 0, packet.size()));
            Tolerance.exact().assertWithin("with the header", picture,
                    new Mj2Decoder(track(encoder, null)).decodeFrame(packet.data(), 0, packet.size()));
            Tolerance.exact().assertWithin("without a track", picture,
                    new Mj2Decoder().decodeFrame(packet.data(), 0, packet.size()));
            var channels = Jp2Decoder.decodeChannels(MediaSource.of(packet.data()));
            assertEquals(3, channels.channels().size());
            assertEquals(2, channels.channels().get(1).subsamplingX());
        }

        @Test
        @DisplayName("needs the track's header to decode an unsubsampled Y'CbCr codestream as Y'CbCr")
        void header() {
            var options = Mj2EncoderOptions.builder().pixelFormat(Mj2PixelFormat.YUV444).coding(Jp2EncoderOptions
                    .lossless().toBuilder().format(Jp2Format.CODESTREAM).build()).build();
            var encoder = new Mj2Encoder(WIDTH, HEIGHT, options);
            var config = encoder.codecConfig().orElseThrow();
            assertTrue(Jp2Frames.isHeader(config));
            assertEquals(18, Jp2Frames.headerColorSpace(config));
            assertArrayEquals(new long[]{WIDTH, HEIGHT}, Jp2Frames.headerSize(config));
            var picture = Mj2Testing.yuv(WIDTH, HEIGHT, ChromaFormat.YUV444, 1);
            var packet = Mj2Testing.encode(encoder, List.of(picture)).getFirst();
            Tolerance.exact().assertWithin("under the header", picture,
                    new Mj2Decoder(track(encoder, null)).decodeFrame(packet.data(), 0, packet.size()));
            assertInstanceOf(RgbaPicture.class, new Mj2Decoder().decodeFrame(packet.data(), 0, packet.size()),
                    "three unsubsampled components of an undeclared space are RGB");
            var boxed = Jp2Frames.toSample(packet.data());
            Tolerance.exact().assertWithin("a jp2c box", picture,
                    new Mj2Decoder(track(encoder, null)).decodeFrame(boxed, 0, boxed.length));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Mj2PixelFormat.class, names = {"GRAY", "RGB", "RGBA"})
        @DisplayName("declares greyscale or sRGB in the header of the other layouts")
        void colorSpaces(Mj2PixelFormat format) {
            var config = new Mj2Encoder(8, 8, Mj2EncoderOptions.builder().pixelFormat(format).bitDepth(12).build())
                    .codecConfig().orElseThrow();
            assertEquals(format == Mj2PixelFormat.GRAY ? 17 : 16, Jp2Frames.headerColorSpace(config));
            assertEquals(12, Jp2Frames.headerDepth(config));
            assertEquals(format.components(), (config[24] & 0xFF) << 8 | config[25] & 0xFF);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Mj2PixelFormat.class)
        @DisplayName("configures the track with the JP2 Header box every JP2 file frame carries")
        void headerOfFrames(Mj2PixelFormat format) {
            var encoder = new Mj2Encoder(WIDTH, HEIGHT, Mj2EncoderOptions.builder().pixelFormat(format).build());
            var packet = Mj2Testing.encode(encoder, List.of(source(format, 8, 0))).getFirst();
            assertArrayEquals(Jp2Frames.header(packet.data(), 0, packet.size()), encoder.codecConfig().orElseThrow());
        }

        @Test
        @DisplayName("keeps the coding's ICC profile, its density and the opacity channel definition in the header")
        void headerMetadata() {
            var profile = ColorProfile.displayP3();
            var coding = Jp2EncoderOptions.lossless().toBuilder().colorProfile(profile)
                    .density(PixelDensity.ofDotsPerInch(300)).build();
            var encoder = new Mj2Encoder(WIDTH, HEIGHT, Mj2EncoderOptions.builder().pixelFormat(Mj2PixelFormat.RGBA)
                    .coding(coding).build());
            var picture = source(Mj2PixelFormat.RGBA, 8, 1);
            var packet = Mj2Testing.encode(encoder, List.of(picture)).getFirst();
            var config = encoder.codecConfig().orElseThrow();
            assertArrayEquals(Jp2Frames.header(packet.data(), 0, packet.size()), config);
            assertEquals(-1, Jp2Frames.headerColorSpace(config), "an ICC profile, not an enumerated space");
            var bounds = new int[4];
            Jp2Frames.codestreams(packet.data(), 0, packet.size(), bounds);
            var rewrapped = Jp2Frames.toFile(config, packet.data(), bounds[0], bounds[1]);
            var channels = Jp2Decoder.decodeChannels(MediaSource.of(rewrapped));
            assertEquals(Jp2ColorSpace.ICC, channels.colorSpace());
            assertArrayEquals(profile.toByteArray(), channels.iccProfile().orElseThrow());
            assertEquals(Jp2ChannelType.OPACITY, channels.channels().get(3).type());
            var boxed = Jp2Frames.toSample(packet.data());
            var decoded = new Mj2Decoder(track(encoder, null)).decodeFrame(boxed, 0, boxed.length);
            assertTrue(decoded.hasAlpha());
            Tolerance.exact().assertWithin("under the track's header", picture, decoded);
        }

        @Test
        @DisplayName("prefers a JP2 Header box among the boxes of a frame to the track's")
        void ownHeader() {
            var options = Mj2EncoderOptions.builder().pixelFormat(Mj2PixelFormat.YUV444).fieldOrder(FieldOrder.TOP_FIRST)
                    .build();
            var encoder = new Mj2Encoder(WIDTH, 32, options);
            var picture = Mj2Testing.yuv(WIDTH, 32, ChromaFormat.YUV444, 2);
            var packet = Mj2Testing.encode(encoder, List.of(picture)).getFirst().data();
            var framed = Jp2Frames.selfContained(packet, encoder.codecConfig().orElseThrow());
            assertEquals(Jp2Frames.BOXES, Jp2Frames.form(framed, 0, framed.length));
            assertArrayEquals(encoder.codecConfig().get(), Jp2Frames.header(framed, 0, framed.length));
            var trackless = new Mj2Decoder();
            Tolerance.exact().assertWithin("the frame's own header", picture,
                    trackless.decodeFrame(framed, 0, framed.length));
            assertInstanceOf(RgbaPicture.class, trackless.decodeFrame(packet, 0, packet.length),
                    "without a header the 4:4:4 fields are RGB");
            var rgb = new Mj2Encoder(WIDTH, 32, options.toBuilder().pixelFormat(Mj2PixelFormat.RGB).build());
            var rgbTrack = track(rgb, Interlacing.TOP_FIELD_FIRST_SEPARATED);
            assertInstanceOf(YuvPicture.class, new Mj2Decoder(rgbTrack).decodeFrame(framed, 0, framed.length),
                    "the frame's sYCC header wins over the track's sRGB one");
        }

        @Test
        @DisplayName("decodes a frame held at an offset of a larger array")
        void offset() {
            var encoder = new Mj2Encoder(WIDTH, HEIGHT);
            var picture = Mj2Testing.yuv(WIDTH, HEIGHT, ChromaFormat.YUV420, 2);
            var packet = Mj2Testing.encode(encoder, List.of(picture)).getFirst().data();
            var padded = new byte[packet.length + 13];
            System.arraycopy(packet, 0, padded, 7, packet.length);
            Tolerance.exact().assertWithin("JP2 file", picture, new Mj2Decoder().decodeFrame(padded, 7, packet.length));
            var codestream = Jp2Frames.toSample(packet);
            padded = new byte[codestream.length + 5];
            System.arraycopy(codestream, 0, padded, 3, codestream.length);
            Tolerance.exact().assertWithin("jp2c box", picture,
                    new Mj2Decoder(track(encoder, null)).decodeFrame(padded, 3, codestream.length));
        }
    }

    @Nested
    @DisplayName("interlaced frames")
    class Fields {
        static Stream<Arguments> orders() {
            return Stream.of(
                    Arguments.of(FieldOrder.TOP_FIRST, Interlacing.TOP_FIELD_FIRST_SEPARATED, ChromaFormat.YUV422),
                    Arguments.of(FieldOrder.BOTTOM_FIRST, Interlacing.BOTTOM_FIELD_FIRST_SEPARATED,
                            ChromaFormat.YUV420),
                    Arguments.of(FieldOrder.TOP_FIRST, Interlacing.TOP_FIELD_FIRST_SEPARATED, ChromaFormat.MONOCHROME));
        }

        @ParameterizedTest(name = "{0}, {2}")
        @MethodSource("orders")
        @DisplayName("code two field images, the earlier first, that weave back into the frame")
        void weave(FieldOrder order, Interlacing scan, ChromaFormat chroma) {
            var height = 48;
            var format = chroma == ChromaFormat.MONOCHROME ? Mj2PixelFormat.GRAY : chroma == ChromaFormat.YUV420
                    ? Mj2PixelFormat.YUV420 : Mj2PixelFormat.YUV422;
            var encoder = new Mj2Encoder(WIDTH, height, Mj2EncoderOptions.builder().pixelFormat(format)
                    .fieldOrder(order).build());
            var picture = Mj2Testing.yuv(WIDTH, height, chroma, 4);
            var packet = Mj2Testing.encode(encoder, List.of(picture)).getFirst();
            var bounds = new int[4];
            assertEquals(Jp2Frames.BOXES, Jp2Frames.form(packet.data(), 0, packet.size()));
            assertEquals(2, Jp2Frames.codestreams(packet.data(), 0, packet.size(), bounds));
            var first = Jp2Frames.siz(packet.data(), bounds[0], bounds[1]);
            assertEquals(height / 2, first.height());
            var woven = new Mj2Decoder(track(encoder, scan)).decodeFrame(packet.data(), 0, packet.size());
            Tolerance.exact().assertWithin("woven frame", picture, woven);
            var firstField = new Mj2Decoder(track(encoder, null)).decodeFrame(packet.data(), bounds[0] - 8,
                    bounds[1] - bounds[0] + 8);
            var expectedRow = order == FieldOrder.TOP_FIRST ? 0 : 1;
            var luma = ((YuvPicture) firstField).y();
            assertArrayEquals(Arrays.copyOfRange(picture.y(), expectedRow * picture.yStride(),
                    expectedRow * picture.yStride() + WIDTH), Arrays.copyOf(luma, WIDTH), "the earlier field first");
            var swapped = order == FieldOrder.TOP_FIRST ? Interlacing.BOTTOM_FIELD_FIRST_SEPARATED
                    : Interlacing.TOP_FIELD_FIRST_SEPARATED;
            var wrong = new Mj2Decoder(track(encoder, swapped)).decodeFrame(packet.data(), 0, packet.size());
            assertNotEquals(0.0, Tolerance.Difference.of(picture, wrong).maxAbs(), "the scan decides the weave");
        }

        @Test
        @DisplayName("weave 16-bit RGB fields into a frame that keeps its depth")
        void wideRgb() {
            var height = 20;
            var encoder = new Mj2Encoder(WIDTH, height, Mj2EncoderOptions.builder().pixelFormat(Mj2PixelFormat.RGB)
                    .bitDepth(16).fieldOrder(FieldOrder.BOTTOM_FIRST).build());
            var picture = Mj2Testing.rgba16(WIDTH, height, false, 3);
            var packet = Mj2Testing.encode(encoder, List.of(picture)).getFirst().data();
            var woven = new Mj2Decoder(track(encoder, Interlacing.BOTTOM_FIELD_FIRST_SEPARATED),
                    VideoDecoderOptions.builder().highBitDepth(true).build()).decodeFrame(packet, 0, packet.length);
            assertInstanceOf(Rgba16Picture.class, woven);
            assertEquals(height, woven.height());
            Tolerance.exact().assertWithin("woven 16-bit frame", picture, woven);
        }

        @Test
        @DisplayName("weave an odd-height frame from a top field one row higher, and keep the first field alone when"
                + " the second is damaged")
        void oddAndDamaged() {
            var encoder = new Mj2Encoder(WIDTH, HEIGHT, Mj2EncoderOptions.builder().pixelFormat(Mj2PixelFormat.GRAY)
                    .fieldOrder(FieldOrder.TOP_FIRST).build());
            var picture = Mj2Testing.yuv(WIDTH, HEIGHT, ChromaFormat.MONOCHROME, 5);
            var packet = Mj2Testing.encode(encoder, List.of(picture)).getFirst().data();
            var decoder = new Mj2Decoder(track(encoder, Interlacing.TOP_FIELD_FIRST_SEPARATED));
            Tolerance.exact().assertWithin("odd height", picture, decoder.decodeFrame(packet, 0, packet.length));
            var bounds = new int[4];
            Jp2Frames.codestreams(packet, 0, packet.length, bounds);
            var damaged = packet.clone();
            // break the second field's SIZ marker: its codestream no longer parses
            damaged[bounds[2] + 3] = 0;
            var alone = decoder.decodeFrame(damaged, 0, damaged.length);
            assertEquals((HEIGHT + 1) / 2, alone.height());
        }
    }

    @Nested
    @DisplayName("timing and rate control")
    class Timing {
        @Test
        @DisplayName("stamps frames without a timestamp at the nominal frame rate and keeps given timestamps")
        void timestamps() {
            var encoder = new Mj2Encoder(16, 16, Mj2EncoderOptions.builder().frameRate(new Rational(25, 1))
                    .timeBase(new Rational(1, 1000)).build());
            var picture = Mj2Testing.yuv(16, 16, ChromaFormat.YUV420, 0);
            var first = encoder.encodeFrame(new VideoFrame(picture, Packet.NO_TIMESTAMP, Packet.UNKNOWN_DURATION,
                    true));
            var second = encoder.encodeFrame(new VideoFrame(picture, Packet.NO_TIMESTAMP, Packet.UNKNOWN_DURATION,
                    false));
            var third = encoder.encodeFrame(new VideoFrame(picture, 500, 40, false));
            assertEquals(0, first.pts());
            assertEquals(40, second.pts());
            assertEquals(40, second.duration());
            assertEquals(500, third.pts());
            assertTrue(third.keyframe());
            assertThrows(IllegalArgumentException.class, () -> encoder.encodeFrame(new VideoFrame(picture, 100, 40,
                    false)));
            assertThrows(IllegalArgumentException.class, () -> encoder.encodeFrame(new VideoFrame(
                    Mj2Testing.yuv(8, 16, ChromaFormat.YUV420, 0), 600, 40, false)));
        }

        @Test
        @DisplayName("caps every frame at its share of the target bitrate, and follows a new target")
        void bitrate() {
            var pictures = new ArrayList<Picture>();
            for (var frame = 0; frame < 4; frame++) {
                pictures.add(Mj2Testing.yuv(96, 64, ChromaFormat.YUV420, frame));
            }
            var unconstrained = Mj2Testing.encode(new Mj2Encoder(96, 64), pictures);
            var target = 240_000L;
            var options = Mj2EncoderOptions.builder().frameRate(new Rational(30, 1)).bitrate(target).build();
            var encoder = new Mj2Encoder(96, 64, options);
            var packets = Mj2Testing.encode(encoder, pictures.subList(0, 2));
            var share = target / 30 / 8;
            for (var packet : packets) {
                assertTrue(packet.size() <= share + 400, "frame of " + packet.size() + " bytes, share " + share);
                assertTrue(packet.size() < unconstrained.getFirst().size());
            }
            encoder.setTargetBitrate(target / 4);
            var smaller = Mj2Testing.encode(encoder, pictures.subList(2, 4));
            for (var packet : smaller) {
                assertTrue(packet.size() <= share / 4 + 400, "frame of " + packet.size() + " bytes");
            }
            var decoded = Mj2Testing.decode(new Mj2Decoder(track(encoder, null)), packets);
            assertTrue(com.github.auties00.smalt.testkit.Quality.psnr(pictures.getFirst(), decoded.getFirst()) > 20);
            assertThrows(IllegalStateException.class, () -> new Mj2Encoder(16, 16).setTargetBitrate(1000));
            assertThrows(IllegalArgumentException.class, () -> encoder.setTargetBitrate(0));
        }
    }

    @Nested
    @DisplayName("registry and options")
    class Api {
        @Test
        @DisplayName("serves the encoder and the decoder through the registries")
        void registry() {
            assertTrue(VideoEncoders.isSupported(Codec.JPEG2000));
            assertTrue(VideoDecoders.isSupported(Codec.JPEG2000));
            try (var encoder = VideoEncoders.create(Codec.JPEG2000, VideoEncoderOptions.of(64, 48,
                    new Rational(25, 1), 1_000_000))) {
                assertInstanceOf(Mj2Encoder.class, encoder);
                assertEquals(Codec.JPEG2000, encoder.codec());
                var picture = Mj2Testing.yuv(64, 48, ChromaFormat.YUV420, 0);
                var packets = new ArrayList<Packet>();
                encoder.encode(new VideoFrame(picture, 0, 3600, true), packets::add);
                assertTrue(packets.getFirst().size() <= 1_000_000 / 25 / 8 + 400);
                var track = VideoTrack.builder().id(1).codec(Codec.JPEG2000).timeBase(encoder.timeBase()).width(64)
                        .height(48).codecConfig(encoder.codecConfig().orElseThrow()).build();
                try (var decoder = VideoDecoders.create(track)) {
                    assertInstanceOf(Mj2Decoder.class, decoder);
                    var frames = new ArrayList<VideoFrame>();
                    decoder.decode(packets.getFirst(), frames::add);
                    assertTrue(com.github.auties00.smalt.testkit.Quality.psnr(picture, frames.getFirst().picture())
                            > 30);
                }
            }
        }

        @Test
        @DisplayName("validates the options and the picture size")
        void validation() {
            assertThrows(IllegalArgumentException.class, () -> Mj2EncoderOptions.builder().bitDepth(7).build());
            assertThrows(IllegalArgumentException.class, () -> Mj2EncoderOptions.builder().bitDepth(17).build());
            assertThrows(IllegalArgumentException.class, () -> Mj2EncoderOptions.builder().bitrate(0).build());
            assertThrows(IllegalArgumentException.class, () -> Mj2EncoderOptions.builder()
                    .frameRate(new Rational(1, 100)).build());
            assertThrows(IllegalArgumentException.class, () -> Mj2EncoderOptions.builder()
                    .pixelFormat(Mj2PixelFormat.RGBA).coding(Jp2EncoderOptions.forCinema2k(24)).build());
            assertThrows(IllegalArgumentException.class, () -> new Mj2Encoder(0, 16));
            assertThrows(IllegalArgumentException.class, () -> new Mj2Encoder(65536, 16));
            assertThrows(IllegalArgumentException.class, () -> new Mj2Encoder(16, 1, Mj2EncoderOptions.builder()
                    .fieldOrder(FieldOrder.TOP_FIRST).build()));
            assertThrows(IllegalArgumentException.class, () -> new Mj2Decoder(VideoTrack.builder().id(1)
                    .codec(Codec.MJPEG).timeBase(Rational.MILLISECONDS).width(2).height(2).build()));
            var options = Mj2EncoderOptions.lossy(15).toBuilder().pixelFormat(Mj2PixelFormat.RGB).bitDepth(9)
                    .bitrate(5).noBitrate().build();
            assertEquals(options, options.toBuilder().build());
            assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
            assertFalse(options.bitrate().isPresent());
            assertFalse(options.coding().isReversible());
            assertEquals(Mj2EncoderOptions.defaults(), Mj2EncoderOptions.builder().build());
            assertEquals(Mj2PixelFormat.YUV420, Mj2EncoderOptions.defaults().pixelFormat());
        }
    }

    @Nested
    @DisplayName("damaged frames")
    class Damaged {
        @Test
        @DisplayName("raise Malformed for frames of no known form or without a complete codestream")
        void malformed() {
            var decoder = new Mj2Decoder();
            assertThrows(MediaException.Malformed.class, () -> decoder.decodeFrame(new byte[0], 0, 0));
            var text = "definitely not JPEG 2000".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            assertThrows(MediaException.Malformed.class, () -> decoder.decodeFrame(text, 0, text.length));
            var packet = Mj2Testing.encode(new Mj2Encoder(16, 16), List.of(Mj2Testing.yuv(16, 16,
                    ChromaFormat.YUV420, 0))).getFirst().data();
            var sample = Jp2Frames.toSample(packet);
            var cut = Arrays.copyOf(sample, sample.length / 2);
            assertThrows(MediaException.Malformed.class, () -> decoder.decodeFrame(cut, 0, cut.length));
            assertThrows(IndexOutOfBoundsException.class, () -> decoder.decodeFrame(packet, 4, packet.length));
        }

        @Test
        @DisplayName("throw only MediaException for truncated and mutated frames, and decode the next frame intact")
        void robust() {
            var encoder = new Mj2Encoder(24, 16, Mj2EncoderOptions.builder().fieldOrder(FieldOrder.TOP_FIRST).build());
            var picture = Mj2Testing.yuv(24, 16, ChromaFormat.YUV420, 0);
            var packet = Mj2Testing.encode(encoder, List.of(picture)).getFirst().data();
            var decoder = new Mj2Decoder(track(encoder, Interlacing.TOP_FIELD_FIRST_SEPARATED));
            var random = new Random(11);
            for (var round = 0; round < 300; round++) {
                var mutated = round < 100 ? Arrays.copyOf(packet, random.nextInt(packet.length))
                        : packet.clone();
                for (var flips = 0; round >= 100 && flips < 3; flips++) {
                    mutated[random.nextInt(mutated.length)] = (byte) random.nextInt(256);
                }
                try {
                    decoder.decodeFrame(mutated, 0, mutated.length);
                } catch (MediaException expected) {
                    // damaged frames may be rejected; nothing else may escape
                }
            }
            Tolerance.exact().assertWithin("next frame", picture, decoder.decodeFrame(packet, 0, packet.length));
        }
    }
}
