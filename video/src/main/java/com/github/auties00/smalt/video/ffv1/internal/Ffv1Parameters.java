package com.github.auties00.smalt.video.ffv1.internal;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * The coding parameters of an FFV1 stream (RFC 9043, section 4.2): read from the configuration record or the
 * header of a version 0 or 1 key frame, or set up by the encoder and written in the same forms.
 *
 * <p>Instances are immutable once built.
 */
public final class Ffv1Parameters {
    /**
     * The largest log2 chroma subsampling factor decoded.
     */
    public static final int MAX_CHROMA_SHIFT = 4;

    /**
     * The FFV1 version: 0, 1 or 3.
     */
    public final int version;

    /**
     * The micro version (versions 3 and later).
     */
    public final int microVersion;

    /**
     * The coder type: 0 Golomb Rice, 1 range coder with the default table, 2 range coder with a custom table.
     */
    public final int coderType;

    /**
     * The state transition table for a decoded 1.
     */
    public final int[] oneState;

    /**
     * The state transition table for a decoded 0.
     */
    public final int[] zeroState;

    /**
     * The color space: 0 Y'CbCr, 1 RGB through the JPEG 2000 reversible color transform.
     */
    public final int colorspace;

    /**
     * The bits per sample, from 1 to 16.
     */
    public final int bits;

    /**
     * Whether the chroma planes are present.
     */
    public final boolean chromaPlanes;

    /**
     * The binary logarithm of the horizontal chroma subsampling.
     */
    public final int chromaShiftX;

    /**
     * The binary logarithm of the vertical chroma subsampling.
     */
    public final int chromaShiftY;

    /**
     * Whether the transparency plane is present.
     */
    public final boolean extraPlane;

    /**
     * The number of columns of the slice raster.
     */
    public final int horizontalSlices;

    /**
     * The number of rows of the slice raster.
     */
    public final int verticalSlices;

    /**
     * The quantization tables of each set: {@code [set][input][256]}.
     */
    public final int[][][] quantTables;

    /**
     * The number of contexts of each set.
     */
    public final int[] contextCounts;

    /**
     * The quantization tables of each set as stored: the run lengths of the first half of each table.
     */
    final int[][][] quantRuns;

    /**
     * The initial states of each set, {@code [set][context * CONTEXT_SIZE + k]}, or {@code null} for all 128.
     */
    public final byte[][] initialStates;

    /**
     * The error detection type: 1 when each slice carries a CRC.
     */
    public final int ec;

    /**
     * Whether every frame is a key frame.
     */
    public final boolean intra;

    /**
     * Constructs parameters from their fields; the encoder builds its own with {@link #forEncoder}.
     */
    private Ffv1Parameters(int version, int microVersion, int coderType, int[] oneState, int colorspace, int bits,
                           boolean chromaPlanes, int chromaShiftX, int chromaShiftY, boolean extraPlane,
                           int horizontalSlices, int verticalSlices, int[][][] quantRuns, byte[][] initialStates,
                           int ec, boolean intra) {
        this.version = version;
        this.microVersion = microVersion;
        this.coderType = coderType;
        this.oneState = oneState;
        this.zeroState = Ffv1Tables.zeroStates(oneState);
        this.colorspace = colorspace;
        this.bits = bits;
        this.chromaPlanes = chromaPlanes;
        this.chromaShiftX = chromaShiftX;
        this.chromaShiftY = chromaShiftY;
        this.extraPlane = extraPlane;
        this.horizontalSlices = horizontalSlices;
        this.verticalSlices = verticalSlices;
        this.quantRuns = quantRuns;
        this.quantTables = new int[quantRuns.length][][];
        this.contextCounts = new int[quantRuns.length];
        for (var set = 0; set < quantRuns.length; set++) {
            quantTables[set] = new int[Ffv1Tables.CONTEXT_INPUTS][];
            long scale = 1;
            for (var input = 0; input < Ffv1Tables.CONTEXT_INPUTS; input++) {
                var levels = quantRuns[set][input].length;
                quantTables[set][input] = expand(quantRuns[set][input], (int) scale);
                scale *= 2L * levels - 1;
                if (scale > 2L * Ffv1Tables.MAX_CONTEXTS) {
                    throw new MediaException.Malformed("FFV1 quantization table set " + set + " has too many contexts");
                }
            }
            contextCounts[set] = (int) ((scale + 1) / 2);
        }
        this.initialStates = initialStates;
        this.ec = ec;
        this.intra = intra;
    }

    /**
     * Expands the run lengths of the first half of a quantization table into the 256 entries.
     */
    private static int[] expand(int[] runs, int scale) {
        var table = new int[256];
        var k = 0;
        for (var v = 0; v < runs.length; v++) {
            for (var n = 0; n < runs[v]; n++) {
                table[k++] = scale * v;
            }
        }
        for (k = 1; k < 128; k++) {
            table[256 - k] = -table[k];
        }
        table[128] = -table[127];
        return table;
    }

    /**
     * Returns encoder parameters.
     *
     * @param version 0, 1 or 3
     * @param coderType 0, 1 or 2; 2 stores the alternative state transition table
     * @param colorspace 0 or 1
     * @param bits the bits per sample
     * @param chromaPlanes whether chroma planes are coded
     * @param chromaShiftX the horizontal chroma subsampling
     * @param chromaShiftY the vertical chroma subsampling
     * @param extraPlane whether a transparency plane is coded
     * @param horizontalSlices the slice raster width (1 before version 3)
     * @param verticalSlices the slice raster height (1 before version 3)
     * @param quantRuns the run lengths of the quantization tables, {@code [set][input][levels]}
     * @param ec 1 for slice CRCs
     * @param intra whether every frame is a key frame
     * @return the parameters
     */
    public static Ffv1Parameters forEncoder(int version, int coderType, int colorspace, int bits,
                                            boolean chromaPlanes, int chromaShiftX, int chromaShiftY,
                                            boolean extraPlane, int horizontalSlices, int verticalSlices,
                                            int[][][] quantRuns, int ec, boolean intra) {
        var one = coderType == 2 ? Ffv1Tables.ALTERNATIVE_ONE_STATE : Ffv1Tables.DEFAULT_ONE_STATE;
        return new Ffv1Parameters(version, version >= 3 ? 4 : 0, coderType, one, colorspace, bits, chromaPlanes,
                chromaShiftX, chromaShiftY, extraPlane, horizontalSlices, verticalSlices, quantRuns, null, ec,
                intra);
    }

    /**
     * Reads the parameters of a version 3 configuration record and checks its CRC.
     *
     * @param record the configuration record
     * @return the parameters
     * @throws MediaException.Malformed if the record is truncated, fails its CRC or holds invalid values
     * @throws MediaException.Unsupported if the version or color space is not supported
     */
    public static Ffv1Parameters readConfigurationRecord(byte[] record) {
        if (record.length < 5) {
            throw new MediaException.Malformed("FFV1 configuration record of " + record.length + " bytes");
        }
        if (Ffv1Tables.crc(record, 0, record.length) != 0) {
            throw new MediaException.Malformed("FFV1 configuration record fails its CRC");
        }
        var decoder = new Ffv1RangeDecoder();
        decoder.reset(record, 0, record.length - 4);
        var parameters = read(decoder);
        if (parameters.version < 3) {
            throw new MediaException.Malformed("FFV1 version " + parameters.version + " in a configuration record");
        }
        return parameters;
    }

    /**
     * Reads the parameters from a range decoder positioned at them (RFC 9043, figure 28).
     *
     * @param decoder the range decoder, positioned at the parameters and left after them
     * @return the parameters
     * @throws MediaException.Malformed if a value is invalid
     * @throws MediaException.Unsupported if the version or color space is not supported
     */
    public static Ffv1Parameters read(Ffv1RangeDecoder decoder) {
        var states = new byte[Ffv1Tables.CONTEXT_SIZE];
        Arrays.fill(states, (byte) 128);
        var version = decoder.unsigned(states, "version", Integer.MAX_VALUE);
        if (version == 2) {
            // TODO: version 2 only existed in experimental form, and RFC 9043 does not describe it.
            throw new MediaException.Unsupported("ffv1", "FFV1 version 2, an unspecified experimental version");
        }
        if (version > 3) {
            throw new MediaException.Unsupported("ffv1", "FFV1 version " + version);
        }
        var micro = version >= 3 ? decoder.unsigned(states, "micro version", Integer.MAX_VALUE) : 0;
        if (version == 3 && micro < 4) {
            throw new MediaException.Unsupported("ffv1", "FFV1 version 3." + micro + ", a development version");
        }
        var coderType = decoder.unsigned(states, "coder type", Integer.MAX_VALUE);
        if (coderType > 2) {
            throw new MediaException.Unsupported("ffv1", "FFV1 coder type " + coderType);
        }
        var one = Ffv1Tables.DEFAULT_ONE_STATE.clone();
        if (coderType > 1) {
            for (var i = 1; i < 256; i++) {
                one[i] = Ffv1Tables.DEFAULT_ONE_STATE[i] + decoder.symbol(states, 0, true) & 0xFF;
            }
        }
        var colorspace = decoder.unsigned(states, "color space", Integer.MAX_VALUE);
        if (colorspace > 1) {
            throw new MediaException.Unsupported("ffv1", "FFV1 color space " + colorspace);
        }
        var bits = version >= 1 ? decoder.unsigned(states, "bits per sample", Integer.MAX_VALUE) : 8;
        if (bits == 0) {
            bits = 8;
        }
        if (bits > 16) {
            throw new MediaException.Unsupported("ffv1", "FFV1 samples of " + bits + " bits");
        }
        var chromaPlanes = decoder.bit(states, 0) != 0;
        var shiftX = decoder.unsigned(states, "horizontal chroma subsampling", Integer.MAX_VALUE);
        var shiftY = decoder.unsigned(states, "vertical chroma subsampling", Integer.MAX_VALUE);
        if (shiftX > MAX_CHROMA_SHIFT || shiftY > MAX_CHROMA_SHIFT) {
            throw new MediaException.Unsupported("ffv1", "FFV1 chroma subsampling 2^" + shiftX + " by 2^" + shiftY);
        }
        var extraPlane = decoder.bit(states, 0) != 0;
        if (colorspace == 1 && (!chromaPlanes || shiftX != 0 || shiftY != 0)) {
            throw new MediaException.Unsupported("ffv1", "FFV1 RGB without chroma planes or with subsampled planes");
        }
        var horizontal = 1;
        var vertical = 1;
        var sets = 1;
        if (version >= 3) {
            horizontal = decoder.unsigned(states, "slice raster width", 255) + 1;
            vertical = decoder.unsigned(states, "slice raster height", 255) + 1;
            sets = decoder.unsigned(states, "quantization table set count", Ffv1Tables.MAX_QUANT_SETS);
            if (sets == 0) {
                throw new MediaException.Malformed("FFV1 configuration without quantization table sets");
            }
        }
        var runs = new int[sets][][];
        for (var set = 0; set < sets; set++) {
            runs[set] = readQuantSet(decoder);
        }
        byte[][] initial = null;
        var ec = 0;
        var intra = false;
        var parameters = new Ffv1Parameters(version, micro, coderType, one, colorspace, bits, chromaPlanes, shiftX,
                shiftY, extraPlane, horizontal, vertical, runs, null, 0, false);
        if (version >= 3) {
            initial = new byte[sets][];
            for (var set = 0; set < sets; set++) {
                if (decoder.bit(states, 0) == 0) {
                    continue;
                }
                var count = parameters.contextCounts[set];
                var table = new byte[count * Ffv1Tables.CONTEXT_SIZE];
                var deltas = new byte[Ffv1Tables.CONTEXT_SIZE];
                Arrays.fill(deltas, (byte) 128);
                for (var j = 0; j < count; j++) {
                    for (var k = 0; k < Ffv1Tables.CONTEXT_SIZE; k++) {
                        var predicted = j == 0 ? 128 : table[(j - 1) * Ffv1Tables.CONTEXT_SIZE + k] & 0xFF;
                        table[j * Ffv1Tables.CONTEXT_SIZE + k] = (byte) (predicted + decoder.symbol(deltas, 0, true));
                    }
                }
                initial[set] = table;
            }
            ec = decoder.unsigned(states, "error correction type", Integer.MAX_VALUE);
            if (ec > 1) {
                throw new MediaException.Unsupported("ffv1", "FFV1 error correction type " + ec);
            }
            var intraValue = decoder.unsigned(states, "intra", Integer.MAX_VALUE);
            if (intraValue > 1) {
                throw new MediaException.Unsupported("ffv1", "FFV1 intra type " + intraValue);
            }
            intra = intraValue == 1;
        }
        return new Ffv1Parameters(version, micro, coderType, one, colorspace, bits, chromaPlanes, shiftX, shiftY,
                extraPlane, horizontal, vertical, runs, initial, ec, intra);
    }

    private static int[][] readQuantSet(Ffv1RangeDecoder decoder) {
        var set = new int[Ffv1Tables.CONTEXT_INPUTS][];
        long scale = 1;
        var runs = new int[128];
        for (var input = 0; input < Ffv1Tables.CONTEXT_INPUTS; input++) {
            var states = new byte[Ffv1Tables.CONTEXT_SIZE];
            Arrays.fill(states, (byte) 128);
            var levels = 0;
            for (var k = 0; k < 128; ) {
                var length = decoder.unsigned(states, "quantization run", 127) + 1;
                if (k + length > 128) {
                    throw new MediaException.Malformed("FFV1 quantization table overruns 128 entries");
                }
                runs[levels++] = length;
                k += length;
            }
            set[input] = Arrays.copyOf(runs, levels);
            scale *= 2L * levels - 1;
            if (scale > 2L * Ffv1Tables.MAX_CONTEXTS) {
                throw new MediaException.Malformed("FFV1 quantization table set has more than 32768 contexts");
            }
        }
        return set;
    }

    /**
     * Writes the parameters as RFC 9043, figure 28 lays them out.
     *
     * @param encoder the encoder, positioned where the parameters go
     */
    public void write(Ffv1RangeEncoder encoder) {
        var states = new byte[Ffv1Tables.CONTEXT_SIZE];
        Arrays.fill(states, (byte) 128);
        encoder.symbol(states, 0, version, false);
        if (version >= 3) {
            encoder.symbol(states, 0, microVersion, false);
        }
        encoder.symbol(states, 0, coderType, false);
        if (coderType > 1) {
            for (var i = 1; i < 256; i++) {
                var delta = oneState[i] - Ffv1Tables.DEFAULT_ONE_STATE[i];
                encoder.symbol(states, 0, delta, true);
            }
        }
        encoder.symbol(states, 0, colorspace, false);
        if (version >= 1) {
            encoder.symbol(states, 0, bits, false);
        }
        encoder.bit(states, 0, chromaPlanes ? 1 : 0);
        encoder.symbol(states, 0, chromaShiftX, false);
        encoder.symbol(states, 0, chromaShiftY, false);
        encoder.bit(states, 0, extraPlane ? 1 : 0);
        if (version >= 3) {
            encoder.symbol(states, 0, horizontalSlices - 1, false);
            encoder.symbol(states, 0, verticalSlices - 1, false);
            encoder.symbol(states, 0, quantRuns.length, false);
        }
        for (var set : quantRuns) {
            for (var input : set) {
                var tableStates = new byte[Ffv1Tables.CONTEXT_SIZE];
                Arrays.fill(tableStates, (byte) 128);
                for (var length : input) {
                    encoder.symbol(tableStates, 0, length - 1, false);
                }
            }
        }
        if (version >= 3) {
            for (var ignored : quantRuns) {
                encoder.bit(states, 0, 0);
            }
            encoder.symbol(states, 0, ec, false);
            encoder.symbol(states, 0, intra ? 1 : 0, false);
        }
    }

    /**
     * Writes the version 3 configuration record: the parameters and their CRC parity.
     *
     * @return the record
     */
    public byte[] configurationRecord() {
        var encoder = new Ffv1RangeEncoder();
        encoder.reset();
        write(encoder);
        encoder.finish();
        var size = encoder.size();
        var record = Arrays.copyOf(encoder.buffer(), size + 4);
        var crc = Ffv1Tables.crc(record, 0, size);
        record[size] = (byte) (crc >>> 24);
        record[size + 1] = (byte) (crc >>> 16);
        record[size + 2] = (byte) (crc >>> 8);
        record[size + 3] = (byte) crc;
        return record;
    }

    /**
     * {@return the number of planes a slice codes}
     */
    public int planeCount() {
        return colorspace == 1 ? 3 + (extraPlane ? 1 : 0) : 1 + (chromaPlanes ? 2 : 0) + (extraPlane ? 1 : 0);
    }

    /**
     * {@return the number of quantization table set indices of a slice header}
     */
    public int quantIndexCount() {
        return 1 + (chromaPlanes || version <= 3 ? 1 : 0) + (extraPlane ? 1 : 0);
    }

    /**
     * {@return whether the samples are predicted as signed 16-bit values (RFC 9043, section 3.3.1)}
     */
    public boolean signedPrediction() {
        return colorspace == 0 && bits == 16 && coderType >= 1;
    }
}
