package com.github.auties00.smalt.video.mpeg4;

import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Quality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static com.github.auties00.smalt.video.mpeg4.InterlacedDirectStream.Picture;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks interlaced direct mode (ISO/IEC 14496-2 7.7.2.2) on streams written by
 * {@link InterlacedDirectStream}, whose B-VOPs carry no residual: the I- and P-VOPs must match
 * ffmpeg's decode at the IDCT noise floor, and every B-VOP must equal, sample for sample, the
 * prediction that a model of 7.7.2.2 and field motion compensation (7.7.2.1) written here forms
 * from the decoded references. ffmpeg's encoder does not use interlaced direct mode (it predicts
 * such macroblocks as progressive direct ones) and its decoder scales zero co-located vectors,
 * so neither can serve as the oracle for these B-VOPs. The ISO/IEC 14496-5 reference decoder
 * ({@code tools/testdata/mpeg4-oracle}) implements 7.7.2.2 with one deviation: it measures the temporal distances
 * {@code TRB} and {@code TRD} in VOP time ticks instead of field periods of the frame period {@code Tframe}, which
 * agree only when a frame lasts one tick. On the streams whose VOP times are frame numbers every picture must match
 * its decode at the IDCT noise floor, which also checks the model. With {@code -Dsmalt.test.mfDecode} naming
 * the Media Foundation decoder driver of {@code tools/testdata/mf-oracle}, the streams whose VOP
 * times are frame numbers are also compared with the Windows MPEG-4 Part 2 decoder, which filters
 * its output and therefore matches within a few sample values.
 */
@DisplayName("MPEG-4 Part 2 interlaced direct mode")
class Mpeg4InterlacedDirectTest {
    private static final int WIDTH = 176;
    private static final int HEIGHT = 144;
    private static final int RESOLUTION = 25;
    private static final int FRAME = WIDTH * HEIGHT * 3 / 2;

    record Case(String name, long seed, boolean topFieldFirst, int[][] gops, boolean frameTicks) {
        @Override
        public String toString() {
            return name;
        }
    }

    // Each row is a P-VOP time followed by the times of the B-VOPs it closes; a row of one time is
    // a P-VOP without B-VOPs.
    static Stream<Case> cases() {
        return Stream.of(
                new Case("bottom field first", 1, false, new int[][]{{3, 1, 2}, {6, 4, 5}}, true),
                new Case("top field first", 2, true, new int[][]{{3, 1, 2}, {6, 4, 5}}, true),
                new Case("top field first, other vectors", 3, true, new int[][]{{3, 1, 2}, {6, 4, 5}}, true),
                new Case("forward reference a P-VOP", 4, false, new int[][]{{1}, {4, 2, 3}}, true),
                new Case("two ticks per frame", 5, true, new int[][]{{8, 2, 4, 6}, {16, 10, 12, 14}}, false),
                new Case("irregular times", 6, false, new int[][]{{9, 3, 5}, {17, 11, 12, 16}}, false)
        );
    }

    static Stream<Case> frameTickCases() {
        return cases().filter(Case::frameTicks);
    }

    static InterlacedDirectStream stream(Case c) {
        var intro = Fixtures.ffmpeg("m4v", List.of("-f", "lavfi", "-i", "nullsrc=size=" + WIDTH + "x" + HEIGHT
                        + ":rate=" + RESOLUTION + ",geq=lum=mod(X*37+Y*101+X*Y*7\\,251)"
                        + ":cb=mod(X*11+Y*3\\,200)+28:cr=mod(X*5+Y*17\\,200)+28"),
                List.of("-frames:v", "1", "-c:v", "mpeg4", "-bf", "2", "-flags", "+ildct+ilme", "-q:v", "2", "-f", "m4v"));
        var stream = new InterlacedDirectStream(InterlacedDirectStream.intro(Fixtures.read(intro)), WIDTH, HEIGHT,
                RESOLUTION);
        var random = new Random(c.seed());
        for (var gop : c.gops()) {
            stream.p(gop[0], c.topFieldFirst(), InterlacedDirectStream.randomBlocks(random));
            for (var i = 1; i < gop.length; i++) {
                stream.b(gop[i], c.topFieldFirst(), InterlacedDirectStream.randomDeltas(random));
            }
        }
        return stream;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("predicts direct macroblocks from the scaled co-located field vectors")
    void matchesModel(Case c, @TempDir Path directory) throws IOException {
        var stream = stream(c);
        var file = directory.resolve("direct.m4v");
        Files.write(file, stream.bytes());
        var decoded = Mpeg4TestStreams.decodeElementary(file, Mpeg4Decoder::new);
        var pictures = stream.pictures();
        assertEquals(pictures.size() + 1, decoded.frames());
        var times = new ArrayList<Integer>();
        times.add(0);
        pictures.forEach(picture -> times.add(picture.time()));
        times.sort(null);
        var raw = decoded.raw();
        // -cpuflags 0: the reference is ffmpeg's C inverse DCT, which its arm64 NEON kernels do not reproduce.
        var expected = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-cpuflags", "0",
                "-idct", "int", "-f", "m4v", "-i", file.toString(), "-f", "rawvideo", "-pix_fmt", "yuv420p", "-"));
        assertEquals(raw.length, expected.length);
        var anchors = new ArrayList<Integer>(List.of(0));
        var framePeriod = 0;
        var directBlocks = 0;
        for (var picture : pictures) {
            var display = times.indexOf(picture.time());
            if (!picture.b()) {
                anchors.add(picture.time());
                var psnr = Quality.psnr(frame(expected, display), frame(raw, display));
                assertTrue(psnr >= 60, "P-VOP at " + picture.time() + ": " + psnr + " dB against ffmpeg");
                continue;
            }
            var past = anchors.get(anchors.size() - 2);
            var future = anchors.getLast();
            if (framePeriod == 0) {
                framePeriod = picture.time() - past;
            }
            var model = predict(picture, frame(raw, times.indexOf(past)), frame(raw, times.indexOf(future)),
                    framesBetween(past, picture.time(), framePeriod), framesBetween(past, future, framePeriod));
            assertArrayEquals(model, frame(raw, display), c.name() + ", B-VOP at " + picture.time());
            directBlocks += picture.blocks().size();
        }
        assertTrue(directBlocks >= 12);
        var mf = System.getProperty("smalt.test.mfDecode");
        if (mf != null && c.frameTicks()) {
            compareWithMediaFoundation(Path.of(mf), file, raw, directory);
        }
    }

    private static void compareWithMediaFoundation(Path driver, Path file, byte[] raw, Path directory) {
        Skip.unless(Files.isRegularFile(driver), "opt-in:mf-decode", "no Media Foundation driver at " + driver);
        var mp4 = directory.resolve("direct.mp4");
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-y", "-r",
                Integer.toString(RESOLUTION), "-f", "m4v", "-i", file.toString(), "-c", "copy", mp4.toString()));
        var output = directory.resolve("mf.yuv");
        try {
            var process = new ProcessBuilder(driver.toString(), mp4.toString(), output.toString(),
                    Integer.toString(WIDTH), Integer.toString(HEIGHT)).redirectErrorStream(true).start();
            var log = new String(process.getInputStream().readAllBytes());
            assertEquals(0, process.waitFor(), log);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
        var reference = Fixtures.read(output);
        assertEquals(raw.length, reference.length);
        for (var i = 0; i < raw.length / FRAME; i++) {
            // The Windows decoder deblocks its output, which moves samples by up to about 4.
            var difference = Quality.maxDifference(frame(reference, i), frame(raw, i));
            assertTrue(difference <= 6, "picture " + i + " differs from Media Foundation by " + difference);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("frameTickCases")
    @DisplayName("decodes every picture like the ISO/IEC 14496-5 reference decoder")
    void matchesReferenceDecoder(Case c, @TempDir Path directory) throws IOException {
        var file = directory.resolve("direct.m4v");
        Files.write(file, stream(c).bytes());
        var decoded = Mpeg4TestStreams.decodeElementary(file, Mpeg4Decoder::new);
        var reference = Mpeg4TestStreams.reference(file, WIDTH, HEIGHT, directory);
        assertEquals(reference.length, decoded.raw().length, "decoded size against the reference decoder");
        for (var i = 0; i < decoded.frames(); i++) {
            // the two inverse DCTs drift by a few sample values at most: a wrong prediction costs tens of dB
            var psnr = Quality.psnr(frame(reference, i), frame(decoded.raw(), i));
            if (psnr < 50) {
                fail(c.name() + ", picture " + i + ": " + psnr + " dB against the reference decoder; macroblocks off by"
                        + " more than 8 (x,y luma:chroma): " + differing(frame(reference, i), frame(decoded.raw(), i))
                        + "; pictures " + stream(c).pictures());
            }
        }
    }

    /** Lists the macroblocks whose luminance or chrominance differ by more than 8 anywhere, with the differences. */
    private static String differing(byte[] expected, byte[] actual) {
        var out = new StringBuilder();
        for (var my = 0; my < HEIGHT / 16; my++) {
            for (var mx = 0; mx < WIDTH / 16; mx++) {
                var luma = 0;
                for (var y = my * 16; y < my * 16 + 16; y++) {
                    for (var x = mx * 16; x < mx * 16 + 16; x++) {
                        luma = Math.max(luma, Math.abs((expected[y * WIDTH + x] & 0xFF) - (actual[y * WIDTH + x] & 0xFF)));
                    }
                }
                var chroma = 0;
                for (var plane = 0; plane < 2; plane++) {
                    var base = WIDTH * HEIGHT + plane * (WIDTH / 2) * (HEIGHT / 2);
                    for (var y = my * 8; y < my * 8 + 8; y++) {
                        for (var x = mx * 8; x < mx * 8 + 8; x++) {
                            var at = base + y * (WIDTH / 2) + x;
                            chroma = Math.max(chroma, Math.abs((expected[at] & 0xFF) - (actual[at] & 0xFF)));
                        }
                    }
                }
                if (luma > 8 || chroma > 8) {
                    out.append(' ').append(mx).append(',').append(my).append(' ').append(luma).append(':').append(chroma);
                }
            }
        }
        return out.toString();
    }

    static int framesBetween(int earlier, int later, int period) {
        return (later + period / 2) / period - (earlier + period / 2) / period;
    }

    static byte[] frame(byte[] raw, int index) {
        return Arrays.copyOfRange(raw, index * FRAME, (index + 1) * FRAME);
    }

    /**
     * Forms a B-VOP of the stream: the co-located samples of the forward reference where the
     * backward reference skipped the macroblock, and the average of the two field predictions of
     * interlaced direct mode elsewhere.
     */
    static byte[] predict(Picture picture, byte[] past, byte[] future, int framesB, int framesD) {
        var out = past.clone();
        for (var i = 0; i < picture.blocks().size(); i++) {
            var block = picture.blocks().get(i);
            var delta = picture.deltas().get(i);
            var dx = delta == null ? 0 : delta.x();
            var dy = delta == null ? 0 : delta.y();
            int[][] fields = {
                    {block.topRef(), block.topX(), 2 * block.topY()},
                    {block.bottomRef(), block.bottomX(), 2 * block.bottomY()}
            };
            for (var field = 0; field < 2; field++) {
                var reference = fields[field][0];
                var mvx = fields[field][1];
                var mvy = fields[field][2];
                // Table 7-12: the referenced field lies one field period nearer or further.
                var correction = field == reference ? 0 : (field == 0) == picture.topFieldFirst() ? -1 : 1;
                var trd = 2 * framesD + correction;
                var trb = 2 * framesB + correction;
                var fx = trb * mvx / trd + dx;
                var fy = trb * mvy / trd + dy;
                var bx = dx == 0 ? (trb - trd) * mvx / trd : fx - mvx;
                var by = dy == 0 ? (trb - trd) * mvy / trd : fy - mvy;
                predictField(out, past, future, block.mx(), block.my(), field, reference, fx, Math.floorDiv(fy, 2),
                        bx, Math.floorDiv(by, 2));
            }
        }
        return out;
    }

    /**
     * Writes one field of a direct macroblock: the rounded average of the forward prediction from
     * field {@code reference} of the forward reference and of the backward prediction from the
     * same field of the backward reference, vertical vectors in half field lines.
     */
    static void predictField(byte[] out, byte[] past, byte[] future, int mx, int my, int field, int reference,
                             int fx, int fy, int bx, int by) {
        for (var plane = 0; plane < 3; plane++) {
            var chroma = plane > 0;
            var width = chroma ? WIDTH / 2 : WIDTH;
            var height = chroma ? HEIGHT / 2 : HEIGHT;
            var base = plane == 0 ? 0 : plane == 1 ? WIDTH * HEIGHT : WIDTH * HEIGHT + width * height;
            var size = chroma ? 8 : 16;
            var fvx = chroma ? (fx >> 1) | (fx & 1) : fx;
            var fvy = chroma ? (fy >> 1) | (fy & 1) : fy;
            var bvx = chroma ? (bx >> 1) | (bx & 1) : bx;
            var bvy = chroma ? (by >> 1) | (by & 1) : by;
            for (var row = 0; row < size / 2; row++) {
                var fieldRow = my * size / 2 + row;
                for (var column = 0; column < size; column++) {
                    var x = mx * size + column;
                    var forward = sample(past, base, width, height, reference, x, fieldRow, fvx, fvy);
                    var backward = sample(future, base, width, height, field, x, fieldRow, bvx, bvy);
                    out[base + (2 * fieldRow + field) * width + x] = (byte) ((forward + backward + 1) >> 1);
                }
            }
        }
    }

    /**
     * Returns a sample of a field predicted with a half sample vector and rounding control 0, the
     * field's edge samples extended outwards.
     */
    static int sample(byte[] picture, int base, int width, int height, int field, int x, int y, int vx, int vy) {
        var sx = x + (vx >> 1);
        var sy = y + (vy >> 1);
        var a = fieldSample(picture, base, width, height, field, sx, sy);
        var b = fieldSample(picture, base, width, height, field, sx + 1, sy);
        var c = fieldSample(picture, base, width, height, field, sx, sy + 1);
        var d = fieldSample(picture, base, width, height, field, sx + 1, sy + 1);
        return switch ((vx & 1) | (vy & 1) << 1) {
            case 0 -> a;
            case 1 -> (a + b + 1) >> 1;
            case 2 -> (a + c + 1) >> 1;
            default -> (a + b + c + d + 2) >> 2;
        };
    }

    static int fieldSample(byte[] picture, int base, int width, int height, int field, int x, int y) {
        x = Math.clamp(x, 0, width - 1);
        y = Math.clamp(y, 0, height / 2 - 1);
        return picture[base + (2 * y + field) * width + x] & 0xFF;
    }
}
