package com.github.auties00.smalt.audio.aac.internal.usac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Holds a parsed USAC configuration ({@code UsacConfig}, ISO/IEC 23003-3 subclause 5.2).
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param samplingFrequency the output sampling frequency in hertz
 * @param coreSbrFrameLengthIndex the {@code coreSbrFrameLengthIndex}, from 0 to 4
 * @param channelConfigurationIndex the {@code channelConfigurationIndex}; 0 when {@code outputPositions}
 * was coded explicitly
 * @param outputPositions the loudspeaker position of each output channel ({@code bsOutputChannelPos})
 * @param elements the elements of every frame, in bitstream order
 * @param loudnessInfo the {@code loudnessInfoSet()} configuration extension, or {@code null}
 * @param config the configuration bytes, starting at {@code usacSamplingFrequencyIndex}
 */
public record UsacConfig(int samplingFrequency, int coreSbrFrameLengthIndex, int channelConfigurationIndex,
                         int[] outputPositions, List<Element> elements, byte[] loudnessInfo, byte[] config) {
    /**
     * The element type of a single channel element ({@code ID_USAC_SCE}).
     */
    public static final int SCE = 0;

    /**
     * The element type of a channel pair element ({@code ID_USAC_CPE}).
     */
    public static final int CPE = 1;

    /**
     * The element type of a low frequency enhancement element ({@code ID_USAC_LFE}).
     */
    public static final int LFE = 2;

    /**
     * The element type of an extension element ({@code ID_USAC_EXT}).
     */
    public static final int EXT = 3;

    /**
     * The extension element type of fill data ({@code ID_EXT_ELE_FILL}).
     */
    public static final int EXT_FILL = 0;

    /**
     * The extension element type of MPEG Surround ({@code ID_EXT_ELE_MPEGS}).
     */
    public static final int EXT_MPEGS = 1;

    /**
     * The extension element type of SAOC ({@code ID_EXT_ELE_SAOC}).
     */
    public static final int EXT_SAOC = 2;

    /**
     * The extension element type of the audio pre-roll ({@code ID_EXT_ELE_AUDIOPREROLL}).
     */
    public static final int EXT_AUDIO_PRE_ROLL = 3;

    /**
     * The extension element type of MPEG-D DRC gains ({@code ID_EXT_ELE_UNI_DRC}).
     */
    public static final int EXT_UNI_DRC = 4;

    /**
     * The configuration extension type of loudness information ({@code ID_CONFIG_EXT_LOUDNESS_INFO}).
     */
    private static final int CONFIG_EXT_LOUDNESS_INFO = 2;

    /**
     * Holds the default SBR header of an element ({@code SbrDfltHeader}).
     *
     * @param startFrequency {@code dflt_start_freq}
     * @param stopFrequency {@code dflt_stop_freq}
     * @param frequencyScale {@code dflt_freq_scale}, 2 by default
     * @param alterScale {@code dflt_alter_scale}, 1 by default
     * @param noiseBands {@code dflt_noise_bands}, 2 by default
     * @param limiterBands {@code dflt_limiter_bands}, 2 by default
     * @param limiterGains {@code dflt_limiter_gains}, 2 by default
     * @param interpolation {@code dflt_interpol_freq}, 1 by default
     * @param smoothing {@code dflt_smoothing_mode}, 1 by default
     */
    public record SbrHeader(int startFrequency, int stopFrequency, int frequencyScale, int alterScale,
                            int noiseBands, int limiterBands, int limiterGains, int interpolation, int smoothing) {
    }

    /**
     * Holds the MPEG Surround 2-1-2 configuration of a channel pair element ({@code Mps212Config}).
     *
     * @param frequencyResolution {@code bsFreqRes}
     * @param fixedGainDownmix {@code bsFixedGainDMX}
     * @param temporalShaping {@code bsTempShapeConfig}
     * @param decorrelator {@code bsDecorrConfig}
     * @param highRateMode {@code bsHighRateMode}
     * @param phaseCoding {@code bsPhaseCoding}
     * @param phaseBands {@code bsOttBandsPhase}, raised to the residual bands; 0 when not coded
     * @param phaseBandsPresent {@code bsOttBandsPhasePresent}
     * @param residualBands {@code bsResidualBands}, 0 without residual coding
     * @param pseudoLr {@code bsPseudoLr}
     * @param envelopeQuantization {@code bsEnvQuantMode}
     */
    public record Mps212Config(int frequencyResolution, int fixedGainDownmix, int temporalShaping, int decorrelator,
                               boolean highRateMode, boolean phaseCoding, int phaseBands, boolean phaseBandsPresent,
                               int residualBands, boolean pseudoLr, int envelopeQuantization) {
    }

    /**
     * Holds the configuration of one element of a USAC frame.
     *
     * @param type the element type: {@link #SCE}, {@link #CPE}, {@link #LFE} or {@link #EXT}
     * @param timeWarping {@code tw_mdct}
     * @param noiseFilling {@code noiseFilling}
     * @param harmonicSbr {@code harmonicSBR}
     * @param interTes {@code bs_interTes}
     * @param pvc {@code bs_pvc}
     * @param sbrHeader the default SBR header, or {@code null} without SBR
     * @param stereoConfigIndex {@code stereoConfigIndex} of a channel pair element
     * @param mps the MPEG Surround configuration, or {@code null}
     * @param extensionType {@code usacExtElementType} of an extension element
     * @param extensionConfig the {@code usacExtElementConfig} payload of an extension element
     * @param extensionDefaultLength {@code usacExtElementDefaultLength}, 0 when absent
     * @param extensionFragmented {@code usacExtElementPayloadFrag}
     */
    public record Element(int type, boolean timeWarping, boolean noiseFilling, boolean harmonicSbr, boolean interTes,
                          boolean pvc, SbrHeader sbrHeader, int stereoConfigIndex, Mps212Config mps,
                          int extensionType, byte[] extensionConfig, int extensionDefaultLength,
                          boolean extensionFragmented) {
        /**
         * {@return the number of core channels the element codes}
         */
        public int coreChannels() {
            return switch (type) {
                case SCE, LFE -> 1;
                case CPE -> stereoConfigIndex == 1 ? 1 : 2;
                default -> 0;
            };
        }

        /**
         * {@return the number of output channels the element produces}
         */
        public int outputChannels() {
            return switch (type) {
                case SCE, LFE -> 1;
                case CPE -> 2;
                default -> 0;
            };
        }
    }

    /**
     * Parses the {@code UsacConfig} of an {@code AudioSpecificConfig} of object type 42.
     *
     * @param data the {@code AudioSpecificConfig} bytes
     * @return the configuration
     * @throws MediaException.Malformed if the configuration is truncated or invalid
     */
    public static UsacConfig parseAudioSpecificConfig(byte[] data) {
        var reader = new AacBitReader(data);
        var type = reader.read(5);
        if (type == 31) {
            type = 32 + reader.read(6);
        }
        if (type != 42) {
            throw new MediaException.Malformed("AudioSpecificConfig of object type " + type + " is not USAC");
        }
        if (reader.read(4) == 15) {
            reader.read(24);
        }
        reader.read(4);
        var start = (int) reader.bitPosition();
        var config = parse(reader, data, start);
        reader.checkNotOverrun();
        return config;
    }

    /**
     * Parses a {@code UsacConfig} at the reader's position, as the audio pre-roll embeds one.
     *
     * @param reader the bit source
     * @return the configuration
     * @throws MediaException.Malformed if the configuration is invalid
     */
    static UsacConfig parse(AacBitReader reader) {
        return parse(reader, null, 0);
    }

    private static UsacConfig parse(AacBitReader reader, byte[] data, int start) {
        var index = reader.read(5);
        int frequency;
        if (index == 0x1F) {
            frequency = reader.read(24);
        } else {
            frequency = UsacTables.SAMPLING_FREQUENCIES[index];
        }
        if (frequency <= 0) {
            throw new MediaException.Malformed("USAC configuration declares sampling frequency index " + index);
        }
        var coreSbr = reader.read(3);
        if (coreSbr > 4) {
            throw new MediaException.Malformed("reserved coreSbrFrameLengthIndex " + coreSbr);
        }
        var channelConfiguration = reader.read(5);
        int[] positions;
        if (channelConfiguration == 0) {
            var count = escaped(reader, 5, 8, 16);
            if (count > 255) {
                throw new MediaException.Malformed("USAC configuration declares " + count + " output channels");
            }
            positions = new int[count];
            for (var i = 0; i < count; i++) {
                positions[i] = reader.read(5);
            }
        } else {
            positions = positionsOf(channelConfiguration);
        }
        var sbr = coreSbr >= 2;
        var count = escaped(reader, 4, 8, 16) + 1;
        if (count > 64) {
            throw new MediaException.Malformed("USAC configuration declares " + count + " elements");
        }
        var elements = new ArrayList<Element>(count);
        for (var i = 0; i < count; i++) {
            reader.checkNotOverrun();
            var type = reader.read(2);
            elements.add(switch (type) {
                case SCE -> {
                    var tw = reader.readFlag();
                    var nf = reader.readFlag();
                    if (!sbr) {
                        yield new Element(SCE, tw, nf, false, false, false, null, 0, null, 0, null, 0, false);
                    }
                    var harmonic = reader.readFlag();
                    var interTes = reader.readFlag();
                    var pvc = reader.readFlag();
                    yield new Element(SCE, tw, nf, harmonic, interTes, pvc, readSbrHeader(reader), 0, null, 0, null,
                            0, false);
                }
                case CPE -> {
                    var tw = reader.readFlag();
                    var nf = reader.readFlag();
                    var harmonic = false;
                    var interTes = false;
                    var pvc = false;
                    SbrHeader header = null;
                    var stereo = 0;
                    if (sbr) {
                        harmonic = reader.readFlag();
                        interTes = reader.readFlag();
                        pvc = reader.readFlag();
                        header = readSbrHeader(reader);
                        stereo = reader.read(2);
                    }
                    Mps212Config mps = null;
                    if (stereo > 0) {
                        mps = readMps(reader, stereo);
                    }
                    yield new Element(CPE, tw, nf, harmonic, interTes, pvc, header, stereo, mps, 0, null, 0, false);
                }
                case LFE -> new Element(LFE, false, false, false, false, false, null, 0, null, 0, null, 0, false);
                default -> {
                    var extType = escaped(reader, 4, 8, 16);
                    var length = escaped(reader, 4, 8, 16);
                    var defaultLength = 0;
                    if (reader.readFlag()) {
                        defaultLength = escaped(reader, 8, 16, 0) + 1;
                    }
                    var fragmented = reader.readFlag();
                    if ((long) length * 8 > reader.bitsLeft()) {
                        throw new MediaException.Malformed("USAC extension element configuration is truncated");
                    }
                    var payload = new byte[length];
                    for (var b = 0; b < length; b++) {
                        payload[b] = (byte) reader.read(8);
                    }
                    yield new Element(EXT, false, false, false, false, false, null, 0, null, extType, payload,
                            defaultLength, fragmented);
                }
            });
        }
        byte[] loudness = null;
        if (reader.readFlag()) {
            var extensions = escaped(reader, 2, 4, 8) + 1;
            for (var i = 0; i < extensions; i++) {
                var extType = escaped(reader, 4, 8, 16);
                var length = escaped(reader, 4, 8, 16);
                if ((long) length * 8 > reader.bitsLeft()) {
                    throw new MediaException.Malformed("USAC configuration extension is truncated");
                }
                var payload = new byte[length];
                for (var b = 0; b < length; b++) {
                    payload[b] = (byte) reader.read(8);
                }
                if (extType == CONFIG_EXT_LOUDNESS_INFO) {
                    loudness = payload;
                }
            }
        }
        reader.checkNotOverrun();
        var bytes = data == null ? new byte[0] : copyBits(data, start, (int) reader.bitPosition() - start);
        return new UsacConfig(frequency, coreSbr, channelConfiguration, positions, List.copyOf(elements), loudness,
                bytes);
    }

    private static byte[] copyBits(byte[] data, int start, int bits) {
        var out = new byte[(bits + 7) / 8];
        var reader = new AacBitReader(data);
        reader.seek(start);
        for (var i = 0; i < out.length; i++) {
            var take = Math.min(8, bits - 8 * i);
            out[i] = (byte) (reader.read(take) << (8 - take));
        }
        return out;
    }

    private static SbrHeader readSbrHeader(AacBitReader reader) {
        var start = reader.read(4);
        var stop = reader.read(4);
        var extra1 = reader.readFlag();
        var extra2 = reader.readFlag();
        var scale = 2;
        var alter = 1;
        var noise = 2;
        if (extra1) {
            scale = reader.read(2);
            alter = reader.read(1);
            noise = reader.read(2);
        }
        var limiterBands = 2;
        var limiterGains = 2;
        var interpolation = 1;
        var smoothing = 1;
        if (extra2) {
            limiterBands = reader.read(2);
            limiterGains = reader.read(2);
            interpolation = reader.read(1);
            smoothing = reader.read(1);
        }
        return new SbrHeader(start, stop, scale, alter, noise, limiterBands, limiterGains, interpolation, smoothing);
    }

    private static Mps212Config readMps(AacBitReader reader, int stereoConfigIndex) {
        var freqRes = reader.read(3);
        var fixedGain = reader.read(3);
        var tempShape = reader.read(2);
        var decorr = reader.read(2);
        var highRate = reader.readFlag();
        var phaseCoding = reader.readFlag();
        var phasePresent = reader.readFlag();
        var phaseBands = phasePresent ? reader.read(5) : 0;
        var residualBands = 0;
        var pseudoLr = false;
        if (stereoConfigIndex > 1) {
            residualBands = reader.read(5);
            phaseBands = Math.max(phaseBands, residualBands);
            pseudoLr = reader.readFlag();
        }
        var envQuant = tempShape == 2 ? reader.read(1) : 0;
        return new Mps212Config(freqRes, fixedGain, tempShape, decorr, highRate, phaseCoding, phaseBands,
                phasePresent, residualBands, pseudoLr, envQuant);
    }

    /**
     * Reads an {@code escapedValue(nBits1, nBits2, nBits3)}.
     *
     * @param reader the bit source
     * @param bits1 the width of the first field
     * @param bits2 the width of the first escape
     * @param bits3 the width of the second escape
     * @return the value
     */
    static int escaped(AacBitReader reader, int bits1, int bits2, int bits3) {
        var value = reader.read(bits1);
        if (value == (1 << bits1) - 1) {
            var add = reader.read(bits2);
            value += add;
            if (bits3 > 0 && add == (1 << bits2) - 1) {
                value += reader.read(bits3);
            }
        }
        return value;
    }

    /**
     * Returns the output positions of a {@code channelConfigurationIndex} (ISO/IEC 23003-3 Table 73).
     *
     * @param index the index, from 1 to 13
     * @return the {@code bsOutputChannelPos} of each channel
     * @throws MediaException.Malformed if the index is reserved
     */
    static int[] positionsOf(int index) {
        return switch (index) {
            case 1 -> new int[]{2};
            case 2 -> new int[]{0, 1};
            case 3 -> new int[]{2, 0, 1};
            case 4 -> new int[]{2, 0, 1, 10};
            case 5 -> new int[]{2, 0, 1, 4, 5};
            case 6 -> new int[]{2, 0, 1, 4, 5, 3};
            case 7 -> new int[]{2, 6, 7, 0, 1, 4, 5, 3};
            case 8 -> new int[]{-1, -1};
            case 9 -> new int[]{0, 1, 10};
            case 10 -> new int[]{0, 1, 4, 5};
            case 11 -> new int[]{2, 0, 1, 4, 5, 10, 3};
            case 12 -> new int[]{2, 0, 1, 4, 5, 8, 9, 3};
            case 13 -> new int[]{2, 6, 7, 0, 1, 13, 14, 8, 9, 10, 3, 26, 19, 17, 18, 23, 24, 25, 20, 21, 22, 29,
                    27, 28};
            default -> throw new MediaException.Malformed("reserved USAC channelConfigurationIndex " + index);
        };
    }

    /**
     * {@return the number of samples per channel the core coder produces per frame: 768 or 1024}
     */
    public int coreFrameLength() {
        return coreSbrFrameLengthIndex == 0 || coreSbrFrameLengthIndex == 2 ? 768 : 1024;
    }

    /**
     * {@return the number of output samples per channel and frame: 768, 1024, 2048 or 4096}
     */
    public int outputFrameLength() {
        return switch (coreSbrFrameLengthIndex) {
            case 0 -> 768;
            case 1 -> 1024;
            case 2, 3 -> 2048;
            default -> 4096;
        };
    }

    /**
     * {@return the SBR ratio index: 0 without SBR, 1 for 4:1, 2 for 8:3 and 3 for 2:1}
     */
    public int sbrRatioIndex() {
        return switch (coreSbrFrameLengthIndex) {
            case 2 -> 2;
            case 3 -> 3;
            case 4 -> 1;
            default -> 0;
        };
    }

    /**
     * {@return the sampling frequency of the core coder in hertz}
     */
    public int coreSamplingFrequency() {
        return switch (coreSbrFrameLengthIndex) {
            case 2 -> samplingFrequency * 3 / 8;
            case 3 -> samplingFrequency / 2;
            case 4 -> samplingFrequency / 4;
            default -> samplingFrequency;
        };
    }

    /**
     * {@return the number of output channels}
     */
    public int outputChannels() {
        return outputPositions.length;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof UsacConfig that && Arrays.equals(config, that.config) && config.length > 0;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(config);
    }
}
