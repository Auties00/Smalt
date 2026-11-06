package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the committed lossless JPEG variant vectors ({@code jpeg/lossless/variants}, pinned in
 * {@code variants.sha256}), which the I/O behavior harness and the fuzz seeds use: streams of the test encoder
 * (arithmetic coding, several scans, subsampling, five components), a five-component baseline DCT stream of the
 * test DCT writer, and a libjpeg-turbo 3.1.3 {@code cjpeg -lossless -scans} file. The output is deterministic.
 * Runs only with {@code -Dsmalt.jpeg.writeLosslessVectors=<directory>}.
 */
@EnabledIfSystemProperty(named = "smalt.jpeg.writeLosslessVectors", matches = ".+",
        disabledReason = "[opt-in:lossless-vectors] runs only with -Dsmalt.jpeg.writeLosslessVectors=<directory>")
class LosslessVectorWriter {
    @Test
    @DisplayName("writes the lossless JPEG variant vectors")
    void write() throws IOException, NoSuchAlgorithmException {
        var directory = Path.of(System.getProperty("smalt.jpeg.writeLosslessVectors"));
        Files.createDirectories(directory);
        var files = new LinkedHashMap<String, byte[]>();
        var subsampled = new int[][]{{2, 2}, {1, 1}, {1, 1}};
        files.put("37x29-ycbcr420-p8-sof11-psv4-r19.jpg", LosslessSubsampledTest.withJfif(Lj92Encoder.encodeFrame(
                LosslessVariantsTest.planes(37, 29, subsampled, 8, 1), 37, 29, subsampled, 8, true,
                List.of(Lj92Encoder.ScanSpec.of(new int[]{0, 1, 2}, 4, 0, 19)))));
        var horizontal = new int[][]{{2, 1}, {1, 1}, {1, 1}};
        files.put("37x29-ycbcr422-p12-3scans.jpg", LosslessSubsampledTest.withJfif(Lj92Encoder.encodeFrame(
                LosslessVariantsTest.planes(37, 29, horizontal, 12, 2), 37, 29, horizontal, 12, false,
                List.of(Lj92Encoder.ScanSpec.of(new int[]{0}, 7, 1, 37), Lj92Encoder.ScanSpec.of(new int[]{1}, 2, 0, 0),
                        Lj92Encoder.ScanSpec.of(new int[]{2}, 6, 0, 19)))));
        var five = LosslessVariantsTest.ones(5);
        files.put("23x17-5ch-p8-sof11-2scans.jpg", Lj92Encoder.encodeFrame(LosslessVariantsTest.planes(23, 17, five, 8, 3),
                23, 17, five, 8, true, List.of(Lj92Encoder.ScanSpec.of(new int[]{0, 1, 2, 3}, 1, 0, 0),
                        Lj92Encoder.ScanSpec.of(new int[]{4}, 7, 0, 0))));
        var ids = new int[5];
        var ones = new int[5];
        var planes = new byte[5][];
        for (var c = 0; c < 5; c++) {
            ids[c] = c + 1;
            ones[c] = 1;
            planes[c] = JpegStreamTest.plane(23, 17, c);
        }
        files.put("23x17-5ch-baseline.jpg", TestJpegWriter.fromPlanes(23, 17, ids, ones, ones, planes, 90)
                .sequential(new int[]{0, 1, 2, 3}, new int[]{4}));
        var rgb = LosslessVariantsTest.planes(45, 31, LosslessVariantsTest.ones(3), 12, 4);
        var script = directory.resolve("scans.txt");
        Files.writeString(script, "0,1: 5 0 0 1;\n2: 3 0 0 0;\n", StandardCharsets.US_ASCII);
        files.put("45x31-rgb-p12-psv5-cjpeg-scans.jpg", Oracle.run(Oracle.Tool.CJPEG, Duration.ofSeconds(60),
                LosslessVariantsTest.Oracles.pnm(rgb, 45, 31, 12), List.of("-precision", "12", "-lossless", "5,1",
                        "-restart", "2", "-scans", script.toAbsolutePath().toString())));
        Files.delete(script);
        var listing = new ArrayList<String>();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            Files.write(directory.resolve(file.getKey()), file.getValue());
            var digest = MessageDigest.getInstance("SHA-256").digest(file.getValue());
            listing.add(HexFormat.of().formatHex(digest) + "  " + file.getKey());
        }
        Files.write(directory.resolve("variants.sha256"), listing, StandardCharsets.US_ASCII);
    }
}
