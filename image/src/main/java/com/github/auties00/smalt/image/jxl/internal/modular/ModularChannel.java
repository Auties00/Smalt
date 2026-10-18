/* Ports jxl-oxide 0.12.6 (crates/jxl-modular). Copyright Wonwoo Choi and the jxl-oxide contributors; MIT License.
   Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.modular;

/**
 * Holds one channel of a modular image: its size, its subsampling shifts and its integer samples
 * in raster order.
 *
 * <p>A shift of -1 marks a meta channel (a palette) that is never subsampled. Instances are not
 * thread-safe.
 */
public final class ModularChannel {
    /**
     * The width in samples.
     */
    public int width;

    /**
     * The height in samples.
     */
    public int height;

    /**
     * The horizontal subsampling shift, or -1 for a meta channel.
     */
    public int hshift;

    /**
     * The vertical subsampling shift, or -1 for a meta channel.
     */
    public int vshift;

    /**
     * The samples, {@code width * height} of them, row by row; {@code null} until allocated.
     */
    public int[] data;

    /**
     * Creates a channel without samples.
     *
     * @param width the width in samples
     * @param height the height in samples
     * @param hshift the horizontal shift
     * @param vshift the vertical shift
     */
    public ModularChannel(int width, int height, int hshift, int vshift) {
        this.width = width;
        this.height = height;
        this.hshift = hshift;
        this.vshift = vshift;
    }

    /**
     * Returns a channel of the same geometry without samples.
     *
     * @return the copy
     */
    public ModularChannel copyInfo() {
        return new ModularChannel(width, height, hshift, vshift);
    }

    /**
     * Returns whether another channel has the same size and shifts.
     *
     * @param other the other channel
     * @return whether the geometries match
     */
    boolean sameGeometry(ModularChannel other) {
        return width == other.width && height == other.height && hshift == other.hshift && vshift == other.vshift;
    }

    /**
     * Allocates zeroed samples when they are missing.
     */
    void allocate() {
        if (data == null) {
            data = new int[width * height];
        }
    }
}
