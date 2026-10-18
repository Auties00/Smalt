/* Ports jxl-oxide 0.12.6 (crates/jxl-frame and crates/jxl-render: frame data decoding). Copyright Wonwoo Choi and the
   jxl-oxide contributors; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.frame;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.jxl.internal.header.FrameHeader;
import com.github.auties00.smalt.image.jxl.internal.header.ImageHeader;
import com.github.auties00.smalt.image.jxl.internal.modular.ModularChannel;
import com.github.auties00.smalt.image.jxl.internal.vardct.VarDctFrame;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.List;

/**
 * Decodes the data sections of one frame into sample planes (ISO/IEC 18181-1 annex G): the LF
 * global section, the LF groups, the HF global section and the pass groups, with the VarDCT and
 * modular parts of each.
 *
 * <p>LF groups, and the groups of each pass, decode concurrently on the shared helper threads
 * when the frame has more than one section; the result does not depend on the scheduling.
 */
public final class FrameDecoder {
    /**
     * Prevents instantiation.
     */
    private FrameDecoder() {
        throw new AssertionError();
    }

    /**
     * Decodes a frame.
     *
     * @param sections the frame's sections
     * @param image the image header
     * @param frame the frame header
     * @param lfFrame the X, Y and B planes of the LF frame this frame uses, or {@code null}
     * @param lfWidth the width of the LF frame planes
     * @param lfHeight the height of the LF frame planes
     * @param limits the resource bounds
     * @return the decoded planes
     * @throws MediaException.Malformed if the frame is invalid
     * @throws MediaException.LimitExceeded if it exceeds the limits
     */
    public static DecodedFrame decode(Sections sections, ImageHeader image, FrameHeader frame, float[][] lfFrame,
                                      int lfWidth, int lfHeight, MediaLimits limits) {
        return decode(sections, image, frame, lfFrame, lfWidth, lfHeight, limits, false);
    }

    /**
     * Decodes a frame, optionally stopping a VarDCT frame at its quantized coefficients.
     *
     * <p>With {@code coefficientsOnly}, a VarDCT frame is entropy decoded but neither dequantized
     * nor transformed: its {@link VarDctFrame} keeps the quantized LF and HF coefficients of every
     * group, which JPEG reconstruction reads.
     *
     * @param sections the frame's sections
     * @param image the image header
     * @param frame the frame header
     * @param lfFrame the X, Y and B planes of the LF frame this frame uses, or {@code null}
     * @param lfWidth the width of the LF frame planes
     * @param lfHeight the height of the LF frame planes
     * @param limits the resource bounds
     * @param coefficientsOnly whether to stop before the dequantization
     * @return the decoded frame
     * @throws MediaException.Malformed if the frame is invalid
     * @throws MediaException.LimitExceeded if it exceeds the limits
     */
    public static DecodedFrame decode(Sections sections, ImageHeader image, FrameHeader frame, float[][] lfFrame,
                                      int lfWidth, int lfHeight, MediaLimits limits, boolean coefficientsOnly) {
        var lfGlobal = LfGlobal.read(sections.lfGlobal(), image, frame, limits);
        var tree = lfGlobal.globalTree();
        var bitDepth = image.bitDepth().bitsPerSample();
        var groups = lfGlobal.modular() == null ? null
                : new ModularGroups(lfGlobal.modular(), lfGlobal.modularGlobalEnd(), frame, tree, bitDepth, limits);
        VarDctFrame varDct = null;
        var useLfFrame = frame.hasFlag(FrameHeader.FLAG_USE_LF_FRAME);
        if (frame.encoding == FrameHeader.VARDCT) {
            varDct = new VarDctFrame(image, frame, lfGlobal.varDct(), lfGlobal.lfDequant(), limits);
            if (useLfFrame) {
                if (lfFrame == null) {
                    throw new MediaException.Malformed("frame uses a missing LF frame");
                }
                varDct.useLfFrame(lfFrame[0], lfFrame[1], lfFrame[2], lfWidth, lfHeight);
            }
        }
        var vd = varDct;
        var single = sections.isSingle();
        var numLf = frame.numLfGroups();
        run(single, numLf, index -> {
            var br = sections.lfGroup(index);
            if (vd != null && !useLfFrame) {
                vd.readLfCoefficients(br, index, tree);
            }
            if (groups != null) {
                groups.decodeLfGroup(br, index);
            }
            if (vd != null) {
                vd.readHfMetadata(br, index, tree);
            }
        });
        if (vd != null) {
            if (!useLfFrame) {
                vd.finishLf();
            }
            vd.readHfGlobal(sections.hfGlobal(), tree);
        }
        var numGroups = frame.numGroups();
        for (var p = 0; p < frame.numPasses; p++) {
            var pass = p;
            run(single, numGroups, index -> {
                var br = sections.passGroup(pass, index);
                if (vd != null) {
                    vd.readPassGroup(br, pass, index);
                }
                if (groups != null) {
                    groups.decodePassGroup(br, pass, index);
                }
            });
        }
        if (vd != null && !coefficientsOnly) {
            run(single, numGroups, vd::reconstruct);
        }
        List<ModularChannel> modular = lfGlobal.finishModular();
        return new DecodedFrame(frame, lfGlobal, varDct, modular);
    }

    /**
     * Runs a task for every index, concurrently unless the frame has a single section.
     *
     * @param single whether the frame has a single section
     * @param count the number of indices
     * @param task the work for one index
     */
    private static void run(boolean single, int count, ParallelBands.Band task) {
        if (single || count <= 1) {
            for (var i = 0; i < count; i++) {
                task.run(i);
            }
            return;
        }
        ParallelBands.run(count, task);
    }
}
