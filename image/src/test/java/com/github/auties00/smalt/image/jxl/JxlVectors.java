package com.github.auties00.smalt.image.jxl;

import com.github.auties00.smalt.testkit.Conformance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The libjxl conformance suite (github.com/libjxl/conformance at a pinned commit): each case's
 * {@code input.jxl} and, when the case has one, its reference decode, a NumPy array of float32
 * samples shaped (frames, height, width, channels) in the orientation the image is displayed in,
 * with the published per-case RMSE and peak error bounds. The cases whose names end in {@code _5} are those of
 * the Main profile's level 5 list ({@code main_level5.txt}): they decode the input of the level 10 case of the same
 * name, against their own reference and within the looser level 5 bounds.
 */
final class JxlVectors {
    /**
     * A conformance case.
     *
     * @param name      the case name
     * @param reference the vector holding its reference decode, shared by the cases with identical references, or
     *                  {@code null} when the case has none
     * @param rmse      the RMSE bound
     * @param peak      the peak error bound
     */
    record Case(String name, String reference, double rmse, double peak) {
        Path input() {
            // a level 5 case decodes the input of its level 10 case, with the level 5 tolerances
            var source = name.endsWith("_5") ? name.substring(0, name.length() - 2) : name;
            return Conformance.vector("jxl-conformance-" + source + ".jxl");
        }

        Reference referenceDecode() {
            return Reference.read(Conformance.vector(reference));
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** A memory-mapped NumPy float32 array of shape (frames, height, width, channels). */
    record Reference(int frames, int height, int width, int channels, FloatBuffer samples) {
        static Reference read(Path file) {
            try (var channel = FileChannel.open(file)) {
                var map = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()).order(ByteOrder.LITTLE_ENDIAN);
                var headerLength = (map.get(8) & 0xFF) | ((map.get(9) & 0xFF) << 8);
                var bytes = new byte[headerLength];
                map.get(10, bytes);
                var header = new String(bytes, StandardCharsets.ISO_8859_1);
                if (!header.contains("'<f4'") || header.contains("'fortran_order': True")) {
                    throw new IllegalStateException("unexpected reference layout " + header);
                }
                var shape = header.substring(header.indexOf('(') + 1, header.indexOf(')')).split(",");
                var samples = map.slice(10 + headerLength, map.capacity() - 10 - headerLength)
                        .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
                return new Reference(Integer.parseInt(shape[0].trim()), Integer.parseInt(shape[1].trim()),
                        Integer.parseInt(shape[2].trim()), Integer.parseInt(shape[3].trim()), samples);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        float sample(int frame, int x, int y, int channel) {
            return samples.get((int) ((((long) frame * height + y) * width + x) * channels + channel));
        }
    }

    static final List<Case> CASES = List.of(
            new Case("alpha_nonpremultiplied", "jxl-conformance-alpha_nonpremultiplied.npy", 6.1035e-05, 6.1035e-05),
            new Case("alpha_premultiplied", "jxl-conformance-alpha_premultiplied.npy", 3.815e-06, 3.815e-06),
            new Case("alpha_triangles", "jxl-conformance-alpha_triangles.npy", 0.001953125, 0.001953125),
            new Case("animation_icos4d", "jxl-conformance-animation_icos4d.npy", 0.0001, 0.005),
            new Case("animation_newtons_cradle", "jxl-conformance-animation_newtons_cradle.npy", 0.000976562, 0.000976562),
            new Case("animation_spline", "jxl-conformance-animation_spline.npy", 0.0001, 0.004),
            new Case("bench_oriented_brg", "jxl-conformance-bench_oriented_brg.npy", 1e-05, 0.004),
            new Case("bicycles", "jxl-conformance-bicycles.npy", 0.000976562, 0.000976562),
            new Case("bike", "jxl-conformance-bike.npy", 0.0001, 0.007),
            new Case("blendmodes", "jxl-conformance-blendmodes.npy", 0.004, 0.0001),
            new Case("cafe", "jxl-conformance-cafe.npy", 1e-05, 0.004),
            new Case("cmyk_layers", "jxl-conformance-cmyk_layers.npy", 0.000976562, 0.000976562),
            new Case("delta_palette", "jxl-conformance-delta_palette.npy", 0.000976562, 0.000976562),
            new Case("grayscale", "jxl-conformance-grayscale.npy", 0.0001, 0.004),
            new Case("grayscale_jpeg", "jxl-conformance-grayscale_jpeg.npy", 1e-05, 0.004),
            new Case("grayscale_public_university", "jxl-conformance-grayscale_public_university.npy", 0.000976562, 0.000976562),
            new Case("lossless_pfm", "jxl-conformance-lossless_pfm.npy", 0.0, 0.0),
            new Case("lz77_flower", "jxl-conformance-lz77_flower.npy", 0.000976562, 0.000976562),
            new Case("mul_no_extra_channels", null, 0, 0),
            new Case("noise", "jxl-conformance-noise.npy", 0.0001, 0.004),
            new Case("opsin_inverse", "jxl-conformance-opsin_inverse.npy", 0.0001, 0.004),
            new Case("patches", "jxl-conformance-patches.npy", 0.0001, 0.004),
            new Case("patches_lossless", "jxl-conformance-patches_lossless.npy", 0.000976562, 0.000976562),
            new Case("progressive", "jxl-conformance-progressive.npy", 0.0001, 0.02),
            new Case("spot", "jxl-conformance-spot.npy", 3.815e-06, 3.815e-06),
            new Case("sunset_logo", "jxl-conformance-sunset_logo.npy", 0.000244141, 0.000244141),
            new Case("upsampling", "jxl-conformance-upsampling.npy", 0.0001, 0.004),
            new Case("animation_icos4d_5", "jxl-conformance-animation_icos4d_5.npy", 0.02, 0.06),
            new Case("animation_spline_5", "jxl-conformance-animation_spline.npy", 0.02, 0.06),
            new Case("bench_oriented_brg_5", "jxl-conformance-bench_oriented_brg.npy", 0.02, 0.06),
            new Case("bike_5", "jxl-conformance-bike.npy", 0.02, 0.06),
            new Case("blendmodes_5", "jxl-conformance-blendmodes.npy", 0.02, 0.06),
            new Case("cafe_5", "jxl-conformance-cafe.npy", 0.02, 0.06),
            new Case("grayscale_5", "jxl-conformance-grayscale.npy", 0.02, 0.06),
            new Case("grayscale_jpeg_5", "jxl-conformance-grayscale_jpeg.npy", 0.02, 0.06),
            new Case("noise_5", "jxl-conformance-noise.npy", 0.02, 0.06),
            new Case("opsin_inverse_5", "jxl-conformance-opsin_inverse.npy", 0.02, 0.06),
            new Case("patches_5", "jxl-conformance-patches_5.npy", 0.02, 0.06),
            new Case("progressive_5", "jxl-conformance-progressive.npy", 0.02, 0.06),
            new Case("upsampling_5", "jxl-conformance-upsampling_5.npy", 0.02, 0.06)
    );

    static Case find(String name) {
        return CASES.stream().filter(test -> test.name().equals(name)).findFirst().orElseThrow();
    }

    static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private JxlVectors() {
    }
}
