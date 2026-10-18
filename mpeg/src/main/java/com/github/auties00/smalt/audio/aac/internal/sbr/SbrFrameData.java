package com.github.auties00.smalt.audio.aac.internal.sbr;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;

/**
 * Holds the spectral band replication data of one channel for one frame: the time grid, the
 * coding directions, the inverse filtering modes, the envelope and noise floor scalefactors and
 * the sinusoid flags.
 *
 * <p>The reading methods store the scalefactors as coded (start values and deltas); the channel
 * resolves the deltas against its previous values once the whole extension payload parsed.
 * Every method throws {@link MediaException.Malformed} for a value the syntax does not allow.
 */
final class SbrFrameData {
    /**
     * The frame class with fixed borders at both ends.
     */
    static final int FIXFIX = 0;

    /**
     * The frame class with a fixed leading and a variable trailing border.
     */
    static final int FIXVAR = 1;

    /**
     * The frame class with a variable leading and a fixed trailing border.
     */
    static final int VARFIX = 2;

    /**
     * The frame class with variable borders at both ends.
     */
    static final int VARVAR = 3;

    /**
     * The largest number of envelopes of a frame.
     */
    static final int MAX_ENVELOPES = 5;

    /**
     * The frame class.
     */
    int frameClass;

    /**
     * The number of envelopes, {@code L_E}.
     */
    int envelopes;

    /**
     * The envelope borders {@code t_E}, in time slots of two QMF slots each.
     */
    final int[] borders = new int[MAX_ENVELOPES + 1];

    /**
     * The relative borders a variable grid reads before resolving them, the leading ones or
     * those of a {@code FIXVAR} or {@code VARFIX} frame.
     */
    private final int[] relative = new int[MAX_ENVELOPES];

    /**
     * The trailing relative borders of a {@code VARVAR} frame.
     */
    private final int[] trailingRelative = new int[3];

    /**
     * The frequency resolution of each envelope: 0 for low, 1 for high.
     */
    final int[] resolution = new int[MAX_ENVELOPES];

    /**
     * The index of the envelope that starts at a transient, {@code l_A}, or {@code -1}; it may
     * equal {@link #envelopes}, which places the transient at the start of the next frame.
     */
    int transientEnvelope;

    /**
     * The number of noise floor envelopes, {@code L_Q}.
     */
    int noiseEnvelopes;

    /**
     * The noise floor borders {@code t_Q}, in time slots.
     */
    final int[] noiseBorders = new int[3];

    /**
     * The amplitude resolution of the envelope data: 0 for 1.5 dB, 1 for 3 dB.
     */
    int ampResolution;

    /**
     * Whether each envelope is coded as a delta in time rather than in frequency.
     */
    final boolean[] envelopeTime = new boolean[MAX_ENVELOPES];

    /**
     * Whether each noise floor envelope is coded as a delta in time.
     */
    final boolean[] noiseTime = new boolean[2];

    /**
     * The inverse filtering mode of each noise floor band, from 0 (off) to 3 (strong).
     */
    final int[] inverseFiltering = new int[SbrFrequencies.MAX_NOISE_BANDS];

    /**
     * The envelope scalefactors, as coded and then resolved, indexed by envelope and band.
     */
    final int[][] envelope = new int[MAX_ENVELOPES][64];

    /**
     * The noise floor scalefactors, as coded and then resolved, indexed by envelope and band.
     */
    final int[][] noise = new int[2][SbrFrequencies.MAX_NOISE_BANDS];

    /**
     * Whether any sinusoid flag is set.
     */
    boolean harmonics;

    /**
     * The sinusoid flag of each high resolution band.
     */
    final boolean[] harmonic = new boolean[64];

    /**
     * Whether this channel carries the balance of a coupled pair rather than levels.
     */
    boolean balance;

    /**
     * Creates the data of a channel with no frame read yet.
     */
    SbrFrameData() {
    }

    /**
     * Reads the time grid, {@code sbr_grid}.
     *
     * @param reader    the bit source
     * @param timeSlots the number of time slots per frame, 16 or 15
     * @throws MediaException.Malformed if the grid is invalid
     */
    void readGrid(AacBitReader reader, int timeSlots) {
        frameClass = reader.read(2);
        var pointer = 0;
        switch (frameClass) {
            case FIXFIX -> {
                envelopes = 1 << reader.read(2);
                if (envelopes > 4) {
                    throw new MediaException.Malformed("SBR FIXFIX frame with " + envelopes + " envelopes");
                }
                var res = reader.read(1);
                var step = (timeSlots + (envelopes >> 1)) / envelopes;
                for (var l = 0; l < envelopes; l++) {
                    resolution[l] = res;
                    borders[l] = l * step;
                }
                borders[envelopes] = timeSlots;
            }
            case FIXVAR -> {
                var trailing = timeSlots + reader.read(2);
                var count = reader.read(2);
                envelopes = count + 1;
                for (var k = 0; k < count; k++) {
                    relative[k] = 2 * reader.read(2) + 2;
                }
                pointer = reader.read(pointerBits(envelopes));
                for (var l = 0; l < envelopes; l++) {
                    resolution[envelopes - 1 - l] = reader.read(1);
                }
                borders[0] = 0;
                borders[envelopes] = trailing;
                for (var l = envelopes - 1; l >= 1; l--) {
                    borders[l] = borders[l + 1] - relative[envelopes - 1 - l];
                }
            }
            case VARFIX -> {
                var leading = reader.read(2);
                var count = reader.read(2);
                envelopes = count + 1;
                for (var k = 0; k < count; k++) {
                    relative[k] = 2 * reader.read(2) + 2;
                }
                pointer = reader.read(pointerBits(envelopes));
                for (var l = 0; l < envelopes; l++) {
                    resolution[l] = reader.read(1);
                }
                borders[0] = leading;
                for (var l = 1; l < envelopes; l++) {
                    borders[l] = borders[l - 1] + relative[l - 1];
                }
                borders[envelopes] = timeSlots;
            }
            default -> {
                var leading = reader.read(2);
                var trailing = timeSlots + reader.read(2);
                var leadingCount = reader.read(2);
                var trailingCount = reader.read(2);
                envelopes = leadingCount + trailingCount + 1;
                if (envelopes > MAX_ENVELOPES) {
                    throw new MediaException.Malformed("SBR VARVAR frame with " + envelopes + " envelopes");
                }
                for (var k = 0; k < leadingCount; k++) {
                    relative[k] = 2 * reader.read(2) + 2;
                }
                for (var k = 0; k < trailingCount; k++) {
                    trailingRelative[k] = 2 * reader.read(2) + 2;
                }
                pointer = reader.read(pointerBits(envelopes));
                for (var l = 0; l < envelopes; l++) {
                    resolution[l] = reader.read(1);
                }
                borders[0] = leading;
                for (var l = 1; l <= leadingCount; l++) {
                    borders[l] = borders[l - 1] + relative[l - 1];
                }
                borders[envelopes] = trailing;
                for (var l = envelopes - 1; l > leadingCount; l--) {
                    borders[l] = borders[l + 1] - trailingRelative[envelopes - 1 - l];
                }
            }
        }
        if (pointer > envelopes + 1) {
            throw new MediaException.Malformed("SBR transient pointer " + pointer + " beyond " + envelopes + " envelopes");
        }
        for (var l = 0; l < envelopes; l++) {
            if (borders[l] >= borders[l + 1] || borders[l] < 0) {
                throw new MediaException.Malformed("SBR envelope borders are not increasing");
            }
        }
        transientEnvelope = switch (frameClass) {
            case FIXFIX -> -1;
            case VARFIX -> pointer <= 1 ? -1 : pointer - 1;
            default -> pointer == 0 ? -1 : envelopes + 1 - pointer;
        };
        noiseBorders[0] = borders[0];
        if (envelopes > 1) {
            var middle = switch (frameClass) {
                case FIXFIX -> envelopes >> 1;
                case VARFIX -> pointer == 0 ? 1 : pointer == 1 ? envelopes - 1 : pointer - 1;
                default -> pointer <= 1 ? envelopes - 1 : envelopes + 1 - pointer;
            };
            noiseEnvelopes = 2;
            noiseBorders[1] = borders[middle];
            noiseBorders[2] = borders[envelopes];
        } else {
            noiseEnvelopes = 1;
            noiseBorders[1] = borders[1];
        }
    }

    /**
     * Copies the time grid of another channel, for a coupled pair.
     *
     * @param other the channel whose grid is copied
     */
    void copyGrid(SbrFrameData other) {
        frameClass = other.frameClass;
        envelopes = other.envelopes;
        System.arraycopy(other.borders, 0, borders, 0, borders.length);
        System.arraycopy(other.resolution, 0, resolution, 0, resolution.length);
        transientEnvelope = other.transientEnvelope;
        noiseEnvelopes = other.noiseEnvelopes;
        System.arraycopy(other.noiseBorders, 0, noiseBorders, 0, noiseBorders.length);
    }

    /**
     * Returns the number of bits of the transient pointer of a frame.
     *
     * @param envelopes the number of envelopes
     * @return {@code ceil(log2(envelopes + 1))}
     */
    private static int pointerBits(int envelopes) {
        return 32 - Integer.numberOfLeadingZeros(envelopes);
    }

    /**
     * Reads the coding directions of the envelopes and noise floors, {@code sbr_dtdf}.
     *
     * @param reader the bit source
     */
    void readDirections(AacBitReader reader) {
        for (var l = 0; l < envelopes; l++) {
            envelopeTime[l] = reader.readFlag();
        }
        for (var l = 0; l < noiseEnvelopes; l++) {
            noiseTime[l] = reader.readFlag();
        }
    }

    /**
     * Reads the inverse filtering modes, {@code sbr_invf}.
     *
     * @param reader the bit source
     * @param bands  the number of noise floor bands
     */
    void readInverseFiltering(AacBitReader reader, int bands) {
        for (var i = 0; i < bands; i++) {
            inverseFiltering[i] = reader.read(2);
        }
    }

    /**
     * Copies the inverse filtering modes of another channel, for a coupled pair.
     *
     * @param other the channel whose modes are copied
     */
    void copyInverseFiltering(SbrFrameData other) {
        System.arraycopy(other.inverseFiltering, 0, inverseFiltering, 0, inverseFiltering.length);
    }

    /**
     * Reads the envelope scalefactors, {@code sbr_envelope}.
     *
     * @param reader        the bit source
     * @param tables        the frequency tables
     * @param ampResolution the header's amplitude resolution
     * @param balance       whether this channel carries the balance of a coupled pair
     */
    void readEnvelope(AacBitReader reader, SbrFrequencies tables, int ampResolution, boolean balance) {
        this.balance = balance;
        this.ampResolution = frameClass == FIXFIX && envelopes == 1 ? 0 : ampResolution;
        var shift = balance ? 1 : 0;
        byte[] time;
        byte[] frequency;
        int startBits;
        if (this.ampResolution == 0) {
            time = balance ? SbrHuffman.BALANCE_15_TIME : SbrHuffman.ENVELOPE_15_TIME;
            frequency = balance ? SbrHuffman.BALANCE_15_FREQUENCY : SbrHuffman.ENVELOPE_15_FREQUENCY;
            startBits = balance ? 6 : 7;
        } else {
            time = balance ? SbrHuffman.BALANCE_30_TIME : SbrHuffman.ENVELOPE_30_TIME;
            frequency = balance ? SbrHuffman.BALANCE_30_FREQUENCY : SbrHuffman.ENVELOPE_30_FREQUENCY;
            startBits = balance ? 5 : 6;
        }
        for (var l = 0; l < envelopes; l++) {
            var bands = tables.bands(resolution[l]);
            var values = envelope[l];
            if (envelopeTime[l]) {
                for (var k = 0; k < bands; k++) {
                    values[k] = SbrHuffman.decode(time, reader) << shift;
                }
            } else {
                values[0] = reader.read(startBits) << shift;
                for (var k = 1; k < bands; k++) {
                    values[k] = SbrHuffman.decode(frequency, reader) << shift;
                }
            }
        }
    }

    /**
     * Reads the noise floor scalefactors, {@code sbr_noise}.
     *
     * @param reader  the bit source
     * @param bands   the number of noise floor bands
     * @param balance whether this channel carries the balance of a coupled pair
     */
    void readNoise(AacBitReader reader, int bands, boolean balance) {
        var shift = balance ? 1 : 0;
        var time = balance ? SbrHuffman.NOISE_BALANCE_TIME : SbrHuffman.NOISE_TIME;
        var frequency = balance ? SbrHuffman.BALANCE_30_FREQUENCY : SbrHuffman.ENVELOPE_30_FREQUENCY;
        for (var l = 0; l < noiseEnvelopes; l++) {
            var values = noise[l];
            if (noiseTime[l]) {
                for (var k = 0; k < bands; k++) {
                    values[k] = SbrHuffman.decode(time, reader) << shift;
                }
            } else {
                values[0] = reader.read(5) << shift;
                for (var k = 1; k < bands; k++) {
                    values[k] = SbrHuffman.decode(frequency, reader) << shift;
                }
            }
        }
    }

    /**
     * Reads the sinusoid flags, {@code sbr_sinusoidal_coding}, behind their presence flag.
     *
     * @param reader the bit source
     * @param bands  the number of high resolution bands
     */
    void readHarmonics(AacBitReader reader, int bands) {
        harmonics = reader.readFlag();
        for (var k = 0; k < bands; k++) {
            harmonic[k] = harmonics && reader.readFlag();
        }
    }
}
