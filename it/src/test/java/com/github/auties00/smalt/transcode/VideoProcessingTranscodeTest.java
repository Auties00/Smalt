package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.color.ColorTransformOptions;
import com.github.auties00.smalt.image.color.ToneMapping;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.WatermarkOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleFont;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import com.github.auties00.smalt.subtitle.SubtitleStyle;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the video processing of a transcode end to end, through Smalt's encoders and ffmpeg and ffprobe as
 * black-box oracles: HDR10 (HEVC, and AV1 made by Smalt) to SDR H.264 against {@link ImageOps#toSrgb} of the
 * decoded source, SDR to PQ AV1 and back, deinterlacing of field-coded H.264 measured by a combing count,
 * telecine and inverse telecine (Smalt's and ffmpeg's {@code telecine} filter) by frame counts, timestamps and
 * PSNR, frame blending, subtitle burn-in and the watermark.
 */
@DisplayName("Video processing in transcodes")
class VideoProcessingTranscodeTest {
    static final ColorInfo HDR10 = new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED,
            ColorPrimaries.BT2020, TransferCharacteristics.PQ);

    static Path transcode(Path input, Path output, VideoTranscodeOptions video) {
        try (var in = MediaSource.of(input); var out = MediaSink.of(output)) {
            Transcoder.transcode(in, out, TranscodeOptions.of(OutputContainer.MP4).toBuilder().video(video)
                    .subtitles(SubtitleTranscodeOptions.drop()).build());
        }
        return output;
    }

    static VideoTranscodeOptions.Builder h264() {
        return VideoTranscodeOptions.encode(Codec.H264).toBuilder().bitrate(8_000_000);
    }

    /** Decodes every frame with Smalt at full depth and maps it to 8-bit RGBA. */
    static List<RgbaPicture> frames(Path file, Function<com.github.auties00.smalt.image.Picture, RgbaPicture> map) {
        var frames = new ArrayList<RgbaPicture>();
        try (var reader = VideoReader.open(MediaSource.of(file), VideoDecoderOptions.builder().highBitDepth(true)
                .build())) {
            for (var frame = reader.read(); frame != null; frame = reader.read()) {
                frames.add(map.apply(frame.picture()));
            }
        }
        return frames;
    }

    static double psnr(RgbaPicture a, RgbaPicture b) {
        assertEquals(a.width(), b.width());
        assertEquals(a.height(), b.height());
        var sum = 0.0;
        var count = 0;
        for (var y = 0; y < a.height(); y++) {
            for (var x = 0; x < a.width(); x++) {
                for (var channel = 0; channel < 3; channel++) {
                    var d = (a.pixels()[y * a.stride() + 4 * x + channel] & 0xFF)
                            - (b.pixels()[y * b.stride() + 4 * x + channel] & 0xFF);
                    sum += d * d;
                    count++;
                }
            }
        }
        return sum == 0 ? 99 : 10 * StrictMath.log10(255.0 * 255 * count / sum);
    }

    /** The mean of each RGB channel of a picture, for failure messages. */
    static String means(RgbaPicture picture) {
        var sums = new long[3];
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width(); x++) {
                for (var channel = 0; channel < 3; channel++) {
                    sums[channel] += picture.pixels()[y * picture.stride() + 4 * x + channel] & 0xFF;
                }
            }
        }
        var count = (double) picture.width() * picture.height();
        return String.format("rgb(%.1f, %.1f, %.1f)", sums[0] / count, sums[1] / count, sums[2] / count);
    }

    /** The picture as the encoder's 4:2:0 BT.709 Y'CbCr renders it, which the comparisons hold the output to. */
    static RgbaPicture subsampled(RgbaPicture picture) {
        return ImageOps.toRgba(ImageOps.toYuv(picture, com.github.auties00.smalt.image.ChromaFormat.YUV420,
                ColorInfo.BT709_LIMITED));
    }

    /** The picture at full chroma resolution, each chroma sample replicated as the color conversion does. */
    static com.github.auties00.smalt.image.Picture replicated(com.github.auties00.smalt.image.Picture picture) {
        var color = ((com.github.auties00.smalt.image.Yuv16Picture) picture).color();
        return ImageOps.toYuv16(ImageOps.toRgba16(picture, com.github.auties00.smalt.image.ops.ChromaUpsampling.NEAREST),
                com.github.auties00.smalt.image.ChromaFormat.YUV444, color, 16);
    }

    static String stream(Path file, String entries) {
        Oracle.assume(Oracle.Tool.FFPROBE);
        return new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error",
                "-select_streams", "v:0", "-count_frames", "-show_entries", "stream=" + entries, "-of",
                "default=noprint_wrappers=1", file.toString())), StandardCharsets.UTF_8);
    }

    static String value(String probe, String key) {
        var matcher = Pattern.compile("(?m)^" + key + "=(.*)$").matcher(probe);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    /** The share of luma samples combed: between a row above and below that both differ by more than 20 alike. */
    static double combed(byte[] gray, int width, int height, int frames) {
        long count = 0;
        long total = 0;
        for (var frame = 0; frame < frames; frame++) {
            var base = frame * width * height;
            for (var y = 1; y < height - 1; y++) {
                for (var x = 0; x < width; x++) {
                    var m = gray[base + y * width + x] & 0xFF;
                    var u = gray[base + (y - 1) * width + x] & 0xFF;
                    var d = gray[base + (y + 1) * width + x] & 0xFF;
                    if (m - u > 20 && m - d > 20 || u - m > 20 && d - m > 20) {
                        count++;
                    }
                    total++;
                }
            }
        }
        return (double) count / total;
    }

    @Nested
    @DisplayName("color")
    class Color {
        static Path hdr10Hevc() {
            Oracle.assumeEncoder("libx265");
            return Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i", "testsrc2=size=160x96:rate=10:duration=1"),
                    List.of("-vf", "format=yuv420p10le", "-c:v", "libx265", "-x265-params",
                            "log-level=error:hdr10=1:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:"
                                    + "max-cll=1000,400:master-display=G(13250,34500)B(7500,3000)R(34000,16000)"
                                    + "WP(15635,16450)L(10000000,1)", "-color_primaries", "bt2020", "-color_trc",
                            "smpte2084", "-colorspace", "bt2020nc", "-tag:v", "hvc1"));
        }

        static void assertMatchesToSrgb(Path source, Path output, double minimum) {
            var expected = frames(source, picture -> subsampled(ImageOps.toSrgb(replicated(picture))));
            var actual = frames(output, ImageOps::toRgba);
            assertEquals(expected.size(), actual.size());
            for (var index = 0; index < expected.size(); index++) {
                var value = psnr(expected.get(index), actual.get(index));
                assertTrue(value >= minimum, "frame " + index + " PSNR " + value + ": expected "
                        + means(expected.get(index)) + ", actual " + means(actual.get(index)));
            }
        }

        @Test
        @DisplayName("tone-maps HDR10 HEVC to SDR H.264 as ImageOps.toSrgb renders the decoded frames")
        void hevcToSdr(@TempDir Path directory) {
            var source = hdr10Hevc();
            var output = transcode(source, directory.resolve("sdr.mp4"), h264().color(ColorInfo.BT709_LIMITED).build());
            assertMatchesToSrgb(source, output, 34);
            var probe = stream(output, "pix_fmt,color_transfer,color_primaries");
            assertEquals("yuv420p", value(probe, "pix_fmt"));
            assertEquals("bt709", value(probe, "color_transfer"));
            assertEquals("bt709", value(probe, "color_primaries"));
        }

        @Test
        @DisplayName("keeps HDR10 when re-encoding without a color, at 10 bits, then tone-maps that AV1")
        void av1(@TempDir Path directory) {
            var source = hdr10Hevc();
            var av1 = transcode(source, directory.resolve("hdr.mp4"), VideoTranscodeOptions.encode(Codec.AV1)
                    .toBuilder().bitrate(4_000_000).build());
            var probe = stream(av1, "pix_fmt,color_transfer,color_primaries");
            assertEquals("yuv420p10le", value(probe, "pix_fmt"));
            assertEquals("smpte2084", value(probe, "color_transfer"));
            assertEquals("bt2020", value(probe, "color_primaries"));
            var sdr = transcode(av1, directory.resolve("sdr.mp4"), h264().color(ColorInfo.BT709_LIMITED).build());
            assertMatchesToSrgb(av1, sdr, 34);
        }

        @Test
        @DisplayName("converts SDR to PQ AV1 at 10 bits, which clipping back restores")
        void sdrToPq(@TempDir Path directory) {
            var source = Fixtures.testVideo("mp4", 160, 96, 1, List.of("-c:v", "libx264", "-qp", "0", "-pix_fmt",
                    "yuv420p"));
            var pq = transcode(source, directory.resolve("pq.mp4"), VideoTranscodeOptions.encode(Codec.AV1).toBuilder()
                    .bitrate(6_000_000).color(HDR10).build());
            var probe = stream(pq, "pix_fmt,color_transfer,color_primaries,color_space");
            assertEquals("yuv420p10le", value(probe, "pix_fmt"));
            assertEquals("smpte2084", value(probe, "color_transfer"));
            assertEquals("bt2020", value(probe, "color_primaries"));
            assertEquals("bt2020nc", value(probe, "color_space"));
            try (var reader = VideoReader.open(MediaSource.of(pq), VideoDecoderOptions.builder().highBitDepth(true)
                    .build())) {
                var picture = reader.read().picture();
                assertEquals(HDR10, ((com.github.auties00.smalt.image.Yuv16Picture) picture).color()
                        .withChromaSiting(HDR10.chromaSiting()), "the color AV1 decodes");
            }
            ColorInfo sourceColor;
            try (var reader = VideoReader.open(MediaSource.of(source))) {
                sourceColor = ((com.github.auties00.smalt.image.YuvPicture) reader.read().picture()).color();
            }
            // back to the source's own primaries and matrix, so that only the transfer round trip remains
            var back = transcode(pq, directory.resolve("back.mp4"), h264().color(sourceColor)
                    .colorConversion(ColorTransformOptions.builder().toneMapping(ToneMapping.CLIP).build()).build());
            // the same two encodes without the color conversion give the loss of the codecs alone
            var sdr = transcode(source, directory.resolve("sdr.mp4"), VideoTranscodeOptions.encode(Codec.AV1).toBuilder()
                    .bitrate(6_000_000).build());
            var control = frames(transcode(sdr, directory.resolve("control.mp4"), h264().build()), ImageOps::toRgba);
            var expected = frames(source, ImageOps::toRgba);
            var actual = frames(back, ImageOps::toRgba);
            var hdr = frames(pq, ImageOps::toSrgb);
            assertEquals(expected.size(), actual.size());
            for (var index = 0; index < expected.size(); index++) {
                var value = psnr(expected.get(index), actual.get(index));
                var codecs = psnr(expected.get(index), control.get(index));
                // PQ at 10 bits and the two conversions cost a few decibels over the codecs alone
                assertTrue(value >= Math.min(33, codecs - 3), "frame " + index + " PSNR " + value + " against " + codecs
                        + " without conversion: source " + means(expected.get(index)) + ", PQ shown as sRGB "
                        + means(hdr.get(index)) + ", back " + means(actual.get(index)) + ", control "
                        + means(control.get(index)));
            }
        }
    }

    @Nested
    @DisplayName("deinterlacing")
    class Deinterlacing {
        /** 25 interlaced frames a second of moving content, coded as interlaced H.264, top field first. */
        static Path interlaced() {
            return Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=50:duration=1"),
                    List.of("-vf", "tinterlace=mode=interleave_top,setfield=tff", "-c:v", "libx264", "-flags",
                            "+ildct+ilme", "-x264-params", "tff=1:threads=1", "-qp", "4", "-pix_fmt", "yuv420p"));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Deinterlacer.class)
        @DisplayName("removes the combing of interlaced frames at the frame and the field rate")
        void combing(Deinterlacer method, @TempDir Path directory) {
            var source = interlaced();
            var woven = transcode(source, directory.resolve("woven.mp4"), h264().build());
            var progressive = transcode(source, directory.resolve("frame.mp4"), h264().deinterlacer(method).build());
            var doubled = transcode(source, directory.resolve("field.mp4"), h264().deinterlacer(method).fieldRate(true)
                    .build());
            var before = combed(Oracle.decodeVideo(woven, "gray", 0), 176, 144, 25);
            var after = combed(Oracle.decodeVideo(progressive, "gray", 0), 176, 144, 25);
            assertTrue(before > 0.01, "the source is combed: " + before);
            // the residue is the horizontal detail of the pattern, which a progressive frame has too
            assertTrue(after < before / 3, method + ": " + after + " of " + before);
            var probe = stream(doubled, "nb_read_frames,r_frame_rate,field_order");
            assertEquals("50", value(probe, "nb_read_frames"));
            assertEquals("50/1", value(probe, "r_frame_rate"));
            assertTrue(combed(Oracle.decodeVideo(doubled, "gray", 0), 176, 144, 50) < before / 3);
            assertEquals("25", value(stream(progressive, "nb_read_frames"), "nb_read_frames"));
        }

        @Test
        @DisplayName("copies a progressive stream even with a deinterlacer set")
        void progressiveCopy(@TempDir Path directory) {
            var source = Fixtures.ffmpeg("mkv", List.of("-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10:duration=1"),
                    List.of("-c:v", "libx264", "-field_order", "progressive"));
            try (var in = MediaSource.of(source); var out = MediaSink.of(directory.resolve("copy.mp4"))) {
                var result = Transcoder.transcode(in, out, TranscodeOptions.of(OutputContainer.MP4).toBuilder()
                        .video(VideoTranscodeOptions.defaults().toBuilder().deinterlacer(Deinterlacer.ELA).build())
                        .build());
                assertTrue(result.streams().getFirst().copied(), "a declared progressive stream is copied");
            }
        }
    }

    @Nested
    @DisplayName("frame rates and pulldown")
    class Pulldowns {
        static Path film() {
            return Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=24000/1001:duration=2"),
                    List.of("-c:v", "libx264", "-qp", "2", "-pix_fmt", "yuv420p", "-x264-params", "threads=1"));
        }

        @Test
        @DisplayName("telecines 24000/1001 to 30000/1001 and removes it again")
        void roundTrip(@TempDir Path directory) {
            var source = film();
            var telecined = transcode(source, directory.resolve("tc.mp4"), h264().bitrate(20_000_000)
                    .pulldown(Pulldown.TELECINE).build());
            var probe = stream(telecined, "nb_read_frames,r_frame_rate");
            var films = Integer.parseInt(value(stream(source, "nb_read_frames"), "nb_read_frames"));
            var fields = Integer.parseInt(value(probe, "nb_read_frames"));
            assertTrue(Math.abs(fields - films * 5 / 4.0) <= 1.25, fields + " frames from " + films);
            assertEquals("30000/1001", value(probe, "r_frame_rate"));
            var restored = transcode(telecined, directory.resolve("ivtc.mp4"), h264().bitrate(20_000_000)
                    .pulldown(Pulldown.INVERSE_TELECINE).build());
            var restoredProbe = stream(restored, "nb_read_frames,r_frame_rate");
            assertEquals("24000/1001", value(restoredProbe, "r_frame_rate"));
            var expected = frames(source, ImageOps::toRgba);
            var actual = frames(restored, ImageOps::toRgba);
            assertTrue(Math.abs(expected.size() - actual.size()) <= 1, expected.size() + " vs " + actual.size());
            var report = new StringBuilder();
            var bad = 0;
            for (var index = 0; index < Math.min(expected.size(), actual.size()); index++) {
                var value = psnr(expected.get(index), actual.get(index));
                report.append(String.format("%d:%.1f ", index, value));
                if (value < 30) {
                    bad++;
                }
            }
            assertEquals(0, bad, "film frames below 30 dB: " + report);
        }

        @Test
        @DisplayName("removes the pulldown of ffmpeg's telecine filter")
        void ffmpegTelecine(@TempDir Path directory) {
            Oracle.assumeFilter("telecine");
            var source = film();
            var telecined = Fixtures.ffmpeg("mp4", List.of("-i", source.toString()), List.of("-vf",
                    "telecine=first_field=top:pattern=23", "-c:v", "libx264", "-qp", "2", "-flags", "+ildct+ilme",
                    "-x264-params", "tff=1:threads=1", "-pix_fmt", "yuv420p"));
            var matchedOnly = frames(transcode(telecined, directory.resolve("matched.mp4"), h264().bitrate(20_000_000)
                    .pulldown(Pulldown.INVERSE_TELECINE).build()), ImageOps::toRgba);
            var restored = transcode(telecined, directory.resolve("ivtc.mp4"), h264().bitrate(20_000_000)
                    .pulldown(Pulldown.INVERSE_TELECINE).deinterlacer(Deinterlacer.ELA).build());
            var expected = frames(source, ImageOps::toRgba);
            var actual = frames(restored, ImageOps::toRgba);
            var withoutDeinterlacer = new StringBuilder();
            for (var index = 0; index < Math.min(expected.size(), matchedOnly.size()); index++) {
                withoutDeinterlacer.append(String.format("%.1f ", psnr(expected.get(index), matchedOnly.get(index))));
            }
            assertTrue(Math.abs(expected.size() - actual.size()) <= 1, expected.size() + " vs " + actual.size());
            var good = 0;
            var report = new StringBuilder();
            for (var index = 0; index < actual.size(); index++) {
                var best = 0.0;
                var bestAt = -1;
                for (var film = Math.max(0, index - 2); film < Math.min(expected.size(), index + 3); film++) {
                    var value = psnr(expected.get(film), actual.get(index));
                    if (value > best) {
                        best = value;
                        bestAt = film;
                    }
                }
                report.append(index).append("->").append(bestAt).append(String.format(":%.1f ", best));
                if (best >= 30) {
                    good++;
                }
            }
            assertTrue(good >= expected.size() - 3, good + " of " + expected.size() + " film frames restored: " + report
                    + "; matched without deinterlacing: " + withoutDeinterlacer);
        }

        @Test
        @DisplayName("blends frames when converting 24 to 30 frames per second")
        void blend(@TempDir Path directory) {
            var source = film();
            var repeated = transcode(source, directory.resolve("repeat.mp4"), h264().frameRate(new Rational(30, 1))
                    .build());
            var blended = transcode(source, directory.resolve("blend.mp4"), h264().frameRate(new Rational(30, 1))
                    .frameRateConversion(FrameRateConversion.BLEND).build());
            assertEquals(value(stream(repeated, "nb_read_frames"), "nb_read_frames"),
                    value(stream(blended, "nb_read_frames"), "nb_read_frames"));
            var a = frames(repeated, ImageOps::toRgba);
            var b = frames(blended, ImageOps::toRgba);
            var differing = 0;
            for (var index = 0; index < a.size(); index++) {
                if (psnr(a.get(index), b.get(index)) < 40) {
                    differing++;
                }
            }
            // ticks between film frames blend; the ticks that coincide with one stay that frame
            assertTrue(differing >= a.size() / 2, differing + " of " + a.size());
            assertTrue(psnr(a.getFirst(), b.getFirst()) >= 40, "the first tick shows the first frame");
        }
    }

    @Nested
    @DisplayName("overlays")
    class Overlays {
        static SubtitleFont font() {
            try (var stream = VideoProcessingTranscodeTest.class.getResourceAsStream(
                    "/com/github/auties00/smalt/subtitle/smalt-test.ttf")) {
                return SubtitleFont.of(stream.readAllBytes());
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        static Path withSubtitles(Path directory) throws IOException {
            var srt = directory.resolve("cues.srt");
            Files.writeString(srt, "1\n00:00:00,400 --> 00:00:00,800\n### ##\n");
            return Fixtures.ffmpeg("mkv", List.of("-f", "lavfi", "-i", "testsrc2=size=160x96:rate=10:duration=1.2",
                    "-i", srt.toString()), List.of("-map", "0", "-map", "1", "-c:v", "libx264", "-qp", "0", "-c:s",
                    "srt"));
        }

        @Test
        @DisplayName("burns a subtitle track in only while each cue is shown")
        void burnIn(@TempDir Path directory) throws IOException {
            var source = withSubtitles(directory);
            var plain = frames(transcode(source, directory.resolve("plain.mp4"), h264().build()), ImageOps::toRgba);
            var burned = frames(transcode(source, directory.resolve("burned.mp4"), h264().subtitleBurnIn(
                    SubtitleBurnIn.of(SubtitleStyle.builder(font()).size(0.15).build())).build()), ImageOps::toRgba);
            assertEquals(plain.size(), burned.size());
            for (var index = 0; index < plain.size(); index++) {
                var shown = index >= 4 && index < 8;
                var value = psnr(plain.get(index), burned.get(index));
                assertTrue(shown ? value < 30 : value > 40, "frame " + index + " PSNR " + value);
            }
            // the text is white and sits in the bottom quarter
            var frame = burned.get(5);
            var whiteBottom = 0;
            var whiteTop = 0;
            for (var y = 0; y < 96; y++) {
                for (var x = 0; x < 160; x++) {
                    var index = y * frame.stride() + 4 * x;
                    var white = (frame.pixels()[index] & 0xFF) > 235 && (frame.pixels()[index + 1] & 0xFF) > 235
                            && (frame.pixels()[index + 2] & 0xFF) > 235;
                    if (white && y >= 72) {
                        whiteBottom++;
                    } else if (white && y < 24) {
                        whiteTop++;
                    }
                }
            }
            assertTrue(whiteBottom > whiteTop + 100, whiteBottom + " vs " + whiteTop);
        }

        @Test
        @DisplayName("burns the cues of an input read once as their packets arrive")
        void burnInForwardOnly(@TempDir Path directory) throws IOException {
            var source = withSubtitles(directory);
            var plain = frames(transcode(source, directory.resolve("plain.mp4"), h264().build()), ImageOps::toRgba);
            var output = directory.resolve("burned.mp4");
            try (var stream = Files.newInputStream(source); var in = MediaSource.of(stream, Files.size(source));
                 var out = MediaSink.of(output)) {
                Transcoder.transcode(in, out, TranscodeOptions.of(OutputContainer.MP4).toBuilder().video(h264()
                        .subtitleBurnIn(SubtitleBurnIn.of(SubtitleStyle.builder(font()).size(0.15).build())).build())
                        .subtitles(SubtitleTranscodeOptions.drop()).build());
            }
            var burned = frames(output, ImageOps::toRgba);
            assertEquals(plain.size(), burned.size());
            for (var index = 0; index < plain.size(); index++) {
                if (index == 4) {
                    // the cue starts on this frame, whose packet may precede the cue's in the file
                    continue;
                }
                var shown = index > 4 && index < 8;
                var value = psnr(plain.get(index), burned.get(index));
                assertTrue(shown ? value < 30 : value > 40, "frame " + index + " PSNR " + value);
            }
        }

        @Test
        @DisplayName("burns given cues and places a watermark")
        void cuesAndWatermark(@TempDir Path directory) {
            var source = Fixtures.testVideo("mp4", 160, 96, 1, List.of("-c:v", "libx264", "-qp", "0"));
            var mark = RgbaPicture.allocate(24, 12, false);
            java.util.Arrays.fill(mark.pixels(), (byte) 255);
            var plain = frames(transcode(source, directory.resolve("plain.mp4"), h264().build()), ImageOps::toRgba);
            var marked = frames(transcode(source, directory.resolve("marked.mp4"), h264().subtitleBurnIn(SubtitleBurnIn
                    .builder(SubtitleStyle.builder(font()).alignment(8).build()).cues(List.of(SubtitleCue.of(0, 200_000,
                    "{\\an8}##")), SubtitleMarkup.SUBRIP).build()).watermark(mark, WatermarkOptions.builder()
                    .opacity(1).margins(4, 4).build()).build()), ImageOps::toRgba);
            for (var index = 0; index < plain.size(); index++) {
                var frame = marked.get(index);
                // the opaque white mark covers the bottom-right corner, 4 pixels in
                var corner = (96 - 4 - 6) * frame.stride() + 4 * (160 - 4 - 12);
                assertTrue((frame.pixels()[corner] & 0xFF) > 235, "mark on frame " + index);
                var top = 0;
                for (var y = 0; y < 30; y++) {
                    for (var x = 0; x < 160; x++) {
                        var at = y * frame.stride() + 4 * x;
                        var base = y * plain.get(index).stride() + 4 * x;
                        if (Math.abs((frame.pixels()[at + 1] & 0xFF) - (plain.get(index).pixels()[base + 1] & 0xFF)) > 40) {
                            top++;
                        }
                    }
                }
                assertEquals(index < 6, top > 20, "cue at the top of frame " + index + ": " + top);
            }
        }
    }
}
