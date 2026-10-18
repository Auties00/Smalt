package com.github.auties00.smalt.video.hevc;

import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Encodes synthetic scenes with x265 (through ffmpeg) across the coding tools, picture sizes, chroma
 * formats (including the 4:2:2, 4:4:4 and monochrome range extension profiles) and bit depths an
 * HEVC encoder uses in practice, and requires the decoder's output to equal ffmpeg's
 * decode byte for byte, with the decoded picture hash SEI messages x265 writes verified as well (the
 * one case where ffmpeg 7.1 departs from the HM reference decoder relies on the hashes alone).
 */
@DisplayName("HEVC decoder against ffmpeg on x265 streams")
class HevcDifferentialTest {
    record Case(String name, String size, String pixelFormat, String params, double seconds, String scene,
                boolean ffmpegExact) {
        Case(String name, String size, String pixelFormat, String params, double seconds, String scene) {
            this(name, size, pixelFormat, params, seconds, scene, true);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Case> cases() {
        var moving = "testsrc2";
        var natural = "mandelbrot";
        return Stream.of(
                new Case("medium preset", "176x144", "yuv420p", "", 1.0, moving),
                new Case("slow preset with RDOQ", "176x144", "yuv420p", "preset=slow", 0.6, natural),
                new Case("odd size cropped", "202x118", "yuv420p", "", 0.6, moving),
                new Case("tiny picture", "16x16", "yuv420p", "", 0.4, moving),
                new Case("main10", "176x144", "yuv420p10le", "", 0.8, natural),
                new Case("main12", "96x64", "yuv420p12le", "", 0.4, moving),
                new Case("weighted prediction", "128x96", "yuv420p", "weightp=1:weightb=1", 1.0, "fade"),
                new Case("transform skip", "128x96", "yuv420p", "tskip=1:rdoq-level=2", 0.4, moving),
                new Case("lossless", "64x64", "yuv420p", "lossless=1", 0.3, moving),
                new Case("four slices", "192x128", "yuv420p", "slices=4", 0.5, moving),
                new Case("no wavefront", "192x128", "yuv420p", "wpp=0", 0.5, natural),
                // ffmpeg 7.1 deblocks the chroma of 16x16 coding tree blocks differently from the HM reference
                // decoder in some streams (the x265 of the 2025 static builds makes one here); this decoder agrees
                // with every picture hash x265 writes, which hashVerification checks
                new Case("16x16 coding tree blocks", "160x96", "yuv420p", "ctu=16:min-cu-size=8:max-tu-size=16", 0.5, moving,
                        false),
                new Case("32x32 coding tree blocks with deep transform trees", "160x96", "yuv420p",
                        "ctu=32:tu-intra-depth=4:tu-inter-depth=4:max-tu-size=32", 0.5, natural),
                new Case("adaptive quantization groups", "192x128", "yuv420p", "aq-mode=3:qg-size=8", 0.5, natural),
                new Case("intra only", "128x96", "yuv420p", "keyint=1:strong-intra-smoothing=1", 0.3, natural),
                new Case("constrained intra", "128x96", "yuv420p", "constrained-intra=1", 0.6, moving),
                new Case("deep pyramid with open GOP", "128x96", "yuv420p",
                        "bframes=8:b-pyramid=1:ref=5:keyint=12:open-gop=1", 1.6, moving),
                new Case("no deblocking or SAO", "128x96", "yuv420p", "no-deblock=1:no-sao=1", 0.4, moving),
                new Case("deblocking offsets", "128x96", "yuv420p", "deblock=-3,2", 0.4, natural),
                new Case("low delay P", "128x96", "yuv420p", "bframes=0:ref=3", 0.6, moving),
                new Case("AMP and rectangular partitions", "160x96", "yuv420p", "amp=1:rect=1:preset=slower", 0.4, natural),
                new Case("parallel merge level", "128x96", "yuv420p", "max-merge=5:me=star", 0.4, moving),
                new Case("long-term references", "128x96", "yuv420p", "bframes=0:ref=1:keyint=60", 0.8, moving),
                new Case("main 4:2:2", "176x144", "yuv422p", "", 0.8, moving),
                new Case("main 4:2:2 10", "176x144", "yuv422p10le", "", 0.8, natural),
                new Case("main 4:2:2 12", "96x64", "yuv422p12le", "", 0.6, moving),
                new Case("main 4:4:4", "176x144", "yuv444p", "", 0.8, moving),
                new Case("main 4:4:4 10", "176x144", "yuv444p10le", "", 0.8, natural),
                new Case("main 4:4:4 12", "96x64", "yuv444p12le", "", 0.6, moving),
                new Case("monochrome", "128x96", "gray", "", 0.6, moving),
                new Case("monochrome 10", "128x96", "gray10le", "", 0.6, natural),
                new Case("monochrome 12", "96x64", "gray12le", "", 0.4, moving),
                new Case("4:2:2 odd size cropped", "202x118", "yuv422p10le", "", 0.6, moving),
                new Case("4:4:4 odd size cropped", "202x118", "yuv444p", "", 0.6, moving),
                new Case("4:2:2 intra with deep transform trees", "160x96", "yuv422p10le",
                        "keyint=1:tu-intra-depth=4:max-tu-size=32:strong-intra-smoothing=1", 0.3, natural),
                new Case("4:4:4 intra with deep transform trees", "160x96", "yuv444p10le",
                        "keyint=1:tu-intra-depth=4:max-tu-size=32", 0.3, natural),
                new Case("4:2:2 weighted prediction", "128x96", "yuv422p10le", "weightp=1:weightb=1", 1.0, "fade"),
                new Case("4:4:4 weighted prediction", "128x96", "yuv444p", "weightp=1:weightb=1", 1.0, "fade"),
                new Case("4:2:2 transform skip", "128x96", "yuv422p", "tskip=1:rdoq-level=2", 0.4, moving),
                new Case("4:4:4 transform skip", "128x96", "yuv444p12le", "tskip=1:rdoq-level=2", 0.4, moving),
                new Case("4:2:2 lossless", "64x64", "yuv422p10le", "lossless=1", 0.3, moving),
                new Case("4:4:4 lossless", "64x64", "yuv444p", "lossless=1", 0.3, moving),
                new Case("4:2:2 chroma QP offsets", "128x96", "yuv422p10le", "cbqpoffs=-4:crqpoffs=3", 0.5, natural),
                new Case("4:4:4 chroma QP offsets with deblocking offsets", "128x96", "yuv444p",
                        "cbqpoffs=5:crqpoffs=-6:deblock=2,-2", 0.5, natural),
                // ffmpeg 7.1 deblocks 4:2:2 chroma differently from the HM reference decoder here, which
                // agrees with this decoder and with every picture hash x265 writes
                new Case("4:2:2 16x16 coding tree blocks", "160x96", "yuv422p", "ctu=16:min-cu-size=8:max-tu-size=16",
                        0.5, moving, false),
                new Case("4:4:4 AMP and rectangular partitions", "160x96", "yuv444p10le",
                        "amp=1:rect=1:preset=slower", 0.4, natural),
                new Case("4:2:2 adaptive quantization groups", "192x128", "yuv422p", "aq-mode=3:qg-size=8", 0.5,
                        natural),
                new Case("4:4:4 constrained intra and PCM", "128x96", "yuv444p", "constrained-intra=1:pcm=1", 0.6,
                        moving)
        );
    }

    private static List<String> input(Case c) {
        var scene = switch (c.scene()) {
            case "fade" -> "testsrc2=size=" + c.size() + ":rate=25,fade=in:0:12,fade=out:13:12";
            case "mandelbrot" -> "mandelbrot=size=" + c.size() + ":rate=25";
            default -> "testsrc2=size=" + c.size() + ":rate=25";
        };
        return List.of("-f", "lavfi", "-i", scene);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("decodes x265 output exactly like ffmpeg")
    void matchesFfmpeg(Case c) {
        var params = "log-level=error:hash=1" + (c.params().isEmpty() ? "" : ":" + c.params());
        var output = new ArrayList<>(List.of("-t", Double.toString(c.seconds()), "-pix_fmt", c.pixelFormat(),
                "-c:v", "libx265", "-x265-params", params, "-f", "hevc"));
        var file = Fixtures.ffmpeg("hevc", input(c), output);
        var expected = Oracle.decodeVideo(file, c.pixelFormat(), 0);
        var raw = new ByteArrayOutputStream();
        var count = new int[1];
        var options = HevcDecoderOptions.builder().highBitDepth(true).hashVerification(true).build();
        try (var decoder = new HevcDecoder(HevcTestStreams.track(), options)) {
            java.util.function.Consumer<VideoFrame> sink = frame -> {
                count[0]++;
                raw.writeBytes(HevcTestStreams.raw(frame.picture()));
            };
            for (var nal : HevcTestStreams.splitAnnexB(Fixtures.read(file))) {
                decoder.decode(HevcTestStreams.packet(nal), sink);
            }
            decoder.flush(sink);
        }
        assertEquals(expected.length, raw.size(), "decoded size of " + c.name() + " after " + count[0] + " frames");
        if (c.ffmpegExact()) {
            assertArrayEquals(expected, raw.toByteArray(), c.name());
        }
    }
}
