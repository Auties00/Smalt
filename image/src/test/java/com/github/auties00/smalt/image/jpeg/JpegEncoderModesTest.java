package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions.ColorSpace;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions.Density;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions.QuantizationTable;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions.Sampling;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions.Scan;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.github.auties00.smalt.image.jpeg.JpegTestSupport.md5;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the progressive, arithmetic, lossless, 12-bit, RGB, CMYK, YCCK, custom-table, sampling and JFIF-density
 * modes of the encoder byte for byte against libjpeg-turbo 3.1.3: every case of {@code encoder/cjpeg-cases.tsv}
 * against the MD5 of cjpeg's output recorded there (among them the MD5 sums of libjpeg-turbo's own CMake bit
 * tests for these modes) and, where cjpeg is installed, against a live cjpeg run; and the CMYK, YCCK and density
 * cases cjpeg cannot express against the TurboJPEG API through {@code encoder/turbojpeg-md5.csv}. Each output is
 * also decoded, and a lossless one must give back its samples exactly.
 */
@DisplayName("JpegEncoder coding modes")
class JpegEncoderModesTest {
    private static final Map<String, Picture> INPUTS = new ConcurrentHashMap<>();

    record Case(String name, String input, String args, String md5) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<Case> cjpegCases() {
        var cases = new ArrayList<Case>();
        for (var line : lines("encoder/cjpeg-cases.tsv")) {
            var fields = line.split("\t");
            cases.add(new Case(fields[0], fields[1], fields[2], fields[3]));
        }
        return cases;
    }

    static List<String> turbojpegCases() {
        return lines("encoder/turbojpeg-md5.csv");
    }

    private static List<String> lines(String resource) {
        var result = new ArrayList<String>();
        try (var reader = new BufferedReader(new InputStreamReader(open(resource), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    result.add(line);
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return result;
    }

    private static InputStream open(String resource) {
        var stream = JpegEncoderModesTest.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new IllegalStateException("missing resource " + resource);
        }
        return stream;
    }

    private static byte[] resource(String name) {
        try (var stream = open("encoder/" + name)) {
            return stream.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static byte[] file(String name) {
        return switch (name) {
            case "test1.icc" -> JpegTestVectors.test1Icc();
            case "test.scan" -> JpegTestVectors.testScan();
            default -> resource(name);
        };
    }

    private static byte[] image(String name) {
        return switch (name) {
            case "testorig.ppm" -> JpegTestVectors.testorigPpm();
            case "testorig.pgm" -> JpegTestVectors.testorigPgm();
            case "monkey16.ppm" -> JpegTestVectors.monkey16Ppm();
            case "monkey16.pgm" -> JpegTestVectors.monkey16Pgm();
            default -> throw new IllegalArgumentException(name);
        };
    }

    /**
     * A PNM image: its samples as unsigned values, interleaved.
     */
    record Pnm(int width, int height, int channels, int maxval, int[] samples) {
    }

    static Pnm readPnm(byte[] data) {
        var tokens = new String[4];
        var index = 0;
        var position = 0;
        while (index < 4) {
            while (Character.isWhitespace(data[position])) {
                position++;
            }
            if (data[position] == '#') {
                while (data[position] != '\n') {
                    position++;
                }
                continue;
            }
            var start = position;
            while (!Character.isWhitespace(data[position])) {
                position++;
            }
            tokens[index++] = new String(data, start, position - start, StandardCharsets.US_ASCII);
        }
        position++;
        var channels = tokens[0].equals("P6") ? 3 : 1;
        var width = Integer.parseInt(tokens[1]);
        var height = Integer.parseInt(tokens[2]);
        var maxval = Integer.parseInt(tokens[3]);
        var samples = new int[width * height * channels];
        for (var i = 0; i < samples.length; i++) {
            samples[i] = maxval > 255 ? ((data[position + 2 * i] & 0xFF) << 8) | (data[position + 2 * i + 1] & 0xFF)
                    : data[position + i] & 0xFF;
        }
        return new Pnm(width, height, channels, maxval, samples);
    }

    static Pnm crop(Pnm pnm, int width, int height) {
        var samples = new int[width * height * pnm.channels()];
        for (var y = 0; y < height; y++) {
            System.arraycopy(pnm.samples(), y * pnm.width() * pnm.channels(), samples, y * width * pnm.channels(),
                    width * pnm.channels());
        }
        return new Pnm(width, height, pnm.channels(), pnm.maxval(), samples);
    }

    static Pnm pnm(String input) {
        var at = input.indexOf('@');
        var pnm = readPnm(image(at < 0 ? input : input.substring(0, at)));
        if (at >= 0) {
            var size = input.substring(at + 1).split("x");
            pnm = crop(pnm, Integer.parseInt(size[0]), Integer.parseInt(size[1]));
        }
        return pnm;
    }

    static byte[] writePnm(Pnm pnm) {
        var wide = pnm.maxval() > 255;
        var header = (pnm.channels() == 3 ? "P6" : "P5") + "\n" + pnm.width() + " " + pnm.height() + "\n" + pnm.maxval()
                + "\n";
        var head = header.getBytes(StandardCharsets.US_ASCII);
        var data = new byte[head.length + pnm.samples().length * (wide ? 2 : 1)];
        System.arraycopy(head, 0, data, 0, head.length);
        for (var i = 0; i < pnm.samples().length; i++) {
            if (wide) {
                data[head.length + 2 * i] = (byte) (pnm.samples()[i] >> 8);
                data[head.length + 2 * i + 1] = (byte) pnm.samples()[i];
            } else {
                data[head.length + i] = (byte) pnm.samples()[i];
            }
        }
        return data;
    }

    /**
     * Returns the picture of a PNM image: RGBA or 16-bit RGBA for PPM, a gray planar picture for PGM.
     */
    static Picture picture(Pnm pnm) {
        var width = pnm.width();
        var height = pnm.height();
        var samples = pnm.samples();
        var wide = pnm.maxval() > 255;
        if (pnm.channels() == 3) {
            if (wide) {
                var picture = Rgba16Picture.allocate(width, height, false);
                for (var i = 0; i < width * height; i++) {
                    picture.pixels()[4 * i] = (short) samples[3 * i];
                    picture.pixels()[4 * i + 1] = (short) samples[3 * i + 1];
                    picture.pixels()[4 * i + 2] = (short) samples[3 * i + 2];
                    picture.pixels()[4 * i + 3] = (short) 0xFFFF;
                }
                return picture;
            }
            var picture = RgbaPicture.allocate(width, height, false);
            for (var i = 0; i < width * height; i++) {
                picture.pixels()[4 * i] = (byte) samples[3 * i];
                picture.pixels()[4 * i + 1] = (byte) samples[3 * i + 1];
                picture.pixels()[4 * i + 2] = (byte) samples[3 * i + 2];
                picture.pixels()[4 * i + 3] = (byte) 255;
            }
            return picture;
        }
        if (wide) {
            var picture = Yuv16Picture.allocate(width, height, ChromaFormat.MONOCHROME, 16, false, ColorInfo.JPEG);
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    picture.y()[y * picture.yStride() + x] = (short) samples[y * width + x];
                }
            }
            return picture;
        }
        var picture = YuvPicture.allocate(width, height, ChromaFormat.MONOCHROME, false, ColorInfo.JPEG);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                picture.y()[y * picture.yStride() + x] = (byte) samples[y * width + x];
            }
        }
        return picture;
    }

    /**
     * Maps cjpeg's switches onto the options that ask for the same file.
     */
    static JpegEncoderOptions options(String args, Pnm input) {
        var builder = JpegEncoderOptions.builder().optimizeHuffman(false);
        var tokens = args.split(" ");
        var baseline = false;
        var quality = false;
        var tables = false;
        var lossless = false;
        var rowRestart = 0;
        for (var i = 0; i < tokens.length; i++) {
            switch (tokens[i]) {
                case "-progressive" -> builder.progressive(true);
                case "-arithmetic" -> builder.arithmetic(true);
                case "-optimize" -> builder.optimizeHuffman(true);
                case "-grayscale" -> builder.chromaFormat(ChromaFormat.MONOCHROME);
                case "-rgb" -> builder.colorSpace(ColorSpace.RGB);
                case "-baseline" -> baseline = true;
                case "-quality" -> {
                    var values = tokens[++i].split(",");
                    builder.quality(Integer.parseInt(values[0]));
                    if (values.length > 1) {
                        builder.chromaQuality(Integer.parseInt(values[1]));
                    }
                    quality = true;
                }
                case "-sample" -> {
                    var factors = new ArrayList<Sampling>();
                    for (var factor : tokens[++i].split(",")) {
                        var parts = factor.split("x");
                        factors.add(Sampling.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
                    }
                    builder.samplingFactors(factors);
                }
                case "-restart" -> {
                    var value = tokens[++i];
                    if (value.endsWith("B")) {
                        builder.restartInterval(Integer.parseInt(value.substring(0, value.length() - 1)));
                    } else {
                        rowRestart = Integer.parseInt(value);
                    }
                }
                case "-precision" -> builder.precision(Integer.parseInt(tokens[++i]));
                case "-lossless" -> {
                    var values = tokens[++i].split(",");
                    builder.lossless(true).predictor(Integer.parseInt(values[0]));
                    if (values.length > 1) {
                        builder.pointTransform(Integer.parseInt(values[1]));
                    }
                    lossless = true;
                }
                case "-qtables" -> {
                    builder.quantizationTables(parseTables(file(tokens[++i])));
                    tables = true;
                }
                case "-qslots" -> builder.quantizationSlots(Arrays.stream(tokens[++i].split(","))
                        .mapToInt(Integer::parseInt).toArray());
                case "-scans" -> {
                    var script = parseScans(file(tokens[++i]));
                    builder.scanScript(script);
                    var first = script.getFirst();
                    if (first.spectralStart() != 0 && first.spectralEnd() == 0) {
                        builder.lossless(true);
                        lossless = true;
                    } else if (first.spectralStart() != 0 || first.spectralEnd() != 63) {
                        builder.progressive(true);
                    } else {
                        builder.progressive(false);
                    }
                }
                case "-icc" -> builder.colorProfile(ColorProfile.parse(file(tokens[++i])));
                default -> throw new IllegalArgumentException("unmapped cjpeg switch " + tokens[i]);
            }
        }
        builder.baselineQuantization(baseline || (!quality && !tables));
        if (input.channels() == 1) {
            builder.chromaFormat(ChromaFormat.MONOCHROME);
        } else if (lossless) {
            builder.colorSpace(ColorSpace.RGB);
        }
        if (rowRestart != 0) {
            builder.restartInterval(rowRestart * input.width());
        }
        return builder.build();
    }

    static List<QuantizationTable> parseTables(byte[] text) {
        var values = new ArrayList<Integer>();
        for (var line : new String(text, StandardCharsets.US_ASCII).split("\n")) {
            var hash = line.indexOf('#');
            var content = hash < 0 ? line : line.substring(0, hash);
            for (var token : content.trim().split("\\s+")) {
                if (!token.isEmpty()) {
                    values.add(Integer.parseInt(token));
                }
            }
        }
        var tables = new ArrayList<QuantizationTable>();
        for (var i = 0; i < values.size(); i += 64) {
            tables.add(new QuantizationTable(values.subList(i, i + 64)));
        }
        return tables;
    }

    static List<Scan> parseScans(byte[] text) {
        var scans = new ArrayList<Scan>();
        for (var entry : new String(text, StandardCharsets.US_ASCII).split(";")) {
            var trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            var colon = trimmed.indexOf(':');
            var components = Arrays.stream((colon < 0 ? trimmed : trimmed.substring(0, colon)).trim().split("\\s+"))
                    .map(Integer::valueOf).toList();
            if (colon < 0) {
                scans.add(new Scan(components, 0, 63, 0, 0));
            } else {
                var p = Arrays.stream(trimmed.substring(colon + 1).trim().split("\\s+")).mapToInt(Integer::parseInt)
                        .toArray();
                scans.add(new Scan(components, p[0], p[1], p[2], p[3]));
            }
        }
        return scans;
    }

    static byte[] encode(Case c) {
        var pnm = pnm(c.input());
        var picture = INPUTS.computeIfAbsent(c.input(), _ -> picture(pnm));
        return JpegEncoder.encode(picture, options(c.args(), pnm));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cjpegCases")
    @DisplayName("matches the recorded cjpeg output byte for byte")
    void matchesRecordedCjpeg(Case c) {
        assertEquals(c.md5(), md5(encode(c)), c.args());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cjpegCases")
    @DisplayName("decodes, and a lossless file gives back its samples")
    void decodes(Case c) {
        var jpeg = encode(c);
        var pnm = pnm(c.input());
        var options = options(c.args(), pnm);
        var decoded = JpegDecoder.decode(MediaSource.of(jpeg), ImageDecoderOptions.builder().highBitDepth(true)
                .build()).picture();
        assertEquals(pnm.width(), decoded.width());
        assertEquals(pnm.height(), decoded.height());
        if (!options.lossless()) {
            return;
        }
        var precision = options.precision().orElse(pnm.maxval() > 255 ? 16 : 8);
        var transforms = options.scanScript().isEmpty() ? null : options.scanScript();
        var maxIn = pnm.maxval();
        var maxOut = (1 << precision) - 1;
        for (var y = 0; y < pnm.height(); y++) {
            for (var x = 0; x < pnm.width(); x++) {
                for (var index = 0; index < pnm.channels(); index++) {
                    var channel = index;
                    var source = pnm.samples()[(y * pnm.width() + x) * pnm.channels() + channel];
                    var coded = (int) (((long) source * maxOut + maxIn / 2) / maxIn);
                    var shift = transforms == null ? options.pointTransform()
                            : transforms.stream().filter(s -> s.components().contains(channel)).findFirst()
                            .orElseThrow().successiveLow();
                    var expected = (coded >> shift) << shift;
                    var actual = codedSample(decoded, x, y, channel, precision);
                    if (expected != actual) {
                        throw new AssertionError("sample " + channel + " at " + x + "," + y + ": " + actual
                                + " != " + expected);
                    }
                }
            }
        }
    }

    /**
     * Returns a decoded sample at the coded precision.
     */
    private static int codedSample(Picture decoded, int x, int y, int channel, int precision) {
        var max = (1 << precision) - 1;
        return switch (decoded) {
            case RgbaPicture rgba -> {
                var value = rgba.pixels()[y * rgba.stride() + 4 * x + channel] & 0xFF;
                yield precision == 8 ? value : (int) Math.round(value * (double) max / 255);
            }
            case Rgba16Picture rgba -> {
                var value = rgba.pixels()[y * rgba.stride() + 4 * x + channel] & 0xFFFF;
                yield (int) Math.round(value * (double) max / 65535);
            }
            case YuvPicture yuv -> {
                var value = yuv.y()[y * yuv.yStride() + x] & 0xFF;
                yield precision == 8 ? value : (int) Math.round(value * (double) max / 255);
            }
            case Yuv16Picture yuv -> yuv.y()[y * yuv.yStride() + x] & 0xFFFF;
            default -> throw new AssertionError("unexpected " + decoded);
        };
    }

    @Nested
    @DisplayName("against a live cjpeg")
    class LiveCjpeg {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.jpeg.JpegEncoderModesTest#cjpegCases")
        @DisplayName("writes the bytes cjpeg writes")
        void matchesCjpeg(Case c) throws IOException {
            Oracle.assume(Oracle.Tool.CJPEG);
            var directory = Scratch.directory("jpeg-encoder-modes");
            var arguments = new ArrayList<>(List.of("-dct", "int"));
            var tokens = c.args().split(" ");
            for (var i = 0; i < tokens.length; i++) {
                arguments.add(tokens[i]);
                if (tokens[i].equals("-qtables") || tokens[i].equals("-scans") || tokens[i].equals("-icc")) {
                    var name = tokens[++i];
                    var path = directory.resolve(name);
                    Files.write(path, file(name));
                    arguments.add(path.toString());
                }
            }
            var expected = Oracle.run(Oracle.Tool.CJPEG, Duration.ofMinutes(1), writePnm(pnm(c.input())), arguments);
            assertEquals(c.md5(), md5(expected), "the recorded MD5 no longer matches cjpeg");
            assertArrayEquals(expected, encode(c));
        }
    }

    /**
     * Returns the stored 16-bit sample of the synthetic pattern of turbojpeg-record.py.
     */
    static int pattern(int x, int y, int c, int width, int height) {
        var smooth = (x * 65535 / Math.max(1, width - 1) + y * 40000 / Math.max(1, height - 1) + c * 9000) % 65536;
        var detail = ((x * 7 + y * 13 + c * 61 + (x * y) % 29) & 0xFF) << 4;
        return (smooth + detail) & 0xFFFF;
    }

    static List<Arguments> turbojpeg() {
        return turbojpegCases().stream().map(line -> Arguments.of(line.split(",")[0], line)).toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("turbojpeg")
    @DisplayName("matches TurboJPEG's CMYK, YCCK and density output byte for byte")
    void matchesTurbojpeg(String name, String line) {
        var f = line.split(",");
        var width = Integer.parseInt(f[1]);
        var height = Integer.parseInt(f[2]);
        var space = f[3];
        var subsampling = f[4];
        var precision = Integer.parseInt(f[10]);
        var cmyk = space.equals("CMYK") || space.equals("YCCK");
        Picture picture;
        if (cmyk && precision == 8) {
            var channels = Channel8Picture.allocate(width, height, PixelLayout.CMYK);
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    for (var c = 0; c < 4; c++) {
                        channels.pixels()[y * channels.stride() + 4 * x + c] =
                                (byte) (255 - (pattern(x, y, c, width, height) >> 8));
                    }
                }
            }
            picture = channels;
        } else if (cmyk) {
            var channels = Channel16Picture.allocate(width, height, PixelLayout.CMYK);
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    for (var c = 0; c < 4; c++) {
                        channels.pixels()[y * channels.stride() + 4 * x + c] =
                                (short) (65535 - pattern(x, y, c, width, height));
                    }
                }
            }
            picture = channels;
        } else {
            var rgba = RgbaPicture.allocate(width, height, false);
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    for (var c = 0; c < 3; c++) {
                        rgba.pixels()[y * rgba.stride() + 4 * x + c] = (byte) (pattern(x, y, c, width, height) >> 8);
                    }
                    rgba.pixels()[y * rgba.stride() + 4 * x + 3] = (byte) 255;
                }
            }
            picture = rgba;
        }
        var builder = JpegEncoderOptions.builder()
                .quality(Integer.parseInt(f[5]))
                .optimizeHuffman(f[6].equals("1"))
                .progressive(f[7].equals("1"))
                .arithmetic(f[8].equals("1"))
                .restartInterval(Integer.parseInt(f[9]))
                .precision(precision)
                .density(new Density(JpegEncoderOptions.DensityUnit.values()[Integer.parseInt(f[11])],
                        Integer.parseInt(f[12]), Integer.parseInt(f[13])));
        var format = switch (subsampling) {
            case "444" -> ChromaFormat.YUV444;
            case "422" -> ChromaFormat.YUV422;
            case "420" -> ChromaFormat.YUV420;
            case "GRAY" -> ChromaFormat.MONOCHROME;
            default -> null;
        };
        switch (space) {
            case "CMYK" -> {
                builder.colorSpace(ColorSpace.CMYK);
                if (format == ChromaFormat.YUV420) {
                    builder.samplingFactors(Sampling.of(2, 2), Sampling.of(1, 1), Sampling.of(1, 1), Sampling.of(2, 2));
                }
            }
            case "YCCK" -> {
                builder.colorSpace(ColorSpace.YCCK);
                if (format != null) {
                    builder.chromaFormat(format);
                } else if (subsampling.equals("440")) {
                    builder.samplingFactors(Sampling.of(1, 2), Sampling.of(1, 1), Sampling.of(1, 1), Sampling.of(1, 2));
                } else {
                    builder.samplingFactors(Sampling.of(4, 1), Sampling.of(1, 1), Sampling.of(1, 1), Sampling.of(4, 1));
                }
            }
            default -> builder.chromaFormat(format);
        }
        var jpeg = JpegEncoder.encode(picture, builder.build());
        assertEquals(f[14], md5(jpeg), name);
        var decoded = JpegDecoder.decode(MediaSource.of(jpeg), ImageDecoderOptions.defaults()).picture();
        assertEquals(width, decoded.width());
        assertTrue(decoded.height() == height);
    }
}
