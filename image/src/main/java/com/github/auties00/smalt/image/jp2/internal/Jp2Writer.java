/* Ports OpenJPEG 2.5.3 (j2k.c, jp2.c, tcd.c, dwt.c, mct.c). Copyright (c) 2002-2014, Universite catholique de
   Louvain (UCL), Belgium, and the OpenJPEG authors; BSD 2-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.internal.io.FourCc;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Encodes components into a JPEG 2000 codestream (ISO/IEC 15444-1 annex A to F), and wraps a
 * codestream in a JP2 file (annex I).
 *
 * <p>Each tile goes through the DC level shift, the forward component transform, the forward
 * wavelet transform, the region of interest scaling, the code-block coder and the rate allocation,
 * then its packets are written in the order of its progressions, divided into tile-parts.
 *
 * <p>Lossless codestreams of the ISO/IEC 15444-1 block coder are byte-identical to
 * {@code opj_compress}'s with the same parameters and comment. Arguments are not checked; values
 * outside the documented ranges give unspecified results.
 *
 * @implNote This implementation follows OpenJPEG's rate allocation ({@code opj_tcd_rateallocate}
 * and {@code opj_tcd_makelayer}): a bisection on the rate-distortion slope threshold against each
 * layer's byte budget or distortion target.
 */
public final class Jp2Writer {
    /**
     * The approximate work of coding one sample, weighed against
     * {@link ParallelBands#MIN_PARALLEL_WORK}.
     */
    private static final long SAMPLE_WORK = 96;

    /**
     * The norms of the reversible component transform (OpenJPEG's {@code opj_mct_norms}).
     */
    private static final double[] RCT_NORMS = {1.732, .8292, .8292};

    /**
     * The norms of the irreversible component transform (OpenJPEG's {@code opj_mct_norms_real}).
     */
    private static final double[] ICT_NORMS = {1.732, 1.805, 1.573};

    /**
     * The {@code Rsiz} flag of a codestream that needs ISO/IEC 15444-15 (bit 14).
     */
    private static final int RSIZ_PART15 = 0x4000;

    /**
     * The number of times the selection of high-throughput candidates narrows its byte target when the
     * packets it selects exceed a layer's budget.
     */
    private static final int SELECTION_ROUNDS = 8;

    /**
     * The largest number of magnitude bit-planes of a sub-band coded with the high-throughput block coder, the
     * most {@link HtDecoder} decodes.
     */
    private static final int HT_MAX_BIT_PLANES = 30;

    /**
     * The largest number of tile-parts of a tile, the range of the {@code TNsot} field.
     */
    private static final int MAX_TILE_PARTS = 255;

    /**
     * The number of entries of a full {@code TLM} segment with one-byte tile indices: 4 bytes of fields and 5 per
     * entry within the segment's 65535 bytes.
     */
    private static final int TLM_NARROW_ENTRIES = (65535 - 4) / 5;

    /**
     * The number of entries of a full {@code TLM} segment with two-byte tile indices.
     */
    private static final int TLM_WIDE_ENTRIES = (65535 - 4) / 6;

    /**
     * The largest number of magnitude bit-planes a coefficient scaled by a region of interest keeps, the most the
     * fixed-point form of {@link Tier1Encoder} holds.
     */
    private static final int ROI_MAX_BIT_PLANES = 31 - Tier1Tables.NMSEDEC_FRACBITS;

    /**
     * An empty array.
     */
    private static final byte[] NO_BYTES = new byte[0];

    /**
     * Prevents instantiation.
     */
    private Jp2Writer() {
        throw new AssertionError();
    }

    /**
     * Encodes components into a codestream.
     *
     * @param config the encoding parameters
     * @param planes the samples of each component, row-major over its sample grid (from {@code ceil(x0 / dx)} to
     * {@code ceil(x1 / dx)} by {@code ceil(y0 / dy)} to {@code ceil(y1 / dy)})
     * @param limits the limits bounding the allocations
     * @return the codestream
     * @throws IllegalArgumentException if the high-throughput block coder is selected and a sub-band needs more
     * than 30 magnitude bit-planes, a tile is divided into more than 255 tile-parts, a region of interest scales a
     * coefficient beyond 25 magnitude bit-planes, or the guard bits leave a reversible sub-band fewer bit-planes
     * than its coefficients need
     */
    public static byte[] codestream(EncoderConfig config, int[][] planes, MediaLimits limits) {
        var components = config.components();
        var quantizations = new Quantization[components];
        for (var c = 0; c < components; c++) {
            quantizations[c] = stepSizes(config, config.precisions()[c]);
        }
        if ((config.codeBlockStyle() & ComponentCoding.HIGH_THROUGHPUT) != 0
                && magnitudeBound(config, quantizations) > HT_MAX_BIT_PLANES) {
            throw new IllegalArgumentException("the high-throughput block coder codes at most " + HT_MAX_BIT_PLANES
                    + " magnitude bit-planes, and these channels need " + magnitudeBound(config, quantizations));
        }
        var coding = coding(config);
        var progressions = progressions(config);
        var tiles = config.tilesWide() * config.tilesHigh();
        var plans = new TilePartPlan[tiles];
        var totalParts = 0L;
        for (var t = 0; t < tiles; t++) {
            plans[t] = new TilePartPlan(config, progressions, config.tileBounds(t));
            if (plans[t].count() > MAX_TILE_PARTS) {
                throw new IllegalArgumentException("tile " + t + " would be divided into " + plans[t].count()
                        + " tile-parts, above " + MAX_TILE_PARTS);
            }
            totalParts += plans[t].count();
        }
        var out = new ByteWriter(4096);
        var tlm = writeMainHeader(out, config, coding, quantizations, totalParts);
        var budgets = budgets(config, out.size(), plans);
        var outputs = new TileOutput[tiles];
        for (var t = 0; t < tiles; t++) {
            outputs[t] = encodeTile(config, coding, quantizations, planes, t, plans[t], progressions, budgets[t],
                    limits);
            ParallelBands.checkpoint();
        }
        var lengths = new long[(int) totalParts];
        var index = 0;
        for (var t = 0; t < tiles; t++) {
            var output = outputs[t];
            var parts = output.bodies().length;
            for (var p = 0; p < parts; p++) {
                var header = p == 0 ? output.header() : NO_BYTES;
                var plt = config.plt() ? plt(output.packetLengths()[p]) : NO_BYTES;
                var body = output.bodies()[p];
                var length = 14L + header.length + plt.length + body.length;
                if (length > 0xFFFF_FFFFL) {
                    throw new IllegalArgumentException("a tile-part of " + length + " bytes exceeds the 32-bit length "
                            + "field of its SOT segment");
                }
                out.u16be(Codestream.SOT);
                out.u16be(10);
                out.u16be(t);
                out.u32be(length);
                out.u8(p);
                out.u8(parts);
                out.bytes(header);
                out.bytes(plt);
                out.u16be(Codestream.SOD);
                out.bytes(body);
                lengths[index++] = length;
            }
        }
        out.u16be(Codestream.EOC);
        var bytes = out.toByteArray();
        if (tlm >= 0) {
            fillTlm(bytes, tlm, plans, lengths);
        }
        return bytes;
    }

    /**
     * Returns the progressions of every tile: the progression changes, or the {@code COD} progression alone.
     *
     * @param config the parameters
     * @return the progressions
     */
    private static List<ProgressionChange> progressions(EncoderConfig config) {
        if (!config.changes().isEmpty()) {
            return config.changes();
        }
        return List.of(new ProgressionChange(0, 0, config.layers(), ComponentCoding.MAX_RESOLUTIONS,
                config.components(), config.progression()));
    }

    /**
     * Wraps a codestream in a JP2 file, or a JPH file (ISO/IEC 15444-15 annex D) when it codes with the
     * high-throughput block coder.
     *
     * @param codestream the codestream bytes
     * @param config the parameters it was encoded with
     * @param colorSpace the enumerated color space, used when {@code icc} is {@code null}
     * @param icc the ICC profile to embed, or {@code null}
     * @param alpha the index of the opacity channel, or {@code -1}
     * @param premultiplied whether the opacity channel is premultiplied opacity
     * @param exif the EXIF block to write in a {@code uuid} box, starting with its byte-order mark, or {@code null}
     * @param xmp the XMP packet to write in a {@code uuid} box, or {@code null}
     * @param iptc the IPTC-IIM data sets to write in a {@code uuid} box, or {@code null}
     * @return the JP2 file
     */
    public static byte[] jp2(byte[] codestream, EncoderConfig config, int colorSpace, byte[] icc, int alpha,
                             boolean premultiplied, byte[] exif, byte[] xmp, byte[] iptc) {
        var metadata = (exif != null ? exif.length + 24 : 0) + (xmp != null ? xmp.length + 24 : 0)
                + (iptc != null ? iptc.length + 24 : 0);
        var out = new ByteWriter(codestream.length + 256 + metadata);
        out.bytes(Jp2Boxes.SIGNATURE);
        // ISO/IEC 15444-15 D.3: a file holding an HTJ2K codestream is a JPH file
        var brand = (config.codeBlockStyle() & ComponentCoding.HIGH_THROUGHPUT) != 0 ? "jph " : "jp2 ";
        out.begin(FourCc.of("ftyp"));
        out.fourCc(brand);
        out.u32be(0);
        out.fourCc(brand);
        out.end();
        var components = config.components();
        var uniform = true;
        for (var c = 1; c < components; c++) {
            uniform &= config.precisions()[c] == config.precisions()[0] && config.signed()[c] == config.signed()[0];
        }
        out.begin(FourCc.of("jp2h"));
        out.begin(FourCc.of("ihdr"));
        out.u32be(config.height());
        out.u32be(config.width());
        out.u16be(components);
        out.u8(uniform ? depth(config, 0) : 255);
        out.u8(7);
        out.u8(0);
        out.u8(0);
        out.end();
        if (!uniform) {
            out.begin(FourCc.of("bpcc"));
            for (var c = 0; c < components; c++) {
                out.u8(depth(config, c));
            }
            out.end();
        }
        out.begin(FourCc.of("colr"));
        if (icc != null) {
            out.u8(2);
            out.u8(0);
            out.u8(0);
            out.bytes(icc);
        } else {
            out.u8(1);
            out.u8(0);
            out.u8(0);
            out.u32be(colorSpace);
        }
        out.end();
        if (alpha >= 0) {
            out.begin(FourCc.of("cdef"));
            out.u16be(components);
            var color = 1;
            for (var c = 0; c < components; c++) {
                out.u16be(c);
                if (c == alpha) {
                    out.u16be(premultiplied ? 2 : 1);
                    out.u16be(0);
                } else {
                    out.u16be(0);
                    out.u16be(color++);
                }
            }
            out.end();
        }
        out.end();
        uuid(out, Jp2Boxes.EXIF_UUID, exif);
        uuid(out, Jp2Boxes.IPTC_UUID, iptc);
        uuid(out, Jp2Boxes.XMP_UUID, xmp);
        out.begin(FourCc.of("jp2c"));
        out.bytes(codestream);
        out.end();
        return out.toByteArray();
    }

    /**
     * Writes a {@code uuid} box, if it has a payload.
     *
     * @param out the stream
     * @param uuid the box's 16-byte identifier
     * @param payload the data after the identifier, or {@code null} to write nothing
     */
    private static void uuid(ByteWriter out, byte[] uuid, byte[] payload) {
        if (payload == null) {
            return;
        }
        out.begin(FourCc.of("uuid"));
        out.bytes(uuid);
        out.bytes(payload);
        out.end();
    }

    /**
     * Returns the {@code BPC} byte of a component.
     *
     * @param config the parameters
     * @param component the component index
     * @return the precision minus one, with bit 7 set for signed components
     */
    private static int depth(EncoderConfig config, int component) {
        return config.precisions()[component] - 1 + (config.signed()[component] ? 0x80 : 0);
    }

    /**
     * Builds the coding style of every component.
     *
     * @param config the parameters
     * @return the coding style
     */
    private static ComponentCoding coding(EncoderConfig config) {
        var resolutions = config.resolutions();
        var widths = new int[resolutions];
        var heights = new int[resolutions];
        for (var r = 0; r < resolutions; r++) {
            widths[r] = config.precinctWidths() == null ? 15 : config.precinctWidths()[r];
            heights[r] = config.precinctHeights() == null ? 15 : config.precinctHeights()[r];
        }
        return new ComponentCoding(resolutions, config.codeBlockWidth(), config.codeBlockHeight(),
                config.codeBlockStyle(), config.reversible(), widths, heights);
    }

    /**
     * Computes the step sizes of a component (OpenJPEG's {@code opj_dwt_calc_explicit_stepsizes}):
     * no quantization for the 5-3 wavelet, the inverse of each sub-band's 9-7 synthesis norm for
     * the 9-7 one.
     *
     * @param config the parameters
     * @param precision the component precision
     * @return the quantization, with the configured guard bits
     */
    static Quantization stepSizes(EncoderConfig config, int precision) {
        var resolutions = config.resolutions();
        var bands = 3 * resolutions - 2;
        var exponents = new int[Quantization.MAX_BANDS];
        var mantissas = new int[Quantization.MAX_BANDS];
        for (var band = 0; band < bands; band++) {
            var resolution = band == 0 ? 0 : (band - 1) / 3 + 1;
            var orientation = band == 0 ? 0 : (band - 1) % 3 + 1;
            var level = resolutions - 1 - resolution;
            var gain = !config.reversible() ? 0 : orientation == 0 ? 0 : orientation == 3 ? 2 : 1;
            var stepSize = config.reversible() ? 1.0 : (1 << gain) / Tier1Encoder.norm(level, orientation, false);
            var value = (int) Math.floor(stepSize * 8192.0);
            var log = 31 - Integer.numberOfLeadingZeros(value);
            var p = log - 13;
            var n = 11 - log;
            mantissas[band] = (n < 0 ? value >> -n : value << n) & 0x7FF;
            exponents[band] = precision + gain - p;
        }
        return new Quantization(config.reversible() ? Quantization.NONE : Quantization.SCALAR_EXPOUNDED,
                config.guardBits(), exponents, mantissas);
    }

    /**
     * Writes the main header: {@code SOC}, {@code SIZ}, {@code CAP} for the high-throughput block
     * coder, {@code COD}, {@code QCD}, a {@code QCC} for each component quantized differently from
     * the first, the {@code TLM} segments with room for every tile-part, a {@code POC} segment when
     * the progression changes go in the main header, the {@code RGN} segment of a whole component
     * region of interest, and {@code COM}.
     *
     * @param out the stream
     * @param config the parameters
     * @param coding the coding style
     * @param quantizations the quantization of each component
     * @param tileParts the total number of tile-parts
     * @return the offset of the first {@code TLM} segment, or {@code -1} without one
     */
    private static int writeMainHeader(ByteWriter out, EncoderConfig config, ComponentCoding coding,
                                       Quantization[] quantizations, long tileParts) {
        var components = config.components();
        var highThroughput = (config.codeBlockStyle() & ComponentCoding.HIGH_THROUGHPUT) != 0;
        out.u16be(Codestream.SOC);
        out.u16be(Codestream.SIZ);
        out.u16be(38 + 3 * components);
        out.u16be(config.profile() | (highThroughput ? RSIZ_PART15 : 0));
        out.u32be(config.x1());
        out.u32be(config.y1());
        out.u32be(config.x0());
        out.u32be(config.y0());
        out.u32be(config.tileWidth());
        out.u32be(config.tileHeight());
        out.u32be(config.tileX0());
        out.u32be(config.tileY0());
        out.u16be(components);
        for (var c = 0; c < components; c++) {
            out.u8(depth(config, c));
            out.u8(config.subsamplingX()[c]);
            out.u8(config.subsamplingY()[c]);
        }
        if (highThroughput) {
            out.u16be(Codestream.CAP);
            out.u16be(8);
            out.u32be(Codestream.PCAP_PART15);
            out.u16be(capabilities(config, quantizations));
        }
        var precincts = config.precinctWidths() != null;
        var flags = (precincts ? 1 : 0) | (config.sop() ? 2 : 0) | (config.eph() ? 4 : 0);
        out.u16be(Codestream.COD);
        out.u16be(12 + (precincts ? config.resolutions() : 0));
        out.u8(flags);
        out.u8(config.progression());
        out.u16be(config.layers());
        out.u8(config.transform() ? 1 : 0);
        writeCoding(out, coding, precincts);
        out.u16be(Codestream.QCD);
        out.u16be(2 + quantizationLength(quantizations[0], config.resolutions()));
        writeQuantization(out, quantizations[0], config.resolutions());
        var componentBytes = components <= 256 ? 1 : 2;
        for (var c = 1; c < components; c++) {
            if (Arrays.equals(exponents(quantizations[c], config.resolutions()),
                    exponents(quantizations[0], config.resolutions()))) {
                continue;
            }
            out.u16be(Codestream.QCC);
            out.u16be(2 + componentBytes + quantizationLength(quantizations[c], config.resolutions()));
            if (componentBytes == 1) {
                out.u8(c);
            } else {
                out.u16be(c);
            }
            writeQuantization(out, quantizations[c], config.resolutions());
        }
        var tlm = -1;
        if (config.tlm()) {
            tlm = out.size();
            var wide = tileParts > 255;
            var perSegment = wide ? TLM_WIDE_ENTRIES : TLM_NARROW_ENTRIES;
            var entry = wide ? 6 : 5;
            var segments = (int) Math.ceilDiv(Math.max(tileParts, 1), perSegment);
            if (segments > 256) {
                throw new IllegalArgumentException(tileParts + " tile-parts exceed the 256 TLM segments of a "
                        + "codestream");
            }
            for (var s = 0; s < segments; s++) {
                var count = (int) Math.min(perSegment, tileParts - (long) s * perSegment);
                out.u16be(Codestream.TLM);
                out.u16be(4 + entry * count);
                out.u8(s);
                out.u8(wide ? 0x60 : 0x50);
                out.zeros(entry * count);
            }
        }
        if (config.changesInMainHeader() && !config.changes().isEmpty()) {
            out.bytes(poc(config));
        }
        var region = config.region();
        if (region != null && region.component() >= 0 && region.shift() > 0) {
            out.bytes(rgn(region.component(), region.shift(), components));
        }
        if (config.comment() != null) {
            var text = config.comment().getBytes(StandardCharsets.ISO_8859_1);
            out.u16be(Codestream.COM);
            out.u16be(4 + text.length);
            out.u16be(1);
            out.bytes(text);
        }
        return tlm;
    }

    /**
     * Fills the entries of the {@code TLM} segments: the tile index and length of every tile-part, in codestream
     * order.
     *
     * @param codestream the codestream, with the segments' entries zero
     * @param offset the offset of the first {@code TLM} segment
     * @param plans the tile-part plan of each tile
     * @param lengths the length of each tile-part, in codestream order
     */
    private static void fillTlm(byte[] codestream, int offset, TilePartPlan[] plans, long[] lengths) {
        var wide = lengths.length > 255;
        var perSegment = wide ? TLM_WIDE_ENTRIES : TLM_NARROW_ENTRIES;
        var at = offset;
        var index = 0;
        for (var t = 0; t < plans.length; t++) {
            for (var p = 0; p < plans[t].count(); p++) {
                if (index % perSegment == 0) {
                    at += 6;
                }
                if (wide) {
                    ByteArrays.put16be(codestream, at, t);
                    at += 2;
                } else {
                    codestream[at++] = (byte) t;
                }
                ByteArrays.put32be(codestream, at, lengths[index++]);
                at += 4;
            }
        }
    }

    /**
     * Builds the {@code POC} segment of the progression changes. The layer and resolution ends are written as
     * given and the component ends clamped to the component count, as {@code opj_compress} writes them.
     *
     * @param config the parameters
     * @return the segment
     */
    private static byte[] poc(EncoderConfig config) {
        var components = config.components();
        var room = components <= 256 ? 1 : 2;
        var changes = config.changes();
        var out = new ByteWriter(4 + (5 + 2 * room) * changes.size());
        out.u16be(Codestream.POC);
        out.u16be(2 + (5 + 2 * room) * changes.size());
        for (var change : changes) {
            out.u8(change.resolutionStart());
            component(out, change.componentStart(), room);
            out.u16be(change.layerEnd());
            out.u8(change.resolutionEnd());
            component(out, Math.min(change.componentEnd(), components), room);
            out.u8(change.order());
        }
        return out.toByteArray();
    }

    /**
     * Writes a component index in one or two bytes, a one-byte 256 as 0.
     *
     * @param out the stream
     * @param component the component index or end
     * @param room the field size, 1 or 2
     */
    private static void component(ByteWriter out, int component, int room) {
        if (room == 1) {
            out.u8(component & 0xFF);
        } else {
            out.u16be(component);
        }
    }

    /**
     * Builds an {@code RGN} segment of the max-shift method.
     *
     * @param component the component index
     * @param shift the scaling exponent
     * @param components the number of components
     * @return the segment
     */
    private static byte[] rgn(int component, int shift, int components) {
        var room = components <= 256 ? 1 : 2;
        var out = new ByteWriter(6 + room);
        out.u16be(Codestream.RGN);
        out.u16be(4 + room);
        component(out, component, room);
        out.u8(0);
        out.u8(shift);
        return out.toByteArray();
    }

    /**
     * Builds the {@code PLT} segments of a tile-part: the length of each packet in 7-bit groups, the most
     * significant first, every group but the last with its high bit set, a new segment starting before a length
     * that would take one past 65535 bytes.
     *
     * @param lengths the length of each packet, in codestream order
     * @return the segments, empty for a tile-part without packets
     * @throws IllegalArgumentException if the lengths need more than 256 segments
     */
    private static byte[] plt(int[] lengths) {
        var out = new ByteWriter(16 + 5 * lengths.length);
        var segment = 0;
        var start = out.size();
        out.u16be(Codestream.PLT);
        out.u16be(0);
        out.u8(segment);
        var length = 3;
        var groups = new int[5];
        for (var size : lengths) {
            var count = 0;
            var value = size;
            groups[count++] = value & 0x7F;
            value >>>= 7;
            while (value > 0) {
                groups[count++] = (value & 0x7F) | 0x80;
                value >>>= 7;
            }
            if (length + count > 65535) {
                if (segment == 255) {
                    throw new IllegalArgumentException("the packet lengths of a tile-part need more than 256 PLT "
                            + "segments");
                }
                ByteArrays.put16be(out.buffer(), start + 2, length);
                start = out.size();
                out.u16be(Codestream.PLT);
                out.u16be(0);
                out.u8(++segment);
                length = 3;
            }
            length += count;
            for (var g = count - 1; g >= 0; g--) {
                out.u8(groups[g]);
            }
        }
        ByteArrays.put16be(out.buffer(), start + 2, length);
        return out.toByteArray();
    }

    /**
     * Returns the {@code Ccap15} field of a codestream coded with the high-throughput block coder (ISO/IEC
     * 15444-15 Table A.2): every code-block an HT one, one HT set each, no region of interest, every
     * parameter in the main header, the irreversible flag, and the cleanup magnitude bound.
     *
     * @param config the parameters
     * @param quantizations the quantization of each component
     * @return the field
     */
    private static int capabilities(EncoderConfig config, Quantization[] quantizations) {
        var bound = magnitudeBound(config, quantizations);
        // the irreversible bound of A.3.7 is one bit lower for a sub-band without decomposition
        if (!config.reversible()) {
            bound++;
        }
        int field;
        if (bound <= 8) {
            field = 0;
        } else if (bound < 28) {
            field = bound - 8;
        } else {
            field = Math.min(31, 19 + Math.ceilDiv(bound - 27, 4));
        }
        return (config.reversible() ? 0 : 0x20) | field;
    }

    /**
     * Returns the largest number of magnitude bit-planes of a sub-band ({@code Mb} of equation E-2).
     *
     * @param config the parameters
     * @param quantizations the quantization of each component
     * @return the number of bit-planes
     */
    private static int magnitudeBound(EncoderConfig config, Quantization[] quantizations) {
        var bound = 0;
        var bands = 3 * config.resolutions() - 2;
        for (var quantization : quantizations) {
            for (var b = 0; b < bands; b++) {
                bound = Math.max(bound, quantization.exponent(b) + quantization.guardBits() - 1);
            }
        }
        return bound;
    }

    /**
     * Returns the exponents and mantissas of a quantization, for comparison.
     *
     * @param quantization the step sizes and quantization style
     * @param resolutions the number of resolutions
     * @return the step sizes of its bands
     */
    private static int[] exponents(Quantization quantization, int resolutions) {
        var bands = 3 * resolutions - 2;
        var values = new int[2 * bands];
        for (var b = 0; b < bands; b++) {
            values[2 * b] = quantization.exponent(b);
            values[2 * b + 1] = quantization.mantissa(b);
        }
        return values;
    }

    /**
     * Writes the {@code SPcod} fields.
     *
     * @param out the stream
     * @param coding the coding style
     * @param precincts whether precinct sizes are written
     */
    private static void writeCoding(ByteWriter out, ComponentCoding coding, boolean precincts) {
        out.u8(coding.resolutions() - 1);
        out.u8(coding.codeBlockWidth() - 2);
        out.u8(coding.codeBlockHeight() - 2);
        out.u8(coding.codeBlockStyle());
        out.u8(coding.reversible() ? 1 : 0);
        if (precincts) {
            for (var r = 0; r < coding.resolutions(); r++) {
                out.u8(coding.precinctWidth(r) + (coding.precinctHeight(r) << 4));
            }
        }
    }

    /**
     * Returns the length of the quantization fields.
     *
     * @param quantization the step sizes and quantization style
     * @param resolutions the number of resolutions
     * @return the length in bytes
     */
    private static int quantizationLength(Quantization quantization, int resolutions) {
        var bands = 3 * resolutions - 2;
        return 1 + (quantization.style() == Quantization.NONE ? bands : 2 * bands);
    }

    /**
     * Writes the {@code Sqcx} and {@code SPqcx} fields.
     *
     * @param out the stream
     * @param quantization the step sizes and quantization style
     * @param resolutions the number of resolutions
     */
    private static void writeQuantization(ByteWriter out, Quantization quantization, int resolutions) {
        var bands = 3 * resolutions - 2;
        out.u8(quantization.style() + (quantization.guardBits() << 5));
        for (var b = 0; b < bands; b++) {
            if (quantization.style() == Quantization.NONE) {
                out.u8(quantization.exponent(b) << 3);
            } else {
                out.u16be((quantization.exponent(b) << 11) + quantization.mantissa(b));
            }
        }
    }

    /**
     * Computes the byte budget of each layer of each tile (OpenJPEG's
     * {@code opj_j2k_update_rates}).
     *
     * @param config the parameters
     * @param header the length of the main header
     * @param plans the tile-part plan of each tile
     * @return the budget of each tile and layer, 0 for unconstrained layers
     */
    private static float[][] budgets(EncoderConfig config, int header, TilePartPlan[] plans) {
        var layers = config.layers();
        var tiles = plans.length;
        var budgets = new float[tiles][layers];
        var ratios = config.ratios();
        if (ratios == null) {
            if (config.capRatio() <= 0) {
                return budgets;
            }
            ratios = new double[layers];
            Arrays.fill(ratios, config.capRatio());
        }
        var dx = config.subsamplingX()[0];
        var dy = config.subsamplingY()[0];
        var bitsEmpty = 8 * dx * dy;
        var sizePixel = config.components() * config.precisions()[0];
        var remove = (float) header / (float) tiles;
        for (var t = 0; t < tiles; t++) {
            var bounds = config.tileBounds(t);
            var offset = config.tilePartSplit() == 0 ? 0.0f : (float) ((plans[t].count() - 1) * 14) / (float) layers;
            var rates = budgets[t];
            for (var k = 0; k < layers; k++) {
                var ratio = (float) ratios[k];
                if (ratio > 0.0f) {
                    rates[k] = (float) (((double) sizePixel * (bounds[2] - bounds[0]) * (bounds[3] - bounds[1]))
                            / (ratio * (float) bitsEmpty)) - offset;
                }
            }
            if (rates[0] > 0.0f) {
                rates[0] -= remove;
                if (rates[0] < 30.0f) {
                    rates[0] = 30.0f;
                }
            }
            for (var k = 1; k < layers - 1; k++) {
                if (rates[k] > 0.0f) {
                    rates[k] -= remove;
                    if (rates[k] < rates[k - 1] + 10.0f) {
                        rates[k] = rates[k - 1] + 20.0f;
                    }
                }
            }
            if (layers > 1 && rates[layers - 1] > 0.0f) {
                rates[layers - 1] -= remove + 2.0f;
                if (rates[layers - 1] < rates[layers - 2] + 10.0f) {
                    rates[layers - 1] = rates[layers - 2] + 20.0f;
                }
            }
        }
        return budgets;
    }

    /**
     * Encodes one tile into the bodies of its tile-parts.
     *
     * @param config the parameters
     * @param coding the coding style
     * @param quantizations the quantization of each component
     * @param planes the component samples
     * @param tile the tile index
     * @param plan the tile's tile-part plan
     * @param progressions the progressions of the tile
     * @param budgets the byte budget of each layer
     * @param limits the resource bounds checked before every allocation
     * @return the tile-parts
     */
    private static TileOutput encodeTile(EncoderConfig config, ComponentCoding coding, Quantization[] quantizations,
                                         int[][] planes, int tile, TilePartPlan plan,
                                         List<ProgressionChange> progressions, float[] budgets, MediaLimits limits) {
        var count = config.components();
        var bounds = config.tileBounds(tile);
        var components = new TileComponent[count];
        var integers = new int[count][];
        var floats = new float[count][];
        for (var c = 0; c < count; c++) {
            var dx = config.subsamplingX()[c];
            var dy = config.subsamplingY()[c];
            var cx0 = Math.ceilDiv(bounds[0], dx);
            var cy0 = Math.ceilDiv(bounds[1], dy);
            var cx1 = Math.ceilDiv(bounds[2], dx);
            var cy1 = Math.ceilDiv(bounds[3], dy);
            components[c] = new TileComponent(cx0, cy0, cx1, cy1, coding, quantizations[c], config.precisions()[c],
                    coding.resolutions(), true, limits);
            var width = (int) (cx1 - cx0);
            var height = (int) (cy1 - cy0);
            LimitChecks.checkAllocation(limits, (long) width * height * 4);
            var planeX0 = Math.ceilDiv(config.x0(), dx);
            var planeY0 = Math.ceilDiv(config.y0(), dy);
            var planeWidth = (int) (Math.ceilDiv(config.x1(), dx) - planeX0);
            var shift = config.signed()[c] ? 0 : 1 << (config.precisions()[c] - 1);
            var plane = planes[c];
            var left = (int) (cx0 - planeX0);
            var top = (int) (cy0 - planeY0);
            if (config.reversible()) {
                var samples = integers[c] = new int[width * height];
                for (var y = 0; y < height; y++) {
                    var source = (top + y) * planeWidth + left;
                    for (var x = 0; x < width; x++) {
                        samples[y * width + x] = plane[source + x] - shift;
                    }
                }
            } else {
                var samples = floats[c] = new float[width * height];
                for (var y = 0; y < height; y++) {
                    var source = (top + y) * planeWidth + left;
                    for (var x = 0; x < width; x++) {
                        samples[y * width + x] = (float) (plane[source + x] - shift);
                    }
                }
            }
        }
        var transform = config.transform() && count >= 3;
        if (transform) {
            forwardTransform(integers, floats, config.reversible());
        }
        for (var c = 0; c < count; c++) {
            var stride = (int) (components[c].x1 - components[c].x0);
            if (stride == 0 || components[c].y1 == components[c].y0) {
                continue;
            }
            if (config.reversible()) {
                Wavelet.forward53(integers[c], stride, components[c]);
            } else {
                Wavelet.forward97(floats[c], stride, components[c]);
            }
        }
        var region = regionOfInterest(config, components, integers, floats, limits);
        var order = new PacketOrder(bounds, components, config.subsamplingX(), config.subsamplingY(), config.layers(),
                progressions.size() > 1, limits);
        var highThroughput = (config.codeBlockStyle() & ComponentCoding.HIGH_THROUGHPUT) != 0;
        var blocks = highThroughput
                ? encodeHighThroughput(config, components, integers, floats, transform, order, progressions, budgets)
                : encodeBlocks(config, components, integers, floats, transform, region);
        var distortion = 0.0;
        for (var value : blocks) {
            distortion += value;
        }
        var layers = config.layers();
        for (var component : components) {
            forEachBlock(component, (band, block) -> block.ensureLayers(layers));
        }
        allocate(config, components, order, progressions, budgets, distortion);
        return writePackets(config, components, plan, order, progressions, region);
    }

    /**
     * Writes the packets of a coded tile into its tile-parts.
     *
     * @param config the parameters
     * @param components the tile-components, with their layers allocated
     * @param plan the tile's tile-part plan
     * @param order the tile's packet order
     * @param progressions the progressions of the tile
     * @param region the region of interest of the tile, or {@code null}
     * @return the tile-parts
     */
    private static TileOutput writePackets(EncoderConfig config, TileComponent[] components, TilePartPlan plan,
                                           PacketOrder order, List<ProgressionChange> progressions,
                                           TileRegion region) {
        var parts = plan.count();
        var streams = new ByteArrayOutputStream[parts];
        var lengths = new int[parts][];
        var counts = new int[parts];
        for (var p = 0; p < parts; p++) {
            streams[p] = new ByteArrayOutputStream();
            lengths[p] = new int[16];
        }
        var packets = new PacketEncoder(config.sop(), config.eph());
        order.reset();
        for (var i = 0; i < progressions.size(); i++) {
            var progression = i;
            order.list(progressions.get(i), new PacketOrder.Visitor() {
                @Override
                public boolean packet(int layer, int resolution, int component, int precinct) {
                    return packet(layer, resolution, component, precinct, -1, -1);
                }

                @Override
                public boolean packet(int layer, int resolution, int component, int precinct, long x, long y) {
                    var part = plan.part(progression, layer, resolution, component, precinct, x, y);
                    var length = packets.write(components[component], resolution, precinct, layer, streams[part]);
                    if (counts[part] == lengths[part].length) {
                        lengths[part] = Arrays.copyOf(lengths[part], counts[part] * 2);
                    }
                    lengths[part][counts[part]++] = length;
                    return true;
                }
            });
        }
        var bodies = new byte[parts][];
        for (var p = 0; p < parts; p++) {
            bodies[p] = streams[p].toByteArray();
            lengths[p] = Arrays.copyOf(lengths[p], counts[p]);
        }
        var header = new ByteWriter(64);
        if (!config.changesInMainHeader() && !config.changes().isEmpty()) {
            header.bytes(poc(config));
        }
        if (region != null) {
            for (var c = 0; c < components.length; c++) {
                if (region.shifts()[c] > 0 && region.masks()[c] != null) {
                    header.bytes(rgn(c, region.shifts()[c], components.length));
                }
            }
        }
        return new TileOutput(header.toByteArray(), bodies, lengths);
    }

    /**
     * Prepares the region of interest of a tile: for a whole component, its shift; for a rectangle, the mask of the
     * coefficients that reconstruct it in each tile-component it meets (ISO/IEC 15444-2 L.3.4) and its shift: one
     * bit more than the magnitude of every other coefficient of the tile-component needs, since decoders compare
     * the scaling threshold with magnitudes that carry one fractional bit, as OpenJPEG's {@code t1.c} does.
     *
     * @param config the parameters
     * @param components the tile-components, after the wavelet transform
     * @param integers the integer coefficients
     * @param floats the floating-point coefficients
     * @param limits the resource bounds checked before every allocation
     * @return the region of the tile, or {@code null} for none
     * @throws IllegalArgumentException if a scaled coefficient would need more than 25 magnitude bit-planes
     */
    private static TileRegion regionOfInterest(EncoderConfig config, TileComponent[] components, int[][] integers,
                                               float[][] floats, MediaLimits limits) {
        var region = config.region();
        if (region == null) {
            return null;
        }
        var count = components.length;
        var masks = new byte[count][];
        var shifts = new int[count];
        var whole = new boolean[count];
        if (region.component() >= 0) {
            if (region.component() < count) {
                shifts[region.component()] = region.shift();
                whole[region.component()] = true;
            }
            return new TileRegion(masks, shifts, whole);
        }
        var rectangle = List.of(new HeaderScope.RoiRegion(false, 1, region.x0(), region.y0(),
                region.x1() - region.x0(), region.y1() - region.y0()));
        for (var c = 0; c < count; c++) {
            var component = components[c];
            if (component.x1 == component.x0 || component.y1 == component.y0) {
                continue;
            }
            var mask = RoiMasks.scales(component, rectangle, config.subsamplingX()[c], config.subsamplingY()[c],
                    limits);
            var inside = false;
            for (var value : mask) {
                if (value != 0) {
                    inside = true;
                    break;
                }
            }
            if (!inside) {
                continue;
            }
            var magnitudes = magnitudes(component, integers[c], floats[c], mask, config.reversible());
            if (magnitudes[0] > 0) {
                var shift = bits(magnitudes[0]) + 1;
                if (bits(magnitudes[1]) + shift > ROI_MAX_BIT_PLANES) {
                    throw new IllegalArgumentException("the region of interest scales coefficients of component " + c
                            + " to " + (bits(magnitudes[1]) + shift) + " magnitude bit-planes, above "
                            + ROI_MAX_BIT_PLANES);
                }
                masks[c] = mask;
                shifts[c] = shift;
            }
        }
        return new TileRegion(masks, shifts, whole);
    }

    /**
     * Returns the number of bits of a non-negative value.
     *
     * @param value a non-negative number
     * @return the position of its highest set bit plus one, 0 for 0
     */
    private static int bits(int value) {
        return 32 - Integer.numberOfLeadingZeros(value);
    }

    /**
     * Returns the largest magnitudes of the quantized coefficients outside and inside the mask of a region, as the
     * block coder codes them.
     *
     * @param component the tile-component
     * @param integers the integer coefficients of the 5-3 wavelet, or {@code null}
     * @param floats the floating-point coefficients of the 9-7 wavelet, or {@code null}
     * @param mask the mask, nonzero for a coefficient of the region
     * @param reversible whether the 5-3 wavelet is used
     * @return the largest magnitude of the background, then of the region
     */
    private static int[] magnitudes(TileComponent component, int[] integers, float[] floats, byte[] mask,
                                    boolean reversible) {
        var stride = (int) (component.x1 - component.x0);
        var result = new int[2];
        for (var level : component.resolutions) {
            for (var band : level.bands()) {
                if (band.empty()) {
                    continue;
                }
                var width = (int) (band.x1() - band.x0());
                var height = (int) (band.y1() - band.y0());
                var step = band.stepSize();
                var limit = reversible || band.bitPlanes() >= 31 ? Integer.MAX_VALUE : (1 << band.bitPlanes()) - 1;
                for (var y = 0; y < height; y++) {
                    var row = (band.offsetY() + y) * stride + band.offsetX();
                    for (var x = 0; x < width; x++) {
                        var at = row + x;
                        int magnitude;
                        if (reversible) {
                            magnitude = Math.abs(integers[at]);
                        } else {
                            var value = (long) Math.rint((floats[at] / step) * (1 << Tier1Tables.NMSEDEC_FRACBITS));
                            magnitude = (int) Math.min(Math.abs(value) >> Tier1Tables.NMSEDEC_FRACBITS, limit);
                        }
                        var inside = mask[at] != 0 ? 1 : 0;
                        result[inside] = Math.max(result[inside], magnitude);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Returns how much of a code-block a region of interest mask covers.
     *
     * @param mask the mask, in the layout of the coefficients
     * @param offset the index of the block's first coefficient
     * @param stride the row stride of the coefficients
     * @param width the block width
     * @param height the block height
     * @return 0 for none of it, 1 for part of it, 2 for all of it
     */
    private static int coverage(byte[] mask, int offset, int stride, int width, int height) {
        var inside = 0;
        for (var y = 0; y < height; y++) {
            var row = offset + y * stride;
            for (var x = 0; x < width; x++) {
                if (mask[row + x] != 0) {
                    inside++;
                }
            }
        }
        return inside == 0 ? 0 : inside == width * height ? 2 : 1;
    }

    /**
     * Applies the forward multiple component transformation to the first three components.
     *
     * @param integers the integer samples of the reversible components
     * @param floats the floating-point samples of the irreversible components
     * @param reversible whether the reversible transform is applied
     */
    private static void forwardTransform(int[][] integers, float[][] floats, boolean reversible) {
        if (reversible) {
            var r = integers[0];
            var g = integers[1];
            var b = integers[2];
            for (var i = 0; i < r.length; i++) {
                var red = r[i];
                var green = g[i];
                var blue = b[i];
                r[i] = (red + (green * 2) + blue) >> 2;
                g[i] = blue - green;
                b[i] = red - green;
            }
        } else {
            var r = floats[0];
            var g = floats[1];
            var b = floats[2];
            for (var i = 0; i < r.length; i++) {
                var red = r[i];
                var green = g[i];
                var blue = b[i];
                r[i] = 0.299f * red + 0.587f * green + 0.114f * blue;
                g[i] = -0.16875f * red - 0.331260f * green + 0.5f * blue;
                b[i] = 0.5f * red - 0.41869f * green - 0.08131f * blue;
            }
        }
    }

    /**
     * Visits every code-block of the non-empty sub-bands of a tile-component.
     */
    @FunctionalInterface
    private interface BlockVisitor {
        /**
         * Receives one code-block.
         *
         * @param band the sub-band
         * @param block the code-block
         */
        void visit(Band band, EncodedBlock block);
    }

    /**
     * Visits every code-block of a tile-component in codestream order.
     *
     * @param component the tile-component
     * @param visitor the callback
     */
    private static void forEachBlock(TileComponent component, BlockVisitor visitor) {
        for (var level : component.resolutions) {
            for (var band : level.bands()) {
                if (band.empty()) {
                    continue;
                }
                for (var precinct : band.precincts()) {
                    for (var block : precinct.encodedBlocks) {
                        visitor.visit(band, block);
                    }
                }
            }
        }
    }

    /**
     * Codes every code-block of a tile.
     *
     * <p>Under a region of interest, a code-block whose coefficients all belong to the region is coded as it is,
     * its distortion weighed by the region's scaling, and the decoder's scaling of its bit-planes restores it; a
     * block that the region covers in part is coded with the region's coefficients scaled up; and a block outside
     * the region is coded as it is, its bit-planes counted from the scaled bound.
     *
     * @param config the parameters
     * @param components the tile-components
     * @param integers the integer coefficients
     * @param floats the floating-point coefficients
     * @param transform whether the component transform was applied
     * @param region the region of interest of the tile, or {@code null}
     * @return the weighted distortion reduction of each block, in codestream order
     * @throws IllegalArgumentException if the guard bits leave a reversible sub-band fewer bit-planes than its
     * coefficients need
     */
    private static double[] encodeBlocks(EncoderConfig config, TileComponent[] components, int[][] integers,
                                         float[][] floats, boolean transform, TileRegion region) {
        var jobs = new ArrayList<Job>();
        var work = 0L;
        for (var c = 0; c < components.length; c++) {
            var component = components[c];
            for (var r = 0; r < component.resolutions.length; r++) {
                for (var band : component.resolutions[r].bands()) {
                    if (band.empty()) {
                        continue;
                    }
                    for (var precinct : band.precincts()) {
                        for (var block : precinct.encodedBlocks) {
                            jobs.add(new Job(c, r, band, block));
                            work += (long) (block.x1 - block.x0) * (block.y1 - block.y0) * SAMPLE_WORK;
                        }
                    }
                }
            }
        }
        var distortions = new double[jobs.size()];
        if (jobs.isEmpty()) {
            return distortions;
        }
        var bands = ParallelBands.bands(jobs.size(), work);
        ParallelBands.run(bands, index -> {
            var coder = new Tier1Encoder();
            var end = ParallelBands.start(index + 1, bands, jobs.size());
            for (var j = ParallelBands.start(index, bands, jobs.size()); j < end; j++) {
                var job = jobs.get(j);
                var c = job.component();
                var r = job.resolution();
                var band = job.band();
                var block = job.block();
                var component = components[c];
                var x = (int) (block.x0 - band.x0()) + band.offsetX();
                var y = (int) (block.y0 - band.y0()) + band.offsetY();
                var stride = (int) (component.x1 - component.x0);
                var weight = transform && c < 3 ? (config.reversible() ? RCT_NORMS : ICT_NORMS)[c] : 1.0;
                var shift = region == null ? 0 : region.shifts()[c];
                byte[] mask = null;
                block.roiPlanes = 0;
                if (shift > 0) {
                    var coverage = region.whole()[c] ? 2 : coverage(region.masks()[c], y * stride + x, stride,
                            (int) (block.x1 - block.x0), (int) (block.y1 - block.y0));
                    if (coverage == 2) {
                        weight *= StrictMath.scalb(1.0, shift);
                    } else {
                        block.roiPlanes = shift;
                        mask = coverage == 1 ? region.masks()[c] : null;
                    }
                }
                distortions[j] = coder.encode(block, integers[c], floats[c], y * stride + x, stride, band.orientation(),
                        component.resolutions.length - 1 - r, band.stepSize(), config.reversible(),
                        config.codeBlockStyle(), weight, mask, shift, config.reversible() ? 0 : band.bitPlanes());
            }
        });
        for (var job : jobs) {
            var block = job.block();
            if (block.bitPlanes > job.band().bitPlanes() + block.roiPlanes) {
                throw new IllegalArgumentException(config.guardBits() + " guard bits leave a sub-band of component "
                        + job.component() + " " + job.band().bitPlanes() + " magnitude bit-planes, and its "
                        + "coefficients need " + (block.bitPlanes - block.roiPlanes));
            }
        }
        return distortions;
    }

    /**
     * Codes every code-block of a tile with the high-throughput block coder (ISO/IEC 15444-15), one HT set per
     * block.
     *
     * <p>When the last quality layer takes every pass, each block is coded at full precision with a cleanup pass
     * alone. Otherwise each block is first coded with a cleanup pass at every bit-plane, alone and followed by the
     * SigProp and MagRef passes of the bit-plane below; the candidates on the convex hull of each block's
     * rate-distortion points then compete under one slope threshold, set so that the packets of the selected
     * candidates fit the last layer's byte budget or reach its PSNR target, and each block is coded once more with
     * its selected candidate.
     *
     * @param config the parameters
     * @param components the tile-components
     * @param integers the integer coefficients
     * @param floats the floating-point coefficients
     * @param transform whether the component transform was applied
     * @param order the tile's packet order
     * @param progressions the progressions of the tile
     * @param budgets the byte budget of each layer
     * @return the weighted distortion reduction of each block at full precision, in codestream order
     */
    private static double[] encodeHighThroughput(EncoderConfig config, TileComponent[] components, int[][] integers,
                                                 float[][] floats, boolean transform, PacketOrder order,
                                                 List<ProgressionChange> progressions, float[] budgets) {
        var jobs = new ArrayList<Job>();
        var work = 0L;
        for (var c = 0; c < components.length; c++) {
            var component = components[c];
            for (var r = 0; r < component.resolutions.length; r++) {
                for (var band : component.resolutions[r].bands()) {
                    if (band.empty()) {
                        continue;
                    }
                    for (var precinct : band.precincts()) {
                        for (var block : precinct.encodedBlocks) {
                            jobs.add(new Job(c, r, band, block));
                            work += (long) (block.x1 - block.x0) * (block.y1 - block.y0) * SAMPLE_WORK;
                        }
                    }
                }
            }
        }
        var distortions = new double[jobs.size()];
        if (jobs.isEmpty()) {
            return distortions;
        }
        var last = config.layers() - 1;
        var constrained = config.ratios() != null ? budgets[last] > 0.0f : config.qualities()[last] > 0.0;
        var bands = ParallelBands.bands(jobs.size(), work * (constrained ? 8 : 1));
        ParallelBands.run(bands, index -> {
            var coder = new HtEncoder();
            var rates = new int[3];
            var gains = new double[3];
            var end = ParallelBands.start(index + 1, bands, jobs.size());
            for (var j = ParallelBands.start(index, bands, jobs.size()); j < end; j++) {
                var job = jobs.get(j);
                var block = job.block();
                var planes = load(coder, config, components, integers, floats, transform, job);
                block.resetCandidates(2 * planes);
                if (planes == 0) {
                    block.totalPasses = 0;
                    continue;
                }
                if (!constrained) {
                    coder.encode(0, false);
                    keep(block, coder, 0, 1);
                    distortions[j] = coder.distortion(0);
                    continue;
                }
                for (var plane = 0; plane < planes; plane++) {
                    var refine = plane > 0;
                    coder.encode(plane, refine);
                    var cleanup = coder.cleanupLength();
                    rates[0] = cleanup;
                    rates[1] = cleanup + coder.sigPropLength();
                    rates[2] = rates[1] + coder.magRefLength();
                    gains[0] = coder.distortion(0);
                    gains[1] = refine ? coder.distortion(1) : gains[0];
                    gains[2] = refine ? coder.distortion(2) : gains[0];
                    block.addCandidate(plane, 1, rates, gains);
                    if (refine) {
                        block.addCandidate(plane, 3, rates, gains);
                    }
                    if (plane == 0) {
                        distortions[j] = gains[0];
                    }
                }
                block.buildHull();
            }
        });
        if (!constrained) {
            return distortions;
        }
        selectHighThroughput(config, components, order, progressions, budgets[last], distortions);
        ParallelBands.run(bands, index -> {
            var coder = new HtEncoder();
            var end = ParallelBands.start(index + 1, bands, jobs.size());
            for (var j = ParallelBands.start(index, bands, jobs.size()); j < end; j++) {
                var job = jobs.get(j);
                var block = job.block();
                var chosen = block.chosen;
                if (chosen < 0) {
                    block.totalPasses = 0;
                    continue;
                }
                load(coder, config, components, integers, floats, transform, job);
                var plane = block.candidatePlane[chosen];
                var passes = block.candidatePasses[chosen];
                coder.encode(plane, passes > 1);
                keep(block, coder, plane, passes);
            }
        });
        return distortions;
    }

    /**
     * Loads the coefficients of a code-block into a high-throughput coder.
     *
     * @param coder the high-throughput coder
     * @param config the parameters
     * @param components the tile-components
     * @param integers the integer coefficients
     * @param floats the floating-point coefficients
     * @param transform whether the component transform was applied
     * @param job the code-block and its location
     * @return the number of magnitude bit-planes of its largest coefficient
     */
    private static int load(HtEncoder coder, EncoderConfig config, TileComponent[] components, int[][] integers,
                            float[][] floats, boolean transform, Job job) {
        var c = job.component();
        var r = job.resolution();
        var band = job.band();
        var block = job.block();
        var component = components[c];
        var x = (int) (block.x0 - band.x0()) + band.offsetX();
        var y = (int) (block.y0 - band.y0()) + band.offsetY();
        var stride = (int) (component.x1 - component.x0);
        var weight = transform && c < 3 ? (config.reversible() ? RCT_NORMS : ICT_NORMS)[c] : 1.0;
        var level = component.resolutions.length - 1 - r;
        var scale = Tier1Encoder.distortionScale(level, band.orientation(), config.reversible(), band.stepSize(),
                weight);
        var planes = coder.load(integers[c], floats[c], y * stride + x, stride, (int) (block.x1 - block.x0),
                (int) (block.y1 - block.y0), band.stepSize(), config.reversible(), scale,
                (config.codeBlockStyle() & ComponentCoding.VERTICALLY_CAUSAL) != 0);
        return planes;
    }

    /**
     * Gives a code-block the passes a high-throughput coder just coded.
     *
     * @param block the code-block
     * @param coder the high-throughput coder
     * @param plane the cleanup pass's bit-plane
     * @param passes the number of passes, 1 or 3
     */
    private static void keep(EncodedBlock block, HtEncoder coder, int plane, int passes) {
        block.ensurePasses(3);
        var cleanup = coder.cleanupLength();
        var length = passes > 1 ? cleanup + coder.sigPropLength() + coder.magRefLength() : cleanup;
        block.data = Arrays.copyOf(coder.bytes(), length);
        block.totalPasses = passes;
        block.bitPlanes = plane + 1;
        block.passRate[0] = cleanup;
        block.passDistortion[0] = coder.distortion(0);
        block.passTerminated[0] = true;
        if (passes > 1) {
            block.passRate[1] = cleanup + coder.sigPropLength();
            block.passRate[2] = length;
            block.passDistortion[1] = coder.distortion(1);
            block.passDistortion[2] = coder.distortion(2);
            block.passTerminated[1] = false;
            block.passTerminated[2] = true;
        }
    }

    /**
     * Selects the candidate of every high-throughput code-block of a tile for the last quality layer, by
     * bisection on a rate-distortion slope threshold.
     *
     * @param config the parameters
     * @param components the tile-components
     * @param order the tile's packet order
     * @param progressions the progressions of the tile
     * @param budget the last layer's byte budget, used when layers target compression ratios
     * @param distortions the full-precision distortion reduction of each block
     */
    private static void selectHighThroughput(EncoderConfig config, TileComponent[] components, PacketOrder order,
                                             List<ProgressionChange> progressions, float budget,
                                             double[] distortions) {
        var blocks = new ArrayList<EncodedBlock>();
        var maxSlope = 0.0;
        for (var component : components) {
            forEachBlock(component, (band, block) -> {
                if (block.hullSize > 0) {
                    blocks.add(block);
                }
            });
        }
        for (var block : blocks) {
            var first = block.hull[0];
            maxSlope = Math.max(maxSlope, block.candidateGain(first) / block.candidateBytes(first));
        }
        var hi = maxSlope * 2 + 1;
        if (config.ratios() == null) {
            var total = 0.0;
            for (var value : distortions) {
                total += value;
            }
            var target = total - maxSquaredError(config, components)
                    / StrictMath.pow(10.0, config.qualities()[config.layers() - 1] / 10);
            var lo = 0.0;
            for (var i = 0; i < 100; i++) {
                var threshold = (lo + hi) / 2;
                var gain = 0.0;
                for (var block : blocks) {
                    var c = block.select(threshold);
                    gain += c < 0 ? 0 : block.candidateGain(c);
                }
                if (gain >= target) {
                    lo = threshold;
                } else {
                    hi = threshold;
                }
            }
            for (var block : blocks) {
                block.chosen = block.select(lo);
            }
            return;
        }
        var limit = (double) budget;
        for (var round = 0; round < SELECTION_ROUNDS; round++) {
            var lo = 0.0;
            var top = hi;
            for (var i = 0; i < 100; i++) {
                var threshold = (lo + top) / 2;
                var rate = 0L;
                for (var block : blocks) {
                    var c = block.select(threshold);
                    rate += c < 0 ? 0 : block.candidateBytes(c);
                }
                if (rate <= limit) {
                    top = threshold;
                } else {
                    lo = threshold;
                }
            }
            var positions = new int[blocks.size()];
            var rate = 0L;
            for (var b = 0; b < blocks.size(); b++) {
                var block = blocks.get(b);
                positions[b] = block.hullPosition(top);
                rate += positions[b] < 0 ? 0 : block.candidateBytes(block.hull[positions[b]]);
            }
            fill(blocks, positions, (long) limit - rate);
            for (var b = 0; b < blocks.size(); b++) {
                var block = blocks.get(b);
                block.chosen = positions[b] < 0 ? -1 : block.hull[positions[b]];
            }
            var size = measureCandidates(config, components, order, progressions);
            if (size <= budget) {
                return;
            }
            limit -= size - budget + 1;
        }
    }

    /**
     * Spends the bytes a slope threshold leaves under a budget: repeatedly moves the code-block whose next hull
     * step has the steepest slope and still fits to that step.
     *
     * @param blocks the code-blocks with candidates
     * @param positions the hull position each block has, {@code -1} for none; updated
     * @param remaining the bytes left
     */
    private static void fill(List<EncodedBlock> blocks, int[] positions, long remaining) {
        var queue = new PriorityQueue<Integer>(Math.max(1, blocks.size()),
                (a, b) -> Double.compare(nextSlope(blocks.get(b), positions[b]),
                        nextSlope(blocks.get(a), positions[a])));
        for (var b = 0; b < blocks.size(); b++) {
            if (positions[b] + 1 < blocks.get(b).hullSize) {
                queue.add(b);
            }
        }
        while (!queue.isEmpty() && remaining > 0) {
            var b = queue.poll();
            var block = blocks.get(b);
            var next = block.hull[positions[b] + 1];
            var current = positions[b] < 0 ? 0 : block.candidateBytes(block.hull[positions[b]]);
            var bytes = block.candidateBytes(next) - current;
            if (bytes > remaining) {
                continue;
            }
            remaining -= bytes;
            positions[b]++;
            if (positions[b] + 1 < block.hullSize) {
                queue.add(b);
            }
        }
    }

    /**
     * Returns the rate-distortion slope of a code-block's next hull step.
     *
     * @param block the code-block
     * @param position its hull position, {@code -1} for none
     * @return the slope
     */
    private static double nextSlope(EncodedBlock block, int position) {
        var next = block.hull[position + 1];
        var rate = position < 0 ? 0 : block.candidateBytes(block.hull[position]);
        var gain = position < 0 ? 0 : block.candidateGain(block.hull[position]);
        return (block.candidateGain(next) - gain) / (block.candidateBytes(next) - rate);
    }

    /**
     * Measures the packets of a tile whose high-throughput code-blocks hold their chosen candidates in one layer.
     *
     * @param config the parameters
     * @param components the tile-components
     * @param order the tile's packet order
     * @param progressions the progressions of the tile
     * @return the packets' length
     */
    private static long measureCandidates(EncoderConfig config, TileComponent[] components, PacketOrder order,
                                          List<ProgressionChange> progressions) {
        for (var component : components) {
            forEachBlock(component, (band, block) -> {
                block.ensureLayers(config.layers());
                var c = block.chosen;
                if (c < 0) {
                    block.totalPasses = 0;
                    return;
                }
                var passes = block.candidatePasses[c];
                block.ensurePasses(3);
                block.totalPasses = passes;
                for (var p = 0; p < passes; p++) {
                    block.passRate[p] = block.candidateRate[3 * c + p];
                    block.passDistortion[p] = block.candidateDistortion[3 * c + p];
                }
                block.passTerminated[0] = true;
                block.passTerminated[1] = false;
                block.passTerminated[2] = true;
                block.bitPlanes = block.candidatePlane[c] + 1;
            });
        }
        makeLayer(components, 0, -1, false, new double[config.layers()]);
        return measure(config, components, order, progressions, 1, Long.MAX_VALUE);
    }

    /**
     * Returns the sum, over a tile's components, of the largest squared error of a sample times the component's
     * sample count, the reference of PSNR targets.
     *
     * @param config the parameters
     * @param components the tile-components
     * @return the sum
     */
    private static double maxSquaredError(EncoderConfig config, TileComponent[] components) {
        var total = 0.0;
        for (var c = 0; c < components.length; c++) {
            var pixels = new long[1];
            forEachBlock(components[c], (band, block) ->
                    pixels[0] += (long) (block.x1 - block.x0) * (block.y1 - block.y0));
            var peak = (double) (1 << config.precisions()[c]) - 1.0;
            total += peak * peak * (double) pixels[0];
        }
        return total;
    }

    /**
     * Allocates the coding passes of every code-block to the quality layers (OpenJPEG's
     * {@code opj_tcd_rateallocate}).
     *
     * <p>A layer with a byte budget, or under a limit of the packets of each component, takes the passes of the
     * lowest slope threshold whose packets fit; a layer with a PSNR target the passes of the highest threshold that
     * reaches it, or of the threshold that fits, whichever is higher.
     *
     * @param config the parameters
     * @param components the tile-components
     * @param order the tile's packet order
     * @param progressions the progressions of the tile
     * @param budgets the byte budget of each layer
     * @param distortion the tile's total distortion reduction
     */
    private static void allocate(EncoderConfig config, TileComponent[] components, PacketOrder order,
                                 List<ProgressionChange> progressions, float[] budgets, double distortion) {
        var min = Double.MAX_VALUE;
        var max = 0.0;
        var maxSquaredError = 0.0;
        for (var c = 0; c < components.length; c++) {
            var pixels = new long[1];
            var slopes = new double[]{min, max};
            forEachBlock(components[c], (band, block) -> {
                for (var pass = 0; pass < block.totalPasses; pass++) {
                    var rate = block.passLength(pass);
                    var gain = block.passDistortion[pass] - (pass == 0 ? 0 : block.passDistortion[pass - 1]);
                    if (rate == 0) {
                        continue;
                    }
                    var slope = gain / rate;
                    slopes[0] = Math.min(slopes[0], slope);
                    slopes[1] = Math.max(slopes[1], slope);
                }
                pixels[0] += (long) (block.x1 - block.x0) * (block.y1 - block.y0);
            });
            min = slopes[0];
            max = slopes[1];
            var peak = (double) (1 << config.precisions()[c]) - 1.0;
            maxSquaredError += peak * peak * (double) pixels[0];
        }
        var layers = config.layers();
        var layerDistortion = new double[layers];
        var cumulative = new double[layers];
        var rateMode = config.ratios() != null;
        for (var layer = 0; layer < layers; layer++) {
            var maxLength = budgets[layer] > 0.0f ? (long) Math.ceil(budgets[layer]) : Long.MAX_VALUE;
            var sized = maxLength != Long.MAX_VALUE || config.maxComponentBytes() > 0;
            var quality = rateMode ? 0.0 : config.qualities()[layer];
            var threshold = -1.0;
            if (!rateMode && quality > 0.0) {
                var target = distortion - (maxSquaredError / StrictMath.pow(10.0f, (float) quality / 10));
                threshold = bisect(config, components, order, progressions, layer, min, max, false, target,
                        maxLength, layerDistortion, cumulative);
            }
            // a layer under the component limit alone keeps its threshold when the limit does not bind
            if (sized && (maxLength != Long.MAX_VALUE
                    || !fitsAt(config, components, order, progressions, layer, threshold, layerDistortion))) {
                threshold = Math.max(threshold, bisect(config, components, order, progressions, layer, min, max, true,
                        0, maxLength, layerDistortion, cumulative));
            }
            makeLayer(components, layer, threshold, true, layerDistortion);
            cumulative[layer] = layer == 0 ? layerDistortion[0] : cumulative[layer - 1] + layerDistortion[layer];
        }
    }

    /**
     * Searches by bisection the rate-distortion slope threshold of a layer: the lowest one whose packets fit the
     * layer's budget and the component limit, or the highest one that reaches a distortion target.
     *
     * @param config the parameters
     * @param components the tile-components
     * @param order the tile's packet order
     * @param progressions the progressions of the tile
     * @param layer the quality layer
     * @param min the lowest slope of a pass
     * @param max the highest slope of a pass
     * @param rate whether the threshold must fit the budget rather than reach the target
     * @param target the cumulative distortion reduction to reach
     * @param maxLength the layer's budget, {@link Long#MAX_VALUE} for none
     * @param layerDistortion receives each layer's distortion reduction
     * @param cumulative the cumulative distortion reduction of the earlier layers
     * @return the threshold
     */
    private static double bisect(EncoderConfig config, TileComponent[] components, PacketOrder order,
                                 List<ProgressionChange> progressions, int layer, double min, double max, boolean rate,
                                 double target, long maxLength, double[] layerDistortion, double[] cumulative) {
        var lo = min;
        var hi = max;
        var current = 0.0;
        var stable = 0.0;
        var lastFits = false;
        for (var i = 0; i < 128; i++) {
            var next = (lo + hi) / 2;
            if (Math.abs(next - current) <= 0.5 * 1e-5 * current) {
                break;
            }
            current = next;
            var same = makeLayer(components, layer, current, false, layerDistortion) && i != 0;
            if (!rate) {
                var achieved = layer == 0 ? layerDistortion[0] : cumulative[layer - 1] + layerDistortion[layer];
                if (achieved < target) {
                    hi = current;
                    stable = current;
                    continue;
                }
                lo = current;
            } else {
                if ((same && !lastFits) || (!same && !fits(measure(config, components, order, progressions,
                        layer + 1, maxLength), maxLength))) {
                    lastFits = false;
                    lo = current;
                    continue;
                }
                lastFits = true;
                hi = current;
                stable = current;
            }
        }
        return stable == 0 ? current : stable;
    }

    /**
     * Returns whether the packets of the first layers of a tile, a layer holding the passes of a slope threshold,
     * keep every component within the component limit.
     *
     * @param config the parameters
     * @param components the tile-components
     * @param order the tile's packet order
     * @param progressions the progressions of the tile
     * @param layer the quality layer
     * @param threshold the slope threshold, or a negative value for every pass left
     * @param layerDistortion receives each layer's distortion reduction
     * @return whether the packets fit
     */
    private static boolean fitsAt(EncoderConfig config, TileComponent[] components, PacketOrder order,
                                  List<ProgressionChange> progressions, int layer, double threshold,
                                  double[] layerDistortion) {
        makeLayer(components, layer, threshold, false, layerDistortion);
        return fits(measure(config, components, order, progressions, layer + 1, Long.MAX_VALUE), Long.MAX_VALUE);
    }

    /**
     * Returns whether a measured length fits a budget.
     *
     * @param length the length {@link #measure} returned
     * @param budget the budget, {@link Long#MAX_VALUE} for none
     * @return whether no component exceeded its limit and the length is within the budget
     */
    private static boolean fits(long length, long budget) {
        return length != Long.MAX_VALUE && length <= budget;
    }

    /**
     * Measures the packets of the first layers of a tile.
     *
     * @param config the parameters
     * @param components the tile-components
     * @param order the tile's packet order
     * @param progressions the progressions of the tile
     * @param layers the number of layers to measure
     * @param limit the length beyond which measuring stops
     * @return the packets' length, or {@link Long#MAX_VALUE} when the packets of a component exceed the component
     * limit, or another value above {@code limit}
     */
    private static long measure(EncoderConfig config, TileComponent[] components, PacketOrder order,
                                List<ProgressionChange> progressions, int layers, long limit) {
        var packets = new PacketEncoder(config.sop(), config.eph());
        var componentLimit = config.maxComponentBytes() > 0 ? config.maxComponentBytes() : Long.MAX_VALUE;
        var perComponent = new long[components.length];
        var total = new long[1];
        PacketOrder.Visitor visitor = (layer, resolution, component, precinct) -> {
            var length = packets.write(components[component], resolution, precinct, layer, null);
            total[0] += length;
            perComponent[component] += length;
            if (perComponent[component] > componentLimit) {
                total[0] = Long.MAX_VALUE;
                return false;
            }
            return total[0] <= limit;
        };
        order.reset();
        for (var progression : progressions) {
            var bounded = new ProgressionChange(progression.resolutionStart(), progression.componentStart(),
                    Math.min(progression.layerEnd(), layers), progression.resolutionEnd(),
                    progression.componentEnd(), progression.order());
            if (!order.list(bounded, visitor)) {
                break;
            }
        }
        return total[0];
    }

    /**
     * Allocates the passes of every code-block whose rate-distortion slope reaches a threshold to
     * a layer (OpenJPEG's {@code opj_tcd_makelayer}).
     *
     * @param components the tile-components
     * @param layer the quality layer, from 0
     * @param threshold the slope threshold, or a negative value to include every pass left
     * @param last whether the allocation is final
     * @param distortion receives the layer's distortion reduction
     * @return whether no block's allocation changed since the previous call
     */
    private static boolean makeLayer(TileComponent[] components, int layer, double threshold, boolean last,
                                     double[] distortion) {
        var same = new boolean[]{true};
        distortion[layer] = 0;
        for (var component : components) {
            forEachBlock(component, (band, block) -> {
                if (layer == 0) {
                    block.passesInLayers = 0;
                }
                var n = block.passesInLayers;
                if (threshold < 0) {
                    n = block.totalPasses;
                } else {
                    for (var pass = block.passesInLayers; pass < block.totalPasses; pass++) {
                        int rate;
                        double gain;
                        if (n == 0) {
                            rate = block.passRate[pass];
                            gain = block.passDistortion[pass];
                        } else {
                            rate = block.passRate[pass] - block.passRate[n - 1];
                            gain = block.passDistortion[pass] - block.passDistortion[n - 1];
                        }
                        if (rate == 0) {
                            if (gain != 0) {
                                n = pass + 1;
                            }
                            continue;
                        }
                        if (threshold - (gain / rate) < Math.ulp(1.0)) {
                            n = pass + 1;
                        }
                    }
                }
                var passes = n - block.passesInLayers;
                if (block.layerPasses[layer] != passes) {
                    same[0] = false;
                    block.layerPasses[layer] = passes;
                }
                if (passes == 0) {
                    block.layerDistortion[layer] = 0;
                    return;
                }
                if (block.passesInLayers == 0) {
                    block.layerLength[layer] = block.passRate[n - 1];
                    block.layerOffset[layer] = 0;
                    block.layerDistortion[layer] = block.passDistortion[n - 1];
                } else {
                    var previous = block.passesInLayers - 1;
                    block.layerLength[layer] = block.passRate[n - 1] - block.passRate[previous];
                    block.layerOffset[layer] = block.passRate[previous];
                    block.layerDistortion[layer] = block.passDistortion[n - 1] - block.passDistortion[previous];
                }
                distortion[layer] += block.layerDistortion[layer];
                if (last) {
                    block.passesInLayers = n;
                }
            });
        }
        return same[0];
    }

    /**
     * Holds the region of interest of one tile.
     *
     * @param masks the mask of each tile-component a rectangle meets, in the layout of its coefficients, or
     * {@code null}
     * @param shifts the scaling exponent of each tile-component, 0 for none
     * @param whole whether each tile-component is scaled whole
     */
    private record TileRegion(byte[][] masks, int[] shifts, boolean[] whole) {
    }

    /**
     * Holds the coded tile-parts of one tile.
     *
     * @param header the segments of the first tile-part's header, after {@code SOT}
     * @param bodies the packets of each tile-part
     * @param packetLengths the length of each packet of each tile-part
     */
    private record TileOutput(byte[] header, byte[][] bodies, int[][] packetLengths) {
    }

    /**
     * Identifies one code-block to code.
     *
     * @param component the component index
     * @param resolution the resolution level
     * @param band the sub-band
     * @param block the code-block
     */
    private record Job(int component, int resolution, Band band, EncodedBlock block) {
    }
}
