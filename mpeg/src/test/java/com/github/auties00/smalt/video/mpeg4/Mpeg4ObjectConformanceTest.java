package com.github.auties00.smalt.video.mpeg4;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the streams of the MPEG-4 Visual tools beyond the rectangular Simple and Advanced Simple layers: binary
 * shape (with the shape-adaptive DCT, interlacing, error resilience and global motion compensation), shape only
 * layers, grayscale alpha (with MPEG quantization and multiple auxiliary components), static sprites with 1 to 4
 * warping points (one stream rewritten to carry the sprite brightness change, which the reference encoder does not
 * code), N-bit samples (4, 10 and 12 bits), NEWPRED and reduced resolution VOPs.
 *
 * <p>The object decoder is a port of the ISO/IEC 14496-5 reference decoder, whose inverse DCT it shares, so every
 * stream must decode exactly like it. The streams under {@code object/} were encoded by the reference encoder of
 * ISO/IEC 14496-5 from synthetic content ({@code tools/testdata/mpeg4-object}); {@code object/golden.tsv} holds the
 * MD5s of the reference decoder's output for them, and where the reference decoder ({@code smalt-mpeg4ref}) is
 * installed its output is compared byte for byte too. The binary shape conformance streams of ISO/IEC 14496-4 Cor 1
 * and the demonstration streams of the reference software come from pinned archive sets.
 */
@DisplayName("MPEG-4 Visual object tools (ISO/IEC 14496-2 shape, alpha, sprites, N-bit, NEWPRED, RRV)")
class Mpeg4ObjectConformanceTest {
    private static final Tolerance EXACT = Tolerance.exact();
    private static final Set<String> TEMPORAL = Set.of("pv_scs12.bits", "pv_scs14.bits");

    @TempDir
    Path work;

    record Golden(String name, int width, int height, int depth, int frames, String yuv, String alpha) {
        @Override
        public String toString() {
            return name;
        }
    }

    record Decoded(byte[] yuv, byte[] alpha, int frames, int width, int height) {
    }

    static Stream<Golden> generated() {
        var rows = new ArrayList<Golden>();
        for (var line : resourceText("object/golden.tsv").split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            var f = line.strip().split("\t");
            rows.add(new Golden(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]),
                    Integer.parseInt(f[4]), f[5], f[6]));
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("generated")
    @DisplayName("self-encoded streams decode to the reference decoder's golden output")
    void golden(Golden golden) {
        var decoded = decode(resource("object/" + golden.name() + ".m4v"), golden.width(), golden.height(), true);
        assertEquals(golden.frames(), decoded.frames(), "frame count");
        assertEquals(golden.width(), decoded.width(), "width");
        assertEquals(golden.height(), decoded.height(), "height");
        assertEquals(golden.yuv(), md5(decoded.yuv()), "Y'CbCr MD5");
        assertEquals(golden.alpha(), decoded.alpha().length == 0 ? "-" : md5(decoded.alpha()), "alpha MD5");
    }

    static Stream<Golden> referenceDecodable() {
        // the 8-bit reference build decodes the 8-bit streams; it fails on shape only layers with B-VOPs, writes no
        // texture for shape only layers, and exits with an error on the NEWPRED stream on Linux
        return generated().filter(golden -> golden.depth() == 8 && !golden.name().equals("shape-only")
                && !golden.name().equals("shape-only-ip") && !golden.name().equals("newpred"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("referenceDecodable")
    @DisplayName("self-encoded streams decode exactly like the installed reference decoder")
    void reference(Golden golden) throws IOException {
        var stream = work.resolve(golden.name() + ".m4v");
        Files.write(stream, resource("object/" + golden.name() + ".m4v"));
        var reference = reference(stream, golden.width(), golden.height());
        var decoded = decode(Files.readAllBytes(stream), golden.width(), golden.height(), true);
        EXACT.check("Y'CbCr against the reference decoder", Tolerance.Difference.of(reference[0], decoded.yuv()));
        if (decoded.alpha().length > 0) {
            EXACT.check("alpha against the reference decoder", Tolerance.Difference.of(reference[1], decoded.alpha()));
        }
    }

    @Test
    @DisplayName("shape only layers with B-VOPs decode to their lossless shape")
    void shapeOnlyLossless() {
        var withB = decode(resource("object/shape-only.m4v"), 176, 144, true);
        var withoutB = decode(resource("object/shape-only-ip.m4v"), 176, 144, true);
        // both streams code the same lossless shape, with and without B-VOPs
        assertArrayEquals(withoutB.alpha(), withB.alpha());
        assertArrayEquals(withoutB.yuv(), withB.yuv());
    }

    @Test
    @DisplayName("the sprite brightness change scales the luminance of each warped sprite VOP")
    void spriteBrightnessChange() {
        // sprite-brightness.m4v is sprite-basic.m4v with a brightness_change_factor written into every S-VOP
        // (tools/testdata/mpeg4-object/sprite-brightness.py); the factors cover every code length of the table
        var plain = decode(resource("object/sprite-basic.m4v"), 176, 144, true);
        var changed = decode(resource("object/sprite-brightness.m4v"), 176, 144, true);
        var factors = resourceText("object/brightness.tsv").lines().filter(line -> !line.startsWith("#"))
                .mapToInt(Integer::parseInt).toArray();
        assertEquals(plain.frames(), factors.length, "sprite VOPs");
        assertEquals(plain.frames(), changed.frames(), "frame count");
        assertArrayEquals(plain.alpha(), changed.alpha(), "the shape is unchanged");
        var luma = 176 * 144;
        var frame = luma * 3 / 2;
        var expected = plain.yuv().clone();
        for (var i = 0; i < factors.length; i++) {
            // 7.8.7: Y' = clip(floor(Y * (1 + brightness_change_factor / 100) + 0.5)) of the warped luminance
            var scale = 1 + factors[i] / 100.0;
            for (var p = i * frame; p < i * frame + luma; p++) {
                expected[p] = (byte) Math.clamp((long) Math.floor((expected[p] & 0xFF) * scale + 0.5), 0, 255);
            }
        }
        assertArrayEquals(expected, changed.yuv(), "luminance scaled, chrominance unchanged");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"nbit12", "nbit10-shape"})
    @DisplayName("samples of more than 8 bits are reduced to 8 bits without high bit depth output")
    void eightBitOutput(String name) {
        var data = resource("object/" + name + ".m4v");
        var deep = new ArrayList<Picture>();
        var narrow = new ArrayList<Picture>();
        decodePictures(data, 176, 144, true, picture -> deep.add(copy(picture)));
        decodePictures(data, 176, 144, false, picture -> narrow.add(copy(picture)));
        assertEquals(deep.size(), narrow.size());
        for (var i = 0; i < deep.size(); i++) {
            var expected = ImageOps.toEightBit(deep.get(i));
            assertEquals(YuvPicture.class, narrow.get(i).getClass());
            EXACT.check("picture " + i, Tolerance.Difference.of(expected, narrow.get(i)));
        }
    }

    @Test
    @DisplayName("a shaped layer without a display size takes it from the extent of its first VOP")
    void displayFromFirstVop() throws IOException {
        var data = resource("object/shape-v1.m4v");
        var pictures = new ArrayList<Picture>();
        try (var source = MediaSource.of(data); var stream = Mpeg4ElementaryStream.open(source);
             var decoder = new Mpeg4Decoder(stream.track())) {
            Consumer<VideoFrame> sink = frame -> pictures.add(copy(frame.picture()));
            Packet packet;
            while ((packet = stream.read()) != null) {
                decoder.decode(packet, sink);
            }
            decoder.flush(sink);
        }
        assertEquals(10, pictures.size());
        var first = (YuvPicture) pictures.getFirst();
        var extent = firstVopExtent(data);
        assertEquals(extent[0], first.width());
        assertEquals(extent[1], first.height());
        // within the display, the pictures equal those of the 176x144 decode
        var full = decodePictureList(data, 176, 144);
        for (var i = 0; i < pictures.size(); i++) {
            var small = (YuvPicture) pictures.get(i);
            var large = (YuvPicture) full.get(i);
            for (var y = 0; y < small.height(); y++) {
                for (var x = 0; x < small.width(); x++) {
                    assertEquals(large.y()[y * large.yStride() + x], small.y()[y * small.yStride() + x]);
                    assertEquals(large.alpha()[y * large.alphaStride() + x],
                            small.alpha()[y * small.alphaStride() + x]);
                }
            }
        }
    }

    @Nested
    @DisplayName("conformance and reference software streams")
    class Archived {
        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "vcon-sh7-1_reva.cmp, 352, 288, 300, 4f12b002a15231dea41e0ecbfbecbbcf, d48ba16448dfb64753337ad724d17caf",
                "vcon-sh7-2_reva.cmp, 352, 288, 300, 828d4e4c1f6542f6c5f8ffdcb1ef651b, d48ba16448dfb64753337ad724d17caf",
                "vcon-sh8-1_reva.cmp, 352, 288, 300, e61ac2ade26dee77dd238de1ab8ea68d, aa3e9cfd9ef635e1e619d87499f02478",
                "vcon-sh8-2_reva.cmp, 352, 288, 300, 4602abaf8daf31b5a3a8c702fa7cd734, aa3e9cfd9ef635e1e619d87499f02478"
        })
        @DisplayName("the binary shape streams of ISO/IEC 14496-4 Cor 1 decode to the reference output")
        void binaryShape(String member, int width, int height, int frames, String yuv, String alpha) {
            var file = member("mpeg4visual-cor1-shape-scalable", member);
            checkArchived(file, width, height, frames, Set.of(), yuv, alpha);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({
                // the last VOP of brea_cif is cut short, and decoders read different bits past its end
                "brea_cif.cmp, 352, 288, 10, 8, 34c2f757aa70ebb413168e9422a6bbee, a12d0a7d01eb89c27fa6801bfa2f7903",
                "brea_qcif.cmp, 176, 144, 10, -1, 8986f57b170dfff974901f1d88a35b44, d4bcd14179b305c2087d5f17e5ef7657",
                "sprt_stef_sif.cmp, 352, 240, 8, -1, 7a746a20315221c65988c1b574f154d6, e282ba886c04715479e7a1e211649293"
        })
        @DisplayName("the demonstration streams of the reference software decode to the reference output")
        void demonstration(String member, int width, int height, int frames, int excluded, String yuv, String alpha) {
            var file = member("mpeg4visual-refsw-demo", member);
            checkArchived(file, width, height, frames, excluded < 0 ? Set.of() : Set.of(excluded), yuv, alpha);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({
                // spatial scalability: the base layer upsampled 16 times, 2 times, 3/2 by 2, and 1:1
                "mpeg4visual-cor1-shape-scalable, vcon-scs1_reva.bits, vcon-scs1_e_rev.bits, 256, 256, 10, -1, "
                        + "84aba391f4dac33c785a483e183d9450",
                "mpeg4visual-cor1-shape-scalable, vcon-scs2_reva.bits, vcon-scs2_e_reva.bits, 256, 256, 10, -1, "
                        + "2e799ccf46190ec1a3a380cafe402845",
                "mpeg4visual-cor1-shape-scalable, vcon-scs3_reva.bits, vcon-scs3_e_reva.bits, 256, 256, 10, -1, "
                        + "98abc41c362b97489ee616d8ab07a674",
                "mpeg4visual-cor1-shape-scalable, vcon-scs8_reva.bits, vcon-scs8_e_reva.bits, 352, 288, 75, -1, "
                        + "e939cd2a8452521829d4cf3281c07572",
                "mpeg4visual-cor1-shape-scalable, vcon-scs9_reva.bits, vcon-scs9_e_reva.bits, 528, 288, 90, -1, "
                        + "9c5612c9f37a91bdb776ca5e816ad353",
                "mpeg4visual-amd3-scalable, pv_scs13.bits, pv_scs13_e.bits, 176, 144, 100, -1, "
                        + "3ecd294254dec2c1dd1ea3f29c10c8e1",
                // the last VOP of the enhancement layer is cut short
                "mpeg4visual-amd3-scalable, pv_scs15.bits, pv_scs15_e.bits, 176, 144, 100, 99, "
                        + "1b3e27c9f8a3bbc1363e73a4c07a3fc7",
                // temporal scalability: the frames of both layers in display order; the Linux build of the reference
                // decoder aborts on these two (the Windows build wrote their MD5s), so they skip the oracle
                "mpeg4visual-amd3-scalable, pv_scs12.bits, pv_scs12_e.bits, 176, 144, 100, -1, "
                        + "629b01df416af2dedc4f4e6ac98e59d5",
                "mpeg4visual-amd3-scalable, pv_scs14.bits, pv_scs14_e.bits, 176, 144, 100, -1, "
                        + "789b11852a04dbaf596ecafbcb8eea12"
        })
        @DisplayName("the scalable streams of ISO/IEC 14496-4 decode to the reference output from their two layers")
        void scalable(String set, String baseMember, String enhancementMember, int width, int height, int frames,
                      int excluded, String yuv) throws IOException {
            var baseFile = member(set, baseMember);
            var enhancementFile = member(set, enhancementMember);
            var decoded = decodeLayers(Files.readAllBytes(baseFile), Files.readAllBytes(enhancementFile), 1);
            assertEquals(frames, decoded.frames(), "frame count");
            assertEquals(width, decoded.width(), "width");
            assertEquals(height, decoded.height(), "height");
            var kept = keep(decoded.yuv(), width * height * 3 / 2, frames,
                    excluded < 0 ? Set.of() : Set.of(excluded));
            assertEquals(yuv, md5(kept), "Y'CbCr MD5");
            // the output does not depend on how the packets of the two layers interleave
            var bursts = decodeLayers(Files.readAllBytes(baseFile), Files.readAllBytes(enhancementFile), 7);
            assertArrayEquals(decoded.yuv(), bursts.yuv(), "interleaving of the layers");
            if (!TEMPORAL.contains(baseMember) && Oracle.find(Oracle.Tool.MPEG4REF).isPresent()) {
                Oracle.assume(Oracle.Tool.MPEG4REF);
                var base = work.resolve(baseMember + ".ref");
                var size = layerSize(Files.readAllBytes(baseFile));
                Oracle.run(Oracle.Tool.MPEG4REF, Duration.ofMinutes(10), List.of(baseFile.toString(),
                        enhancementFile.toString(), base.toString(), Integer.toString(size[0]),
                        Integer.toString(size[1])));
                var reference = Files.readAllBytes(Path.of(base + "_e.yuv"));
                EXACT.check("Y'CbCr against the reference decoder", Tolerance.Difference.of(
                        keep(reference, width * height * 3 / 2, frames, excluded < 0 ? Set.of() : Set.of(excluded)),
                        kept));
            }
        }

        private void checkArchived(Path file, int width, int height, int frames, Set<Integer> excluded, String yuv,
                                   String alpha) {
            byte[] data;
            try {
                data = Files.readAllBytes(file);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            var decoded = decode(data, width, height, true);
            assertEquals(frames, decoded.frames(), "frame count");
            var lumaSize = width * height;
            var kept = keep(decoded.yuv(), lumaSize * 3 / 2, frames, excluded);
            var keptAlpha = keep(decoded.alpha(), lumaSize, frames, excluded);
            assertEquals(yuv, md5(kept), "Y'CbCr MD5");
            assertEquals(alpha, md5(keptAlpha), "alpha MD5");
            if (Oracle.find(Oracle.Tool.MPEG4REF).isPresent()) {
                var reference = reference(file, width, height);
                EXACT.check("Y'CbCr against the reference decoder",
                        Tolerance.Difference.of(keep(reference[0], lumaSize * 3 / 2, frames, excluded), kept));
                EXACT.check("alpha against the reference decoder",
                        Tolerance.Difference.of(keep(reference[1], lumaSize, frames, excluded), keptAlpha));
            }
        }
    }

    /**
     * Decodes the two layers of a scalable object, feeding their packets in alternating runs of a length.
     */
    static Decoded decodeLayers(byte[] baseData, byte[] enhancementData, int run) {
        var yuv = new ByteArrayOutputStream();
        var alpha = new ByteArrayOutputStream();
        var count = new int[3];
        try (var baseSource = MediaSource.of(baseData); var baseStream = Mpeg4ElementaryStream.open(baseSource);
             var enhancementSource = MediaSource.of(enhancementData);
             var enhancementStream = Mpeg4ElementaryStream.open(enhancementSource)) {
            var baseTrack = baseStream.track();
            var enhancementTrack = enhancementStream.track().toBuilder().id(baseTrack.id() + 1).build();
            try (var decoder = new Mpeg4Decoder(baseTrack, enhancementTrack, VideoDecoderOptions.defaults())) {
                Consumer<VideoFrame> sink = frame -> {
                    write(frame.picture(), yuv, alpha);
                    count[0]++;
                    count[1] = frame.picture().width();
                    count[2] = frame.picture().height();
                };
                var baseDone = false;
                var enhancementDone = false;
                while (!baseDone || !enhancementDone) {
                    for (var i = 0; i < run && !baseDone; i++) {
                        var packet = baseStream.read();
                        if (packet == null) {
                            baseDone = true;
                        } else {
                            decoder.decode(packet, sink);
                        }
                    }
                    for (var i = 0; i < run && !enhancementDone; i++) {
                        var packet = enhancementStream.read();
                        if (packet == null) {
                            enhancementDone = true;
                        } else {
                            decoder.decode(packet.withTrackId(enhancementTrack.id()), sink);
                        }
                    }
                }
                decoder.flush(sink);
            }
        }
        return new Decoded(yuv.toByteArray(), alpha.toByteArray(), count[0], count[1], count[2]);
    }

    /**
     * Reads the width and height of a rectangular video object layer (ISO/IEC 14496-2 6.2.3).
     */
    private static int[] layerSize(byte[] data) {
        var vol = -1;
        for (var i = 0; i + 3 < data.length && vol < 0; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1 && (data[i + 3] & 0xF0) == 0x20) {
                vol = i;
            }
        }
        assertTrue(vol >= 0, "no video object layer");
        var bits = new Bits(data, vol + 4);
        bits.read(9);
        var verid = 1;
        if (bits.read(1) == 1) {
            verid = bits.read(4);
            bits.read(3);
        }
        if (bits.read(4) == 15) {
            bits.read(16);
        }
        if (bits.read(1) == 1) {
            bits.read(3);
            if (bits.read(1) == 1) {
                bits.read(79);
            }
        }
        assertEquals(0, bits.read(2), "video_object_layer_shape");
        bits.read(1);
        var resolution = bits.read(16);
        bits.read(1);
        if (bits.read(1) == 1) {
            bits.read(Math.max(1, 32 - Integer.numberOfLeadingZeros(resolution - 1)));
        }
        bits.read(1);
        var width = bits.read(13);
        bits.read(1);
        var height = bits.read(13);
        return new int[]{width, height};
    }

    static Path member(String set, String name) {
        var archive = Conformance.archive(set);
        var path = archive.members().stream().filter(member -> member.endsWith("/" + name) || member.equals(name))
                .findFirst().orElseThrow();
        return archive.fetch(path);
    }

    private static byte[] keep(byte[] frames, int frameSize, int count, Set<Integer> excluded) {
        var out = new ByteArrayOutputStream();
        for (var i = 0; i < count && (i + 1) * frameSize <= frames.length; i++) {
            if (!excluded.contains(i)) {
                out.write(frames, i * frameSize, frameSize);
            }
        }
        return out.toByteArray();
    }

    /**
     * Runs the reference decoder; returns its Y'CbCr output and the alpha it writes: the first grayscale alpha plane
     * ({@code .0.aux}) or the binary shape ({@code .seg}).
     */
    private byte[][] reference(Path stream, int width, int height) {
        Oracle.assume(Oracle.Tool.MPEG4REF);
        var base = work.resolve(stream.getFileName() + ".ref");
        Oracle.run(Oracle.Tool.MPEG4REF, Duration.ofMinutes(10), List.of(stream.toString(), base.toString(),
                Integer.toString(width), Integer.toString(height)));
        try {
            var yuv = Files.readAllBytes(Path.of(base + ".yuv"));
            var aux = Path.of(base + ".0.aux");
            var seg = Path.of(base + ".seg");
            var alpha = Files.isRegularFile(aux) ? Files.readAllBytes(aux)
                    : Files.isRegularFile(seg) ? Files.readAllBytes(seg) : new byte[0];
            return new byte[][]{yuv, alpha};
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static Decoded decode(byte[] data, int width, int height, boolean highBitDepth) {
        var yuv = new ByteArrayOutputStream();
        var alpha = new ByteArrayOutputStream();
        var count = new int[3];
        decodePictures(data, width, height, highBitDepth, picture -> {
            write(picture, yuv, alpha);
            count[0]++;
            count[1] = picture.width();
            count[2] = picture.height();
        });
        return new Decoded(yuv.toByteArray(), alpha.toByteArray(), count[0], count[1], count[2]);
    }

    private static List<Picture> decodePictureList(byte[] data, int width, int height) {
        var pictures = new ArrayList<Picture>();
        decodePictures(data, width, height, true, picture -> pictures.add(copy(picture)));
        return pictures;
    }

    static void decodePictures(byte[] data, int width, int height, boolean highBitDepth, Consumer<Picture> sink) {
        var options = VideoDecoderOptions.builder().highBitDepth(highBitDepth).build();
        try (var source = MediaSource.of(data); var stream = Mpeg4ElementaryStream.open(source)) {
            var track = stream.track().toBuilder().width(width).height(height).build();
            try (var decoder = new Mpeg4Decoder(track, options)) {
                Consumer<VideoFrame> frames = frame -> sink.accept(frame.picture());
                Packet packet;
                while ((packet = stream.read()) != null) {
                    decoder.decode(packet, frames);
                }
                decoder.flush(frames);
            }
        }
    }

    private static Picture copy(Picture picture) {
        return switch (picture) {
            case YuvPicture p -> {
                var builder = p.toBuilder().luma(p.y().clone(), p.yStride())
                        .chroma(p.u().clone(), p.v().clone(), p.uvStride());
                yield (p.alpha() != null ? builder.alpha(p.alpha().clone(), p.alphaStride()) : builder).build();
            }
            case Yuv16Picture p -> {
                var builder = p.toBuilder().luma(p.y().clone(), p.yStride())
                        .chroma(p.u().clone(), p.v().clone(), p.uvStride());
                yield (p.alpha() != null ? builder.alpha(p.alpha().clone(), p.alphaStride()) : builder).build();
            }
            default -> throw new AssertionError(picture.getClass());
        };
    }

    private static void write(Picture picture, ByteArrayOutputStream yuv, ByteArrayOutputStream alpha) {
        switch (picture) {
            case YuvPicture p -> {
                yuv.writeBytes(Mpeg4TestStreams.raw(p));
                if (p.alpha() != null) {
                    for (var y = 0; y < p.height(); y++) {
                        alpha.write(p.alpha(), y * p.alphaStride(), p.width());
                    }
                }
            }
            case Yuv16Picture p -> {
                words(yuv, p.y(), p.yStride(), p.width(), p.height());
                words(yuv, p.u(), p.uvStride(), p.chromaWidth(), p.chromaHeight());
                words(yuv, p.v(), p.uvStride(), p.chromaWidth(), p.chromaHeight());
                if (p.alpha() != null) {
                    words(alpha, p.alpha(), p.alphaStride(), p.width(), p.height());
                }
            }
            default -> throw new AssertionError(picture.getClass());
        }
    }

    private static void words(ByteArrayOutputStream out, short[] plane, int stride, int width, int height) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var v = plane[y * stride + x];
                out.write(v & 0xFF);
                out.write((v >> 8) & 0xFF);
            }
        }
    }

    /**
     * Reads the bounding rectangle of the first VOP of a version 1 binary shape stream without B-VOPs before it:
     * its right and bottom edges (ISO/IEC 14496-2 6.2.5).
     */
    private static int[] firstVopExtent(byte[] data) {
        var vol = -1;
        var vop = -1;
        for (var i = 0; i + 3 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                var code = data[i + 3] & 0xFF;
                if (code >= 0x20 && code <= 0x2F && vol < 0) {
                    vol = i;
                } else if (code == 0xB6) {
                    vop = i;
                    break;
                }
            }
        }
        assertTrue(vol >= 0 && vop > vol, "no video object layer before the first VOP");
        var resolution = timeResolution(data, vol);
        var bits = new Bits(data, vop + 4);
        bits.read(2);
        while (bits.read(1) == 1) {
            // modulo_time_base
        }
        bits.read(1);
        bits.read(Math.max(1, 32 - Integer.numberOfLeadingZeros(resolution - 1)));
        bits.read(1);
        assertEquals(1, bits.read(1), "vop_coded");
        var width = bits.read(13);
        bits.read(1);
        var height = bits.read(13);
        bits.read(1);
        var left = bits.read(13);
        bits.read(1);
        var top = bits.read(13);
        return new int[]{(left + width + 1) & ~1, (top + height + 1) & ~1};
    }

    private static int timeResolution(byte[] data, int vol) {
        var bits = new Bits(data, vol + 4);
        bits.read(1);
        bits.read(8);
        if (bits.read(1) == 1) {
            bits.read(7);
        }
        if (bits.read(4) == 15) {
            bits.read(16);
        }
        if (bits.read(1) == 1) {
            bits.read(3);
            if (bits.read(1) == 1) {
                bits.read(79);
            }
        }
        assertEquals(1, bits.read(2), "video_object_layer_shape");
        bits.read(1);
        return bits.read(16);
    }

    private static final class Bits {
        private final byte[] data;
        private long position;

        Bits(byte[] data, int offset) {
            this.data = data;
            this.position = offset * 8L;
        }

        int read(int count) {
            var value = 0;
            for (var i = 0; i < count; i++) {
                var bit = (data[(int) (position >> 3)] >> (7 - (position & 7))) & 1;
                value = value << 1 | bit;
                position++;
            }
            return value;
        }
    }

    static byte[] resource(String name) {
        try (InputStream input = Mpeg4ObjectConformanceTest.class.getResourceAsStream(name)) {
            assertNotNull(input, name);
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String resourceText(String name) {
        return new String(resource(name), StandardCharsets.UTF_8);
    }

    static String md5(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
