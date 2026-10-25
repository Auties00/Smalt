package com.github.auties00.smalt.video.h263.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.video.mpeg4.internal.BitReader;

/**
 * Parses an ITU-T H.263 picture header (5.1) with every field the optional modes add, and holds the modes in effect
 * for the picture after the inference rules of 5.1.4.5.
 *
 * <p>An instance is reused from picture to picture and is not thread-safe.
 */
final class PictureHeader {
    /**
     * An I-picture.
     */
    static final int I = 0;

    /**
     * A P-picture, also the P-part of a PB-frame of Annex G.
     */
    static final int P = 1;

    /**
     * An Improved PB-frame (Annex M).
     */
    static final int IMPROVED_PB = 2;

    /**
     * A B-picture (Annex O).
     */
    static final int B = 3;

    /**
     * An EI-picture (Annex O).
     */
    static final int EI = 4;

    /**
     * An EP-picture (Annex O).
     */
    static final int EP = 5;

    /**
     * The pixel aspect ratios of Table 5, as width and height, indexed by the PAR code.
     */
    private static final int[][] ASPECT = {{0, 0}, {1, 1}, {12, 11}, {10, 11}, {16, 11}, {40, 33}};

    /**
     * Whether the header carried PLUSPTYPE.
     */
    boolean plus;

    /**
     * The UFEP of PLUSPTYPE.
     */
    int ufep;

    /**
     * The picture type, one of the type constants.
     */
    int type;

    /**
     * Whether the picture is a PB-frame of Annex G.
     */
    boolean pbFrame;

    /**
     * The temporal reference, with the extended bits when a custom picture clock frequency is in use.
     */
    int temporalReference;

    /**
     * Whether the temporal reference has 10 bits.
     */
    boolean extendedReference;

    /**
     * The width of the picture.
     */
    int width;

    /**
     * The height of the picture.
     */
    int height;

    /**
     * The pixel aspect ratio width, 0 when unknown.
     */
    int parWidth;

    /**
     * The pixel aspect ratio height, 0 when unknown.
     */
    int parHeight;

    /**
     * The optional modes in effect for the picture, as the {@code LayerState} bits.
     */
    int modes;

    /**
     * Whether the Rectangular Slice submode is in use.
     */
    boolean rectangularSlices;

    /**
     * Whether the Arbitrary Slice Ordering submode is in use.
     */
    boolean arbitrarySliceOrder;

    /**
     * Whether the vectors are unlimited (UUI "01").
     */
    boolean unlimitedVectors;

    /**
     * Whether the Reference Picture Resampling bit is set.
     */
    boolean resampling;

    /**
     * Whether the Reduced-Resolution Update mode is on.
     */
    boolean reducedResolution;

    /**
     * The rounding type.
     */
    int rounding;

    /**
     * Whether Continuous Presence Multipoint is on.
     */
    boolean cpm;

    /**
     * The sub-bitstream of the picture.
     */
    int subBitstream;

    /**
     * The enhancement layer number, 1 for the base layer.
     */
    int layer;

    /**
     * The reference layer number.
     */
    int referenceLayer;

    /**
     * The picture quantizer.
     */
    int quant;

    /**
     * The temporal reference of the B-part of a PB- or Improved PB-frame (TRB).
     */
    int trb;

    /**
     * The DBQUANT of a PB- or Improved PB-frame.
     */
    int dbquant;

    /**
     * The temporal reference of the picture to predict from (TRP), or -1.
     */
    int trp;

    /**
     * The picture number of the Enhanced Reference Picture Selection mode.
     */
    int pictureNumber;

    /**
     * The ERPS layer of the picture.
     */
    final ErpsLayer erps = new ErpsLayer();

    /**
     * The warping displacement accuracy: 2 or 16.
     */
    int warpingAccuracy;

    /**
     * The eight warping parameters, or the one-bit refinements of an EP-picture.
     */
    final int[] warping = new int[8];

    /**
     * Whether {@link #warping} holds the one-bit refinements of a spatial scalability EP-picture.
     */
    boolean warpingRefinement;

    /**
     * Whether the warping parameters were sent.
     */
    boolean warpingSent;

    /**
     * The fill mode, 0 to 3 as Table P.1.
     */
    int fillMode;

    /**
     * The fill color, as Y, Cb and Cr.
     */
    final int[] fillColor = new int[3];

    /**
     * The duration of the picture clock period in ticks of 1/1800000 second.
     */
    int periodTicks;

    /**
     * The duration of the picture clock period before the header, which its temporal reference is measured in.
     */
    int previousPeriodTicks;

    /**
     * The bit position of the picture data after the header.
     */
    long dataPosition;

    /**
     * Creates a header.
     */
    PictureHeader() {
    }

    /**
     * Returns whether a mode is in effect.
     *
     * @param mode a {@code LayerState} mode bit
     * @return whether it is on
     */
    boolean has(int mode) {
        return (modes & mode) != 0;
    }

    /**
     * Returns the sub-bitstream of the picture whose header starts at the reader's position, without consuming bits.
     *
     * @param reader the stream, positioned at the picture start code
     * @return the PSBI, or 0 without Continuous Presence Multipoint
     */
    static int peekSubBitstream(BitReader reader) {
        var start = reader.position();
        var format = reader.peekAt(start + 35, 3);
        long cpmAt;
        if (format != 7) {
            cpmAt = start + 48;
        } else {
            var ufep = reader.peekAt(start + 38, 3);
            cpmAt = start + 41 + (ufep == 1 ? 18 : 0) + 9;
        }
        return reader.peekAt(cpmAt, 1) == 1 ? reader.peekAt(cpmAt + 1, 2) : 0;
    }

    /**
     * Parses a picture header.
     *
     * @param reader the stream, positioned at the picture start code
     * @param streams the sub-bitstreams, whose state the header updates
     * @throws MediaException.Malformed if the header is corrupt
     * @throws MediaException.Unsupported if the header uses a mode the decoder lacks
     */
    void parse(BitReader reader, SubBitstream[] streams) {
        if (reader.read(22) != 0x20) {
            throw new MediaException.Malformed("missing H.263 picture start code");
        }
        var tr = reader.read(8);
        if (reader.readBit() != 1 || reader.readBit() != 0) {
            throw new MediaException.Malformed("invalid H.263 PTYPE");
        }
        reader.skip(3);
        var format = reader.read(3);
        pbFrame = false;
        trp = -1;
        resampling = false;
        reducedResolution = false;
        rounding = 0;
        trb = 0;
        dbquant = 0;
        layer = 1;
        referenceLayer = 1;
        warpingSent = false;
        warpingRefinement = false;
        if (format != 7) {
            parseBaseline(reader, streams, tr, format);
        } else {
            parsePlus(reader, streams, tr);
        }
        var guard = 0;
        while (reader.readBit() == 1) {
            reader.skip(8);
            if (++guard > 1 << 16 || reader.overrun()) {
                throw new MediaException.Malformed("runaway H.263 PSUPP");
            }
        }
        if (reader.overrun()) {
            throw new MediaException.Malformed("truncated H.263 picture header");
        }
        if (quant < 1 || quant > 31) {
            throw new MediaException.Malformed("H.263 PQUANT " + quant);
        }
        if (width < 4 || height < 4) {
            throw new MediaException.Malformed("H.263 picture of size " + width + "x" + height);
        }
        dataPosition = reader.position();
    }

    /**
     * Parses the rest of a header without PLUSPTYPE.
     *
     * @param reader the stream
     * @param streams the sub-bitstreams
     * @param tr the temporal reference
     * @param format the source format
     */
    private void parseBaseline(BitReader reader, SubBitstream[] streams, int tr, int format) {
        if (format == 0 || format == 6) {
            throw new MediaException.Malformed("reserved H.263 source format " + format);
        }
        plus = false;
        ufep = 0;
        type = reader.readBit() == 0 ? I : P;
        var mode = 0;
        if (reader.readFlag()) {
            mode |= LayerState.UMV;
        }
        if (reader.readFlag()) {
            mode |= LayerState.SAC;
        }
        if (reader.readFlag()) {
            mode |= LayerState.AP;
        }
        pbFrame = reader.readFlag();
        if (pbFrame && type == I) {
            throw new MediaException.Malformed("H.263 PB-frame with an INTRA picture type");
        }
        quant = reader.read(5);
        cpm = reader.readFlag();
        subBitstream = cpm ? reader.read(2) : 0;
        var stream = streams[subBitstream];
        stream.cpm = cpm;
        var state = stream.layer(1);
        state.known = true;
        state.modes = 0;
        state.format = format;
        state.parWidth = 12;
        state.parHeight = 11;
        state.rectangularSlices = false;
        state.arbitrarySliceOrder = false;
        state.unlimitedVectors = false;
        modes = mode;
        rectangularSlices = false;
        arbitrarySliceOrder = false;
        unlimitedVectors = false;
        width = state.width();
        height = state.height();
        parWidth = 12;
        parHeight = 11;
        previousPeriodTicks = stream.periodTicks;
        stream.customClock = false;
        stream.periodTicks = 60060;
        periodTicks = 60060;
        temporalReference = tr;
        extendedReference = false;
        if (pbFrame) {
            trb = reader.read(3);
            dbquant = reader.read(2);
        }
        state.lastType = type;
    }

    /**
     * Parses the rest of a header with PLUSPTYPE.
     *
     * @param reader the stream
     * @param streams the sub-bitstreams
     * @param tr the temporal reference
     */
    private void parsePlus(BitReader reader, SubBitstream[] streams, int tr) {
        plus = true;
        ufep = reader.read(3);
        if (ufep > 1) {
            throw new MediaException.Malformed("reserved H.263 UFEP " + ufep);
        }
        var format = 0;
        var customClock = false;
        var optional = 0;
        if (ufep == 1) {
            format = reader.read(3);
            customClock = reader.readFlag();
            optional = reader.read(10) << 2;
            if (reader.readBit() != 1) {
                throw new MediaException.Malformed("invalid H.263 OPPTYPE");
            }
            if (reader.readFlag()) {
                optional |= LayerState.ERPS;
            }
            if (reader.readFlag()) {
                optional |= LayerState.DPS;
            }
            reader.skip(1);
            if (format == 0 || format == 7) {
                throw new MediaException.Malformed("reserved H.263 source format " + format);
            }
        }
        type = reader.read(3);
        if (type > EP) {
            throw new MediaException.Malformed("reserved H.263 picture type " + type);
        }
        resampling = reader.readFlag();
        reducedResolution = reader.readFlag();
        rounding = reader.readBit();
        reader.skip(2);
        if (reader.readBit() != 1) {
            throw new MediaException.Malformed("invalid H.263 MPPTYPE");
        }
        cpm = reader.readFlag();
        subBitstream = cpm ? reader.read(2) : 0;
        var stream = streams[subBitstream];
        stream.cpm = cpm;
        var customWidth = 0;
        var customHeight = 0;
        var par = -1;
        var epar = new int[2];
        if (ufep == 1 && format == 6) {
            par = reader.read(4);
            customWidth = (reader.read(9) + 1) * 4;
            if (reader.readBit() != 1) {
                throw new MediaException.Malformed("invalid H.263 CPFMT");
            }
            customHeight = reader.read(9) * 4;
            if (par == 15) {
                epar[0] = reader.read(8);
                epar[1] = reader.read(8);
            }
        }
        previousPeriodTicks = stream.periodTicks;
        if (ufep == 1) {
            stream.customClock = customClock;
            if (customClock) {
                var conversion = reader.readBit() == 0 ? 1000 : 1001;
                var divisor = reader.read(7);
                if (divisor == 0) {
                    throw new MediaException.Malformed("zero H.263 clock divisor");
                }
                stream.periodTicks = conversion * divisor;
            } else {
                stream.periodTicks = 60060;
            }
        }
        periodTicks = stream.periodTicks;
        temporalReference = tr;
        extendedReference = stream.customClock;
        if (stream.customClock) {
            temporalReference |= reader.read(2) << 8;
        }
        var unlimited = false;
        if (ufep == 1 && (optional & LayerState.UMV) != 0) {
            unlimited = reader.readBit() == 0;
            if (unlimited && reader.readBit() != 1) {
                throw new MediaException.Malformed("invalid H.263 UUI");
            }
        }
        var rect = false;
        var aso = false;
        if (ufep == 1 && (optional & LayerState.SS) != 0) {
            rect = reader.readFlag();
            aso = reader.readFlag();
        }
        layer = 1;
        referenceLayer = 1;
        var enhancement = type == B || type == EI || type == EP;
        var explicitReference = false;
        if (enhancement) {
            layer = reader.read(4);
            if (ufep == 1) {
                referenceLayer = reader.read(4);
                explicitReference = true;
            }
            if (layer < 1) {
                throw new MediaException.Malformed("H.263 enhancement layer number 0");
            }
        }
        var state = stream.layer(layer);
        if (ufep == 1) {
            state.known = true;
            state.modes = optional;
            state.format = format;
            if (format == 6) {
                state.customWidth = customWidth;
                state.customHeight = customHeight;
                if (par == 15) {
                    state.parWidth = epar[0];
                    state.parHeight = epar[1];
                } else if (par >= 1 && par <= 5) {
                    state.parWidth = ASPECT[par][0];
                    state.parHeight = ASPECT[par][1];
                } else {
                    state.parWidth = 0;
                    state.parHeight = 0;
                }
            } else {
                state.parWidth = 12;
                state.parHeight = 11;
            }
            state.unlimitedVectors = unlimited;
            state.rectangularSlices = rect;
            state.arbitrarySliceOrder = aso;
        } else if (!state.known) {
            var base = stream.layer(1);
            if (!base.known) {
                throw new MediaException.Malformed("H.263 picture without the optional PLUSPTYPE before");
            }
            state.known = true;
            state.modes = base.modes;
            state.format = base.format;
            state.customWidth = base.customWidth;
            state.customHeight = base.customHeight;
            state.parWidth = base.parWidth;
            state.parHeight = base.parHeight;
            state.unlimitedVectors = base.unlimitedVectors;
            state.rectangularSlices = base.rectangularSlices;
            state.arbitrarySliceOrder = base.arbitrarySliceOrder;
        }
        if (enhancement && !explicitReference) {
            if (type == B) {
                referenceLayer = state.lastType == EI || state.lastType == EP ? layer : state.lastReferenceLayer;
            } else {
                referenceLayer = state.lastReferenceLayer;
            }
        }
        if (enhancement) {
            state.lastReferenceLayer = referenceLayer;
        }
        state.lastType = type;
        if (enhancement && (referenceLayer > layer || (type != B && referenceLayer == layer))) {
            throw new MediaException.Malformed("H.263 reference layer " + referenceLayer + " for layer " + layer);
        }
        modes = applicable(state.modes, type);
        if ((modes & LayerState.DPS) != 0) {
            // TODO: decode the Data Partitioned Slice mode of Annex V
            throw new MediaException.Unsupported("h263 data partitioned slices",
                    "H.263 Annex V data partitioned slices are not supported");
        }
        if ((modes & LayerState.RPS) != 0 && (modes & LayerState.ERPS) != 0) {
            throw new MediaException.Malformed("H.263 RPS and ERPS modes together");
        }
        rectangularSlices = state.rectangularSlices;
        arbitrarySliceOrder = state.arbitrarySliceOrder;
        unlimitedVectors = state.unlimitedVectors;
        width = state.width();
        height = state.height();
        parWidth = state.parWidth;
        parHeight = state.parHeight;
        if (type == B && ufep == 0) {
            var subsequent = stream.anchor(referenceLayer);
            if (subsequent != null) {
                width = subsequent.width;
                height = subsequent.height;
            }
        }
        if ((modes & LayerState.RPS) != 0) {
            if (ufep == 1) {
                state.rpsmf = reader.read(3);
            }
            if (reader.readFlag()) {
                trp = reader.read(10);
            }
            readBackChannel(reader, cpm);
        }
        if ((modes & LayerState.ERPS) != 0) {
            state.rpsmf = reader.read(3);
            pictureNumber = reader.read(10);
            var buffer = stream.buffer(layer);
            erps.parse(reader, type, width, height, buffer.spwi, buffer.sphi);
        }
        if (resampling) {
            parseResampling(reader, stream);
        }
        quant = reader.read(5);
        if (type == IMPROVED_PB) {
            trb = reader.read(stream.customClock ? 5 : 3);
            dbquant = reader.read(2);
        }
    }

    /**
     * Applies the restrictions of 5.1.4.5 to the modes of a picture type.
     *
     * @param modes the modes in effect for the layer
     * @param type the picture type
     * @return the modes of the picture
     */
    private static int applicable(int modes, int type) {
        return switch (type) {
            case I -> modes & ~(LayerState.UMV | LayerState.AP | LayerState.AIV);
            case B -> modes & ~(LayerState.SAC | LayerState.DF | LayerState.AP);
            case EI -> modes & ~(LayerState.UMV | LayerState.SAC | LayerState.AP | LayerState.AIV);
            case EP -> modes & ~(LayerState.SAC | LayerState.AP);
            default -> modes;
        };
    }

    /**
     * Skips the back-channel messages of the videomux mode (BCI and BCM, N.4.2).
     *
     * @param reader the stream
     * @param cpm whether Continuous Presence Multipoint is on
     */
    void readBackChannel(BitReader reader, boolean cpm) {
        var guard = 0;
        while (reader.readBit() == 1) {
            var bt = reader.read(2);
            reader.skip(1 + 10);
            if (reader.readFlag()) {
                reader.skip(4);
            }
            if (reader.readFlag()) {
                reader.skip(2);
            }
            reader.marker("BEPB1");
            reader.skip(has(LayerState.SS) ? sliceAddressBits() : 5);
            reader.marker("BEPB2");
            if (bt == 0b10) {
                reader.skip(10);
            }
            if (++guard > 4096 || reader.overrun()) {
                throw new MediaException.Malformed("runaway H.263 back-channel message");
            }
        }
        if (reader.readBit() != 1) {
            throw new MediaException.Malformed("invalid H.263 BCI");
        }
    }

    /**
     * Parses the Reference Picture Resampling parameters (P.2).
     *
     * @param reader the stream
     * @param stream the sub-bitstream
     */
    private void parseResampling(BitReader reader, SubBitstream stream) {
        var wda = reader.read(2);
        if (wda < 2) {
            throw new MediaException.Malformed("reserved H.263 warping displacement accuracy " + wda);
        }
        warpingAccuracy = wda == 2 ? 2 : 16;
        if (type == EP) {
            var reference = stream.anchor(referenceLayer);
            var horizontal = reference != null && width > reference.width;
            var vertical = reference != null && height > reference.height;
            warpingRefinement = horizontal || vertical;
            for (var i = 0; i < 8; i++) {
                var upsampled = (i & 1) == 0 ? horizontal : vertical;
                warping[i] = upsampled ? reader.readBit() : -1;
            }
            return;
        }
        if (type != P && type != B && type != IMPROVED_PB) {
            throw new MediaException.Malformed("H.263 resampling parameters in an INTRA picture");
        }
        warpingSent = true;
        for (var i = 0; i < 8; i += 2) {
            var x = PlusTables.readReversible(reader);
            var y = PlusTables.readReversible(reader);
            if (x == PlusTables.INVALID || y == PlusTables.INVALID) {
                throw new MediaException.Malformed("invalid H.263 warping parameter");
            }
            if (x == 1 && y == 1) {
                reader.marker("warping parameters");
            }
            warping[i] = x;
            warping[i + 1] = y;
        }
        fillMode = reader.read(2);
        if (fillMode == 0) {
            fillColor[0] = reader.read(8);
            reader.marker("CB_EPB");
            fillColor[1] = reader.read(8);
            reader.marker("CR_EPB");
            fillColor[2] = reader.read(8);
        } else if (fillMode == 1) {
            fillColor[0] = 16;
            fillColor[1] = 128;
            fillColor[2] = 128;
        } else {
            fillColor[0] = 128;
            fillColor[1] = 128;
            fillColor[2] = 128;
        }
    }

    /**
     * Returns the number of macroblocks of the coded picture: 16 by 16, or 32 by 32 in the Reduced-Resolution Update
     * mode.
     *
     * @return the count
     */
    int macroblocks() {
        var size = reducedResolution ? 32 : 16;
        return ((width + size - 1) / size) * ((height + size - 1) / size);
    }

    /**
     * Returns the width of the MBA field of slice headers (Table K.2).
     *
     * @return the bits
     */
    int sliceAddressBits() {
        var count = macroblocks();
        if (reducedResolution) {
            return count <= 12 ? 5 : count <= 30 ? 6 : count <= 99 ? 7 : count <= 396 ? 9 : count <= 1584 ? 11 : 12;
        }
        return count <= 48 ? 6 : count <= 99 ? 7 : count <= 396 ? 9 : count <= 1584 ? 11 : count <= 6336 ? 13 : 14;
    }

    /**
     * Returns the width of the SWI field of slice headers (Table K.3).
     *
     * @return the bits
     */
    int sliceWidthBits() {
        if (reducedResolution) {
            return width <= 176 ? 3 : width <= 352 ? 4 : width <= 704 ? 5 : 6;
        }
        return width <= 176 ? 4 : width <= 352 ? 5 : width <= 704 ? 6 : 7;
    }
}
