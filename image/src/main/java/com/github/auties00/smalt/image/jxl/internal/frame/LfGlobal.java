/* Ports jxl-oxide 0.12.6 (crates/jxl-frame: LfGlobal). Copyright Wonwoo Choi and the jxl-oxide contributors;
   MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.frame;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;
import com.github.auties00.smalt.image.jxl.internal.header.FrameHeader;
import com.github.auties00.smalt.image.jxl.internal.header.ImageHeader;
import com.github.auties00.smalt.image.jxl.internal.modular.MaTree;
import com.github.auties00.smalt.image.jxl.internal.modular.ModularChannel;
import com.github.auties00.smalt.image.jxl.internal.modular.ModularImage;
import com.github.auties00.smalt.image.jxl.internal.vardct.LfGlobalVarDct;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the LF global section of a frame (ISO/IEC 18181-1 section G.1): the patches, splines and
 * noise parameters, the LF dequantization weights, the VarDCT globals, the global MA tree and the
 * global modular image.
 *
 * @param patches the patch dictionary, or {@code null}
 * @param splines the splines, or {@code null}
 * @param noise the eight noise strengths, or {@code null}
 * @param lfDequant the LF dequantization weights of the X, Y and B channels
 * @param varDct the VarDCT globals, or {@code null} for a modular frame
 * @param globalTree the global MA tree, or {@code null}
 * @param modular the global modular image, or {@code null} when the frame has no modular
 * channel
 * @param modularColor the number of color channels stored in the modular image
 * @param modularGlobalEnd the number of leading modular channels decoded in this section
 */
public record LfGlobal(Patches patches, Splines splines, float[] noise, float[] lfDequant, LfGlobalVarDct varDct,
                       MaTree globalTree, ModularImage modular, int modularColor, int modularGlobalEnd) {
    /**
     * Reads the section.
     *
     * @param br the bitstream
     * @param image the image header
     * @param frame the frame header
     * @param limits the resource bounds
     * @return the section
     * @throws MediaException.Malformed if the section is invalid
     * @throws MediaException.LimitExceeded if it exceeds the limits
     */
    public static LfGlobal read(JxlBitReader br, ImageHeader image, FrameHeader frame, MediaLimits limits) {
        Patches patches = null;
        if (frame.hasFlag(FrameHeader.FLAG_PATCHES)) {
            patches = Patches.read(br, image, frame);
        }
        Splines splines = null;
        if (frame.hasFlag(FrameHeader.FLAG_SPLINES)) {
            splines = Splines.read(br, frame);
        }
        float[] noise = null;
        if (frame.hasFlag(FrameHeader.FLAG_NOISE)) {
            noise = new float[8];
            for (var i = 0; i < 8; i++) {
                noise[i] = br.readBits(10) / 1024.0f;
            }
        }
        var dequant = new float[]{1.0f / 32.0f, 1.0f / 4.0f, 1.0f / 2.0f};
        if (!br.readBool()) {
            dequant[0] = br.readF16();
            dequant[1] = br.readF16();
            dequant[2] = br.readF16();
        }
        for (var value : dequant) {
            if (!(value / 128.0f >= 1e-8f)) {
                throw new MediaException.Malformed("LF dequantization weight too small");
            }
        }
        LfGlobalVarDct varDct = null;
        if (frame.encoding == FrameHeader.VARDCT) {
            varDct = LfGlobalVarDct.read(br);
        }
        if (splines != null) {
            var corrX = varDct == null ? 0.0f : varDct.baseCorrelationX();
            var corrB = varDct == null ? 1.0f : varDct.baseCorrelationB();
            var area = splines.estimateArea(corrX, corrB);
            var imageSize = (long) frame.width * frame.height;
            if (area > Math.min(1L << 42, 1024 * imageSize + (1L << 32))) {
                throw new MediaException.Malformed("splines cover too large an area");
            }
        }
        var colorChannels = frame.encodedColorChannels;
        var numEc = image.extraChannels().size();
        var maxNodes = Math.min(1L << 22, 1024 + (long) frame.width * frame.height * (colorChannels + numEc) / 16);
        MaTree globalTree = null;
        if (br.readBool()) {
            globalTree = MaTree.read(br, maxNodes);
        }
        var channels = new ArrayList<ModularChannel>();
        var colorWidth = frame.colorWidth();
        var colorHeight = frame.colorHeight();
        var modularColor = 0;
        if (frame.encoding == FrameHeader.MODULAR) {
            if (frame.doYCbCr) {
                for (var c = 0; c < 3; c++) {
                    channels.add(jpegChannel(colorWidth, colorHeight, frame.jpegUpsampling, c));
                }
                modularColor = 3;
            } else {
                for (var c = 0; c < colorChannels; c++) {
                    channels.add(new ModularChannel(colorWidth, colorHeight, 0, 0));
                }
                modularColor = colorChannels;
            }
        }
        var colorShift = Integer.numberOfTrailingZeros(frame.upsampling);
        for (var i = 0; i < numEc; i++) {
            var shift = Integer.numberOfTrailingZeros(frame.ecUpsampling[i]) + image.extraChannels().get(i).dimShift()
                    - colorShift;
            var w = (int) (((long) colorWidth + (1L << shift) - 1) >> shift);
            var h = (int) (((long) colorHeight + (1L << shift) - 1) >> shift);
            channels.add(new ModularChannel(w, h, shift, shift));
        }
        ModularImage modular = null;
        var globalEnd = 0;
        if (!channels.isEmpty()) {
            modular = ModularImage.read(br, channels, globalTree, image.bitDepth().bitsPerSample(), limits, true);
            globalEnd = modular.globalPrefix(frame.groupDim());
            modular.decode(br, 0, globalEnd);
        }
        return new LfGlobal(patches, splines, noise, dequant, varDct, globalTree, modular, modularColor, globalEnd);
    }

    /**
     * Describes a YCbCr channel of a modular frame, subsampled as the JPEG upsampling modes say.
     *
     * @param width the frame color width
     * @param height the frame color height
     * @param jpegUpsampling the upsampling modes of the Cb, Y and Cr channels
     * @param index the channel index, 0 for Cb, 1 for Y, 2 for Cr
     * @return the channel geometry
     */
    public static ModularChannel jpegChannel(int width, int height, int[] jpegUpsampling, int index) {
        var shifts = jpegShifts(jpegUpsampling, index);
        var hscale = shifts[2] != 0;
        var vscale = shifts[3] != 0;
        var w = width;
        var h = height;
        if (hscale) {
            var half = (w + 1) / 2;
            w = shifts[0] != 0 ? half : half * 2;
        }
        if (vscale) {
            var half = (h + 1) / 2;
            h = shifts[1] != 0 ? half : half * 2;
        }
        return new ModularChannel(w, h, shifts[0], shifts[1]);
    }

    /**
     * Returns the subsampling of a YCbCr channel.
     *
     * @param jpegUpsampling the upsampling modes of the Cb, Y and Cr channels
     * @param index the channel index
     * @return {@code {hshift, vshift, anyHorizontal, anyVertical}}, each 0 or 1
     */
    public static int[] jpegShifts(int[] jpegUpsampling, int index) {
        var hscale = false;
        var vscale = false;
        for (var mode : jpegUpsampling) {
            hscale |= mode == 1 || mode == 2;
            vscale |= mode == 1 || mode == 3;
        }
        var mode = jpegUpsampling[index];
        boolean h;
        boolean v;
        switch (mode) {
            case 0 -> {
                h = hscale;
                v = vscale;
            }
            case 1 -> {
                h = false;
                v = false;
            }
            case 2 -> {
                h = false;
                v = vscale;
            }
            default -> {
                h = hscale;
                v = false;
            }
        }
        return new int[]{h ? 1 : 0, v ? 1 : 0, hscale ? 1 : 0, vscale ? 1 : 0};
    }

    /**
     * Returns the modular channels in their original order, once decoded and inverse transformed.
     *
     * @return the channels: the stored color channels, then the extra channels
     */
    public List<ModularChannel> finishModular() {
        return modular == null ? List.of() : modular.finish();
    }
}
