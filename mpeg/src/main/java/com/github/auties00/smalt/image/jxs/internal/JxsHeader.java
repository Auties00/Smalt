/* Ports SVT-JPEG-XS 0.9.0 (ParseHeader.c). Copyright 2024 Intel Corporation; BSD-2-Clause-Patent.
   Modified for Smalt. */
package com.github.auties00.smalt.image.jxs.internal;

import com.github.auties00.smalt.MediaException;

/**
 * The main header of a JPEG XS codestream (ISO/IEC 21122-1 annex A): the markers from {@code SOC} to the first slice
 * header.
 *
 * <p>Instances are immutable once parsed. Arguments are not checked beyond the codestream syntax.
 */
public final class JxsHeader {
    /**
     * Start of codestream.
     */
    public static final int SOC = 0xFF10;
    /**
     * End of codestream.
     */
    public static final int EOC = 0xFF11;
    /**
     * Picture header.
     */
    public static final int PIH = 0xFF12;
    /**
     * Component table.
     */
    public static final int CDT = 0xFF13;
    /**
     * Weights table.
     */
    public static final int WGT = 0xFF14;
    /**
     * Extension marker.
     */
    public static final int COM = 0xFF15;
    /**
     * Nonlinearity marker.
     */
    public static final int NLT = 0xFF16;
    /**
     * Component-dependent wavelet decomposition marker.
     */
    public static final int CWD = 0xFF17;
    /**
     * Color transformation specification marker.
     */
    public static final int CTS = 0xFF18;
    /**
     * Component registration marker.
     */
    public static final int CRG = 0xFF19;
    /**
     * Temporal prediction control marker of temporal differential coding.
     */
    public static final int TPC = 0xFF1A;
    /**
     * Refresh weights table of temporal differential coding.
     */
    public static final int WGR = 0xFF1B;
    /**
     * Slice header.
     */
    public static final int SLH = 0xFF20;
    /**
     * Slice header of a slice coded with temporal differential coding.
     */
    public static final int SLI = 0xFF21;
    /**
     * Capabilities marker.
     */
    public static final int CAP = 0xFF50;

    /**
     * The largest number of components a codestream may have.
     */
    public static final int MAX_COMPONENTS = 8;

    /**
     * The number of bytes of the main header, from {@code SOC} to the first slice header.
     */
    public final int length;
    /**
     * The capability bits of the {@code CAP} marker, bit {@code i} of the marker in bit {@code i}.
     */
    public final long capabilities;
    /**
     * The codestream length {@code Lcod} in bytes, or 0 for variable bitrate coding.
     */
    public final long codestreamLength;
    /**
     * The profile {@code Ppih}.
     */
    public final int profile;
    /**
     * The level and sublevel {@code Plev}.
     */
    public final int level;
    /**
     * The width {@code Wf} of the sampling grid.
     */
    public final int width;
    /**
     * The height {@code Hf} of the sampling grid.
     */
    public final int height;
    /**
     * The precinct width {@code Cw} in multiples of {@code 8 * max(Sx) * 2^NLx}, or 0 for the full width.
     */
    public final int precinctWidth;
    /**
     * The slice height {@code Hsl} in precincts.
     */
    public final int sliceHeight;
    /**
     * The number of components {@code Nc}.
     */
    public final int components;
    /**
     * The number of coefficients per code group {@code Ng}.
     */
    public final int groupSize;
    /**
     * The number of code groups per significance group {@code Ss}.
     */
    public final int significanceGroupSize;
    /**
     * The nominal bit precision of the wavelet coefficients {@code Bw}.
     */
    public final int waveletPrecision;
    /**
     * The number of fractional bits of the wavelet coefficients {@code Fq}.
     */
    public final int fractionalBits;
    /**
     * The number of bits of a raw bit-plane count {@code Br}.
     */
    public final int rawBits;
    /**
     * The slice coding mode {@code Fslc}.
     */
    public final int sliceCoding;
    /**
     * The progression order {@code Ppoc}.
     */
    public final int progression;
    /**
     * The color transformation {@code Cpih}: 0 none, 1 the reversible color transform, 3 Star-Tetrix.
     */
    public final int colorTransform;
    /**
     * The number of horizontal decomposition levels {@code NLx}.
     */
    public final int horizontalLevels;
    /**
     * The number of vertical decomposition levels {@code NLy}.
     */
    public final int verticalLevels;
    /**
     * Whether long packet headers are enforced ({@code Lh}).
     */
    public final boolean longHeaders;
    /**
     * Whether a packet may select raw bit-plane count coding ({@code Rl}).
     */
    public final boolean rawPerPacket;
    /**
     * The inverse quantizer {@code Qpih}: 0 deadzone, 1 uniform.
     */
    public final int quantizer;
    /**
     * The sign handling {@code Fs}: 0 signs with the data, 1 in their own sub-packet.
     */
    public final int signHandling;
    /**
     * The run mode {@code Rm}: 0 insignificant groups repeat the prediction, 1 they code zero coefficients.
     */
    public final int runMode;
    /**
     * The bit precision {@code B[c]} of each component.
     */
    public final int[] precision;
    /**
     * The horizontal sampling factor {@code Sx[c]} of each component.
     */
    public final int[] sx;
    /**
     * The vertical sampling factor {@code Sy[c]} of each component.
     */
    public final int[] sy;
    /**
     * The gain {@code G[b]} of each band that exists, in band order.
     */
    public final int[] gains;
    /**
     * The priority {@code P[b]} of each band that exists, in band order.
     */
    public final int[] priorities;
    /**
     * The refresh gains of temporal differential coding, or {@code null} without a {@code WGR} marker.
     */
    public final int[] refreshGains;
    /**
     * The refresh priorities of temporal differential coding, or {@code null} without a {@code WGR} marker.
     */
    public final int[] refreshPriorities;
    /**
     * The nonlinearity type {@code Tnlt}: 0 linear, 1 quadratic, 2 extended.
     */
    public final int nonlinearity;
    /**
     * The DC offset of the quadratic nonlinearity.
     */
    public final int quadraticOffset;
    /**
     * The upper threshold {@code T1} of region 1 of the extended nonlinearity.
     */
    public final long extendedT1;
    /**
     * The upper threshold {@code T2} of region 2 of the extended nonlinearity.
     */
    public final long extendedT2;
    /**
     * The exponent {@code E} of the linear slope of the extended nonlinearity.
     */
    public final int extendedE;
    /**
     * The number {@code Sd} of trailing components without wavelet decomposition.
     */
    public final int suppressedComponents;
    /**
     * Whether a {@code CTS} marker is present.
     */
    public final boolean hasColorSpecification;
    /**
     * The extent {@code Cf} of the Star-Tetrix transform.
     */
    public final int starTetrixExtent;
    /**
     * The exponent {@code e1} of the first chroma component of the Star-Tetrix transform.
     */
    public final int starTetrixE1;
    /**
     * The exponent {@code e2} of the second chroma component of the Star-Tetrix transform.
     */
    public final int starTetrixE2;
    /**
     * The horizontal registration {@code Xcrg[c]} of each component in units of 1/65536 sample, or {@code null}
     * without a {@code CRG} marker.
     */
    public final int[] registrationX;
    /**
     * The vertical registration {@code Ycrg[c]} of each component, or {@code null} without a {@code CRG} marker.
     */
    public final int[] registrationY;
    /**
     * Whether the codestream uses temporal differential coding: its profile is one of the TDC profiles, whose slices
     * open with {@code SLI} and whose precinct headers carry the frame buffer quantization and the temporal modes.
     */
    public final boolean temporal;
    /**
     * Whether a {@code TPC} marker is present, which enables the periodic refresh of temporal differential coding.
     */
    public final boolean temporalRefresh;
    /**
     * The number {@code S_i} of code groups of a temporal selection group: 8, the only value ISO/IEC 21122-1 allows.
     */
    public final int selectionGroups;
    /**
     * The intra coefficient scaling {@code Qbi} of the {@code TPC} marker, from -8 to 7, or 0 without one.
     */
    public final int intraShift;
    /**
     * The refresh coefficient scaling {@code Qbr} of the {@code TPC} marker, from -8 to 7, or 0 without one.
     */
    public final int refreshShift;
    /**
     * The refresh offset {@code Yh[b]} of each band that exists, in band order, or {@code null} without a {@code TPC}
     * marker.
     */
    public final int[] refreshOffsets;
    /**
     * The refresh period exponent {@code Sh[b]} of each band that exists, in band order, from 1 to 7, or {@code null}
     * without a {@code TPC} marker.
     */
    public final int[] refreshPeriods;

    private JxsHeader(Parser p) {
        length = p.position - p.start;
        capabilities = p.capabilities;
        codestreamLength = p.codestreamLength;
        profile = p.profile;
        level = p.level;
        width = p.width;
        height = p.height;
        precinctWidth = p.precinctWidth;
        sliceHeight = p.sliceHeight;
        components = p.components;
        groupSize = p.groupSize;
        significanceGroupSize = p.significanceGroupSize;
        waveletPrecision = p.waveletPrecision;
        fractionalBits = p.fractionalBits;
        rawBits = p.rawBits;
        sliceCoding = p.sliceCoding;
        progression = p.progression;
        colorTransform = p.colorTransform;
        horizontalLevels = p.horizontalLevels;
        verticalLevels = p.verticalLevels;
        longHeaders = p.longHeaders;
        rawPerPacket = p.rawPerPacket;
        quantizer = p.quantizer;
        signHandling = p.signHandling;
        runMode = p.runMode;
        precision = p.precision;
        sx = p.sx;
        sy = p.sy;
        gains = p.gains;
        priorities = p.priorities;
        refreshGains = p.refreshGains;
        refreshPriorities = p.refreshPriorities;
        nonlinearity = p.nonlinearity;
        quadraticOffset = p.quadraticOffset;
        extendedT1 = p.extendedT1;
        extendedT2 = p.extendedT2;
        extendedE = p.extendedE;
        suppressedComponents = p.suppressedComponents;
        hasColorSpecification = p.hasCts;
        starTetrixExtent = p.cf;
        starTetrixE1 = p.e1;
        starTetrixE2 = p.e2;
        registrationX = p.registrationX;
        registrationY = p.registrationY;
        temporal = profile == 0x4A45 || profile == 0x6A45;
        temporalRefresh = p.temporalControl != null;
        selectionGroups = 8;
        var control = p.temporalControl;
        if (control != null) {
            intraShift = (control[1] >>> 4 & 7) - (control[1] >>> 7 & 1) * 8;
            refreshShift = (control[1] & 7) - (control[1] >>> 3 & 1) * 8;
            refreshOffsets = new int[(control.length - 2) / 2];
            refreshPeriods = new int[refreshOffsets.length];
            for (var b = 0; b < refreshOffsets.length; b++) {
                refreshOffsets[b] = control[2 + 2 * b] & 0xFF;
                refreshPeriods[b] = control[3 + 2 * b] & 0xFF;
            }
        } else {
            intraShift = 0;
            refreshShift = 0;
            refreshOffsets = null;
            refreshPeriods = null;
        }
    }

    /**
     * Returns whether {@code data} starts a JPEG XS codestream at {@code offset}: {@code SOC} followed by
     * {@code CAP}.
     *
     * @param data the bytes
     * @param offset the start
     * @param length the number of bytes available
     * @return whether the bytes start a codestream
     */
    public static boolean isCodestream(byte[] data, int offset, int length) {
        return length >= 4 && u16(data, offset) == SOC && u16(data, offset + 2) == CAP;
    }

    /**
     * Parses the main header of the codestream that starts at {@code offset}.
     *
     * @param data the bytes
     * @param offset the position of {@code SOC}
     * @param end the end of the codestream (exclusive)
     * @return the header
     * @throws MediaException.Malformed if the header is invalid or truncated
     * @throws MediaException.Unsupported if the codestream uses a slice coding mode or progression order this
     * decoder does not implement
     */
    public static JxsHeader parse(byte[] data, int offset, int end) {
        var parser = new Parser(data, offset, end);
        parser.parse();
        return new JxsHeader(parser);
    }

    /**
     * Returns the big-endian 16-bit value at {@code offset}.
     *
     * @param data the bytes
     * @param offset the position of the value
     * @return the value, from 0 to 65535
     */
    public static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 8 | data[offset + 1] & 0xFF;
    }

    /**
     * Reads the markers of a main header.
     */
    private static final class Parser {
        private final byte[] data;
        private final int start;
        private final int end;
        private int position;

        private long capabilities;
        private long codestreamLength;
        private int profile;
        private int level;
        private int width;
        private int height;
        private int precinctWidth;
        private int sliceHeight;
        private int components;
        private int groupSize;
        private int significanceGroupSize;
        private int waveletPrecision;
        private int fractionalBits;
        private int rawBits;
        private int sliceCoding;
        private int progression;
        private int colorTransform;
        private int horizontalLevels;
        private int verticalLevels;
        private boolean longHeaders;
        private boolean rawPerPacket;
        private int quantizer;
        private int signHandling;
        private int runMode;
        private int[] precision;
        private int[] sx;
        private int[] sy;
        private int[] gains;
        private int[] priorities;
        private int[] refreshGains;
        private int[] refreshPriorities;
        private int nonlinearity;
        private int quadraticOffset;
        private long extendedT1;
        private long extendedT2;
        private int extendedE;
        private int suppressedComponents;
        private boolean hasCts;
        private int cf;
        private int e1;
        private int e2;
        private int[] registrationX;
        private int[] registrationY;
        private byte[] temporalControl;

        Parser(byte[] data, int start, int end) {
            this.data = data;
            this.start = start;
            this.end = end;
            this.position = start;
        }

        void parse() {
            if (marker() != SOC) {
                throw new MediaException.Malformed("the JPEG XS codestream does not start with SOC");
            }
            if (marker() != CAP) {
                throw new MediaException.Malformed("the JPEG XS codestream has no capabilities marker after SOC");
            }
            capabilities();
            if (marker() != PIH) {
                throw new MediaException.Malformed("the JPEG XS codestream has no picture header after CAP");
            }
            pictureHeader();
            var seenCdt = false;
            var seenWgt = false;
            var seenNlt = false;
            var seenCwd = false;
            var seenCrg = false;
            while (true) {
                need(2);
                var marker = JxsHeader.u16(data, position);
                if (marker == SLH || marker == SLI) {
                    break;
                }
                position += 2;
                switch (marker) {
                    case CDT -> {
                        if (seenCdt) {
                            throw duplicate("CDT");
                        }
                        seenCdt = true;
                        componentTable();
                    }
                    case WGT -> {
                        if (seenWgt) {
                            throw duplicate("WGT");
                        }
                        seenWgt = true;
                        var table = weights();
                        gains = table[0];
                        priorities = table[1];
                    }
                    case WGR -> {
                        if (refreshGains != null) {
                            throw duplicate("WGR");
                        }
                        var table = weights();
                        refreshGains = table[0];
                        refreshPriorities = table[1];
                    }
                    case NLT -> {
                        if (seenNlt) {
                            throw duplicate("NLT");
                        }
                        seenNlt = true;
                        nonlinearity();
                    }
                    case CWD -> {
                        if (seenCwd) {
                            throw duplicate("CWD");
                        }
                        seenCwd = true;
                        if (segmentLength() != 3) {
                            throw new MediaException.Malformed("the JPEG XS CWD marker has an invalid length");
                        }
                        suppressedComponents = u8();
                    }
                    case CTS -> {
                        if (hasCts) {
                            throw duplicate("CTS");
                        }
                        hasCts = true;
                        if (segmentLength() != 4) {
                            throw new MediaException.Malformed("the JPEG XS CTS marker has an invalid length");
                        }
                        cf = u8() & 0x0F;
                        var exponents = u8();
                        e1 = exponents >>> 4;
                        e2 = exponents & 0x0F;
                    }
                    case CRG -> {
                        if (seenCrg) {
                            throw duplicate("CRG");
                        }
                        seenCrg = true;
                        registration();
                    }
                    case TPC -> {
                        if (temporalControl != null) {
                            throw duplicate("TPC");
                        }
                        var length = segmentLength();
                        if (length < 2) {
                            throw new MediaException.Malformed("the JPEG XS TPC marker has an invalid length");
                        }
                        need(length - 2);
                        if (length < 6 || length % 2 != 0 || data[position] != 8) {
                            throw new MediaException.Malformed("the JPEG XS TPC marker has an invalid length or "
                                    + "selection group size");
                        }
                        for (var i = position + 3; i < position + length - 2; i += 2) {
                            if (data[i] == 0 || (data[i] & 0xFF) > 7) {
                                throw new MediaException.Malformed("the JPEG XS TPC marker has an invalid refresh "
                                        + "period");
                            }
                        }
                        temporalControl = java.util.Arrays.copyOfRange(data, position, position + length - 2);
                        position += length - 2;
                    }
                    case COM -> {
                        var length = segmentLength();
                        if (length < 4) {
                            throw new MediaException.Malformed("the JPEG XS COM marker has an invalid length");
                        }
                        need(length - 2);
                        position += length - 2;
                    }
                    case SOC, EOC, PIH, CAP -> throw new MediaException.Malformed("the JPEG XS main header repeats "
                            + "or ends with marker 0x" + Integer.toHexString(marker));
                    default -> throw new MediaException.Malformed("the JPEG XS main header has an unknown marker 0x"
                            + Integer.toHexString(marker));
                }
            }
            if (!seenCdt || !seenWgt) {
                throw new MediaException.Malformed("the JPEG XS main header lacks its " + (seenCdt ? "WGT" : "CDT")
                        + " marker");
            }
            validate();
        }

        private void capabilities() {
            var length = segmentLength();
            if (length < 2 || (length - 2) * 8 > 64) {
                throw new MediaException.Malformed("the JPEG XS capabilities marker has an invalid length");
            }
            need(length - 2);
            for (var i = 0; i < length - 2; i++) {
                var bits = data[position + i] & 0xFF;
                for (var b = 0; b < 8; b++) {
                    if ((bits & (0x80 >>> b)) != 0) {
                        capabilities |= 1L << (i * 8 + b);
                    }
                }
            }
            position += length - 2;
        }

        private void pictureHeader() {
            if (segmentLength() != 26) {
                throw new MediaException.Malformed("the JPEG XS picture header has an invalid length");
            }
            need(24);
            codestreamLength = u32();
            profile = u16();
            level = u16();
            width = u16();
            height = u16();
            precinctWidth = u16();
            sliceHeight = u16();
            components = u8();
            groupSize = u8();
            significanceGroupSize = u8();
            waveletPrecision = u8();
            var fqBr = u8();
            fractionalBits = fqBr >>> 4;
            rawBits = fqBr & 0x0F;
            var slc = u8();
            sliceCoding = slc >>> 7;
            progression = (slc >>> 4) & 7;
            colorTransform = slc & 0x0F;
            var levels = u8();
            horizontalLevels = levels >>> 4;
            verticalLevels = levels & 0x0F;
            var flags = u8();
            longHeaders = (flags & 0x80) != 0;
            rawPerPacket = (flags & 0x40) != 0;
            quantizer = (flags >>> 4) & 3;
            signHandling = (flags >>> 2) & 3;
            runMode = flags & 3;
        }

        private void componentTable() {
            if (components < 1 || components > MAX_COMPONENTS) {
                throw new MediaException.Malformed("the JPEG XS picture header declares " + components
                        + " components");
            }
            if (segmentLength() != 2 + 2 * components) {
                throw new MediaException.Malformed("the JPEG XS component table has an invalid length");
            }
            precision = new int[components];
            sx = new int[components];
            sy = new int[components];
            for (var c = 0; c < components; c++) {
                precision[c] = u8();
                var sampling = u8();
                sx[c] = sampling >>> 4;
                sy[c] = sampling & 0x0F;
            }
        }

        private int[][] weights() {
            var length = segmentLength();
            if (length < 2 || ((length - 2) & 1) != 0) {
                throw new MediaException.Malformed("the JPEG XS weights table has an invalid length");
            }
            var count = (length - 2) / 2;
            need(length - 2);
            var g = new int[count];
            var p = new int[count];
            for (var b = 0; b < count; b++) {
                g[b] = u8();
                p[b] = u8();
            }
            return new int[][]{g, p};
        }

        private void nonlinearity() {
            var length = segmentLength();
            if (length < 3) {
                throw new MediaException.Malformed("the JPEG XS NLT marker has an invalid length");
            }
            nonlinearity = u8();
            switch (nonlinearity) {
                case 1 -> {
                    if (length != 5) {
                        throw new MediaException.Malformed("the JPEG XS quadratic NLT marker has an invalid length");
                    }
                    var value = u16();
                    quadraticOffset = (value & 0x7FFF) - ((value >>> 15) << 15);
                }
                case 2 -> {
                    if (length != 12) {
                        throw new MediaException.Malformed("the JPEG XS extended NLT marker has an invalid length");
                    }
                    extendedT1 = u32();
                    extendedT2 = u32();
                    extendedE = u8();
                }
                default -> throw new MediaException.Malformed("the JPEG XS NLT marker has unknown type "
                        + nonlinearity);
            }
        }

        private void registration() {
            if (components < 1 || components > MAX_COMPONENTS) {
                throw new MediaException.Malformed("the JPEG XS picture header declares " + components
                        + " components");
            }
            if (segmentLength() != 2 + 4 * components) {
                throw new MediaException.Malformed("the JPEG XS CRG marker has an invalid length");
            }
            registrationX = new int[components];
            registrationY = new int[components];
            for (var c = 0; c < components; c++) {
                registrationX[c] = u16();
                registrationY[c] = u16();
            }
        }

        private void validate() {
            if (width < 1 || height < 1) {
                throw new MediaException.Malformed("the JPEG XS picture is " + width + "x" + height);
            }
            if (sliceHeight < 1) {
                throw new MediaException.Malformed("the JPEG XS slice height is zero");
            }
            if (groupSize != 4 || significanceGroupSize != 8) {
                throw new MediaException.Malformed("the JPEG XS code group size is " + groupSize
                        + " and the significance group size " + significanceGroupSize + ", not 4 and 8");
            }
            if (rawBits != 4 && rawBits != 5) {
                throw new MediaException.Malformed("the JPEG XS raw bit-plane count has " + rawBits
                        + " bits, not 4 or 5");
            }
            if (waveletPrecision < 1 || waveletPrecision > 31 || fractionalBits > waveletPrecision) {
                throw new MediaException.Malformed("the JPEG XS wavelet precision " + waveletPrecision
                        + " with " + fractionalBits + " fractional bits is invalid");
            }
            if (sliceCoding != 0) {
                throw new MediaException.Malformed("the JPEG XS slice coding mode " + sliceCoding + " is reserved");
            }
            if (progression != 0) {
                throw new MediaException.Malformed("the JPEG XS progression order " + progression + " is reserved");
            }
            if (colorTransform != 0 && colorTransform != 1 && colorTransform != 3) {
                throw new MediaException.Malformed("the JPEG XS color transformation " + colorTransform
                        + " is reserved");
            }
            if (quantizer > 1) {
                throw new MediaException.Malformed("the JPEG XS inverse quantizer " + quantizer + " is reserved");
            }
            if (signHandling > 1) {
                throw new MediaException.Malformed("the JPEG XS sign handling " + signHandling + " is reserved");
            }
            if (runMode > 1) {
                throw new MediaException.Malformed("the JPEG XS run mode " + runMode + " is reserved");
            }
            if (horizontalLevels > 15 || verticalLevels > 2 || verticalLevels > horizontalLevels) {
                throw new MediaException.Malformed("the JPEG XS decomposition has " + horizontalLevels
                        + " horizontal and " + verticalLevels + " vertical levels");
            }
            for (var c = 0; c < components; c++) {
                if (precision[c] < 1 || precision[c] > 16) {
                    throw new MediaException.Malformed("JPEG XS component " + c + " has a precision of "
                            + precision[c] + " bits");
                }
                if (sx[c] < 1 || sx[c] > 2 || sy[c] < 1 || sy[c] > 2) {
                    throw new MediaException.Malformed("JPEG XS component " + c + " has sampling factors "
                            + sx[c] + "x" + sy[c]);
                }
            }
            if (suppressedComponents >= components && suppressedComponents > 0) {
                throw new MediaException.Malformed("the JPEG XS CWD marker suppresses the decomposition of "
                        + suppressedComponents + " of " + components + " components");
            }
            if (colorTransform == 1 && components < 3) {
                throw new MediaException.Malformed("the JPEG XS codestream applies the color transform to "
                        + components + " components");
            }
            if (colorTransform == 3) {
                if (components != 4 || !hasCts || registrationX == null) {
                    throw new MediaException.Malformed("the JPEG XS Star-Tetrix transform needs four components "
                            + "and the CTS and CRG markers");
                }
            }
            if (colorTransform != 0) {
                var limit = colorTransform == 1 ? 3 : components;
                for (var c = 1; c < limit; c++) {
                    if (sx[c] != sx[0] || sy[c] != sy[0]) {
                        throw new MediaException.Malformed("the JPEG XS color transform applies to components "
                                + "of different sampling");
                    }
                }
            }
            if (nonlinearity == 2 && (extendedE >= waveletPrecision || extendedT1 > extendedT2
                    || extendedT2 > 0x7FFFFFFFL)) {
                throw new MediaException.Malformed("the JPEG XS extended nonlinearity parameters are invalid");
            }
        }

        private int segmentLength() {
            return u16();
        }

        private MediaException.Malformed duplicate(String name) {
            return new MediaException.Malformed("the JPEG XS main header has two " + name + " markers");
        }

        private int marker() {
            need(2);
            var value = JxsHeader.u16(data, position);
            position += 2;
            return value;
        }

        private void need(int count) {
            if (count < 0 || position + count > end) {
                throw new MediaException.Malformed("the JPEG XS main header is truncated");
            }
        }

        private int u8() {
            need(1);
            return data[position++] & 0xFF;
        }

        private int u16() {
            need(2);
            var value = JxsHeader.u16(data, position);
            position += 2;
            return value;
        }

        private long u32() {
            need(4);
            var value = ((long) JxsHeader.u16(data, position) << 16) | JxsHeader.u16(data, position + 2);
            position += 4;
            return value;
        }
    }
}
