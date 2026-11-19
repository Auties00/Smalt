package com.github.auties00.smalt.image.raw;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Feeds damaged raw files to every entry point: random byte changes, truncations and overwritten
 * structure fields must produce a result or a {@link MediaException}, never another exception, and
 * finish in bounded time.
 */
class RawRobustnessTest {
    private static final MediaLimits SMALL = MediaLimits.builder().maxPixels(40_000_000).maxBytes(512L << 20).build();

    static void exercise(byte[] bytes, boolean render) {
        var options = RawDecoderOptions.fast().toBuilder().limits(SMALL).build();
        attempt(() -> RawDecoder.info(MediaSource.of(bytes), SMALL));
        attempt(() -> Images.detect(MediaSource.of(bytes)));
        attempt(() -> RawDecoder.extractPreview(MediaSource.of(bytes)));
        if (render) {
            attempt(() -> RawDecoder.decode(MediaSource.of(bytes), options));
            attempt(() -> RawDecoder.decodeRaw(MediaSource.of(bytes), options));
            attempt(() -> RawDecoder.decode(MediaSource.of(bytes), options.toBuilder().minSize(100, 60).build()));
        }
    }

    static void attempt(Runnable operation) {
        try {
            operation.run();
        } catch (MediaException expected) {
            // a damaged file may be rejected
        }
    }

    static byte[] mutate(byte[] original, SplittableRandom random, int round) {
        var bytes = original.clone();
        switch (round % 4) {
            case 0 -> {
                for (var k = 0; k < 1 + random.nextInt(16); k++) {
                    bytes[random.nextInt(Math.min(bytes.length, 4096))] = (byte) random.nextInt(256);
                }
            }
            case 1 -> {
                return java.util.Arrays.copyOf(bytes, random.nextInt(bytes.length));
            }
            case 2 -> {
                var at = 8 + random.nextInt(Math.min(bytes.length - 12, 2048));
                bytes[at] = (byte) 0xFF;
                bytes[at + 1] = (byte) 0xFF;
                bytes[at + 2] = (byte) random.nextInt(256);
                bytes[at + 3] = (byte) 0x7F;
            }
            default -> {
                for (var k = 0; k < 64; k++) {
                    bytes[random.nextInt(bytes.length)] ^= (byte) (1 << random.nextInt(8));
                }
            }
        }
        return bytes;
    }

    @Test
    @DisplayName("synthetic DNG files of every compression survive 400 mutations")
    void synthetic() {
        var random = new SplittableRandom(42);
        var originals = new byte[4][];
        for (var c = 0; c < 4; c++) {
            var dng = TinyDng.ramp(48, 32);
            dng.compression = new int[]{1, 7, 8, 8}[c];
            dng.predictor = c == 3 ? 2 : 1;
            var gain = TinyDng.Opcodes.area(0, 0, 32, 48, 0, 1, 1, 1, 64);
            gain.putInt(2).putInt(2).putDouble(1).putDouble(1).putDouble(0).putDouble(0).putInt(1)
                    .putFloat(1).putFloat(2).putFloat(1).putFloat(2);
            dng.opcodes2 = new TinyDng.Opcodes().add(9, 0, gain).build();
            dng.linearization = new int[]{0, 100, 1000, 65535};
            originals[c] = dng.write();
        }
        assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            for (var round = 0; round < 400; round++) {
                exercise(mutate(originals[round % 4], random, round), true);
            }
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RawSamples.class, names = {"DNG_5D3_14BIT_SMALL", "DNG_5D3_16BIT_SMALL", "CR2_5D3",
            "NEF_D70S_LOSSY_12", "ARW_NEX6_CRAW_12", "PEF_PENTAX_KX", "RAF_XA1", "ORF_XZ2", "RW2_GF6", "CR3_250D", "CR3_EOS_R5",
            "MRW_DYNAX_7D", "MRW_DIMAGE_5", "CRW_D30", "CRW_G1", "X3F_DP1", "X3F_SD14"})
    @DisplayName("damaged sample files only raise MediaException")
    void samples(RawSamples sample) throws IOException {
        var original = Files.readAllBytes(sample.fetch());
        var small = original.length < 4 << 20;
        var random = new SplittableRandom(sample.ordinal());
        assertTimeoutPreemptively(Duration.ofSeconds(240), () -> {
            for (var round = 0; round < (small ? 60 : 24); round++) {
                exercise(mutate(original, random, round), small || round % 6 == 1);
            }
        });
    }

    @Test
    @DisplayName("an image over the limits raises LimitExceeded before allocating")
    void limits() {
        var bytes = TinyDng.ramp(64, 48).write();
        var tight = RawDecoderOptions.builder().limits(MediaLimits.builder().maxPixels(1000).build()).build();
        assertThrows(MediaException.LimitExceeded.class, () -> RawDecoder.decode(MediaSource.of(bytes), tight));
    }

    @Test
    @DisplayName("an empty or foreign input raises Unsupported")
    void foreign() {
        assertThrows(MediaException.Unsupported.class, () -> RawDecoder.info(MediaSource.of(new byte[0])));
        assertThrows(MediaException.Unsupported.class, () -> RawDecoder.info(MediaSource.of(new byte[]{1, 2, 3, 4,
                5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16})));
    }
}
