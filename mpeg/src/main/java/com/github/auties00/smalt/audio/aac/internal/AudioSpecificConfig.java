package com.github.auties00.smalt.audio.aac.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.internal.header.AacRates;

/**
 * Holds the parts of an MPEG-4 {@code AudioSpecificConfig} an AAC Low Complexity decoder uses.
 *
 * <p>For HE-AAC (explicit object types 5 and 29, or the backward-compatible sync extension) the
 * fields describe the Low Complexity core: {@link #sampleRate()} is the core rate and
 * {@link #sbr()}, {@link #ps()} and {@link #extensionSampleRate()} record the signaled
 * extensions. A configuration that signals neither leaves their presence to the bitstream
 * (implicit signaling); {@link #sbrSignaled()} and {@link #psSignaled()} tell an explicit
 * absence from that.
 *
 * @param objectType the core audio object type, always 2 (Low Complexity)
 * @param sampleRate the core sampling frequency in hertz
 * @param tableIndex the index of the sampling tables serving {@code sampleRate}
 * @param channelConfiguration the channel configuration, 0 when a program configuration (or,
 * when {@code programConfig} is {@code null}, the bitstream)
 * defines the layout
 * @param programConfig the program configuration, or {@code null}
 * @param frameLength the number of samples per frame, 1024 or 960
 * @param sbr whether spectral band replication is signaled
 * @param ps whether parametric stereo is signaled
 * @param extensionSampleRate the output rate of the signaled extension, or 0
 * @param sbrSignaled whether the configuration signals the presence or absence of
 * spectral band replication
 * @param psSignaled whether the configuration signals the presence or absence of
 * parametric stereo
 */
public record AudioSpecificConfig(int objectType, int sampleRate, int tableIndex, int channelConfiguration,
                                  ProgramConfig programConfig, int frameLength, boolean sbr, boolean ps,
                                  int extensionSampleRate, boolean sbrSignaled, boolean psSignaled) {
    /**
     * The audio object type of AAC Low Complexity.
     */
    public static final int AOT_LC = 2;

    /**
     * The audio object type of spectral band replication.
     */
    public static final int AOT_SBR = 5;

    /**
     * The audio object type of parametric stereo.
     */
    public static final int AOT_PS = 29;

    /**
     * The sync extension type that announces a backward-compatible SBR extension.
     */
    private static final int SYNC_EXTENSION_SBR = 0x2B7;

    /**
     * The sync extension type that announces a backward-compatible PS extension.
     */
    private static final int SYNC_EXTENSION_PS = 0x548;

    /**
     * Parses an {@code AudioSpecificConfig}.
     *
     * @param data the configuration bytes
     * @return the configuration
     * @throws MediaException.Malformed if the configuration is truncated or invalid
     * @throws MediaException.Unsupported if it describes an object type other than AAC Low
     * Complexity (possibly with SBR or PS)
     */
    public static AudioSpecificConfig parse(byte[] data) {
        if (data.length == 0) {
            throw new MediaException.Malformed("empty AudioSpecificConfig");
        }
        var reader = new AacBitReader(data);
        var objectType = readObjectType(reader);
        var sampleRate = readSampleRate(reader);
        var channelConfiguration = reader.read(4);
        var sbr = false;
        var ps = false;
        var sbrSignaled = false;
        var psSignaled = false;
        var extensionSampleRate = 0;
        if (objectType == AOT_SBR || objectType == AOT_PS) {
            sbr = true;
            sbrSignaled = true;
            ps = objectType == AOT_PS;
            psSignaled = ps;
            extensionSampleRate = readSampleRate(reader);
            objectType = readObjectType(reader);
            if (objectType == 22) {
                reader.read(4);
            }
        }
        checkObjectType(objectType);
        var frameLength = reader.readFlag() ? 960 : 1024;
        if (reader.readFlag()) {
            reader.read(14);
        }
        var extensionFlag = reader.readFlag();
        validateChannelConfiguration(channelConfiguration);
        ProgramConfig programConfig = null;
        if (channelConfiguration == 0 && reader.bitsLeft() > 0) {
            programConfig = ProgramConfig.parse(reader);
        }
        if (extensionFlag) {
            reader.read(1);
        }
        reader.checkNotOverrun();
        if (!sbr && reader.bitsLeft() >= 16) {
            if (reader.read(11) == SYNC_EXTENSION_SBR && readObjectType(reader) == AOT_SBR) {
                sbrSignaled = true;
                if (reader.readFlag()) {
                    sbr = true;
                    extensionSampleRate = readSampleRate(reader);
                    if (reader.bitsLeft() >= 12 && reader.read(11) == SYNC_EXTENSION_PS) {
                        psSignaled = true;
                        ps = reader.readFlag();
                    }
                }
            }
            if (reader.overrun()) {
                sbr = false;
                ps = false;
                sbrSignaled = false;
                psSignaled = false;
                extensionSampleRate = 0;
            }
        }
        return new AudioSpecificConfig(objectType, sampleRate, SamplingTables.tableIndexFor(sampleRate),
                channelConfiguration, programConfig, frameLength, sbr, ps, extensionSampleRate, sbrSignaled,
                psSignaled);
    }

    /**
     * Returns the two-byte configuration of a plain AAC Low Complexity stream.
     *
     * @param sampleRate a standard sampling frequency in hertz
     * @param channelConfiguration the channel configuration, from 1 to 7
     * @return the configuration bytes
     * @throws IllegalArgumentException if the rate has no sampling frequency index
     */
    public static byte[] lowComplexity(int sampleRate, int channelConfiguration) {
        var index = AacRates.index(sampleRate);
        if (index < 0) {
            throw new IllegalArgumentException("no sampling frequency index for " + sampleRate + " Hz");
        }
        var writer = new MsbBitWriter(2);
        writer.write(AOT_LC, 5);
        writer.write(index, 4);
        writer.write(channelConfiguration, 4);
        writer.write(0, 3);
        return writer.toByteArray();
    }

    /**
     * Reads an audio object type, including the escape to six extra bits.
     *
     * @param reader the bit source
     * @return the audio object type
     */
    private static int readObjectType(AacBitReader reader) {
        var type = reader.read(5);
        return type == 31 ? 32 + reader.read(6) : type;
    }

    /**
     * Reads a sampling frequency index and, when escaped, the explicit frequency.
     *
     * @param reader the bit source
     * @return the sampling frequency in hertz
     * @throws MediaException.Malformed if the index is reserved or the frequency is zero
     */
    private static int readSampleRate(AacBitReader reader) {
        var index = reader.read(4);
        if (index == 15) {
            var rate = reader.read(24);
            if (rate == 0) {
                throw new MediaException.Malformed("AudioSpecificConfig declares a zero sampling frequency");
            }
            return rate;
        }
        if (index > 12) {
            throw new MediaException.Malformed("reserved sampling frequency index " + index);
        }
        return AacRates.rate(index);
    }

    /**
     * Rejects every core object type other than AAC Low Complexity.
     *
     * @param objectType the core audio object type
     * @throws MediaException.Unsupported if the object type is not Low Complexity
     * @throws MediaException.Malformed if the object type is zero or nests another extension
     */
    private static void checkObjectType(int objectType) {
        switch (objectType) {
            case AOT_LC -> {
            }
            case 0 -> throw new MediaException.Malformed("AudioSpecificConfig declares a null object type");
            case 1 -> throw new MediaException.Unsupported("aac main", "AAC Main profile prediction is not supported");
            case 3 -> throw new MediaException.Unsupported("aac ssr", "AAC Scalable Sample Rate is not supported");
            case 4 -> throw new MediaException.Unsupported("aac ltp", "AAC Long Term Prediction is not supported");
            case AOT_SBR, AOT_PS -> throw new MediaException.Malformed("nested SBR or PS object type");
            case 6, 20 -> throw new MediaException.Unsupported("aac scalable", "scalable AAC is not supported");
            case 17 -> throw new MediaException.Unsupported("aac er lc", "error resilient AAC LC is not supported");
            case 19 -> throw new MediaException.Unsupported("aac er ltp", "error resilient AAC LTP is not supported");
            case 22 -> throw new MediaException.Unsupported("aac er bsac", "ER BSAC is not supported");
            case 23 -> throw new MediaException.Unsupported("aac ld", "AAC Low Delay is not supported");
            case 39 -> throw new MediaException.Unsupported("aac eld", "AAC Enhanced Low Delay is not supported");
            case 42 -> throw new MediaException.Unsupported("usac", "MPEG-D USAC is not supported");
            default -> throw new MediaException.Unsupported("mpeg-4 audio object type " + objectType,
                    "MPEG-4 audio object type " + objectType + " is not AAC");
        }
    }

    /**
     * Rejects reserved and unsupported channel configurations.
     *
     * @param channelConfiguration the channel configuration
     * @throws MediaException.Malformed if the configuration is reserved
     * @throws MediaException.Unsupported if the configuration is 22.2
     */
    private static void validateChannelConfiguration(int channelConfiguration) {
        switch (channelConfiguration) {
            case 0, 1, 2, 3, 4, 5, 6, 7, 11, 12, 14 -> {
            }
            case 13 -> throw new MediaException.Unsupported("aac channel configuration 13",
                    "the 22.2 channel configuration is not supported");
            default -> throw new MediaException.Malformed("reserved channel configuration " + channelConfiguration);
        }
    }
}
