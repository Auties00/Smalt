package com.github.auties00.smalt.image.jpeg.internal;

/**
 * Describes one scan: the components it codes, its spectral and successive-approximation
 * parameters, and the block layout of its MCUs.
 *
 * <p>A scan with one component is non-interleaved: every MCU is one block and the scan covers the
 * component's own block extent. A scan with several components is interleaved: every MCU holds
 * {@code h * v} blocks of each component, in scan order, and the scan covers the frame's MCU grid.
 */
final class JpegScan {
    /**
     * The components, in scan order.
     */
    final JpegComponent[] components;

    /**
     * The DC Huffman or arithmetic table selector of each scan component.
     */
    final int[] dcSelectors;

    /**
     * The AC Huffman or arithmetic table selector of each scan component.
     */
    final int[] acSelectors;

    /**
     * The start of the spectral selection, a zigzag index.
     */
    final int ss;

    /**
     * The end of the spectral selection, a zigzag index.
     */
    final int se;

    /**
     * The successive-approximation bit position of the previous scan of this band, or zero.
     */
    final int ah;

    /**
     * The successive-approximation bit position of this scan.
     */
    final int al;

    /**
     * The number of MCU columns.
     */
    final int mcusWide;

    /**
     * The number of MCU rows.
     */
    final int mcusHigh;

    /**
     * The number of blocks in one MCU.
     */
    final int blocksPerMcu;

    /**
     * The scan component index of each block of an MCU.
     */
    final int[] blockComponent;

    /**
     * The block column offset, inside the MCU, of each block of an MCU.
     */
    final int[] blockX;

    /**
     * The block row offset, inside the MCU, of each block of an MCU.
     */
    final int[] blockY;

    /**
     * Constructs a scan and derives its MCU layout; the frame layout must already be computed.
     *
     * @param frame the frame the scan belongs to
     * @param components the components, in scan order
     * @param dcSelectors the DC table selectors
     * @param acSelectors the AC table selectors
     * @param ss the spectral selection start
     * @param se the spectral selection end
     * @param ah the previous successive-approximation position
     * @param al the successive-approximation position
     */
    JpegScan(JpegFrame frame, JpegComponent[] components, int[] dcSelectors, int[] acSelectors,
             int ss, int se, int ah, int al) {
        this.components = components;
        this.dcSelectors = dcSelectors;
        this.acSelectors = acSelectors;
        this.ss = ss;
        this.se = se;
        this.ah = ah;
        this.al = al;
        if (components.length == 1) {
            var component = components[0];
            this.mcusWide = component.blocksWide;
            this.mcusHigh = component.blocksHigh;
            this.blocksPerMcu = 1;
            this.blockComponent = new int[]{0};
            this.blockX = new int[]{0};
            this.blockY = new int[]{0};
        } else {
            this.mcusWide = frame.mcusWide;
            this.mcusHigh = frame.mcusHigh;
            var blocks = 0;
            for (var component : components) {
                blocks += component.h * component.v;
            }
            this.blocksPerMcu = blocks;
            this.blockComponent = new int[blocks];
            this.blockX = new int[blocks];
            this.blockY = new int[blocks];
            var b = 0;
            for (var ci = 0; ci < components.length; ci++) {
                var component = components[ci];
                for (var y = 0; y < component.v; y++) {
                    for (var x = 0; x < component.h; x++) {
                        blockComponent[b] = ci;
                        blockX[b] = x;
                        blockY[b] = y;
                        b++;
                    }
                }
            }
        }
    }

    /**
     * Returns whether the scan interleaves several components.
     *
     * @return {@code true} when the scan has more than one component
     */
    boolean interleaved() {
        return components.length > 1;
    }

    /**
     * Returns the block column, in the component's block grid, of one block of an MCU.
     *
     * @param mcuX the MCU column
     * @param block the block index inside the MCU
     * @return the block column
     */
    int blockColumn(int mcuX, int block) {
        return components.length == 1 ? mcuX : mcuX * components[blockComponent[block]].h + blockX[block];
    }

    /**
     * Returns the block row, in the component's block grid, of one block of an MCU.
     *
     * @param mcuY the MCU row
     * @param block the block index inside the MCU
     * @return the block row
     */
    int blockRow(int mcuY, int block) {
        return components.length == 1 ? mcuY : mcuY * components[blockComponent[block]].v + blockY[block];
    }
}
