package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.internal.Cdf;
import com.github.auties00.smalt.video.av1.internal.Scans;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the invariants of the rate-distortion optimization of quantized levels on random blocks: it only
 * lowers magnitudes, keeps signs, and leaves a consistent end of block.
 */
class RdoqTest {
    @Test
    @DisplayName("levels only drop, keep their sign, and the end of block follows the last non-zero level")
    void invariants() {
        var random = new Random(2026);
        var cdf = new char[Cdf.SIZE];
        for (var qindex : new int[]{20, 80, 140, 220}) {
            Cdf.loadDefaults(cdf, Cdf.qcat(qindex));
            var rdoq = new Rdoq();
            rdoq.setup(cdf);
            var quantizer = new Quantizer();
            quantizer.set(qindex, 0, 0);
            for (var tx = 0; tx <= 3; tx++) {
                var scan = Scans.SCANS[tx];
                var n = scan.length;
                var logScale = Math.max(0, com.github.auties00.smalt.video.av1.internal.Tables.TX_CTX[tx] - 2);
                for (var round = 0; round < 200; round++) {
                    var coeff = new int[n];
                    var scale = 20 + random.nextInt(2000);
                    for (var i = 0; i < n; i++) {
                        var decay = 1.0 / (1 + (i >> 1));
                        coeff[i] = (int) Math.round(random.nextGaussian() * scale * decay);
                    }
                    var levels = new int[n];
                    var eob = quantizer.quantizeB(coeff, n, scan, CoefWriter.inverseScan(scan), logScale, levels,
                            quantizer.roundRd);
                    var original = levels.clone();
                    var plane = round & 1;
                    var optimized = rdoq.optimize(coeff, levels, eob, scan, tx, plane, plane == 0 ? 0 : 3, 0,
                            quantizer.dequant(false), quantizer.dequant(true), logScale, 50 + random.nextInt(5000));
                    assertTrue(optimized >= 0 && optimized <= eob, "end of block " + optimized + " of " + eob);
                    for (var i = 0; i < n; i++) {
                        var level = levels[scan[i]];
                        var before = original[scan[i]];
                        assertTrue(Math.abs(level) <= Math.abs(before), "level grew at " + i);
                        assertTrue(level == 0 || Integer.signum(level) == Integer.signum(before), "sign changed at " + i);
                        if (i >= optimized) {
                            assertEquals(0, level, "level beyond the end of block at " + i);
                        }
                    }
                    if (optimized > 0) {
                        assertTrue(levels[scan[optimized - 1]] != 0, "the last scan position of the block is zero");
                    }
                }
            }
        }
    }
}
