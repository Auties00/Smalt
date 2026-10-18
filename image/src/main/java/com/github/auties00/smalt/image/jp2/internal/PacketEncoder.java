package com.github.auties00.smalt.image.jp2.internal;

import java.io.ByteArrayOutputStream;

/**
 * Writes the packets of a tile (ISO/IEC 15444-1 B.9 and B.10): each packet's header (inclusion,
 * zero bit-planes, pass counts and codeword lengths) followed by the codewords its code-blocks
 * contribute.
 *
 * @implNote This implementation follows OpenJPEG's {@code opj_t2_encode_packet}: every packet
 * has a header even when it is empty, and code-blocks of empty sub-bands are skipped, so the
 * packets are byte-identical to OpenJPEG's.
 */
final class PacketEncoder {
    /**
     * The header bits.
     */
    private final PacketBitWriter bits = new PacketBitWriter();

    /**
     * Whether packets start with an {@code SOP} marker segment.
     */
    private final boolean sop;

    /**
     * Whether packet headers end with an {@code EPH} marker.
     */
    private final boolean eph;

    /**
     * The index of the next packet, for the {@code SOP} segments.
     */
    private int packet;

    /**
     * Constructs a packet writer.
     *
     * @param sop whether to write {@code SOP} marker segments
     * @param eph whether to write {@code EPH} markers
     */
    PacketEncoder(boolean sop, boolean eph) {
        this.sop = sop;
        this.eph = eph;
    }

    /**
     * Writes one packet.
     *
     * @param component the tile-component
     * @param resolution the resolution level
     * @param precinct the precinct index
     * @param layer the quality layer, from 0
     * @param out the stream receiving the packet, or {@code null} to only measure it
     * @return the packet length in bytes
     */
    int write(TileComponent component, int resolution, int precinct, int layer, ByteArrayOutputStream out) {
        var level = component.resolutions[resolution];
        var length = 0;
        if (sop) {
            if (out != null) {
                out.write(0xFF);
                out.write(0x91);
                out.write(0);
                out.write(4);
                out.write((packet >> 8) & 0xFF);
                out.write(packet & 0xFF);
            }
            length += 6;
        }
        packet++;
        if (layer == 0) {
            for (var band : level.bands()) {
                if (band.empty()) {
                    continue;
                }
                var area = band.precincts()[precinct];
                if (area.inclusion != null) {
                    area.inclusion.reset();
                    area.zeroPlanes.reset();
                }
                for (var index = 0; index < area.encodedBlocks.length; index++) {
                    var block = area.encodedBlocks[index];
                    block.writtenPasses = 0;
                    area.zeroPlanes.setValue(index, band.bitPlanes() - block.bitPlanes);
                }
            }
        }
        bits.reset();
        bits.bit(1);
        for (var band : level.bands()) {
            if (band.empty()) {
                continue;
            }
            var area = band.precincts()[precinct];
            var blocks = area.encodedBlocks;
            for (var index = 0; index < blocks.length; index++) {
                var block = blocks[index];
                if (block.writtenPasses == 0 && block.layerPasses[layer] != 0) {
                    area.inclusion.setValue(index, layer);
                }
            }
            for (var index = 0; index < blocks.length; index++) {
                var block = blocks[index];
                var passes = block.layerPasses[layer];
                if (block.writtenPasses == 0) {
                    area.inclusion.encode(bits, index, layer + 1);
                } else {
                    bits.bit(passes != 0 ? 1 : 0);
                }
                if (passes == 0) {
                    continue;
                }
                if (block.writtenPasses == 0) {
                    block.lengthBits = 3;
                    area.zeroPlanes.encode(bits, index, 999);
                }
                passCount(passes);
                var end = block.writtenPasses + passes;
                var increment = 0;
                var segmentPasses = 0;
                var segmentLength = 0;
                for (var pass = block.writtenPasses; pass < end; pass++) {
                    segmentPasses++;
                    segmentLength += block.passLength(pass);
                    if (block.passTerminated[pass] || pass == end - 1) {
                        increment = Math.max(increment, floorLog2(segmentLength) + 1
                                - (block.lengthBits + floorLog2(segmentPasses)));
                        segmentLength = 0;
                        segmentPasses = 0;
                    }
                }
                for (var i = 0; i < increment; i++) {
                    bits.bit(1);
                }
                bits.bit(0);
                block.lengthBits += increment;
                for (var pass = block.writtenPasses; pass < end; pass++) {
                    segmentPasses++;
                    segmentLength += block.passLength(pass);
                    if (block.passTerminated[pass] || pass == end - 1) {
                        bits.write(segmentLength, block.lengthBits + floorLog2(segmentPasses));
                        segmentLength = 0;
                        segmentPasses = 0;
                    }
                }
            }
        }
        bits.flush();
        length += bits.length();
        if (out != null) {
            out.write(bits.data(), 0, bits.length());
        }
        if (eph) {
            if (out != null) {
                out.write(0xFF);
                out.write(0x92);
            }
            length += 2;
        }
        for (var band : level.bands()) {
            if (band.empty()) {
                continue;
            }
            for (var block : band.precincts()[precinct].encodedBlocks) {
                var passes = block.layerPasses[layer];
                if (passes == 0) {
                    continue;
                }
                if (out != null) {
                    out.write(block.data, block.layerOffset[layer], block.layerLength[layer]);
                }
                length += block.layerLength[layer];
                block.writtenPasses += passes;
            }
        }
        return length;
    }

    /**
     * Writes a number of coding passes (Table B.4).
     *
     * @param count the number of passes, from 1 to 164
     */
    private void passCount(int count) {
        if (count == 1) {
            bits.bit(0);
        } else if (count == 2) {
            bits.write(2, 2);
        } else if (count <= 5) {
            bits.write(0xC | (count - 3), 4);
        } else if (count <= 36) {
            bits.write(0x1E0 | (count - 6), 9);
        } else {
            bits.write(0xFF80 | (count - 37), 16);
        }
    }

    /**
     * Returns the base-2 logarithm of a value, rounded down, as OpenJPEG's
     * {@code opj_int_floorlog2} does.
     *
     * @param value the number
     * @return the logarithm, 0 for values up to 1
     */
    private static int floorLog2(int value) {
        return value <= 1 ? 0 : 31 - Integer.numberOfLeadingZeros(value);
    }
}
