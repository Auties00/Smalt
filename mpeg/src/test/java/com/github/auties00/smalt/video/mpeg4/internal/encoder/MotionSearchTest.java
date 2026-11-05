package com.github.auties00.smalt.video.mpeg4.internal.encoder;

import com.github.auties00.smalt.video.mpeg4.EncoderScenes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that the motion search finds displacements beyond the reach of its step search alone: the fast scene's
 * background moves 20 samples right and 9 down a picture over a texture with many local minima, so only the pyramid
 * candidate leads the search to the true vector.
 */
@DisplayName("motion search")
class MotionSearchTest {
    @ParameterizedTest(name = "{0} samples, range {1}")
    @CsvSource({"half, 32", "quarter, 32", "half, 24"})
    @DisplayName("finds the true vector of every macroblock that moves with the background")
    void fastBackground(String precision, int range) {
        var width = 176;
        var height = 144;
        var quarter = precision.equals("quarter");
        var units = quarter ? 4 : 2;
        var frames = new SourceFrames(width, height);
        var ref = frames.copy(EncoderScenes.picture(width, height, 3, EncoderScenes.Motion.FAST));
        var source = frames.copy(EncoderScenes.picture(width, height, 4, EncoderScenes.Motion.FAST));
        var sourcePyramid = new MotionPyramid(11, 9);
        var refPyramid = new MotionPyramid(11, 9);
        sourcePyramid.build(source);
        refPyramid.build(ref);
        var search = new MotionSearch();
        search.configure(quarter, 0, 4);
        var limit = range * units;
        search.bounds(-limit, limit - 1, -limit, limit - 1);
        var eligible = 0;
        var found = 0;
        var misses = new StringBuilder();
        for (var my = 0; my < 9; my++) {
            for (var mx = 0; mx < 11; mx++) {
                var x = mx * 16;
                var y = my * 16;
                // The block and the block it moves from lie inside the picture and clear of the moving square
                // (at 120, 40 and 123, 39 with side 36 in pictures 4 and 3).
                if (x + 20 + 16 > width || y + 9 + 16 > height || x + 16 + 20 > 120 && y < 40 + 36 && y + 16 + 9 > 39) {
                    continue;
                }
                eligible++;
                var candidates = new int[2 + 2 * MotionSearch.PYRAMID_CANDIDATES];
                var count = 1 + search.pyramidCandidates(sourcePyramid, refPyramid, source, ref, x, y, range,
                        candidates, 2);
                search.search(source, ref, x, y, 16, candidates, count, 0, 0, range);
                if (search.bestX == 20 * units && search.bestY == 9 * units) {
                    found++;
                } else {
                    misses.append(String.format(" (%d,%d)=(%d,%d)", mx, my, search.bestX, search.bestY));
                }
            }
        }
        assertTrue(eligible >= 40, "eligible macroblocks " + eligible);
        assertTrue(found == eligible, found + " of " + eligible + " macroblocks at the true vector; misses"
                + misses);
    }
}
