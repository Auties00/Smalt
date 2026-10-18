package com.github.auties00.smalt.audio.amr;

import com.github.auties00.smalt.audio.amr.internal.nb.NbDecoder;
import com.github.auties00.smalt.audio.amr.internal.nb.NbEncoder;
import com.github.auties00.smalt.audio.amr.internal.wb.WbDecoder;
import com.github.auties00.smalt.audio.amr.internal.wbenc.WbEncoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs the 3GPP digital test sequences of AMR-NB (TS 26.074) and AMR-WB (TS 26.174) through the
 * frame coders: every encoder output must equal the reference bitstream word for word, and every
 * decoder output the reference waveform sample for sample. The sequences cover every mode,
 * frame-by-frame mode switching, the homing frames, and discontinuous transmission with VAD
 * option 1 (AMR-NB) and the AMR-WB detector. The AMR-NB VAD option 2 and the A-law and mu-law
 * variants of the sequences are not run.
 *
 * <p>The AMR-WB encoder follows the TS 26.173 arithmetic and reproduces every sequence, including
 * the overloaded inputs, the mode switching sequence and the DTX sequences that vo-amrwbenc (and
 * so ffmpeg's {@code libvo_amrwbenc}) codes differently.
 */
class AmrConformanceTest {
    private static final String[] NB_MODES = {"475", "515", "59", "67", "74", "795", "102", "122"};
    private static final String[] WB_MODES = {"660", "885", "1265", "1425", "1585", "1825", "1985", "2305", "2385"};
    private static final int NB_FRAME_WORDS = 250;
    private static final int WB_SYNC = 0x6b21;

    @Nested
    @DisplayName("AMR-NB")
    class Narrowband {
        @TestFactory
        @DisplayName("encodes every sequence of every mode to the reference bitstream")
        Stream<DynamicTest> encoder() {
            var root = AmrVectors.narrowband();
            var tests = new ArrayList<DynamicTest>();
            for (var mode = 0; mode < NB_MODES.length; mode++) {
                var folder = "T_" + NB_MODES[mode];
                for (var cod : AmrVectors.list(root.resolve(folder), name -> name.toUpperCase(Locale.ROOT).endsWith(".COD"))) {
                    var name = cod.getFileName().toString();
                    var input = AmrVectors.find(root.resolve("T_INP"), name.substring(0, name.indexOf('_')) + ".INP");
                    var fixed = mode;
                    tests.add(DynamicTest.dynamicTest(folder + "/" + name, () -> encodeNb(input, cod, frame -> fixed, false)));
                }
            }
            return tests.stream();
        }

        @TestFactory
        @DisplayName("decodes every sequence of every mode to the reference waveform")
        Stream<DynamicTest> decoder() {
            var root = AmrVectors.narrowband();
            var tests = new ArrayList<DynamicTest>();
            for (var mode : NB_MODES) {
                var folder = "T_" + mode;
                for (var cod : AmrVectors.list(root.resolve(folder), name -> name.toUpperCase(Locale.ROOT).endsWith(".COD"))) {
                    var out = sibling(cod, ".OUT");
                    if (out != null) {
                        tests.add(DynamicTest.dynamicTest(folder + "/" + cod.getFileName(), () -> decodeNb(cod, out)));
                    }
                }
            }
            return tests.stream();
        }

        @TestFactory
        @DisplayName("switches modes frame by frame as the mode switching sequence requires")
        Stream<DynamicTest> modeSwitching() {
            var root = AmrVectors.narrowband();
            var modes = nbModeFile(AmrVectors.find(root, "T21.MOD"));
            var input = AmrVectors.find(root.resolve("T_INP"), "T21.INP");
            var cod = AmrVectors.find(root.resolve("T_MODE"), "T21.COD");
            var out = AmrVectors.find(root.resolve("T_MODE"), "T21.OUT");
            return Stream.of(
                    DynamicTest.dynamicTest("encoder", () -> encodeNb(input, cod, frame -> modes[frame], false)),
                    DynamicTest.dynamicTest("decoder", () -> decodeNb(cod, out)));
        }

        @TestFactory
        @DisplayName("codes the discontinuous transmission sequences (VAD option 1)")
        Stream<DynamicTest> dtx() {
            var root = AmrVectors.narrowband().resolve("DTX");
            var tests = new ArrayList<DynamicTest>();
            for (var cod : AmrVectors.list(root, name -> name.toUpperCase(Locale.ROOT).endsWith(".COD"))) {
                var name = cod.getFileName().toString();
                var sequence = name.substring(0, name.indexOf('_'));
                var mode = indexOf(NB_MODES, name.substring(name.indexOf('_') + 1, name.lastIndexOf('.')));
                var input = AmrVectors.find(root, sequence + ".INP");
                tests.add(DynamicTest.dynamicTest("encoder " + name, () -> encodeNb(input, cod, frame -> mode, true)));
                var out = sibling(cod, ".OUT");
                if (out != null) {
                    tests.add(DynamicTest.dynamicTest("decoder " + name, () -> decodeNb(cod, out)));
                }
            }
            return tests.stream();
        }
    }

    @Nested
    @DisplayName("AMR-WB")
    class Wideband {
        @TestFactory
        @DisplayName("encodes every sequence of every mode to the reference bitstream")
        Stream<DynamicTest> encoder() {
            var root = AmrVectors.wideband().resolve("T");
            var tests = new ArrayList<DynamicTest>();
            for (var mode = 0; mode < WB_MODES.length; mode++) {
                var folder = "T_" + WB_MODES[mode];
                for (var cod : AmrVectors.list(root.resolve(folder), name -> name.toLowerCase(Locale.ROOT).endsWith(".cod"))) {
                    var name = cod.getFileName().toString();
                    var input = AmrVectors.find(root.resolve("T_inp"), name.substring(0, name.indexOf('_')) + ".inp");
                    var fixed = mode;
                    tests.add(DynamicTest.dynamicTest(folder + "/" + name, () -> encodeWb(input, cod, frame -> fixed, false)));
                }
            }
            return tests.stream();
        }

        @TestFactory
        @DisplayName("decodes every sequence of every mode to the reference waveform")
        Stream<DynamicTest> decoder() {
            var root = AmrVectors.wideband().resolve("T");
            var tests = new ArrayList<DynamicTest>();
            for (var mode : WB_MODES) {
                var folder = "T_" + mode;
                for (var cod : AmrVectors.list(root.resolve(folder), name -> name.toLowerCase(Locale.ROOT).endsWith(".cod"))) {
                    var out = sibling(cod, ".out");
                    if (out != null) {
                        tests.add(DynamicTest.dynamicTest(folder + "/" + cod.getFileName(), () -> decodeWb(cod, out)));
                    }
                }
            }
            return tests.stream();
        }

        @TestFactory
        @DisplayName("switches modes frame by frame as the mode switching sequence requires")
        Stream<DynamicTest> modeSwitching() {
            var root = AmrVectors.wideband().resolve("T");
            var modes = wbModeFile(AmrVectors.find(root.resolve("T_inp"), "T22.mod"));
            var input = AmrVectors.find(root.resolve("T_inp"), "T22.inp");
            var cod = AmrVectors.find(root.resolve("T_mode"), "T22.cod");
            var out = AmrVectors.find(root.resolve("T_mode"), "T22.out");
            return Stream.of(
                    DynamicTest.dynamicTest("encoder", () -> encodeWb(input, cod, frame -> modes[frame], false)),
                    DynamicTest.dynamicTest("decoder", () -> decodeWb(cod, out)));
        }

        @TestFactory
        @DisplayName("codes the discontinuous transmission sequences")
        Stream<DynamicTest> dtx() {
            var root = AmrVectors.wideband().resolve("T").resolve("DTX_test_vectors");
            var tests = new ArrayList<DynamicTest>();
            for (var mode = 0; mode < WB_MODES.length; mode++) {
                var folder = root.resolve("T_" + WB_MODES[mode]);
                if (!Files.isDirectory(folder)) {
                    continue;
                }
                for (var cod : AmrVectors.list(folder, name -> name.toLowerCase(Locale.ROOT).endsWith(".cod"))) {
                    var name = cod.getFileName().toString();
                    var input = AmrVectors.find(root.resolve("T_inp"), name.substring(0, name.indexOf('_')) + ".inp");
                    var fixed = mode;
                    tests.add(DynamicTest.dynamicTest("encoder " + name, () -> encodeWb(input, cod, frame -> fixed, true)));
                    var out = sibling(cod, ".out");
                    if (out != null) {
                        tests.add(DynamicTest.dynamicTest("decoder " + name, () -> decodeWb(cod, out)));
                    }
                }
            }
            return tests.stream();
        }
    }

    private interface ModeSchedule {
        int mode(int frame);
    }

    private static void encodeNb(Path input, Path cod, ModeSchedule schedule, boolean dtx) {
        var pcm = AmrVectors.words(input);
        var expected = AmrVectors.words(cod);
        var encoder = new NbEncoder(dtx);
        var ets = new short[246];
        var frames = pcm.length / 160;
        assertEquals(expected.length / NB_FRAME_WORDS, frames, "frame count");
        for (var frame = 0; frame < frames; frame++) {
            encoder.encodeEts(schedule.mode(frame), pcm, frame * 160, ets);
            var base = frame * NB_FRAME_WORDS;
            for (var i = 0; i < NB_FRAME_WORDS; i++) {
                var actual = i < ets.length ? ets[i] : 0;
                if (expected[base + i] != actual) {
                    fail("frame " + frame + " word " + i + ": expected " + expected[base + i] + ", got " + actual);
                }
            }
        }
    }

    private static void decodeNb(Path cod, Path out) {
        var words = AmrVectors.words(cod);
        var expected = AmrVectors.words(out);
        var decoder = new NbDecoder();
        var pcm = new short[160];
        var frames = words.length / NB_FRAME_WORDS;
        assertEquals(expected.length / 160, frames, "frame count");
        for (var frame = 0; frame < frames; frame++) {
            var base = frame * NB_FRAME_WORDS;
            decoder.decodeEts(words[base], words, base + 1, words[base + 245], pcm, 0);
            compare(expected, frame * 160, pcm, frame);
        }
    }

    private static void encodeWb(Path input, Path cod, ModeSchedule schedule, boolean dtx) {
        var pcm = AmrVectors.words(input);
        var expected = AmrVectors.words(cod);
        var encoder = new WbEncoder(dtx);
        var serial = new short[3 + 477];
        var position = 0;
        var frames = pcm.length / 320;
        for (var frame = 0; frame < frames; frame++) {
            var count = encoder.encodeSerial(schedule.mode(frame), pcm, frame * 320, serial);
            for (var i = 0; i < count; i++) {
                if (position + i >= expected.length || expected[position + i] != serial[i]) {
                    fail("frame " + frame + " word " + i + ": expected "
                            + (position + i < expected.length ? expected[position + i] : "end of file") + ", got " + serial[i]);
                }
            }
            position += count;
        }
        assertEquals(expected.length, position, "bitstream length");
    }

    private static void decodeWb(Path cod, Path out) {
        var words = AmrVectors.words(cod);
        var expected = AmrVectors.words(out);
        var decoder = new WbDecoder();
        var pcm = new short[320];
        var position = 0;
        var frame = 0;
        while (position < words.length) {
            assertEquals(WB_SYNC, words[position] & 0xFFFF, "sync word of frame " + frame);
            var end = position + 3;
            while (end < words.length && (words[end] & 0xFFFF) != WB_SYNC) {
                end++;
            }
            decoder.decodeSerial(words[position + 1], words[position + 2], words, position + 3, end - position - 3, pcm, 0);
            compare(expected, frame * 320, pcm, frame);
            frame++;
            position = end;
        }
        assertEquals(expected.length / 320, frame, "frame count");
    }

    private static void compare(short[] expected, int offset, short[] actual, int frame) {
        for (var i = 0; i < actual.length; i++) {
            if (offset + i >= expected.length || expected[offset + i] != actual[i]) {
                fail("frame " + frame + " sample " + i + ": expected "
                        + (offset + i < expected.length ? expected[offset + i] : "end of file") + ", got " + actual[i]);
            }
        }
    }

    private static Path sibling(Path file, String extension) {
        var name = file.getFileName().toString();
        var base = name.substring(0, name.lastIndexOf('.'));
        try (var files = Files.list(file.getParent())) {
            return files.filter(candidate -> candidate.getFileName().toString().equalsIgnoreCase(base + extension))
                    .findFirst().orElse(null);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static int[] nbModeFile(Path file) {
        try {
            var modes = new ArrayList<Integer>();
            for (var line : Files.readAllLines(file)) {
                var trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    modes.add(indexOf(NB_MODES, trimmed.substring(2)));
                }
            }
            return modes.stream().mapToInt(Integer::intValue).toArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static int[] wbModeFile(Path file) {
        try {
            return List.of(Files.readString(file).trim().split("\\s+")).stream().mapToInt(Integer::parseInt).toArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static int indexOf(String[] values, String value) {
        for (var i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return i;
            }
        }
        throw new IllegalArgumentException("unknown mode " + value);
    }
}
