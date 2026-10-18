/* Ports jxl-oxide 0.12.6 (crates/jxl-frame: the patch parser). Copyright Wonwoo Choi and the jxl-oxide contributors;
   MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.frame;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;
import com.github.auties00.smalt.image.jxl.internal.entropy.EntropyDecoder;
import com.github.auties00.smalt.image.jxl.internal.header.FrameHeader;
import com.github.auties00.smalt.image.jxl.internal.header.ImageHeader;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the patch dictionary of a frame (ISO/IEC 18181-1 section G.1.4): rectangles copied from
 * reference frames and blended at listed positions.
 *
 * @implNote This implementation bounds the patch counts as libjxl does.
 *
 * @param refs the patches
 */
public record Patches(List<PatchRef> refs) {
    /**
     * No blending; the patch is skipped for this channel.
     */
    public static final int NONE = 0;

    /**
     * Replaces the samples.
     */
    public static final int REPLACE = 1;

    /**
     * Adds the patch.
     */
    public static final int ADD = 2;

    /**
     * Multiplies by the patch.
     */
    public static final int MUL = 3;

    /**
     * Blends the patch above the samples.
     */
    public static final int BLEND_ABOVE = 4;

    /**
     * Blends the patch below the samples.
     */
    public static final int BLEND_BELOW = 5;

    /**
     * Adds the patch weighted by its alpha, above the samples.
     */
    public static final int MULADD_ABOVE = 6;

    /**
     * Adds the samples weighted by their alpha, below the patch.
     */
    public static final int MULADD_BELOW = 7;

    /**
     * Describes how one channel of a patch is blended.
     *
     * @param mode one of the blending constants of {@link Patches}
     * @param alphaChannel the extra channel holding the alpha
     * @param clamp whether alpha is clamped to {@code 0..1}
     */
    public record Blending(int mode, int alphaChannel, boolean clamp) {
    }

    /**
     * Describes one position a patch is placed at.
     *
     * @param x the left edge on the frame
     * @param y the top edge on the frame
     * @param blending the blending of the color channels, then of each extra channel
     */
    public record Target(int x, int y, Blending[] blending) {
    }

    /**
     * Describes one patch.
     *
     * @param refIndex the reference slot the patch is copied from
     * @param x0 the left edge in the reference
     * @param y0 the top edge in the reference
     * @param width the width of the copied rectangle in pixels
     * @param height the height of the copied rectangle in pixels
     * @param targets the positions the patch is placed at
     */
    public record PatchRef(int refIndex, int x0, int y0, int width, int height, List<Target> targets) {
    }

    /**
     * Reads the patch dictionary.
     *
     * @param br the bitstream
     * @param image the image header
     * @param frame the frame header
     * @return the patches
     * @throws MediaException.Malformed if the dictionary is invalid or too large
     */
    public static Patches read(JxlBitReader br, ImageHeader image, FrameHeader frame) {
        var numExtra = image.extraChannels().size();
        var alphaChannels = new ArrayList<Integer>();
        for (var i = 0; i < numExtra; i++) {
            if (image.extraChannels().get(i).isAlpha()) {
                alphaChannels.add(i);
            }
        }
        var decoder = EntropyDecoder.read(br, 10);
        decoder.begin(br);
        var maxRefs = Math.min(1L << 24, (long) frame.width * frame.height / 16);
        var maxPatches = maxRefs * 4;
        var numRefs = decoder.readUint(br, 0) & 0xFFFF_FFFFL;
        if (numRefs > maxRefs) {
            throw new MediaException.Malformed("too many patches");
        }
        var refs = new ArrayList<PatchRef>((int) numRefs);
        var total = 0L;
        for (var r = 0; r < numRefs; r++) {
            var refIndex = decoder.readUint(br, 1);
            if (refIndex < 0 || refIndex >= 4) {
                throw new MediaException.Malformed("patch reference out of range");
            }
            var x0 = decoder.readUint(br, 3);
            var y0 = decoder.readUint(br, 3);
            var width = decoder.readUint(br, 2) + 1;
            var height = decoder.readUint(br, 2) + 1;
            var count = (decoder.readUint(br, 7) & 0xFFFF_FFFFL) + 1;
            total += count;
            if (total > maxPatches || x0 < 0 || y0 < 0 || width <= 0 || height <= 0) {
                throw new MediaException.Malformed("too many or invalid patches");
            }
            var targets = new ArrayList<Target>((int) count);
            var px = 0L;
            var py = 0L;
            for (var t = 0; t < count; t++) {
                long x;
                long y;
                if (t == 0) {
                    x = decoder.readUint(br, 4) & 0xFFFF_FFFFL;
                    y = decoder.readUint(br, 4) & 0xFFFF_FFFFL;
                } else {
                    x = px + unpackSigned(decoder.readUint(br, 6));
                    y = py + unpackSigned(decoder.readUint(br, 6));
                }
                if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || y < Integer.MIN_VALUE || y > Integer.MAX_VALUE) {
                    throw new MediaException.Malformed("patch coordinate overflow");
                }
                px = x;
                py = y;
                var blending = new Blending[numExtra + 1];
                for (var c = 0; c <= numExtra; c++) {
                    var mode = decoder.readUint(br, 5);
                    if (mode < 0 || mode > MULADD_BELOW) {
                        throw new MediaException.Malformed("invalid patch blend mode " + mode);
                    }
                    int alpha;
                    if (mode >= BLEND_ABOVE && alphaChannels.size() >= 2) {
                        alpha = decoder.readUint(br, 8);
                    } else {
                        alpha = alphaChannels.isEmpty() ? 0 : alphaChannels.getFirst();
                    }
                    var clamp = mode >= MUL && decoder.readUint(br, 9) != 0;
                    if (mode >= BLEND_ABOVE && (alpha < 0 || alpha >= numExtra)) {
                        throw new MediaException.Malformed("patch alpha channel out of range");
                    }
                    blending[c] = new Blending(mode, alpha, clamp);
                }
                targets.add(new Target((int) x, (int) y, blending));
            }
            refs.add(new PatchRef(refIndex, x0, y0, width, height, targets));
        }
        decoder.checkFinalState();
        return new Patches(refs);
    }

    /**
     * Maps an unsigned value to a signed one ({@code UnpackSigned}).
     *
     * @param value the unsigned value
     * @return the signed value
     */
    private static int unpackSigned(int value) {
        return (value >>> 1) ^ -(value & 1);
    }
}
