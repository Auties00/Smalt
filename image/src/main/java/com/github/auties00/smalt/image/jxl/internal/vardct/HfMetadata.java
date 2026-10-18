/* Ports jxl-oxide 0.12.6 (crates/jxl-vardct: HfMetadata). Copyright Wonwoo Choi and the jxl-oxide contributors;
   MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.vardct;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;
import com.github.auties00.smalt.image.jxl.internal.header.FrameHeader;
import com.github.auties00.smalt.image.jxl.internal.modular.MaTree;
import com.github.auties00.smalt.image.jxl.internal.modular.ModularChannel;
import com.github.auties00.smalt.image.jxl.internal.modular.ModularImage;

import java.util.ArrayList;

/**
 * Holds the varblock metadata of one LF group (ISO/IEC 18181-1 section I.2.6,
 * {@code HfMetadata}): the chroma-from-luma factors of each 64x64 tile, the transform type and
 * quantization multiplier of each varblock, and the edge-preserving filter sigma of each block.
 *
 * <p>The block grid covers the LF group in 8x8 blocks. A block that starts a varblock holds its
 * transform type plus one in bits 0-7 and its multiplier in bits 8-31; a block covered by a
 * varblock started elsewhere holds -1; no block is left empty.
 *
 * @param xFromY the X from Y factors, one per 64x64 tile
 * @param bFromY the B from Y factors, one per 64x64 tile
 * @param cflWidth the number of tile columns
 * @param blocks the block grid
 * @param blockWidth the number of block columns
 * @param blockHeight the number of block rows
 * @param epfSigma the edge-preserving filter sigma of each block, or {@code null} when the
 * filter is off
 */
public record HfMetadata(int[] xFromY, int[] bFromY, int cflWidth, int[] blocks, int blockWidth, int blockHeight,
                         float[] epfSigma) {
    /**
     * Marks a block covered by a varblock that starts elsewhere.
     */
    public static final int OCCUPIED = -1;

    /**
     * Reads the metadata of an LF group.
     *
     * @param br the bitstream
     * @param frame the frame header
     * @param lfGroup the LF group index
     * @param lfWidth the LF group width in pixels
     * @param lfHeight the LF group height in pixels
     * @param globalScale the global quantization scale
     * @param bitDepth the modular bit depth
     * @param globalTree the global MA tree, or {@code null}
     * @param limits the resource bounds
     * @return the metadata
     * @throws MediaException.Malformed if the varblocks do not tile the LF group
     */
    public static HfMetadata read(JxlBitReader br, FrameHeader frame, int lfGroup, int lfWidth, int lfHeight,
                                  int globalScale, int bitDepth, MaTree globalTree, MediaLimits limits) {
        var bw = (lfWidth + 7) / 8;
        var bh = (lfHeight + 7) / 8;
        var hUp = false;
        var vUp = false;
        for (var mode : frame.jpegUpsampling) {
            hUp |= mode == 1 || mode == 2;
            vUp |= mode == 1 || mode == 3;
        }
        if (hUp) {
            bw = (bw + 1) / 2 * 2;
        }
        if (vUp) {
            bh = (bh + 1) / 2 * 2;
        }
        var nbBits = 32 - Integer.numberOfLeadingZeros(bw * bh - 1);
        if (bw * bh == 1) {
            nbBits = 0;
        }
        var nbBlocks = 1 + br.readBits(nbBits);
        var cflWidth = (lfWidth + 63) / 64;
        var cflHeight = (lfHeight + 63) / 64;
        var channels = new ArrayList<ModularChannel>(4);
        channels.add(new ModularChannel(cflWidth, cflHeight, 0, 0));
        channels.add(new ModularChannel(cflWidth, cflHeight, 0, 0));
        channels.add(new ModularChannel(nbBlocks, 2, 0, 0));
        channels.add(new ModularChannel(bw, bh, 0, 0));
        var image = ModularImage.read(br, channels, globalTree, bitDepth, limits, true);
        image.decode(br, 1 + 2 * frame.numLfGroups() + lfGroup, image.channels().size());
        var decoded = image.finish();
        var xFromY = decoded.get(0).data;
        var bFromY = decoded.get(1).data;
        var raw = decoded.get(2).data;
        var rawWidth = decoded.get(2).width;
        var sharpness = decoded.get(3).data;
        var blocks = new int[bw * bh];
        float[] sigma = null;
        var epf = frame.epf;
        var quantMul = 0.0f;
        if (epf != null) {
            sigma = new float[bw * bh];
            quantMul = epf.quantMul() * 65536.0f / globalScale;
        }
        var dataIndex = 0;
        for (var y = 0; y < bh; y++) {
            var x = 0;
            while (x < bw) {
                if (blocks[y * bw + x] != 0) {
                    x++;
                    continue;
                }
                if (dataIndex >= rawWidth) {
                    throw new MediaException.Malformed("varblocks do not fill the LF group");
                }
                var type = raw[dataIndex];
                if (type < 0 || type >= TransformType.COUNT) {
                    throw new MediaException.Malformed("invalid transform type " + type);
                }
                var hfMul = raw[rawWidth + dataIndex] + 1;
                if (hfMul <= 0 || hfMul > 1 << 23) {
                    throw new MediaException.Malformed("invalid HF multiplier " + hfMul);
                }
                var dw = TransformType.width(type);
                var dh = TransformType.height(type);
                if ((x % 32) + dw > 32 || (y % 32) + dh > 32) {
                    throw new MediaException.Malformed("varblock across a group border");
                }
                if (x + dw > bw || y + dh > bh) {
                    throw new MediaException.Malformed("varblock outside its LF group");
                }
                var blockSigma = epf == null ? 0.0f : quantMul / hfMul;
                for (var dy = 0; dy < dh; dy++) {
                    for (var dx = 0; dx < dw; dx++) {
                        var index = (y + dy) * bw + x + dx;
                        if (blocks[index] != 0) {
                            throw new MediaException.Malformed("overlapping varblocks");
                        }
                        blocks[index] = dx == 0 && dy == 0 ? (type + 1) | (hfMul << 8) : OCCUPIED;
                        if (sigma != null) {
                            var sharp = sharpness[index];
                            if (sharp < 0 || sharp >= 8) {
                                throw new MediaException.Malformed("invalid EPF sharpness " + sharp);
                            }
                            sigma[index] = blockSigma * epf.sharpLut()[sharp];
                        }
                    }
                }
                dataIndex++;
                x += dw;
            }
        }
        return new HfMetadata(xFromY, bFromY, cflWidth, blocks, bw, bh, sigma);
    }

    /**
     * Returns whether a block starts a varblock.
     *
     * @param value the block grid entry
     * @return whether the entry holds a transform type
     */
    public static boolean isData(int value) {
        return value > 0;
    }

    /**
     * Returns the transform type of a varblock start.
     *
     * @param value the block grid entry
     * @return the transform type
     */
    public static int type(int value) {
        return (value & 0xFF) - 1;
    }

    /**
     * Returns the quantization multiplier of a varblock start.
     *
     * @param value the block grid entry
     * @return the multiplier
     */
    public static int hfMul(int value) {
        return value >>> 8;
    }
}
