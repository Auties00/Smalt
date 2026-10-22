package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.image.metadata.ImageMetadata;
import com.github.auties00.smalt.image.metadata.IptcData;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the codestream options of the JPEG 2000 encoder: image and tile origins, tile-parts, {@code TLM} and
 * {@code PLT} markers, progression changes, regions of interest, guard bits, precinct size lists, size limits, the
 * digital cinema and IMF profiles, and the metadata boxes of a JP2 file. Lossless output is compared byte for byte
 * with {@code opj_compress} given the same options; every other output is parsed marker by marker, decoded by this
 * decoder and by {@code opj_decompress}, and its metadata read back by exiftool.
 */
@DisplayName("JPEG 2000 codestream options")
class Jp2CodestreamOptionsTest {
    private static final String COMMENT = "Smalt test";

    private static final int SOT = 0xFF90;

    private static final int SOD = 0xFF93;

    private static final int EOC = 0xFFD9;

    private static final int TLM = 0xFF55;

    private static final int PLT = 0xFF58;

    private static final int POC = 0xFF5F;

    private static final int RGN = 0xFF5E;

    private static final int SIZ = 0xFF51;

    private static final int COD = 0xFF52;

    private static final int QCD = 0xFF5C;

    /** One marker segment: the marker and the bytes after its length field. */
    record Segment(int marker, byte[] body) {
        int u8(int at) {
            return body[at] & 0xFF;
        }

        int u16(int at) {
            return (u8(at) << 8) | u8(at + 1);
        }

        long u32(int at) {
            return ((long) u16(at) << 16) | u16(at + 2);
        }
    }

    /** One tile-part: its SOT fields, its header segments and its packets. */
    record TilePart(int tile, long length, int index, int count, List<Segment> header, byte[] body) {
        List<Segment> segments(int marker) {
            return header.stream().filter(segment -> segment.marker() == marker).toList();
        }
    }

    /** A parsed codestream. */
    record Codestream(List<Segment> main, List<TilePart> parts) {
        List<Segment> segments(int marker) {
            return main.stream().filter(segment -> segment.marker() == marker).toList();
        }

        Segment segment(int marker) {
            var list = segments(marker);
            assertEquals(1, list.size(), "segments of marker " + Integer.toHexString(marker));
            return list.getFirst();
        }

        List<TilePart> parts(int tile) {
            return parts.stream().filter(part -> part.tile() == tile).toList();
        }
    }

    static Codestream parse(byte[] data) {
        assertEquals(0xFF4F, u16(data, 0), "SOC");
        var main = new ArrayList<Segment>();
        var at = 2;
        while (u16(data, at) != SOT) {
            var length = u16(data, at + 2);
            main.add(new Segment(u16(data, at), Arrays.copyOfRange(data, at + 4, at + 2 + length)));
            at += 2 + length;
        }
        var parts = new ArrayList<TilePart>();
        while (u16(data, at) == SOT) {
            var start = at;
            var tile = u16(data, at + 4);
            var length = ((long) u16(data, at + 6) << 16) | u16(data, at + 8);
            var index = data[at + 10] & 0xFF;
            var count = data[at + 11] & 0xFF;
            at += 12;
            var header = new ArrayList<Segment>();
            while (u16(data, at) != SOD) {
                var segmentLength = u16(data, at + 2);
                header.add(new Segment(u16(data, at), Arrays.copyOfRange(data, at + 4, at + 2 + segmentLength)));
                at += 2 + segmentLength;
            }
            at += 2;
            var end = (int) (start + length);
            parts.add(new TilePart(tile, length, index, count, header, Arrays.copyOfRange(data, at, end)));
            at = end;
        }
        assertEquals(EOC, u16(data, at), "EOC");
        assertEquals(data.length, at + 2, "codestream length");
        return new Codestream(main, parts);
    }

    static int u16(byte[] data, int at) {
        return ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
    }

    /** Returns the codestream of a JP2 file, or the input when it is a codestream. */
    static byte[] codestream(byte[] file) {
        if ((file[0] & 0xFF) == 0xFF) {
            return file;
        }
        var at = 0;
        while (at + 8 <= file.length) {
            var size = (int) (((file[at] & 0xFFL) << 24) | ((file[at + 1] & 0xFF) << 16) | ((file[at + 2] & 0xFF) << 8)
                    | (file[at + 3] & 0xFF));
            var type = new String(file, at + 4, 4, StandardCharsets.ISO_8859_1);
            if (size == 0) {
                size = file.length - at;
            }
            if (type.equals("jp2c")) {
                return Arrays.copyOfRange(file, at + 8, at + size);
            }
            at += size;
        }
        throw new AssertionError("no jp2c box");
    }

    /** Lists the top-level box types of a JP2 file. */
    static List<String> boxes(byte[] file) {
        var result = new ArrayList<String>();
        var at = 0;
        while (at + 8 <= file.length) {
            var size = (int) (((file[at] & 0xFFL) << 24) | ((file[at + 1] & 0xFF) << 16) | ((file[at + 2] & 0xFF) << 8)
                    | (file[at + 3] & 0xFF));
            result.add(new String(file, at + 4, 4, StandardCharsets.ISO_8859_1));
            at += size == 0 ? file.length - at : size;
        }
        return result;
    }

    static RgbaPicture picture(int width, int height) {
        return Jp2EncoderTest.picture(width, height, false, width * 7L + height);
    }

    /** Encodes with opj_compress, returning its output. */
    static byte[] opjCompress(byte[] input, String inputName, List<String> arguments, String extension) {
        OpenJpeg.assume("opj_compress");
        try {
            var in = OpenJpeg.temp(inputName);
            Files.write(in, input);
            var out = OpenJpeg.temp("out." + extension);
            var command = new ArrayList<>(List.of("-i", in.toString(), "-o", out.toString(), "-C", COMMENT));
            command.addAll(arguments);
            assertNotNull(OpenJpeg.run("opj_compress", command), "opj_compress failed on " + arguments);
            return Files.readAllBytes(out);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Asserts that this decoder and, when installed, opj_decompress reproduce every channel of a picture. */
    static void assertLossless(RgbaPicture picture, byte[] encoded, String name) {
        var decoded = (RgbaPicture) Jp2Decoder.decode(MediaSource.of(encoded)).picture();
        Tolerance.exact().assertWithin(name, picture.pixels(), decoded.pixels());
        if (OpenJpeg.find("opj_decompress").isPresent()) {
            var planes = OpenJpeg.decode(encoded, (encoded[0] & 0xFF) == 0xFF ? "j2k" : "jp2", 0, 0);
            assertNotNull(planes, "opj_decompress rejected " + name);
            for (var c = 0; c < 3; c++) {
                Tolerance.exact().assertWithin(name + " channel " + c, Jp2Testing.channel(picture, c),
                        planes[c].samples());
            }
        }
    }

    static Jp2EncoderOptions.Builder lossless() {
        return Jp2EncoderOptions.builder().format(Jp2Format.CODESTREAM).comment(COMMENT);
    }

    static Stream<Arguments> identities() {
        var list = new ArrayList<Arguments>();
        list.add(Arguments.of("-d 7,5", List.of("-d", "7,5"), lossless().imageOrigin(7, 5)));
        list.add(Arguments.of("-d 37,21 -T 10,3 -t 40,40", List.of("-d", "37,21", "-T", "10,3", "-t", "40,40"),
                lossless().imageOrigin(37, 21).tileOrigin(10, 3).tileSize(40, 40)));
        list.add(Arguments.of("-d 3,9 -T 3,9 -t 50,40", List.of("-d", "3,9", "-T", "3,9", "-t", "50,40"),
                lossless().imageOrigin(3, 9).tileOrigin(3, 9).tileSize(50, 40)));
        for (var division : Jp2TilePartDivision.values()) {
            var letter = String.valueOf(division.name().charAt(0));
            list.add(Arguments.of("-TP " + letter, List.of("-TP", letter), lossless().tilePartDivision(division)));
        }
        // opj_compress 2.5.3 fails on -TP L and -TP C with the RPCL order ("accessing precno ...")
        list.add(Arguments.of("-TP R -t 64,48 -p RPCL -c [32,32]", List.of("-TP", "R", "-t", "64,48", "-p", "RPCL",
                "-c", "[32,32]"), lossless().tilePartDivision(Jp2TilePartDivision.RESOLUTION).tileSize(64, 48)
                .progressionOrder(Jp2ProgressionOrder.RPCL).precinctSize(32, 32)));
        // precincts of 16 give opj_compress a lowest-but-one resolution of precinct exponent 0, which its own
        // decoder rejects, so the position-driven cases use 32
        list.add(Arguments.of("-TP C -p CPRL -c [32,32]", List.of("-TP", "C", "-p", "CPRL", "-c", "[32,32]"),
                lossless().tilePartDivision(Jp2TilePartDivision.COMPONENT).progressionOrder(Jp2ProgressionOrder.CPRL)
                        .precinctSize(32, 32)));
        list.add(Arguments.of("-TP R -p PCRL -c [64,64] -t 64,64", List.of("-TP", "R", "-p", "PCRL", "-c", "[64,64]",
                "-t", "64,64"), lossless().tilePartDivision(Jp2TilePartDivision.RESOLUTION)
                .progressionOrder(Jp2ProgressionOrder.PCRL).precinctSize(64, 64).tileSize(64, 64)));
        list.add(Arguments.of("-TLM", List.of("-TLM"), lossless().tlmMarkers(true)));
        list.add(Arguments.of("-TLM -TP R -t 32,32", List.of("-TLM", "-TP", "R", "-t", "32,32"),
                lossless().tlmMarkers(true).tilePartDivision(Jp2TilePartDivision.RESOLUTION).tileSize(32, 32)));
        list.add(Arguments.of("-PLT", List.of("-PLT"), lossless().pltMarkers(true)));
        list.add(Arguments.of("-PLT -SOP -EPH -TP R -c [32,32]", List.of("-PLT", "-SOP", "-EPH", "-TP", "R", "-c",
                "[32,32]"), lossless().pltMarkers(true).sopMarkers(true).ephMarkers(true)
                .tilePartDivision(Jp2TilePartDivision.RESOLUTION).precinctSize(32, 32)));
        list.add(Arguments.of("-TLM -PLT -t 40,40", List.of("-TLM", "-PLT", "-t", "40,40"),
                lossless().tlmMarkers(true).pltMarkers(true).tileSize(40, 40)));
        list.add(Arguments.of("-POC LRCP then RPCL", List.of("-POC", "T1=0,0,1,3,3,LRCP/T1=3,0,1,6,3,RPCL", "-c",
                "[32,32]"), lossless().precinctSize(32, 32).progressionChanges(List.of(
                new Jp2ProgressionChange(0, 0, 1, 3, 3, Jp2ProgressionOrder.LRCP),
                new Jp2ProgressionChange(3, 0, 1, 6, 3, Jp2ProgressionOrder.RPCL)))));
        list.add(Arguments.of("-POC by component", List.of("-POC", "T1=0,0,1,6,1,CPRL/T1=0,1,1,6,3,PCRL", "-c",
                "[32,32]"), lossless().precinctSize(32, 32).progressionChanges(List.of(
                new Jp2ProgressionChange(0, 0, 1, 6, 1, Jp2ProgressionOrder.CPRL),
                new Jp2ProgressionChange(0, 1, 1, 6, 3, Jp2ProgressionOrder.PCRL)))));
        list.add(Arguments.of("-POC every tile -t 64,64 -TLM", List.of("-POC", pocEveryTile(6), "-t", "64,64", "-TLM"),
                lossless().tileSize(64, 64).tlmMarkers(true).progressionChanges(List.of(
                        new Jp2ProgressionChange(0, 0, 1, 2, 3, Jp2ProgressionOrder.RLCP),
                        new Jp2ProgressionChange(2, 0, 1, 6, 3, Jp2ProgressionOrder.LRCP)))));
        // opj_compress 2.5.3 repeats or drops packets when a tile-part division cuts a progression change on a
        // dimension the change also ranges over; these two cases it codes as the standard says
        list.add(Arguments.of("-POC by component -TP R", List.of("-POC", "T1=0,0,1,6,1,LRCP/T1=0,1,1,6,3,LRCP", "-TP",
                "R"), lossless().tilePartDivision(Jp2TilePartDivision.RESOLUTION).progressionChanges(List.of(
                new Jp2ProgressionChange(0, 0, 1, 6, 1, Jp2ProgressionOrder.LRCP),
                new Jp2ProgressionChange(0, 1, 1, 6, 3, Jp2ProgressionOrder.LRCP)))));
        list.add(Arguments.of("-POC by resolution -TP L", List.of("-POC", "T1=0,0,1,3,3,LRCP/T1=3,0,1,6,3,LRCP",
                "-TP", "L"), lossless().tilePartDivision(Jp2TilePartDivision.LAYER).progressionChanges(List.of(
                new Jp2ProgressionChange(0, 0, 1, 3, 3, Jp2ProgressionOrder.LRCP),
                new Jp2ProgressionChange(3, 0, 1, 6, 3, Jp2ProgressionOrder.LRCP)))));
        list.add(Arguments.of("-ROI c=0,U=5", List.of("-ROI", "c=0,U=5"),
                lossless().regionOfInterest(new Jp2RegionOfInterest.Component(0, 5))));
        list.add(Arguments.of("-ROI c=2,U=11 -t 64,48", List.of("-ROI", "c=2,U=11", "-t", "64,48"),
                lossless().regionOfInterest(new Jp2RegionOfInterest.Component(2, 11)).tileSize(64, 48)));
        for (var bits : List.of(1, 3, 7)) {
            list.add(Arguments.of("-GuardBits " + bits, List.of("-GuardBits", String.valueOf(bits)),
                    lossless().guardBits(bits)));
        }
        list.add(Arguments.of("-c [64,64],[32,32],[16,16]", List.of("-c", "[64,64],[32,32],[16,16]"),
                lossless().precinctSizes(new int[]{64, 32, 16}, new int[]{64, 32, 16})));
        list.add(Arguments.of("-c [128,64],[32,16] -p PCRL", List.of("-c", "[128,64],[32,16]", "-p", "PCRL"),
                lossless().precinctSizes(new int[]{128, 32}, new int[]{64, 16})
                        .progressionOrder(Jp2ProgressionOrder.PCRL)));
        list.add(Arguments.of("-IMF 2K_R -n 5", List.of("-IMF", "2K_R", "-n", "5"),
                Jp2EncoderOptions.forImf(Jp2Profile.IMF_2K_REVERSIBLE, 0, 0, 0).toBuilder()
                        .format(Jp2Format.CODESTREAM).comment(COMMENT)));
        list.add(Arguments.of("-IMF 4K_R,mainlevel=2,sublevel=1 -n 5", List.of("-IMF", "4K_R,mainlevel=2,sublevel=1",
                "-n", "5"), Jp2EncoderOptions.forImf(Jp2Profile.IMF_4K_REVERSIBLE, 2, 1, 0).toBuilder()
                .format(Jp2Format.CODESTREAM).comment(COMMENT)));
        list.add(Arguments.of("jp2 -d 11,4 -TP C -TLM -PLT", List.of("-d", "11,4", "-TP", "C", "-TLM", "-PLT"),
                Jp2EncoderOptions.builder().comment(COMMENT).imageOrigin(11, 4)
                        .tilePartDivision(Jp2TilePartDivision.COMPONENT).tlmMarkers(true).pltMarkers(true)));
        return list.stream();
    }

    /** Returns a -POC argument giving every tile the same two progressions. */
    static String pocEveryTile(int tiles) {
        var parts = new ArrayList<String>();
        for (var t = 1; t <= tiles; t++) {
            parts.add("T" + t + "=0,0,1,2,3,RLCP");
            parts.add("T" + t + "=2,0,1,6,3,LRCP");
        }
        return String.join("/", parts);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("identities")
    @DisplayName("lossless output is byte-identical to opj_compress with the same options")
    void identical(String name, List<String> arguments, Jp2EncoderOptions.Builder options) {
        var picture = picture(131, 87);
        var built = options.build();
        var extension = built.format() == Jp2Format.JP2 ? "jp2" : "j2k";
        var expected = opjCompress(Jp2EncoderTest.ppm(picture, false), "in.ppm", arguments, extension);
        var actual = Jp2Encoder.encode(picture, built);
        assertEquals(expected.length, actual.length, name + " length");
        assertArrayEquals(expected, actual, name);
        assertLossless(picture, actual, name);
    }

    @Nested
    @DisplayName("origins")
    class Origins {
        @Test
        @DisplayName("the SIZ segment places the image and the tiles where the options say")
        void siz() {
            var picture = picture(40, 30);
            var encoded = Jp2Encoder.encode(picture, lossless().imageOrigin(1000, 2000).tileOrigin(990, 1990)
                    .tileSize(16, 16).build());
            var siz = parse(encoded).segment(SIZ);
            assertEquals(1040, siz.u32(2));
            assertEquals(2030, siz.u32(6));
            assertEquals(1000, siz.u32(10));
            assertEquals(2000, siz.u32(14));
            assertEquals(16, siz.u32(18));
            assertEquals(990, siz.u32(26));
            assertEquals(1990, siz.u32(30));
            assertEquals(4 * 3, parse(encoded).parts().size());
            assertLossless(picture, encoded, "origin");
        }

        @Test
        @DisplayName("subsampled channels at an odd origin round trip, and the decoded image encodes to the same bytes")
        void channels() {
            var x0 = 13L;
            var y0 = 7L;
            var width = 45;
            var height = 31;
            var luma = new int[width * height];
            for (var i = 0; i < luma.length; i++) {
                luma[i] = (int) ((i * 2654435761L) % 1024);
            }
            var cw = (int) (Math.ceilDiv(x0 + width, 2) - Math.ceilDiv(x0, 2));
            var ch = (int) (Math.ceilDiv(y0 + height, 2) - Math.ceilDiv(y0, 2));
            var chroma = new int[cw * ch];
            for (var i = 0; i < chroma.length; i++) {
                chroma[i] = (i * 37) % 1024;
            }
            var image = new Jp2Image(x0, y0, width, height, width, height, 0, List.of(
                    new Jp2Channel(x0, y0, width, height, 1, 1, 10, false, Jp2ChannelType.COLOR, 1, luma),
                    new Jp2Channel(Math.ceilDiv(x0, 2), Math.ceilDiv(y0, 2), cw, ch, 2, 2, 10, false,
                            Jp2ChannelType.COLOR, 2, chroma)), Jp2ColorSpace.UNSPECIFIED, Optional.empty(),
                    Optional.empty(), Optional.empty());
            var options = Jp2EncoderOptions.builder().tileOrigin(5, 3).tileSize(16, 16).build();
            var encoded = Jp2Encoder.encode(image, options);
            var decoded = Jp2Decoder.decodeChannels(MediaSource.of(encoded));
            assertEquals(x0, decoded.x0());
            assertEquals(y0, decoded.y0());
            Tolerance.exact().assertWithin("luma", luma, decoded.channels().get(0).samples());
            Tolerance.exact().assertWithin("chroma", chroma, decoded.channels().get(1).samples());
            assertArrayEquals(encoded, Jp2Encoder.encode(decoded, options));
            if (OpenJpeg.find("opj_decompress").isPresent()) {
                var planes = OpenJpeg.decode(encoded, "jp2", 0, 0);
                assertNotNull(planes);
                Tolerance.exact().assertWithin("opj luma", luma, planes[0].samples());
                Tolerance.exact().assertWithin("opj chroma", chroma, planes[1].samples());
            }
        }

        @Test
        @DisplayName("channels that do not start at the origin's sample, and tile grids that miss the origin, are rejected")
        void rejected() {
            var samples = new int[4 * 4];
            var image = new Jp2Image(10, 10, 4, 4, 4, 4, 0, List.of(new Jp2Channel(0, 0, 4, 4, 1, 1, 8, false,
                    Jp2ChannelType.COLOR, 1, samples)), Jp2ColorSpace.GRAYSCALE, Optional.empty(), Optional.empty(),
                    Optional.empty());
            assertThrows(IllegalArgumentException.class, () -> Jp2Encoder.encode(image));
            var placed = new Jp2Image(10, 10, 4, 4, 4, 4, 0, List.of(new Jp2Channel(10, 10, 4, 4, 1, 1, 8, false,
                    Jp2ChannelType.COLOR, 1, samples)), Jp2ColorSpace.GRAYSCALE, Optional.empty(), Optional.empty(),
                    Optional.empty());
            Jp2Encoder.encode(placed);
            assertThrows(IllegalArgumentException.class, () -> Jp2Encoder.encode(placed,
                    Jp2EncoderOptions.builder().tileOrigin(11, 0).build()));
            assertThrows(IllegalArgumentException.class, () -> Jp2Encoder.encode(placed,
                    Jp2EncoderOptions.builder().tileSize(4, 4).build()));
            assertThrows(IllegalArgumentException.class, () -> Jp2Encoder.encode(picture(8, 8),
                    Jp2EncoderOptions.builder().imageOrigin(0xFFFF_FFFAL, 0).build()));
        }
    }

    @Nested
    @DisplayName("tile-parts and their markers")
    class TileParts {
        @ParameterizedTest(name = "{0}")
        @EnumSource(Jp2TilePartDivision.class)
        @DisplayName("every tile is divided at each value of the dimension, numbered in order")
        void division(Jp2TilePartDivision division) {
            var picture = picture(100, 70);
            var encoded = Jp2Encoder.encode(picture, Jp2EncoderOptions.lossy(20).toBuilder().format(Jp2Format.CODESTREAM)
                    .compressionRatios(60, 20, 0).reversible(true).tileSize(64, 64).tilePartDivision(division)
                    .build());
            var stream = parse(encoded);
            // under LRCP a tile-part starts with each layer, then each resolution, then each component
            var expected = switch (division) {
                case RESOLUTION -> 3 * 6;
                case LAYER -> 3;
                case COMPONENT -> 3 * 6 * 3;
            };
            for (var tile = 0; tile < 4; tile++) {
                var parts = stream.parts(tile);
                assertEquals(expected, parts.size(), "tile " + tile);
                for (var p = 0; p < parts.size(); p++) {
                    assertEquals(p, parts.get(p).index());
                    assertEquals(expected, parts.get(p).count());
                }
            }
            assertLossless(picture, encoded, division.name());
        }

        @Test
        @DisplayName("TLM segments list the tile and length of every tile-part, with two-byte tile indices beyond 255")
        void tlm() {
            var picture = picture(131, 87);
            for (var tile : List.of(64, 16)) {
                var encoded = Jp2Encoder.encode(picture, lossless().tileSize(tile, tile).tlmMarkers(true)
                        .tilePartDivision(Jp2TilePartDivision.RESOLUTION).build());
                var stream = parse(encoded);
                var wide = stream.parts().size() > 255;
                assertEquals(tile == 16, wide);
                var entries = new ArrayList<long[]>();
                for (var segment : stream.segments(TLM)) {
                    assertEquals(entries.size() / (wide ? 10921 : 13106), segment.u8(0), "Ztlm");
                    assertEquals(wide ? 0x60 : 0x50, segment.u8(1), "Stlm");
                    for (var at = 2; at < segment.body().length; at += wide ? 6 : 5) {
                        entries.add(new long[]{wide ? segment.u16(at) : segment.u8(at), segment.u32(at + (wide ? 2 : 1))});
                    }
                }
                assertEquals(stream.parts().size(), entries.size());
                for (var i = 0; i < entries.size(); i++) {
                    assertEquals(stream.parts().get(i).tile(), entries.get(i)[0]);
                    assertEquals(stream.parts().get(i).length(), entries.get(i)[1]);
                }
                assertLossless(picture, encoded, "TLM " + tile);
            }
        }

        @Test
        @DisplayName("PLT segments list every packet, split before a segment would exceed 65535 bytes")
        void plt() {
            var picture = picture(512, 512);
            var encoded = Jp2Encoder.encode(picture, lossless().pltMarkers(true).sopMarkers(true).codeBlockSize(4, 4)
                    .precinctSize(4, 4).build());
            var stream = parse(encoded);
            var segments = stream.parts().getFirst().segments(PLT);
            assertTrue(segments.size() >= 2, "segments " + segments.size());
            var lengths = new ArrayList<Long>();
            for (var z = 0; z < segments.size(); z++) {
                var segment = segments.get(z);
                assertEquals(z, segment.u8(0), "Zplt");
                assertTrue(segment.body().length + 2 <= 65535);
                var value = 0L;
                for (var at = 1; at < segment.body().length; at++) {
                    value = (value << 7) | (segment.u8(at) & 0x7F);
                    if ((segment.u8(at) & 0x80) == 0) {
                        lengths.add(value);
                        value = 0;
                    }
                }
                assertEquals(0, value, "a length runs past its segment");
            }
            var body = stream.parts().getFirst().body();
            var at = 0;
            var packet = 0;
            for (var length : lengths) {
                assertEquals(0xFF91, u16(body, at), "SOP of packet " + packet);
                assertEquals(packet & 0xFFFF, u16(body, at + 4));
                at += (int) (long) length;
                packet++;
            }
            assertEquals(body.length, at);
            assertLossless(picture, encoded, "PLT");
        }

        @Test
        @DisplayName("high-throughput codestreams take origins, tile-parts, length markers and progression changes")
        void highThroughput() {
            var picture = picture(90, 70);
            var encoded = Jp2Encoder.encode(picture, lossless().highThroughput(true).imageOrigin(5, 9).tileOrigin(1, 2)
                    .tileSize(48, 40).tilePartDivision(Jp2TilePartDivision.RESOLUTION).tlmMarkers(true)
                    .pltMarkers(true).progressionChanges(List.of(
                            new Jp2ProgressionChange(0, 0, 1, 3, 3, Jp2ProgressionOrder.RLCP),
                            new Jp2ProgressionChange(3, 0, 1, 6, 3, Jp2ProgressionOrder.LRCP)))
                    .build());
            var stream = parse(encoded);
            assertEquals(1, stream.segments(TLM).size());
            assertEquals(4 * 6, stream.parts().size());
            assertLossless(picture, encoded, "HTJ2K");
            var lossy = Jp2Encoder.encode(picture, Jp2EncoderOptions.lossy(12).toBuilder().highThroughput(true)
                    .format(Jp2Format.CODESTREAM).tileSize(48, 40).tilePartDivision(Jp2TilePartDivision.COMPONENT)
                    .tlmMarkers(true).build());
            assertTrue(lossy.length <= 90 * 70 * 3 / 12 + 400, "length " + lossy.length);
            Tolerance.psnr(25).assertWithin("HTJ2K lossy", picture,
                    Jp2Decoder.decode(MediaSource.of(lossy)).picture());
        }

        @Test
        @DisplayName("more than 255 tile-parts in one tile are rejected before coding")
        void tooMany() {
            var options = lossless().tilePartDivision(Jp2TilePartDivision.RESOLUTION).decompositionLevels(5)
                    .progressionOrder(Jp2ProgressionOrder.PCRL).precinctSize(4, 4).build();
            assertThrows(IllegalArgumentException.class, () -> Jp2Encoder.encode(picture(64, 64), options));
        }
    }

    @Nested
    @DisplayName("progression changes")
    class Progressions {
        @Test
        @DisplayName("the POC segment goes in the first tile-part header and orders the packets of every tile")
        void poc() {
            var picture = picture(120, 80);
            var changes = List.of(new Jp2ProgressionChange(0, 0, 2, 3, 3, Jp2ProgressionOrder.RLCP),
                    new Jp2ProgressionChange(0, 0, 4, 6, 3, Jp2ProgressionOrder.CPRL));
            var encoded = Jp2Encoder.encode(picture, lossless().reversible(true).compressionRatios(80, 30, 10, 0)
                    .tileSize(64, 64).precinctSize(32, 32).progressionChanges(changes).build());
            var stream = parse(encoded);
            assertTrue(stream.segments(POC).isEmpty());
            for (var tile = 0; tile < 4; tile++) {
                var parts = stream.parts(tile);
                assertEquals(2, parts.size(), "one tile-part per progression");
                var poc = parts.getFirst().segments(POC);
                assertEquals(1, poc.size());
                var body = poc.getFirst();
                assertEquals(14, body.body().length);
                assertArrayEquals(new int[]{0, 0, 2, 3, 3, 1}, new int[]{body.u8(0), body.u8(1), body.u16(2),
                        body.u8(4), body.u8(5), body.u8(6)});
                assertTrue(parts.get(1).segments(POC).isEmpty());
            }
            assertLossless(picture, encoded, "POC");
            for (var layers = 1; layers <= 4; layers++) {
                var smalt = Jp2Decoder.decodeChannels(MediaSource.of(encoded),
                        Jp2DecoderOptions.builder().qualityLayers(layers).build());
                if (OpenJpeg.find("opj_decompress").isPresent()) {
                    var planes = OpenJpeg.decode(encoded, "j2k", 0, layers);
                    assertNotNull(planes);
                    for (var c = 0; c < 3; c++) {
                        Tolerance.exact().assertWithin("layers " + layers + " channel " + c, planes[c].samples(),
                                smalt.channels().get(c).samples());
                    }
                }
            }
        }

        @Test
        @DisplayName("packets no progression covers are not written, and those covered twice are written once")
        void coverage() {
            var picture = picture(64, 64);
            var partial = Jp2Encoder.encode(picture, lossless().progressionChanges(List.of(
                    new Jp2ProgressionChange(0, 0, 1, 4, 3, Jp2ProgressionOrder.LRCP))).build());
            var reduced = Jp2Decoder.decodeChannels(MediaSource.of(partial),
                    Jp2DecoderOptions.builder().reduction(2).build());
            var full = Jp2Decoder.decodeChannels(MediaSource.of(Jp2Encoder.encode(picture, lossless().build())),
                    Jp2DecoderOptions.builder().reduction(2).build());
            for (var c = 0; c < 3; c++) {
                Tolerance.exact().assertWithin("reduced " + c, full.channels().get(c).samples(),
                        reduced.channels().get(c).samples());
            }
            var twice = Jp2Encoder.encode(picture, lossless().progressionChanges(List.of(
                    new Jp2ProgressionChange(0, 0, 1, 4, 3, Jp2ProgressionOrder.LRCP),
                    new Jp2ProgressionChange(0, 0, 1, 6, 3, Jp2ProgressionOrder.RLCP))).sopMarkers(true).build());
            var stream = parse(twice);
            var packets = 0;
            for (var part : stream.parts()) {
                for (var at = 0; at + 1 < part.body().length; at++) {
                    if (u16(part.body(), at) == 0xFF91 && part.body().length >= at + 6 && u16(part.body(), at + 2) == 4
                            && u16(part.body(), at + 4) == packets) {
                        packets++;
                    }
                }
            }
            assertEquals(6 * 3, packets);
            assertLossless(picture, twice, "twice");
        }
    }

    @Nested
    @DisplayName("regions of interest")
    class Regions {
        @Test
        @DisplayName("a lossless rectangle decodes exactly, with an RGN segment per component of each tile it meets")
        void losslessRectangle() {
            var picture = picture(96, 80);
            var encoded = Jp2Encoder.encode(picture, lossless().tileSize(48, 40)
                    .regionOfInterest(new Jp2RegionOfInterest.Rectangle(10, 12, 20, 15)).build());
            var stream = parse(encoded);
            assertTrue(stream.segments(RGN).isEmpty());
            var tile0 = stream.parts(0).getFirst().segments(RGN);
            assertEquals(3, tile0.size());
            for (var c = 0; c < 3; c++) {
                assertEquals(c, tile0.get(c).u8(0));
                assertEquals(0, tile0.get(c).u8(1), "Srgn");
                assertTrue(tile0.get(c).u8(2) > 0, "SPrgn");
            }
            assertTrue(stream.parts(3).getFirst().segments(RGN).isEmpty(), "a tile outside the region");
            assertLossless(picture, encoded, "rectangle");
        }

        @Test
        @DisplayName("a lossy rectangle is coded more precisely than without a region, and the background less")
        void lossyRectangle() {
            var picture = picture(160, 128);
            var base = Jp2EncoderOptions.lossy(40).toBuilder().format(Jp2Format.CODESTREAM);
            var plain = decode(Jp2Encoder.encode(picture, base.build()));
            var withRegion = Jp2Encoder.encode(picture, base.regionOfInterest(
                    new Jp2RegionOfInterest.Rectangle(40, 32, 48, 40)).build());
            var region = decode(withRegion);
            assertTrue(error(picture, region, 40, 32, 48, 40) < error(picture, plain, 40, 32, 48, 40) / 2,
                    "region error");
            assertTrue(error(picture, region, 0, 100, 160, 28) > error(picture, plain, 0, 100, 160, 28),
                    "background error");
            if (OpenJpeg.find("opj_decompress").isPresent()) {
                var planes = OpenJpeg.decode(withRegion, "j2k", 0, 0);
                assertNotNull(planes);
                for (var c = 0; c < 3; c++) {
                    Tolerance.exact().assertWithin("opj channel " + c, Jp2Testing.channel(region, c),
                            planes[c].samples());
                }
            }
        }

        @Test
        @DisplayName("a lossy component region is coded more precisely than without a region")
        void lossyComponent() {
            var picture = picture(128, 96);
            var base = Jp2EncoderOptions.lossy(60).toBuilder().colorTransform(false);
            var plain = decode(Jp2Encoder.encode(picture, base.build()));
            var favored = decode(Jp2Encoder.encode(picture, base.regionOfInterest(
                    new Jp2RegionOfInterest.Component(1, 8)).build()));
            assertTrue(Jp2Testing.mse(Jp2Testing.channel(picture, 1), Jp2Testing.channel(favored, 1))
                    < Jp2Testing.mse(Jp2Testing.channel(picture, 1), Jp2Testing.channel(plain, 1)));
        }

        @Test
        @DisplayName("a region of a component the image lacks, or deeper than the coder holds, is rejected")
        void rejected() {
            assertThrows(IllegalArgumentException.class, () -> Jp2Encoder.encode(picture(16, 16),
                    lossless().regionOfInterest(new Jp2RegionOfInterest.Component(3, 2)).build()));
            var samples = new int[32 * 32];
            for (var i = 0; i < samples.length; i++) {
                samples[i] = (i * 40503) & 0xFFFF;
            }
            var deep = new Jp2Image(0, 0, 32, 32, 32, 32, 0, List.of(new Jp2Channel(0, 0, 32, 32, 1, 1, 16, false,
                    Jp2ChannelType.COLOR, 1, samples)), Jp2ColorSpace.GRAYSCALE, Optional.empty(), Optional.empty(),
                    Optional.empty());
            assertThrows(IllegalArgumentException.class, () -> Jp2Encoder.encode(deep, Jp2EncoderOptions.builder()
                    .regionOfInterest(new Jp2RegionOfInterest.Rectangle(4, 4, 8, 8)).build()));
        }

        static RgbaPicture decode(byte[] encoded) {
            return (RgbaPicture) Jp2Decoder.decode(MediaSource.of(encoded)).picture();
        }

        static double error(RgbaPicture expected, RgbaPicture actual, int x, int y, int width, int height) {
            var total = 0.0;
            for (var row = y; row < y + height; row++) {
                for (var column = x; column < x + width; column++) {
                    for (var c = 0; c < 3; c++) {
                        var i = 4 * (row * expected.width() + column) + c;
                        var d = (expected.pixels()[i] & 0xFF) - (actual.pixels()[i] & 0xFF);
                        total += d * d;
                    }
                }
            }
            return total / (width * height * 3.0);
        }
    }

    @Nested
    @DisplayName("guard bits and size limits")
    class Limits {
        @Test
        @DisplayName("too few guard bits for a lossless sub-band are rejected, and saturate lossy coefficients")
        void guardBits() {
            var pixels = new byte[64 * 64 * 4];
            for (var y = 0; y < 64; y++) {
                for (var x = 0; x < 64; x++) {
                    var value = x % 4 != 2 && y % 4 != 2 ? 255 : 0;
                    Arrays.fill(pixels, 4 * (y * 64 + x), 4 * (y * 64 + x) + 3, (byte) value);
                    pixels[4 * (y * 64 + x) + 3] = (byte) 255;
                }
            }
            var picture = RgbaPicture.of(64, 64, 256, pixels, false);
            assertThrows(IllegalArgumentException.class, () -> Jp2Encoder.encode(picture,
                    Jp2EncoderOptions.builder().guardBits(0).build()));
            var lossy = (RgbaPicture) Jp2Decoder.decode(MediaSource.of(Jp2Encoder.encode(picture,
                    Jp2EncoderOptions.lossy(3).toBuilder().guardBits(0).build()))).picture();
            Tolerance.psnr(20).assertWithin("saturated", picture, lossy);
        }

        @Test
        @DisplayName("the codestream size limit caps every layer, lossless ones included")
        void codestreamLimit() {
            var picture = picture(160, 120);
            for (var options : List.of(Jp2EncoderOptions.lossless(), Jp2EncoderOptions.lossy(4),
                    Jp2EncoderOptions.ofPsnr(60))) {
                var limited = options.toBuilder().format(Jp2Format.CODESTREAM).maxCodestreamBytes(6000).build();
                assertFalse(limited.isLossless());
                var encoded = Jp2Encoder.encode(picture, limited);
                assertTrue(encoded.length <= 6000 + 200, "length " + encoded.length);
                assertTrue(encoded.length >= 6000 * 0.8, "length " + encoded.length);
                Jp2Decoder.decode(MediaSource.of(encoded));
            }
        }

        @Test
        @DisplayName("the component limit bounds the packets of each component, one tile-part per component")
        void componentLimit() {
            var picture = picture(160, 120);
            var encoded = Jp2Encoder.encode(picture, Jp2EncoderOptions.lossless().toBuilder().format(Jp2Format.CODESTREAM)
                    .tilePartDivision(Jp2TilePartDivision.COMPONENT).progressionOrder(Jp2ProgressionOrder.CPRL)
                    .maxComponentBytes(2500).build());
            var parts = parse(encoded).parts();
            assertEquals(3, parts.size());
            var largest = 0;
            for (var part : parts) {
                assertTrue(part.body().length <= 2500, "component bytes " + part.body().length);
                largest = Math.max(largest, part.body().length);
            }
            assertTrue(largest >= 2500 * 0.75, "largest component bytes " + largest);
            var loose = Jp2Encoder.encode(picture, Jp2EncoderOptions.lossless().toBuilder().maxComponentBytes(1 << 20)
                    .build());
            assertArrayEquals(Jp2Encoder.encode(picture), loose, "a limit that does not bind changes nothing");
        }
    }

    @Nested
    @DisplayName("profiles")
    class Profiles {
        /** A 12-bit RGB image and the same samples as a 16-bit PPM with maxval 4095. */
        static Jp2Image cinemaImage(int width, int height) {
            var channels = new ArrayList<Jp2Channel>();
            var picture = picture(width, height);
            for (var c = 0; c < 3; c++) {
                var samples = Jp2Testing.channel(picture, c);
                for (var i = 0; i < samples.length; i++) {
                    samples[i] = samples[i] * 16 + (i * 7 + c) % 16;
                }
                channels.add(new Jp2Channel(0, 0, width, height, 1, 1, 12, false, Jp2ChannelType.COLOR, c + 1, samples));
            }
            return new Jp2Image(0, 0, width, height, width, height, 0, channels, Jp2ColorSpace.SRGB, Optional.empty(),
                    Optional.empty(), Optional.empty());
        }

        static byte[] ppm12(Jp2Image image) {
            var out = new ByteArrayOutputStream();
            out.writeBytes(("P6\n" + image.width() + " " + image.height() + "\n4095\n").getBytes());
            for (var i = 0; i < image.width() * image.height(); i++) {
                for (var c = 0; c < 3; c++) {
                    var value = image.channels().get(c).samples()[i];
                    out.write(value >> 8);
                    out.write(value);
                }
            }
            return out.toByteArray();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Jp2Profile.class, names = {"CINEMA_2K", "CINEMA_4K"})
        @DisplayName("digital cinema codestreams carry the headers of opj_compress and meet the size limits")
        void cinema(Jp2Profile profile) {
            var image = cinemaImage(256, 136);
            var options = (profile == Jp2Profile.CINEMA_2K ? Jp2EncoderOptions.forCinema2k(24)
                    : Jp2EncoderOptions.forCinema4k()).toBuilder().format(Jp2Format.CODESTREAM).comment(COMMENT)
                    .maxCodestreamBytes(9000).maxComponentBytes(4000).build();
            var encoded = Jp2Encoder.encode(image, options);
            var stream = parse(encoded);
            assertEquals(profile == Jp2Profile.CINEMA_2K ? 3 : 4, stream.segment(SIZ).u16(0), "Rsiz");
            assertEquals(4, stream.segment(COD).u8(1), "CPRL");
            assertEquals(profile == Jp2Profile.CINEMA_2K ? 1 : 2, stream.segment(QCD).u8(0) >> 5, "guard bits");
            assertEquals(1, stream.segments(TLM).size());
            assertEquals(profile == Jp2Profile.CINEMA_4K ? 1 : 0, stream.segments(POC).size());
            assertEquals(profile == Jp2Profile.CINEMA_4K ? 6 : 3, stream.parts().size());
            assertTrue(encoded.length <= 9000 + 300, "length " + encoded.length);
            for (var c = 0; c < 3; c++) {
                var component = 0;
                for (var part : stream.parts()) {
                    component += profile == Jp2Profile.CINEMA_2K ? (part.index() == c ? part.body().length : 0) : 0;
                }
                assertTrue(component <= 4000, "component " + c + " bytes " + component);
            }
            var decoded = Jp2Decoder.decodeChannels(MediaSource.of(encoded));
            for (var c = 0; c < 3; c++) {
                var psnr = 10 * Math.log10(4095.0 * 4095.0 / Jp2Testing.mse(image.channels().get(c).samples(),
                        decoded.channels().get(c).samples()));
                assertTrue(psnr > 25, "PSNR " + psnr);
            }
            if (OpenJpeg.find("opj_compress").isPresent()) {
                var reference = parse(opjCompress(ppm12(image), "in.ppm", List.of(profile == Jp2Profile.CINEMA_2K
                        ? "-cinema2K" : "-cinema4K", profile == Jp2Profile.CINEMA_2K ? "24" : "-I"), "j2k"));
                for (var marker : List.of(SIZ, COD, QCD)) {
                    assertArrayEquals(reference.segment(marker).body(), stream.segment(marker).body(),
                            Integer.toHexString(marker));
                }
                assertEquals(reference.segments(POC).size(), stream.segments(POC).size());
                if (!reference.segments(POC).isEmpty()) {
                    assertArrayEquals(reference.segment(POC).body(), stream.segment(POC).body());
                }
                assertEquals(reference.parts().size(), stream.parts().size());
                assertEquals(reference.segment(TLM).body().length, stream.segment(TLM).body().length);
                var planes = OpenJpeg.decode(encoded, "j2k", 0, 0);
                assertNotNull(planes);
                for (var c = 0; c < 3; c++) {
                    Tolerance.exact().assertWithin("opj channel " + c, decoded.channels().get(c).samples(),
                            planes[c].samples());
                }
            }
        }

        @Test
        @DisplayName("a picture is coded as three 12-bit components under a cinema profile")
        void cinemaPicture() {
            var picture = Jp2EncoderTest.grayOf(picture(64, 48));
            var encoded = Jp2Encoder.encode(picture, Jp2EncoderOptions.forCinema2k(48).toBuilder()
                    .format(Jp2Format.CODESTREAM).build());
            var siz = parse(encoded).segment(SIZ);
            assertEquals(3, siz.u16(34));
            for (var c = 0; c < 3; c++) {
                assertEquals(11, siz.u8(36 + 3 * c));
            }
            var decoded = Jp2Decoder.decodeChannels(MediaSource.of(encoded));
            var expected = Jp2Testing.channel(picture, 0);
            for (var i = 0; i < expected.length; i++) {
                expected[i] = (expected[i] * 4095 + 127) / 255;
            }
            var psnr = 10 * Math.log10(4095.0 * 4095.0 / Jp2Testing.mse(expected, decoded.channels().get(1).samples()));
            assertTrue(psnr > 30, "PSNR " + psnr);
        }

        @Test
        @DisplayName("an IMF lossy profile records its levels in Rsiz and caps the size by the sub level's rate")
        void imf() {
            var options = Jp2EncoderOptions.forImf(Jp2Profile.IMF_2K, 3, 1, 24);
            assertEquals(200_000_000L / 8 / 24, options.maxCodestreamBytes());
            var encoded = Jp2Encoder.encode(picture(96, 64), options.toBuilder().format(Jp2Format.CODESTREAM)
                    .build());
            var stream = parse(encoded);
            assertEquals(0x0413, stream.segment(SIZ).u16(0));
            assertEquals(3, stream.parts().size());
            assertEquals(1, stream.segments(TLM).size());
        }

        @Test
        @DisplayName("images that break a profile are rejected")
        void rejected() {
            var eightBit = new Jp2Image(0, 0, 8, 8, 8, 8, 0, List.of(
                    new Jp2Channel(0, 0, 8, 8, 1, 1, 8, false, Jp2ChannelType.COLOR, 1, new int[64]),
                    new Jp2Channel(0, 0, 8, 8, 1, 1, 8, false, Jp2ChannelType.COLOR, 2, new int[64]),
                    new Jp2Channel(0, 0, 8, 8, 1, 1, 8, false, Jp2ChannelType.COLOR, 3, new int[64])),
                    Jp2ColorSpace.SRGB, Optional.empty(), Optional.empty(), Optional.empty());
            assertThrows(IllegalArgumentException.class, () -> Jp2Encoder.encode(eightBit,
                    Jp2EncoderOptions.forCinema2k(24)));
            assertThrows(IllegalArgumentException.class, () -> Jp2Encoder.encode(cinemaImage(2049, 1),
                    Jp2EncoderOptions.forCinema2k(24).toBuilder().decompositionLevels(0)
                            .precinctSizes(new int[]{128}, new int[]{128}).build()));
            assertThrows(IllegalArgumentException.class, () -> Jp2Encoder.encode(
                    Jp2EncoderTest.picture(16, 16, true, 1), Jp2EncoderOptions.forImf(Jp2Profile.IMF_2K, 0, 0, 0)));
            var reversible = Jp2EncoderOptions.forImf(Jp2Profile.IMF_2K_REVERSIBLE, 0, 0, 0);
            assertThrows(IllegalArgumentException.class, () -> Jp2Encoder.encode(picture(40, 40),
                    reversible.toBuilder().tileSize(16, 16).build()));
            assertThrows(IllegalArgumentException.class, () -> Jp2Encoder.encode(picture(1100, 8),
                    reversible.toBuilder().decompositionLevels(5).precinctSizes(new int[]{256, 256, 256, 256, 256},
                            new int[]{256, 256, 256, 256, 256}).build()));
            Jp2Encoder.encode(picture(1100, 8), reversible);
        }
    }

    @Nested
    @DisplayName("metadata boxes")
    class Metadata {
        static ExifData exif() {
            return ExifData.EMPTY.with(ExifData.Directory.IFD0, 0x013B, new ExifValue.Ascii("Ada Lovelace"));
        }

        static XmpPacket xmp() {
            return XmpPacket.empty().with(XmpPacket.DC, "title", "Analytical engine");
        }

        static IptcData iptc() {
            return IptcData.of(List.of(new IptcData.DataSet(2, 90, "Milano".getBytes(StandardCharsets.UTF_8))));
        }

        @Test
        @DisplayName("EXIF, XMP and IPTC go in uuid boxes after the JP2 header, read back here and by exiftool")
        void written() {
            var picture = picture(48, 32);
            var encoded = Jp2Encoder.encode(picture, Jp2EncoderOptions.builder().exif(exif()).xmp(xmp()).iptc(iptc())
                    .build());
            assertEquals(List.of("jP  ", "ftyp", "jp2h", "uuid", "uuid", "uuid", "jp2c"), boxes(encoded));
            var metadata = ImageMetadata.read(encoded);
            assertEquals(new ExifValue.Ascii("Ada Lovelace"), metadata.exif().orElseThrow()
                    .get(ExifData.Directory.IFD0, 0x013B).orElseThrow());
            assertEquals("Analytical engine", metadata.xmp().orElseThrow().text(XmpPacket.DC, "title").orElseThrow());
            assertEquals(List.of("Milano"), metadata.iptc().orElseThrow().strings(2, 90));
            var decoded = Jp2Decoder.decode(MediaSource.of(encoded));
            assertTrue(decoded.exif().isPresent());
            assertTrue(decoded.xmp().isPresent());
            assertLossless(picture, encoded, "metadata");
            if (Oracle.find(Oracle.Tool.EXIFTOOL).isPresent()) {
                var file = OpenJpeg.temp("metadata.jp2");
                try {
                    Files.write(file, encoded);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
                var output = new String(Oracle.run(Oracle.Tool.EXIFTOOL, Duration.ofMinutes(1),
                        List.of("-a", "-G1", "-s", "-Artist", "-XMP-dc:Title", "-IPTC:City", file.toString())),
                        StandardCharsets.UTF_8);
                assertTrue(output.contains("Ada Lovelace"), output);
                assertTrue(output.contains("Analytical engine"), output);
                assertTrue(output.contains("Milano"), output);
            }
        }

        @Test
        @DisplayName("an image's own EXIF and XMP are written unless the options carry their own, and codestreams hold none")
        void precedence() {
            var own = exif().toBytes();
            var image = new Jp2Image(0, 0, 4, 4, 4, 4, 0, List.of(new Jp2Channel(0, 0, 4, 4, 1, 1, 8, false,
                    Jp2ChannelType.COLOR, 1, new int[16])), Jp2ColorSpace.GRAYSCALE, Optional.empty(),
                    Optional.of(own), Optional.of(xmp().toBytes()));
            var kept = ImageMetadata.read(Jp2Encoder.encode(image));
            assertArrayEquals(own, kept.exifBytes().orElseThrow());
            var replaced = ImageMetadata.read(Jp2Encoder.encode(image, Jp2EncoderOptions.builder().exif(ExifData.EMPTY
                    .with(ExifData.Directory.IFD0, 0x013B, new ExifValue.Ascii("Grace"))).build()));
            assertEquals(new ExifValue.Ascii("Grace"), replaced.exif().orElseThrow()
                    .get(ExifData.Directory.IFD0, 0x013B).orElseThrow());
            assertTrue(replaced.xmp().isPresent());
            var raw = Jp2Encoder.encode(image, Jp2EncoderOptions.builder().format(Jp2Format.CODESTREAM).build());
            assertTrue(ImageMetadata.read(raw).isEmpty());
        }
    }
}
