package com.github.auties00.smalt.video.dv.internal;

/**
 * The 8-bit limited-range samples of a picture being encoded, in the chroma layout of the profile: chroma planes a
 * quarter of the width for 4:1:1, half the width (and half the height for 4:2:0) otherwise.
 *
 * @param luma the luma plane
 * @param lumaStride the luma row stride
 * @param cb the Cb plane
 * @param cr the Cr plane
 * @param chromaStride the chroma row stride
 */
record DvSource(byte[] luma, int lumaStride, byte[] cb, byte[] cr, int chromaStride) {
}
