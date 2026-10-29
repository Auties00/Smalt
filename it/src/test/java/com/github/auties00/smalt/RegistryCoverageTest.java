package com.github.auties00.smalt;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.aac.AacEncoder;
import com.github.auties00.smalt.audio.aac.AacEncoderOptions;
import com.github.auties00.smalt.audio.opus.OpusEncoder;
import com.github.auties00.smalt.audio.opus.OpusApplication;
import com.github.auties00.smalt.audio.opus.OpusEncoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.bmp.BmpEncoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import com.github.auties00.smalt.image.png.PngEncoder;
import com.github.auties00.smalt.image.png.PngEncoderOptions;
import com.github.auties00.smalt.image.webp.WebpEncoder;
import com.github.auties00.smalt.image.webp.WebpEncoderOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.av1.Av1Encoder;
import com.github.auties00.smalt.video.av1.Av1Decoder;
import com.github.auties00.smalt.video.av1.Av1EncoderOptions;
import com.github.auties00.smalt.video.av1.Av1EncoderSpeed;
import com.github.auties00.smalt.video.av1.Av1EncoderUsage;
import com.github.auties00.smalt.video.h264.encoder.H264Encoder;
import com.github.auties00.smalt.video.h264.encoder.H264EncoderOptions;
import com.github.auties00.smalt.video.vp8.Vp8Encoder;
import com.github.auties00.smalt.video.vp8.Vp8EncoderOptions;
import com.github.auties00.smalt.video.vp8.Vp8EncoderSpeed;
import com.github.auties00.smalt.video.vp9.Vp9Encoder;
import com.github.auties00.smalt.video.vp9.Vp9EncoderOptions;
import com.github.auties00.smalt.video.vp9.Vp9EncoderSpeed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the registries with every Smalt artifact present: which codecs and formats they
 * support, that their codec-independent options map onto the codecs' own configurations, and that
 * encoding through a registry or a sink produces the bytes of the codec's own entry point.
 */
@DisplayName("Registries with every artifact")
class RegistryCoverageTest {
    private static RgbaPicture picture() {
        var picture = RgbaPicture.allocate(48, 32, false);
        var pixels = picture.pixels();
        for (var i = 0; i < pixels.length; i += 4) {
            var x = (i / 4) % 48;
            var y = (i / 4) / 48;
            pixels[i] = (byte) (x * 5);
            pixels[i + 1] = (byte) (y * 7);
            pixels[i + 2] = (byte) ((x ^ y) * 3);
            pixels[i + 3] = (byte) 255;
        }
        return picture;
    }

    @Test
    @DisplayName("report the decoders and encoders of every artifact")
    void supported() {
        for (var codec : new Codec[]{Codec.H264, Codec.HEVC, Codec.VP8, Codec.VP9, Codec.AV1, Codec.MJPEG,
                Codec.JPEG2000, Codec.MPEG4_PART2, Codec.H263, Codec.MPEG1_VIDEO, Codec.MPEG2_VIDEO, Codec.PRORES,
                Codec.DNXHD}) {
            assertTrue(com.github.auties00.smalt.video.VideoDecoders.isSupported(codec), codec.name());
        }
        for (var codec : new Codec[]{Codec.AAC, Codec.MP3, Codec.MP2, Codec.OPUS, Codec.VORBIS, Codec.FLAC, Codec.PCM_F32LE}) {
            assertTrue(AudioDecoders.isSupported(codec), codec.name());
        }
        assertTrue(AudioDecoders.isSupported(Codec.AC3));
        assertTrue(AudioDecoders.isSupported(Codec.EAC3));
        assertTrue(AudioEncoders.isSupported(Codec.AC3));
        assertTrue(AudioEncoders.isSupported(Codec.EAC3));
        for (var codec : new Codec[]{Codec.H264, Codec.VP8, Codec.VP9, Codec.AV1, Codec.MJPEG, Codec.JPEG2000,
                Codec.PRORES, Codec.DNXHD, Codec.MPEG4_PART2, Codec.H263}) {
            assertTrue(VideoEncoders.isSupported(codec), codec.name());
        }
        assertTrue(AudioEncoders.isSupported(Codec.OPUS));
        assertTrue(AudioEncoders.isSupported(Codec.AAC));
        assertTrue(AudioDecoders.isSupported(Codec.AMR_NB));
        assertTrue(AudioDecoders.isSupported(Codec.AMR_WB));
        assertTrue(AudioEncoders.isSupported(Codec.AMR_NB));
        assertTrue(AudioEncoders.isSupported(Codec.AMR_WB));
        assertTrue(AudioEncoders.isSupported(Codec.FLAC));
        assertTrue(AudioEncoders.isSupported(Codec.ALAC));
        assertTrue(AudioDecoders.isSupported(Codec.ALAC));
        for (var format : new ImageFormat[]{ImageFormat.JPEG, ImageFormat.PNG, ImageFormat.GIF, ImageFormat.WEBP, ImageFormat.BMP,
                ImageFormat.TIFF, ImageFormat.ICO, ImageFormat.ANI, ImageFormat.JP2}) {
            assertTrue(Images.isDecodable(format), format.name());
        }
        assertTrue(Images.isDecodable(ImageFormat.AVIF));
        assertTrue(Images.isDecodable(ImageFormat.HEIC));
        assertTrue(Images.isDecodable(ImageFormat.DNG));
        assertTrue(Images.isDecodable(ImageFormat.RAW));
        assertFalse(Images.isEncodable(ImageFormat.DNG));
        assertTrue(Images.isEncodable(ImageFormat.JPEG));
        assertTrue(Images.isEncodable(ImageFormat.PNG));
        assertTrue(Images.isEncodable(ImageFormat.WEBP));
        assertTrue(Images.isEncodable(ImageFormat.GIF));
        assertTrue(Images.isEncodable(ImageFormat.TIFF));
        assertTrue(Images.isEncodable(ImageFormat.ICO));
        assertTrue(Images.isEncodable(ImageFormat.ANI));
        assertTrue(Images.isEncodable(ImageFormat.JP2));
        assertTrue(Images.isEncodable(ImageFormat.BMP));
        assertTrue(Images.isAnimationEncodable(ImageFormat.WEBP));
        assertTrue(Images.isAnimationEncodable(ImageFormat.GIF));
        assertTrue(Images.isAnimationEncodable(ImageFormat.PNG));
        assertTrue(Images.isAnimationEncodable(ImageFormat.ANI));
        assertTrue(Images.isEncodable(ImageFormat.AVIF));
        assertTrue(Images.isAnimationEncodable(ImageFormat.AVIF));
        assertFalse(Images.isAnimationEncodable(ImageFormat.JPEG));
    }

    @Nested
    @DisplayName("image encoding")
    class ImageEncoding {
        @Test
        @DisplayName("produces the bytes of each format's encoder, through byte arrays and sinks")
        void bitIdentical() {
            var picture = picture();
            var jpeg = JpegEncoder.encode(picture, JpegEncoderOptions.ofQuality(85));
            assertArrayEquals(jpeg, Images.encode(picture, ImageFormat.JPEG, ImageEncoderOptions.ofQuality(85)));
            assertArrayEquals(JpegEncoder.encode(picture, JpegEncoderOptions.ofQuality(75)), Images.encode(picture, ImageFormat.JPEG));
            var sink = MediaSink.memory();
            JpegEncoder.encode(picture, JpegEncoderOptions.ofQuality(85), sink);
            assertArrayEquals(jpeg, sink.toByteArray());
            var png = PngEncoder.encode(picture, PngEncoderOptions.defaults());
            assertArrayEquals(png, Images.encode(picture, ImageFormat.PNG, ImageEncoderOptions.ofQuality(10)));
            var lossless = WebpEncoder.encode(picture, WebpEncoderOptions.lossless());
            assertArrayEquals(lossless, Images.encode(picture, ImageFormat.WEBP, ImageEncoderOptions.lossless()));
            var lossy = WebpEncoder.encode(picture, WebpEncoderOptions.lossy(75));
            var webpSink = MediaSink.memory();
            Images.encode(picture, ImageFormat.WEBP, ImageEncoderOptions.defaults(), webpSink);
            assertArrayEquals(lossy, webpSink.toByteArray());
            assertEquals(Optional.of(ImageFormat.WEBP), Images.detect(MediaSource.of(lossy)));
            var bmp = BmpEncoder.encode(picture);
            assertArrayEquals(bmp, Images.encode(picture, ImageFormat.BMP, ImageEncoderOptions.ofQuality(10)));
            assertEquals(Optional.of(ImageFormat.BMP), Images.detect(MediaSource.of(bmp)));
        }

        @Test
        @DisplayName("codes lossless JPEG in RGB, which decodes to the same pixels")
        void losslessJpeg() {
            var picture = picture();
            var jpeg = Images.encode(picture, ImageFormat.JPEG, ImageEncoderOptions.lossless());
            assertArrayEquals(JpegEncoder.encode(picture, JpegEncoderOptions.ofLossless()), jpeg);
            var decoded = assertInstanceOf(RgbaPicture.class, Images.decode(MediaSource.of(jpeg)).picture());
            assertArrayEquals(picture.pixels(), decoded.pixels());
        }
    }

    @Nested
    @DisplayName("audio encoding")
    class AudioEncoding {
        @Test
        @DisplayName("maps the options onto Opus and AAC configurations")
        void configurations() {
            try (var opus = AudioEncoders.create(Codec.OPUS, AudioEncoderOptions.of(48_000, ChannelLayout.STEREO))) {
                var config = assertInstanceOf(OpusEncoder.class, opus).options();
                assertEquals(OpusEncoderOptions.builder().bitrate(64_000).build(), config);
            }
            try (var opus = AudioEncoders.create(Codec.OPUS, AudioEncoderOptions.forRealtime(16_000, ChannelLayout.MONO)
                    .toBuilder().bitrate(20_000).build())) {
                var config = assertInstanceOf(OpusEncoder.class, opus).options();
                assertEquals(OpusApplication.VOIP, config.application());
                assertEquals(OptionalInt.of(20_000), config.bitrate());
                assertEquals(16_000, config.sampleRate());
            }
            try (var aac = AudioEncoders.create(Codec.AAC)) {
                assertEquals(AacEncoderOptions.of(48_000, ChannelLayout.STEREO, 128_000),
                        assertInstanceOf(AacEncoder.class, aac).options());
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Codec.class, names = {"OPUS", "AAC"})
        @DisplayName("encodes packets a registry decoder accepts")
        void roundTrip(Codec codec) {
            var packets = new ArrayList<Packet>();
            try (var encoder = AudioEncoders.create(codec, AudioEncoderOptions.of(48_000, ChannelLayout.MONO))) {
                var block = new AudioBuffer(ChannelLayout.MONO, 48_000, 4800);
                for (var i = 0; i < 4800; i++) {
                    block.planes()[0][i] = (float) Math.sin(i * 0.05) * 0.5f;
                }
                block.setFrames(4800);
                for (var n = 0; n < 5; n++) {
                    encoder.encode(block, packets::add);
                }
                encoder.flush(packets::add);
            }
            assertFalse(packets.isEmpty());
        }
    }

    @Nested
    @DisplayName("video encoding")
    class VideoEncoding {
        @Test
        @DisplayName("maps the options onto H.264, VP8, VP9 and AV1 options")
        void configurations() {
            var rate = new Rational(25, 1);
            try (var h264 = VideoEncoders.create(Codec.H264, VideoEncoderOptions.of(320, 240, rate, 500_000))) {
                var expected = H264EncoderOptions.forFile().toBuilder()
                        .frameRate(rate)
                        .timeBase(new Rational(1, 25))
                        .bitrate(500_000)
                        .keyframeInterval(50)
                        .build();
                assertEquals(expected, assertInstanceOf(H264Encoder.class, h264).options());
                assertEquals(320, h264.width());
            }
            try (var h264 = VideoEncoders.create(Codec.H264, VideoEncoderOptions.forRealtime(320, 240, rate, 500_000)
                    .toBuilder().threads(2).build())) {
                assertEquals(H264EncoderOptions.forRealtime().toBuilder()
                        .frameRate(rate).bitrate(500_000).threads(2).build(),
                        assertInstanceOf(H264Encoder.class, h264).options());
            }
            try (var vp8 = VideoEncoders.create(Codec.VP8, VideoEncoderOptions.forRealtime(320, 240, rate, 500_000))) {
                assertEquals(Vp8EncoderOptions.forRealtime().toBuilder().frameRate(rate).bitrate(500_000)
                                .maxKeyframeBitratePercent(750).build(),
                        assertInstanceOf(Vp8Encoder.class, vp8).options());
            }
            try (var vp8 = VideoEncoders.create(Codec.VP8, VideoEncoderOptions.of(320, 240, rate, 500_000).toBuilder()
                    .threads(3).build())) {
                assertEquals(Vp8EncoderOptions.ofBitrate(500_000).toBuilder().speed(Vp8EncoderSpeed.SPEED_4)
                                .frameRate(rate).threads(3).build(),
                        assertInstanceOf(Vp8Encoder.class, vp8).options());
            }
            try (var vp9 = VideoEncoders.create(Codec.VP9, VideoEncoderOptions.forRealtime(320, 240, rate, 500_000))) {
                assertEquals(Vp9EncoderOptions.forRealtime().toBuilder().frameRate(rate).bitrate(500_000).build(),
                        assertInstanceOf(Vp9Encoder.class, vp9).options());
            }
            try (var vp9 = VideoEncoders.create(Codec.VP9, VideoEncoderOptions.of(320, 240, rate, 500_000))) {
                assertEquals(Vp9EncoderOptions.ofBitrate(500_000).toBuilder().speed(Vp9EncoderSpeed.SPEED_4)
                                .frameRate(rate).build(),
                        assertInstanceOf(Vp9Encoder.class, vp9).options());
            }
            try (var av1 = VideoEncoders.create(Codec.AV1, VideoEncoderOptions.forRealtime(320, 240, rate, 500_000)
                    .toBuilder().threads(4).build())) {
                assertEquals(Av1EncoderOptions.forRealtime().toBuilder()
                        .frameRate(rate).bitrate(500_000).threads(4).build(),
                        assertInstanceOf(Av1Encoder.class, av1).options());
            }
            try (var av1 = VideoEncoders.create(Codec.AV1, VideoEncoderOptions.of(320, 240, rate, 500_000))) {
                var options = assertInstanceOf(Av1Encoder.class, av1).options();
                assertEquals(Av1EncoderOptions.ofBitrate(500_000).toBuilder()
                        .speed(Av1EncoderSpeed.FAST).frameRate(rate).build(),
                        options);
                assertEquals(Av1EncoderUsage.GOOD_QUALITY, options.usage());
            }
        }

        @Test
        @DisplayName("maps the key frame settings onto H.264, VP8, VP9 and AV1 options")
        void keyframeSettings() {
            var rate = new Rational(25, 1);
            var interval = VideoEncoderOptions.of(320, 240, rate, 500_000).toBuilder().keyframeInterval(12).build();
            var none = VideoEncoderOptions.of(320, 240, rate, 500_000).toBuilder().noPeriodicKeyframes().build();
            try (var h264 = VideoEncoders.create(Codec.H264, interval)) {
                assertEquals(OptionalInt.of(12), assertInstanceOf(H264Encoder.class, h264).options().keyframeInterval());
            }
            try (var h264 = VideoEncoders.create(Codec.H264, none)) {
                assertEquals(H264EncoderOptions.forFile().toBuilder().frameRate(rate).timeBase(new Rational(1, 25))
                        .bitrate(500_000).noPeriodicKeyframes().build(), assertInstanceOf(H264Encoder.class, h264).options());
            }
            try (var h264 = VideoEncoders.create(Codec.H264, VideoEncoderOptions.forRealtime(320, 240, rate, 500_000)
                    .toBuilder().keyframeInterval(50).build())) {
                assertEquals(H264EncoderOptions.forRealtime().toBuilder().frameRate(rate).bitrate(500_000)
                        .keyframeInterval(50).build(), assertInstanceOf(H264Encoder.class, h264).options());
            }
            try (var vp8 = VideoEncoders.create(Codec.VP8, interval)) {
                assertEquals(Vp8EncoderOptions.ofBitrate(500_000).toBuilder().speed(Vp8EncoderSpeed.SPEED_4)
                                .frameRate(rate).keyframeInterval(12).build(),
                        assertInstanceOf(Vp8Encoder.class, vp8).options());
            }
            try (var vp8 = VideoEncoders.create(Codec.VP8, none)) {
                assertEquals(Vp8EncoderOptions.ofBitrate(500_000).toBuilder().speed(Vp8EncoderSpeed.SPEED_4)
                                .frameRate(rate).noPeriodicKeyframes().build(),
                        assertInstanceOf(Vp8Encoder.class, vp8).options());
            }
            try (var vp9 = VideoEncoders.create(Codec.VP9, interval)) {
                assertEquals(Vp9EncoderOptions.ofBitrate(500_000).toBuilder().speed(Vp9EncoderSpeed.SPEED_4)
                                .frameRate(rate).keyframeInterval(12).build(),
                        assertInstanceOf(Vp9Encoder.class, vp9).options());
            }
            try (var vp9 = VideoEncoders.create(Codec.VP9, none)) {
                assertEquals(Vp9EncoderOptions.ofBitrate(500_000).toBuilder().speed(Vp9EncoderSpeed.SPEED_4)
                                .frameRate(rate).noPeriodicKeyframes().build(),
                        assertInstanceOf(Vp9Encoder.class, vp9).options());
            }
            try (var av1 = VideoEncoders.create(Codec.AV1, interval)) {
                assertEquals(Av1EncoderOptions.ofBitrate(500_000).toBuilder().speed(Av1EncoderSpeed.FAST).frameRate(rate)
                        .keyframeInterval(12).build(), assertInstanceOf(Av1Encoder.class, av1).options());
            }
            try (var av1 = VideoEncoders.create(Codec.AV1, VideoEncoderOptions.forRealtime(320, 240, rate, 500_000)
                    .toBuilder().keyframeInterval(12).build())) {
                assertEquals(Av1EncoderOptions.forRealtime().toBuilder().frameRate(rate).bitrate(500_000)
                        .keyframeInterval(12).build(), assertInstanceOf(Av1Encoder.class, av1).options());
            }
        }

        @Test
        @DisplayName("codes non-real-time AV1 with the good-quality usage: frames held back, drained by flush")
        void av1GoodQuality() {
            var frames = 40;
            var packets = new ArrayList<Packet>();
            var duringEncode = 0;
            try (var encoder = VideoEncoders.create(Codec.AV1, VideoEncoderOptions.of(48, 32, new Rational(30, 1), 200_000))) {
                assertTrue(encoder.codecConfig().isPresent(), "av1C before the first frame");
                for (var i = 0; i < frames; i++) {
                    var picture = picture();
                    var pixels = picture.pixels();
                    for (var p = 0; p < pixels.length; p += 4) {
                        pixels[p] = (byte) (pixels[p] + i * 3);
                    }
                    encoder.encode(new VideoFrame(picture, i * 3000L, 3000, false), packets::add);
                }
                duringEncode = packets.size();
                encoder.flush(packets::add);
            }
            assertTrue(duringEncode < frames, "the lookahead holds frames back: " + duringEncode);
            assertEquals(frames, packets.size(), "one packet per frame");
            assertTrue(packets.getFirst().keyframe());
            for (var i = 0; i < frames; i++) {
                assertEquals(i * 3000L, packets.get(i).pts(), "display order");
                assertEquals(packets.get(i).pts(), packets.get(i).dts(), "no reordering");
            }
            var decoded = new ArrayList<VideoFrame>();
            try (var decoder = new Av1Decoder(com.github.auties00.smalt.track.VideoTrack.builder().id(1).codec(Codec.AV1)
                    .timeBase(new Rational(1, 90_000)).width(48).height(32).build())) {
                for (var packet : packets) {
                    decoder.decode(packet, decoded::add);
                }
                decoder.flush(decoded::add);
            }
            assertEquals(frames, decoded.size(), "every frame decodes");
        }

        @Test
        @DisplayName("codes non-real-time VP8 with the good-quality usage: hidden frames in packets of their own")
        void vp8GoodQuality() {
            var frames = 40;
            var packets = new ArrayList<Packet>();
            var duringEncode = 0;
            try (var encoder = VideoEncoders.create(Codec.VP8, VideoEncoderOptions.of(48, 32, new Rational(30, 1), 200_000))) {
                for (var i = 0; i < frames; i++) {
                    var picture = picture();
                    var pixels = picture.pixels();
                    for (var p = 0; p < pixels.length; p += 4) {
                        pixels[p] = (byte) (pixels[p] + i * 3);
                    }
                    encoder.encode(new VideoFrame(picture, i * 3000L, 3000, false), packets::add);
                }
                duringEncode = packets.size();
                encoder.flush(packets::add);
            }
            assertTrue(duringEncode < frames, "the lookahead holds frames back: " + duringEncode);
            var shown = packets.stream().filter(p -> !p.flags().contains(com.github.auties00.smalt.track.PacketFlag.DISCARD))
                    .toList();
            assertEquals(frames, shown.size(), "one packet per shown frame");
            assertTrue(shown.getFirst().keyframe());
            for (var i = 0; i < frames; i++) {
                assertEquals(i * 3000L, shown.get(i).pts(), "display order");
            }
            var decoded = new ArrayList<VideoFrame>();
            try (var decoder = new com.github.auties00.smalt.video.vp8.Vp8Decoder(com.github.auties00.smalt.track.VideoTrack
                    .builder().id(1).codec(Codec.VP8).timeBase(new Rational(1, 90_000)).width(48).height(32).build())) {
                for (var packet : packets) {
                    decoder.decode(packet, decoded::add);
                }
                decoder.flush(decoded::add);
            }
            assertEquals(frames, decoded.size(), "every shown frame decodes");
        }

        @Test
        @DisplayName("codes non-real-time VP9 with the good-quality usage: frames held back, drained by flush")
        void vp9GoodQuality() {
            var frames = 40;
            var packets = new ArrayList<Packet>();
            var duringEncode = 0;
            try (var encoder = VideoEncoders.create(Codec.VP9, VideoEncoderOptions.of(48, 32, new Rational(30, 1), 200_000))) {
                for (var i = 0; i < frames; i++) {
                    var picture = picture();
                    var pixels = picture.pixels();
                    for (var p = 0; p < pixels.length; p += 4) {
                        pixels[p] = (byte) (pixels[p] + i * 3);
                    }
                    encoder.encode(new VideoFrame(picture, i * 3000L, 3000, false), packets::add);
                }
                duringEncode = packets.size();
                encoder.flush(packets::add);
            }
            assertTrue(duringEncode < frames, "the lookahead holds frames back: " + duringEncode);
            assertEquals(frames, packets.size(), "one packet per frame");
            assertTrue(packets.getFirst().keyframe());
            for (var i = 0; i < frames; i++) {
                assertEquals(i * 3000L, packets.get(i).pts(), "display order");
            }
            var decoded = new ArrayList<VideoFrame>();
            try (var decoder = new com.github.auties00.smalt.video.vp9.Vp9Decoder(com.github.auties00.smalt.track.VideoTrack
                    .builder().id(1).codec(Codec.VP9).timeBase(new Rational(1, 90_000)).width(48).height(32).build())) {
                for (var packet : packets) {
                    decoder.decode(packet, decoded::add);
                }
                decoder.flush(decoded::add);
            }
            assertEquals(frames, decoded.size(), "every frame decodes");
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Codec.class, names = {"H264", "VP8", "VP9", "AV1"})
        @DisplayName("encodes a key frame from the first picture")
        void encodes(Codec codec) {
            var packets = new ArrayList<Packet>();
            try (var encoder = VideoEncoders.create(codec, VideoEncoderOptions.forRealtime(48, 32, new Rational(30, 1), 200_000))) {
                encoder.encode(new VideoFrame(picture(), 0, 3000, false), packets::add);
                encoder.flush(packets::add);
            }
            assertFalse(packets.isEmpty());
            assertTrue(packets.getFirst().keyframe());
        }
    }
}
