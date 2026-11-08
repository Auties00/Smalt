package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks the decoding of H.264 streams deeper than 8 bits. Every frame is compared, as the MD5 of
 * its 16-bit little-endian planar samples, with the output of two independent reference decoders
 * (FFmpeg and the JM reference software), which agree on every frame of every stream listed in
 * {@code highdepth/frames.tsv}.
 */
@DisplayName("H.264 high bit depth decoding")
class H264HighBitDepthTest {
    private static final String BASE = "https://www.itu.int/wftp3/av-arch/jvt-site/draft_conformance/";

    /**
     * The expected frame hashes by stream name, in output order.
     */
    private static final Map<String, List<String>> GOLDEN = loadGolden();

    private static Map<String, List<String>> loadGolden() {
        try (var input = H264HighBitDepthTest.class.getResourceAsStream("highdepth/frames.tsv")) {
            assertNotNull(input, "highdepth/frames.tsv");
            var result = new LinkedHashMap<String, List<String>>();
            for (var line : new String(input.readAllBytes(), StandardCharsets.US_ASCII).split("\n")) {
                var parts = line.strip().split("\t");
                if (parts.length != 3) {
                    continue;
                }
                var frames = result.computeIfAbsent(parts[0], key -> new ArrayList<>());
                assertEquals(frames.size(), Integer.parseInt(parts[1]), "frame index in " + parts[0]);
                frames.add(parts[2]);
            }
            return result;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * An ITU-T H.264.1 conformance bitstream without a reference YUV in its archive.
     *
     * @param name the stream name in {@code frames.tsv}
     * @param directory the directory of the conformance site
     * @param archive the archive name
     * @param sha256 the archive checksum
     * @param entry the bitstream entry of the archive
     */
    record Vector(String name, String directory, String archive, String sha256, String entry) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Vector> conformance() {
        return Stream.of(
                new Vector("FREH10-1", "FRExt", "FREH10-1.zip",
                        "c2f6747f507e08afda32b70f374dac2d0a8104ed38df9fad700b873cab11d946",
                        "WB_10bit_QP21_I-Only_1920x1088.264"),
                new Vector("FREH10-2", "FRExt", "FREH10-2.zip",
                        "248d32b6c449ae652bf6556b1413d23151fe211115f9beb20507edf710b22473",
                        "WB_10bit_QP21_1920x1088.264"),
                pph10("1", "5bc720fd8e152fa9c1e10645b3bfddb0e32c74dad68bb6145922353a60f1beb0"),
                pph10("2", "d1d18c66fdcadad1024abdaa8a5d3d64ab24b4208bb69d5abd7b990dfbf24281"),
                pph10("3", "b36213833d6ac6d79490c5952c3e19af006e10ab34485819425997b75382dd06"),
                pph10("4", "fd4dcdc6b61d5781c4271302c43976c81980a5311e10bd5e3344bcdc226717ee"),
                pph10("5", "4115e3c9838c02d71f5ad86bea7b55ef7b24dc615babbcc1e6652566364f566e"),
                pph10("6", "cfc57cfd5791fb122520bcb179a315c7a3b7e34f241bcb2f7d5cc9c5d83fe8a4"),
                pph10("7", "81e441e22bc74d5ce7f0ab5ed256c7cf92eebd1a7cf37e2aa66107a4861b0525"));
    }

    private static Vector pph10(String number, String sha256) {
        var name = "PPH10I" + number + "_Panasonic_A";
        return new Vector(name, "Professional_profiles", name + ".zip", sha256, name + ".264");
    }

    static byte[] bitstream(Vector vector) {
        var archive = Conformance.fetch("h264-" + vector.directory() + "-" + vector.archive(),
                BASE + vector.directory() + "/" + vector.archive(), vector.sha256());
        try (var zip = new ZipFile(archive.toFile())) {
            return zip.getInputStream(zip.getEntry(vector.entry())).readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Returns the MD5 of a picture's samples as 16-bit little-endian planar data: Y, then Cb and Cr
     * when the picture has chroma.
     */
    static String md5(Picture picture) {
        try {
            var digest = MessageDigest.getInstance("MD5");
            if (picture instanceof Yuv16Picture wide) {
                digestPlane(digest, wide.y(), wide.yStride(), wide.width(), wide.height());
                if (wide.chromaFormat() != ChromaFormat.MONOCHROME) {
                    digestPlane(digest, wide.u(), wide.uvStride(), wide.chromaWidth(), wide.chromaHeight());
                    digestPlane(digest, wide.v(), wide.uvStride(), wide.chromaWidth(), wide.chromaHeight());
                }
            } else {
                var narrow = (YuvPicture) picture;
                digest.update(H264TestStreams.serialize(narrow));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void digestPlane(MessageDigest digest, short[] plane, int stride, int width, int height) {
        var row = new byte[2 * width];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var sample = plane[y * stride + x];
                row[2 * x] = (byte) sample;
                row[2 * x + 1] = (byte) (sample >> 8);
            }
            digest.update(row);
        }
    }

    /**
     * Decodes an Annex B stream one NAL unit per packet and returns the MD5 of every frame.
     */
    static List<String> decodeHashes(byte[] stream, H264DecoderOptions options) {
        var hashes = new ArrayList<String>();
        Consumer<VideoFrame> sink = frame -> hashes.add(md5(frame.picture()));
        try (var decoder = new H264Decoder(H264ConformanceTest.track(), options)) {
            for (var nal : H264TestStreams.splitAnnexB(stream)) {
                decoder.decode(new Packet(1, nal, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, false), sink);
            }
            decoder.flush(sink);
        }
        return hashes;
    }

    static void assertGolden(String name, List<String> actual) {
        var expected = GOLDEN.get(name);
        assertNotNull(expected, "no golden hashes for " + name);
        for (var i = 0; i < Math.min(expected.size(), actual.size()); i++) {
            if (!expected.get(i).equals(actual.get(i))) {
                fail(name + ": frame " + i + " differs from the reference decoders");
            }
        }
        assertEquals(expected.size(), actual.size(), "frame count of " + name);
    }

    /**
     * The streams encoded with the JM reference encoder and stored with the tests (see
     * {@code highdepth/streams.tsv} for how each was made).
     */
    static Stream<String> encoded() {
        return Stream.of("hd9-mbaff", "hd10-8-paff", "hd10-fields", "hd8-12-spatial", "hd12-cabac-wp", "hd12-mono",
                "hd14-cavlc-implicit", "hd14-mbaff-slices", "hd14-cabac-mbaff-pcm", "hd10-high422-profile");
    }

    /**
     * Decodes NAL units one per packet through the integrity-reporting API and returns copies of
     * the frames with their damage flags.
     */
    static List<Map.Entry<Picture, Boolean>> decodeChecked(List<byte[]> nals, H264DecoderOptions options) {
        var frames = new ArrayList<Map.Entry<Picture, Boolean>>();
        H264Decoder.FrameConsumer sink = (frame, damaged) -> frames.add(Map.entry(copy(frame.picture()), damaged));
        try (var decoder = new H264Decoder(H264ConformanceTest.track(), options)) {
            for (var nal : nals) {
                decoder.decode(new Packet(1, nal, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, false), sink);
            }
            decoder.flush(sink);
        }
        return frames;
    }

    /**
     * Builds a High 10 sequence parameter set of one 4:2:0 macroblock with the given
     * {@code bit_depth_luma_minus8} and {@code bit_depth_chroma_minus8}.
     */
    static byte[] high10Sps(long lumaMinus8, long chromaMinus8) {
        var writer = new H264BitWriter();
        writer.bits(110, 8).bits(0, 8).bits(30, 8).ue(0).ue(1).ue(lumaMinus8).ue(chromaMinus8).bit(0).bit(0);
        writer.ue(0).ue(2).ue(1).bit(0).ue(0).ue(0).bit(1).bit(1).bit(0).bit(0);
        return H264BitWriter.nal(3, 7, writer.trailing());
    }

    /**
     * Rewrites the {@code bit_depth_chroma_minus8} of a High profile sequence parameter set NAL
     * unit (header byte first, no start code).
     */
    static byte[] withChromaDepth(byte[] spsNal, int chromaMinus8) {
        var rbsp = new ByteArrayOutputStream();
        var zeros = 0;
        for (var i = 1; i < spsNal.length; i++) {
            var value = spsNal[i] & 0xFF;
            if (zeros >= 2 && value == 3) {
                zeros = 0;
                continue;
            }
            rbsp.write(value);
            zeros = value == 0 ? zeros + 1 : 0;
        }
        var bytes = rbsp.toByteArray();
        var bits = new StringBuilder();
        for (var b : bytes) {
            bits.append(String.format("%8s", Integer.toBinaryString(b & 0xFF)).replace(' ', '0'));
        }
        var end = bits.lastIndexOf("1");
        var position = 24;
        for (var field = 0; field < 3; field++) {
            var leading = 0;
            while (bits.charAt(position + leading) == '0') {
                leading++;
            }
            position += 2 * leading + 1;
        }
        var length = 2 * bits.indexOf("1", position) - 2 * position + 1;
        var writer = new H264BitWriter();
        for (var i = 0; i < position; i++) {
            writer.bit(bits.charAt(i) - '0');
        }
        writer.ue(chromaMinus8);
        for (var i = position + length; i < end; i++) {
            writer.bit(bits.charAt(i) - '0');
        }
        return H264BitWriter.nal(spsNal[0] >> 5 & 3, 7, writer.trailing());
    }

    @Nested
    @DisplayName("bit depth signalling")
    class Signalling {
        @ParameterizedTest(name = "luma {0}, chroma {1}")
        @CsvSource({"7, 0", "0, 7", "2147483647, 0", "0, 2147483647", "2147483641, 3"})
        @DisplayName("rejects bit depths outside 8 to 14 as malformed")
        void outOfRange(long lumaMinus8, long chromaMinus8) {
            var sps = high10Sps(lumaMinus8, chromaMinus8);
            assertThrows(MediaException.Malformed.class, () -> H264StreamInfo.probe(sps));
        }

        @ParameterizedTest(name = "luma {0}, chroma {1}")
        @CsvSource({"0, 0, 8, 8", "2, 0, 10, 8", "0, 6, 8, 14", "6, 6, 14, 14"})
        @DisplayName("reports every bit depth from 8 to 14 as supported")
        void inRange(long lumaMinus8, long chromaMinus8, int luma, int chroma) {
            var info = H264StreamInfo.probe(high10Sps(lumaMinus8, chromaMinus8));
            assertEquals(luma, info.bitDepthLuma());
            assertEquals(chroma, info.bitDepthChroma());
            assertTrue(info.decoderSupported());
        }

        @Test
        @DisplayName("ignores the chroma bit depth of a monochrome stream")
        void monochromeChromaDepth() {
            var stream = Fixtures.read(Fixtures.ffmpeg("h264", List.of("-f", "lavfi", "-i",
                    "testsrc2=size=64x48:rate=25"), List.of("-frames:v", "4", "-pix_fmt", "gray", "-c:v", "libx264",
                    "-x264-params", "bframes=1:qp=20", "-f", "h264")));
            var nals = H264TestStreams.nalUnits(stream);
            var rewritten = new ArrayList<byte[]>();
            for (var nal : nals) {
                rewritten.add((nal[0] & 0x1F) == 7 ? withChromaDepth(nal, 4) : nal);
            }
            var deep = H264TestStreams.annexB(rewritten);
            assertEquals(12, H264StreamInfo.probe(deep).bitDepthChroma());
            assertEquals(8, H264StreamInfo.probe(deep).bitDepthLuma());
            var options = H264DecoderOptions.builder().highBitDepth(true).build();
            var expected = decodePictures(stream, options);
            var actual = decodePictures(deep, options);
            assertEquals(4, actual.size());
            for (var i = 0; i < actual.size(); i++) {
                assertEquals(YuvPicture.class, actual.get(i).getClass(), "picture type of frame " + i);
                assertEquals(ChromaFormat.MONOCHROME, ((YuvPicture) actual.get(i)).chromaFormat());
                assertEquals(md5(expected.get(i)), md5(actual.get(i)), "frame " + i);
            }
        }
    }

    @Nested
    @DisplayName("damage and output formats")
    class Damage {
        @Test
        @DisplayName("conceals a lost slice of the first picture with mid-gray at the stream's depth")
        void concealedSlice() {
            var nals = H264TestStreams.splitAnnexB(resource("hd14-mbaff-slices"));
            var lastSliceOfFirstPicture = -1;
            for (var i = 0; i < nals.size(); i++) {
                var type = nals.get(i)[nals.get(i)[2] == 1 ? 3 : 4] & 0x1F;
                if (type == 5) {
                    lastSliceOfFirstPicture = i;
                }
            }
            var damagedNals = new ArrayList<>(nals.subList(0, lastSliceOfFirstPicture));
            var frames = decodeChecked(damagedNals, H264DecoderOptions.builder().highBitDepth(true).build());
            assertEquals(1, frames.size());
            assertEquals(Boolean.TRUE, frames.getFirst().getValue());
            var picture = (Yuv16Picture) frames.getFirst().getKey();
            assertEquals(14, picture.bitDepth());
            // the last slice of the 64x64 picture covers its two bottom-right macroblock pairs
            for (var y = 32; y < 64; y++) {
                for (var x = 32; x < 64; x++) {
                    assertEquals(8192, picture.y()[y * picture.yStride() + x], "luma " + x + "," + y);
                }
            }
            for (var y = 16; y < 32; y++) {
                for (var x = 16; x < 32; x++) {
                    assertEquals(8192, picture.u()[y * picture.uvStride() + x], "cb " + x + "," + y);
                    assertEquals(8192, picture.v()[y * picture.uvStride() + x], "cr " + x + "," + y);
                }
            }
            assertNotEquals(8192, picture.y()[0], "decoded area");
        }

        @Test
        @DisplayName("conceals a lost slice of a later picture from the previous picture at the stream's depth")
        void concealedFromReference() {
            var nals = H264TestStreams.splitAnnexB(resource("hd14-mbaff-slices"));
            var pictureStarts = new ArrayList<Integer>();
            for (var i = 0; i < nals.size(); i++) {
                var nal = nals.get(i);
                var header = nal[2] == 1 ? 3 : 4;
                var type = nal[header] & 0x1F;
                if ((type == 1 || type == 5) && (nal[header + 1] & 0x80) != 0) {
                    pictureStarts.add(i);
                }
            }
            var damagedNals = new ArrayList<>(nals);
            damagedNals.remove(pictureStarts.get(2) - 1);
            var options = H264DecoderOptions.builder().highBitDepth(true).build();
            var complete = decodeChecked(nals, options);
            var damaged = decodeChecked(damagedNals, options);
            assertEquals(complete.size(), damaged.size());
            // decode order I P B P: the second picture is output third and conceals from the first
            assertEquals(Boolean.FALSE, damaged.get(0).getValue());
            assertEquals(Boolean.TRUE, damaged.get(2).getValue());
            var source = (Yuv16Picture) complete.get(0).getKey();
            var picture = (Yuv16Picture) damaged.get(2).getKey();
            var differs = false;
            for (var y = 32; y < 64; y++) {
                for (var x = 32; x < 64; x++) {
                    assertEquals(source.y()[y * source.yStride() + x], picture.y()[y * picture.yStride() + x],
                            "luma " + x + "," + y);
                    differs |= source.y()[y * source.yStride() + x]
                            != ((Yuv16Picture) complete.get(2).getKey()).y()[y * picture.yStride() + x];
                }
            }
            for (var y = 16; y < 32; y++) {
                for (var x = 16; x < 32; x++) {
                    assertEquals(source.u()[y * source.uvStride() + x], picture.u()[y * picture.uvStride() + x],
                            "cb " + x + "," + y);
                    assertEquals(source.v()[y * source.uvStride() + x], picture.v()[y * picture.uvStride() + x],
                            "cr " + x + "," + y);
                }
            }
            assertTrue(differs, "the lost slice differs from the concealment source");
        }

        @Test
        @DisplayName("fills the field a lone field picture lacks with mid-gray at the stream's depth")
        void missingField() {
            var nals = H264TestStreams.splitAnnexB(resource("hd10-fields"));
            var options = H264DecoderOptions.builder().highBitDepth(true).build();
            var complete = decodeChecked(nals, options);
            var lone = decodeChecked(nals.subList(0, nals.size() - 1), options);
            assertEquals(3, complete.size());
            assertEquals(3, lone.size());
            var full = (Yuv16Picture) complete.get(2).getKey();
            var half = (Yuv16Picture) lone.get(2).getKey();
            for (var y = 0; y < full.height(); y++) {
                for (var x = 0; x < full.width(); x++) {
                    var expected = (y & 1) == 0 ? full.y()[y * full.yStride() + x] : 512;
                    assertEquals(expected, half.y()[y * half.yStride() + x], "luma " + x + "," + y);
                }
            }
            for (var y = 1; y < full.chromaHeight(); y += 2) {
                for (var x = 0; x < full.chromaWidth(); x++) {
                    assertEquals(512, half.u()[y * half.uvStride() + x], "cb " + x + "," + y);
                    assertEquals(512, half.v()[y * half.uvStride() + x], "cr " + x + "," + y);
                }
            }
        }

        @Test
        @DisplayName("hands out mixed-depth streams at the larger depth, the shallower component shifted up")
        void mixedDepth() {
            var options = H264DecoderOptions.builder().highBitDepth(true).build();
            var luma10 = decodePictures(resource("hd10-8-paff"), options);
            var chroma12 = decodePictures(resource("hd8-12-spatial"), options);
            for (var picture : luma10) {
                var wide = (Yuv16Picture) picture;
                assertEquals(10, wide.bitDepth());
                for (var sample : wide.u()) {
                    assertEquals(0, sample & 3, "chroma sample of an 8-bit component shifted to 10 bits");
                }
            }
            for (var picture : chroma12) {
                var wide = (Yuv16Picture) picture;
                assertEquals(12, wide.bitDepth());
                for (var y = 0; y < wide.height(); y++) {
                    assertEquals(0, wide.y()[y * wide.yStride()] & 15, "luma sample of an 8-bit component");
                }
            }
            var narrow = decodePictures(resource("hd8-12-spatial"), H264DecoderOptions.defaults());
            for (var i = 0; i < narrow.size(); i++) {
                var deep = (Yuv16Picture) chroma12.get(i);
                var shallow = (YuvPicture) narrow.get(i);
                for (var y = 0; y < deep.height(); y++) {
                    for (var x = 0; x < deep.width(); x++) {
                        assertEquals(deep.y()[y * deep.yStride() + x] >> 4,
                                shallow.y()[y * shallow.yStride() + x] & 0xFF);
                    }
                }
                for (var y = 0; y < deep.chromaHeight(); y++) {
                    for (var x = 0; x < deep.chromaWidth(); x++) {
                        var expected = Math.min((deep.u()[y * deep.uvStride() + x] + 8) >> 4, 255);
                        assertEquals(expected, shallow.u()[y * shallow.uvStride() + x] & 0xFF);
                    }
                }
            }
        }

        @Test
        @DisplayName("follows a stream that switches between 8 and 10 bits at IDR pictures")
        void depthSwitch() {
            var eight = Fixtures.read(Fixtures.ffmpeg("h264", List.of("-f", "lavfi", "-i",
                    "testsrc2=size=192x128:rate=25"), List.of("-frames:v", "6", "-c:v", "libx264", "-bf", "2", "-f",
                    "h264")));
            var ten = Fixtures.read(x264("yuv420p10le", "bframes=2:qp=20"));
            var joined = new ByteArrayOutputStream();
            joined.writeBytes(eight);
            joined.writeBytes(ten);
            joined.writeBytes(eight);
            for (var threads : new int[]{1, 3}) {
                var options = H264DecoderOptions.builder().highBitDepth(true).threads(threads).build();
                var expected = new ArrayList<Picture>();
                expected.addAll(decodePictures(eight, options));
                expected.addAll(decodePictures(ten, options));
                expected.addAll(decodePictures(eight, options));
                var actual = decodePictures(joined.toByteArray(), options);
                assertEquals(6 + 12 + 6, actual.size());
                for (var i = 0; i < actual.size(); i++) {
                    assertEquals(expected.get(i).getClass(), actual.get(i).getClass(), "picture type of frame " + i);
                    assertEquals(md5(expected.get(i)), md5(actual.get(i)), "frame " + i + " with " + threads
                            + " threads");
                }
            }
        }

        @Test
        @DisplayName("starts a new picture when an identical IDR follows under a sequence of another depth")
        void depthChangeBetweenIdenticalIdrs() {
            var nals = H264TestStreams.nalUnits(resource("hd8-12-spatial"));
            var units = H264TestStreams.accessUnits(nals);
            var first = new ArrayList<byte[]>();
            for (var nal : units.getFirst()) {
                first.add((nal[0] & 0x1F) == 7 ? withChromaDepth(nal, 3) : nal);
            }
            for (var threads : new int[]{1, 3}) {
                var options = H264DecoderOptions.builder().highBitDepth(true).threads(threads).build();
                var expected = decodePictures(resource("hd8-12-spatial"), options);
                var frames = new ArrayList<Picture>();
                try (var decoder = new H264Decoder(H264ConformanceTest.track(), options)) {
                    var packets = new ArrayList<byte[]>();
                    packets.add(H264TestStreams.annexB(first));
                    for (var unit : units) {
                        packets.add(H264TestStreams.annexB(unit));
                    }
                    for (var packet : packets) {
                        try {
                            decoder.decode(new Packet(1, packet, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, false),
                                    frame -> frames.add(copy(frame.picture())));
                        } catch (MediaException.Malformed expectedFailure) {
                            // the 11-bit reading of the 12-bit I_PCM samples is corrupt
                        }
                    }
                    decoder.flush(frame -> frames.add(copy(frame.picture())));
                }
                assertEquals(expected.size() + 1, frames.size(), "frames with " + threads + " threads");
                assertEquals(11, ((Yuv16Picture) frames.getFirst()).bitDepth(),
                        "the picture of the 8/11-bit sequence");
                for (var i = 0; i < expected.size(); i++) {
                    assertEquals(md5(expected.get(i)), md5(frames.get(i + 1)), "frame " + i + " with " + threads
                            + " threads");
                }
            }
        }

        @Test
        @DisplayName("hands out 16-bit frames when the registry options ask for high bit depth")
        void registry() {
            var stream = resource("hd12-cabac-wp");
            for (var wide : new boolean[]{true, false}) {
                var options = VideoDecoderOptions.builder().highBitDepth(wide).build();
                var types = new ArrayList<Class<?>>();
                try (var decoder = VideoDecoders.create(H264ConformanceTest.track(), options)) {
                    for (var nal : H264TestStreams.splitAnnexB(stream)) {
                        decoder.decode(new Packet(1, nal, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, false),
                                frame -> types.add(frame.picture().getClass()));
                    }
                    decoder.flush(frame -> types.add(frame.picture().getClass()));
                }
                assertEquals(8, types.size());
                var expected = wide ? Yuv16Picture.class : YuvPicture.class;
                assertTrue(types.stream().allMatch(type -> type == expected), "picture types " + types);
            }
        }

        @Test
        @DisplayName("hands out 8-bit streams as YuvPicture even with highBitDepth")
        void eightBitStream() {
            var stream = Fixtures.ffmpeg("h264", List.of("-f", "lavfi", "-i", "testsrc2=size=64x64:rate=25"),
                    List.of("-frames:v", "2", "-c:v", "libx264", "-f", "h264"));
            var pictures = decodePictures(stream, H264DecoderOptions.builder().highBitDepth(true).build());
            assertEquals(2, pictures.size());
            for (var picture : pictures) {
                assertEquals(YuvPicture.class, picture.getClass());
            }
        }

        @Test
        @DisplayName("keeps highBitDepth through the builder, equality and toString")
        void options() {
            var options = H264DecoderOptions.builder().highBitDepth(true).threads(2).build();
            assertTrue(options.highBitDepth());
            assertEquals(false, H264DecoderOptions.defaults().highBitDepth());
            assertEquals(false, H264DecoderOptions.forRealtime().highBitDepth());
            assertEquals(options, options.toBuilder().build());
            assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
            assertNotEquals(options, options.toBuilder().highBitDepth(false).build());
            assertTrue(options.toString().contains("highBitDepth=true"));
        }
    }

    static byte[] resource(String name) {
        try (var input = H264HighBitDepthTest.class.getResourceAsStream("highdepth/" + name + ".264")) {
            assertNotNull(input, name);
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Nested
    @DisplayName("JM-encoded streams of 9 to 14 bits")
    class EncodedStreams {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264HighBitDepthTest#encoded")
        @DisplayName("decodes every frame bit-exactly at 16 bits")
        void bitExact(String name) {
            var options = H264DecoderOptions.builder().highBitDepth(true).build();
            assertGolden(name, decodeHashes(resource(name), options));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264HighBitDepthTest#encoded")
        @DisplayName("decodes bit-exactly with four frame threads")
        void threaded(String name) {
            var options = H264DecoderOptions.builder().highBitDepth(true).threads(4).build();
            assertGolden(name, decodeHashes(resource(name), options));
        }
    }

    /**
     * Encodes a moving synthetic clip with x264 at 10 bits.
     *
     * @param pixelFormat {@code yuv420p10le} or {@code gray10le}
     * @param parameters the {@code x264-params} list; a list naming weighted prediction gets a
     * fade-in, so the encoder signals weights and offsets
     */
    static Path x264(String pixelFormat, String parameters) {
        return x264(pixelFormat, parameters, 192, 128);
    }

    /**
     * Encodes a moving synthetic clip of the given size with x264 at 10 bits.
     *
     * @param pixelFormat {@code yuv420p10le} or {@code gray10le}
     * @param parameters the {@code x264-params} list; a list naming weighted prediction gets a
     * fade-in, so the encoder signals weights and offsets
     * @param width the picture width, even
     * @param height the picture height, even
     */
    static Path x264(String pixelFormat, String parameters, int width, int height) {
        // a fade gives the weighted prediction analysis weights and offsets to signal
        var filters = (parameters.contains("weight") ? "fade=in:0:12," : "") + "noise=alls=8:allf=t,format="
                + pixelFormat;
        var output = new ArrayList<>(List.of("-vf", filters, "-frames:v", "12", "-c:v", "libx264", "-x264-params",
                parameters, "-f", "h264"));
        return Fixtures.ffmpeg("h264", List.of("-f", "lavfi", "-i", "testsrc2=size=" + width + "x" + height
                + ":rate=25"), output);
    }

    /**
     * The x264 settings of the differential tests: entropy coders, frame types, weighted
     * prediction, interlacing, scaling matrices, chroma offsets, deblocking offsets and slices,
     * with quantization parameters down to negative {@code QPY}.
     */
    static Stream<Arguments> x264Settings() {
        return Stream.of(
                Arguments.of("cavlc-p", "yuv420p10le", "cabac=0:bframes=0:qp=24:8x8dct=0"),
                Arguments.of("cabac-b-pyramid", "yuv420p10le", "bframes=3:b-pyramid=normal:ref=4:qp=20"),
                Arguments.of("weightp", "yuv420p10le", "bframes=0:weightp=2:qp=22:ref=3"),
                Arguments.of("weightb-temporal", "yuv420p10le", "bframes=2:weightb=1:direct=temporal:qp=26"),
                Arguments.of("low-qp", "yuv420p10le", "bframes=2:qp=3:chroma-qp-offset=-6"),
                Arguments.of("crf-aq", "yuv420p10le", "bframes=2:crf=16:aq-mode=2:aq-strength=1.5"),
                Arguments.of("cqm-jvt", "yuv420p10le", "bframes=2:cqm=jvt:qp=18:8x8dct=1"),
                Arguments.of("deblock-slices", "yuv420p10le", "bframes=1:slices=4:deblock=-3,2:qp=30"),
                Arguments.of("mbaff-cabac", "yuv420p10le", "interlaced=1:bframes=2:qp=22"),
                Arguments.of("mbaff-cavlc", "yuv420p10le", "interlaced=1:cabac=0:bframes=1:qp=26:weightp=1"),
                Arguments.of("fake-interlaced", "yuv420p10le", "fake-interlaced=1:bframes=1:qp=24"),
                Arguments.of("intra-refresh", "yuv420p10le", "bframes=0:intra-refresh=1:keyint=6:qp=24"),
                Arguments.of("partitions-all", "yuv420p10le",
                        "bframes=2:partitions=all:subme=9:direct=auto:weightb=1:ref=3:qp=18"),
                Arguments.of("constrained-intra", "yuv420p10le", "bframes=0:constrained-intra=1:keyint=4:qp=20"),
                Arguments.of("monochrome", "gray10le", "bframes=2:qp=20:weightp=2"),
                Arguments.of("monochrome-cavlc", "gray10le", "cabac=0:bframes=0:qp=8"),
                Arguments.of("monochrome-mbaff", "gray10le", "interlaced=1:bframes=1:qp=16"));
    }

    /**
     * The x264 settings of the differential test at a picture size that is not a multiple of 16,
     * so the stream signals frame cropping.
     */
    static Stream<Arguments> croppedSettings() {
        return Stream.of(
                Arguments.of("cropped-cabac", "yuv420p10le", "bframes=2:qp=20"),
                Arguments.of("cropped-mbaff", "yuv420p10le", "interlaced=1:cabac=0:bframes=1:qp=22"),
                Arguments.of("cropped-monochrome", "gray10le", "bframes=1:qp=18"));
    }

    /**
     * Returns the frames of a reference decode by FFmpeg, as 16-bit little-endian planar data.
     */
    static List<byte[]> oracleFrames(Path stream, String pixelFormat, int width, int height) {
        var raw = Oracle.decodeVideo(stream, List.of("-f", "h264"), pixelFormat, 0);
        var size = 2 * (width * height + (pixelFormat.startsWith("gray") ? 0 : 2 * (width / 2) * (height / 2)));
        var frames = new ArrayList<byte[]>();
        for (var at = 0; at + size <= raw.length; at += size) {
            frames.add(Arrays.copyOfRange(raw, at, at + size));
        }
        return frames;
    }

    /**
     * Serializes a 16-bit picture as little-endian planar data.
     */
    static byte[] serialize(Yuv16Picture picture) {
        var out = new ByteArrayOutputStream();
        writePlane(out, picture.y(), picture.yStride(), picture.width(), picture.height());
        if (picture.chromaFormat() != ChromaFormat.MONOCHROME) {
            writePlane(out, picture.u(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight());
            writePlane(out, picture.v(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight());
        }
        return out.toByteArray();
    }

    private static void writePlane(ByteArrayOutputStream out, short[] plane, int stride, int width, int height) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var sample = plane[y * stride + x];
                out.write(sample & 0xFF);
                out.write((sample >> 8) & 0xFF);
            }
        }
    }

    /**
     * Decodes a stream with the given options, one access unit per packet, and returns a copy of
     * each frame's picture.
     */
    static List<Picture> decodePictures(Path stream, H264DecoderOptions options) {
        return decodePictures(Fixtures.read(stream), options);
    }

    /**
     * Decodes an Annex B stream with the given options, one access unit per packet, and returns a
     * copy of each frame's picture.
     */
    static List<Picture> decodePictures(byte[] stream, H264DecoderOptions options) {
        var pictures = new ArrayList<Picture>();
        Consumer<VideoFrame> sink = frame -> pictures.add(copy(frame.picture()));
        try (var decoder = new H264Decoder(H264ConformanceTest.track(), options)) {
            for (var unit : H264TestStreams.accessUnits(H264TestStreams.nalUnits(stream))) {
                decoder.decode(new Packet(1, H264TestStreams.annexB(unit), Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP,
                        0, false), sink);
            }
            decoder.flush(sink);
        }
        return pictures;
    }

    private static Picture copy(Picture picture) {
        if (picture instanceof Yuv16Picture wide) {
            var copy = Yuv16Picture.allocate(wide.width(), wide.height(), wide.chromaFormat(), wide.bitDepth(), false,
                    wide.color());
            System.arraycopy(wide.y(), 0, copy.y(), 0, wide.y().length);
            if (wide.u() != null) {
                System.arraycopy(wide.u(), 0, copy.u(), 0, wide.u().length);
                System.arraycopy(wide.v(), 0, copy.v(), 0, wide.v().length);
            }
            return copy;
        }
        var narrow = (YuvPicture) picture;
        var copy = YuvPicture.allocate(narrow.width(), narrow.height(), narrow.chromaFormat(), false, narrow.color());
        System.arraycopy(narrow.y(), 0, copy.y(), 0, narrow.y().length);
        if (narrow.u() != null) {
            System.arraycopy(narrow.u(), 0, copy.u(), 0, narrow.u().length);
            System.arraycopy(narrow.v(), 0, copy.v(), 0, narrow.v().length);
        }
        return copy;
    }

    @Nested
    @DisplayName("x264 10-bit streams against FFmpeg")
    class Differential {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264HighBitDepthTest#x264Settings")
        @DisplayName("decodes every frame as FFmpeg does, with one and four threads")
        void matchesFfmpeg(String name, String pixelFormat, String parameters) {
            assertMatchesFfmpeg(name, pixelFormat, parameters, 192, 128);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264HighBitDepthTest#croppedSettings")
        @DisplayName("decodes cropped pictures as FFmpeg does, with one and four threads")
        void croppedMatchesFfmpeg(String name, String pixelFormat, String parameters) {
            assertMatchesFfmpeg(name, pixelFormat, parameters, 202, 116);
        }

        private static void assertMatchesFfmpeg(String name, String pixelFormat, String parameters, int width,
                                                int height) {
            var stream = x264(pixelFormat, parameters, width, height);
            var expected = oracleFrames(stream, pixelFormat, width, height);
            assertEquals(12, expected.size(), "frames FFmpeg decoded");
            for (var threads : new int[]{1, 4}) {
                var options = H264DecoderOptions.builder().highBitDepth(true).threads(threads).build();
                var actual = decodePictures(stream, options);
                assertEquals(expected.size(), actual.size(), "frame count with " + threads + " threads");
                for (var i = 0; i < actual.size(); i++) {
                    var picture = (Yuv16Picture) actual.get(i);
                    assertEquals(10, picture.bitDepth());
                    assertEquals(width, picture.width());
                    assertEquals(height, picture.height());
                    assertEquals(pixelFormat.startsWith("gray") ? ChromaFormat.MONOCHROME : ChromaFormat.YUV420,
                            picture.chromaFormat());
                    if (!Arrays.equals(expected.get(i), serialize(picture))) {
                        fail(name + ": frame " + i + " differs from FFmpeg with " + threads + " threads");
                    }
                }
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264HighBitDepthTest#x264Settings")
        @DisplayName("rounds to 8 bits by default as min((v + 2) >> 2, 255)")
        void roundsToEightBits(String name, String pixelFormat, String parameters) {
            var stream = x264(pixelFormat, parameters);
            var wide = decodePictures(stream, H264DecoderOptions.builder().highBitDepth(true).build());
            var narrow = decodePictures(stream, H264DecoderOptions.defaults());
            assertEquals(wide.size(), narrow.size());
            for (var i = 0; i < wide.size(); i++) {
                var deep = (Yuv16Picture) wide.get(i);
                var shallow = (YuvPicture) narrow.get(i);
                assertEquals(deep.chromaFormat(), shallow.chromaFormat());
                assertRounded(deep.y(), deep.yStride(), shallow.y(), shallow.yStride(), deep.width(), deep.height());
                if (deep.u() != null) {
                    assertRounded(deep.u(), deep.uvStride(), shallow.u(), shallow.uvStride(), deep.chromaWidth(),
                            deep.chromaHeight());
                    assertRounded(deep.v(), deep.uvStride(), shallow.v(), shallow.uvStride(), deep.chromaWidth(),
                            deep.chromaHeight());
                }
            }
        }

        private static void assertRounded(short[] deep, int deepStride, byte[] shallow, int shallowStride, int width,
                                          int height) {
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var expected = Math.min((deep[y * deepStride + x] + 2) >> 2, 255);
                    assertEquals(expected, shallow[y * shallowStride + x] & 0xFF, "sample " + x + "," + y);
                }
            }
        }
    }

    @Nested
    @DisplayName("ITU-T conformance bitstreams")
    class ConformanceStreams {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264HighBitDepthTest#conformance")
        @DisplayName("decodes every frame bit-exactly at 16 bits")
        void bitExact(Vector vector) {
            var options = H264DecoderOptions.builder().highBitDepth(true).build();
            assertGolden(vector.name(), decodeHashes(bitstream(vector), options));
        }
    }
}
