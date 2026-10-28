package com.github.auties00.smalt.video.h264.internal;

/**
 * Derives the neighboring locations of a macroblock of a layer picture (ITU-T H.264 6.4.12): the
 * macroblock that holds a luma or chroma location outside the current macroblock and the location
 * inside it, in fields and frames (6.4.12.1) and in frames with {@code MbaffFrameFlag} 1
 * (6.4.12.2, Table 6-4).
 *
 * <p>{@link #setMacroblock} sets the current macroblock and the availability of its neighboring
 * macroblocks (or macroblock pairs) A, B, C and D, which the caller derives with the availability
 * rules of the process it serves; {@link #locate} then maps locations.
 */
final class SvcNeighbors {
    /**
     * The horizontal location {@code xW} inside the macroblock {@link #locate} returned.
     */
    int xW;

    /**
     * The vertical location {@code yW} inside the macroblock {@link #locate} returned.
     */
    int yW;

    /**
     * The arrays of the layer picture.
     */
    private SvcLayer layer;

    /**
     * The current macroblock address.
     */
    private int current;

    /**
     * Whether the layer picture is a frame with {@code MbaffFrameFlag} 1.
     */
    private boolean mbaff;

    /**
     * The address of the neighboring macroblock A, the top macroblock of the left pair in an MBAFF
     * frame, or -1 when not available.
     */
    private int mbA;

    /**
     * The address of the neighboring macroblock B, the top macroblock of the upper pair in an MBAFF
     * frame, or -1 when not available.
     */
    private int mbB;

    /**
     * The address of the neighboring macroblock C, the top macroblock of the upper right pair in an
     * MBAFF frame, or -1 when not available.
     */
    private int mbC;

    /**
     * The address of the neighboring macroblock D, the top macroblock of the upper left pair in an
     * MBAFF frame, or -1 when not available.
     */
    private int mbD;

    /**
     * Constructs a neighbor derivation.
     */
    SvcNeighbors() {
    }

    /**
     * Returns the addresses of the neighboring macroblocks A, B, C and D of a macroblock (ITU-T
     * H.264 6.4.9 and 6.4.10) before their availability is checked.
     *
     * @param layer the arrays of the layer picture
     * @param mb the macroblock address
     * @param n receives A, B, C and D, -1 for those outside the picture
     */
    static void candidates(SvcLayer layer, int mb, int[] n) {
        var w = layer.widthMbs;
        if (!layer.mbaff) {
            var x = mb % w;
            var y = mb / w;
            n[0] = x > 0 ? mb - 1 : -1;
            n[1] = y > 0 ? mb - w : -1;
            n[2] = y > 0 && x < w - 1 ? mb - w + 1 : -1;
            n[3] = y > 0 && x > 0 ? mb - w - 1 : -1;
            return;
        }
        var pair = mb >> 1;
        var x = pair % w;
        var y = pair / w;
        n[0] = x > 0 ? 2 * (pair - 1) : -1;
        n[1] = y > 0 ? 2 * (pair - w) : -1;
        n[2] = y > 0 && x < w - 1 ? 2 * (pair - w + 1) : -1;
        n[3] = y > 0 && x > 0 ? 2 * (pair - w - 1) : -1;
    }

    /**
     * Sets the current macroblock and its available neighbors.
     *
     * @param layer the arrays of the layer picture, whose {@code fieldMb} holds the field
     * decoding flag of the current macroblock and its neighbors
     * @param mb the current macroblock address
     * @param a the neighbor A, or -1 when not available
     * @param b the neighbor B, or -1 when not available
     * @param c the neighbor C, or -1 when not available
     * @param d the neighbor D, or -1 when not available
     */
    void setMacroblock(SvcLayer layer, int mb, int a, int b, int c, int d) {
        this.layer = layer;
        this.current = mb;
        this.mbaff = layer.mbaff;
        this.mbA = a;
        this.mbB = b;
        this.mbC = c;
        this.mbD = d;
    }

    /**
     * Returns the neighbor A of the current macroblock (the top macroblock of the left pair in an
     * MBAFF frame).
     *
     * @return its address, or -1 when not available
     */
    int a() {
        return mbA;
    }

    /**
     * Returns the neighbor B of the current macroblock (the top macroblock of the upper pair in an
     * MBAFF frame).
     *
     * @return its address, or -1 when not available
     */
    int b() {
        return mbB;
    }

    /**
     * Returns the neighbor C of the current macroblock.
     *
     * @return its address, or -1 when not available
     */
    int c() {
        return mbC;
    }

    /**
     * Returns the neighbor D of the current macroblock.
     *
     * @return its address, or -1 when not available
     */
    int d() {
        return mbD;
    }

    /**
     * Derives the macroblock holding a neighboring location and the location inside it, into
     * {@link #xW} and {@link #yW} (ITU-T H.264 6.4.12).
     *
     * @param xN the horizontal location relative to the upper-left sample of the current
     * macroblock
     * @param yN the vertical location relative to the upper-left sample of the current macroblock
     * @param maxW the macroblock width of the component: 16 for luma
     * @param maxH the macroblock height of the component: 16 for luma
     * @return the address of the macroblock, or -1 when it is not available
     */
    int locate(int xN, int yN, int maxW, int maxH) {
        if (yN > maxH - 1 || (xN > maxW - 1 && yN >= 0)) {
            return -1;
        }
        xW = (xN + maxW) % maxW;
        if (!mbaff) {
            yW = (yN + maxH) % maxH;
            if (xN < 0) {
                return yN < 0 ? mbD : mbA;
            }
            if (xN < maxW) {
                return yN < 0 ? mbB : current;
            }
            return mbC;
        }
        var frame = !layer.fieldMb[current];
        var top = (current & 1) == 0;
        int address;
        int yM;
        if (xN < 0) {
            if (yN < 0) {
                if (frame) {
                    if (top) {
                        address = mbD < 0 ? -1 : mbD + 1;
                        yM = yN;
                    } else if (mbA < 0) {
                        return -1;
                    } else if (!layer.fieldMb[mbA]) {
                        address = mbA;
                        yM = yN;
                    } else {
                        address = mbA + 1;
                        yM = (yN + maxH) >> 1;
                    }
                } else if (top) {
                    if (mbD < 0) {
                        return -1;
                    }
                    if (!layer.fieldMb[mbD]) {
                        address = mbD + 1;
                        yM = 2 * yN;
                    } else {
                        address = mbD;
                        yM = yN;
                    }
                } else {
                    address = mbD < 0 ? -1 : mbD + 1;
                    yM = yN;
                }
            } else {
                if (mbA < 0) {
                    return -1;
                }
                var fieldA = layer.fieldMb[mbA];
                if (frame) {
                    if (top) {
                        if (!fieldA) {
                            address = mbA;
                            yM = yN;
                        } else {
                            address = mbA + (yN & 1);
                            yM = yN >> 1;
                        }
                    } else if (!fieldA) {
                        address = mbA + 1;
                        yM = yN;
                    } else {
                        address = mbA + (yN & 1);
                        yM = (yN + maxH) >> 1;
                    }
                } else if (top) {
                    if (!fieldA) {
                        if (yN < maxH / 2) {
                            address = mbA;
                            yM = yN << 1;
                        } else {
                            address = mbA + 1;
                            yM = (yN << 1) - maxH;
                        }
                    } else {
                        address = mbA;
                        yM = yN;
                    }
                } else if (!fieldA) {
                    if (yN < maxH / 2) {
                        address = mbA;
                        yM = (yN << 1) + 1;
                    } else {
                        address = mbA + 1;
                        yM = (yN << 1) + 1 - maxH;
                    }
                } else {
                    address = mbA + 1;
                    yM = yN;
                }
            }
        } else if (xN < maxW) {
            if (yN >= 0) {
                yW = yN;
                return current;
            }
            if (frame) {
                if (top) {
                    address = mbB < 0 ? -1 : mbB + 1;
                } else {
                    address = current - 1;
                }
                yM = yN;
            } else if (top) {
                if (mbB < 0) {
                    return -1;
                }
                if (!layer.fieldMb[mbB]) {
                    address = mbB + 1;
                    yM = 2 * yN;
                } else {
                    address = mbB;
                    yM = yN;
                }
            } else {
                address = mbB < 0 ? -1 : mbB + 1;
                yM = yN;
            }
        } else {
            if (frame) {
                if (!top) {
                    return -1;
                }
                address = mbC < 0 ? -1 : mbC + 1;
                yM = yN;
            } else if (top) {
                if (mbC < 0) {
                    return -1;
                }
                if (!layer.fieldMb[mbC]) {
                    address = mbC + 1;
                    yM = 2 * yN;
                } else {
                    address = mbC;
                    yM = yN;
                }
            } else {
                address = mbC < 0 ? -1 : mbC + 1;
                yM = yN;
            }
        }
        yW = (yM + maxH) % maxH;
        return address;
    }
}
