/* Ports libavif b1e4d339 (src/sampletransform.c, the sample transform operators). Copyright 2024 Google LLC;
   BSD 2-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.internal.io.FourCc;

import java.util.List;

/**
 * Describes the expression of a sample transform derived image item ({@code sato}, AVIF 1.2
 * clause 4.2.3) and applies it to the samples of its input images.
 *
 * <p>The expression is a list of tokens in postfix order: constants, samples of the input images
 * (the {@code dimg} references of the item, numbered from 1), and unary and binary operators. It
 * is evaluated once per sample position of every plane, luma, chroma and alpha alike, on signed
 * integers of {@link #intermediateBits()} bits: every operand and every result outside that range
 * is replaced by its nearest bound, and the final value is clamped to the sample range of the
 * output. The operators are:
 * <ul>
 *   <li>unary, of the top value {@code L}: 64 negation {@code -L}; 65 absolute value; 66 bitwise
 *       not {@code ~L}; 67 the index of the most significant set bit of {@code L}, or 0 when
 *       {@code L <= 0};</li>
 *   <li>binary, of the two top values {@code L} (pushed first) and {@code R}: 128 {@code L + R};
 *       129 {@code L - R}; 130 {@code L * R}; 131 {@code L / R} truncated toward zero, {@code L}
 *       when {@code R == 0}; 132 {@code L & R}; 133 {@code L | R}; 134 {@code L ^ R}; 135
 *       {@code L} to the power {@code R} truncated toward zero; 136 the minimum; 137 the
 *       maximum.</li>
 * </ul>
 * Instances are immutable and thread-safe.
 *
 * @param intermediateBits the width of the signed intermediate values: 8, 16, 32 or 64
 * @param tokens the token values in evaluation order
 * @param constants the constant of each token of value 0, and 0 for the other tokens
 * @param inputs the number of input images the item references
 * @implNote This implementation evaluates the power operator as libavif's
 * {@code avifSampleTransformOperation32bTwoOperands} does, by repeated multiplication that stops
 * at the first saturation, generalized to every intermediate width.
 */
public record HeifSampleTransform(int intermediateBits, int[] tokens, long[] constants, int inputs) {
    /**
     * The most input images a sample transform references.
     */
    public static final int MAX_INPUTS = 32;

    /**
     * The largest payload of a {@code sato} item: the header byte, the token count, and 255 tokens
     * that are each a constant of 64 bits.
     */
    public static final int MAX_PAYLOAD = 2 + 255 * 9;

    /**
     * The token of a constant operand.
     */
    private static final int CONSTANT = 0;

    /**
     * The first unary operator token.
     */
    private static final int FIRST_UNARY = 64;

    /**
     * The last unary operator token.
     */
    private static final int LAST_UNARY = 67;

    /**
     * The first binary operator token.
     */
    private static final int FIRST_BINARY = 128;

    /**
     * The last binary operator token.
     */
    private static final int LAST_BINARY = 137;

    /**
     * Parses the payload of a {@code sato} item ({@code SampleTransform}).
     *
     * <p>A reserved token ends the parse: its operands and payload are unknown, so neither the
     * tokens after it nor the stack are checked.
     *
     * @param payload the item data
     * @param inputs the number of {@code dimg} references of the item, from 1 to {@value #MAX_INPUTS}
     * @return the transform
     * @throws MediaException.Unsupported if the version is not 0 or a token is reserved, which
     * readers ignore the item for
     * @throws MediaException.Malformed if the item has no input or more than {@value #MAX_INPUTS},
     * the payload is truncated or has trailing bytes, the expression is empty, names an input the
     * item does not reference or does not leave exactly one value on the stack
     */
    public static HeifSampleTransform parse(byte[] payload, int inputs) {
        if (inputs < 1 || inputs > MAX_INPUTS) {
            throw new MediaException.Malformed("heif: a sample transform has " + inputs + " input images");
        }
        var box = BoxCursor.strict(payload, FourCc.of("sato"));
        var header = box.u8();
        var version = header >>> 6;
        if (version != 0) {
            throw new MediaException.Unsupported("heif", "sample transform version " + version);
        }
        var intermediateBits = 8 << (header & 3);
        var count = box.u8();
        if (count == 0) {
            throw new MediaException.Malformed("heif: a sample transform has no token");
        }
        var tokens = new int[count];
        var constants = new long[count];
        var depth = 0;
        for (var i = 0; i < count; i++) {
            var token = box.u8();
            tokens[i] = token;
            if (token == CONSTANT) {
                constants[i] = switch (intermediateBits) {
                    case 8 -> (byte) box.u8();
                    case 16 -> (short) box.u16();
                    case 32 -> (int) box.u32();
                    default -> box.u64();
                };
                depth++;
            } else if (token <= MAX_INPUTS) {
                if (token > inputs) {
                    throw new MediaException.Malformed("heif: a sample transform reads input " + token + " of "
                            + inputs);
                }
                depth++;
            } else if (token >= FIRST_UNARY && token <= LAST_UNARY) {
                if (depth < 1) {
                    throw new MediaException.Malformed("heif: a sample transform operator lacks its operand");
                }
            } else if (token >= FIRST_BINARY && token <= LAST_BINARY) {
                if (depth < 2) {
                    throw new MediaException.Malformed("heif: a sample transform operator lacks its operands");
                }
                depth--;
            } else {
                throw new MediaException.Unsupported("heif", "sample transform token " + token);
            }
        }
        if (box.remaining() != 0) {
            throw new MediaException.Malformed("heif: a sample transform has " + box.remaining() + " trailing bytes");
        }
        if (depth != 1) {
            throw new MediaException.Malformed("heif: a sample transform leaves " + depth + " values on its stack");
        }
        return new HeifSampleTransform(intermediateBits, tokens, constants, inputs);
    }

    /**
     * Returns the stack depth the expression needs.
     *
     * @return the largest number of values on the stack during an evaluation
     */
    int stackDepth() {
        var depth = 0;
        var deepest = 0;
        for (var token : tokens) {
            if (token <= MAX_INPUTS) {
                deepest = Math.max(deepest, ++depth);
            } else if (token >= FIRST_BINARY) {
                depth--;
            }
        }
        return deepest;
    }

    /**
     * Evaluates the expression at one sample position.
     *
     * @param samples the sample of each input image at the position, indexed from 0
     * @param stack a scratch array of at least {@link #stackDepth()} values
     * @return the value of the expression, within the intermediate range
     */
    long evaluate(long[] samples, long[] stack) {
        var min = intermediateBits == 64 ? Long.MIN_VALUE : -(1L << (intermediateBits - 1));
        var max = intermediateBits == 64 ? Long.MAX_VALUE : (1L << (intermediateBits - 1)) - 1;
        var size = 0;
        for (var i = 0; i < tokens.length; i++) {
            var token = tokens[i];
            if (token == CONSTANT) {
                stack[size++] = constants[i];
            } else if (token <= MAX_INPUTS) {
                stack[size++] = Math.clamp(samples[token - 1], min, max);
            } else if (token <= LAST_UNARY) {
                stack[size - 1] = unary(token, stack[size - 1], min, max);
            } else {
                stack[size - 2] = binary(token, stack[size - 2], stack[size - 1], min, max);
                size--;
            }
        }
        return stack[0];
    }

    /**
     * Applies a unary operator.
     *
     * @param token the operator token
     * @param value the operand, within the intermediate range
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     * @return the result, within the intermediate range
     */
    private static long unary(int token, long value, long min, long max) {
        return switch (token) {
            case 64 -> value == min ? max : -value;
            case 65 -> value >= 0 ? value : value == min ? max : -value;
            case 66 -> ~value;
            default -> value <= 0 ? 0 : 63 - Long.numberOfLeadingZeros(value);
        };
    }

    /**
     * Applies a binary operator.
     *
     * @param token the operator token
     * @param left the left operand, within the intermediate range
     * @param right the right operand, within the intermediate range
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     * @return the result, within the intermediate range
     */
    private static long binary(int token, long left, long right, long min, long max) {
        return switch (token) {
            case 128 -> saturate(left, left + right, ((left ^ (left + right)) & (right ^ (left + right))) < 0, min,
                    max);
            case 129 -> saturate(left, left - right, ((left ^ right) & (left ^ (left - right))) < 0, min, max);
            case 130 -> multiply(left, right, min, max);
            case 131 -> right == 0 ? left : left == min && right == -1 ? max : left / right;
            case 132 -> left & right;
            case 133 -> left | right;
            case 134 -> left ^ right;
            case 135 -> power(left, right, min, max);
            case 136 -> Math.min(left, right);
            default -> Math.max(left, right);
        };
    }

    /**
     * Clamps a sum or difference to the intermediate range.
     *
     * @param left the left operand, whose sign is the sign of an overflowed result
     * @param result the 64-bit result
     * @param overflowed whether the 64-bit result overflowed
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     * @return the clamped result
     */
    private static long saturate(long left, long result, boolean overflowed, long min, long max) {
        if (overflowed) {
            return left < 0 ? min : max;
        }
        return Math.clamp(result, min, max);
    }

    /**
     * Multiplies two values, clamping the product to the intermediate range.
     *
     * @param left the left operand
     * @param right the right operand
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     * @return the clamped product
     */
    private static long multiply(long left, long right, long min, long max) {
        var high = Math.multiplyHigh(left, right);
        var low = left * right;
        if (high != (low >> 63)) {
            return (left < 0) == (right < 0) ? max : min;
        }
        return Math.clamp(low, min, max);
    }

    /**
     * Raises a value to a power, truncating toward zero and clamping to the intermediate range.
     *
     * @param base the value raised, within the intermediate range
     * @param exponent the power, within the intermediate range
     * @param min the lowest intermediate value
     * @param max the highest intermediate value
     * @return the clamped power
     */
    private static long power(long base, long exponent, long min, long max) {
        if (base == 0 || base == 1) {
            return base;
        }
        if (base == -1) {
            return (exponent & 1) == 0 ? 1 : -1;
        }
        if (exponent == 0) {
            return 1;
        }
        if (exponent == 1) {
            return base;
        }
        if (exponent < 0) {
            return 0;
        }
        var saturated = base > 0 || (exponent & 1) == 0 ? max : min;
        var result = base;
        for (var i = 1L; i < exponent; i++) {
            var next = multiply(result, base, min, max);
            if (next == min || next == max) {
                return saturated;
            }
            result = next;
        }
        return result;
    }

    /**
     * Applies the transform to its input images.
     *
     * <p>The inputs must be planar pictures of one size and one chroma layout, all with an alpha
     * plane or all without one. Each output sample is the expression evaluated over the input
     * samples at its plane and position, clamped to the {@code depth}-bit range: the full range,
     * or for the luma and chroma planes the limited range of ITU-T H.273 when {@code limited} is
     * set. The output depth may differ from the inputs'.
     *
     * @param inputs the input images, in {@code dimg} reference order
     * @param depth the bit depth of the output, from 8 to 16
     * @param color the color description of the output
     * @param limited whether the luma and chroma samples are clamped to the limited range, as an
     * {@code nclx} color property of the item without its full range flag requires
     * @return a {@link YuvPicture} of 8 bits or a {@link Yuv16Picture} of {@code depth} bits, with
     * an alpha plane when the inputs have one
     * @throws MediaException.Unsupported if an input is not planar or the inputs differ in alpha
     * @throws MediaException.Malformed if the number of inputs is not {@link #inputs()}, or the
     * inputs differ in size or chroma layout
     */
    public Picture apply(List<Picture> inputs, int depth, ColorInfo color, boolean limited) {
        if (inputs.size() != this.inputs) {
            throw new MediaException.Malformed("heif: a sample transform of " + this.inputs + " inputs got "
                    + inputs.size());
        }
        var first = inputs.getFirst();
        var format = formatOf(first);
        for (var input : inputs) {
            if (input.width() != first.width() || input.height() != first.height() || formatOf(input) != format) {
                throw new MediaException.Malformed("heif: the inputs of a sample transform differ in size or chroma"
                        + " layout");
            }
            if (input.hasAlpha() != first.hasAlpha()) {
                throw new MediaException.Unsupported("heif", "a sample transform over inputs with and without alpha");
            }
        }
        var width = first.width();
        var height = first.height();
        var planes = new Plane[inputs.size()];
        Picture output;
        if (depth > 8) {
            output = Yuv16Picture.allocate(width, height, format, depth, first.hasAlpha(), color);
        } else {
            output = YuvPicture.allocate(width, height, format, first.hasAlpha(), color);
        }
        var scale = 1 << (depth - 8);
        var full = (1 << depth) - 1;
        for (var plane = 0; plane < 4; plane++) {
            if (plane == 3 ? !first.hasAlpha() : plane > 0 && !format.hasChroma()) {
                continue;
            }
            for (var i = 0; i < planes.length; i++) {
                planes[i] = Plane.of(inputs.get(i), plane);
            }
            var target = Plane.of(output, plane);
            var low = limited && plane < 3 ? 16 * scale : 0;
            var high = limited && plane < 3 ? (plane == 0 ? 235 : 240) * scale : full;
            apply(planes, target, low, high);
        }
        return output;
    }

    /**
     * Evaluates the expression over one plane, in parallel bands of rows.
     *
     * @param inputs the plane of every input
     * @param target the plane of the output
     * @param low the lowest output sample
     * @param high the highest output sample
     */
    private void apply(Plane[] inputs, Plane target, int low, int high) {
        var rows = target.height();
        var bands = ParallelBands.bands(rows, (long) target.width() * rows * Math.max(1, tokens.length / 2));
        ParallelBands.run(bands, band -> {
            var samples = new long[inputs.length];
            var stack = new long[Math.max(1, stackDepth())];
            var end = ParallelBands.start(band + 1, bands, rows);
            for (var row = ParallelBands.start(band, bands, rows); row < end; row++) {
                for (var col = 0; col < target.width(); col++) {
                    for (var i = 0; i < inputs.length; i++) {
                        samples[i] = inputs[i].get(row, col);
                    }
                    target.set(row, col, (int) Math.clamp(evaluate(samples, stack), low, high));
                }
            }
        });
    }

    /**
     * {@return the chroma layout of a planar picture}
     *
     * @param picture the input
     * @throws MediaException.Unsupported if the picture is not planar
     */
    private static ChromaFormat formatOf(Picture picture) {
        return switch (picture) {
            case YuvPicture yuv -> yuv.chromaFormat();
            case Yuv16Picture yuv -> yuv.chromaFormat();
            default -> throw new MediaException.Unsupported("heif", "a sample transform over a packed "
                    + picture.getClass().getSimpleName());
        };
    }

    /**
     * Reads and writes the samples of one plane of a planar picture.
     *
     * @param bytes the 8-bit samples, or {@code null}
     * @param shorts the 16-bit samples, or {@code null}
     * @param stride the row stride in samples
     * @param width the plane width
     * @param height the plane height
     */
    private record Plane(byte[] bytes, short[] shorts, int stride, int width, int height) {
        /**
         * {@return one plane of a planar picture}
         *
         * @param picture the planar picture holding the plane
         * @param plane 0 for luma, 1 and 2 for chroma, 3 for alpha
         */
        static Plane of(Picture picture, int plane) {
            return switch (picture) {
                case YuvPicture yuv -> switch (plane) {
                    case 0 -> new Plane(yuv.y(), null, yuv.yStride(), yuv.width(), yuv.height());
                    case 1 -> new Plane(yuv.u(), null, yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                    case 2 -> new Plane(yuv.v(), null, yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                    default -> new Plane(yuv.alpha(), null, yuv.alphaStride(), yuv.width(), yuv.height());
                };
                case Yuv16Picture yuv -> switch (plane) {
                    case 0 -> new Plane(null, yuv.y(), yuv.yStride(), yuv.width(), yuv.height());
                    case 1 -> new Plane(null, yuv.u(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                    case 2 -> new Plane(null, yuv.v(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                    default -> new Plane(null, yuv.alpha(), yuv.alphaStride(), yuv.width(), yuv.height());
                };
                default -> throw new IllegalStateException("not planar");
            };
        }

        /**
         * {@return the sample at a position, unsigned}
         *
         * @param row the row within the plane
         * @param col the column
         */
        int get(int row, int col) {
            return bytes != null ? bytes[row * stride + col] & 0xFF : shorts[row * stride + col] & 0xFFFF;
        }

        /**
         * Writes the sample at a position.
         *
         * @param row the row within the plane
         * @param col the column
         * @param value the sample, within the plane's range
         */
        void set(int row, int col, int value) {
            if (bytes != null) {
                bytes[row * stride + col] = (byte) value;
            } else {
                shorts[row * stride + col] = (short) value;
            }
        }
    }
}
