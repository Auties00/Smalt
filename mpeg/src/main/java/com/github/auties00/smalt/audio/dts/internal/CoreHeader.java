package com.github.auties00.smalt.audio.dts.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.bits.MsbBitReader;

/**
 * The bit stream header of a DTS core frame (ETSI TS 102 114 Table 5-1).
 *
 * <p>Instances are not thread-safe.
 */
public final class CoreHeader {
    /**
     * The core sync word ({@code SYNC}).
     */
    public static final int SYNC = 0x7FFE8001;

    /**
     * The sample rates by {@code SFREQ}, {@code 0} where invalid (Table 5-5).
     */
    private static final int[] SAMPLE_RATES = {0, 8000, 16000, 32000, 0, 0, 11025, 22050, 44100, 0, 0, 12000, 24000,
            48000, 0, 0};

    /**
     * The primary channel count by {@code AMODE} 0 to 15 (Table 5-4).
     */
    private static final int[] AMODE_CHANNELS = {1, 2, 2, 2, 2, 3, 3, 4, 4, 5, 6, 6, 6, 7, 8, 8};

    /**
     * The target bit rates in bits per second by {@code RATE}, {@code 0} for open, {@code -1} where invalid
     * (Table 5-7).
     */
    private static final int[] RATES = {32000, 56000, 64000, 96000, 112000, 128000, 192000, 224000, 256000, 320000,
            384000, 448000, 512000, 576000, 640000, 768000, 960000, 1024000, 1152000, 1280000, 1344000, 1408000,
            1411200, 1472000, 1536000, 0, 0, 0, 0, 0, 0, -1};

    /**
     * Whether the frame is a normal frame rather than a termination frame ({@code FTYPE}).
     */
    public boolean normal;

    /**
     * The deficit sample count ({@code SHORT}).
     */
    public int deficit;

    /**
     * Whether the frame carries the obsolete CRC words ({@code CPF}).
     */
    public boolean crcPresent;

    /**
     * The number of 32-sample blocks ({@code NBLKS} + 1).
     */
    public int blocks;

    /**
     * The frame size in bytes ({@code FSIZE} + 1).
     */
    public int frameSize;

    /**
     * The audio channel arrangement ({@code AMODE}).
     */
    public int amode;

    /**
     * The core sample rate in hertz ({@code SFREQ}).
     */
    public int sampleRate;

    /**
     * The transmission rate index ({@code RATE}).
     */
    public int rate;

    /**
     * Whether dynamic range coefficients are present ({@code DYNF}).
     */
    public boolean dynamicRange;

    /**
     * Whether a time stamp is present ({@code TIMEF}).
     */
    public boolean timeStamp;

    /**
     * Whether auxiliary data is present ({@code AUXF}).
     */
    public boolean auxiliary;

    /**
     * Whether the source was HDCD mastered ({@code HDCD}).
     */
    public boolean hdcd;

    /**
     * The extension audio type ({@code EXT_AUDIO_ID}).
     */
    public int extensionType;

    /**
     * Whether extension audio follows the core audio ({@code EXT_AUDIO}).
     */
    public boolean extension;

    /**
     * Whether a DSYNC word ends every subsubframe rather than every subframe ({@code ASPF}).
     */
    public boolean syncEverySubsubframe;

    /**
     * The LFE flag ({@code LFF}): 0 for none, 1 for 128 times and 2 for 64 times interpolation.
     */
    public int lfe;

    /**
     * Whether the ADPCM history of the previous frame is used ({@code HFLAG}).
     */
    public boolean predictorHistory;

    /**
     * Whether the perfect reconstruction filter bank is used ({@code FILTS}).
     */
    public boolean perfectReconstruction;

    /**
     * The encoder software revision ({@code VERNUM}).
     */
    public int version;

    /**
     * The source PCM resolution code ({@code PCMR}).
     */
    public int pcmResolution;

    /**
     * Whether the front channels are sum/difference coded ({@code SUMF}).
     */
    public boolean frontSumDifference;

    /**
     * Whether the surround channels are sum/difference coded ({@code SUMS}).
     */
    public boolean surroundSumDifference;

    /**
     * The dialogue normalization gain in dB, from -31 to 0 ({@code DNG}).
     */
    public int dialogueNormalization;

    /**
     * Reads the header; the reader stands on the sync word.
     *
     * @param reader the bit stream
     * @throws MediaException.Malformed if the sync word or a field is invalid
     */
    public void read(MsbBitReader reader) {
        if (reader.read(32) != SYNC) {
            throw new MediaException.Malformed("missing DTS core sync word");
        }
        normal = reader.readFlag();
        deficit = reader.read(5);
        crcPresent = reader.readFlag();
        blocks = reader.read(7) + 1;
        if (blocks < 6) {
            throw new MediaException.Malformed("invalid DTS block count " + blocks);
        }
        frameSize = reader.read(14) + 1;
        if (frameSize < 96) {
            throw new MediaException.Malformed("invalid DTS frame size " + frameSize);
        }
        amode = reader.read(6);
        var sfreq = reader.read(4);
        sampleRate = SAMPLE_RATES[sfreq];
        if (sampleRate == 0) {
            throw new MediaException.Malformed("invalid DTS sample rate code " + sfreq);
        }
        rate = reader.read(5);
        reader.skip(1);
        dynamicRange = reader.readFlag();
        timeStamp = reader.readFlag();
        auxiliary = reader.readFlag();
        hdcd = reader.readFlag();
        extensionType = reader.read(3);
        extension = reader.readFlag();
        syncEverySubsubframe = reader.readFlag();
        lfe = reader.read(2);
        if (lfe == 3) {
            throw new MediaException.Malformed("invalid DTS LFE flag 3");
        }
        predictorHistory = reader.readFlag();
        if (crcPresent) {
            reader.skip(16);
        }
        perfectReconstruction = reader.readFlag();
        version = reader.read(4);
        reader.skip(2);
        pcmResolution = reader.read(3);
        frontSumDifference = reader.readFlag();
        surroundSumDifference = reader.readFlag();
        var dialnorm = reader.read(4);
        dialogueNormalization = switch (version) {
            case 6 -> -(16 + dialnorm);
            case 7 -> -dialnorm;
            default -> 0;
        };
    }

    /**
     * {@return the number of channels the arrangement codes, LFE excluded}
     */
    public int amodeChannels() {
        return amode < AMODE_CHANNELS.length ? AMODE_CHANNELS[amode] : 0;
    }

    /**
     * {@return the number of PCM samples per channel of the frame at the core rate}
     */
    public int samples() {
        return blocks * 32;
    }

    /**
     * {@return the target bit rate in bits per second, {@code 0} for an open rate, or {@code -1} if invalid}
     */
    public int bitRate() {
        return RATES[rate];
    }

    /**
     * {@return the source resolution in bits per sample, or {@code 0} if the code is invalid}
     */
    public int sourceBits() {
        return switch (pcmResolution) {
            case 0, 1 -> 16;
            case 2, 3 -> 20;
            case 5, 6 -> 24;
            default -> 0;
        };
    }

    /**
     * {@return whether the source was mastered as extended surround ({@code ES} of Table 5-17)}
     */
    public boolean extendedSurround() {
        return pcmResolution == 1 || pcmResolution == 3 || pcmResolution == 5;
    }
}
