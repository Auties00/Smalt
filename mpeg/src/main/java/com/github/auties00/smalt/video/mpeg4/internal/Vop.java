package com.github.auties00.smalt.video.mpeg4.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Holds the header of one video object plane (ISO/IEC 14496-2 6.2.5) or of one H.263 picture.
 *
 * <p>{@link #parse(BitReader, VideoObjectLayer)} reads a {@code VideoObjectPlane()} header from the
 * bit after its start code; H.263 picture headers fill the same fields through their own parser.
 * Instances are mutable value holders reused by one decoder; they are not thread-safe.
 */
public final class Vop {
    /**
     * The coding type of an intra-coded VOP.
     */
    public static final int I = 0;

    /**
     * The coding type of a predictive-coded VOP.
     */
    public static final int P = 1;

    /**
     * The coding type of a bidirectionally predictive-coded VOP.
     */
    public static final int B = 2;

    /**
     * The coding type of a sprite (global motion compensated) VOP.
     */
    public static final int S = 3;

    /**
     * The {@code vop_coding_type}: {@link #I}, {@link #P}, {@link #B} or {@link #S}.
     */
    public int codingType;

    /**
     * The number of seconds signaled by {@code modulo_time_base}.
     */
    public int moduloTimeBase;

    /**
     * The {@code vop_time_increment}.
     */
    public int timeIncrement;

    /**
     * Whether the VOP carries coded data ({@code vop_coded}).
     */
    public boolean coded;

    /**
     * The {@code rounding_control} of motion compensation.
     */
    public int roundingType;

    /**
     * The {@code intra_dc_vlc_thr}, from 0 to 7.
     */
    public int intraDcVlcThreshold;

    /**
     * Whether the top field is displayed first.
     */
    public boolean topFieldFirst;

    /**
     * Whether the alternate vertical scan is used.
     */
    public boolean alternateVerticalScan;

    /**
     * The initial quantizer, {@code vop_quant}.
     */
    public int quant;

    /**
     * The {@code vop_fcode_forward}, from 1 to 7.
     */
    public int fcodeForward = 1;

    /**
     * The {@code vop_fcode_backward}, from 1 to 7.
     */
    public int fcodeBackward = 1;

    /**
     * The horizontal sprite trajectory differences {@code du}, one per warping point.
     */
    public final int[] du = new int[4];

    /**
     * The vertical sprite trajectory differences {@code dv}, one per warping point.
     */
    public final int[] dv = new int[4];

    /**
     * The bit position of the first macroblock after the header.
     */
    public long dataPosition;

    /**
     * Creates an empty header.
     */
    public Vop() {
    }

    /**
     * Returns an independent copy of this header.
     *
     * @return the copy
     */
    public Vop copy() {
        var copy = new Vop();
        copy.codingType = codingType;
        copy.moduloTimeBase = moduloTimeBase;
        copy.timeIncrement = timeIncrement;
        copy.coded = coded;
        copy.roundingType = roundingType;
        copy.intraDcVlcThreshold = intraDcVlcThreshold;
        copy.topFieldFirst = topFieldFirst;
        copy.alternateVerticalScan = alternateVerticalScan;
        copy.quant = quant;
        copy.fcodeForward = fcodeForward;
        copy.fcodeBackward = fcodeBackward;
        System.arraycopy(du, 0, copy.du, 0, du.length);
        System.arraycopy(dv, 0, copy.dv, 0, dv.length);
        copy.dataPosition = dataPosition;
        return copy;
    }

    /**
     * Finds the width of {@code vop_time_increment} in a stream whose encoder wrote it with another
     * width than its video object layer's {@code vop_time_increment_resolution} implies, and
     * adopts it for the layer.
     *
     * <p>The width is the smallest one from 1 to 16 that the marker bit and a coded VOP follow; the
     * layer's resolution becomes {@code 1 << width}, which keeps the increments below one second,
     * so the VOP times stay monotonic although their scale is only approximate.
     *
     * @implNote This implementation recovers the streams of early libavcodec versions, which
     * declare the reduced frame rate as the resolution (5 for 5 frames per second) but
     * code the increments in the 15 bits of a 30000 tick clock.
     * @param reader the reader, positioned at the syntax structure
     * @param layer the layer, whose increment width and resolution are replaced
     * @param position the bit position of {@code vop_time_increment}
     * @throws MediaException.Malformed if no width fits
     */
    private void recoverTimeIncrementBits(BitReader reader, VideoObjectLayer layer, long position) {
        for (var width = 1; width <= 16; width++) {
            if (width == layer.timeIncrementBits) {
                continue;
            }
            reader.seek(position);
            var increment = reader.read(width);
            if (reader.readBit() == 1 && reader.peek(1) == 1 && !reader.overrun()) {
                timeIncrement = increment;
                layer.timeIncrementBits = width;
                layer.timeResolution = Math.max(layer.timeResolution, 1 << width);
                return;
            }
        }
        throw new MediaException.Malformed("missing marker bit after vop_time_increment");
    }

    /**
     * Parses a {@code VideoObjectPlane()} header up to its first macroblock.
     *
     * @param reader the reader, positioned after {@code vop_start_code}
     * @param layer the layer the plane belongs to
     * @throws MediaException.Malformed if the header is corrupt
     */
    public void parse(BitReader reader, VideoObjectLayer layer) {
        codingType = reader.read(2);
        if (codingType == S && layer.spriteEnable != VideoObjectLayer.SPRITE_GMC) {
            throw new MediaException.Malformed("S-VOP in a layer without global motion compensation");
        }
        var seconds = 0;
        while (reader.readBit() == 1) {
            if (++seconds > 3600 || reader.overrun()) {
                throw new MediaException.Malformed("runaway modulo_time_base");
            }
        }
        moduloTimeBase = seconds;
        reader.marker("modulo_time_base");
        var incrementPosition = reader.position();
        timeIncrement = reader.read(layer.timeIncrementBits);
        if (reader.readBit() != 1) {
            recoverTimeIncrementBits(reader, layer, incrementPosition);
        }
        coded = reader.readFlag();
        roundingType = 0;
        alternateVerticalScan = false;
        topFieldFirst = false;
        fcodeForward = 1;
        fcodeBackward = 1;
        if (!coded) {
            dataPosition = reader.position();
            return;
        }
        if (codingType == P || codingType == S) {
            roundingType = reader.readBit();
        }
        if (layer.complexityEstimation) {
            layer.skipComplexityEstimation(reader, codingType);
        }
        intraDcVlcThreshold = reader.read(3);
        if (layer.interlaced) {
            topFieldFirst = reader.readFlag();
            alternateVerticalScan = reader.readFlag();
        }
        if (codingType == S) {
            for (var i = 0; i < layer.warpingPoints; i++) {
                du[i] = warpingCode(reader);
                dv[i] = warpingCode(reader);
            }
            for (var i = layer.warpingPoints; i < 4; i++) {
                du[i] = 0;
                dv[i] = 0;
            }
        }
        quant = reader.read(layer.quantPrecision);
        if (quant == 0) {
            throw new MediaException.Malformed("vop_quant is zero");
        }
        if (codingType != I) {
            fcodeForward = reader.read(3);
            if (fcodeForward == 0) {
                throw new MediaException.Malformed("vop_fcode_forward is zero");
            }
        }
        if (codingType == B) {
            fcodeBackward = reader.read(3);
            if (fcodeBackward == 0) {
                throw new MediaException.Malformed("vop_fcode_backward is zero");
            }
        }
        if (reader.overrun()) {
            throw new MediaException.Malformed("truncated VOP header");
        }
        dataPosition = reader.position();
    }

    /**
     * Reads one {@code warping_mv_code()}: a length code of Table B-33, the difference in that
     * many bits, and a marker bit.
     *
     * @param reader the reader, positioned at the syntax structure
     * @return the difference
     * @throws MediaException.Malformed if the length code is invalid
     */
    public static int warpingCode(BitReader reader) {
        var length = Tables.SPRITE_DMV_LENGTH.decode(reader);
        if (length == Vlc.INVALID) {
            throw new MediaException.Malformed("invalid dmv_length code");
        }
        var value = 0;
        if (length > 0) {
            var code = reader.read(length);
            value = (code >>> (length - 1)) != 0 ? code : code - (1 << length) + 1;
        }
        reader.marker("dmv_code");
        return value;
    }
}
