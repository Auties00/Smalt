package com.github.auties00.smalt.video.mpeg4.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;

/**
 * Parses the picture header of ITU-T H.263 (5.1), which the short video header of ISO/IEC
 * 14496-2 (6.2.5.2) restricts to its baseline form, and holds the optional modes the header
 * enables for the macroblock layer.
 *
 * <p>The baseline picture header and the extended picture type (PLUSPTYPE) are supported, with
 * the custom picture formats and clock frequencies and the optional modes that the profiles of
 * Annex X and the 3GPP H.263 profiles use: Unrestricted Motion Vectors (Annex D), Advanced
 * Prediction (Annex F), Advanced INTRA Coding (Annex I), the Deblocking Filter (Annex J), Slice
 * Structured mode without rectangular slices (Annex K), Alternative INTER VLC (Annex S) and
 * Modified Quantization (Annex T). Headers using any other mode raise
 * {@link MediaException.Unsupported} naming it: syntax-based arithmetic coding, PB-frames and
 * Improved PB-frames, Continuous Presence Multipoint, reference picture selection, independent
 * segment decoding, scalability, reference picture resampling, reduced-resolution update and
 * rectangular slices.
 *
 * <p>The mode states persist from picture to picture as the inference rules of 5.1.4.5 require.
 * An instance belongs to one decoder and is not thread-safe.
 */
public final class H263Header {
    /**
     * The widths of the standard source formats, indexed by the source format code.
     */
    private static final int[] WIDTHS = {0, 128, 176, 352, 704, 1408};

    /**
     * The heights of the standard source formats, indexed by the source format code.
     */
    private static final int[] HEIGHTS = {0, 96, 144, 288, 576, 1152};

    /**
     * The deblocking filter strength of each quantizer (Table J.2), indexed by the quantizer.
     */
    private static final int[] STRENGTH = {
            0, 1, 1, 2, 2, 3, 3, 4, 4, 4, 5, 5, 6, 6, 7, 7, 7, 8, 8, 8, 9, 9, 9, 10, 10, 10, 11, 11, 11, 12, 12, 12
    };

    /**
     * The chrominance quantizer of the Modified Quantization mode (Table T.2), indexed by the
     * luminance quantizer.
     */
    private static final int[] CHROMA_QUANT = {
            0, 1, 2, 3, 4, 5, 6, 6, 7, 8, 9, 9, 10, 10, 11, 11, 12, 12, 12, 13, 13, 13, 14, 14, 14, 14, 14, 15, 15, 15,
            15, 15
    };

    /**
     * Whether the picture header carried an extended picture type.
     */
    public boolean extended;

    /**
     * Whether the Unrestricted Motion Vector mode is on.
     */
    public boolean unrestrictedVectors;

    /**
     * Whether the Advanced Prediction mode is on.
     */
    public boolean advancedPrediction;

    /**
     * Whether the Advanced INTRA Coding mode is on.
     */
    public boolean advancedIntra;

    /**
     * Whether the Deblocking Filter mode is on.
     */
    public boolean deblocking;

    /**
     * Whether the Slice Structured mode is on.
     */
    public boolean slices;

    /**
     * Whether the Alternative INTER VLC mode is on.
     */
    public boolean alternativeInterVlc;

    /**
     * Whether the Modified Quantization mode is on.
     */
    public boolean modifiedQuantization;

    /**
     * The picture coding type of the last header.
     */
    private int codingType;

    /**
     * The quantizer of the last header.
     */
    private int quant;

    /**
     * The rounding type of the last header.
     */
    private int rounding;

    /**
     * The bit position of the picture data after the header.
     */
    private long dataPosition;

    /**
     * The temporal reference of the previous picture, or -1.
     */
    private int lastReference = -1;

    /**
     * The accumulated temporal references, in picture clock periods.
     */
    private long clockPeriods;

    /**
     * The duration of a picture clock period in ticks of 1/1800000 second.
     */
    private int periodTicks = 60060;

    /**
     * Whether a custom picture clock frequency is in effect.
     */
    private boolean customClock;

    /**
     * The width of the last custom picture format.
     */
    private int customWidth;

    /**
     * The height of the last custom picture format.
     */
    private int customHeight;

    /**
     * The pixel aspect ratio width of the last custom format.
     */
    private int parWidth = 12;

    /**
     * The pixel aspect ratio height of the last custom format.
     */
    private int parHeight = 11;

    /**
     * The modes of the optional part of the last PLUSPTYPE that carried it, one bit per mode as
     * in OPPTYPE bits 5 to 14.
     */
    private int optionalModes;

    /**
     * The source format of the last PLUSPTYPE that carried it.
     */
    private int extendedFormat;

    /**
     * Creates a parser.
     */
    public H263Header() {
    }

    /**
     * Parses a picture header from its picture start code.
     *
     * @param reader the reader, positioned at the picture start code
     * @param h263 whether the stream is H.263 rather than MPEG-4 with the short video header
     * @param previous the active layer, or {@code null}
     * @param limits the limits bounding the picture size
     * @return the layer of the picture: {@code previous} when its size and syntax are unchanged,
     * a new layer otherwise; {@code null} for an end of sequence code
     * @throws MediaException.Malformed if the header is corrupt
     * @throws MediaException.Unsupported if the header enables a mode this decoder lacks
     * @throws MediaException.LimitExceeded if the picture exceeds the limits
     */
    public VideoObjectLayer parse(BitReader reader, boolean h263, VideoObjectLayer previous, MediaLimits limits) {
        if (reader.peek(22) != 0x20) {
            return null;
        }
        reader.skip(22);
        var reference = reader.read(8);
        if (reader.readBit() != 1 || reader.readBit() != 0) {
            throw new MediaException.Malformed("invalid H.263 PTYPE");
        }
        reader.skip(3);
        var format = reader.read(3);
        int width;
        int height;
        if (format != 7) {
            if (format == 0 || format == 6) {
                throw new MediaException.Malformed("reserved H.263 source format " + format);
            }
            extended = false;
            codingType = reader.readBit() == 0 ? Vop.I : Vop.P;
            unrestrictedVectors = reader.readFlag();
            if (reader.readFlag()) {
                throw new MediaException.Unsupported("h263 syntax-based arithmetic coding",
                        "H.263 Annex E is not supported");
            }
            advancedPrediction = reader.readFlag();
            if (reader.readFlag()) {
                throw new MediaException.Unsupported("h263 pb-frames", "H.263 Annex G is not supported");
            }
            advancedIntra = false;
            deblocking = false;
            slices = false;
            alternativeInterVlc = false;
            modifiedQuantization = false;
            customClock = false;
            periodTicks = 60060;
            rounding = 0;
            quant = reader.read(5);
            if (reader.readFlag()) {
                throw new MediaException.Unsupported("h263 continuous presence multipoint",
                        "H.263 Annex C sub-bitstreams are not supported");
            }
            width = WIDTHS[format];
            height = HEIGHTS[format];
            parWidth = 12;
            parHeight = 11;
        } else {
            extended = true;
            var ufep = reader.read(3);
            if (ufep == 1) {
                extendedFormat = reader.read(3);
                customClock = reader.readFlag();
                optionalModes = reader.read(10);
                if (reader.readBit() != 1) {
                    throw new MediaException.Malformed("invalid H.263 OPPTYPE");
                }
                reader.skip(3);
            } else if (ufep != 0) {
                throw new MediaException.Malformed("reserved H.263 UFEP " + ufep);
            } else if (previous == null) {
                throw new MediaException.Malformed("H.263 picture without the optional PLUSPTYPE before");
            }
            var type = reader.read(3);
            if (type > 1) {
                throw new MediaException.Unsupported("h263 picture type " + type,
                        "H.263 improved PB-frames, B, EI and EP pictures are not supported");
            }
            codingType = type == 0 ? Vop.I : Vop.P;
            if (reader.readFlag()) {
                throw new MediaException.Unsupported("h263 reference picture resampling",
                        "H.263 Annex P is not supported");
            }
            if (reader.readFlag()) {
                throw new MediaException.Unsupported("h263 reduced-resolution update",
                        "H.263 Annex Q is not supported");
            }
            rounding = reader.readBit();
            reader.skip(2);
            if (reader.readBit() != 1) {
                throw new MediaException.Malformed("invalid H.263 MPPTYPE");
            }
            var modes = optionalModes;
            unrestrictedVectors = (modes & 0x200) != 0;
            if ((modes & 0x100) != 0) {
                throw new MediaException.Unsupported("h263 syntax-based arithmetic coding",
                        "H.263 Annex E is not supported");
            }
            advancedPrediction = (modes & 0x80) != 0;
            advancedIntra = (modes & 0x40) != 0;
            deblocking = (modes & 0x20) != 0;
            slices = (modes & 0x10) != 0;
            if ((modes & 0x8) != 0) {
                throw new MediaException.Unsupported("h263 reference picture selection",
                        "H.263 Annex N is not supported");
            }
            if ((modes & 0x4) != 0) {
                throw new MediaException.Unsupported("h263 independent segment decoding",
                        "H.263 Annex R is not supported");
            }
            alternativeInterVlc = (modes & 0x2) != 0;
            modifiedQuantization = (modes & 0x1) != 0;
            if (reader.readFlag()) {
                throw new MediaException.Unsupported("h263 continuous presence multipoint",
                        "H.263 Annex C sub-bitstreams are not supported");
            }
            if (ufep == 1 && extendedFormat == 6) {
                var aspect = reader.read(4);
                var widthIndication = reader.read(9);
                if (reader.readBit() != 1) {
                    throw new MediaException.Malformed("invalid H.263 CPFMT");
                }
                var heightIndication = reader.read(9);
                customWidth = (widthIndication + 1) * 4;
                customHeight = heightIndication * 4;
                switch (aspect) {
                    case 1 -> {
                        parWidth = 1;
                        parHeight = 1;
                    }
                    case 2 -> {
                        parWidth = 12;
                        parHeight = 11;
                    }
                    case 3 -> {
                        parWidth = 10;
                        parHeight = 11;
                    }
                    case 4 -> {
                        parWidth = 16;
                        parHeight = 11;
                    }
                    case 5 -> {
                        parWidth = 40;
                        parHeight = 33;
                    }
                    case 15 -> {
                        parWidth = reader.read(8);
                        parHeight = reader.read(8);
                    }
                    default -> {
                        parWidth = 0;
                        parHeight = 0;
                    }
                }
            } else if (ufep == 1 && extendedFormat >= 1 && extendedFormat <= 5) {
                parWidth = 12;
                parHeight = 11;
            }
            if (ufep == 1 && customClock) {
                var conversion = reader.readBit() == 0 ? 1000 : 1001;
                var divisor = reader.read(7);
                if (divisor == 0) {
                    throw new MediaException.Malformed("zero H.263 clock divisor");
                }
                periodTicks = conversion * divisor;
            } else if (ufep == 1) {
                periodTicks = 60060;
            }
            if (customClock) {
                reference |= reader.read(2) << 8;
            }
            if (ufep == 1 && unrestrictedVectors && reader.readBit() == 0) {
                // UUI = 01: the motion vector range is unlimited, which decoding needs no state for.
                reader.skip(1);
            }
            if (ufep == 1 && slices) {
                if (reader.readBit() == 1) {
                    throw new MediaException.Unsupported("h263 rectangular slices",
                            "H.263 Annex K rectangular slices are not supported");
                }
                reader.skip(1);
            }
            if (extendedFormat == 6) {
                width = customWidth;
                height = customHeight;
            } else if (extendedFormat >= 1 && extendedFormat <= 5) {
                width = WIDTHS[extendedFormat];
                height = HEIGHTS[extendedFormat];
            } else {
                throw new MediaException.Malformed("reserved H.263 source format " + extendedFormat);
            }
            quant = reader.read(5);
        }
        if (quant == 0) {
            throw new MediaException.Malformed("H.263 PQUANT is zero");
        }
        var guard = 0;
        while (reader.readBit() == 1) {
            reader.skip(8);
            if (++guard > 4096 || reader.overrun()) {
                throw new MediaException.Malformed("runaway H.263 PSUPP");
            }
        }
        if (width == 0 || height == 0) {
            throw new MediaException.Malformed("H.263 picture of size " + width + "x" + height);
        }
        if (reader.overrun()) {
            throw new MediaException.Malformed("truncated H.263 picture header");
        }
        dataPosition = reader.position();
        var modulus = customClock ? 1024 : 256;
        if (lastReference < 0) {
            clockPeriods = 0;
        } else {
            var delta = (reference - lastReference) & (modulus - 1);
            clockPeriods += delta == 0 ? 1 : delta;
        }
        lastReference = reference;
        if (previous != null && previous.width == width && previous.height == height
                && previous.shortVideoHeader && previous.parWidth == parWidth && previous.parHeight == parHeight) {
            return previous;
        }
        LimitChecks.checkPicture(limits, width, height);
        var layer = VideoObjectLayer.shortHeader(width, height);
        layer.timeResolution = 1800000;
        layer.fixedTimeIncrement = periodTicks;
        layer.parWidth = parWidth;
        layer.parHeight = parHeight;
        return layer;
    }

    /**
     * Copies the fields of the last header into a VOP header.
     *
     * @param vop the VOP header
     */
    public void fill(Vop vop) {
        vop.codingType = codingType;
        vop.coded = true;
        vop.quant = quant;
        vop.roundingType = codingType == Vop.P ? rounding : 0;
        vop.fcodeForward = 1;
        vop.fcodeBackward = 1;
        vop.intraDcVlcThreshold = 0;
        vop.alternateVerticalScan = false;
        vop.topFieldFirst = false;
        vop.dataPosition = dataPosition;
    }

    /**
     * Returns the time of the last picture in ticks of 1/1800000 second.
     *
     * @return the time
     */
    public long time() {
        return clockPeriods * periodTicks;
    }

    /**
     * Returns the number of macroblock rows of a group of blocks for a picture height (5.2.3 and
     * K.2): one up to 400 lines, two up to 800 lines, four above.
     *
     * @param height the picture height
     * @return the rows per group of blocks
     */
    public static int gobRows(int height) {
        return height <= 400 ? 1 : height <= 800 ? 2 : 4;
    }

    /**
     * Returns the chrominance quantizer of a luminance quantizer: QUANT_C of Table T.2 in the
     * Modified Quantization mode, the quantizer itself otherwise.
     *
     * @param quant the luminance quantizer
     * @return the chrominance quantizer
     */
    public int chromaQuant(int quant) {
        return modifiedQuantization ? CHROMA_QUANT[quant] : quant;
    }

    /**
     * Applies the in-loop processing of the picture's modes after reconstruction: the deblocking
     * edge filter of Annex J.
     *
     * @param frame the reconstructed picture
     * @param blocks the parsed macroblocks
     * @param layer the layer of the picture
     */
    public void postProcess(Frame frame, Macroblocks blocks, VideoObjectLayer layer) {
        if (!deblocking) {
            return;
        }
        var mbWidth = frame.mbWidth;
        var mbHeight = frame.mbHeight;
        filterPlane(frame.y, frame.lumaOrigin, frame.lumaStride, mbWidth * 2, mbHeight * 2, 1, frame, blocks, false);
        filterPlane(frame.u, frame.chromaOrigin, frame.chromaStride, mbWidth, mbHeight, 0, frame, blocks, true);
        filterPlane(frame.v, frame.chromaOrigin, frame.chromaStride, mbWidth, mbHeight, 0, frame, blocks, true);
    }

    /**
     * Filters the block edges of one plane (J.3): every horizontal edge first, then every
     * vertical edge.
     *
     * @param plane the component plane, filtered in place
     * @param origin the index of its top-left sample
     * @param stride its row stride
     * @param columns the number of block columns
     * @param rows the number of block rows
     * @param shift the base-2 logarithm of the blocks per macroblock side
     * @param frame the picture, for the macroblock types
     * @param blocks the parsed macroblocks, for the quantizers
     * @param chroma whether the plane is a chrominance plane
     */
    private void filterPlane(byte[] plane, int origin, int stride, int columns, int rows, int shift, Frame frame,
                             Macroblocks blocks, boolean chroma) {
        var mbWidth = frame.mbWidth;
        for (var by = 1; by < rows; by++) {
            for (var bx = 0; bx < columns; bx++) {
                var above = ((by - 1) >> shift) * mbWidth + (bx >> shift);
                var below = (by >> shift) * mbWidth + (bx >> shift);
                var strength = strength(frame, blocks, above, below, chroma);
                if (strength == 0) {
                    continue;
                }
                var at = origin + by * 8 * stride + bx * 8;
                for (var i = 0; i < 8; i++) {
                    filter(plane, at + i, stride, strength);
                }
            }
        }
        for (var by = 0; by < rows; by++) {
            for (var bx = 1; bx < columns; bx++) {
                var left = (by >> shift) * mbWidth + ((bx - 1) >> shift);
                var right = (by >> shift) * mbWidth + (bx >> shift);
                var strength = strength(frame, blocks, left, right, chroma);
                if (strength == 0) {
                    continue;
                }
                var at = origin + by * 8 * stride + bx * 8;
                for (var i = 0; i < 8; i++) {
                    filter(plane, at + i * stride, 1, strength);
                }
            }
        }
    }

    /**
     * Returns the filter strength of an edge between two macroblocks' blocks, or zero when
     * neither macroblock is coded.
     *
     * @param frame the picture
     * @param blocks the parsed macroblocks
     * @param first the macroblock of block1 (above or left)
     * @param second the macroblock of block2 (below or right)
     * @param chroma whether the edge is in a chrominance plane
     * @return the strength
     */
    private int strength(Frame frame, Macroblocks blocks, int first, int second, boolean chroma) {
        var secondCoded = frame.mbType[second] != Frame.MB_SKIPPED;
        var firstCoded = frame.mbType[first] != Frame.MB_SKIPPED;
        if (!secondCoded && !firstCoded) {
            return 0;
        }
        var q = blocks.quant[secondCoded ? second : first];
        return STRENGTH[chroma ? chromaQuant(q) : q];
    }

    /**
     * Filters one line of four samples across an edge.
     *
     * @param plane the component plane, filtered in place
     * @param at the index of sample C, the first after the edge
     * @param step the distance between successive samples of the line
     * @param strength the filter strength
     */
    private static void filter(byte[] plane, int at, int step, int strength) {
        var a = plane[at - 2 * step] & 0xFF;
        var b = plane[at - step] & 0xFF;
        var c = plane[at] & 0xFF;
        var d = plane[at + step] & 0xFF;
        var delta = (a - 4 * b + 4 * c - d) / 8;
        var magnitude = Math.abs(delta);
        var d1 = Integer.signum(delta) * Math.max(0, magnitude - Math.max(0, 2 * (magnitude - strength)));
        var limit = Math.abs(d1 / 2);
        var d2 = Math.max(-limit, Math.min(limit, (a - d) / 4));
        plane[at - 2 * step] = (byte) (a - d2);
        plane[at - step] = (byte) clip(b + d1);
        plane[at] = (byte) clip(c - d1);
        plane[at + step] = (byte) (d + d2);
    }

    /**
     * Clips a value to the 8-bit sample range.
     *
     * @param value the sample before clipping
     * @return the clipped value
     */
    private static int clip(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }
}
