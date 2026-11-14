package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.UnciWriter;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Random;

/**
 * The reference conversions of uncompressed HEIF values to samples, computed with exact arithmetic independently of
 * the decoder, and the generators of raw values for every component format.
 */
final class UnciReference {
    private UnciReference() {
        throw new AssertionError();
    }

    /** Decodes the primary unci item of a file. */
    static HeifComponents decode(byte[] file, MediaLimits limits) {
        var source = MediaSource.of(file);
        var heif = HeifFile.parse(source, limits);
        var item = heif.primaryItem();
        var extent = item.property(HeifProperty.SpatialExtent.class);
        return HeifUncompressed.decode("heic", heif.readItem(source, item, limits), item, (int) extent.width(),
                (int) extent.height(), limits);
    }

    /** {@return the depth of the plane a component of a format and depth makes} */
    static int outputDepth(int format, int depth) {
        return (format == UnciWriter.UNSIGNED || format == UnciWriter.SIGNED) && depth <= 16 ? depth : 16;
    }

    /** {@return the sample a raw value of a format and depth becomes} */
    static int sample(int format, int depth, BigInteger raw) {
        return switch (format) {
            case UnciWriter.UNSIGNED -> depth <= 16 ? raw.intValueExact() : rescale(raw, depth);
            case UnciWriter.SIGNED -> {
                var offset = raw.flipBit(depth - 1);
                yield depth <= 16 ? offset.intValueExact() : rescale(offset, depth);
            }
            case UnciWriter.FLOAT -> quantize(toDouble(raw, depth));
            case UnciWriter.COMPLEX -> {
                var half = depth / 2;
                var real = toDouble(raw.shiftRight(half), half);
                var imaginary = toDouble(raw.and(BigInteger.ONE.shiftLeft(half).subtract(BigInteger.ONE)), half);
                yield quantize(StrictMath.hypot(real, imaginary));
            }
            default -> throw new IllegalArgumentException("format " + format);
        };
    }

    /** {@return {@code round(v * 65535 / (2^n - 1))}, computed exactly} */
    static int rescale(BigInteger value, int depth) {
        var divisor = BigInteger.ONE.shiftLeft(depth).subtract(BigInteger.ONE);
        return value.multiply(BigInteger.valueOf(2 * 65535L)).add(divisor).divide(divisor.shiftLeft(1)).intValueExact();
    }

    /** {@return {@code round(v * 65535)} clamped, rounding halves up, NaN becoming 0} */
    static int quantize(double value) {
        if (Double.isNaN(value) || value <= 0) {
            return 0;
        }
        if (Double.isInfinite(value)) {
            return 65535;
        }
        var scaled = new BigDecimal(value).multiply(BigDecimal.valueOf(65535));
        if (scaled.compareTo(BigDecimal.valueOf(65535)) >= 0) {
            return 65535;
        }
        return scaled.add(new BigDecimal("0.5")).toBigInteger().intValueExact();
    }

    /** {@return an IEEE 754 binary interchange value, correctly rounded to a double} */
    static double toDouble(BigInteger raw, int width) {
        return switch (width) {
            case 16 -> Float.float16ToFloat((short) raw.intValue());
            case 32 -> Float.intBitsToFloat(raw.intValue());
            case 64 -> Double.longBitsToDouble(raw.longValue());
            default -> {
                var exponentBits = width == 128 ? 15 : 19;
                var mantissaBits = width - 1 - exponentBits;
                var negative = raw.testBit(width - 1);
                var exponent = raw.shiftRight(mantissaBits).intValue() & ((1 << exponentBits) - 1);
                var mantissa = raw.and(BigInteger.ONE.shiftLeft(mantissaBits).subtract(BigInteger.ONE));
                var bias = (1 << (exponentBits - 1)) - 1;
                double magnitude;
                if (exponent == (1 << exponentBits) - 1) {
                    magnitude = mantissa.signum() == 0 ? Double.POSITIVE_INFINITY : Double.NaN;
                } else {
                    var significand = exponent == 0 ? mantissa : mantissa.setBit(mantissaBits);
                    var power = (exponent == 0 ? 1 : exponent) - bias - mantissaBits;
                    if (significand.signum() == 0 || power + mantissaBits < -1200) {
                        magnitude = 0;
                    } else if (power + mantissaBits > 1100) {
                        magnitude = Double.POSITIVE_INFINITY;
                    } else if (power >= 0) {
                        magnitude = new BigDecimal(significand.shiftLeft(power)).doubleValue();
                    } else {
                        magnitude = new BigDecimal(significand).divide(new BigDecimal(BigInteger.ONE.shiftLeft(-power)))
                                .doubleValue();
                    }
                }
                yield negative ? -magnitude : magnitude;
            }
        };
    }

    /** {@return the raw bits of a double as a binary interchange value of a width, with random extra low bits} */
    static BigInteger fromDouble(double value, int width, Random random) {
        return switch (width) {
            case 16 -> BigInteger.valueOf(Float.floatToFloat16((float) value) & 0xFFFF);
            case 32 -> BigInteger.valueOf(Float.floatToRawIntBits((float) value) & 0xFFFFFFFFL);
            case 64 -> new BigInteger(1, longBytes(Double.doubleToRawLongBits(value)));
            default -> {
                var exponentBits = width == 128 ? 15 : 19;
                var mantissaBits = width - 1 - exponentBits;
                var bits = Double.doubleToRawLongBits(value);
                var sign = bits < 0;
                var exponent = (int) ((bits >>> 52) & 0x7FF);
                var mantissa = BigInteger.valueOf(bits & ((1L << 52) - 1));
                var bias = (1 << (exponentBits - 1)) - 1;
                int stored;
                if (exponent == 0x7FF) {
                    stored = (1 << exponentBits) - 1;
                } else if (exponent == 0) {
                    stored = 0;
                    mantissa = BigInteger.ZERO;
                } else {
                    stored = exponent - 1023 + bias;
                }
                var result = mantissa.shiftLeft(mantissaBits - 52);
                if (exponent != 0x7FF && exponent != 0 && random.nextBoolean()) {
                    result = result.or(new BigInteger(mantissaBits - 52, random));
                }
                result = result.or(BigInteger.valueOf(stored).shiftLeft(mantissaBits));
                if (sign) {
                    result = result.setBit(width - 1);
                }
                yield result;
            }
        };
    }

    private static byte[] longBytes(long value) {
        var out = new byte[8];
        for (var i = 0; i < 8; i++) {
            out[i] = (byte) (value >>> (56 - 8 * i));
        }
        return out;
    }

    /** {@return a raw value of a format and depth: random, an edge value, or a value in the nominal range} */
    static BigInteger random(Random random, int format, int depth) {
        return switch (format) {
            case UnciWriter.UNSIGNED, UnciWriter.SIGNED -> switch (random.nextInt(8)) {
                case 0 -> BigInteger.ZERO;
                case 1 -> BigInteger.ONE.shiftLeft(depth).subtract(BigInteger.ONE);
                case 2 -> BigInteger.ONE.shiftLeft(depth - 1);
                case 3 -> BigInteger.ONE.shiftLeft(depth - 1).subtract(BigInteger.ONE);
                default -> new BigInteger(depth, random);
            };
            case UnciWriter.FLOAT -> randomFloat(random, depth);
            case UnciWriter.COMPLEX -> randomFloat(random, depth / 2).shiftLeft(depth / 2)
                    .or(randomFloat(random, depth / 2));
            default -> throw new IllegalArgumentException("format " + format);
        };
    }

    private static BigInteger randomFloat(Random random, int width) {
        return switch (random.nextInt(12)) {
            case 0 -> fromDouble(Double.NaN, width, random);
            case 1 -> fromDouble(Double.POSITIVE_INFINITY, width, random);
            case 2 -> fromDouble(-0.0, width, random);
            case 3 -> new BigInteger(width, random);
            case 4 -> fromDouble(1.0, width, random);
            case 5 -> fromDouble((random.nextInt(65536) + 0.5) / 65535.0, width, random);
            default -> fromDouble(random.nextDouble() * 1.4 - 0.2, width, random);
        };
    }
}
