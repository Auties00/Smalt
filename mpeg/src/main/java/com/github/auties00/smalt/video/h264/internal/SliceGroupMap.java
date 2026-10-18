package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Derives the macroblock to slice group map of flexible macroblock ordering (ITU-T H.264
 * 8.2.2) for frame pictures.
 */
final class SliceGroupMap {
    private SliceGroupMap() {
        throw new AssertionError();
    }

    /**
     * Computes the macroblock to slice group map of a frame picture.
     *
     * @param pps the picture parameter set, with more than one slice group
     * @param sps the sequence parameter set
     * @param cycle the {@code slice_group_change_cycle} of the slice
     * @param target receives the slice group of every macroblock; at least
     * {@code widthInMbs * frameHeightInMbs} entries
     * @throws MediaException.Malformed if the map parameters are inconsistent with the picture
     */
    static void compute(H264Pps pps, H264Sps sps, int cycle, int[] target) {
        var width = sps.widthInMbs;
        var heightUnits = sps.heightInMapUnits;
        var units = width * heightUnits;
        var map = new int[units];
        var groups = pps.numSliceGroups;
        var direction = pps.sliceGroupChangeDirection ? 1 : 0;
        var group0 = (int) Math.min((long) cycle * Math.max(pps.sliceGroupChangeRate, 1), units);
        switch (pps.sliceGroupMapType) {
            case 0 -> {
                var i = 0;
                do {
                    for (var group = 0; group < groups && i < units; i += pps.runLength[group++]) {
                        for (var j = 0; j < pps.runLength[group] && i + j < units; j++) {
                            map[i + j] = group;
                        }
                    }
                } while (i < units);
            }
            case 1 -> {
                for (var i = 0; i < units; i++) {
                    map[i] = ((i % width) + (((i / width) * groups) / 2)) % groups;
                }
            }
            case 2 -> {
                Arrays.fill(map, groups - 1);
                for (var group = groups - 2; group >= 0; group--) {
                    var yTop = pps.topLeft[group] / width;
                    var xTop = pps.topLeft[group] % width;
                    var yBottom = pps.bottomRight[group] / width;
                    var xBottom = pps.bottomRight[group] % width;
                    if (yBottom >= heightUnits || yTop > yBottom || xTop > xBottom) {
                        throw new MediaException.Malformed("invalid slice group rectangle");
                    }
                    for (var y = yTop; y <= yBottom; y++) {
                        for (var x = xTop; x <= xBottom; x++) {
                            map[y * width + x] = group;
                        }
                    }
                }
            }
            case 3 -> boxOut(map, width, heightUnits, direction, group0);
            case 4 -> {
                var upperLeft = direction != 0 ? units - group0 : group0;
                for (var i = 0; i < units; i++) {
                    map[i] = i < upperLeft ? direction : 1 - direction;
                }
            }
            case 5 -> {
                var upperLeft = direction != 0 ? units - group0 : group0;
                var k = 0;
                for (var x = 0; x < width; x++) {
                    for (var y = 0; y < heightUnits; y++) {
                        map[y * width + x] = k++ < upperLeft ? direction : 1 - direction;
                    }
                }
            }
            case 6 -> {
                if (pps.sliceGroupId.length != units) {
                    throw new MediaException.Malformed("slice group map size mismatch");
                }
                System.arraycopy(pps.sliceGroupId, 0, map, 0, units);
            }
            default -> throw new MediaException.Malformed("invalid slice_group_map_type");
        }
        if (sps.frameMbsOnly) {
            System.arraycopy(map, 0, target, 0, units);
        } else {
            var count = width * sps.frameHeightInMbs;
            for (var i = 0; i < count; i++) {
                target[i] = map[(i / (2 * width)) * width + (i % width)];
            }
        }
    }

    /**
     * Fills the box-out map (ITU-T H.264 8.2.2.4).
     *
     * @param map the map units, receives the groups
     * @param width the width in map units
     * @param height the height in map units
     * @param direction the {@code slice_group_change_direction_flag}
     * @param group0 the number of map units in slice group 0
     */
    private static void boxOut(int[] map, int width, int height, int direction, int group0) {
        Arrays.fill(map, 1);
        var x = (width - direction) / 2;
        var y = (height - direction) / 2;
        var leftBound = x;
        var topBound = y;
        var rightBound = x;
        var bottomBound = y;
        var xDir = direction - 1;
        var yDir = direction;
        var guard = 4L * map.length + 16;
        for (var k = 0; k < group0; ) {
            if (--guard < 0) {
                throw new MediaException.Malformed("invalid box-out slice group map");
            }
            var vacant = map[y * width + x] == 1;
            if (vacant) {
                map[y * width + x] = 0;
                k++;
            }
            if (xDir == -1 && x == leftBound) {
                leftBound = Math.max(leftBound - 1, 0);
                x = leftBound;
                xDir = 0;
                yDir = 2 * direction - 1;
            } else if (xDir == 1 && x == rightBound) {
                rightBound = Math.min(rightBound + 1, width - 1);
                x = rightBound;
                xDir = 0;
                yDir = 1 - 2 * direction;
            } else if (yDir == -1 && y == topBound) {
                topBound = Math.max(topBound - 1, 0);
                y = topBound;
                xDir = 1 - 2 * direction;
                yDir = 0;
            } else if (yDir == 1 && y == bottomBound) {
                bottomBound = Math.min(bottomBound + 1, height - 1);
                y = bottomBound;
                xDir = 2 * direction - 1;
                yDir = 0;
            } else {
                x += xDir;
                y += yDir;
            }
        }
    }
}
