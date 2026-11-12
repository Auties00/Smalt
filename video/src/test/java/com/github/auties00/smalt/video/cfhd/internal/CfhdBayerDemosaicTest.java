package com.github.auties00.smalt.video.cfhd.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.task.ParallelBands;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demosaics frames large enough to be split into row bands (the SDK vectors are all below the size at which
 * {@link ParallelBands} splits work) and holds every split to the single-threaded result.
 */
@DisplayName("CineForm RAW demosaic in row bands")
class CfhdBayerDemosaicTest {
    @ParameterizedTest(name = "phase {0}")
    @ValueSource(ints = {0, 1, 2, 3})
    @DisplayName("demosaics a large frame identically on one thread and in parallel bands")
    void bandsMatchOneThread(int phase) {
        var quadsWide = 300;
        var quadsHigh = 150;
        var samples = 4 * quadsWide * quadsHigh;
        if (ParallelBands.enabled() && Runtime.getRuntime().availableProcessors() > 1) {
            assertTrue(ParallelBands.bands(quadsHigh, 12L * samples) > 1, "the frame is split into bands");
        }
        var random = new Random(31 + phase);
        var planes = new int[4][quadsWide * quadsHigh];
        for (var c = 0; c < 4; c++) {
            for (var i = 0; i < planes[c].length; i++) {
                // smooth content with noise and a few saturated photosites
                var smooth = 20000 + (i % quadsWide) * 60 + (i / quadsWide) * 40;
                planes[c][i] = random.nextInt(64) == 0 ? (c == 0 ? 65535 : random.nextInt(65536))
                        : Math.clamp(smooth + random.nextInt(4096) - 2048 - (c == 0 ? 0 : smooth - 32768), 0, 65535);
            }
        }
        var single = new short[samples * 4];
        new CfhdBayer().demosaic(planes, quadsWide, quadsWide, quadsHigh, phase, MediaLimits.DEFAULT, 1, single,
                8 * quadsWide);
        var parallel = new short[samples * 4];
        var bayer = new CfhdBayer();
        bayer.demosaic(planes, quadsWide, quadsWide, quadsHigh, phase, MediaLimits.DEFAULT, 0, parallel,
                8 * quadsWide);
        assertArrayEquals(single, parallel);
        var two = new short[samples * 4];
        bayer.demosaic(planes, quadsWide, quadsWide, quadsHigh, phase, MediaLimits.DEFAULT, 2, two, 8 * quadsWide);
        assertArrayEquals(single, two);
    }
}
