package com.github.auties00.smalt.testkit.audio;

import com.github.auties00.smalt.MediaException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Builds deterministic mutations of valid media files and checks that a parser either accepts
 * each one or rejects it with a {@link MediaException}, within a time bound.
 *
 * <p>Mutations mix random byte flips, truncation at a random offset, overwriting a random
 * 1..4-byte window (typically a length or size field) with an extreme value, duplicating a random
 * slice, and inserting garbage bytes. The random generator is seeded, so failures reproduce.
 */
public final class AudioMutations {
    private AudioMutations() {
        throw new AssertionError();
    }

    /**
     * Returns {@code count} mutated copies of {@code input}.
     */
    public static List<byte[]> variants(byte[] input, long seed, int count) {
        var random = new SplittableRandom(seed);
        var result = new ArrayList<byte[]>(count);
        for (var i = 0; i < count; i++) {
            result.add(mutate(input, random, i % 6));
        }
        return result;
    }

    private static byte[] mutate(byte[] input, SplittableRandom random, int kind) {
        if (input.length == 0) {
            return new byte[]{(byte) random.nextInt(256)};
        }
        return switch (kind) {
            case 0 -> {
                var copy = input.clone();
                var flips = 1 + random.nextInt(8);
                for (var i = 0; i < flips; i++) {
                    copy[random.nextInt(copy.length)] ^= (byte) (1 << random.nextInt(8));
                }
                yield copy;
            }
            case 1 -> java.util.Arrays.copyOf(input, random.nextInt(input.length));
            case 2 -> {
                var copy = input.clone();
                // bias towards headers, where the length fields live
                var limit = random.nextBoolean() ? Math.min(copy.length, 512) : copy.length;
                var position = random.nextInt(limit);
                var width = 1 + random.nextInt(4);
                var value = switch (random.nextInt(4)) {
                    case 0 -> 0;
                    case 1 -> 0xFF;
                    case 2 -> 0x7F;
                    default -> random.nextInt(256);
                };
                for (var i = 0; i < width && position + i < copy.length; i++) {
                    copy[position + i] = (byte) value;
                }
                yield copy;
            }
            case 3 -> {
                var from = random.nextInt(input.length);
                var length = 1 + random.nextInt(Math.min(4096, input.length - from));
                var at = random.nextInt(input.length);
                var result = new byte[input.length + length];
                System.arraycopy(input, 0, result, 0, at);
                System.arraycopy(input, from, result, at, length);
                System.arraycopy(input, at, result, at + length, input.length - at);
                yield result;
            }
            case 4 -> {
                var at = random.nextInt(input.length);
                var length = 1 + random.nextInt(256);
                var result = new byte[input.length + length];
                System.arraycopy(input, 0, result, 0, at);
                for (var i = 0; i < length; i++) {
                    result[at + i] = (byte) random.nextInt(256);
                }
                System.arraycopy(input, at, result, at + length, input.length - at);
                yield result;
            }
            default -> {
                var copy = input.clone();
                var start = random.nextInt(copy.length);
                var length = Math.min(copy.length - start, 1 + random.nextInt(64));
                for (var i = 0; i < length; i++) {
                    copy[start + i] = (byte) random.nextInt(256);
                }
                yield copy;
            }
        };
    }

    /**
     * Runs {@code action} on {@code variantsPerInput} mutations of every input and fails if any
     * run throws something other than a {@link MediaException} or exceeds {@code perVariant}.
     *
     * @return the outcome counts, keyed by {@code "ok"} or the exception class simple name
     */
    public static Map<String, Integer> assertRobust(List<byte[]> inputs, int variantsPerInput, long seed,
                                                    Duration perVariant, Consumer<byte[]> action) {
        var outcomes = new TreeMap<String, Integer>();
        var index = 0;
        for (var input : inputs) {
            var variants = variants(input, seed + index++ * 7919L, variantsPerInput);
            for (var v = 0; v < variants.size(); v++) {
                var variant = variants.get(v);
                var label = "input " + (index - 1) + " variant " + v;
                String outcome = assertTimeoutPreemptively(perVariant, () -> {
                    try {
                        action.accept(variant);
                        return "ok";
                    } catch (MediaException exception) {
                        return exception.getClass().getSimpleName();
                    } catch (Throwable throwable) {
                        fail(label + " leaked " + throwable, throwable);
                        return "unreachable";
                    }
                }, label + " timed out");
                outcomes.merge(outcome, 1, Integer::sum);
            }
        }
        return outcomes;
    }
}
