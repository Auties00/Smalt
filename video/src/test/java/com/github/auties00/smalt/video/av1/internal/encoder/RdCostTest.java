package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.internal.Cdf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the motion vector cost tables against a direct evaluation of every vector's class,
 * integer, fraction and precision symbols.
 */
class RdCostTest {
    private static int[] costs(char[] cdf, int off, int n) {
        var out = new int[n + 1];
        RdCost.costsFromCdf(out, 0, cdf, off, n);
        return out;
    }

    @ParameterizedTest(name = "hp {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("the motion vector cost table matches the per-vector evaluation")
    void mvCosts(boolean hp) {
        var cdf = new char[Cdf.SIZE];
        Cdf.loadDefaults(cdf, Cdf.qcat(120));
        var rd = new RdCost();
        rd.setup(cdf, 120, false, false, hp);
        for (var comp = 0; comp < 2; comp++) {
            var c0 = comp * Cdf.MV_COMP_STRIDE;
            var sign = costs(cdf, c0 + Cdf.MV_SIGN, 1);
            var classes = costs(cdf, c0 + Cdf.MV_CLASSES, 10);
            var class0 = costs(cdf, c0 + Cdf.MV_CLASS0, 1);
            var class0Fp0 = costs(cdf, c0 + Cdf.MV_CLASS0_FP, 3);
            var class0Fp1 = costs(cdf, c0 + Cdf.MV_CLASS0_FP + 4, 3);
            var fp = costs(cdf, c0 + Cdf.MV_CLASSN_FP, 3);
            var class0Hp = costs(cdf, c0 + Cdf.MV_CLASS0_HP, 1);
            var hpCost = costs(cdf, c0 + Cdf.MV_CLASSN_HP, 1);
            for (var v = 1; v <= RdCost.MV_MAX; v++) {
                var z = v - 1;
                var c = z >= 2 * 4096 ? 10 : Math.max(0, 31 - Integer.numberOfLeadingZeros(z >> 3));
                var o = z - (c != 0 ? 2 << (c + 2) : 0);
                var d = o >> 3;
                var f = (o >> 1) & 3;
                var e = o & 1;
                var cost = classes[c];
                if (c == 0) {
                    cost += class0[d] + (d == 0 ? class0Fp0[f] : class0Fp1[f]);
                } else {
                    for (var i = 0; i < c; i++) {
                        cost += costs(cdf, c0 + Cdf.MV_CLASSN + i * 2, 1)[(d >> i) & 1];
                    }
                    cost += fp[f];
                }
                if (hp) {
                    cost += c == 0 ? class0Hp[e] : hpCost[e];
                }
                assertEquals(cost + sign[0], rd.mvComp[comp][RdCost.MV_MAX + v], "+" + v);
                assertEquals(cost + sign[1], rd.mvComp[comp][RdCost.MV_MAX - v], "-" + v);
            }
        }
    }
}
