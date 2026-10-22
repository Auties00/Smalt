/* Ports OpenJPEG 2.5.3 (j2k.c). Copyright (c) 2002-2014, Universite catholique de Louvain (UCL), Belgium, and
   the OpenJPEG authors; BSD 2-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

/**
 * Parses a JPEG 2000 codestream (ISO/IEC 15444-1 annex A): the main header, then every tile-part
 * header and the location of every tile-part body, without decoding any packet.
 *
 * <p>The tile-part bodies of each tile are recorded in codestream order, so a tile's packets can
 * be read as one stream. Packed packet headers are split per tile: the {@code PPT} segments of a
 * tile in {@code Zppt} order, and the {@code PPM} data in the order of the tile-parts that use it.
 *
 * <p>Damaged codestreams are read as far as they go: a codestream that ends inside a tile-part, a
 * tile-part length that runs past the end and a missing {@code EOC} marker keep the data read so
 * far; an invalid main header, and the first tile-part header, are {@link MediaException.Malformed}.
 */
public final class Codestream {
    /**
     * The start of codestream marker.
     */
    static final int SOC = 0xFF4F;

    /**
     * The image and tile size marker.
     */
    static final int SIZ = 0xFF51;

    /**
     * The coding style default marker.
     */
    static final int COD = 0xFF52;

    /**
     * The coding style component marker.
     */
    static final int COC = 0xFF53;

    /**
     * The quantization default marker.
     */
    static final int QCD = 0xFF5C;

    /**
     * The quantization component marker.
     */
    static final int QCC = 0xFF5D;

    /**
     * The region of interest marker.
     */
    static final int RGN = 0xFF5E;

    /**
     * The progression order change marker.
     */
    static final int POC = 0xFF5F;

    /**
     * The packed packet headers, main header marker.
     */
    static final int PPM = 0xFF60;

    /**
     * The packed packet headers, tile-part header marker.
     */
    static final int PPT = 0xFF61;

    /**
     * The comment marker.
     */
    static final int COM = 0xFF64;

    /**
     * The start of tile-part marker.
     */
    static final int SOT = 0xFF90;

    /**
     * The start of data marker.
     */
    static final int SOD = 0xFF93;

    /**
     * The end of codestream marker.
     */
    static final int EOC = 0xFFD9;

    /**
     * The extended capabilities marker ({@code CAP}), whose {@code Pcap} flags the parts of ISO/IEC 15444 a
     * codestream needs.
     */
    static final int CAP = 0xFF50;

    /**
     * The {@code Pcap} flag of ISO/IEC 15444-15 (HTJ2K): bit 15, counted from the most significant bit as 1.
     */
    static final int PCAP_PART15 = 1 << (32 - 15);

    /**
     * The {@code Ccap15} bits that, both set, declare that high-throughput and legacy code-blocks may share a
     * tile-component (ISO/IEC 15444-15 A.3.2).
     */
    static final int CCAP15_MIXED = 0xC000;

    /**
     * The largest number of components a codestream may declare.
     */
    private static final int MAX_COMPONENTS = 16384;

    /**
     * The largest number of tiles a codestream may declare.
     */
    private static final int MAX_TILES = 65535;

    /**
     * The largest precision supported, in bits.
     */
    private static final int MAX_PRECISION = 31;

    /**
     * The largest number of progressions of one header.
     */
    private static final int MAX_PROGRESSIONS = 256;

    /**
     * The codestream bytes.
     */
    private final byte[] data;

    /**
     * The horizontal image offset on the reference grid.
     */
    private long x0;

    /**
     * The vertical image offset on the reference grid.
     */
    private long y0;

    /**
     * The image right edge on the reference grid (exclusive).
     */
    private long x1;

    /**
     * The image bottom edge on the reference grid (exclusive).
     */
    private long y1;

    /**
     * The horizontal tile grid offset.
     */
    private long tileX0;

    /**
     * The vertical tile grid offset.
     */
    private long tileY0;

    /**
     * The nominal tile width.
     */
    private long tileWidth;

    /**
     * The nominal tile height.
     */
    private long tileHeight;

    /**
     * The number of tile columns.
     */
    private int tilesWide;

    /**
     * The number of tile rows.
     */
    private int tilesHigh;

    /**
     * The precision of each component, in bits.
     */
    private int[] precisions;

    /**
     * Whether each component is signed.
     */
    private boolean[] signed;

    /**
     * The horizontal subsampling of each component.
     */
    private int[] subsamplingX;

    /**
     * The vertical subsampling of each component.
     */
    private int[] subsamplingY;

    /**
     * The parameters of the main header.
     */
    private HeaderScope main;

    /**
     * The tiles, by index; {@code null} for tiles without any tile-part.
     */
    private TileData[] tiles;

    /**
     * The {@code PPM} segments by {@code Zppm}, or {@code null} when the main header has none.
     */
    private TreeMap<Integer, byte[]> ppm;

    /**
     * The {@code Ccap15} field of the {@code CAP} segment, or {@code -1} when the main header declares no
     * ISO/IEC 15444-15 capabilities.
     */
    private int htCapabilities = -1;

    /**
     * Constructs a parser over a codestream.
     *
     * @param data the codestream bytes
     */
    private Codestream(byte[] data) {
        this.data = data;
    }

    /**
     * Parses a codestream.
     *
     * @param data the bytes holding the codestream
     * @param start the index of its {@code SOC} marker
     * @param end the end of the codestream (exclusive)
     * @param limits the limits bounding the tile count and the component count
     * @return the parsed codestream
     * @throws MediaException.Malformed if the main header or the first tile-part header is invalid
     * @throws MediaException.Unsupported if the codestream uses a feature this decoder does not
     * implement
     * @throws MediaException.LimitExceeded if the component count exceeds the channel limit
     */
    public static Codestream parse(byte[] data, int start, int end, MediaLimits limits) {
        var codestream = new Codestream(data);
        codestream.read(start, end, limits);
        return codestream;
    }

    /**
     * Reads the main header and the tile-parts.
     *
     * @param start the index of the {@code SOC} marker
     * @param end the end of the codestream
     * @param limits the resource bounds of the tile and component counts
     */
    private void read(int start, int end, MediaLimits limits) {
        if (end - start < 4 || u16(start) != SOC) {
            throw new MediaException.Malformed("missing JPEG 2000 start of codestream marker");
        }
        var position = start + 2;
        if (u16(position) != SIZ) {
            throw new MediaException.Malformed("the JPEG 2000 codestream does not start with SIZ");
        }
        var length = segmentLength(position, end);
        readSiz(position + 4, position + 2 + length, limits);
        position += 2 + length;
        main = new HeaderScope(precisions.length);
        while (true) {
            if (end - position < 2) {
                throw new MediaException.Malformed("truncated JPEG 2000 main header");
            }
            var marker = u16(position);
            if (marker == SOT) {
                break;
            }
            if (marker == EOC) {
                throw new MediaException.Malformed("the JPEG 2000 codestream holds no tile");
            }
            if ((marker & 0xFF00) != 0xFF00 || marker < 0xFF30) {
                throw new MediaException.Malformed("invalid JPEG 2000 marker " + Integer.toHexString(marker));
            }
            if (marker <= 0xFF3F) {
                position += 2;
                continue;
            }
            length = segmentLength(position, end);
            readHeaderSegment(marker, position + 4, position + 2 + length, main, true);
            position += 2 + length;
        }
        if (main.coding == null) {
            throw new MediaException.Malformed("the JPEG 2000 main header has no COD segment");
        }
        if (main.quantization == null) {
            throw new MediaException.Malformed("the JPEG 2000 main header has no QCD segment");
        }
        tiles = new TileData[tilesWide * tilesHigh];
        readTileParts(position, end);
    }

    /**
     * Reads the tile-parts that follow the main header.
     *
     * @param position the index of the first {@code SOT} marker
     * @param end the end of the codestream
     */
    private void readTileParts(int position, int end) {
        var ppmChunks = ppm == null ? null : splitPpm();
        var tilePart = 0;
        while (end - position >= 2) {
            var marker = u16(position);
            if (marker == EOC) {
                break;
            }
            if (marker != SOT) {
                if (tilePart == 0) {
                    throw new MediaException.Malformed("expected a JPEG 2000 SOT marker");
                }
                break;
            }
            if (end - position < 12 || u16(position + 2) != 10) {
                if (tilePart == 0) {
                    throw new MediaException.Malformed("invalid JPEG 2000 SOT segment");
                }
                break;
            }
            var index = u16(position + 4);
            var partLength = u32(position + 6);
            if (index >= tiles.length) {
                throw new MediaException.Malformed("invalid JPEG 2000 tile index " + index);
            }
            if (partLength != 0 && partLength < 14) {
                if (tilePart == 0) {
                    throw new MediaException.Malformed("invalid JPEG 2000 tile-part length " + partLength);
                }
                break;
            }
            var partEnd = partLength == 0 ? end : (int) Math.min(end, position + partLength);
            var tile = tiles[index];
            if (tile == null) {
                tile = tiles[index] = new TileData(precisions.length);
            }
            var cursor = position + 12;
            var bodyStart = -1;
            try {
                while (end - cursor >= 2) {
                    var headerMarker = u16(cursor);
                    if (headerMarker == SOD) {
                        bodyStart = cursor + 2;
                        break;
                    }
                    if ((headerMarker & 0xFF00) != 0xFF00 || headerMarker < 0xFF30) {
                        throw new MediaException.Malformed("invalid JPEG 2000 marker in a tile-part header");
                    }
                    if (headerMarker <= 0xFF3F) {
                        cursor += 2;
                        continue;
                    }
                    var segment = segmentLength(cursor, end);
                    readTileSegment(headerMarker, cursor + 4, cursor + 2 + segment, tile);
                    cursor += 2 + segment;
                }
            } catch (MediaException.Malformed exception) {
                if (tilePart == 0) {
                    throw exception;
                }
                break;
            }
            if (bodyStart < 0) {
                if (tilePart == 0) {
                    throw new MediaException.Malformed("truncated JPEG 2000 tile-part header");
                }
                break;
            }
            if (partLength == 0) {
                partEnd = end;
                if (partEnd - bodyStart >= 2 && u16(partEnd - 2) == EOC) {
                    partEnd -= 2;
                }
            }
            if (partEnd < bodyStart) {
                partEnd = bodyStart;
            }
            tile.addBody(bodyStart, partEnd - bodyStart);
            if (ppmChunks != null) {
                tile.addPpm(tilePart < ppmChunks.size() ? ppmChunks.get(tilePart) : new byte[0]);
            }
            tilePart++;
            if (partLength == 0 || partEnd >= end) {
                break;
            }
            position = partEnd;
        }
        if (tilePart == 0) {
            throw new MediaException.Malformed("the JPEG 2000 codestream holds no tile-part");
        }
    }

    /**
     * Splits the {@code PPM} data into the packed packet headers of each tile-part.
     *
     * @implNote This implementation follows OpenJPEG's {@code opj_j2k_merge_ppm}: the segments are
     * concatenated in {@code Zppm} order, and an {@code Nppm} field may continue into the next
     * segment.
     * @return the headers of each tile-part, in codestream order
     * @throws MediaException.Malformed if the {@code Nppm} lengths are inconsistent
     */
    private List<byte[]> splitPpm() {
        var joined = new ByteArrayOutputStream();
        for (var segment : ppm.values()) {
            joined.writeBytes(segment);
        }
        var bytes = joined.toByteArray();
        var chunks = new ArrayList<byte[]>();
        var position = 0;
        while (position < bytes.length) {
            if (bytes.length - position < 4) {
                throw new MediaException.Malformed("truncated JPEG 2000 PPM length");
            }
            var length = ((long) (bytes[position] & 0xFF) << 24) | ((bytes[position + 1] & 0xFF) << 16)
                    | ((bytes[position + 2] & 0xFF) << 8) | (bytes[position + 3] & 0xFF);
            position += 4;
            if (length > bytes.length - position) {
                throw new MediaException.Malformed("corrupt JPEG 2000 PPM segments");
            }
            chunks.add(Arrays.copyOfRange(bytes, position, position + (int) length));
            position += (int) length;
        }
        return chunks;
    }

    /**
     * Reads the {@code SIZ} segment.
     *
     * @param position the index of its first field ({@code Rsiz})
     * @param end the end of the segment
     * @param limits the resource bounds of the tile and component counts
     */
    private void readSiz(int position, int end, MediaLimits limits) {
        if (end - position < 36 || (end - position - 36) % 3 != 0) {
            throw new MediaException.Malformed("invalid JPEG 2000 SIZ segment length");
        }
        x1 = u32(position + 2);
        y1 = u32(position + 6);
        x0 = u32(position + 10);
        y0 = u32(position + 14);
        tileWidth = u32(position + 18);
        tileHeight = u32(position + 22);
        tileX0 = u32(position + 26);
        tileY0 = u32(position + 30);
        var components = u16(position + 34);
        if (components == 0 || components > MAX_COMPONENTS || components != (end - position - 36) / 3) {
            throw new MediaException.Malformed("invalid JPEG 2000 component count " + components);
        }
        LimitChecks.checkChannels(limits, components);
        if (x0 >= x1 || y0 >= y1) {
            throw new MediaException.Malformed("invalid JPEG 2000 image area");
        }
        if (tileWidth == 0 || tileHeight == 0) {
            throw new MediaException.Malformed("invalid JPEG 2000 tile size");
        }
        if (tileX0 > x0 || tileY0 > y0 || tileX0 + tileWidth <= x0 || tileY0 + tileHeight <= y0) {
            throw new MediaException.Malformed("invalid JPEG 2000 tile offset");
        }
        if (x1 > Integer.MAX_VALUE || y1 > Integer.MAX_VALUE) {
            throw new MediaException.Unsupported("JPEG 2000", "reference grid coordinates above 2^31");
        }
        var wide = Math.ceilDiv(x1 - tileX0, tileWidth);
        var high = Math.ceilDiv(y1 - tileY0, tileHeight);
        if (wide * high > MAX_TILES) {
            throw new MediaException.Malformed("invalid JPEG 2000 tile count " + wide + "x" + high);
        }
        tilesWide = (int) wide;
        tilesHigh = (int) high;
        precisions = new int[components];
        signed = new boolean[components];
        subsamplingX = new int[components];
        subsamplingY = new int[components];
        for (var i = 0; i < components; i++) {
            var at = position + 36 + 3 * i;
            var ssiz = data[at] & 0xFF;
            precisions[i] = (ssiz & 0x7F) + 1;
            signed[i] = (ssiz & 0x80) != 0;
            subsamplingX[i] = data[at + 1] & 0xFF;
            subsamplingY[i] = data[at + 2] & 0xFF;
            if (subsamplingX[i] == 0 || subsamplingY[i] == 0) {
                throw new MediaException.Malformed("invalid JPEG 2000 component subsampling");
            }
            if (precisions[i] > 38) {
                throw new MediaException.Malformed("invalid JPEG 2000 precision " + precisions[i]);
            }
            if (precisions[i] > MAX_PRECISION) {
                throw new MediaException.Unsupported("JPEG 2000", precisions[i] + "-bit samples");
            }
        }
    }

    /**
     * Reads a main header segment.
     *
     * @param marker the marker code
     * @param position the index of the first field
     * @param end the end of the segment
     * @param scope the scope receiving the parameters
     * @param mainHeader whether the segment belongs to the main header
     */
    private void readHeaderSegment(int marker, int position, int end, HeaderScope scope, boolean mainHeader) {
        var componentBytes = precisions.length <= 256 ? 1 : 2;
        switch (marker) {
            case COD -> {
                if (end - position < 5) {
                    throw new MediaException.Malformed("truncated JPEG 2000 COD segment");
                }
                var flags = data[position] & 0xFF;
                if ((flags & ~0x07) != 0) {
                    throw new MediaException.Malformed("invalid JPEG 2000 coding style " + flags);
                }
                var progression = data[position + 1] & 0xFF;
                if (progression > ProgressionChange.CPRL) {
                    throw new MediaException.Malformed("invalid JPEG 2000 progression order " + progression);
                }
                var layers = u16(position + 2);
                if (layers == 0) {
                    throw new MediaException.Malformed("invalid JPEG 2000 layer count 0");
                }
                var transform = data[position + 4] & 0xFF;
                if (transform > 1) {
                    if (transform == 2) {
                        // TODO: decode the multiple component transforms of ISO/IEC 15444-2 (MCT, MCC, MCO segments)
                        throw new MediaException.Unsupported("JPEG 2000", "Part 2 multiple component transforms");
                    }
                    throw new MediaException.Malformed("invalid JPEG 2000 component transform " + transform);
                }
                var coding = ComponentCoding.parse(data, position + 5, end, (flags & 1) != 0);
                scope.codingFlags = flags;
                scope.progression = progression;
                scope.layers = layers;
                scope.transform = transform;
                scope.coding = coding;
            }
            case COC -> {
                if (end - position < componentBytes + 1) {
                    throw new MediaException.Malformed("truncated JPEG 2000 COC segment");
                }
                var component = component(position, componentBytes);
                var flags = data[position + componentBytes] & 0xFF;
                scope.componentCodings[component] = ComponentCoding.parse(data, position + componentBytes + 1, end,
                        (flags & 1) != 0);
            }
            case QCD -> scope.quantization = Quantization.parse(data, position, end);
            case QCC -> {
                if (end - position < componentBytes + 1) {
                    throw new MediaException.Malformed("truncated JPEG 2000 QCC segment");
                }
                var component = component(position, componentBytes);
                scope.componentQuantizations[component] = Quantization.parse(data, position + componentBytes, end);
            }
            case RGN -> {
                if (end - position < componentBytes + 2) {
                    throw new MediaException.Malformed("truncated JPEG 2000 RGN segment");
                }
                var component = component(position, componentBytes);
                var style = data[position + componentBytes] & 0xFF;
                if (style != 0) {
                    throw new MediaException.Unsupported("JPEG 2000", "region of interest style " + style);
                }
                scope.roiShifts[component] = data[position + componentBytes + 1] & 0xFF;
            }
            case POC -> readPoc(position, end, scope, componentBytes);
            case PPM -> {
                if (!mainHeader) {
                    throw new MediaException.Malformed("JPEG 2000 PPM segment in a tile-part header");
                }
                if (end - position < 1) {
                    throw new MediaException.Malformed("truncated JPEG 2000 PPM segment");
                }
                if (ppm == null) {
                    ppm = new TreeMap<>();
                }
                var index = data[position] & 0xFF;
                if (ppm.putIfAbsent(index, Arrays.copyOfRange(data, position + 1, end)) != null) {
                    throw new MediaException.Malformed("duplicate JPEG 2000 PPM segment " + index);
                }
            }
            case CAP -> {
                if (mainHeader) {
                    readCap(position, end);
                }
            }
            default -> {
                // COM, TLM, PLM, PLT, CRG, CPF and the Part 2 segments carry nothing the decoder needs
            }
        }
    }

    /**
     * Reads a {@code CAP} segment, keeping its {@code Ccap15} field.
     *
     * @param position the index of its first field ({@code Pcap})
     * @param end the end of the segment
     * @throws MediaException.Malformed if the segment is shorter than its {@code Pcap} declares
     */
    private void readCap(int position, int end) {
        if (end - position < 4) {
            throw new MediaException.Malformed("truncated JPEG 2000 CAP segment");
        }
        var pcap = (int) u32(position);
        if (end - position < 4 + 2 * Integer.bitCount(pcap)) {
            throw new MediaException.Malformed("truncated JPEG 2000 CAP segment");
        }
        if ((pcap & PCAP_PART15) != 0) {
            var index = Integer.bitCount(pcap >>> (32 - 14));
            htCapabilities = u16(position + 4 + 2 * index);
        }
    }

    /**
     * Returns whether a tile-component whose code-block style allows both block coders may mix them: the
     * {@code CAP} segment declares the MIXED set of ISO/IEC 15444-15, or there is no {@code Ccap15} field to exclude
     * it.
     *
     * @return whether code-blocks may be classified individually
     */
    boolean mixedCoders() {
        return htCapabilities < 0 || (htCapabilities & CCAP15_MIXED) == CCAP15_MIXED;
    }

    /**
     * Reads a tile-part header segment.
     *
     * @param marker the marker code
     * @param position the index of the first field
     * @param end the end of the segment
     * @param tile the tile receiving the parameters
     */
    private void readTileSegment(int marker, int position, int end, TileData tile) {
        if (marker == PPT) {
            if (end - position < 1) {
                throw new MediaException.Malformed("truncated JPEG 2000 PPT segment");
            }
            if (ppm != null) {
                throw new MediaException.Malformed("JPEG 2000 codestream with both PPM and PPT segments");
            }
            tile.addPpt(data[position] & 0xFF, Arrays.copyOfRange(data, position + 1, end));
            return;
        }
        readHeaderSegment(marker, position, end, tile.scope, false);
    }

    /**
     * Reads a {@code POC} segment.
     *
     * @param position the index of its first field
     * @param end the end of the segment
     * @param scope the scope receiving the progressions
     * @param componentBytes the size of a component index, 1 or 2
     */
    private void readPoc(int position, int end, HeaderScope scope, int componentBytes) {
        var entry = 5 + 2 * componentBytes;
        var count = (end - position) / entry;
        if (count == 0 || (end - position) % entry != 0) {
            throw new MediaException.Malformed("invalid JPEG 2000 POC segment length");
        }
        if (scope.progressions.size() + count > MAX_PROGRESSIONS) {
            throw new MediaException.Malformed("too many JPEG 2000 progression changes");
        }
        var components = precisions.length;
        for (var i = 0; i < count; i++) {
            var at = position + i * entry;
            var resolutionStart = data[at] & 0xFF;
            var componentStart = componentBytes == 1 ? data[at + 1] & 0xFF : u16(at + 1);
            var layerEnd = u16(at + 1 + componentBytes);
            var resolutionEnd = data[at + 3 + componentBytes] & 0xFF;
            var componentEnd = componentBytes == 1 ? data[at + 4 + componentBytes] & 0xFF : u16(at + 4 + componentBytes);
            if (componentEnd == 0) {
                componentEnd = componentBytes == 1 ? 256 : MAX_COMPONENTS;
            }
            var order = data[at + 4 + 2 * componentBytes] & 0xFF;
            if (order > ProgressionChange.CPRL) {
                throw new MediaException.Malformed("invalid JPEG 2000 progression order " + order);
            }
            scope.progressions.add(new ProgressionChange(resolutionStart, componentStart, layerEnd, resolutionEnd,
                    Math.min(componentEnd, components), order));
        }
    }

    /**
     * Reads a component index.
     *
     * @param position the index of the field
     * @param size the field size, 1 or 2
     * @return the component
     * @throws MediaException.Malformed if the component does not exist
     */
    private int component(int position, int size) {
        var component = size == 1 ? data[position] & 0xFF : u16(position);
        if (component >= precisions.length) {
            throw new MediaException.Malformed("invalid JPEG 2000 component index " + component);
        }
        return component;
    }

    /**
     * Returns the length of the segment of a marker, checking it fits the codestream.
     *
     * @param position the index of the marker
     * @param end the end of the codestream
     * @return the segment length, counting the length field
     * @throws MediaException.Malformed if the length is invalid
     */
    private int segmentLength(int position, int end) {
        if (end - position < 4) {
            throw new MediaException.Malformed("truncated JPEG 2000 marker segment");
        }
        var length = u16(position + 2);
        if (length < 2 || length > end - position - 2) {
            throw new MediaException.Malformed("invalid JPEG 2000 marker segment length " + length);
        }
        return length;
    }

    /**
     * Reads a big-endian 16-bit value.
     *
     * @param position the index of the first byte
     * @return the value
     */
    private int u16(int position) {
        return ByteArrays.u16be(data, position);
    }

    /**
     * Reads a big-endian 32-bit unsigned value.
     *
     * @param position the index of the first byte
     * @return the value
     */
    private long u32(int position) {
        return ByteArrays.u32be(data, position);
    }

    /**
     * Returns the codestream bytes.
     *
     * @return the array the codestream was parsed from, not a copy
     */
    public byte[] data() {
        return data;
    }

    /**
     * Returns the horizontal image offset on the reference grid.
     *
     * @return {@code XOsiz}
     */
    public int x0() {
        return (int) x0;
    }

    /**
     * Returns the vertical image offset on the reference grid.
     *
     * @return {@code YOsiz}
     */
    public int y0() {
        return (int) y0;
    }

    /**
     * Returns the image right edge on the reference grid.
     *
     * @return {@code Xsiz}
     */
    public int x1() {
        return (int) x1;
    }

    /**
     * Returns the image bottom edge on the reference grid.
     *
     * @return {@code Ysiz}
     */
    public int y1() {
        return (int) y1;
    }

    /**
     * Returns the image width on the reference grid.
     *
     * @return {@code Xsiz - XOsiz}
     */
    public int width() {
        return (int) (x1 - x0);
    }

    /**
     * Returns the image height on the reference grid.
     *
     * @return {@code Ysiz - YOsiz}
     */
    public int height() {
        return (int) (y1 - y0);
    }

    /**
     * Returns the reference grid area of a tile, clipped to the image.
     *
     * @param tile the tile index
     * @return the left, top, right and bottom edges
     */
    public int[] tileBounds(int tile) {
        var column = tile % tilesWide;
        var row = tile / tilesWide;
        var left = tileX0 + column * tileWidth;
        var top = tileY0 + row * tileHeight;
        return new int[]{
                (int) Math.max(left, x0), (int) Math.max(top, y0),
                (int) Math.min(left + tileWidth, x1), (int) Math.min(top + tileHeight, y1)
        };
    }

    /**
     * Returns the number of image components.
     *
     * @return the component count
     */
    public int components() {
        return precisions.length;
    }

    /**
     * Returns the precision of a component.
     *
     * @param component the component index
     * @return the bits per sample, from 1 to 31
     */
    public int precision(int component) {
        return precisions[component];
    }

    /**
     * Returns whether a component is signed.
     *
     * @param component the component index
     * @return whether its samples are two's complement
     */
    public boolean signed(int component) {
        return signed[component];
    }

    /**
     * Returns the horizontal subsampling of a component.
     *
     * @param component the component index
     * @return {@code XRsiz}, from 1 to 255
     */
    public int subsamplingX(int component) {
        return subsamplingX[component];
    }

    /**
     * Returns the vertical subsampling of a component.
     *
     * @param component the component index
     * @return {@code YRsiz}, from 1 to 255
     */
    public int subsamplingY(int component) {
        return subsamplingY[component];
    }

    /**
     * Returns the data of a tile.
     *
     * @param tile the tile index
     * @return the tile's bodies and headers, or {@code null} when the codestream holds no tile-part
     * of that tile
     */
    TileData tile(int tile) {
        return tiles[tile];
    }

    /**
     * Returns the number of tiles.
     *
     * @return the tile count
     */
    public int tileCount() {
        return tiles.length;
    }

    /**
     * Resolves the coding parameters of a tile from the main header and the tile's own headers.
     *
     * @param tile the tile index
     * @return the parameters
     */
    TileParameters parameters(int tile) {
        var data = tiles[tile];
        var scope = data == null ? null : data.scope;
        var components = precisions.length;
        var tileCod = scope != null && scope.coding != null;
        var flags = tileCod ? scope.codingFlags : main.codingFlags;
        var progression = tileCod ? scope.progression : main.progression;
        var layers = tileCod ? scope.layers : main.layers;
        var transform = tileCod ? scope.transform : main.transform;
        var codings = new ComponentCoding[components];
        var quantizations = new Quantization[components];
        var roiShifts = new int[components];
        for (var c = 0; c < components; c++) {
            ComponentCoding coding = null;
            Quantization quantization = null;
            var roi = -1;
            if (scope != null) {
                coding = scope.componentCodings[c] != null ? scope.componentCodings[c] : scope.coding;
                quantization = scope.componentQuantizations[c] != null ? scope.componentQuantizations[c]
                        : scope.quantization;
                roi = scope.roiShifts[c];
            }
            if (coding == null) {
                coding = main.componentCodings[c] != null ? main.componentCodings[c] : main.coding;
            }
            if (quantization == null) {
                quantization = main.componentQuantizations[c] != null ? main.componentQuantizations[c]
                        : main.quantization;
            }
            if (roi < 0) {
                roi = Math.max(main.roiShifts[c], 0);
            }
            codings[c] = coding;
            quantizations[c] = quantization;
            roiShifts[c] = roi;
        }
        var progressions = new ArrayList<ProgressionChange>();
        if (scope != null && !scope.progressions.isEmpty()) {
            progressions.addAll(scope.progressions);
        } else {
            progressions.addAll(main.progressions);
        }
        return new TileParameters(flags, progression, layers, transform, codings, quantizations, roiShifts,
                List.copyOf(progressions));
    }
}
