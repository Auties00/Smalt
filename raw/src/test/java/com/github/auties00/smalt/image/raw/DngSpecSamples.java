package com.github.auties00.smalt.image.raw;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Builds small DNG files of the layouts and profile features of DNG 1.2 to 1.7 for the I/O behavior harness and
 * the fuzz seeds: samples of more than 16 bits, separately stored planes, sub-tile blocks with interleaved
 * fields, a staggered CFA, an enhanced image, a linear raw preview, a gain table map, masked RGB tables and an
 * extra camera profile.
 */
public final class DngSpecSamples {
    /**
     * The names of the samples, which {@link #of(String)} builds.
     */
    public static final List<String> NAMES = List.of("wide-24bit", "planar", "sub-tiles-interleaved", "staggered",
            "enhanced", "linear-raw-preview", "gain-table-map", "rgb-tables", "extra-profile", "four-color");

    private DngSpecSamples() {
    }

    static long[] ramp(int width, int height, int samples, long maximum) {
        var values = new long[width * height * samples];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                for (var s = 0; s < samples; s++) {
                    values[(y * width + x) * samples + s] = (x * 997L + y * 613 + s * 4111 + (x * y) % 251 * 17) % maximum;
                }
            }
        }
        return values;
    }

    static DngBuilder base(int width, int height, int samples, int bits) {
        var builder = new DngBuilder();
        var depths = new int[samples];
        java.util.Arrays.fill(depths, bits);
        builder.first.longs(254, 0).longs(256, width).longs(257, height).shorts(258, depths).shorts(259, 1)
                .shorts(277, samples).shorts(262, samples == 1 ? 32803 : 34892)
                .bytes(50706, 1, 7, 1, 0).signedRationals(50721, 0.8, -0.1, -0.05, -0.3, 1.2, 0.1, 0, 0.1, 0.7)
                .rationals(50728, 0.6, 1, 0.8).shorts(50778, 21).ascii(271, "Smalt").ascii(272, "Spec");
        if (samples == 1) {
            builder.first.shorts(33421, 2, 2).bytes(33422, 0, 1, 1, 2);
        }
        return builder;
    }

    static byte[] words(long[] values) {
        return DngBuilder.words(values, 16, ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Builds a sample.
     *
     * @param name one of {@link #NAMES}
     * @return the DNG file
     */
    public static byte[] of(String name) {
        return switch (name) {
            case "wide-24bit" -> {
                var builder = base(32, 24, 1, 24);
                builder.first.longs(50717, (1L << 24) - 1).strips(12, DngBuilder.packMsb(
                        java.util.Arrays.copyOfRange(ramp(32, 24, 1, 1L << 24), 0, 32 * 12), 32, 24),
                        DngBuilder.packMsb(java.util.Arrays.copyOfRange(ramp(32, 24, 1, 1L << 24), 32 * 12, 32 * 24), 32, 24));
                yield builder.write();
            }
            case "planar" -> {
                var builder = base(24, 16, 3, 16);
                var values = ramp(24, 16, 3, 65536);
                var planes = new byte[3][];
                for (var plane = 0; plane < 3; plane++) {
                    var part = new long[24 * 16];
                    for (var index = 0; index < part.length; index++) {
                        part[index] = values[index * 3 + plane];
                    }
                    planes[plane] = DngBuilder.deflate(words(part));
                }
                builder.first.shorts(284, 2).shorts(259, 8).strips(16, planes);
                yield builder.write();
            }
            case "sub-tiles-interleaved" -> {
                // one 32x16 tile of 4x2 blocks, the image stored as four subimages
                var builder = base(32, 16, 1, 16);
                var values = ramp(32, 16, 1, 65536);
                var tile = new long[32 * 16];
                var at = 0;
                for (var by = 0; by < 8; by++) {
                    for (var bx = 0; bx < 8; bx++) {
                        for (var y = 0; y < 2; y++) {
                            for (var x = 0; x < 4; x++) {
                                tile[at++] = values[(by * 2 + y) * 32 + bx * 4 + x];
                            }
                        }
                    }
                }
                builder.first.shorts(50974, 2, 4).shorts(50975, 2).shorts(52547, 2).tiles(32, 16, words(tile));
                yield builder.write();
            }
            case "staggered" -> {
                var builder = base(32, 24, 1, 16);
                builder.first.shorts(33421, 2, 4).bytes(33422, 0, 1, 2, 1, 2, 1, 0, 1).shorts(50711, 2)
                        .rationals(50718, 0.5, 1).strips(24, words(ramp(32, 24, 1, 65536)));
                yield builder.write();
            }
            case "enhanced" -> {
                var builder = base(24, 16, 1, 16);
                builder.first.strips(16, words(ramp(24, 16, 1, 65536)));
                var enhanced = builder.child();
                enhanced.longs(254, 16).longs(256, 24).longs(257, 16).shorts(258, 16, 16, 16).shorts(259, 1)
                        .shorts(262, 34892).shorts(277, 3).shorts(50714, 512, 512, 512)
                        .strips(16, words(ramp(24, 16, 3, 65536)));
                yield builder.write();
            }
            case "linear-raw-preview" -> {
                var builder = base(64, 48, 3, 16);
                builder.first.strips(48, words(ramp(64, 48, 3, 65536)));
                var proxy = builder.child();
                proxy.longs(254, 1).longs(256, 40).longs(257, 30).shorts(258, 16, 16, 16).shorts(259, 1)
                        .shorts(262, 34892).shorts(277, 3).rationals(51112, 1.5)
                        .strips(30, words(ramp(40, 30, 3, 43000)));
                yield builder.write();
            }
            case "gain-table-map" -> {
                var builder = base(32, 24, 3, 16);
                var b = ByteBuffer.allocate(80 + 2 * 3 * 4).order(ByteOrder.BIG_ENDIAN);
                b.putInt(2).putInt(3).putDouble(1).putDouble(0.5).putDouble(0).putDouble(0).putInt(4);
                b.putFloat(0.25f).putFloat(0.5f).putFloat(0.25f).putFloat(0).putFloat(0);
                b.putInt(0).putFloat(1.5f).putFloat(0.5f).putFloat(3);
                for (var index = 0; index < 24; index++) {
                    b.put((byte) (index * 11));
                }
                builder.first.undefined(52544, b.array()).strips(24, words(ramp(32, 24, 3, 65536)));
                yield builder.write();
            }
            case "rgb-tables" -> {
                var builder = base(32, 16, 3, 16);
                var b = ByteBuffer.allocate(8 + 2 + 7 + 5 + 8 * 3 + 2 + 5 + 8 * 3 * 2).order(ByteOrder.BIG_ENDIAN);
                b.putInt(2).putInt(0);
                b.putShort((short) 7).put("subject".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                b.put((byte) 2).put((byte) 0).put((byte) 1).put((byte) 0).put((byte) 1);
                for (var index = 0; index < 24; index++) {
                    b.put((byte) (255 - index * 10));
                }
                b.putShort((short) 0);
                b.put((byte) 2).put((byte) 1).put((byte) 0).put((byte) 4).put((byte) 0);
                for (var index = 0; index < 24; index++) {
                    b.putShort((short) (index * 2500));
                }
                builder.first.undefined(52543, b.array()).strips(16, words(ramp(32, 16, 3, 65536)));
                var mask = builder.child();
                var data = new byte[16 * 8];
                for (var index = 0; index < data.length; index++) {
                    data[index] = (byte) ((index % 16) * 17);
                }
                mask.longs(254, 65540).longs(256, 16).longs(257, 8).shorts(258, 8).shorts(259, 1).shorts(262, 52527)
                        .shorts(277, 1).ascii(52526, "subject").strips(8, data);
                yield builder.write();
            }
            case "extra-profile" -> {
                var builder = base(24, 16, 3, 16);
                var profile = new DngBuilder(true, 0x4352);
                profile.first.ascii(50936, "Alternate").signedRationals(50721, 1.1, -0.2, 0, -0.1, 1, 0.1, 0.05, -0.1, 0.9)
                        .shorts(50778, 21).longs(51110, 1);
                builder.first.ascii(50936, "Primary").ascii(50934, "Alternate").blobs(50933, profile.write())
                        .strips(16, words(ramp(24, 16, 3, 65536)));
                yield builder.write();
            }
            case "four-color" -> {
                var builder = base(24, 16, 1, 16);
                builder.first.bytes(33422, 1, 4, 3, 5).bytes(50710, 1, 4, 3, 5).remove(50721)
                        .signedRationals(50721, 0.4, 0.6, -0.1, 0.1, 0.9, 0, 0.6, 0.2, 0.3, -0.1, 0.4, 0.7)
                        .rationals(50728, 0.7, 1, 0.8, 0.9).strips(16, words(ramp(24, 16, 1, 65536)));
                yield builder.write();
            }
            default -> throw new IllegalArgumentException(name);
        };
    }
}
