package com.github.auties00.smalt.video.hevc.encoder.internal;

/**
 * Saves and restores the reconstruction and the decisions of a coding unit's area, one slot per coding tree
 * depth, so the mode decision can keep the best candidate of each depth while it tries others in place.
 *
 * <p>A slot holds the reconstructed samples, the levels and every per 4x4 block decision array of a square
 * area clipped to the picture. Instances are not thread-safe.
 */
final class RegionStore {
    /**
     * The slots, indexed by coding tree depth.
     */
    private final Slot[] slots;

    /**
     * Constructs a store for coding tree blocks of 64x64 samples and coding blocks down to 8x8.
     */
    RegionStore() {
        slots = new Slot[4];
        for (var depth = 0; depth < 4; depth++) {
            slots[depth] = new Slot(64 >> depth);
        }
    }

    /**
     * Saves an area.
     *
     * @param d the picture
     * @param depth the slot, the coding tree depth of the area
     * @param x0 the luma x
     * @param y0 the luma y
     * @param size the side of the area in luma samples
     */
    void save(PictureData d, int depth, int x0, int y0, int size) {
        slots[depth].copy(d, x0, y0, size, true);
    }

    /**
     * Restores an area saved in a slot.
     *
     * @param d the picture
     * @param depth the slot
     * @param x0 the luma x
     * @param y0 the luma y
     * @param size the side of the area in luma samples
     */
    void restore(PictureData d, int depth, int x0, int y0, int size) {
        slots[depth].copy(d, x0, y0, size, false);
    }

    /**
     * The saved state of one area.
     */
    private static final class Slot {
        private final short[] reconY;
        private final short[] reconCb;
        private final short[] reconCr;
        private final short[] levelY;
        private final short[] levelCb;
        private final short[] levelCr;
        private final byte[] flags;
        private final byte[] depth;
        private final byte[] intraMode;
        private final byte[] cuInfo;
        private final byte[] tuInfo;
        private final byte[] ref0;
        private final byte[] ref1;
        private final int[] puInfo;
        private final int[] mvd0;
        private final int[] mvd1;
        private final int[] mv0;
        private final int[] mv1;

        /**
         * Allocates a slot.
         *
         * @param size the largest side in luma samples
         */
        Slot(int size) {
            var luma = size * size;
            var chroma = luma / 4;
            var blocks = luma / 16;
            reconY = new short[luma];
            reconCb = new short[chroma];
            reconCr = new short[chroma];
            levelY = new short[luma];
            levelCb = new short[chroma];
            levelCr = new short[chroma];
            flags = new byte[blocks];
            depth = new byte[blocks];
            intraMode = new byte[blocks];
            cuInfo = new byte[blocks];
            tuInfo = new byte[blocks];
            ref0 = new byte[blocks];
            ref1 = new byte[blocks];
            puInfo = new int[blocks];
            mvd0 = new int[blocks];
            mvd1 = new int[blocks];
            mv0 = new int[blocks];
            mv1 = new int[blocks];
        }

        /**
         * Copies an area between the picture and the slot.
         *
         * @param d the picture
         * @param x0 the luma x
         * @param y0 the luma y
         * @param size the side in luma samples
         * @param save whether to copy from the picture into the slot
         */
        void copy(PictureData d, int x0, int y0, int size, boolean save) {
            var s = d.s;
            var width = Math.min(size, s.codedWidth - x0);
            var height = Math.min(size, s.codedHeight - y0);
            plane(d.frame.y, s.codedWidth, y0 * s.codedWidth + x0, reconY, size, width, height, save);
            plane(d.levelY, s.codedWidth, y0 * s.codedWidth + x0, levelY, size, width, height, save);
            var cOffset = (y0 >> 1) * s.chromaWidth + (x0 >> 1);
            var half = size >> 1;
            plane(d.frame.cb, s.chromaWidth, cOffset, reconCb, half, width >> 1, height >> 1, save);
            plane(d.frame.cr, s.chromaWidth, cOffset, reconCr, half, width >> 1, height >> 1, save);
            plane(d.levelCb, s.chromaWidth, cOffset, levelCb, half, width >> 1, height >> 1, save);
            plane(d.levelCr, s.chromaWidth, cOffset, levelCr, half, width >> 1, height >> 1, save);
            var w4 = d.pic.width4;
            var origin = (y0 >> 2) * w4 + (x0 >> 2);
            var bw = width >> 2;
            var bh = height >> 2;
            var stride = size >> 2;
            bytes(d.pic.flags, w4, origin, flags, stride, bw, bh, save);
            bytes(d.pic.depth, w4, origin, depth, stride, bw, bh, save);
            bytes(d.pic.intraMode, w4, origin, intraMode, stride, bw, bh, save);
            bytes(d.cuInfo, w4, origin, cuInfo, stride, bw, bh, save);
            bytes(d.tuInfo, w4, origin, tuInfo, stride, bw, bh, save);
            bytes(d.frame.ref0, w4, origin, ref0, stride, bw, bh, save);
            bytes(d.frame.ref1, w4, origin, ref1, stride, bw, bh, save);
            ints(d.puInfo, w4, origin, puInfo, stride, bw, bh, save);
            ints(d.mvd0, w4, origin, mvd0, stride, bw, bh, save);
            ints(d.mvd1, w4, origin, mvd1, stride, bw, bh, save);
            ints(d.frame.mv0, w4, origin, mv0, stride, bw, bh, save);
            ints(d.frame.mv1, w4, origin, mv1, stride, bw, bh, save);
        }

        /**
         * Copies a rectangle of 16-bit samples.
         *
         * @param plane the picture plane
         * @param planeStride the row stride of {@code plane}
         * @param offset the index of the rectangle's top-left sample in {@code plane}
         * @param slot the slot buffer
         * @param slotStride the row stride of {@code slot}
         * @param width the rectangle width
         * @param height the rectangle height
         * @param save whether to copy from the plane into the slot
         */
        private static void plane(short[] plane, int planeStride, int offset, short[] slot, int slotStride, int width,
                                  int height, boolean save) {
            for (var y = 0; y < height; y++) {
                if (save) {
                    System.arraycopy(plane, offset + y * planeStride, slot, y * slotStride, width);
                } else {
                    System.arraycopy(slot, y * slotStride, plane, offset + y * planeStride, width);
                }
            }
        }

        /**
         * Copies a rectangle of a byte block array.
         *
         * @param array the picture array
         * @param arrayStride the row stride of {@code array}
         * @param offset the index of the rectangle's top-left entry in {@code array}
         * @param slot the slot buffer
         * @param slotStride the row stride of {@code slot}
         * @param width the rectangle width
         * @param height the rectangle height
         * @param save whether to copy from the array into the slot
         */
        private static void bytes(byte[] array, int arrayStride, int offset, byte[] slot, int slotStride, int width,
                                  int height, boolean save) {
            for (var y = 0; y < height; y++) {
                if (save) {
                    System.arraycopy(array, offset + y * arrayStride, slot, y * slotStride, width);
                } else {
                    System.arraycopy(slot, y * slotStride, array, offset + y * arrayStride, width);
                }
            }
        }

        /**
         * Copies a rectangle of an int block array.
         *
         * @param array the picture array
         * @param arrayStride the row stride of {@code array}
         * @param offset the index of the rectangle's top-left entry in {@code array}
         * @param slot the slot buffer
         * @param slotStride the row stride of {@code slot}
         * @param width the rectangle width
         * @param height the rectangle height
         * @param save whether to copy from the array into the slot
         */
        private static void ints(int[] array, int arrayStride, int offset, int[] slot, int slotStride, int width,
                                 int height, boolean save) {
            for (var y = 0; y < height; y++) {
                if (save) {
                    System.arraycopy(array, offset + y * arrayStride, slot, y * slotStride, width);
                } else {
                    System.arraycopy(slot, y * slotStride, array, offset + y * arrayStride, width);
                }
            }
        }
    }
}
