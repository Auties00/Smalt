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
 * and silence; a real libvorbis encode of a piano recording adds music at 96 kHz.
 */
class VorbisConformanceTest {
    /** Both decoders compute in float, so they differ only by rounding: at least 90 dB of SNR per link. */
    private static final Tolerance TOLERANCE = Tolerance.snr(90);

    /** A silent link has no SNR: it decodes to the same silence, -0.0 equal to 0.0. */
    private static final Tolerance SILENCE = Tolerance.exact();

    /**
     * A vector, whether ffmpeg's native decoder is a valid float reference, and whether ffmpeg trims it to exactly
     * the length the Ogg granules give.
     */
    record Vector(String name, boolean nativeReference, boolean exactLength) {
        Vector(String name, boolean nativeReference) {
            this(name, nativeReference, true);
        }

        Path file() {
            return Conformance.vector(name.endsWith(".ogg") ? name : "vorbis-" + name + ".ogg");
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Vector> vectors() {
        return Stream.of(
                new Vector("1.0-test", true),
                new Vector("1.0.1-test", true),
                new Vector("48k-mono", true),
                new Vector("beta3-test", true),
                new Vector("beta4-test", true),
                new Vector("bimS-silence", true),
                new Vector("chain-test1", true),
                new Vector("chain-test2", true),
                // ffmpeg's native decoder lacks lookup type 2
                new Vector("chain-test3", false),
                new Vector("combustion", false),
                new Vector("highrate-test", true),
                new Vector("lsp-test", true),
                new Vector("lsp-test2", true),
                new Vector("lsp-test3", true),
                new Vector("lsp-test4", true),
                new Vector("make", false),
                new Vector("mono", true),
                new Vector("moog", true),
                new Vector("one-entry-codebook-test", true),
                new Vector("out-of-spec-blocksize", true),
                new Vector("rc1-test", true),
                new Vector("rc2-test", true),
                new Vector("rc2-test2", true),
                new Vector("rc3-test", true),
                // ffmpeg's native decoder disagrees with libvorbis (18 dB) on this stream
                new Vector("singlemap-test", false),
                new Vector("sleepzor", true),
                new Vector("test-short", false),
                new Vector("test-short2", true),
                // 34 packets select an undeclared mode; ffmpeg's end trimming then drops 897 more samples
                new Vector("unused-mode-test", true, false),
                // a real libvorbis encode of a 96 kHz piano recording (Open Goldberg Variations, archive.org)
                new Vector("opengoldberg-05-variatio4.ogg", true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("matches libvorbis to within one 16-bit step (two on at most 0.001% of samples) on every link")
    void libvorbis(Vector vector) throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var links = OggVorbis.read(Files.readAllBytes(vector.file()));
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
        var links = OggVorbis.read(Files.readAllBytes(vector.file()));
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
            return vector.file();
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
