package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import static com.github.auties00.smalt.image.jp2.internal.Part2Forward.Tree;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks the code-block anchor points of ISO/IEC 15444-2 I.2 ({@code Scod} bits 3 and 4) and the block coder
 * extensions of annex P ({@code SXcod} and {@code SXcoc}) against codestreams Kakadu wrote, whose decodes must be their
 * sources exactly, and against codestreams the test writes with its own block coder ({@link Part2BlockCoder}).
 */
@DisplayName("JPEG 2000 Part 2 code-block anchor points and block coder extensions")
class Jp2Part2AnchorBypassTest {
    private static final Path FIXTURES = Path.of("src", "test", "resources", "com", "github", "auties00", "smalt",
            "image", "jp2", "kakadu-cbap");

    private static final Map<String, String> SUMS = sums();

    private static Map<String, String> sums() {
        var sums = new HashMap<String, String>();
        for (var line : new String(Fixtures.read(FIXTURES.resolve("SHA256SUMS")), StandardCharsets.US_ASCII)
                .split("\n")) {
            if (!line.isBlank()) {
                var parts = line.trim().split(" +");
                sums.put(parts[1], parts[0]);
            }
        }
        return sums;
    }

    private static byte[] fixture(String name) throws Exception {
        var data = Fixtures.read(FIXTURES.resolve(name));
        var digest = MessageDigest.getInstance("SHA-256");
        assertEquals(SUMS.get(name), HexFormat.of().formatHex(digest.digest(data)), "fixture hash of " + name);
        return data;
    }

    /** The samples of a binary 8-bit PGM file. */
    private static int[] pgm(byte[] file) {
        var position = 0;
        var fields = 0;
        while (fields < 4) {
            while (Character.isWhitespace(file[position])) {
                position++;
            }
            while (!Character.isWhitespace(file[position])) {
                position++;
            }
            fields++;
        }
        position++;
        var samples = new int[file.length - position];
        for (var i = 0; i < samples.length; i++) {
            samples[i] = file[position + i] & 0xFF;
        }
        return samples;
    }

    private static int[] channel(byte[] codestream, int component, int reduction) {
        return Jp2Part2Test.decode(codestream, reduction).channels().get(component).samples();
    }

    /** The index of the first byte of a marker segment's body ({@code Lxxx} excluded). */
    private static int segment(byte[] codestream, int marker) {
        for (var i = 2; i + 3 < codestream.length; ) {
            var code = ((codestream[i] & 0xFF) << 8) | (codestream[i + 1] & 0xFF);
            if (code == marker) {
                return i + 4;
            }
            if (code == Codestream.SOT) {
                break;
            }
            i += 2 + (((codestream[i + 2] & 0xFF) << 8) | (codestream[i + 3] & 0xFF));
        }
        throw new AssertionError("no segment " + Integer.toHexString(marker));
    }

    private static int segmentLength(byte[] codestream, int body) {
        return (((codestream[body - 2] & 0xFF) << 8) | (codestream[body - 1] & 0xFF)) - 2;
    }

    @Nested
    @DisplayName("Kakadu's codestreams")
    class Kakadu {
        static Stream<Arguments> fixtures() {
            return Stream.of(Arguments.of("anchor-lrcp", 1), Arguments.of("anchor-x-rpcl-tiles", 1),
                    Arguments.of("anchor-y-pcrl-origin", 1), Arguments.of("anchor-decomposition", 1),
                    Arguments.of("anchor-split-detail", 1), Arguments.of("anchor-rpcl-3c", 3),
                    Arguments.of("anchor-pcrl-3c", 3), Arguments.of("anchor-cprl-3c", 3), Arguments.of("bypass-e1", 1),
                    Arguments.of("bypass-e2", 1), Arguments.of("bypass-e3", 1),
                    Arguments.of("bypass-components", 3), Arguments.of("anchor-bypass", 1));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("fixtures")
        @DisplayName("decode to their sources, as Kakadu decodes them")
        void lossless(String name, int components) throws Exception {
            var codestream = fixture(name + ".j2c");
            var sources = List.of("source.pgm", "source-1.pgm", "source-2.pgm");
            var image = Jp2Part2Test.decode(codestream);
            assertEquals(components, image.channels().size(), "components");
            for (var c = 0; c < components; c++) {
                Tolerance.exact().assertWithin(name + " component " + c, pgm(fixture(sources.get(c))),
                        image.channels().get(c).samples());
            }
        }

        @ParameterizedTest(name = "{0} at reduction {1}")
        @MethodSource
        @DisplayName("decode at a reduced resolution as Kakadu does")
        void reduced(String name, int reduction) throws Exception {
            var expected = pgm(fixture(name + "-reduced-" + reduction + ".pgm"));
            Tolerance.exact().assertWithin(name, expected, channel(fixture(name + ".j2c"), 0, reduction));
        }

        static Stream<Arguments> reduced() {
            return Stream.of(Arguments.of("anchor-lrcp", 1), Arguments.of("anchor-y-pcrl-origin", 2));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"anchor-lrcp", "anchor-x-rpcl-tiles", "anchor-y-pcrl-origin", "anchor-pcrl-3c"})
        @DisplayName("decode differently once the anchor point is cleared, so the anchor point is not ignored")
        void anchorUsed(String name) throws Exception {
            var codestream = fixture(name + ".j2c");
            var cleared = codestream.clone();
            cleared[segment(cleared, Codestream.COD)] &= (byte) ~Codestream.ANCHOR_FLAGS;
            assertDiffers(codestream, cleared);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"bypass-e1", "bypass-e2", "bypass-e3"})
        @DisplayName("decode differently once SXcod is cleared, so the earlier bypass is not ignored")
        void extensionsUsed(String name) throws Exception {
            var codestream = fixture(name + ".j2c");
            var cleared = codestream.clone();
            var body = segment(cleared, Codestream.COD);
            var end = body + segmentLength(cleared, body);
            cleared[end - 2] = 0;
            cleared[end - 1] = 0;
            assertDiffers(codestream, cleared);
        }

        @Test
        @DisplayName("Kakadu signals each bypass start in the SXcod and SXcoc fields of Table A.11bis")
        void signalling() throws Exception {
            var codings = new ArrayList<Integer>();
            for (var name : List.of("bypass-e1", "bypass-e2", "bypass-e3")) {
                var codestream = fixture(name + ".j2c");
                var parameters = Codestream.parse(codestream, 0, codestream.length, MediaLimits.DEFAULT).parameters(0);
                codings.add(parameters.codings()[0].bypassPlanes());
            }
            assertEquals(List.of(3, 2, 1), codings);
            var components = fixture("bypass-components.j2c");
            var parameters = Codestream.parse(components, 0, components.length, MediaLimits.DEFAULT).parameters(0);
            assertEquals(List.of(4, 1, 2), Arrays.stream(parameters.codings()).map(ComponentCoding::bypassPlanes)
                    .toList());
        }

        private static void assertDiffers(byte[] codestream, byte[] changed) {
            var expected = channel(codestream, 0, 0);
            try {
                assertFalse(Arrays.equals(expected, channel(changed, 0, 0)), "the change made no difference");
            } catch (MediaException expectedFailure) {
                // a stream read with the wrong partition or coding passes may also fail outright
            }
        }
    }

    @Nested
    @DisplayName("the test's block coder")
    class BlockCoder {
        static Stream<Arguments> styles() {
            var styles = new int[]{ComponentCoding.BYPASS, ComponentCoding.BYPASS | ComponentCoding.RESET,
                    ComponentCoding.BYPASS | ComponentCoding.TERMINATE_ALL,
                    ComponentCoding.BYPASS | ComponentCoding.VERTICALLY_CAUSAL
                            | ComponentCoding.SEGMENTATION_SYMBOLS, 0};
            var arguments = new ArrayList<Arguments>();
            for (var style : styles) {
                for (var extensions = 0; extensions < 4; extensions++) {
                    arguments.add(Arguments.of(style, extensions));
                }
            }
            return arguments.stream();
        }

        private static Part2Writer writer(int style, int extensions, int anchor, Random random) {
            var writer = new Part2Writer(45, 38, 1, 12, false);
            writer.x0 = random.nextInt(5);
            writer.y0 = random.nextInt(5);
            writer.levels = 3;
            writer.codeBlock = 3 + random.nextInt(2);
            writer.codeBlockStyle = style;
            writer.extensions = extensions;
            writer.anchor = anchor;
            writer.precinctSizes = random.nextBoolean() ? new int[]{4, 4, 5, 5} : null;
            writer.independentCoder = true;
            return writer;
        }

        private static long[] plane(Random random) {
            var plane = Jp2Part2Test.smooth(random, 45, 38, 0, 4095);
            for (var i = 0; i < plane.length; i += 7) {
                plane[i] = random.nextInt(4096);
            }
            return plane;
        }

        @ParameterizedTest(name = "style {0}, SXcod {1}")
        @MethodSource("styles")
        @DisplayName("codes every bypass start losslessly, the selective arithmetic coding bypass off without its flag")
        void lossless(int style, int extensions) {
            var random = new Random(31L * style + extensions);
            var writer = writer(style, extensions, random.nextInt(4), random);
            var plane = plane(random);
            var codestream = writer.write(Jp2Part2Test.forward(writer, new long[][]{plane},
                    Jp2Part2Test.levelShifts(writer), Tree.dyadic(3), null));
            Jp2Part2Test.assertChannels(new long[][]{plane}, Jp2Part2Test.decode(codestream));
        }

        @ParameterizedTest(name = "SXcod {0}")
        @ValueSource(ints = {1, 2, 3})
        @DisplayName("an earlier bypass start changes the coded passes: decoded with the default start, they differ")
        void startMatters(int extensions) {
            var random = new Random(extensions);
            var writer = writer(ComponentCoding.BYPASS, extensions, 0, random);
            var plane = plane(random);
            var codestream = writer.write(Jp2Part2Test.forward(writer, new long[][]{plane},
                    Jp2Part2Test.levelShifts(writer), Tree.dyadic(3), null));
            var changed = codestream.clone();
            var body = segment(changed, Codestream.COD);
            changed[body + segmentLength(changed, body) - 1] = 0;
            var expected = Jp2Part2Test.decode(codestream).channels().getFirst().samples();
            try {
                assertFalse(Arrays.equals(expected, Jp2Part2Test.decode(changed).channels().getFirst().samples()));
            } catch (MediaException failure) {
                // the arithmetic and raw segments no longer line up with the passes
            }
        }

        @ParameterizedTest(name = "style {0}")
        @ValueSource(ints = {0, ComponentCoding.RESET | ComponentCoding.VERTICALLY_CAUSAL})
        @DisplayName("codes the arithmetic-coded passes byte for byte as the encoder does")
        void matchesEncoder(int style) {
            var random = new Random(5);
            var plane = plane(random);
            var codestreams = new ArrayList<byte[]>();
            for (var independent : new boolean[]{false, true}) {
                var writer = writer(style, -1, 0, new Random(9));
                writer.independentCoder = independent;
                codestreams.add(writer.write(Jp2Part2Test.forward(writer, new long[][]{plane},
                        Jp2Part2Test.levelShifts(writer), Tree.dyadic(3), null)));
            }
            Tolerance.exact().assertWithin("codestream", codestreams.get(0), codestreams.get(1));
        }
    }

    @Nested
    @DisplayName("anchored partitions")
    class Partitions {
        @ParameterizedTest(name = "anchor {0}")
        @ValueSource(ints = {1, 2, 3})
        @DisplayName("a codestream the test writes with an anchor point decodes losslessly, and not without it")
        void lossless(int anchor) {
            var random = new Random(anchor);
            var writer = new Part2Writer(53, 41, 2, 8, false);
            writer.x0 = 1 + random.nextInt(6);
            writer.y0 = 1 + random.nextInt(6);
            writer.levels = 3;
            writer.codeBlock = 2;
            writer.anchor = anchor;
            writer.precinctSizes = new int[]{3, 3, 4, 4};
            var planes = new long[][]{Jp2Part2Test.smooth(random, 53, 41, 0, 255),
                    Jp2Part2Test.image(random, 53 * 41, 0, 255)};
            var codestream = writer.write(Jp2Part2Test.forward(writer, planes, Jp2Part2Test.levelShifts(writer),
                    Tree.dyadic(3), null));
            Jp2Part2Test.assertChannels(planes, Jp2Part2Test.decode(codestream));
            var cleared = codestream.clone();
            cleared[segment(cleared, Codestream.COD)] &= (byte) ~Codestream.ANCHOR_FLAGS;
            try {
                assertFalse(Arrays.equals(Jp2Part2Test.decode(codestream).channels().getFirst().samples(),
                        Jp2Part2Test.decode(cleared).channels().getFirst().samples()));
            } catch (MediaException failure) {
                // a different partition may misread the packets outright
            }
        }

        @ParameterizedTest(name = "anchor {0}")
        @ValueSource(ints = {0, 1, 2, 3})
        @DisplayName("the code-blocks of every sub-band follow the anchored partitions of I.2")
        void codeBlocks(int anchor) {
            for (var seed = 0; seed < 40; seed++) {
                var random = new Random(seed);
                var x0 = random.nextInt(40);
                var y0 = random.nextInt(40);
                var x1 = x0 + 1 + random.nextInt(90);
                var y1 = y0 + 1 + random.nextInt(90);
                var levels = random.nextInt(5);
                var blockWidth = 2 + random.nextInt(4);
                var blockHeight = 2 + random.nextInt(4);
                var precincts = new int[levels + 1];
                for (var r = 0; r <= levels; r++) {
                    precincts[r] = (r == 0 ? 0 : 1) + random.nextInt(5);
                }
                var coding = new ComponentCoding(levels + 1, blockWidth, blockHeight, 0, 1, 0, precincts, precincts,
                        0, 0, anchor);
                var component = new TileComponent(x0, y0, x1, y1, coding,
                        new Quantization(Quantization.NONE, 2, new int[Quantization.MAX_BANDS],
                                new int[Quantization.MAX_BANDS]), 8, levels + 1, false, MediaLimits.DEFAULT);
                var zx = anchor & 1;
                var zy = anchor >> 1;
                for (var r = 0; r <= levels; r++) {
                    var resolution = component.resolutions[r];
                    for (var band : resolution.bands()) {
                        var actual = new ArrayList<List<Long>>();
                        for (var precinct : band.precincts()) {
                            for (var block : precinct.codeBlocks) {
                                actual.add(List.of(block.x0, block.y0, block.x1, block.y1));
                            }
                        }
                        var expected = expectedBlocks(x0, y0, x1, y1, levels, r, band.orientation(), precincts[r],
                                blockWidth, blockHeight, zx, zy);
                        assertEquals(expected, actual, "seed " + seed + " resolution " + r + " band "
                                + band.orientation());
                    }
                }
            }
        }

        /**
         * The code-blocks of a sub-band, precinct by precinct in raster order: the resolution's precincts start at
         * {@code z + m 2^PP} (I.2.1); a sub-band high-pass along an axis is anchored at 0 along it and a low-pass one
         * at {@code z}, its precincts start at its anchor plus {@code m 2^(PP - 1)} and its code-blocks at its anchor
         * plus multiples of their size, clipped to the precinct.
         */
        private static List<List<Long>> expectedBlocks(long x0, long y0, long x1, long y1, int levels, int r,
                                                       int orientation, int precinct, int blockWidth,
                                                       int blockHeight, int zx, int zy) {
            var level = levels - r;
            var rx0 = ceil(x0, level);
            var ry0 = ceil(y0, level);
            var rx1 = ceil(x1, level);
            var ry1 = ceil(y1, level);
            var split = r == 0 ? 0 : 1;
            var xob = orientation & 1;
            var yob = orientation >> 1;
            long bx0 = r == 0 ? rx0 : ceil(x0 - ((long) xob << level), level + 1);
            long by0 = r == 0 ? ry0 : ceil(y0 - ((long) yob << level), level + 1);
            long bx1 = r == 0 ? rx1 : ceil(x1 - ((long) xob << level), level + 1);
            long by1 = r == 0 ? ry1 : ceil(y1 - ((long) yob << level), level + 1);
            var ax = r != 0 && xob == 1 ? 0 : zx;
            var ay = r != 0 && yob == 1 ? 0 : zy;
            var size = 1L << precinct;
            var bandSize = 1L << (precinct - split);
            var blocks = new ArrayList<List<Long>>();
            if (rx1 <= rx0 || ry1 <= ry0) {
                return blocks;
            }
            var firstM = Math.floorDiv(rx0 - zx, size);
            var lastM = Math.floorDiv(rx1 - 1 - zx, size);
            var firstN = Math.floorDiv(ry0 - zy, size);
            var lastN = Math.floorDiv(ry1 - 1 - zy, size);
            var cw = 1L << Math.min(blockWidth, precinct - split);
            var ch = 1L << Math.min(blockHeight, precinct - split);
            for (var n = firstN; n <= lastN; n++) {
                for (var m = firstM; m <= lastM; m++) {
                    var px0 = Math.max(bx0, ax + m * bandSize);
                    var py0 = Math.max(by0, ay + n * bandSize);
                    var px1 = Math.min(bx1, ax + (m + 1) * bandSize);
                    var py1 = Math.min(by1, ay + (n + 1) * bandSize);
                    if (px1 <= px0 || py1 <= py0) {
                        continue;
                    }
                    for (var cy = ay + Math.floorDiv(py0 - ay, ch) * ch; cy < py1; cy += ch) {
                        for (var cx = ax + Math.floorDiv(px0 - ax, cw) * cw; cx < px1; cx += cw) {
                            blocks.add(List.of(Math.max(cx, px0), Math.max(cy, py0), Math.min(cx + cw, px1),
                                    Math.min(cy + ch, py1)));
                        }
                    }
                }
            }
            return blocks;
        }

        private static long ceil(long value, int shift) {
            return Math.ceilDiv(value, 1L << shift);
        }
    }

    @Nested
    @DisplayName("segments")
    class Segments {
        private static byte[] codestream(int extensions) {
            var random = new Random(4);
            var writer = new Part2Writer(24, 20, 1, 8, false);
            writer.levels = 2;
            writer.codeBlockStyle = ComponentCoding.BYPASS;
            writer.extensions = extensions;
            writer.independentCoder = true;
            var plane = Jp2Part2Test.image(random, 24 * 20, 0, 255);
            return writer.write(Jp2Part2Test.forward(writer, new long[][]{plane}, Jp2Part2Test.levelShifts(writer),
                    Tree.dyadic(2), null));
        }

        @ParameterizedTest(name = "bypass after {0} bit-planes")
        @ValueSource(ints = {1, 2, 3, 4})
        @DisplayName("the first codeword segment holds the arithmetic-coded bit-planes, the later ones alternate 2 raw "
                + "passes and 1 cleanup pass")
        void segmentPasses(int planes) {
            var block = new CodeBlock(0, 0, 4, 4);
            for (var segment = 0; segment < 7; segment++) {
                block.startSegment(segment, ComponentCoding.BYPASS, segment == 0, planes);
                var expected = segment == 0 ? 3 * planes - 2 : segment % 2 == 1 ? 2 : 1;
                assertEquals(expected, block.segmentMaxPasses[segment], "segment " + segment);
            }
            var terminated = new CodeBlock(0, 0, 4, 4);
            for (var segment = 0; segment < 3; segment++) {
                terminated.startSegment(segment, ComponentCoding.BYPASS | ComponentCoding.TERMINATE_ALL,
                        segment == 0, planes);
                assertEquals(1, terminated.segmentMaxPasses[segment], "terminated segment " + segment);
            }
        }

        @ParameterizedTest(name = "SXcod {0}")
        @ValueSource(ints = {0x0004, 0x0100, 0x8003})
        @DisplayName("reserved SXcod bits are malformed")
        void reserved(int extensions) {
            assertThrows(MediaException.Malformed.class, () -> Jp2Part2Test.decode(codestream(extensions)));
        }

        @Test
        @DisplayName("a COD segment that declares SXcod without holding it is malformed")
        void truncated() {
            var codestream = codestream(2);
            var body = segment(codestream, Codestream.COD);
            var length = segmentLength(codestream, body);
            var out = new ByteArrayOutputStream();
            out.write(codestream, 0, body - 2);
            Part2Forward.u16(out, length);
            out.write(codestream, body, length - 2);
            out.write(codestream, body + length, codestream.length - body - length);
            assertThrows(MediaException.Malformed.class, () -> Jp2Part2Test.decode(out.toByteArray()));
        }

        @Test
        @DisplayName("the anchor and block coder extension flags are reserved in a codestream of ISO/IEC 15444-1")
        void partOne() {
            for (var flags : new int[]{0x08, 0x10, 0x20}) {
                var writer = new Part2Writer(16, 16, 1, 8, false);
                writer.rsiz = 0;
                writer.levels = 1;
                var codestream = writer.write(Jp2Part2Test.forward(writer,
                        new long[][]{Jp2Part2Test.image(new Random(flags), 256, 0, 255)},
                        Jp2Part2Test.levelShifts(writer), Tree.dyadic(1), null));
                codestream[segment(codestream, Codestream.COD)] |= (byte) flags;
                assertThrows(MediaException.Malformed.class, () -> Jp2Part2Test.decode(codestream));
            }
        }

        @Test
        @DisplayName("Scoc anchor bits are ignored: the tile's anchor point comes from Scod")
        void cocAnchor() {
            var random = new Random(6);
            var writer = new Part2Writer(30, 26, 1, 8, false);
            writer.levels = 2;
            writer.codeBlock = 2;
            writer.anchor = ComponentCoding.ANCHOR_X;
            writer.precinctSizes = new int[]{3, 3, 3};
            var plane = Jp2Part2Test.smooth(random, 30, 26, 0, 255);
            var coc = new ByteArrayOutputStream();
            coc.write(0);
            coc.write(0x19);
            coc.write(2);
            coc.write(0);
            coc.write(0);
            coc.write(0);
            coc.write(1);
            for (var r = 0; r < 3; r++) {
                coc.write(0x33);
            }
            writer.mainSegments.add(Part2Forward.segment(0xFF53, coc.toByteArray()));
            var codestream = writer.write(Jp2Part2Test.forward(writer, new long[][]{plane},
                    Jp2Part2Test.levelShifts(writer), Tree.dyadic(2), null));
            Jp2Part2Test.assertChannels(new long[][]{plane}, Jp2Part2Test.decode(codestream));
        }
    }
}
