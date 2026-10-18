package com.github.auties00.smalt.audio.aac.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.internal.header.AacRates;

/**
 * Holds the parts of an MPEG-4 {@code AudioSpecificConfig} an AAC decoder uses.
 *
 * <p>For HE-AAC (explicit object types 5 and 29, or the backward-compatible sync extension) the
 * fields describe the Low Complexity core: {@link #sampleRate()} is the core rate and
 * {@link #sbr()}, {@link #ps()} and {@link #extensionSampleRate()} record the signaled
 * extensions. A configuration that signals neither leaves their presence to the bitstream
 * (implicit signaling); {@link #sbrSignaled()} and {@link #psSignaled()} tell an explicit
 * absence from that. For AAC-ELD the low delay spectral band replication of the
 * {@code ELDSpecificConfig} is recorded in {@link #lowDelaySbr()} and reflected in {@link #sbr()}
 * and {@link #extensionSampleRate()}.
 *
 * @param objectType the core audio object type: {@link #AOT_MAIN}, {@link #AOT_LC}, {@link #AOT_SSR},
 * {@link #AOT_LTP}, {@link #AOT_ER_LC}, {@link #AOT_ER_LTP}, {@link #AOT_ER_LD} or {@link #AOT_ER_ELD}
 * @param sampleRate the core sampling frequency in hertz
 * @param tableIndex the index of the sampling tables serving {@code sampleRate}
 * @param channelConfiguration the channel configuration, 0 when a program configuration (or,
 * when {@code programConfig} is {@code null}, the bitstream)
 * defines the layout
 * @param programConfig the program configuration, or {@code null}
 * @param frameLength the number of samples per frame: 1024 or 960, or 512 or 480 for the low delay
 * object types
 * @param sbr whether spectral band replication is signaled
 * @param ps whether parametric stereo is signaled
 * @param extensionSampleRate the output rate of the signaled extension, or 0
 * @param sbrSignaled whether the configuration signals the presence or absence of
 * spectral band replication
 * @param psSignaled whether the configuration signals the presence or absence of
 * parametric stereo
 * @param resilience the error resilience tools of the error resilient object types, a combination of
 * {@link #VIRTUAL_CODEBOOKS}, {@link #RVLC_SCALEFACTORS} and {@link #CODEWORD_REORDERING}
 * @param epConfig the error protection configuration of the error resilient object types, 0 or 1
 * @param lowDelaySbr the low delay spectral band replication of AAC-ELD, or {@code null}
 */
public record AudioSpecificConfig(int objectType, int sampleRate, int tableIndex, int channelConfiguration,
                                  ProgramConfig programConfig, int frameLength, boolean sbr, boolean ps,
                                  int extensionSampleRate, boolean sbrSignaled, boolean psSignaled, int resilience,
                                  int epConfig, LowDelaySbr lowDelaySbr) {
    /**
     * The audio object type of AAC Main, which adds backward-adaptive frequency-domain prediction.
     */
    public static final int AOT_MAIN = 1;

    /**
     * The audio object type of AAC Low Complexity.
     */
    public static final int AOT_LC = 2;

    /**
     * The audio object type of AAC Scalable Sample Rate, which codes four PQF bands with gain control.
     */
    public static final int AOT_SSR = 3;

    /**
     * The audio object type of AAC Long Term Prediction.
     */
    public static final int AOT_LTP = 4;

    /**
     * The audio object type of error resilient AAC Low Complexity.
     */
    public static final int AOT_ER_LC = 17;

    /**
     * The audio object type of error resilient AAC Long Term Prediction.
     */
    public static final int AOT_ER_LTP = 19;

    /**
     * The audio object type of error resilient AAC Low Delay (AAC-LD).
     */
    public static final int AOT_ER_LD = 23;

    /**
     * The audio object type of error resilient AAC Enhanced Low Delay (AAC-ELD).
     */
    public static final int AOT_ER_ELD = 39;

    /**
     * The audio object type of spectral band replication.
     */
    public static final int AOT_SBR = 5;

    /**
     * The audio object type of parametric stereo.
     */
    public static final int AOT_PS = 29;

    /**
     * The {@link #resilience()} flag of virtual codebooks for codebook 11
     * ({@code aacSectionDataResilienceFlag}).
     */
    public static final int VIRTUAL_CODEBOOKS = 4;

    /**
     * The {@link #resilience()} flag of reversible variable length scalefactors
     * ({@code aacScalefactorDataResilienceFlag}).
     */
    public static final int RVLC_SCALEFACTORS = 2;

    /**
     * The {@link #resilience()} flag of Huffman codeword reordering
     * ({@code aacSpectralDataResilienceFlag}).
     */
    public static final int CODEWORD_REORDERING = 1;

    /**
     * The sync extension type that announces a backward-compatible SBR extension.
     */
    private static final int SYNC_EXTENSION_SBR = 0x2B7;

    /**
     * The sync extension type that announces a backward-compatible PS extension.
     */
    private static final int SYNC_EXTENSION_PS = 0x548;

    /**
     * The extension type that ends the extension list of an {@code ELDSpecificConfig}
     * ({@code ELDEXT_TERM}).
     */
    private static final int ELD_EXTENSION_TERMINATOR = 0;

    /**
     * Holds the low delay spectral band replication of an AAC-ELD configuration.
     *
     * <p>The configuration carries one {@code sbr_header} per channel element that is not an LFE
     * element; {@link #headerOffsets()} locates each in {@link #config()}.
     *
     * @param dualRate whether the extension doubles the core rate ({@code ldSbrSamplingRate}); otherwise
     * it runs in the downsampled mode at the core rate
     * @param crc whether every extension payload starts with a CRC ({@code ldSbrCrcFlag})
     * @param config the configuration bytes; shared and not to be modified
     * @param headerOffsets the bit offset of each {@code sbr_header} in {@code config}
     */
    public record LowDelaySbr(boolean dualRate, boolean crc, byte[] config, int[] headerOffsets) {
    }

    /**
     * Parses an {@code AudioSpecificConfig}.
     *
     * @param data the configuration bytes
     * @return the configuration
     * @throws MediaException.Malformed if the configuration is truncated or invalid
     * @throws MediaException.Unsupported if it describes an object type other than AAC Main, Low
     * Complexity, SSR, LTP, ER LC, ER LTP, LD or ELD (possibly with SBR or PS), or error protection
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
        validateChannelConfiguration(channelConfiguration);
        int frameLength;
        var resilience = 0;
        ProgramConfig programConfig = null;
        LowDelaySbr lowDelaySbr = null;
        if (objectType == AOT_ER_ELD) {
            frameLength = reader.readFlag() ? 480 : 512;
            resilience = reader.read(3);
            if (reader.readFlag()) {
                var dualRate = reader.readFlag();
                var crc = reader.readFlag();
                lowDelaySbr = new LowDelaySbr(dualRate, crc, data, skipSbrHeaders(reader, channelConfiguration));
                sbr = true;
                sbrSignaled = true;
                extensionSampleRate = dualRate ? 2 * sampleRate : sampleRate;
            } else {
                sbrSignaled = true;
            }
            skipEldExtensions(reader);
        } else {
            frameLength = reader.readFlag() ? 960 : 1024;
            if (objectType == AOT_ER_LD) {
                frameLength /= 2;
            }
            if (objectType == AOT_SSR && frameLength != 1024) {
                throw new MediaException.Malformed("AAC SSR configuration sets the frame length flag");
            }
            if (reader.readFlag()) {
                reader.read(14);
            }
            var extensionFlag = reader.readFlag();
            if (channelConfiguration == 0 && reader.bitsLeft() > 0) {
                programConfig = ProgramConfig.parse(reader);
            }
            if (extensionFlag) {
                if (isErrorResilient(objectType)) {
                    resilience = reader.read(3);
                }
                reader.read(1);
            }
        }
        var epConfig = 0;
        if (isErrorResilient(objectType)) {
            epConfig = reader.read(2);
            checkErrorResilience(objectType, epConfig, channelConfiguration);
        }
        reader.checkNotOverrun();
        if (!sbr && !isErrorResilient(objectType) && reader.bitsLeft() >= 16) {
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
                psSignaled, resilience, epConfig, lowDelaySbr);
    }

    /**
     * {@return whether the payload uses the error resilient syntax ({@code er_raw_data_block})}
     */
    public boolean errorResilient() {
        return isErrorResilient(objectType);
    }

    /**
     * {@return whether the payload carries long term prediction data}
     */
    public boolean longTermPrediction() {
        return objectType == AOT_LTP || objectType == AOT_ER_LTP || objectType == AOT_ER_LD;
    }

    /**
     * {@return whether the object type is AAC-LD or AAC-ELD, whose frames hold 512 or 480 samples}
     */
    public boolean lowDelay() {
        return objectType == AOT_ER_LD || objectType == AOT_ER_ELD;
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
     * Returns whether an object type uses the error resilient syntax.
     *
     * @param objectType the core audio object type
     * @return {@code true} for ER AAC LC, ER AAC LTP, AAC-LD and AAC-ELD
     */
    private static boolean isErrorResilient(int objectType) {
        return objectType == AOT_ER_LC || objectType == AOT_ER_LTP || objectType == AOT_ER_LD
                || objectType == AOT_ER_ELD;
    }

    /**
     * Skips the {@code ld_sbr_header} of an {@code ELDSpecificConfig}: one {@code sbr_header} per
     * channel element other than an LFE element.
     *
     * @param reader the bit source, at the first header
     * @param channelConfiguration the channel configuration
     * @return the bit offset of each header
     */
    private static int[] skipSbrHeaders(AacBitReader reader, int channelConfiguration) {
        var count = switch (channelConfiguration) {
            case 1, 2 -> 1;
            case 3 -> 2;
            case 4, 5, 6 -> 3;
            case 7 -> 4;
            default -> 0;
        };
        var offsets = new int[count];
        for (var i = 0; i < count; i++) {
            offsets[i] = (int) reader.bitPosition();
            reader.read(14);
            var extra1 = reader.readFlag();
            var extra2 = reader.readFlag();
            if (extra1) {
                reader.read(5);
            }
            if (extra2) {
                reader.read(6);
            }
        }
        return offsets;
    }

    /**
     * Skips the extension list of an {@code ELDSpecificConfig} up to its terminator.
     *
     * @param reader the bit source
     * @throws MediaException.Malformed if the list is truncated
     */
    private static void skipEldExtensions(AacBitReader reader) {
        while (reader.read(4) != ELD_EXTENSION_TERMINATOR) {
            var length = reader.read(4);
            if (length == 15) {
                var add = reader.read(8);
                length += add;
                if (add == 255) {
                    length += reader.read(16);
                }
            }
            reader.skip(8L * length);
            reader.checkNotOverrun();
        }
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
     * Rejects the error resilient configurations whose payloads this decoder cannot read.
     *
     * @param objectType the core audio object type
     * @param epConfig the error protection configuration
     * @param channelConfiguration the channel configuration
     * @throws MediaException.Unsupported if the error protection tool is signaled, or AAC-ELD stores
     * its category instances separately
     * @throws MediaException.Malformed if the channel configuration is 0 or above 7, which error
     * resilient payloads reserve
     */
    private static void checkErrorResilience(int objectType, int epConfig, int channelConfiguration) {
        // TODO: epConfig 2 and 3 apply the error protection tool of ISO/IEC 14496-3 subpart 1
        //  (ErrorProtectionSpecificConfig, ep_frame with CRC, SRCPC and Reed-Solomon classes); neither its
        //  specification text nor a permissively licensed implementation is available.
        if (epConfig >= 2) {
            throw new MediaException.Unsupported("aac er epconfig " + epConfig,
                    "error resilient AAC with the error protection tool (epConfig " + epConfig + ") is not supported");
        }
        // TODO: AAC-ELD with epConfig 1 needs the error sensitivity category of its TNS data (which ELD moves
        //  ahead of the spectral data) and of its low delay SBR payload; ISO/IEC 14496-26 has no ELD stream with
        //  epConfig 1, and libxaac, the permissive implementation, parses epConfig for ELD but ignores it.
        if (epConfig == 1 && objectType == AOT_ER_ELD) {
            throw new MediaException.Unsupported("aac eld epconfig 1",
                    "AAC-ELD with separately stored error sensitivity categories (epConfig 1) is not supported");
        }
        if (channelConfiguration == 0 || channelConfiguration > 7) {
            throw new MediaException.Malformed("error resilient AAC reserves channel configuration "
                    + channelConfiguration);
        }
    }

    /**
     * Rejects every core object type other than AAC Main, Low Complexity, SSR, LTP, ER LC, ER LTP,
     * LD and ELD.
     *
     * @param objectType the core audio object type
     * @throws MediaException.Unsupported if the object type is another one
     * @throws MediaException.Malformed if the object type is zero or nests another extension
     */
    private static void checkObjectType(int objectType) {
        switch (objectType) {
            case AOT_MAIN, AOT_LC, AOT_SSR, AOT_LTP, AOT_ER_LC, AOT_ER_LTP, AOT_ER_LD, AOT_ER_ELD -> {
            }
            case 0 -> throw new MediaException.Malformed("AudioSpecificConfig declares a null object type");
            case AOT_SBR, AOT_PS -> throw new MediaException.Malformed("nested SBR or PS object type");
            case 6, 20 -> throw new MediaException.Unsupported("aac scalable", "scalable AAC is not supported");
            case 22 -> throw new MediaException.Unsupported("aac er bsac", "ER BSAC is not supported");
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
