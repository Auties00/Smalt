package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Decodes the Xiph.Org Vorbis test vectors ({@code people.xiph.org/~xiphmont/test-vectors/vorbis})
 * and compares every logical stream with the libvorbis reference decoder (run through ffmpeg's
 * {@code libvorbis} wrapper, which truncates {@code x * 32767} to 16 bits) and, where ffmpeg's
 * native decoder supports the stream and agrees with libvorbis, with its float output.
 *
 * <p>The vectors cover floor 0 (the {@code lsp-test*} and pre-1.0 files), floor 1, residue formats
 * 0, 1 and 2, lookup types 1 and 2 ({@code test-short}, {@code combustion}, {@code make}), the
 * single-entry codebook errata, a stream with a single mode, chained streams, undeclared modes
 * and silence.
 */
class VorbisConformanceTest {
    /** Both decoders compute in float, so they differ only by rounding: at least 90 dB of SNR per link. */
    private static final Tolerance TOLERANCE = Tolerance.snr(90);

    /** A silent link has no SNR: it decodes to the same silence, -0.0 equal to 0.0. */
    private static final Tolerance SILENCE = Tolerance.exact();

    private static final String BASE = "https://people.xiph.org/~xiphmont/test-vectors/vorbis/";

    /**
     * A vector, its pinned hash, whether ffmpeg's native decoder is a valid float reference, and
     * whether ffmpeg trims it to exactly the length the Ogg granules give.
     */
    record Vector(String name, String sha256, boolean nativeReference, boolean exactLength) {
        Vector(String name, String sha256, boolean nativeReference) {
            this(name, sha256, nativeReference, true);
        }

        Path fetch() {
            return Conformance.fetch("vorbis-" + name + ".ogg", BASE + name + ".ogg", sha256);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Vector> vectors() {
        return Stream.of(
                new Vector("1.0-test", "9a882710314bcc1d2b4cdefb7f89911f4375acd1feb9e64a12eca9f7202377d3", true),
                new Vector("1.0.1-test", "8c9423e00826d6d2457d78c09d6e2a94bcdf9216af796bb22a4c4f450c2e72bb", true),
                new Vector("48k-mono", "f51459d9bdd04ca3ec6f6732b8a01efcdc83e5c6fc79c7d5347527bfd4948e9e", true),
                new Vector("beta3-test", "7fc791a4d5a0d3b7cef2448093ccf2ae54600acc81667be90c6b209c366c32ea", true),
                new Vector("beta4-test", "bc367c0d4dcdbf1f0a2f81abf74cc52c8f0ab8366d150a75830782ca2dba080a", true),
                new Vector("bimS-silence", "e2a38871d390ed651faf0fec5253a6873530da4f2503d85021c99325dfd23813", true),
                new Vector("chain-test1", "d9c37533a1f456d2a996755a43d112ef46b6ef953319913907bc79f1c014d79e", true),
                new Vector("chain-test2", "5b5bf834e93e9a93b7114be084161e972f33d2031963518492ba2c91dc4418a7", true),
                // ffmpeg's native decoder lacks lookup type 2
                new Vector("chain-test3", "ed039ba775d1b31e805d26d6413a3ef2c6663bf4c5ea47ce1462f8f23c84d8dc", false),
                new Vector("combustion", "5f58888fd5061c1202a4a93ae13a13f73a5c096a62fee17918a87c6627dabfd8", false),
                new Vector("highrate-test", "0942c88369f84b125388cc8437575b66d67bc97baaeeb997b6602144821509df", true),
                new Vector("lsp-test", "ad1b07b68576ae2c85178475ef3607e1a96c09e96296cae83ee7a9726f8311eb", true),
                new Vector("lsp-test2", "7b00f893a93071bdf243b8a22c1758a6ab09c1ab8eee89ca21659d98ec0f53ea", true),
                new Vector("lsp-test3", "7a5c4064fc31285f6fea0b2424a0d415a8b18ef13d1aa1ef931a429ab9a593d1", true),
                new Vector("lsp-test4", "cb0b28931dfc8ef8b2d320f3028fab548171e7c6e94006f0c23532478a8d3596", true),
                new Vector("make", "28d14448873bc08aa2d57ead5ef4705ed3f00a730a377e2f637dc85233ec17b7", false),
                new Vector("mono", "d8abca95445a07186c9a158d4f573f38918985dd2498a9bc8d1811bd72fe1d1a", true),
                new Vector("moog", "bd5b51bb1d6855e0e990e3ebdd230fc16265407809bd6db44aea691ada498943", true),
                new Vector("one-entry-codebook-test", "789b5146f2a7c0864a228ee4a870606a32c4169e22097be65129623705c54749", true),
                new Vector("out-of-spec-blocksize", "0970e66291744815f2ca0dec7523f5bbc112907c5c75978a0f91d3b0ed6f03b2", true),
                new Vector("rc1-test", "ccfac6ac7c75615bec0632b94bcf088c027c0b112cd136c997d04f037252cd3d", true),
                new Vector("rc2-test", "4adcda786dfeea4188d7b1df35571dbe29c687f2d1cac680ed115353abfe1637", true),
                new Vector("rc2-test2", "24ade471eefe1c7642f73a6810e56266b34c2d7d30a3b5ad05a34c098128226c", true),
                new Vector("rc3-test", "edd984e84c7c2a59af7801f3e8d2db11a6619134fd8d0274d1289e70bf60cde7", true),
                // ffmpeg's native decoder disagrees with libvorbis (18 dB) on this stream
                new Vector("singlemap-test", "50d8077608a4192b8f8505aec0217be8b6c25def4068899afc19c17f30e1d521", false),
                new Vector("sleepzor", "01c67ecaf7a58b5ac5f1fe3bd060b5d61536595e97927a1b1cf0129a62b5cfcf", true),
                new Vector("test-short", "183510552403021fb90ce43796fcc88e16c8bb4ae5d0d72a316b8e51afc395fa", false),
                new Vector("test-short2", "6bd3f59e0fa77904edd35c73dadd6558e2a36d78c9d7bc5db223862bf092fcc2", true),
                // 34 packets select an undeclared mode; ffmpeg's end trimming then drops 897 more samples
                new Vector("unused-mode-test", "e27ae6fc2f7c0037c28328801c46d966abcee3050e3ebd40bf097f7986f50f94", true, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("matches libvorbis to within one 16-bit step (two on at most 0.001% of samples) on every link")
    void libvorbis(Vector vector) throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var links = OggVorbis.read(Files.readAllBytes(vector.fetch()));
        for (var index = 0; index < links.size(); index++) {
            var link = links.get(index);
            var file = linkFile(vector, index, link);
            var decoded = OggVorbis.decode(link);
            var reference = libvorbis(file);
            var ours = decoded.interleaved();
            var length = checkLength(vector, index, decoded, reference.length, ours.length);
            var maxDifference = 0;
            var mismatches = 0L;
            var outliers = 0L;
            for (var i = 0; i < length; i++) {
                var quantized = (int) Math.clamp((long) (ours[i] * 32767.0f), -32768, 32767);
                var difference = Math.abs(quantized - reference[i]);
                maxDifference = Math.max(maxDifference, difference);
                if (difference != 0) {
                    mismatches++;
                }
                if (difference > 1) {
                    outliers++;
                }
            }
            // libvorbis' own float rounding leaves a handful of samples two steps from ffmpeg's decoder as well
            assertTrue(maxDifference <= 2 && outliers <= length / 100_000, vector + " link " + index + " differs by up to "
                    + maxDifference + " 16-bit steps (" + mismatches + " differ, " + outliers + " by more than one, of "
                    + length + ")");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nativeVectors")
    @DisplayName("matches ffmpeg's float decoder with an SNR of at least 90 dB and equal length")
    void ffmpeg(Vector vector) throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var links = OggVorbis.read(Files.readAllBytes(vector.fetch()));
        for (var index = 0; index < links.size(); index++) {
            var link = links.get(index);
            var file = linkFile(vector, index, link);
            var decoded = OggVorbis.decode(link);
            var reference = Oracle.decodeAudio(file);
            var ours = decoded.interleaved();
            var length = checkLength(vector, index, decoded, reference.length, ours.length);
            var difference = Tolerance.Difference.ofPcm(java.util.Arrays.copyOf(reference, length),
                    java.util.Arrays.copyOf(ours, length));
            if (difference.signalSquares() == 0) {
                // a silent link compares by value
                SILENCE.assertPcm(vector + " silent link " + index, reference, ours);
            } else {
                TOLERANCE.check(vector + " link " + index, difference);
            }
        }
    }

    private static int checkLength(Vector vector, int index, OggVorbis.Decoded decoded, int expected, int actual) {
        if (vector.exactLength()) {
            assertEquals(expected, actual, vector + " link " + index + " sample count");
            assertEquals(0, decoded.rejected(), vector + " link " + index + " rejected packets");
        } else {
            assertTrue(decoded.rejected() > 0, vector + " link " + index + " should reject packets");
            assertTrue(Math.abs(expected - actual) <= 8192 * decoded.channels(), vector + " length " + actual + " vs " + expected);
        }
        return Math.min(expected, actual);
    }

    static Stream<Vector> nativeVectors() {
        return vectors().filter(Vector::nativeReference);
    }

    private static Path linkFile(Vector vector, int index, OggVorbis.Link link) {
        if (index == 0 && vector.name().startsWith("chain") == false) {
            return vector.fetch();
        }
        try {
            var directory = Path.of("target", "vorbis-links");
            Files.createDirectories(directory);
            var file = directory.resolve(vector.name() + "-" + index + ".ogg");
            Files.write(file, link.file());
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static short[] libvorbis(Path file) {
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5),
                List.of("-v", "error", "-c:a", "libvorbis", "-i", file.toString(), "-f", "s16le", "-"));
        var samples = new short[raw.length / 2];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
        return samples;
    }
}
