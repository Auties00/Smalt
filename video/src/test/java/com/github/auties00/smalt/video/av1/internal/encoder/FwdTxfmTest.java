package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.internal.ItxDsp;
import com.github.auties00.smalt.video.av1.internal.Tables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that the forward transforms invert the decoder's inverse transforms: a residual
 * transformed forward and back through the decoder's kernels comes back within rounding.
 */
class FwdTxfmTest {
    static List<Arguments> cases() {
        var out = new ArrayList<Arguments>();
        for (var tx = 0; tx < 19; tx++) {
            var w = Tables.TX_W[tx] * 4;
            var h = Tables.TX_H[tx] * 4;
            if (w > 32 || h > 32) {
                continue;
            }
            out.add(Arguments.of(tx, 0));
            if (w <= 16 && h <= 16) {
                for (var type = 1; type <= 3; type++) {
                    out.add(Arguments.of(tx, type));
                }
            }
        }
        return out;
    }

    @ParameterizedTest(name = "tx {0} type {1}")
    @MethodSource("cases")
    @DisplayName("forward then inverse returns the residual")
    void roundTrip(int tx, int type) {
        var w = Tables.TX_W[tx] * 4;
        var h = Tables.TX_H[tx] * 4;
        var random = new Random(tx * 31L + type);
        var residual = new int[w * h];
        var dst = new short[w * h];
        for (var i = 0; i < residual.length; i++) {
            var smooth = (i % w) * 3 + (i / w) * 2;
            residual[i] = smooth + random.nextInt(21) - 10 - 40;
            dst[i] = 128;
        }
        var fwd = new FwdTxfm();
        var coef = new int[32 * 32];
        fwd.forward(tx, type, residual, 0, w, coef);
        var n = Math.min(w, 32) * Math.min(h, 32);
        ItxDsp.select().itxfmAdd(tx, type, dst, 0, w, coef, 0, n - 1, new int[ItxDsp.SCRATCH_INTS], 255);
        var worst = 0;
        for (var i = 0; i < residual.length; i++) {
            worst = Math.max(worst, Math.abs(dst[i] - 128 - residual[i]));
        }
        assertTrue(worst <= 2, "worst error " + worst);
    }
}
