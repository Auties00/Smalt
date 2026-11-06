package com.github.auties00.smalt.video.h263;

import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.mpeg4.EncoderScenes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the H.263 encoder's Advanced Prediction streams that the TMN 3.2 decoder of the University of British Columbia
 * checks: the encoder must still code each of them byte for byte, so the golden.tsv row of the annex streams (the MD5
 * of {@code tmndec -r}'s decode, which the annex engine reproduces sample for sample in {@code H263AnnexStreamsTest})
 * keeps describing what it codes.
 *
 * <p>The TMN decoder is a black-box oracle that CI does not provide. With {@code -Dsmalt.test.tmndec} naming its
 * executable, every stream is also decoded with it: {@code tmndec -r} (the double precision reference inverse DCT)
 * must give the MD5 of golden.tsv, and its default decode must stay within the inverse DCT noise of the encoder's own
 * reconstruction. With {@code -Dsmalt.test.writeTmnVectors} naming a directory, the streams are written there instead
 * of compared, to replace the committed ones after a deliberate change of the encoder's output.
 */
@DisplayName("H.263 encoder against the TMN decoder")
class H263EncoderTmnTest {
    private static final String VECTORS = "/com/github/auties00/smalt/video/h263/annex/";

    record Config(String file, int width, int height, int frames, H263EncoderOptions options,
                  EncoderScenes.Motion motion, double streamPsnr, double picturePsnr) {
        @Override
        public String toString() {
            return file;
        }
    }

    // The NTSC picture clock keeps the extended picture types on the standard clock, which the TMN decoder needs.
    static H263EncoderOptions.Builder base() {
        return H263EncoderOptions.builder().timeBase(new Rational(1001, 30000)).frameRate(new Rational(30000, 1001))
                .bitrate(300_000).advancedPrediction(true);
    }

    // The inverse DCTs of the encoder and the TMN decoder differ within IEEE 1180, which the deblocking filter of
    // Annex J widens (measured: 60.4 dB at worst per picture without it, 56 dB with it).
    static Stream<Config> configs() {
        return Stream.of(
                new Config("smalt-encoder-f.263", 176, 144, 12, base().build(), EncoderScenes.Motion.PAN, 60, 58),
                new Config("smalt-encoder-f-still.263", 176, 144, 12, base().build(), EncoderScenes.Motion.STILL, 60,
                        58),
                new Config("smalt-encoder-df-fast.263", 176, 144, 12, base().unrestrictedVectors(true).searchRange(32)
                        .build(), EncoderScenes.Motion.FAST, 60, 58),
                new Config("smalt-encoder-fijt.263", 176, 144, 12, base().advancedIntraCoding(true)
                        .deblockingFilter(true).modifiedQuantization(true).build(), EncoderScenes.Motion.PAN, 56, 54),
                new Config("smalt-encoder-df-cif.263", 352, 288, 8, base().unrestrictedVectors(true).searchRange(32)
                        .bitrate(800_000).build(), EncoderScenes.Motion.FAST, 60, 58)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("configs")
    @DisplayName("codes the TMN-checked stream byte for byte, and the TMN decoder decodes it as golden.tsv records")
    void matchesTmnDecoder(Config config) throws IOException, InterruptedException {
        EncoderScenes.Encoded encoded;
        try (var encoder = new H263Encoder(config.width(), config.height(), config.options())) {
            encoded = EncoderScenes.encode(encoder, config.frames(), 1, config.motion(),
                    H263Encoder::lastReconstruction);
        }
        var stream = encoded.elementary();
        var write = System.getProperty("smalt.test.writeTmnVectors");
        if (write != null) {
            Files.createDirectories(Path.of(write));
            Files.write(Path.of(write, config.file()), stream);
        } else {
            assertArrayEquals(resource(config.file()), stream, config.file() + ": the encoder's output changed;"
                    + " regenerate the stream and its golden.tsv row as tools/testdata/h263-annexes/README.md says");
        }
        var tmndec = System.getProperty("smalt.test.tmndec");
        if (tmndec == null) {
            return;
        }
        var directory = Files.createTempDirectory("h263-tmn");
        var file = directory.resolve(config.file());
        Files.write(file, stream);
        var reconstruction = EncoderScenes.concat(new TreeMap<>(encoded.reconstructions()).values().stream().toList());
        var reference = tmndec(Path.of(tmndec), file, true);
        assertEquals(golden(config.file()), HexFormat.of().formatHex(digest("MD5").digest(reference)),
                config.file() + ": MD5 of tmndec -r");
        var decoded = tmndec(Path.of(tmndec), file, false);
        assertEquals(reconstruction.length, decoded.length, "tmndec's decoded size");
        Tolerance.psnr(config.streamPsnr()).assertWithin(config + " against tmndec", reconstruction, decoded);
        var picture = config.width() * config.height() * 3 / 2;
        for (var at = 0; at < decoded.length; at += picture) {
            var psnr = Tolerance.Difference.of(reconstruction, decoded, at, at + picture).psnr();
            assertTrue(psnr >= config.picturePsnr(), config + ", picture " + at / picture + ": " + psnr + " dB");
        }
    }

    /**
     * Decodes a stream with the TMN decoder into concatenated planar 4:2:0 pictures.
     */
    private static byte[] tmndec(Path executable, Path file, boolean referenceTransform)
            throws IOException, InterruptedException {
        // The decoder takes the output name as a pattern, so both names are relative to its working directory.
        var output = file.getFileName() + (referenceTransform ? ".r.yuv" : ".yuv");
        var input = file.getFileName().toString();
        var command = referenceTransform
                ? new String[]{executable.toString(), "-q", "-r", "-o5", input, output}
                : new String[]{executable.toString(), "-q", "-o5", input, output};
        var process = new ProcessBuilder(command).directory(file.getParent().toFile()).redirectErrorStream(true)
                .start();
        process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
        assertTrue(process.waitFor(5, TimeUnit.MINUTES), "tmndec finished");
        return Files.readAllBytes(file.resolveSibling(output));
    }

    /**
     * Returns the MD5 golden.tsv records for a stream.
     */
    private static String golden(String file) {
        for (var line : new String(resource("golden.tsv"), StandardCharsets.UTF_8).split("\n")) {
            var fields = line.split("\t");
            if (fields[0].equals(file)) {
                return fields[5];
            }
        }
        throw new AssertionError(file + " has no golden.tsv row");
    }

    private static byte[] resource(String name) {
        try (InputStream in = H263EncoderTmnTest.class.getResourceAsStream(VECTORS + name)) {
            assertNotNull(in, name);
            return in.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
