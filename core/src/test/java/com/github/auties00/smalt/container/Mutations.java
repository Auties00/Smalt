package com.github.auties00.smalt.container;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Deterministic input mutations for robustness tests: byte flips, truncations, zeroed ranges,
 * inserted bytes, and corruption of size fields at known positions.
 */
public final class Mutations {
    private Mutations() {
        throw new AssertionError();
    }

    /** A mutated input and a description of the mutation. */
    public record Variant(String description, byte[] data) {
    }

    /**
     * Creates {@code count} variants of an input.
     *
     * @param input         the valid input
     * @param sizeFields    offsets and lengths of size fields, as {offset, length} pairs
     * @param seed          the random seed
     * @param count         the number of variants
     * @param ebml          whether size fields are EBML variable-size integers rather than
     *                      big-endian integers
     */
    public static List<Variant> variants(byte[] input, List<int[]> sizeFields, long seed, int count, boolean ebml) {
        var random = new Random(seed);
        var result = new ArrayList<Variant>();
        for (var index = 0; index < count; index++) {
            var data = input.clone();
            var kind = index % 5;
            String description;
            switch (kind) {
                case 0 -> {
                    var flips = 1 + random.nextInt(8);
                    for (var flip = 0; flip < flips; flip++) {
                        data[random.nextInt(data.length)] ^= (byte) (1 + random.nextInt(255));
                    }
                    description = flips + " byte flips";
                }
                case 1 -> {
                    var cut = random.nextInt(data.length);
                    data = Arrays.copyOf(data, cut);
                    description = "truncated at " + cut;
                }
                case 2 -> {
                    var start = random.nextInt(data.length);
                    var end = Math.min(data.length, start + 1 + random.nextInt(64));
                    Arrays.fill(data, start, end, (byte) (random.nextBoolean() ? 0 : 0xFF));
                    description = "filled " + start + ".." + end;
                }
                case 3 -> {
                    var at = random.nextInt(data.length);
                    var inserted = new byte[1 + random.nextInt(16)];
                    random.nextBytes(inserted);
                    var grown = new byte[data.length + inserted.length];
                    System.arraycopy(data, 0, grown, 0, at);
                    System.arraycopy(inserted, 0, grown, at, inserted.length);
                    System.arraycopy(data, at, grown, at + inserted.length, data.length - at);
                    data = grown;
                    description = inserted.length + " bytes inserted at " + at;
                }
                default -> {
                    if (sizeFields.isEmpty()) {
                        data[random.nextInt(data.length)] = 0;
                        description = "zeroed byte";
                        break;
                    }
                    var field = sizeFields.get(random.nextInt(sizeFields.size()));
                    corruptSize(data, field[0], field[1], random, ebml);
                    description = "size field at " + field[0] + " corrupted";
                }
            }
            result.add(new Variant(description, data));
        }
        return result;
    }

    private static void corruptSize(byte[] data, int offset, int length, Random random, boolean ebml) {
        if (ebml) {
            switch (random.nextInt(5)) {
                case 0 -> data[offset] = 0;
                case 1 -> {
                    data[offset] = (byte) (0xFF >> (length - 1) | (0x80 >> (length - 1)));
                    for (var index = 1; index < length; index++) {
                        data[offset + index] = (byte) 0xFF;
                    }
                }
                case 2 -> {
                    for (var index = 1; index < length; index++) {
                        data[offset + index] = (byte) random.nextInt(256);
                    }
                    data[offset] = (byte) ((0x80 >> (length - 1)) | random.nextInt(0x80 >> (length - 1)));
                }
                case 3 -> data[offset] = (byte) (0x80 >> random.nextInt(8));
                default -> {
                    for (var index = 1; index < length; index++) {
                        data[offset + index] = 0;
                    }
                }
            }
            return;
        }
        long value = switch (random.nextInt(7)) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 7;
            case 3 -> 0xFFFFFFFFL;
            case 4 -> 0x7FFFFFFF;
            case 5 -> random.nextInt(data.length + 1);
            default -> random.nextInt(64);
        };
        for (var index = 0; index < length; index++) {
            data[offset + index] = (byte) (value >>> (8 * (length - 1 - index)));
        }
    }

    /**
     * Runs an operation on every variant, requiring each to succeed or fail with a
     * {@link MediaException} within a time bound, and returns the outcome counts.
     */
    public static TreeMap<String, Integer> run(List<Variant> variants, Duration timeout, Consumer<MediaSource> operation) {
        var outcomes = new TreeMap<String, Integer>();
        for (var variant : variants) {
            var outcome = assertTimeoutPreemptively(timeout, () -> {
                try (var source = MediaSource.of(variant.data())) {
                    operation.accept(source);
                    return "ok";
                } catch (MediaException exception) {
                    return exception.getClass().getSimpleName();
                } catch (RuntimeException | Error exception) {
                    throw new AssertionError("unexpected " + exception + " for " + variant.description(), exception);
                }
            }, () -> "timed out: " + variant.description());
            outcomes.merge(outcome, 1, Integer::sum);
        }
        return outcomes;
    }
}
