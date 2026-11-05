package com.github.auties00.smalt.video.mpeg2;

import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Encodes synthetic interlaced video with the ISO/IEC 13818-5 reference encoder ({@code smalt-mpeg2enc} of
 * {@code tools/testdata/mpeg2-oracle}), whose parameter file reaches the coding tools no other encoder on the runners
 * produces: field pictures, dual-prime prediction, 4:2:2 and 4:4:4 chroma, every intra DC precision, the scan, table
 * and quantizer scale choices per picture type, repeated fields and MPEG-1 coding.
 *
 * <p>The source frames move a textured pattern and a bar by different amounts in the two fields, so that field and
 * frame predictions differ. The parameter file is written relative to the working directory of the test JVM, as the
 * encoder reads its names with {@code sscanf("%s")}.
 */
final class ReferenceEncoder {
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    /** The parameters of one encode, set by the tests that need other values. */
    static final class Params {
        String name;
        int width = 176;
        int height = 144;
        int frames = 8;
        int gop = 6;
        int distance = 3;
        boolean mpeg1;
        boolean fieldPictures;
        int aspect = 2;
        int frameRateCode = 3;
        double bitRate = 4_000_000;
        int vbv = 112;
        boolean lowDelay;
        int profile = 4;
        int level = 8;
        boolean progressiveSequence;
        int chroma = 1;
        int dcPrecision;
        boolean topFirst = true;
        int[] framePredFrameDct = {0, 0, 0};
        int[] qScaleType = {1, 1, 1};
        int[] intraVlc = {1, 0, 0};
        int[] alternateScan = {0, 0, 0};
        boolean repeatFirst;
        boolean progressiveFrame;
        int[] intraMatrix;
        int[] interMatrix;

        Params(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private ReferenceEncoder() {
    }

    /** Encodes the frames of the parameters and returns the elementary stream. */
    static byte[] encode(Params p) {
        Oracle.assume(Oracle.Tool.MPEG2ENC);
        var directory = Scratch.directory("mpeg2-encoder").resolve(ProcessHandle.current().pid() + "-"
                + SEQUENCE.incrementAndGet());
        try {
            Files.createDirectories(directory);
            var chromaWidth = p.chroma == 3 ? p.width : p.width / 2;
            var chromaHeight = p.chroma == 1 ? p.height / 2 : p.height;
            for (var frame = 0; frame < p.frames; frame++) {
                var base = directory.resolve("f" + frame);
                Files.write(Path.of(base + ".Y"), plane(p.width, p.height, frame, 0, 1, 1));
                Files.write(Path.of(base + ".U"), plane(chromaWidth, chromaHeight, frame, 1, p.width / chromaWidth,
                        p.height / chromaHeight));
                Files.write(Path.of(base + ".V"), plane(chromaWidth, chromaHeight, frame, 2, p.width / chromaWidth,
                        p.height / chromaHeight));
            }
            var intra = "-";
            var inter = "-";
            if (p.intraMatrix != null) {
                intra = write(directory.resolve("intra.mat"), p.intraMatrix);
            }
            if (p.interMatrix != null) {
                inter = write(directory.resolve("inter.mat"), p.interMatrix);
            }
            var par = parameters(p, directory.resolve("f%d").toString().replace('\\', '/'), intra, inter,
                    directory.resolve("stat.out").toString().replace('\\', '/'));
            var parFile = directory.resolve("encode.par");
            Files.writeString(parFile, par, StandardCharsets.US_ASCII);
            var output = directory.resolve("out.m2v");
            Oracle.run(Oracle.Tool.MPEG2ENC, Duration.ofMinutes(5), List.of(parFile.toString(), output.toString()));
            var stream = Files.readAllBytes(output);
            try (var files = Files.list(directory)) {
                for (var file : files.toList()) {
                    Files.deleteIfExists(file);
                }
            }
            Files.deleteIfExists(directory);
            return stream;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String write(Path file, int[] matrix) throws IOException {
        var text = new StringBuilder();
        for (var row = 0; row < 8; row++) {
            for (var column = 0; column < 8; column++) {
                text.append(matrix[row * 8 + column]).append(column == 7 ? "\n" : " ");
            }
        }
        Files.writeString(file, text, StandardCharsets.US_ASCII);
        return file.toString().replace('\\', '/');
    }

    /**
     * Returns a plane of a source frame: a texture of two sine gratings drifting right by 2 samples a frame and a
     * bright bar moving down by 3 rows a frame, the bottom field rows one half frame later than the top field rows.
     */
    static byte[] plane(int width, int height, int frame, int component, int stepX, int stepY) {
        var plane = new byte[width * height];
        for (var y = 0; y < height; y++) {
            var fullY = y * stepY;
            var time = frame * 2 + ((fullY & 1) == 0 ? 0 : 1);
            for (var x = 0; x < width; x++) {
                var fullX = x * stepX;
                double value;
                if (component == 0) {
                    value = 128 + 50 * StrictMath.sin((fullX + time * 2) / 6.0) * StrictMath.cos(fullY / 9.0)
                            + 30 * StrictMath.sin((fullX - fullY + time) / 13.0);
                    var bar = (time * 3 / 2) % height;
                    if (Math.abs(fullY - bar) < 6 && fullX > width * stepX / 4 && fullX < width * stepX * 3 / 4) {
                        value = 230;
                    }
                } else {
                    var phase = component == 1 ? 0.0 : 1.7;
                    value = 128 + 40 * StrictMath.sin((fullX + time) / 17.0 + phase)
                            + 20 * StrictMath.cos((fullY - time) / 11.0);
                }
                plane[y * width + x] = (byte) Math.clamp(Math.round(value), 0, 255);
            }
        }
        return plane;
    }

    private static String parameters(Params p, String source, String intra, String inter, String stat) {
        var b = new StringBuilder();
        b.append("Smalt test sequence ").append(p.name).append('\n');
        b.append(source).append('\n');
        b.append("-\n");
        b.append(intra).append('\n');
        b.append(inter).append('\n');
        b.append(stat).append('\n');
        b.append("0\n");
        b.append(p.frames).append('\n');
        b.append("0\n");
        b.append("00:00:00:00\n");
        b.append(p.gop).append('\n');
        b.append(p.distance).append('\n');
        b.append(p.mpeg1 ? 1 : 0).append('\n');
        b.append(p.fieldPictures ? 1 : 0).append('\n');
        b.append(p.width).append('\n');
        b.append(p.height).append('\n');
        b.append(p.aspect).append('\n');
        b.append(p.frameRateCode).append('\n');
        b.append(p.bitRate).append('\n');
        b.append(p.vbv).append('\n');
        b.append(p.lowDelay ? 1 : 0).append('\n');
        b.append("0\n");
        b.append(p.profile).append('\n');
        b.append(p.level).append('\n');
        b.append(p.progressiveSequence ? 1 : 0).append('\n');
        b.append(p.chroma).append('\n');
        b.append("2\n");
        b.append("5\n");
        b.append("5\n");
        b.append("4\n");
        b.append(p.width).append('\n');
        b.append(p.height).append('\n');
        b.append(p.dcPrecision).append('\n');
        b.append(p.topFirst ? 1 : 0).append('\n');
        b.append(triple(p.framePredFrameDct));
        b.append("0 0 0\n");
        b.append(triple(p.qScaleType));
        b.append(triple(p.intraVlc));
        b.append(triple(p.alternateScan));
        b.append(p.repeatFirst ? 1 : 0).append('\n');
        b.append(p.progressiveFrame ? 1 : 0).append('\n');
        b.append("0\n");
        for (var i = 0; i < 8; i++) {
            b.append("0\n");
        }
        // f codes and search windows: P, then the forward and backward vectors of each B-picture
        b.append("2 2 11 11\n");
        for (var i = 1; i < p.distance; i++) {
            b.append("1 1 7 7\n");
            b.append("1 1 7 7\n");
        }
        return b.toString();
    }

    private static String triple(int[] values) {
        return values[0] + " " + values[1] + " " + values[2] + "\n";
    }
}
