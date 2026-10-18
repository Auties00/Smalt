package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Reads the packets of a tile (ISO/IEC 15444-1 B.9 and B.10): each packet header's code-block
 * inclusion, zero bit-planes, pass counts and codeword lengths, and the location of the codeword
 * bytes in the packet body.
 *
 * <p>Packet headers are read from the tile body, or from the packed headers of {@code PPM} or
 * {@code PPT} segments. A body shorter than its header declares marks the code-blocks it fails
 * to cover as corrupted and ends the tile's data, so a truncated codestream decodes as far as its
 * data goes.
 *
 * @implNote This implementation follows OpenJPEG's {@code t2.c} decoder
 * ({@code opj_t2_read_packet_header}, {@code opj_t2_read_packet_data} and
 * {@code opj_t2_skip_packet_data}) in its non-strict mode.
 */
final class PacketDecoder {
    /**
     * The tile body.
     */
    private final byte[] body;

    /**
     * The packed packet headers, or {@code null} when headers are in the body.
     */
    private final byte[] headers;

    /**
     * Whether packets may start with {@code SOP} marker segments.
     */
    private final boolean sop;

    /**
     * Whether packet headers end with {@code EPH} markers.
     */
    private final boolean eph;

    /**
     * The header bit reader.
     */
    private final PacketBits bits = new PacketBits();

    /**
     * The position of the next packet in the body.
     */
    private int bodyPosition;

    /**
     * The position of the next packet header in the packed headers.
     */
    private int headerPosition;

    /**
     * Constructs a reader over a tile's packets.
     *
     * @param body the tile body
     * @param headers the packed packet headers, or {@code null}
     * @param sop whether {@code SOP} markers may be present
     * @param eph whether {@code EPH} markers are present
     */
    PacketDecoder(byte[] body, byte[] headers, boolean sop, boolean eph) {
        this.body = body;
        this.headers = headers;
        this.sop = sop;
        this.eph = eph;
    }

    /**
     * Returns whether every packet left would be empty because the header data is exhausted.
     *
     * @return whether no header byte remains
     */
    boolean exhausted() {
        return headers == null ? bodyPosition >= body.length : headerPosition >= headers.length;
    }

    /**
     * Reads one packet.
     *
     * @param component the tile-component
     * @param resolution the resolution level
     * @param precinct the precinct index
     * @param layer the quality layer, from 0
     * @param skip whether the packet's codewords are skipped rather than recorded
     * @throws MediaException.Malformed if the header is invalid or a required {@code EPH} marker is
     * missing
     */
    void read(TileComponent component, int resolution, int precinct, int layer, boolean skip) {
        var level = component.resolutions[resolution];
        var style = component.coding.codeBlockStyle();
        if (layer == 0) {
            for (var band : level.bands()) {
                if (band.empty()) {
                    continue;
                }
                if (precinct >= band.precincts().length) {
                    throw new MediaException.Malformed("invalid JPEG 2000 precinct " + precinct);
                }
                var area = band.precincts()[precinct];
                if (area.inclusion != null) {
                    area.inclusion.reset();
                    area.zeroPlanes.reset();
                }
                for (var block : area.codeBlocks) {
                    block.segments = 0;
                    block.dataSegments = 0;
                }
            }
        }
        if (sop && body.length - bodyPosition >= 6 && (body[bodyPosition] & 0xFF) == 0xFF
                && (body[bodyPosition + 1] & 0xFF) == 0x91) {
            bodyPosition += 6;
        }
        var source = headers != null ? headers : body;
        var start = headers != null ? headerPosition : bodyPosition;
        bits.init(source, start, source.length);
        var present = bits.bit() != 0;
        if (present) {
            readHeader(level, precinct, layer, style);
        }
        bits.align();
        var end = start + bits.consumed();
        if (eph) {
            if (source.length - end < 2 || (source[end] & 0xFF) != 0xFF || (source[end + 1] & 0xFF) != 0x92) {
                if (source.length - end <= 0 && !present) {
                    end = source.length;
                } else {
                    throw new MediaException.Malformed("missing JPEG 2000 EPH marker");
                }
            } else {
                end += 2;
            }
        }
        if (headers != null) {
            headerPosition = end;
        } else {
            bodyPosition = end;
        }
        if (!present) {
            return;
        }
        if (skip) {
            skipData(level, precinct);
        } else {
            readData(level, precinct);
        }
    }

    /**
     * Reads the code-block contributions of a non-empty packet header.
     *
     * @param level the resolution level
     * @param precinct the precinct index
     * @param layer the quality layer, from 0
     * @param style the code-block style flags
     */
    private void readHeader(Resolution level, int precinct, int layer, int style) {
        for (var band : level.bands()) {
            if (band.empty()) {
                continue;
            }
            var area = band.precincts()[precinct];
            var blocks = area.codeBlocks;
            for (var index = 0; index < blocks.length; index++) {
                var block = blocks[index];
                boolean included;
                if (block.segments == 0) {
                    included = area.inclusion.decode(bits, index, layer + 1);
                } else {
                    included = bits.bit() != 0;
                }
                if (!included) {
                    block.newPasses = 0;
                    continue;
                }
                if (block.segments == 0) {
                    var zero = 0;
                    while (!area.zeroPlanes.decode(bits, index, zero)) {
                        zero++;
                        if (zero > 1000) {
                            throw new MediaException.Malformed("invalid JPEG 2000 zero bit-plane count");
                        }
                    }
                    block.bitPlanes = band.bitPlanes() + 1 - zero;
                    block.lengthBits = 3;
                }
                block.newPasses = passCount();
                var increment = 0;
                while (bits.bit() != 0) {
                    increment++;
                    if (increment > 64) {
                        throw new MediaException.Malformed("invalid JPEG 2000 length indicator increment");
                    }
                }
                block.lengthBits += increment;
                var segment = 0;
                if (block.segments == 0) {
                    block.startSegment(0, style, true);
                } else {
                    segment = block.segments - 1;
                    if (block.segmentPasses[segment] == block.segmentMaxPasses[segment]) {
                        segment++;
                        block.startSegment(segment, style, false);
                    }
                }
                var remaining = block.newPasses;
                do {
                    var passes = Math.min(block.segmentMaxPasses[segment] - block.segmentPasses[segment], remaining);
                    block.segmentNewPasses[segment] = passes;
                    var bitCount = block.lengthBits + floorLog2(passes);
                    if (bitCount > 32) {
                        throw new MediaException.Malformed("invalid JPEG 2000 codeword length size " + bitCount);
                    }
                    block.segmentNewLengths[segment] = bits.read(bitCount);
                    remaining -= passes;
                    if (remaining > 0) {
                        segment++;
                        block.startSegment(segment, style, false);
                    }
                } while (remaining > 0);
            }
        }
    }

    /**
     * Records the codeword bytes of a packet's body.
     *
     * @param level the resolution level
     * @param precinct the precinct index
     */
    private void readData(Resolution level, int precinct) {
        var partial = false;
        for (var band : level.bands()) {
            if (band.empty()) {
                continue;
            }
            for (var block : band.precincts()[precinct].codeBlocks) {
                if (block.newPasses == 0) {
                    continue;
                }
                if (partial || block.corrupted) {
                    block.chunkEntries = 0;
                    block.corrupted = true;
                    continue;
                }
                var segment = currentSegment(block);
                do {
                    var length = block.segmentNewLengths[segment];
                    if (length < 0 || length > body.length - bodyPosition) {
                        partial = true;
                        block.corrupted = true;
                        block.chunkEntries = 0;
                        break;
                    }
                    block.addChunk(bodyPosition, length);
                    bodyPosition += length;
                    block.segmentLengths[segment] += length;
                    block.segmentPasses[segment] += block.segmentNewPasses[segment];
                    block.newPasses -= block.segmentNewPasses[segment];
                    block.segmentDataPasses[segment] = block.segmentPasses[segment];
                    if (block.newPasses > 0) {
                        segment++;
                        block.segments++;
                    }
                } while (block.newPasses > 0);
                block.dataSegments = block.segments;
            }
        }
        if (partial) {
            bodyPosition = body.length;
        }
    }

    /**
     * Skips the codeword bytes of a packet's body, keeping the code-blocks' segment state.
     *
     * @param level the resolution level
     * @param precinct the precinct index
     */
    private void skipData(Resolution level, int precinct) {
        for (var band : level.bands()) {
            if (band.empty()) {
                continue;
            }
            for (var block : band.precincts()[precinct].codeBlocks) {
                if (block.newPasses == 0) {
                    continue;
                }
                var segment = currentSegment(block);
                do {
                    var length = block.segmentNewLengths[segment];
                    if (length < 0 || length > body.length - bodyPosition) {
                        bodyPosition = body.length;
                        return;
                    }
                    bodyPosition += length;
                    block.segmentPasses[segment] += block.segmentNewPasses[segment];
                    block.newPasses -= block.segmentNewPasses[segment];
                    if (block.newPasses > 0) {
                        segment++;
                        block.segments++;
                    }
                } while (block.newPasses > 0);
            }
        }
    }

    /**
     * Selects the segment a packet's first new passes go to, counting it as started.
     *
     * @param block the code-block
     * @return the segment index
     */
    private static int currentSegment(CodeBlock block) {
        if (block.segments == 0) {
            block.segments = 1;
            return 0;
        }
        var segment = block.segments - 1;
        if (block.segmentPasses[segment] == block.segmentMaxPasses[segment]) {
            segment++;
            block.segments++;
        }
        return segment;
    }

    /**
     * Reads a number of coding passes (Table B.4).
     *
     * @return the number of passes, from 1 to 164
     */
    private int passCount() {
        if (bits.bit() == 0) {
            return 1;
        }
        if (bits.bit() == 0) {
            return 2;
        }
        var n = bits.read(2);
        if (n != 3) {
            return 3 + n;
        }
        n = bits.read(5);
        if (n != 31) {
            return 6 + n;
        }
        return 37 + bits.read(7);
    }

    /**
     * Returns the base-2 logarithm of a positive value, rounded down.
     *
     * @param value the number, positive
     * @return {@code floor(log2(value))}
     */
    private static int floorLog2(int value) {
        return 31 - Integer.numberOfLeadingZeros(value);
    }
}
