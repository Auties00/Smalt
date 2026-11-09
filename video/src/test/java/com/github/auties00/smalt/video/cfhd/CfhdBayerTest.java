package com.github.auties00.smalt.video.cfhd;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the CineForm RAW vectors of tools/testdata/cfhd/make-vectors.sh, written by GoPro's encoder from synthetic
 * mosaics in every Bayer phase, under every encode curve and from crops of a CC0 camera raw, and holds the mosaics to
 * GoPro's 16-bit Bayer decode and the demosaiced pictures to its 16-bit RGB decode.
 */
@DisplayName("CineForm RAW against GoPro's decoder")
class CfhdBayerTest {
    static final CfhdDecoderOptions MOSAIC = CfhdDecoderOptions.builder().bayerOutput(CfhdBayerOutput.MOSAIC).build();

    /**
     * One sample of a vector with GoPro's BYR4 decode (16-bit photosites) and RG48 decode (16-bit R, G, B), the
     * latter empty when the vector leaves it out.
     */
    record Entry(byte[] sample, int[] mosaic, int[] rgb) {
    }

    static List<Entry> vector(String name) throws IOException, NoSuchAlgorithmException {
        byte[] data;
        try (var in = CfhdBayerTest.class.getResourceAsStream("sdk/" + name);
             var pins = CfhdBayerTest.class.getResourceAsStream("sdk.sha256")) {
            data = in.readAllBytes();
            var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            assertTrue(new String(pins.readAllBytes(), StandardCharsets.US_ASCII).contains(digest + " *" + name),
                    name + " does not match its pinned SHA-256");
        }
        assertEquals("CFHDBYR1", new String(data, 0, 8, StandardCharsets.US_ASCII));
        var buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).position(8);
        var entries = new ArrayList<Entry>();
        while (buffer.hasRemaining()) {
            var sample = new byte[buffer.getInt()];
            buffer.get(sample);
            var mosaic = new int[buffer.getInt() / 2];
            for (var i = 0; i < mosaic.length; i++) {
                mosaic[i] = buffer.getShort() & 0xFFFF;
            }
            var rgb = new int[buffer.getInt() / 2];
            for (var i = 0; i < rgb.length; i++) {
                rgb[i] = buffer.getShort() & 0xFFFF;
            }
            entries.add(new Entry(sample, mosaic, rgb));
        }
        return entries;
    }

    static VideoTrack track(int width, int height) {
        return VideoTrack.builder().id(1).codec(Codec.CFHD).timeBase(new Rational(1, 600)).width(width)
                .height(height).build();
    }

    static int[] mosaic(Yuv16Picture picture) {
        var out = new int[picture.width() * picture.height()];
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width(); x++) {
                out[y * picture.width() + x] = picture.y()[y * picture.yStride() + x] & 0xFFFF;
            }
        }
        return out;
    }

    static int[] rgb(Rgba16Picture picture) {
        var out = new int[picture.width() * picture.height() * 3];
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width(); x++) {
                for (var c = 0; c < 3; c++) {
                    out[(y * picture.width() + x) * 3 + c] = picture.pixels()[y * picture.stride() + x * 4 + c]
                            & 0xFFFF;
                }
                assertEquals((short) 0xFFFF, picture.pixels()[y * picture.stride() + x * 4 + 3]);
            }
        }
        return out;
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"bayer-rggb-filmscan1.cfhd,128,64,RGGB", "bayer-grbg-high.cfhd,128,64,GRBG",
            "bayer-gbrg-medium.cfhd,128,64,GBRG", "bayer-bggr-low.cfhd,128,64,BGGR",
            "bayer-rggb-176x90-filmscan1.cfhd,176,90,RGGB", "bayer-grbg-208x120-high.cfhd,208,120,GRBG",
            "bayer-gamma22.cfhd,128,64,RGGB", "bayer-cineon17.cfhd,128,64,GRBG", "bayer-cine985.cfhd,128,64,GBRG",
            "bayer-redspace.cfhd,128,64,BGGR", "bayer-cineon10.cfhd,128,64,RGGB", "bayer-slog.cfhd,128,64,GRBG",
            "bayer-logc.cfhd,128,64,GBRG", "bayer-protune.cfhd,128,64,BGGR", "bayer-log12stop.cfhd,128,64,RGGB",
            "bayer-preset.cfhd,128,64,GBRG", "bayer-canon-rggb-filmscan1.cfhd,256,128,RGGB",
            "bayer-canon-bggr-medium.cfhd,160,96,BGGR"})
    @DisplayName("decodes mosaics bit-exact with GoPro's 16-bit Bayer output and reports their phase")
    void mosaics(String name, int width, int height, CfhdBayerPhase phase) throws Exception {
        var decoder = new CfhdDecoder(track(width, height), MOSAIC);
        var frames = 0;
        for (var entry : vector(name)) {
            var picture = assertInstanceOf(Yuv16Picture.class, decoder.decodeFrame(entry.sample(), 0,
                    entry.sample().length));
            frames++;
            assertEquals(width, picture.width(), name);
            assertEquals(height, picture.height(), name);
            assertEquals(ChromaFormat.MONOCHROME, picture.chromaFormat(), name);
            assertEquals(16, picture.bitDepth(), name);
            assertEquals(name.contains("preset") ? TransferCharacteristics.UNSPECIFIED
                    : TransferCharacteristics.LINEAR, picture.color().transfer(), name);
            assertEquals(phase, decoder.bayerPhase().orElseThrow(), name);
            Tolerance.exact().check(name + " frame " + frames, Tolerance.Difference.of(entry.mosaic(),
                    mosaic(picture)));
        }
        assertTrue(frames > 0, name);
    }

    @Test
    @DisplayName("keeps the photosites of a linear-curve source at full scale, four times GoPro's 14-bit output")
    void linearCurve() throws Exception {
        var decoder = new CfhdDecoder(track(128, 64), MOSAIC);
        for (var entry : vector("bayer-linear.cfhd")) {
            var picture = (Yuv16Picture) decoder.decodeFrame(entry.sample(), 0, entry.sample().length);
            assertEquals(TransferCharacteristics.LINEAR, picture.color().transfer());
            var ours = mosaic(picture);
            var reduced = new int[ours.length];
            for (var i = 0; i < ours.length; i++) {
                reduced[i] = ours[i] >> 2;
            }
            Tolerance.exact().check("linear curve", Tolerance.Difference.of(entry.mosaic(), reduced));
            assertTrue(Arrays.stream(ours).max().orElseThrow() > 16383, "the mosaic spans 16 bits");
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"bayer-rggb-filmscan1.cfhd,128,64,RGGB", "bayer-grbg-high.cfhd,128,64,GRBG",
            "bayer-gbrg-medium.cfhd,128,64,GBRG", "bayer-bggr-low.cfhd,128,64,BGGR",
            "bayer-rggb-176x90-filmscan1.cfhd,176,90,RGGB", "bayer-grbg-208x120-high.cfhd,208,120,GRBG",
            "bayer-preset.cfhd,128,64,GBRG", "bayer-canon-rggb-filmscan1.cfhd,256,128,RGGB",
            "bayer-canon-bggr-medium.cfhd,160,96,BGGR"})
    @DisplayName("demosaics as GoPro's 16-bit RGB output does")
    void demosaiced(String name, int width, int height, CfhdBayerPhase phase) throws Exception {
        var decoder = new CfhdDecoder(track(width, height), CfhdDecoderOptions.defaults());
        var frames = 0;
        for (var entry : vector(name)) {
            var picture = assertInstanceOf(Rgba16Picture.class, decoder.decodeFrame(entry.sample(), 0,
                    entry.sample().length));
            frames++;
            assertEquals(width, picture.width(), name);
            assertEquals(height, picture.height(), name);
            assertEquals(phase, decoder.bayerPhase().orElseThrow(), name);
            Tolerance.exact().check(name + " frame " + frames, Tolerance.Difference.of(entry.rgb(), rgb(picture)));
        }
        assertTrue(frames > 0, name);
    }

    @Test
    @DisplayName("agrees with ffmpeg's coded mosaic of the behavior sample once that is linearized by the default curve")
    void ffmpeg() throws Exception {
        com.github.auties00.smalt.testkit.Oracle.assume(com.github.auties00.smalt.testkit.Oracle.Tool.FFMPEG);
        var file = java.nio.file.Path.of("src", "test", "resources", "samples", "avi", "cfhd-bayer.avi");
        // ffmpeg returns the photosites in the domain of the encode curve, as the sample codes them
        var coded = com.github.auties00.smalt.testkit.Oracle.run(com.github.auties00.smalt.testkit.Oracle.Tool.FFMPEG,
                java.time.Duration.ofMinutes(2), List.of("-v", "error", "-i", file.toString(), "-frames:v", "2", "-f",
                        "rawvideo", "-pix_fmt", "bayer_rggb16le", "-"));
        var restore = com.github.auties00.smalt.video.cfhd.internal.CfhdBayer.restoreTable(0);
        var decoder = new CfhdDecoder(track(128, 64), MOSAIC);
        var entries = vector("bayer-rggb-filmscan1.cfhd");
        assertEquals(2 * 128 * 64 * 2, coded.length);
        for (var f = 0; f < 2; f++) {
            var sample = entries.get(f).sample();
            var ours = mosaic((Yuv16Picture) decoder.decodeFrame(sample, 0, sample.length));
            var theirs = new int[ours.length];
            for (var i = 0; i < theirs.length; i++) {
                var at = (f * theirs.length + i) * 2;
                theirs[i] = restore[((coded[at] & 0xFF) | (coded[at + 1] & 0xFF) << 8) >> 2];
            }
            // ffmpeg rounds the inverse wavelets unlike GoPro's decoder by a few coded levels, which the curve
            // magnifies near white (measured: at most 359, mean 31)
            Tolerance.maxAbs(512).and(Tolerance.meanAbs(48)).check("ffmpeg frame " + f,
                    Tolerance.Difference.of(theirs, ours));
        }
    }

    @Test
    @DisplayName("demosaics through the codec registry and delivers frames with the packet's timestamps")
    void registry() throws Exception {
        var entries = vector("bayer-grbg-high.cfhd");
        var decoder = VideoDecoders.create(track(128, 64), VideoDecoderOptions.defaults());
        var cineform = assertInstanceOf(CfhdDecoder.class, decoder);
        var frames = new ArrayList<VideoFrame>();
        for (var index = 0; index < entries.size(); index++) {
            var sample = entries.get(index).sample();
            decoder.decode(new Packet(1, sample, index * 20L, index * 20L, 20, true), frames::add);
        }
        assertEquals(entries.size(), frames.size());
        for (var index = 0; index < frames.size(); index++) {
            assertEquals(index * 20L, frames.get(index).pts());
            Tolerance.exact().check("registry frame " + index, Tolerance.Difference.of(entries.get(index).rgb(),
                    rgb(assertInstanceOf(Rgba16Picture.class, frames.get(index).picture()))));
        }
        assertEquals(CfhdBayerPhase.GRBG, cineform.bayerPhase().orElseThrow());
    }

    @Test
    @DisplayName("follows the phase from sample to sample, keeps it when a sample names none, and forgets it for "
            + "other formats")
    void phaseFollowsTheSamples() throws Exception {
        var decoder = new CfhdDecoder(track(128, 64), MOSAIC);
        assertTrue(decoder.bayerPhase().isEmpty());
        var bggr = vector("bayer-bggr-low.cfhd").getFirst();
        decoder.decodeFrame(bggr.sample(), 0, bggr.sample().length);
        assertEquals(CfhdBayerPhase.BGGR, decoder.bayerPhase().orElseThrow());
        var rggb = vector("bayer-rggb-filmscan1.cfhd").getFirst();
        decoder.decodeFrame(rggb.sample(), 0, rggb.sample().length);
        assertEquals(CfhdBayerPhase.RGGB, decoder.bayerPhase().orElseThrow());
        // without its BFMT entry the GBRG sample decodes in the phase of the sample before it, as GoPro's decoder does
        var gbrg = vector("bayer-gbrg-medium.cfhd").getFirst();
        var unnamed = rename(gbrg.sample(), "BFMT", "XXXX");
        var picture = (Yuv16Picture) decoder.decodeFrame(unnamed, 0, unnamed.length);
        assertEquals(CfhdBayerPhase.RGGB, decoder.bayerPhase().orElseThrow());
        assertNotEquals(Arrays.toString(gbrg.mosaic()), Arrays.toString(mosaic(picture)));
        var yuv = CfhdDifferentialTest.vector("yuv422-intra-filmscan1.cfhd").getFirst();
        decoder.decodeFrame(yuv[0], 0, yuv[0].length);
        assertTrue(decoder.bayerPhase().isEmpty());
    }

    @Test
    @DisplayName("rejects a phase outside the four arrangements")
    void badPhase() throws Exception {
        var sample = vector("bayer-rggb-filmscan1.cfhd").getFirst().sample().clone();
        var at = indexOf(sample, "BFMT".getBytes(StandardCharsets.US_ASCII));
        sample[at + 8] = 7;
        var decoder = new CfhdDecoder(track(128, 64), MOSAIC);
        assertThrows(MediaException.Malformed.class, () -> decoder.decodeFrame(sample, 0, sample.length));
    }

    @Test
    @DisplayName("raises Unsupported for an uncompressed sample")
    void uncompressed() {
        byte[] sample = {0, 1, 0, 9, 0, 84, 0, 2, 0x23, 0, 0, 1, 0, 0, 0, 0};
        var decoder = new CfhdDecoder(track(128, 64), MOSAIC);
        assertThrows(MediaException.Unsupported.class, () -> decoder.decodeFrame(sample, 0, sample.length));
    }

    @Test
    @DisplayName("builds options with the documented defaults and round trips them through their builder")
    void options() {
        var defaults = CfhdDecoderOptions.defaults();
        assertEquals(CfhdBayerOutput.DEMOSAICED, defaults.bayerOutput());
        assertEquals(defaults, defaults.toBuilder().build());
        assertEquals(MOSAIC, MOSAIC.toBuilder().build());
        assertEquals(MOSAIC.hashCode(), MOSAIC.toBuilder().build().hashCode());
        assertNotEquals(defaults, MOSAIC);
        assertThrows(NullPointerException.class, () -> CfhdDecoderOptions.builder().bayerOutput(null));
        assertThrows(NullPointerException.class, () -> CfhdDecoderOptions.builder().limits(null));
        assertEquals(0, defaults.threads());
        var single = CfhdDecoderOptions.builder().threads(1).build();
        assertEquals(1, single.threads());
        assertEquals(single, single.toBuilder().build());
        assertNotEquals(defaults, single);
        assertThrows(IllegalArgumentException.class, () -> CfhdDecoderOptions.builder().threads(-1).build());
        var fromVideo = new CfhdDecoder(track(128, 64), VideoDecoderOptions.builder().threads(1).build());
        assertTrue(fromVideo.bayerPhase().isEmpty());
    }

    @ParameterizedTest(name = "threads {0}")
    @ValueSource(ints = {1, 2, 0})
    @DisplayName("demosaics as GoPro's 16-bit RGB output does whatever the thread count")
    void threads(int threads) throws Exception {
        var entry = vector("bayer-canon-rggb-filmscan1.cfhd").getFirst();
        var decoder = new CfhdDecoder(track(256, 128), CfhdDecoderOptions.builder().threads(threads).build());
        Tolerance.exact().check("threads " + threads, Tolerance.Difference.of(entry.rgb(),
                rgb((Rgba16Picture) decoder.decodeFrame(entry.sample(), 0, entry.sample().length))));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"MOSAIC", "DEMOSAICED"})
    @DisplayName("raises only the documented exceptions for truncated and damaged CineForm RAW samples")
    void damaged(CfhdBayerOutput output) throws Exception {
        var data = vector("bayer-canon-bggr-medium.cfhd").getFirst().sample();
        var decoder = new CfhdDecoder(track(160, 96), CfhdDecoderOptions.builder().bayerOutput(output).build());
        var random = new Random(17);
        for (var length = 0; length < data.length; length += Math.max(1, data.length / 97)) {
            var cut = Arrays.copyOf(data, length);
            decodeTolerating(decoder, cut);
        }
        for (var flip = 0; flip < 400; flip++) {
            var damaged = data.clone();
            damaged[random.nextInt(damaged.length)] ^= (byte) (1 << random.nextInt(8));
            decodeTolerating(decoder, damaged);
        }
        // the decoder still decodes the intact sample as GoPro's decoder does afterwards
        var entry = vector("bayer-canon-bggr-medium.cfhd").getFirst();
        var picture = decoder.decodeFrame(entry.sample(), 0, entry.sample().length);
        Tolerance.exact().check("intact sample after damage", Tolerance.Difference.of(
                output == CfhdBayerOutput.MOSAIC ? entry.mosaic() : entry.rgb(), sdkLayout(picture)));
        assertEquals(CfhdBayerPhase.BGGR, decoder.bayerPhase().orElseThrow());
    }

    private static void decodeTolerating(CfhdDecoder decoder, byte[] data) {
        try {
            decoder.decodeFrame(data, 0, data.length);
        } catch (MediaException.Malformed | MediaException.Unsupported | MediaException.LimitExceeded expected) {
            // a damaged sample may fail; anything but a MediaException fails the test
        }
    }

    private static int[] sdkLayout(Picture picture) {
        return switch (picture) {
            case Yuv16Picture yuv -> mosaic(yuv);
            case Rgba16Picture rgba -> rgb(rgba);
            default -> throw new IllegalArgumentException(picture.toString());
        };
    }

    @Test
    @DisplayName("decodes the same mosaic and picture again from a reused decoder")
    void reuse() throws Exception {
        var entry = vector("bayer-canon-rggb-filmscan1.cfhd").getFirst();
        var decoder = new CfhdDecoder(track(256, 128), CfhdDecoderOptions.defaults());
        var first = rgb((Rgba16Picture) decoder.decodeFrame(entry.sample(), 0, entry.sample().length));
        var small = vector("bayer-gamma22.cfhd").getFirst();
        decoder.decodeFrame(small.sample(), 0, small.sample().length);
        var again = rgb((Rgba16Picture) decoder.decodeFrame(entry.sample(), 0, entry.sample().length));
        assertArrayEquals(first, again);
    }

    private static byte[] rename(byte[] sample, String from, String to) {
        var copy = sample.clone();
        var at = indexOf(copy, from.getBytes(StandardCharsets.US_ASCII));
        System.arraycopy(to.getBytes(StandardCharsets.US_ASCII), 0, copy, at, 4);
        return copy;
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (var i = 0; i + pattern.length <= data.length; i++) {
            for (var j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        throw new AssertionError("pattern not found");
    }
}
