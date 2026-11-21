package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.dv.DvFormat;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Interlacing;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.FieldOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the planning of a raw DV output: the format a transcode picks ({@link DvOutput}), the settings the format
 * imposes on the video and the audio, and the fitting of the pictures to the raster ({@link DvRaster}). The
 * transcodes themselves, which need the DV codec, are covered in the video module.
 */
@DisplayName("Raw DV output planning")
class DvOutputTest {
    private static VideoTrack video(int width, int height, Rational rate, Orientation orientation) {
        var builder = VideoTrack.builder().id(1).codec(Codec.RAW_VIDEO).timeBase(new Rational(1, 90000)).width(width)
                .height(height).orientation(orientation);
        if (rate != null) {
            builder.frameRate(rate);
        }
        return builder.build();
    }

    private static AudioTrack audio(int id, ChannelLayout layout) {
        return AudioTrack.builder().id(id).codec(Codec.AAC).timeBase(new Rational(1, 44100)).sampleRate(44100)
                .layout(layout).build();
    }

    @Nested
    @DisplayName("format")
    class Format {
        @ParameterizedTest(name = "{0}x{1} at {2}/{3} -> {4}")
        @CsvSource({
                "1920, 1080, 30000, 1001, DVCPRO_HD_1080I60",
                "1920, 1080, 25, 1, DVCPRO_HD_1080I50",
                "1920, 1080, 50, 1, DVCPRO_HD_1080I50",
                "1440, 1080, 24, 1, DVCPRO_HD_1080I60",
                "1280, 720, 50, 1, DVCPRO_HD_720P50",
                "1280, 720, 60000, 1001, DVCPRO_HD_720P60",
                "1280, 720, 24000, 1001, DVCPRO_HD_720P60",
                "640, 360, 24, 1, DV_525",
                "720, 576, 25, 1, DV_625",
                "352, 288, 25, 2, DV_625",
                "720, 480, 15, 1, DV_525",
                "720, 480, 100, 1, DV_625",
                "720, 480, 10, 1, DV_525",
                "320, 240, 0, 1, DV_525"})
        @DisplayName("picks the format of the displayed height and the rate family")
        void automatic(int width, int height, long numerator, long denominator, DvFormat expected) {
            var rate = numerator == 0 ? null : new Rational(numerator, denominator);
            assertEquals(expected, DvOutput.resolve(VideoTranscodeOptions.defaults(),
                    video(width, height, rate, Orientation.NORMAL), new Rational(1, 1)));
        }

        @Test
        @DisplayName("follows the requested rate, the speed, telecine, the crop and the orientation")
        void inputs() {
            var hd = video(1920, 1080, new Rational(25, 1), Orientation.NORMAL);
            var one = new Rational(1, 1);
            assertEquals(DvFormat.DVCPRO_HD_1080I60, DvOutput.resolve(VideoTranscodeOptions.builder()
                    .frameRate(new Rational(30000, 1001)).build(), hd, one));
            assertEquals(DvFormat.DVCPRO_HD_1080I60, DvOutput.resolve(VideoTranscodeOptions.defaults(), hd,
                    new Rational(6, 5)));
            assertEquals(DvFormat.DVCPRO_HD_1080I60, DvOutput.resolve(VideoTranscodeOptions.builder()
                    .pulldown(Pulldown.TELECINE).build(), hd, one));
            assertEquals(DvFormat.DV_625, DvOutput.resolve(VideoTranscodeOptions.builder()
                    .crop(new Region(0, 0, 960, 540)).build(), hd, one));
            assertEquals(DvFormat.DVCPRO_HD_1080I50, DvOutput.resolve(VideoTranscodeOptions.defaults(),
                    video(1080, 1920, new Rational(25, 1), Orientation.ROTATE_90), one));
            assertEquals(DvFormat.DVCPRO50_525, DvOutput.resolve(VideoTranscodeOptions.builder()
                    .dvFormat(DvFormat.DVCPRO50_525).build(), hd, one));
        }

        @Test
        @DisplayName("tells the 50 Hz rates from the 60 Hz ones at any power of two")
        void families() {
            for (var rate : new double[]{25, 50, 12.5, 100, 6.25, 26}) {
                assertTrue(DvOutput.fiftyHz(rate), Double.toString(rate));
            }
            for (var rate : new double[]{24, 30, 60, 15, 120, 10, 30000 / 1001.0, 0, -1, Double.NaN,
                    Double.POSITIVE_INFINITY}) {
                assertFalse(DvOutput.fiftyHz(rate), Double.toString(rate));
            }
        }

        @Test
        @DisplayName("recognizes the format of a DV track by its size and rate")
        void ofTrack() {
            assertEquals(DvFormat.DV_525, DvOutput.formatOf(video(720, 480, new Rational(30000, 1001),
                    Orientation.NORMAL)));
            assertEquals(DvFormat.DV_625, DvOutput.formatOf(video(720, 576, new Rational(25, 1), Orientation.NORMAL)));
            assertEquals(DvFormat.DVCPRO_HD_1080I60, DvOutput.formatOf(video(1280, 1080, null, Orientation.NORMAL)));
            assertEquals(DvFormat.DVCPRO_HD_1080I50, DvOutput.formatOf(video(1440, 1080, null, Orientation.NORMAL)));
            assertEquals(DvFormat.DVCPRO_HD_720P50, DvOutput.formatOf(video(960, 720, new Rational(50, 1),
                    Orientation.NORMAL)));
            assertEquals(DvFormat.DVCPRO_HD_720P60, DvOutput.formatOf(video(960, 720, null, Orientation.NORMAL)));
            assertEquals(null, DvOutput.formatOf(video(640, 480, null, Orientation.NORMAL)));
        }

        @ParameterizedTest
        @EnumSource(DvFormat.class)
        @DisplayName("describes each format as the DIF frames carry it")
        void describes(DvFormat format) {
            assertEquals(format.width() * format.height() > 720 * 576, format.highDefinition());
            assertEquals(format.frameRate().denominator() == 1, format.fiftyHz());
            assertEquals(format.highDefinition() ? 100_000_000L : format.name().contains("50") ? 50_000_000L
                    : 25_000_000L, format.bitrate());
            assertEquals(format.name().startsWith("DV_"), format.consumer());
            assertEquals(format.consumer() ? 4 : 0, format.maxAudioChannels(32_000));
            assertEquals(format.consumer() ? 2 : 0, format.maxAudioChannels(44_100));
            assertEquals(format.highDefinition() ? 8 : format.name().contains("50") ? 4 : 2,
                    format.maxAudioChannels(48_000));
            assertEquals(0, format.maxAudioChannels(96_000));
            var display = format.width() * format.sampleAspectRatio(true).toDouble() / format.height();
            assertEquals(16 / 9.0, display, 0.01);
            if (!format.highDefinition()) {
                assertEquals(4 / 3.0, format.width() * format.sampleAspectRatio(false).toDouble() / format.height(),
                        0.01);
            }
            assertEquals(format.height() == 720 ? Interlacing.PROGRESSIVE : format.highDefinition()
                    ? Interlacing.TOP_FIELD_FIRST : Interlacing.BOTTOM_FIELD_FIRST, format.interlacing());
        }
    }

    @Nested
    @DisplayName("video settings")
    class Video {
        @Test
        @DisplayName("fix the frame rate and color, from four fifths of the rate with telecine")
        void derived() {
            var settings = DvOutput.video(VideoTranscodeOptions.defaults(), DvFormat.DV_625);
            assertEquals(new Rational(25, 1), settings.frameRate().orElseThrow());
            assertEquals(ColorInfo.BT601_LIMITED, settings.color().orElseThrow());
            var hd = DvOutput.video(VideoTranscodeOptions.builder().maxFrameRate(new Rational(60, 1)).build(),
                    DvFormat.DVCPRO_HD_720P60);
            assertEquals(new Rational(60000, 1001), hd.frameRate().orElseThrow());
            assertEquals(ColorInfo.BT709_LIMITED, hd.color().orElseThrow());
            assertTrue(hd.maxFrameRate().isEmpty());
            var telecine = DvOutput.video(VideoTranscodeOptions.builder().pulldown(Pulldown.TELECINE).build(),
                    DvFormat.DV_525);
            assertEquals(new Rational(24000, 1001), telecine.frameRate().orElseThrow());
            var same = DvOutput.video(VideoTranscodeOptions.builder().frameRate(new Rational(50, 2)).bitDepth(8)
                    .color(ColorInfo.BT601_LIMITED).build(), DvFormat.DVCPRO50_625);
            assertEquals(25.0, same.frameRate().orElseThrow().toDouble());
        }

        @Test
        @DisplayName("reject a size, another rate, color or depth, and telecine into a 50 Hz or progressive format")
        void rejects() {
            for (var settings : List.of(
                    VideoTranscodeOptions.builder().size(640, 480, ScaleMode.FIT).build(),
                    VideoTranscodeOptions.builder().maxLongEdge(640).build(),
                    VideoTranscodeOptions.builder().frameRate(new Rational(24, 1)).build(),
                    VideoTranscodeOptions.builder().maxFrameRate(new Rational(15, 1)).build(),
                    VideoTranscodeOptions.builder().color(ColorInfo.BT709_LIMITED).build(),
                    VideoTranscodeOptions.builder().bitDepth(10).build())) {
                assertThrows(IllegalArgumentException.class, () -> DvOutput.video(settings, DvFormat.DV_525),
                        settings.toString());
            }
            var telecine = VideoTranscodeOptions.builder().pulldown(Pulldown.TELECINE).build();
            assertThrows(IllegalArgumentException.class, () -> DvOutput.video(telecine, DvFormat.DV_625));
            assertThrows(IllegalArgumentException.class, () -> DvOutput.video(telecine, DvFormat.DVCPRO_HD_720P60));
        }
    }

    @Nested
    @DisplayName("audio settings")
    class Audio {
        private static void assertAudio(AudioTranscodeOptions settings, DvFormat format, List<AudioTrack> tracks,
                                        int rate, int channels) {
            var derived = DvOutput.audio(settings, format, tracks);
            assertEquals(rate, derived.sampleRate().orElseThrow(), format + " rate");
            assertEquals(channels, derived.maxChannels().orElseThrow(), format + " channels");
        }

        @Test
        @DisplayName("resample to 48 kHz and keep the channels the format carries")
        void single() {
            var stereo = List.of(audio(2, ChannelLayout.STEREO));
            var surround = List.of(audio(2, ChannelLayout.SURROUND_5_1));
            var defaults = AudioTranscodeOptions.defaults();
            assertAudio(defaults, DvFormat.DV_525, stereo, 48000, 2);
            assertAudio(defaults, DvFormat.DV_525, List.of(audio(2, ChannelLayout.MONO)), 48000, 1);
            assertAudio(defaults, DvFormat.DV_625, surround, 48000, 2);
            assertAudio(defaults, DvFormat.DVCPRO_625, surround, 48000, 2);
            assertAudio(defaults, DvFormat.DVCPRO50_525, surround, 48000, 4);
            assertAudio(defaults, DvFormat.DVCPRO_HD_1080I50, surround, 48000, 6);
            assertAudio(defaults, DvFormat.DVCPRO_HD_1080I60, List.of(audio(2, ChannelLayout.SURROUND_7_1)), 48000, 8);
            assertAudio(defaults, DvFormat.DVCPRO_HD_720P50, surround, 48000, 4);
            assertAudio(defaults, null, surround, 48000, 2);
            assertAudio(AudioTranscodeOptions.builder().sampleRate(44100).build(), DvFormat.DV_625, stereo, 44100, 2);
        }

        @Test
        @DisplayName("carry four channels at 32 kHz in a consumer format when the options ask for them")
        void fourChannels() {
            var surround = List.of(audio(2, ChannelLayout.SURROUND_5_1));
            assertAudio(AudioTranscodeOptions.builder().maxChannels(4).build(), DvFormat.DV_525, surround, 32000, 4);
            assertAudio(AudioTranscodeOptions.builder().sampleRate(32000).build(), DvFormat.DV_625, surround, 32000,
                    4);
            var quad = DvOutput.audio(AudioTranscodeOptions.builder().layout(ChannelLayout.defaultFor(4)).build(),
                    DvFormat.DV_625, surround);
            assertEquals(32000, quad.sampleRate().orElseThrow());
            assertTrue(quad.maxChannels().isEmpty());
            assertAudio(AudioTranscodeOptions.builder().maxChannels(4).build(), DvFormat.DV_525,
                    List.of(audio(2, ChannelLayout.STEREO)), 48000, 2);
        }

        @Test
        @DisplayName("give several tracks a stereo pair each, at 32 kHz when a consumer format needs two pairs")
        void several() {
            var two = List.of(audio(2, ChannelLayout.SURROUND_5_1), audio(3, ChannelLayout.STEREO));
            assertAudio(AudioTranscodeOptions.defaults(), DvFormat.DV_625, two, 32000, 2);
            assertAudio(AudioTranscodeOptions.defaults(), DvFormat.DVCPRO50_625, two, 48000, 2);
            assertAudio(AudioTranscodeOptions.defaults(), DvFormat.DVCPRO_HD_720P60, Collections.nCopies(4,
                    audio(2, ChannelLayout.STEREO)), 48000, 2);
            assertThrows(IllegalArgumentException.class, () -> DvOutput.audio(AudioTranscodeOptions.defaults(),
                    DvFormat.DVCPRO_625, two));
            assertThrows(IllegalArgumentException.class, () -> DvOutput.audio(AudioTranscodeOptions.defaults(),
                    DvFormat.DV_625, Collections.nCopies(3, audio(2, ChannelLayout.STEREO))));
            assertThrows(IllegalArgumentException.class, () -> DvOutput.audio(AudioTranscodeOptions.builder()
                    .layout(ChannelLayout.SURROUND_5_1).build(), DvFormat.DVCPRO_HD_1080I50, two));
        }

        @Test
        @DisplayName("reject a rate or layout the format does not carry")
        void rejects() {
            var stereo = List.of(audio(2, ChannelLayout.STEREO));
            assertThrows(IllegalArgumentException.class, () -> DvOutput.audio(AudioTranscodeOptions.builder()
                    .sampleRate(44100).build(), DvFormat.DVCPRO_525, stereo));
            assertThrows(IllegalArgumentException.class, () -> DvOutput.audio(AudioTranscodeOptions.builder()
                    .sampleRate(96000).build(), DvFormat.DVCPRO_HD_1080I50, stereo));
            assertThrows(IllegalArgumentException.class, () -> DvOutput.audio(AudioTranscodeOptions.builder()
                    .layout(ChannelLayout.SURROUND_5_1).build(), DvFormat.DVCPRO50_625, stereo));
            assertThrows(IllegalArgumentException.class, () -> DvOutput.audio(AudioTranscodeOptions.builder()
                    .layout(ChannelLayout.defaultFor(4)).sampleRate(48000).build(), DvFormat.DV_525, stereo));
            assertThrows(IllegalArgumentException.class, () -> DvOutput.audio(AudioTranscodeOptions.builder()
                    .layout(ChannelLayout.SURROUND_5_1).build(), DvFormat.DVCPRO_HD_720P50, stereo));
            assertSame(AudioTranscodeOptions.defaults(), DvOutput.audio(AudioTranscodeOptions.defaults(),
                    DvFormat.DV_525, List.of()));
        }
    }

    @Nested
    @DisplayName("raster")
    class Raster {
        @ParameterizedTest(name = "{1}x{2} into {0} -> {3}x{4} wide {5}")
        @CsvSource({
                "DV_525, 640, 480, 720, 480, false",
                "DV_525, 1920, 1080, 720, 480, true",
                "DV_625, 1920, 804, 720, 428, true",
                "DV_625, 1000, 1000, 540, 576, false",
                "DVCPRO_HD_1080I60, 1440, 1080, 960, 1080, true",
                "DVCPRO_HD_1080I50, 1920, 1080, 1440, 1080, true",
                "DVCPRO_HD_720P50, 3840, 1600, 960, 534, true",
                "DVCPRO50_525, 1460, 1080, 720, 474, false"})
        @DisplayName("fits the displayed picture to the display, 16:9 above 14:9, between black bars")
        void plan(DvFormat format, int width, int height, int coveredWidth, int coveredHeight, boolean wide) {
            var raster = new DvRaster(format);
            var plan = raster.plan(width, height, false);
            assertEquals(wide, raster.wide());
            assertEquals(coveredWidth, plan.outputWidth());
            assertEquals(coveredHeight, plan.outputHeight());
            assertEquals(coveredWidth, plan.scaledWidth());
            assertEquals(coveredHeight / 2, raster.plan(width, height, true).outputHeight());
            assertEquals(format.sampleAspectRatio(wide), raster.sampleAspectRatio());
        }

        private static YuvPicture picture(int width, int height, ChromaFormat chroma, long seed) {
            var picture = YuvPicture.allocate(width, height, chroma, false, ColorInfo.BT601_LIMITED);
            var random = new Random(seed);
            random.nextBytes(picture.y());
            random.nextBytes(picture.u());
            random.nextBytes(picture.v());
            return picture;
        }

        @Test
        @DisplayName("centers a picture on black at even offsets, keeping its samples")
        void place() {
            var raster = new DvRaster(DvFormat.DV_625);
            var content = picture(720, 428, ChromaFormat.YUV420, 1);
            var placed = (YuvPicture) raster.place(content, 0);
            assertEquals(720, placed.width());
            assertEquals(576, placed.height());
            assertEquals(ChromaFormat.YUV420, placed.chromaFormat());
            for (var row = 0; row < 576; row++) {
                for (var column = 0; column < 720; column += 7) {
                    var inside = row >= 74 && row < 74 + 428;
                    var expected = inside ? content.y()[(row - 74) * 720 + column] : (byte) 16;
                    assertEquals(expected, placed.y()[row * 720 + column], "luma " + row + "," + column);
                }
            }
            for (var row = 0; row < 288; row++) {
                var inside = row >= 37 && row < 37 + 214;
                var expected = inside ? content.u()[(row - 37) * 360 + 5] : (byte) 128;
                assertEquals(expected, placed.u()[row * 360 + 5], "chroma " + row);
            }
            var full = picture(720, 576, ChromaFormat.YUV422, 2);
            assertSame(full, raster.place(full, 0));
            var deep = Yuv16Picture.allocate(600, 576, ChromaFormat.YUV422, 10, false, ColorInfo.BT601_LIMITED);
            var placedDeep = (Yuv16Picture) raster.place(deep, 0);
            assertEquals(16 << 2, placedDeep.y()[0]);
            assertEquals(512, placedDeep.u()[0]);
            assertEquals(0, placedDeep.y()[60]);
        }

        @Test
        @DisplayName("moves a picture by a line when its field order differs from the format's")
        void shift() {
            var bottomFirst = new DvRaster(DvFormat.DV_525);
            assertEquals(1, bottomFirst.shift(FieldOrder.TOP_FIRST));
            assertEquals(0, bottomFirst.shift(FieldOrder.BOTTOM_FIRST));
            assertEquals(0, bottomFirst.shift(FieldOrder.PROGRESSIVE));
            var topFirst = new DvRaster(DvFormat.DVCPRO_HD_1080I50);
            assertEquals(-1, topFirst.shift(FieldOrder.BOTTOM_FIRST));
            assertEquals(0, topFirst.shift(FieldOrder.TOP_FIRST));
            assertEquals(0, new DvRaster(DvFormat.DVCPRO_HD_720P60).shift(FieldOrder.TOP_FIRST));
            var frame = picture(720, 480, ChromaFormat.YUV422, 3);
            var down = (YuvPicture) bottomFirst.place(frame, 1);
            for (var row = 1; row < 480; row++) {
                assertArrayEquals(java.util.Arrays.copyOfRange(frame.y(), (row - 1) * 720, row * 720),
                        java.util.Arrays.copyOfRange(down.y(), row * 720, (row + 1) * 720), "row " + row);
            }
            // the row the move empties takes the next row of the field now at the even rows
            assertArrayEquals(java.util.Arrays.copyOfRange(frame.y(), 720, 1440),
                    java.util.Arrays.copyOfRange(down.y(), 0, 720));
            var up = (YuvPicture) bottomFirst.place(frame, -1);
            for (var row = 0; row < 479; row++) {
                assertEquals(frame.y()[(row + 1) * 720 + 9], up.y()[row * 720 + 9], "row " + row);
            }
            assertEquals(frame.y()[478 * 720 + 9], up.y()[479 * 720 + 9]);
        }

        @Test
        @DisplayName("splits a picture into its fields and weaves them back unchanged")
        void fields() {
            for (var chroma : List.of(ChromaFormat.YUV420, ChromaFormat.YUV422, ChromaFormat.YUV444)) {
                var frame = picture(64, 48, chroma, 4);
                var top = (YuvPicture) DvRaster.field(frame, 0);
                var bottom = (YuvPicture) DvRaster.field(frame, 1);
                assertEquals(24, top.height());
                assertEquals(frame.y()[2 * 64 + 5], top.y()[64 + 5]);
                assertEquals(frame.y()[3 * 64 + 5], bottom.y()[64 + 5]);
                var woven = (YuvPicture) DvRaster.weave(top, bottom);
                assertArrayEquals(frame.y(), woven.y(), chroma.toString());
                assertArrayEquals(frame.u(), woven.u(), chroma.toString());
                assertArrayEquals(frame.v(), woven.v(), chroma.toString());
            }
            assertTrue(DvRaster.splittable(picture(8, 2, ChromaFormat.YUV420, 5)));
            assertFalse(DvRaster.splittable(picture(8, 1, ChromaFormat.YUV444, 5)));
            Picture odd = picture(16, 9, ChromaFormat.YUV422, 6);
            assertEquals(4, DvRaster.field(odd, 1).height());
        }
    }

    @Test
    @DisplayName("names raw DV files and carries DV video with 16-bit PCM only")
    void container() {
        assertEquals(OutputContainer.DV, OutputContainer.forFileName("clip.dv").orElseThrow());
        assertEquals(OutputContainer.DV, OutputContainer.forFileName("DIF").orElseThrow());
        assertTrue(OutputContainer.DV.supports(Codec.DV));
        assertTrue(OutputContainer.DV.supports(Codec.PCM_S16BE));
        assertFalse(OutputContainer.DV.supports(Codec.AAC));
        assertFalse(OutputContainer.DV.supports(Codec.H264));
        assertFalse(OutputContainer.DV.supportsTags());
        assertTrue(OutputContainer.DV.subtitleCodecs().isEmpty());
        assertEquals(List.of(Codec.DV), OutputContainer.DV.videoCodecs());
        assertEquals(List.of(Codec.PCM_S16LE), OutputContainer.DV.audioCodecs());
        try (var muxer = Containers.openMuxer(OutputContainer.DV, MediaSink.memory())) {
            assertTrue(muxer instanceof com.github.auties00.smalt.container.dv.DvMuxer);
        }
    }
}
