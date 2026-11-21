package com.github.auties00.smalt.video.vc1.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Holds the coding controls of a VC-1 entry point segment: the advanced profile entry-point header of SMPTE ST 421
 * section 6.2, or the equivalent sequence level controls of the simple and main profiles.
 *
 * <p>Instances are mutable and owned by one decoder.
 */
final class EntryPoint {
    /**
     * BROKEN_LINK: whether the B pictures that follow the entry point's I picture lost their forward anchor.
     */
    boolean brokenLink;

    /**
     * CLOSED_ENTRY: whether the B pictures after the entry point predict from no picture before it.
     */
    boolean closedEntry;

    /**
     * PANSCAN_FLAG: whether picture headers carry pan-scan windows.
     */
    boolean panScan;

    /**
     * REFDIST_FLAG: whether field pictures carry REFDIST.
     */
    boolean referenceDistance;

    /**
     * LOOPFILTER: whether the in-loop deblocking filter runs.
     */
    boolean loopFilter;

    /**
     * FASTUVMC: whether color-difference motion vectors round to half samples.
     */
    boolean fastUvMc;

    /**
     * EXTENDED_MV: whether pictures carry MVRANGE.
     */
    boolean extendedMv;

    /**
     * DQUANT: 0 for one quantizer per picture, 1 for VOPDQUANT-signaled macroblock quantizers, 2 for the alternate
     * quantizer on every picture edge.
     */
    int dquant;

    /**
     * VSTRANSFORM: whether inter blocks may use the 8x4, 4x8 and 4x4 transforms.
     */
    boolean variableTransform;

    /**
     * OVERLAP: whether overlap smoothing may run.
     */
    boolean overlap;

    /**
     * QUANTIZER: 0 for the quantizer PQINDEX implies, 1 for PQUANTIZER signaling, 2 for nonuniform, 3 for uniform.
     */
    int quantizer;

    /**
     * The coded picture width, from CODED_WIDTH or the sequence's maximum.
     */
    int codedWidth;

    /**
     * The coded picture height, from CODED_HEIGHT or the sequence's maximum.
     */
    int codedHeight;

    /**
     * EXTENDED_DMV: whether P and B pictures carry DMVRANGE.
     */
    boolean extendedDmv;

    /**
     * RANGE_MAPY, the luma range mapping, or -1 when absent.
     */
    int rangeMapY = -1;

    /**
     * RANGE_MAPUV, the color-difference range mapping, or -1 when absent.
     */
    int rangeMapUv = -1;

    /**
     * Copies the controls of a simple or main profile sequence.
     *
     * @param sequence the sequence header in force
     * @param width the coded width
     * @param height the coded height
     */
    void fromSequence(SequenceHeader sequence, int width, int height) {
        brokenLink = false;
        closedEntry = true;
        panScan = false;
        referenceDistance = false;
        loopFilter = sequence.loopFilter;
        fastUvMc = sequence.fastUvMc;
        extendedMv = sequence.extendedMv;
        dquant = sequence.dquant;
        variableTransform = sequence.variableTransform;
        overlap = sequence.overlap;
        quantizer = sequence.quantizer;
        codedWidth = width;
        codedHeight = height;
        extendedDmv = false;
        rangeMapY = -1;
        rangeMapUv = -1;
    }

    /**
     * Parses an advanced profile entry-point header (Table 14).
     *
     * @param bits the reader, after the start code
     * @param sequence the sequence the entry point belongs to
     * @throws MediaException.Malformed if the header is truncated or a field takes a reserved value
     */
    void parse(Bits bits, SequenceHeader sequence) {
        brokenLink = bits.readFlag();
        closedEntry = bits.readFlag();
        panScan = bits.readFlag();
        referenceDistance = bits.readFlag();
        loopFilter = bits.readFlag();
        fastUvMc = bits.readFlag();
        extendedMv = bits.readFlag();
        dquant = bits.read(2);
        if (dquant == 3) {
            throw new MediaException.Malformed("reserved VC-1 DQUANT value 3");
        }
        variableTransform = bits.readFlag();
        overlap = bits.readFlag();
        quantizer = bits.read(2);
        bits.skip(8L * sequence.leakyBuckets);
        if (bits.readFlag()) {
            codedWidth = bits.read(12) * 2 + 2;
            codedHeight = bits.read(12) * 2 + 2;
        } else {
            codedWidth = sequence.maxCodedWidth;
            codedHeight = sequence.maxCodedHeight;
        }
        extendedDmv = extendedMv && bits.readFlag();
        rangeMapY = bits.readFlag() ? bits.read(3) : -1;
        rangeMapUv = bits.readFlag() ? bits.read(3) : -1;
        if (bits.overrun()) {
            throw new MediaException.Malformed("truncated VC-1 entry-point header");
        }
    }
}
