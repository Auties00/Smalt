/* Ports jxl-oxide 0.12.6 (crates/jxl-render: the frame buffers). Copyright Wonwoo Choi and the jxl-oxide
   contributors; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.render;

import java.util.Arrays;

/**
 * Holds the floating-point planes of a frame or a canvas: the color channels (one or three)
 * followed by the extra channels, every plane the same size.
 *
 * <p>Some operations split their rows among the shared helper threads. Instances are not
 * thread-safe.
 */
public final class FrameImage {
    /**
     * The planes, color channels first, row by row.
     */
    public final float[][] planes;

    /**
     * The number of color channels.
     */
    public int colorChannels;

    /**
     * The width of every plane.
     */
    public final int width;

    /**
     * The height of every plane.
     */
    public final int height;

    /**
     * Whether the color channels are already in the output color space.
     */
    public boolean colorTransformed;

    /**
     * Creates an image from planes.
     *
     * @param planes the planes, every one {@code width * height} samples
     * @param colorChannels the number of color channels
     * @param width the plane width in samples
     * @param height the plane height in samples
     */
    public FrameImage(float[][] planes, int colorChannels, int width, int height) {
        this.planes = planes;
        this.colorChannels = colorChannels;
        this.width = width;
        this.height = height;
    }

    /**
     * Creates an image of zeroed planes.
     *
     * @param channels the total number of planes
     * @param colorChannels the number of color channels
     * @param width the plane width in samples
     * @param height the plane height in samples
     * @return the image
     */
    public static FrameImage zeroed(int channels, int colorChannels, int width, int height) {
        var planes = new float[channels][];
        for (var i = 0; i < channels; i++) {
            planes[i] = new float[width * height];
        }
        return new FrameImage(planes, colorChannels, width, height);
    }

    /**
     * Returns a deep copy.
     *
     * @return the copy
     */
    public FrameImage copy() {
        var copied = new float[planes.length][];
        for (var i = 0; i < planes.length; i++) {
            copied[i] = planes[i].clone();
        }
        var image = new FrameImage(copied, colorChannels, width, height);
        image.colorTransformed = colorTransformed;
        return image;
    }

    /**
     * Returns the number of extra channels.
     *
     * @return the extra channel count
     */
    public int extraChannels() {
        return planes.length - colorChannels;
    }

    /**
     * Returns the plane of an extra channel.
     *
     * @param index the extra channel index
     * @return the plane
     */
    public float[] extra(int index) {
        return planes[colorChannels + index];
    }

    /**
     * Returns an image with the color channels replaced.
     *
     * @param color the new color planes
     * @return the image, sharing the extra planes
     */
    public FrameImage withColor(float[][] color) {
        var all = new float[color.length + extraChannels()][];
        System.arraycopy(color, 0, all, 0, color.length);
        System.arraycopy(planes, colorChannels, all, color.length, extraChannels());
        var image = new FrameImage(all, color.length, width, height);
        image.colorTransformed = colorTransformed;
        return image;
    }

    /**
     * Fills a plane with a value.
     *
     * @param index the plane index
     * @param value the sample value
     */
    public void fill(int index, float value) {
        Arrays.fill(planes[index], value);
    }
}
