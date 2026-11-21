package com.github.auties00.smalt.video.intra;

import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.dnxhd.DnxhdEncoder;
import com.github.auties00.smalt.video.dnxhd.DnxhdEncoderOptions;
import com.github.auties00.smalt.video.dnxhd.DnxhdProfile;
import com.github.auties00.smalt.video.prores.ProResEncoder;
import com.github.auties00.smalt.video.prores.ProResEncoderOptions;
import com.github.auties00.smalt.video.prores.ProResProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares the ProRes and DNxHR encoders with ffmpeg's prores_ks and dnxhd encoders on the same
 * natural frames (the Xiph {@code parkrun} sequence, or the first frames of it in the clip tier): both files
 * are decoded by ffmpeg and measured against the source, and Smalt must reach comparable quality
 * without spending more bits.
 */
@DisplayName("intra encoder quality against ffmpeg")
class IntraQualityTest {
    static final int WIDTH = 1280;
    static final int HEIGHT = 720;
    static final int FRAMES = 3;

    static byte[] sourceRaw(String format) {
        // The comparison is calibrated on natural frames: synthetic scenes such as mandelbrot favor neither encoder
        var parkrun = Path.of(Conformance.directory().toString(), "720p50_parkrun_ter.y4m");
        var clip = Path.of(System.getProperty("smalt.test.clips", Path.of(System.getProperty("user.home"), ".smalt",
                "cache", "clips").toString()), "parkrun720.yuv");
        List<String> input;
        if (Files.isRegularFile(parkrun)) {
            input = List.of("-i", parkrun.toString());
        } else {
            Skip.unless(Files.isRegularFile(clip), "data:clip", "neither 720p50_parkrun_ter.y4m nor the parkrun720.yuv"
                    + " clip is cached (python tools/testdata/testdata.py fetch --tier full)");
            input = List.of("-f", "rawvideo", "-pix_fmt", "yuv420p", "-s", WIDTH + "x" + HEIGHT, "-r", "50", "-i",
                    clip.toString());
        }
        var arguments = new ArrayList<>(List.of("-v", "error"));
        arguments.addAll(input);
        arguments.addAll(List.of("-frames:v", Integer.toString(FRAMES), "-f", "rawvideo", "-pix_fmt", format, "-"));
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), arguments);
    }

    static List<Picture> pictures(byte[] raw, String format) {
        var wide = format.endsWith("le");
        var pictures = new ArrayList<Picture>();
        var frameSize = WIDTH * HEIGHT * 2 * (wide ? 2 : 1);
        for (var f = 0; f < FRAMES; f++) {
            var frame = java.util.Arrays.copyOfRange(raw, f * frameSize, (f + 1) * frameSize);
            if (wide) {
                var y = new short[WIDTH * HEIGHT];
                var u = new short[WIDTH * HEIGHT / 2];
                var v = new short[WIDTH * HEIGHT / 2];
                var buffer = java.nio.ByteBuffer.wrap(frame).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                buffer.get(y).get(u).get(v);
                pictures.add(com.github.auties00.smalt.image.Yuv16Picture.builder().size(WIDTH, HEIGHT)
                        .chromaFormat(com.github.auties00.smalt.image.ChromaFormat.YUV422).bitDepth(10)
                        .color(com.github.auties00.smalt.image.ColorInfo.BT709_LIMITED).luma(y, WIDTH)
                        .chroma(u, v, WIDTH / 2).build());
            } else {
                pictures.add(com.github.auties00.smalt.image.YuvPicture.builder().size(WIDTH, HEIGHT)
                        .chromaFormat(com.github.auties00.smalt.image.ChromaFormat.YUV422)
                        .color(com.github.auties00.smalt.image.ColorInfo.BT709_LIMITED)
                        .luma(java.util.Arrays.copyOfRange(frame, 0, WIDTH * HEIGHT), WIDTH)
                        .chroma(java.util.Arrays.copyOfRange(frame, WIDTH * HEIGHT, WIDTH * HEIGHT * 3 / 2),
                                java.util.Arrays.copyOfRange(frame, WIDTH * HEIGHT * 3 / 2, WIDTH * HEIGHT * 2), WIDTH / 2)
                        .build());
            }
        }
        return pictures;
    }

    static Path smalt(VideoEncoder encoder, Codec codec, List<Picture> pictures, Path file) {
        try (var muxer = new Mp4Muxer(MediaSink.of(file), Mp4MuxerOptions.quickTime())) {
            muxer.addTrack(VideoTrack.builder().id(1).codec(codec).timeBase(encoder.timeBase())
                    .codecConfig(encoder.codecConfig().orElseThrow()).width(WIDTH).height(HEIGHT)
                    .frameRate(new Rational(25, 1)).build());
            var pts = 0L;
            for (var picture : pictures) {
                encoder.encode(new VideoFrame(picture, pts, 3600, true), packet -> muxer.writePacket(packet.withTrackId(1)));
                pts += 3600;
            }
            muxer.finish();
        }
        return file;
    }

    static Path ffmpeg(byte[] raw, String format, List<String> codec, Path file) {
        var arguments = new ArrayList<>(List.of("-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", format, "-s",
                WIDTH + "x" + HEIGHT, "-r", "25", "-i", "-"));
        arguments.addAll(codec);
        arguments.add(file.toString());
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), raw, arguments);
        return file;
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"PRORES_STANDARD, prores_ks:2, yuv422p10le", "PRORES_HQ, prores_ks:3, yuv422p10le",
            "PRORES_PROXY, prores_ks:0, yuv422p10le", "DNXHR_HQ, dnxhd:dnxhr_hq, yuv422p", "DNXHR_SQ, dnxhd:dnxhr_sq, yuv422p"})
    @DisplayName("matches ffmpeg's quality at no more bits")
    void quality(String flavor, String reference, String format, @TempDir Path directory) throws Exception {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var raw = sourceRaw(format);
        var pictures = pictures(raw, format);
        var parts = reference.split(":");
        VideoEncoder encoder = flavor.startsWith("PRORES")
                ? new ProResEncoder(WIDTH, HEIGHT, ProResEncoderOptions.of(ProResProfile.valueOf(flavor.substring(7)))
                .toBuilder()
                .frameRate(new Rational(25, 1))
                .build())
                : new DnxhdEncoder(WIDTH, HEIGHT, DnxhdEncoderOptions.of(DnxhdProfile.valueOf(flavor)).toBuilder()
                        .frameRate(new Rational(25, 1)).build());
        var ours = smalt(encoder, flavor.startsWith("PRORES") ? Codec.PRORES : Codec.DNXHD, pictures, directory.resolve("ours.mov"));
        var theirs = ffmpeg(raw, format, List.of("-c:v", parts[0], "-profile:v", parts[1]), directory.resolve("theirs.mov"));
        var peak = format.endsWith("le") ? 1023 : 255;
        var wide = format.endsWith("le");
        var oursPsnr = IntraTesting.psnr(raw, IntraTesting.ffmpegDecode(ours, format, 0), wide, peak);
        var theirsPsnr = IntraTesting.psnr(raw, IntraTesting.ffmpegDecode(theirs, format, 0), wide, peak);
        var oursSize = Files.size(ours);
        var theirsSize = Files.size(theirs);
        System.out.printf("%s: Smalt %.2f dB in %d bytes, ffmpeg %.2f dB in %d bytes%n", flavor, oursPsnr, oursSize,
                theirsPsnr, theirsSize);
        assertTrue(oursSize <= theirsSize * 1.15, "Smalt spends " + oursSize + " bytes, ffmpeg " + theirsSize);
        assertTrue(oursPsnr >= theirsPsnr - 1.5, "Smalt " + oursPsnr + " dB, ffmpeg " + theirsPsnr);
    }
}
