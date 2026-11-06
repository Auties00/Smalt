/* Ports the Digital Negative (DNG) Specification 1.7.1.0, Adobe. This product includes DNG technology under license
   by Adobe. */
package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;

/**
 * Holds a DNG semantic mask (DNG 1.6, {@code NewSubFileType} 65540): a single-channel image naming
 * a part of the scene, from 0 (not part of it) to 1 (part of it), that covers the active area of the
 * main image at its own resolution, possibly cropped to a sub-area ({@code MaskSubArea}) outside
 * which it is 0.
 *
 * <p>The mask is decoded on first use. Values are sampled with bilinear interpolation at positions
 * relative to the active area, pixel centers at half-pixel offsets.
 */
final class SemanticMask {
    /**
     * The tag of the mask's name.
     */
    static final int SEMANTIC_NAME = 52526;

    /**
     * The tag of the mask's sub-area.
     */
    static final int MASK_SUB_AREA = 52536;

    /**
     * The name ({@code SemanticName}).
     */
    final String name;

    /**
     * The directory holding the mask.
     */
    private final Ifd ifd;

    /**
     * The row of the full mask the stored mask starts at.
     */
    private final int top;

    /**
     * The column of the full mask the stored mask starts at.
     */
    private final int left;

    /**
     * The width of the full mask, which spans the active area.
     */
    private final int fullWidth;

    /**
     * The height of the full mask.
     */
    private final int fullHeight;

    /**
     * The stored width.
     */
    private final int width;

    /**
     * The stored height.
     */
    private final int height;

    /**
     * The values, decoded on first use; guarded by {@code this}.
     */
    private float[] values;

    /**
     * Constructs a mask.
     *
     * @param name the mask's {@code SemanticName}
     * @param ifd the directory holding the mask
     * @param top the row of the full mask the stored mask starts at
     * @param left the column of the full mask the stored mask starts at
     * @param fullWidth the width of the full mask
     * @param fullHeight the height of the full mask
     * @param width the stored width
     * @param height the stored height
     */
    private SemanticMask(String name, Ifd ifd, int top, int left, int fullWidth, int fullHeight, int width, int height) {
        this.name = name;
        this.ifd = ifd;
        this.top = top;
        this.left = left;
        this.fullWidth = fullWidth;
        this.fullHeight = fullHeight;
        this.width = width;
        this.height = height;
    }

    /**
     * Describes the semantic mask a directory holds.
     *
     * @param ifd the directory, of {@code NewSubFileType} 65540
     * @return the mask, or {@code null} when it has no name or no valid size
     */
    static SemanticMask of(Ifd ifd) {
        var name = ifd.string(SEMANTIC_NAME);
        var width = ifd.intValue(TiffSamples.IMAGE_WIDTH, 0);
        var height = ifd.intValue(TiffSamples.IMAGE_LENGTH, 0);
        if (name == null || width <= 0 || height <= 0) {
            return null;
        }
        var area = ifd.longs(MASK_SUB_AREA);
        var top = 0;
        var left = 0;
        var fullWidth = width;
        var fullHeight = height;
        // the specification's validity test compares the crop's top with the width and its left with the
        // height; the test here checks that the stored mask fits the full one
        if (area != null && area.length >= 4 && area[2] > 0 && area[3] > 0 && area[0] >= 0 && area[1] >= 0
                && area[0] + height <= area[3] && area[1] + width <= area[2] && area[2] <= 1 << 20 && area[3] <= 1 << 20) {
            top = (int) area[0];
            left = (int) area[1];
            fullWidth = (int) area[2];
            fullHeight = (int) area[3];
        }
        return new SemanticMask(name, ifd, top, left, fullWidth, fullHeight, width, height);
    }

    /**
     * Decodes the mask if it is not decoded yet.
     *
     * @param limits the resource bounds
     * @return the values, row by row
     * @throws MediaException.Malformed if the mask cannot be decoded
     */
    synchronized float[] values(MediaLimits limits) {
        if (values == null) {
            var sensor = new Sensor();
            var reader = TiffSamples.describe(ifd, sensor);
            if (sensor.samplesPerPixel != 1) {
                throw new MediaException.Malformed("semantic mask of " + sensor.samplesPerPixel + " samples");
            }
            var plane = SensorPlane.of(sensor, limits);
            reader.read(plane, limits);
            var result = new float[width * height];
            var maximum = (float) sensor.maximum();
            for (var index = 0; index < result.length; index++) {
                result[index] = plane.floats != null ? Math.clamp(plane.floats[index], 0f, 1f) : plane.sample(index) / maximum;
            }
            values = result;
        }
        return values;
    }

    /**
     * Samples the mask.
     *
     * @param values the decoded values
     * @param u the horizontal position relative to the active area, from 0 to 1
     * @param v the vertical position relative to the active area, from 0 to 1
     * @return the value, 0 outside the stored mask
     */
    float sample(float[] values, double u, double v) {
        var x = u * fullWidth - 0.5 - left;
        var y = v * fullHeight - 0.5 - top;
        if (x < -0.5 || y < -0.5 || x > width - 0.5 || y > height - 0.5) {
            return 0;
        }
        x = Math.clamp(x, 0, width - 1);
        y = Math.clamp(y, 0, height - 1);
        var x0 = (int) x;
        var y0 = (int) y;
        var x1 = Math.min(x0 + 1, width - 1);
        var y1 = Math.min(y0 + 1, height - 1);
        var tx = (float) (x - x0);
        var ty = (float) (y - y0);
        var upper = values[y0 * width + x0] + (values[y0 * width + x1] - values[y0 * width + x0]) * tx;
        var lower = values[y1 * width + x0] + (values[y1 * width + x1] - values[y1 * width + x0]) * tx;
        return upper + (lower - upper) * ty;
    }
}
