/* Ports jxl-oxide 0.12.6 (crates/jxl-frame). Copyright Wonwoo Choi and the jxl-oxide contributors; MIT License.
   Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.frame;

import com.github.auties00.smalt.image.jxl.internal.header.FrameHeader;
import com.github.auties00.smalt.image.jxl.internal.modular.ModularChannel;
import com.github.auties00.smalt.image.jxl.internal.vardct.VarDctFrame;

import java.util.List;

/**
 * Holds the decoded data of a frame before rendering: the VarDCT planes (when the frame is
 * VarDCT) and the modular channels in their original order.
 *
 * @param header the frame header
 * @param lfGlobal the LF global section
 * @param varDct the VarDCT data, or {@code null} for a modular frame
 * @param modular the modular channels: the stored color channels of a modular frame, then the
 * extra channels
 * @param passes the number of leading passes decoded, at most the frame's pass count; the coefficients and
 * squeeze residuals of the later passes are zero
 */
public record DecodedFrame(FrameHeader header, LfGlobal lfGlobal, VarDctFrame varDct, List<ModularChannel> modular,
                           int passes) {
}
