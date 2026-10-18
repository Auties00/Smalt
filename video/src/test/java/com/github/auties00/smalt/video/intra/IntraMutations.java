package com.github.auties00.smalt.video.intra;

import com.github.auties00.smalt.MediaException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Derives corrupt variants of valid frames and checks that a decoder either decodes them or
 * raises a {@link MediaException}, in bounded time.
 */
public final class IntraMutations {
    private IntraMutations() {
    }

    /** Returns deterministic mutations of a frame: byte flips, truncations and header damage. */
    public static List<byte[]> mutations(byte[] frame, int headerBytes, int count, long seed) {
        var random = new Random(seed);
        var variants = new ArrayList<byte[]>();
        variants.add(new byte[0]);
        variants.add(Arrays.copyOf(frame, Math.min(frame.length, 8)));
        for (var i = 0; i < count; i++) {
            var copy = frame.clone();
            switch (i % 4) {
                case 0 -> {
                    for (var flips = 1 + random.nextInt(20); flips > 0; flips--) {
                        copy[random.nextInt(copy.length)] ^= (byte) (1 << random.nextInt(8));
                    }
                }
                case 1 -> copy = Arrays.copyOf(copy, random.nextInt(copy.length));
                case 2 -> {
                    for (var hits = 1 + random.nextInt(4); hits > 0; hits--) {
                        copy[random.nextInt(Math.min(headerBytes, copy.length))] = (byte) random.nextInt(256);
                    }
                }
                default -> {
                    var start = random.nextInt(copy.length);
                    var length = Math.min(copy.length - start, 1 + random.nextInt(64));
                    for (var k = 0; k < length; k++) {
                        copy[start + k] = (byte) random.nextInt(256);
                    }
                }
            }
            variants.add(copy);
        }
        return variants;
    }

    /** Decodes every variant, failing on any exception that is not a {@link MediaException}. */
    public static int check(List<byte[]> variants, Consumer<byte[]> decoder) {
        var rejected = new int[1];
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            for (var variant : variants) {
                try {
                    decoder.accept(variant);
                } catch (MediaException expected) {
                    rejected[0]++;
                } catch (RuntimeException | Error unexpected) {
                    fail("unexpected " + unexpected + " on a variant of " + variant.length + " bytes", unexpected);
                }
            }
        });
        return rejected[0];
    }
}
