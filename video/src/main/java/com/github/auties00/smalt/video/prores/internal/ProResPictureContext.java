package com.github.auties00.smalt.video.prores.internal;

/**
 * Describes one ProRes picture being encoded: its layout in the frame's planes and the coding
 * parameters every slice of it shares.
 *
 * @param chroma444     whether the frame is 4:4:4
 * @param alphaType     {@code 0} without alpha, {@code 1} for 8-bit alpha, {@code 2} for 16-bit
 *                      alpha
 * @param pictureHeight the picture height in luma rows
 * @param rowOffset     the frame row of the picture's first row
 * @param rowStep       the distance in frame rows between picture rows
 * @param scan          the raster position of every scan index
 * @param lumaMatrix    the luma quantization matrix, raster order
 * @param chromaMatrix  the chroma quantization matrix, raster order
 */
public record ProResPictureContext(boolean chroma444, int alphaType, int pictureHeight, int rowOffset, int rowStep,
                                   int[] scan, int[] lumaMatrix, int[] chromaMatrix) {
}
