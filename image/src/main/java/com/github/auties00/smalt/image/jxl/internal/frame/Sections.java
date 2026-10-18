/* Ports jxl-oxide 0.12.6 (crates/jxl-frame: the section readers). Copyright Wonwoo Choi and the jxl-oxide
   contributors; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.frame;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;
import com.github.auties00.smalt.image.jxl.internal.header.FrameHeader;
import com.github.auties00.smalt.image.jxl.internal.header.Toc;

/**
 * Gives access to the sections of a frame's data through its table of contents.
 *
 * <p>A frame with a single section stores everything in it, read in order from one shared
 * reader; otherwise every section gets its own reader over its byte range, so sections can be
 * decoded concurrently.
 */
public final class Sections {
    /**
     * The array holding the codestream.
     */
    private final byte[] data;

    /**
     * The index of the first codestream byte in {@link #data}.
     */
    private final int codestreamOffset;

    /**
     * The number of codestream bytes.
     */
    private final int codestreamLength;

    /**
     * The table of contents.
     */
    private final Toc toc;

    /**
     * The number of LF groups.
     */
    private final int numLfGroups;

    /**
     * The number of groups.
     */
    private final int numGroups;

    /**
     * The shared reader of a single-section frame, or {@code null}.
     */
    private final JxlBitReader single;

    /**
     * Creates the accessor.
     *
     * @param data the array holding the codestream
     * @param codestreamOffset the index of the first codestream byte
     * @param codestreamLength the number of codestream bytes
     * @param toc the table of contents
     * @param frame the frame header
     * @throws MediaException.Malformed if a section lies outside the codestream
     */
    public Sections(byte[] data, int codestreamOffset, int codestreamLength, Toc toc, FrameHeader frame) {
        this.data = data;
        this.codestreamOffset = codestreamOffset;
        this.codestreamLength = codestreamLength;
        this.toc = toc;
        this.numLfGroups = frame.numLfGroups();
        this.numGroups = frame.numGroups();
        if (toc.end() > codestreamLength) {
            throw new MediaException.Malformed("truncated JPEG XL frame");
        }
        this.single = toc.single() ? reader(0) : null;
    }

    /**
     * Creates a reader over one section.
     *
     * @param index the section index in logical order
     * @return the reader
     */
    private JxlBitReader reader(int index) {
        var offset = toc.offsets()[index];
        var size = toc.sizes()[index];
        if (offset < 0 || offset + size > codestreamLength) {
            throw new MediaException.Malformed("truncated JPEG XL frame section");
        }
        return new JxlBitReader(data, codestreamOffset + (int) offset, size);
    }

    /**
     * Returns the reader of the LF global section.
     *
     * @return the reader
     */
    public JxlBitReader lfGlobal() {
        return single != null ? single : reader(0);
    }

    /**
     * Returns the reader of an LF group section.
     *
     * @param lfGroup the LF group index
     * @return the reader
     */
    public JxlBitReader lfGroup(int lfGroup) {
        return single != null ? single : reader(1 + lfGroup);
    }

    /**
     * Returns the reader of the HF global section.
     *
     * @return the reader
     */
    public JxlBitReader hfGlobal() {
        return single != null ? single : reader(1 + numLfGroups);
    }

    /**
     * Returns the reader of a pass group section.
     *
     * @param pass the pass index
     * @param group the group index
     * @return the reader
     */
    public JxlBitReader passGroup(int pass, int group) {
        return single != null ? single : reader(2 + numLfGroups + pass * numGroups + group);
    }

    /**
     * Returns whether the frame has a single section, which must be decoded in order.
     *
     * @return whether sections share one reader
     */
    public boolean isSingle() {
        return single != null;
    }
}
