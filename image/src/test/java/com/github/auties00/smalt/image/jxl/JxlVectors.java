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
    static final String COMMIT = "b1d0f990b03e57bf6d137c365cd5dc8b470b9191";

    record Case(String name, String inputSha256, String referenceSha256, double rmse, double peak) {
        Path input() {
            // a level 5 case decodes the input of its level 10 case, with the level 5 tolerances
            var source = name.endsWith("_5") ? name.substring(0, name.length() - 2) : name;
            return Conformance.fetch("jxl-conformance-" + source + ".jxl",
                    "https://raw.githubusercontent.com/libjxl/conformance/" + COMMIT + "/testcases/" + source + "/input.jxl",
                    inputSha256);
        }

        Reference reference() {
            // a reference shared with an earlier case is cached under that case's name
            var owner = CASES.stream().filter(other -> referenceSha256.equals(other.referenceSha256())).findFirst()
                    .orElse(this).name();
            var file = Conformance.fetch("jxl-conformance-" + owner + ".npy",
                    "https://storage.googleapis.com/jxl-conformance/objects/" + referenceSha256, referenceSha256);
            return Reference.read(file);
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
            new Case("alpha_nonpremultiplied", "15acbe3edbfd5a75c7609726ae60526ffc812642b5dd6be8475f0b990ce9b1db", "cad070b944d8aff6b7865c211e44bc469b08addf5b4a19d11fdc4ef2f7494d1b", 6.1035e-05, 6.1035e-05),
            new Case("alpha_premultiplied", "5028e630dc358bf4031cbf06e8a80f2bfbfa21fd0d088af74d83b473d3dd540a", "073c1d942ba408f94f6c0aed2fef3d442574899901c616afa060dbd8044bbdb9", 3.815e-06, 3.815e-06),
            new Case("alpha_triangles", "19ac7752a23ad2b22814064cb6b62a581b48be18ed73b5ccc2340888c114d2c9", "1d8471e3b7f0768f408b5e5bf5fee0de49ad6886d846712b1aaa702379722e2b", 0.001953125, 0.001953125),
            new Case("animation_icos4d", "6df934e61b07779626988daa0c4a9f05f89e6142a030f915e8b8ea52c37b3fe2", "77a060cfa0d4df183255424e13e4f41a90b3edcea1248e3f22a3b7fcafe89e49", 0.0001, 0.005),
            new Case("animation_newtons_cradle", "35a13d365f5eb88c3249afe073b7d8b0174d74e38f7edaa8805ca8ab46a59ab4", "4309286cd22fa4008db3dcceee6a72a806c9291bd7e035cf555f3b470d0693d8", 0.000976562, 0.000976562),
            new Case("animation_spline", "87793cac33d05eaa380011e3b0754ff6f228967431a126fd0a0ace2106940c79", "a571c5cbba58affeeb43c44c13f81e2b1962727eb9d4e017e4f25d95c7388f10", 0.0001, 0.004),
            new Case("bench_oriented_brg", "e223fed907c6238622b2b6ec1c80609050c9d2db4d759cf3ca6f0db304cbb82a", "eac8c30907e41e53a73a0c002bc39e998e0ceb021bd523f5bff4580b455579e6", 1e-05, 0.004),
            new Case("bicycles", "8ef03ccccb45bdb5f3d37c9a4c392f745e3e4ad7f4ffc2cbc1e1ba68487a75a6", "6f71d8ca122872e7d850b672e7fb46b818c2dfddacd00b3934fe70aa8e0b327e", 0.000976562, 0.000976562),
            new Case("bike", "639efe13b89868688234ebb9ec36a0ed848ba01bde43681d3d37438d2e0c0370", "815c89d1fe0bf67b6a1c8139d0af86b6e3f11d55c5a0ed9396256fb05744469e", 0.0001, 0.007),
            new Case("blendmodes", "b22d487440dd591fbadd4e75398506070497283f38525433961b1e809c75c860", "6ef265631818f313a70cb23788d1185408ce07243db8d5940553e7ea7467b786", 0.004, 0.0001),
            new Case("cafe", "cb4603bd089483fc1d63a039a3b758ec510f0090b74ce245b88b1e8feb54f0eb", "4aaea4e1bda3771e62643fcdf2003ffe6048ee2870c93f67d34d6cc16cb7da4b", 1e-05, 0.004),
            new Case("cmyk_layers", "d732c8836bf1abeadf310d2e07387a32813ed4690d32650c1c25e541b80eed4a", "a01913d4e4b1a89bd96e5de82a5dfb9925c7827ee6380ad60c0b1c4becb53880", 0.000976562, 0.000976562),
            new Case("delta_palette", "00e24cc453cdf84897d62b0aafc7a9f7024205bbce1922e99d7ad0003759ae7c", "952b9e16aa0ae23df38c6b358cb4835b5f9479838f6855b96845ea54b0528c1f", 0.000976562, 0.000976562),
            new Case("grayscale", "78fbbba852e99946d187dcf0bcbd7fb0e7c22be2f0852523aaae6ed91e7e3c39", "59162e158e042dc44710eb4d334cea78135399613461079582d963fe79251b68", 0.0001, 0.004),
            new Case("grayscale_jpeg", "346cc92b55864b0efb9f85cdddd02b586473ff18b03718e5a02fa03cb3e86844", "c0b86989e287649b944f1734ce182d1c1ac0caebf12cec7d487d9793f51f5b8f", 1e-05, 0.004),
            new Case("grayscale_public_university", "b1c6fe79167858f86a23e0f32c1c5ee7602dae6f82991c1dd53bcb89a7d78606", "851abd36b93948cfaeabeb65c8bb8727ebca4bb1d2697bce73461e05ccf24c1e", 0.000976562, 0.000976562),
            new Case("lossless_pfm", "61ae52b5851ab2e156aec1d22502e8e1ec0cdf6bc0d6a3956ac6d7d6d2969d5e", "1eac3ced5c60ef8a3a602f54c6a9d28162dfee51cd85b8dd7e52f6e3212bbb52", 0.0, 0.0),
            new Case("lz77_flower", "41046f0de4dd36177aefa9b53fe6c3cd35a8e2c215a8d41ca89a6a9bc7f27541", "953d3ada476e3218653834c9addc9c16bb6f9f03b18be1be8a85c07a596ea32d", 0.000976562, 0.000976562),
            new Case("mul_no_extra_channels", "9c25489b645c9715de1cf7d9b27360ae982401e350622ef0fe46368b369592ee", null, 0, 0),
            new Case("noise", "20cddadc3fdbae331a912dba0d14e6326008094d7a16d006961ccdeac2bc9950", "b7bb25b911ab5f4b9a6a6da9c220c9ea738de685f9df25eb860e6bbe1302237d", 0.0001, 0.004),
            new Case("opsin_inverse", "5c25ba126e6fb3536a12ca191b92f1c3be86ee74683688ed56c94804db5d2e16", "a3142a144c112160b8e5a12eb17723fa5fe0cfeb00577e4fb59a5f6cea126a9b", 0.0001, 0.004),
            new Case("patches", "3d8d3847ba40844b1d829366065261f34cc4b4acc3d9006342c64127c1052a3e", "7f1309014d9c30efe4342a0e0a46767967b756fdfdc393e2e702edea4b1fd0bd", 0.0001, 0.004),
            new Case("patches_lossless", "2a47b6766264ac6243a43bbb85684688717591bb3bd9b4c2db56226192418b4e", "806201a2c99d27a54c400134b3db7bfc57476f9bc0775e59eea802d28aba75de", 0.000976562, 0.000976562),
            new Case("progressive", "bb4722ab780dce609a7b007fe7fe235962b351691f60ffb2cde7b4808c8601e1", "5a9d25412e2393ee11632942b4b683cda3f838dd72ab2550cfffc8f34d69c852", 0.0001, 0.02),
            new Case("spot", "69d48f7ace25683db9eb908f112cef112bd1583870abab0fbf166018889893fa", "82de72e756db992792b8e3eb5eac5194ef83e9ab4dc03e846492fbedde7b58da", 3.815e-06, 3.815e-06),
            new Case("sunset_logo", "6617480923e1fdef555e165a1e7df9ca648068dd0bdbc41a22c0e4213392d834", "bf1c1d5626ced3746df867cf3e3a25f3d17512c2e837b5e3a04743660e42ad81", 0.000244141, 0.000244141),
            new Case("upsampling", "d3d97aa5f91401ad6df137b647164e402c7a99d66f8a6f72734562bf844b69d2", "9b83952c4bba9dc93fd5c5c49e27eab29301e848bf70dceccfec96b48d3ab975", 0.0001, 0.004),
            new Case("animation_icos4d_5", "6df934e61b07779626988daa0c4a9f05f89e6142a030f915e8b8ea52c37b3fe2", "ddf2d3c90f64e55a4701c86fe603846d1d515e147e83e1f9cb6ea925d6266cf9", 0.02, 0.06),
            new Case("animation_spline_5", "87793cac33d05eaa380011e3b0754ff6f228967431a126fd0a0ace2106940c79", "a571c5cbba58affeeb43c44c13f81e2b1962727eb9d4e017e4f25d95c7388f10", 0.02, 0.06),
            new Case("bench_oriented_brg_5", "e223fed907c6238622b2b6ec1c80609050c9d2db4d759cf3ca6f0db304cbb82a", "eac8c30907e41e53a73a0c002bc39e998e0ceb021bd523f5bff4580b455579e6", 0.02, 0.06),
            new Case("bike_5", "639efe13b89868688234ebb9ec36a0ed848ba01bde43681d3d37438d2e0c0370", "815c89d1fe0bf67b6a1c8139d0af86b6e3f11d55c5a0ed9396256fb05744469e", 0.02, 0.06),
            new Case("blendmodes_5", "b22d487440dd591fbadd4e75398506070497283f38525433961b1e809c75c860", "6ef265631818f313a70cb23788d1185408ce07243db8d5940553e7ea7467b786", 0.02, 0.06),
            new Case("cafe_5", "cb4603bd089483fc1d63a039a3b758ec510f0090b74ce245b88b1e8feb54f0eb", "4aaea4e1bda3771e62643fcdf2003ffe6048ee2870c93f67d34d6cc16cb7da4b", 0.02, 0.06),
            new Case("grayscale_5", "78fbbba852e99946d187dcf0bcbd7fb0e7c22be2f0852523aaae6ed91e7e3c39", "59162e158e042dc44710eb4d334cea78135399613461079582d963fe79251b68", 0.02, 0.06),
            new Case("grayscale_jpeg_5", "346cc92b55864b0efb9f85cdddd02b586473ff18b03718e5a02fa03cb3e86844", "c0b86989e287649b944f1734ce182d1c1ac0caebf12cec7d487d9793f51f5b8f", 0.02, 0.06),
            new Case("noise_5", "20cddadc3fdbae331a912dba0d14e6326008094d7a16d006961ccdeac2bc9950", "b7bb25b911ab5f4b9a6a6da9c220c9ea738de685f9df25eb860e6bbe1302237d", 0.02, 0.06),
            new Case("opsin_inverse_5", "5c25ba126e6fb3536a12ca191b92f1c3be86ee74683688ed56c94804db5d2e16", "a3142a144c112160b8e5a12eb17723fa5fe0cfeb00577e4fb59a5f6cea126a9b", 0.02, 0.06),
            new Case("patches_5", "3d8d3847ba40844b1d829366065261f34cc4b4acc3d9006342c64127c1052a3e", "477af1efc77cef815c959375c6d9caad20e4b07bcdd5aab05b3019c0b6066e56", 0.02, 0.06),
            new Case("progressive_5", "bb4722ab780dce609a7b007fe7fe235962b351691f60ffb2cde7b4808c8601e1", "5a9d25412e2393ee11632942b4b683cda3f838dd72ab2550cfffc8f34d69c852", 0.02, 0.06),
            new Case("upsampling_5", "d3d97aa5f91401ad6df137b647164e402c7a99d66f8a6f72734562bf844b69d2", "3c5362aa07f071b76c3570490b9229ede547db04ccc3f3ed6cf03f53b08708cf", 0.02, 0.06)
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
