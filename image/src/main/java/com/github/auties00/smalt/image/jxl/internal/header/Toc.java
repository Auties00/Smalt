/* Ports jxl-oxide 0.12.6 (crates/jxl-frame: Toc). Copyright Wonwoo Choi and the jxl-oxide contributors; MIT License.
   Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.header;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;
import com.github.auties00.smalt.image.jxl.internal.entropy.EntropyDecoder;
import com.github.auties00.smalt.image.jxl.internal.entropy.Permutations;

/**
 * Holds the table of contents of a frame (ISO/IEC 18181-1 section C.3): where each section of the
 * frame data starts and how long it is.
 *
 * <p>A frame of one group and one pass has a single section holding everything; otherwise the
 * sections are, in their logical order, the LF global section, one section per LF group, the HF
 * global section, and one section per pass and group. The sections may be stored in a permuted
 * order, which the table resolves.
 *
 * @param offsets the byte offset of each section, in logical order, from the start of the
 * codestream
 * @param sizes the byte length of each section, in logical order
 * @param end the byte offset one past the frame data
 */
public record Toc(long[] offsets, int[] sizes, long end) {
    /**
     * Reads the table of contents.
     *
     * @param br the bitstream, positioned after the frame header
     * @param header the frame header
     * @param frameOffset the byte offset of the bitstream's range within the codestream
     * @return the table
     * @throws MediaException.Malformed if the table is invalid
     */
    public static Toc read(JxlBitReader br, FrameHeader header, long frameOffset) {
        var numGroups = header.numGroups();
        var numPasses = header.numPasses;
        long count = numGroups == 1 && numPasses == 1 ? 1 : 1L + header.numLfGroups() + 1 + (long) numGroups * numPasses;
        if (count > 65536) {
            throw new MediaException.Malformed("too many JPEG XL TOC entries: " + count);
        }
        var entries = (int) count;
        int[] permutation = null;
        if (br.readBool()) {
            var decoder = EntropyDecoder.read(br, 8);
            decoder.begin(br);
            permutation = Permutations.read(br, decoder, entries, 0);
            decoder.checkFinalState();
        }
        br.zeroPadToByte();
        var raw = new int[entries];
        for (var i = 0; i < entries; i++) {
            raw[i] = br.readU32(0, 10, 1024, 14, 17408, 22, 4211712, 30);
        }
        br.zeroPadToByte();
        var base = frameOffset + br.bitPosition() / 8;
        var rawOffsets = new long[entries];
        var acc = base;
        for (var i = 0; i < entries; i++) {
            rawOffsets[i] = acc;
            acc += raw[i] & 0xFFFF_FFFFL;
        }
        var offsets = new long[entries];
        var sizes = new int[entries];
        for (var i = 0; i < entries; i++) {
            var from = permutation == null ? i : permutation[i];
            offsets[i] = rawOffsets[from];
            sizes[i] = raw[from];
            if (sizes[i] < 0) {
                throw new MediaException.Malformed("JPEG XL TOC entry too large");
            }
        }
        return new Toc(offsets, sizes, acc);
    }

    /**
     * Returns whether the frame has a single section.
     *
     * @return whether everything is in section 0
     */
    public boolean single() {
        return sizes.length == 1;
    }
}
