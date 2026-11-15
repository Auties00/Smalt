package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.testkit.Conformance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * Reads the JPEG-LS vectors of {@code tools/testdata/jpegls} and regenerates the synthetic images they code, with
 * the arithmetic of {@code make-vectors.py}.
 */
final class JpegLsVectors {
    /**
     * The resource directory of the vectors.
     */
    static final String DIRECTORY = "/com/github/auties00/smalt/image/jpeg/jpegls/";

    private JpegLsVectors() {
    }

    /**
     * One row of {@code charls.tsv}.
     */
    record CharLsCase(String name, int width, int height, int components, int bits, int interleave, int near,
                      int maxval, int t1, int t2, int t3, int reset, int transform, String spiff, int seed,
                      String decoded) {
        @Override
        public String toString() {
            return name;
        }

        boolean mapped() {
            return spiff.startsWith("table");
        }

        int entrySize() {
            return Integer.parseInt(spiff.substring(5));
        }
    }

    /**
     * One row of {@code referenceA.tsv}.
     */
    record ReferenceCase(String name, int components, int bits, String options, int seed, String decoded) {
        @Override
        public String toString() {
            return name;
        }
    }

    static byte[] resource(String name) {
        try (var input = JpegLsVectors.class.getResourceAsStream(DIRECTORY + name)) {
            if (input == null) {
                throw new IllegalStateException("missing resource " + name);
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static List<String[]> rows(String name) {
        var rows = new ArrayList<String[]>();
        for (var line : new String(resource(name), StandardCharsets.UTF_8).split("\n")) {
            if (!line.isBlank() && !line.startsWith("#")) {
                rows.add(line.split("\t"));
            }
        }
        return rows;
    }

    static List<CharLsCase> charls() {
        var cases = new ArrayList<CharLsCase>();
        for (var r : rows("charls.tsv")) {
            cases.add(new CharLsCase(r[0], Integer.parseInt(r[1]), Integer.parseInt(r[2]), Integer.parseInt(r[3]),
                    Integer.parseInt(r[4]), Integer.parseInt(r[5]), Integer.parseInt(r[6]), Integer.parseInt(r[7]),
                    Integer.parseInt(r[8]), Integer.parseInt(r[9]), Integer.parseInt(r[10]), Integer.parseInt(r[11]),
                    Integer.parseInt(r[12]), r[13], Integer.parseInt(r[14]), r[15]));
        }
        return cases;
    }

    static List<ReferenceCase> reference() {
        var cases = new ArrayList<ReferenceCase>();
        for (var r : rows("referenceA.tsv")) {
            cases.add(new ReferenceCase(r[0], Integer.parseInt(r[1]), Integer.parseInt(r[2]), r[3],
                    Integer.parseInt(r[4]), r[5]));
        }
        return cases;
    }

    /**
     * Regenerates a synthetic image: planar samples of flat, gradient, noisy gradient and noise regions of 8x8
     * blocks, each component at its own size.
     */
    static int[][] synthetic(int width, int height, int components, int bits, int seed, int[][] factors) {
        var mask = (1L << bits) - 1;
        var hMax = 1;
        var vMax = 1;
        for (var c = 0; c < components; c++) {
            hMax = Math.max(hMax, factors == null ? 1 : factors[c][0]);
            vMax = Math.max(vMax, factors == null ? 1 : factors[c][1]);
        }
        long state = seed;
        var planes = new int[components][];
        for (var c = 0; c < components; c++) {
            var w = (width * (factors == null ? 1 : factors[c][0]) + hMax - 1) / hMax;
            var h = (height * (factors == null ? 1 : factors[c][1]) + vMax - 1) / vMax;
            var plane = new int[w * h];
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    state = (state * 1103515245L + 12345) & 0x7FFFFFFFL;
                    var noise = (state >> 16) & 15;
                    var region = ((x >> 3) + (y >> 3) + c) % 4;
                    long gradient = x * 5L + y * 3L + c * 17L;
                    long v = switch (region) {
                        case 0 -> c * 40L + 20;
                        case 1 -> gradient;
                        case 2 -> gradient + noise;
                        default -> state >> 8;
                    };
                    if (bits >= 8) {
                        v = (v << (bits - 8)) | (v & ((1L << (bits - 8)) - 1));
                    } else {
                        v >>= 8 - bits;
                    }
                    plane[y * w + x] = (int) (v & mask);
                }
            }
            planes[c] = plane;
        }
        return planes;
    }

    /**
     * {@return the entry of the mapping table of a {@code charls.tsv} mapped case, one byte of it}
     */
    static int tableByte(int value, int index) {
        return (value * (37 + 50 * index) + 11 * index) & 0xFF;
    }

    /**
     * {@return an entry of the T.87 conformance data set}
     */
    static byte[] t87(String name) {
        var archive = Conformance.fetchListed("itu-t87-data-set.zip");
        try (var zip = new ZipFile(archive.toFile())) {
            var entry = zip.getEntry(name);
            if (entry == null) {
                throw new IllegalStateException("no " + name + " in the T.87 data set");
            }
            try (var input = zip.getInputStream(entry)) {
                return input.readAllBytes();
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Reads the samples of a binary PGM or PPM file, components interleaved.
     *
     * @return {@code {width, height, components, maxval}} followed by the samples
     */
    static int[] pnm(byte[] data) {
        var fields = new int[4];
        var position = 0;
        var field = 0;
        fields[2] = data[1] == '6' ? 3 : 1;
        position = 2;
        var values = new int[3];
        while (field < 3) {
            while (Character.isWhitespace(data[position])) {
                position++;
            }
            if (data[position] == '#') {
                while (data[position] != '\n') {
                    position++;
                }
                continue;
            }
            var value = 0;
            while (!Character.isWhitespace(data[position])) {
                value = value * 10 + data[position] - '0';
                position++;
            }
            values[field++] = value;
        }
        position++;
        fields[0] = values[0];
        fields[1] = values[1];
        fields[3] = values[2];
        var count = fields[0] * fields[1] * fields[2];
        var out = new int[4 + count];
        System.arraycopy(fields, 0, out, 0, 4);
        var wide = fields[3] > 255;
        for (var i = 0; i < count; i++) {
            out[4 + i] = wide ? ((data[position + 2 * i] & 0xFF) << 8) | (data[position + 2 * i + 1] & 0xFF)
                    : data[position + i] & 0xFF;
        }
        return out;
    }

    /**
     * {@return the planes of one PNM component}
     */
    static int[] pnmComponent(int[] pnm, int component) {
        var count = pnm[2];
        var plane = new int[pnm[0] * pnm[1]];
        for (var i = 0; i < plane.length; i++) {
            plane[i] = pnm[4 + i * count + component];
        }
        return plane;
    }

    /**
     * Decodes the components of a stream through the reader, each at its own size.
     */
    static int[][] decode(byte[] stream) {
        return decode(stream, MediaLimits.DEFAULT);
    }

    /**
     * Decodes the components of a stream through the reader under the given limits.
     */
    static int[][] decode(byte[] stream, MediaLimits limits) {
        var reader = new JpegReader(stream, 0, stream.length, limits);
        reader.readHeaders();
        var samples = reader.openLossless();
        var frame = reader.frame();
        var planes = new int[frame.components.length][];
        for (var c = 0; c < planes.length; c++) {
            planes[c] = new int[frame.components[c].width * frame.components[c].height];
        }
        samples.decodeComponents((component, row, values) -> {
            var width = frame.components[component].width;
            System.arraycopy(values, 0, planes[component], row * width, width);
        });
        return planes;
    }

    /**
     * {@return the SHA-256 of planes as 16-bit little-endian samples, one plane after another}
     */
    static String sha256(int[][] planes) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var plane : planes) {
                var bytes = new byte[plane.length * 2];
                for (var i = 0; i < plane.length; i++) {
                    bytes[2 * i] = (byte) plane[i];
                    bytes[2 * i + 1] = (byte) (plane[i] >>> 8);
                }
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
