package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.internal.header.AacRates;
import com.github.auties00.smalt.internal.header.FlacStreamInfo;
import com.github.auties00.smalt.internal.header.MpegAudioHeader;
import com.github.auties00.smalt.internal.header.OpusHeader;
import com.github.auties00.smalt.internal.io.ByteArrays;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Reads and converts the audio codec configurations and frame headers the containers and the probe
 * API need: the MPEG-4 {@code AudioSpecificConfig}, MPEG audio frame headers, the Opus
 * identification header and its ISO base media counterpart, and ALAC and FLAC configurations.
 *
 * <p>Every parser validates its input and raises {@link MediaException.Malformed} for a
 * configuration that is too short or inconsistent.
 */
public final class AudioConfigs {
    /**
     * The channel counts indexed by {@code channelConfiguration}, from ISO/IEC 14496-3 Table 1.19
     * as amended; {@code 0} means the program config element defines the channels.
     */
    private static final int[] AAC_CHANNELS = {0, 1, 2, 3, 4, 5, 6, 8, 0, 0, 0, 7, 8, 24, 8, 0};

    /**
     * The magic that starts an Opus identification header.
     */
    private static final byte[] OPUS_HEAD = "OpusHead".getBytes(StandardCharsets.US_ASCII);

    /**
     * The {@code syncExtensionType} of the backward-compatible SBR signaling (ISO/IEC 14496-3
     * clause 1.6.5.2).
     */
    private static final int SYNC_EXTENSION_SBR = 0x2B7;

    /**
     * The {@code syncExtensionType} of the backward-compatible PS signaling.
     */
    private static final int SYNC_EXTENSION_PS = 0x548;

    /**
     * The coded streams of the Vorbis channel mapping of mapping family 1 by channel count, for counts 1 to 8
     * (RFC 7845 clause 5.1.1.2).
     */
    private static final int[] OPUS_STREAMS = {1, 1, 2, 2, 3, 4, 4, 5};

    /**
     * The coupled streams of the Vorbis channel mapping of mapping family 1 by channel count, for counts 1 to 8.
     */
    private static final int[] OPUS_COUPLED = {0, 1, 1, 2, 2, 2, 3, 3};

    /**
     * The Vorbis channel mappings of mapping family 1 by channel count, for counts 1 to 8.
     */
    private static final int[][] OPUS_MAPPINGS = {{0}, {0, 1}, {0, 2, 1}, {0, 1, 2, 3}, {0, 4, 1, 2, 3},
            {0, 4, 1, 2, 3, 5}, {0, 4, 1, 2, 3, 5, 6}, {0, 6, 1, 2, 3, 4, 5, 7}};

    /**
     * The magic that starts a native FLAC stream and the codec configuration of a FLAC track.
     */
    public static final byte[] FLAC_MARKER = "fLaC".getBytes(StandardCharsets.US_ASCII);

    /**
     * The length of an {@code ALACSpecificConfig}.
     */
    public static final int ALAC_CONFIG_LENGTH = 24;

    /**
     * Prevents instantiation.
     */
    private AudioConfigs() {
        throw new AssertionError();
    }

    /**
     * Describes the fields of an {@code AudioSpecificConfig} a container needs.
     *
     * @param objectType the audio object type of the core coder, for example {@code 2} for AAC-LC
     * @param sampleRate the core sampling rate in hertz
     * @param channels the channel count, or {@code 0} when a program config element defines it
     * @param frameLength the number of samples per frame at the core rate, {@code 1024} or {@code 960}, or {@code 512}
     * or {@code 480} for AAC-LD and AAC-ELD
     * @param outputRate the output sampling rate in hertz, which is twice the core rate when spectral band replication
     * is signaled at dual rate
     * @param sbr whether spectral band replication is signaled explicitly, the low delay one of AAC-ELD included
     * @param channelConfiguration the {@code channelConfiguration} field, {@code 0} when a program config element
     * defines the channels
     */
    public record AacConfig(int objectType, int sampleRate, int channels, int frameLength, int outputRate, boolean sbr,
                            int channelConfiguration) {
        /**
         * Returns the speakers the AAC decoder outputs for this configuration: the layout of the
         * channel configuration ({@link AudioConfigs#aacLayout(int)}), or {@code null} when a
         * program config element defines the channels.
         *
         * @return the layout, or {@code null}
         */
        public ChannelLayout layout() {
            return aacLayout(channelConfiguration);
        }
    }

    /**
     * Parses an {@code AudioSpecificConfig}, per ISO/IEC 14496-3 clause 1.6.2.1.
     *
     * @param config the configuration bytes
     * @return the parsed fields
     * @throws MediaException.Malformed if the configuration is truncated or uses a reserved sampling frequency index
     */
    public static AacConfig parseAac(byte[] config) {
        if (config.length < 2) {
            throw new MediaException.Malformed("AudioSpecificConfig of " + config.length + " bytes is too short");
        }
        var reader = new MsbBitReader(config);
        var objectType = readObjectType(reader);
        var sampleRate = readSampleRate(reader);
        var channelConfiguration = reader.read(4);
        var sbr = false;
        var outputRate = sampleRate;
        if (objectType == 5 || objectType == 29) {
            sbr = true;
            outputRate = readSampleRate(reader);
            objectType = readObjectType(reader);
        }
        var frameLength = 1024;
        if (objectType == 23 || objectType == 39) {
            frameLength = reader.readFlag() ? 480 : 512;
            if (objectType == 39) {
                reader.read(3);
                if (reader.readFlag()) {
                    sbr = true;
                    outputRate = reader.readFlag() ? 2 * sampleRate : sampleRate;
                }
            }
        } else if (objectType == 1 || objectType == 2 || objectType == 3 || objectType == 4 || objectType == 6
                || objectType == 7 || objectType == 17 || objectType == 19 || objectType == 20
                || objectType == 21 || objectType == 22) {
            frameLength = reader.readFlag() ? 960 : 1024;
        }
        reader.checkNotOverrun();
        if (!sbr && config.length >= 5) {
            var extension = findSbrExtension(config);
            if (extension > 0) {
                sbr = true;
                outputRate = extension;
            }
        }
        return new AacConfig(objectType, sampleRate, AAC_CHANNELS[channelConfiguration], frameLength, outputRate, sbr,
                channelConfiguration);
    }

    /**
     * Reads a five-bit audio object type with its six-bit escape.
     *
     * @param reader the bit reader
     * @return the audio object type
     */
    private static int readObjectType(MsbBitReader reader) {
        var type = reader.read(5);
        return type == 31 ? 32 + reader.read(6) : type;
    }

    /**
     * Reads a sampling frequency index with its 24-bit escape.
     *
     * @param reader the bit reader
     * @return the sampling rate in hertz
     * @throws MediaException.Malformed if the index is reserved or the rate is zero
     */
    private static int readSampleRate(MsbBitReader reader) {
        var index = reader.read(4);
        var rate = index == AacRates.ESCAPE ? reader.read(24) : AacRates.rate(index);
        if (rate <= 0) {
            throw new MediaException.Malformed("reserved AAC sampling frequency index " + index);
        }
        return rate;
    }

    /**
     * Searches the tail of an {@code AudioSpecificConfig} for the backward-compatible explicit
     * SBR signaling of ISO/IEC 14496-3 clause 1.6.5.2 ({@code syncExtensionType} 0x2B7).
     *
     * @param config the configuration bytes
     * @return the extension sampling rate when SBR is present, otherwise {@code 0}
     */
    private static int findSbrExtension(byte[] config) {
        var reader = new MsbBitReader(config);
        var total = (long) config.length * 8;
        readObjectType(reader);
        var index = reader.read(4);
        if (index == 15) {
            reader.read(24);
        }
        reader.read(4);
        reader.read(1);
        reader.read(1);
        reader.read(1);
        while (total - reader.bitPosition() >= 16) {
            if (reader.peek(11) != 0x2B7) {
                reader.skip(1);
                continue;
            }
            reader.skip(11);
            var extensionType = readObjectType(reader);
            if (extensionType != 5 || total - reader.bitPosition() < 1) {
                return 0;
            }
            if (!reader.readFlag()) {
                return 0;
            }
            var extensionIndex = reader.read(4);
            var rate = extensionIndex == AacRates.ESCAPE ? reader.read(24) : AacRates.rate(extensionIndex);
            return reader.overrun() ? 0 : rate;
        }
        return 0;
    }

    /**
     * Returns the speakers of an AAC channel configuration in WAVE order, the order the AAC decoder outputs.
     *
     * @param configuration the channel configuration of an {@code AudioSpecificConfig} or ADTS header
     * @return the layout, or {@code null} for configuration 0, whose program config element defines the channels,
     * and for the reserved configurations
     */
    public static ChannelLayout aacLayout(int configuration) {
        return switch (configuration) {
            case 1 -> ChannelLayout.MONO;
            case 2 -> ChannelLayout.STEREO;
            case 3 -> ChannelLayout.defaultFor(3);
            case 4 -> layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.BACK_CENTER);
            case 5 -> ChannelLayout.defaultFor(5);
            case 6 -> ChannelLayout.SURROUND_5_1;
            case 7 -> layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY,
                    Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.FRONT_LEFT_OF_CENTER, Speaker.FRONT_RIGHT_OF_CENTER);
            case 11 -> layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY,
                    Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.BACK_CENTER);
            case 12 -> layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY,
                    Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT);
            case 13 -> layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY,
                    Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.FRONT_LEFT_OF_CENTER, Speaker.FRONT_RIGHT_OF_CENTER,
                    Speaker.BACK_CENTER, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT, Speaker.TOP_CENTER,
                    Speaker.TOP_FRONT_LEFT, Speaker.TOP_FRONT_CENTER, Speaker.TOP_FRONT_RIGHT, Speaker.TOP_BACK_LEFT,
                    Speaker.TOP_BACK_CENTER, Speaker.TOP_BACK_RIGHT, Speaker.LOW_FREQUENCY_2, Speaker.TOP_SIDE_LEFT,
                    Speaker.TOP_SIDE_RIGHT, Speaker.BOTTOM_FRONT_CENTER, Speaker.BOTTOM_FRONT_LEFT,
                    Speaker.BOTTOM_FRONT_RIGHT);
            case 14 -> layout(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY,
                    Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.TOP_FRONT_LEFT, Speaker.TOP_FRONT_RIGHT);
            default -> null;
        };
    }

    /**
     * Returns a layout of speakers.
     *
     * @param speakers the speakers, in channel order
     * @return the layout
     */
    private static ChannelLayout layout(Speaker... speakers) {
        return new ChannelLayout(List.of(speakers));
    }

    /**
     * Returns the rate an AAC sampling frequency index names (ISO/IEC 14496-3 Table 1.18).
     *
     * @param index the sampling frequency index
     * @return the rate in hertz, or 0 when {@code index} is reserved, the escape 15 or outside 0 to 15
     */
    public static int aacSampleRate(int index) {
        return AacRates.rate(index);
    }

    /**
     * Returns the AAC sampling frequency index that names a rate.
     *
     * @param rate the rate in hertz
     * @return the index, from 0 to 12, or {@code -1} when no index names {@code rate} exactly and a configuration must
     * use the escape
     */
    public static int aacSampleRateIndex(int rate) {
        return AacRates.index(rate);
    }

    /**
     * Builds an {@code AudioSpecificConfig} (ISO/IEC 14496-3 clause 1.6.2.1) for a general audio
     * object type.
     *
     * <p>The configuration holds the object type (with its escape above 30), the sampling
     * frequency index (or the escape and the explicit rate), the channel configuration and a
     * {@code GASpecificConfig} with every flag clear. A positive {@code outputRate} appends the
     * backward-compatible explicit SBR signaling of clause 1.6.5.2 with that extension rate,
     * followed by the PS signaling when {@code parametricStereo} is set. The last byte is padded
     * with zero bits.
     *
     * @param objectType the audio object type of the core coder, for example 2 for AAC-LC, from 1 to 95
     * @param sampleRate the core sampling rate in hertz, from 1 to {@code 2^24 - 1}
     * @param channelConfiguration the channel configuration, from 1 to 15
     * @param outputRate the output rate of spectral band replication in hertz, or 0 for none
     * @param parametricStereo whether to signal parametric stereo; ignored without {@code outputRate}
     * @return the configuration
     * @throws IllegalArgumentException if an argument is out of range
     */
    public static byte[] audioSpecificConfig(int objectType, int sampleRate, int channelConfiguration, int outputRate,
                                             boolean parametricStereo) {
        if (objectType < 1 || objectType > 95) {
            throw new IllegalArgumentException("audio object type " + objectType + " is not in [1, 95]");
        }
        if (sampleRate <= 0 || sampleRate > 0xFFFFFF || outputRate < 0 || outputRate > 0xFFFFFF) {
            throw new IllegalArgumentException("sampling rates " + sampleRate + " and " + outputRate
                    + " are not in [1, 16777215]");
        }
        if (channelConfiguration < 1 || channelConfiguration > 15) {
            throw new IllegalArgumentException("channel configuration " + channelConfiguration + " is not in [1, 15]");
        }
        var writer = new MsbBitWriter(16);
        if (objectType > 30) {
            writer.write(31, 5);
            writer.write(objectType - 32, 6);
        } else {
            writer.write(objectType, 5);
        }
        writeRate(writer, sampleRate);
        writer.write(channelConfiguration, 4);
        writer.write(0, 3);
        if (outputRate > 0) {
            writer.write(SYNC_EXTENSION_SBR, 11);
            writer.write(5, 5);
            writer.write(1, 1);
            writeRate(writer, outputRate);
            if (parametricStereo) {
                writer.write(SYNC_EXTENSION_PS, 11);
                writer.write(1, 1);
            }
        }
        writer.alignWithZeros();
        return writer.toByteArray();
    }

    /**
     * Writes a sampling rate as its frequency index, or as the escape and the explicit rate.
     *
     * @param writer the configuration being built
     * @param rate the rate in hertz
     */
    private static void writeRate(MsbBitWriter writer, int rate) {
        var index = AacRates.index(rate);
        if (index >= 0) {
            writer.write(index, 4);
        } else {
            writer.write(AacRates.ESCAPE, 4);
            writer.write(rate, 24);
        }
    }

    /**
     * Describes an MPEG audio frame header.
     *
     * @param layer the layer, from 1 to 3
     * @param sampleRate the sampling rate in hertz
     * @param samplesPerFrame the number of samples one frame decodes to
     * @param mpeg1 whether the frame is MPEG-1 audio rather than MPEG-2 or MPEG-2.5
     */
    public record MpegHeader(int layer, int sampleRate, int samplesPerFrame, boolean mpeg1) {
    }

    /**
     * Parses the four-byte header at the start of an MPEG audio frame, per ISO/IEC 11172-3 clause
     * 2.4.2.3 and its MPEG-2 and MPEG-2.5 extensions.
     *
     * @param data the frame bytes
     * @param offset the index of the header
     * @param length the number of available bytes from {@code offset}
     * @return the header, or {@code null} when the bytes are not a valid header
     */
    public static MpegHeader parseMpegHeader(byte[] data, int offset, int length) {
        if (length < MpegAudioHeader.SIZE) {
            return null;
        }
        var header = MpegAudioHeader.read(data, offset);
        var versionAndLayer = (header >>> 16) & 0xFF;
        if ((header >>> 24) != 0xFF || (versionAndLayer & 0xE0) != 0xE0 || ((versionAndLayer >> 3) & 3) == 1
                || ((versionAndLayer >> 1) & 3) == 0 || MpegAudioHeader.sampleRateIndex(header) == 3) {
            return null;
        }
        return new MpegHeader(MpegAudioHeader.layer(header), MpegAudioHeader.sampleRate(header),
                MpegAudioHeader.samplesPerFrame(header), MpegAudioHeader.isMpeg1(header));
    }

    /**
     * Returns the number of 48 kHz samples an Opus packet decodes to, from its TOC byte and
     * frame count, per RFC 6716 clause 3.1.
     *
     * @param packet the packet bytes
     * @return the duration in 48 kHz samples, or {@code 0} for an empty or invalid packet
     */
    public static int opusPacketSamples(byte[] packet) {
        if (packet.length == 0) {
            return 0;
        }
        var toc = packet[0] & 0xFF;
        var config = toc >> 3;
        int frameSamples;
        if (config < 12) {
            frameSamples = 480 << (config & 3);
            if ((config & 3) == 3) {
                frameSamples = 2880;
            }
        } else if (config < 16) {
            frameSamples = (config & 1) == 0 ? 480 : 960;
        } else {
            frameSamples = 120 << (config & 3);
        }
        int frames = switch (toc & 3) {
            case 0 -> 1;
            case 1, 2 -> 2;
            default -> packet.length < 2 ? 0 : packet[1] & 0x3F;
        };
        var samples = frameSamples * frames;
        return samples > 5760 ? 0 : samples;
    }

    /**
     * Describes the fields of an Opus identification header a container needs.
     *
     * @param channels the output channel count
     * @param preSkip the number of 48 kHz samples to discard at the start of the decoded output
     * @param inputRate the sampling rate of the original input, informational
     * @param outputGain the output gain in Q7.8 decibels
     * @param family the channel mapping family
     * @param mapping the stream count, coupled count and channel mapping table (for family 3 the demixing
     * matrix), or an empty array for family 0
     */
    public record OpusConfig(int channels, int preSkip, long inputRate, int outputGain, int family, byte[] mapping) {
    }

    /**
     * Parses an {@code OpusHead} identification header, per RFC 7845 clause 5.1.
     *
     * @param head the header bytes
     * @return the parsed fields
     * @throws MediaException.Malformed if the magic, version or length is invalid
     */
    public static OpusConfig parseOpusHead(byte[] head) {
        var header = OpusHeader.parseLenient(head);
        var mapping = header.family() == 0 ? new byte[0] : Arrays.copyOfRange(head, OpusHeader.BASE_SIZE,
                OpusHeader.BASE_SIZE + 2 + OpusHeader.tableSize(header.family(), header.channels(),
                        header.streamCount(), header.coupledCount()));
        return new OpusConfig(header.channels(), header.preSkip(), header.inputSampleRate(), header.outputGain(),
                header.family(), mapping);
    }

    /**
     * Builds an Opus identification header for a stream whose container declares only its channel count
     * (RFC 7845 clause 5.1).
     *
     * <p>One or two channels use mapping family 0; three to eight channels, and dual mono, use mapping
     * family 1 with the Vorbis channel order and the stream counts RFC 7845 assigns to the channel count.
     * The output gain is zero.
     *
     * @param channels the channel count, from 1 to 8
     * @param preSkip the number of 48 kHz samples to discard at the start, from 0 to 65535
     * @param inputRate the sampling rate of the original input, informational
     * @param dualMono whether two channels are coded as two uncoupled mono streams
     * @return the header bytes
     * @throws IllegalArgumentException if {@code channels} is outside 1 to 8
     */
    public static byte[] opusHead(int channels, int preSkip, long inputRate, boolean dualMono) {
        if (channels < 1 || channels > 8) {
            throw new IllegalArgumentException(channels + " Opus channels without a channel mapping");
        }
        var family = channels > 2 || dualMono ? 1 : 0;
        var head = new byte[family == 0 ? 19 : 21 + channels];
        System.arraycopy(OPUS_HEAD, 0, head, 0, OPUS_HEAD.length);
        head[8] = 1;
        head[9] = (byte) channels;
        ByteArrays.put16le(head, 10, preSkip);
        ByteArrays.put32le(head, 12, (int) inputRate);
        head[18] = (byte) family;
        if (family != 0) {
            head[19] = (byte) (dualMono ? 2 : OPUS_STREAMS[channels - 1]);
            head[20] = (byte) (dualMono ? 0 : OPUS_COUPLED[channels - 1]);
            for (var index = 0; index < channels; index++) {
                head[21 + index] = (byte) (dualMono ? index : OPUS_MAPPINGS[channels - 1][index]);
            }
        }
        return head;
    }

    /**
     * Returns the pre-skip of an Opus identification header without validating it.
     *
     * <p>The pre-skip is the little-endian 16-bit field at offset 10; neither the signature nor the
     * other fields are checked, so this suits a configuration that a container or a track already
     * validated.
     *
     * @param config the {@code OpusHead} bytes, or {@code null}
     * @return the pre-skip in 48 kHz samples, from 0 to 65535, or {@code -1} when {@code config} is {@code null} or
     * shorter than 12 bytes
     */
    public static int opusPreSkip(byte[] config) {
        return config == null || config.length < 12 ? -1 : ByteArrays.u16le(config, 10);
    }

    /**
     * Converts the payload of an ISO base media {@code dOps} box into an {@code OpusHead}, per the
     * Opus-in-ISOBMFF encapsulation clause 4.3.2: the fields keep their order, the version
     * becomes 1 and the multi-byte fields become little-endian. For mapping family 3 the channel
     * mapping table is the one of RFC 8486 section 3.2, the stream counts and the demixing matrix,
     * copied as it is; a family 3 box whose table stops after one byte per channel, as some writers
     * produce, keeps that short table, which an Opus decoder then reports as truncated.
     *
     * @param dops the {@code dOps} payload after the box header
     * @return the {@code OpusHead} bytes
     * @throws MediaException.Malformed if the box is truncated
     */
    public static byte[] opusHeadFromDops(byte[] dops) {
        if (dops.length < 11) {
            throw new MediaException.Malformed("dOps box of " + dops.length + " bytes is too short");
        }
        if (dops[0] != 0) {
            throw new MediaException.Unsupported("opus", "unsupported dOps version " + (dops[0] & 0xFF));
        }
        var channels = dops[1] & 0xFF;
        var family = dops[10] & 0xFF;
        var mappingLength = family == 0 ? 0 : 2 + channels;
        if (family == 3 && dops.length >= 13) {
            var matrix = 2 + OpusHeader.tableSize(family, channels, dops[11] & 0xFF, dops[12] & 0xFF);
            mappingLength = dops.length >= 11 + matrix ? matrix : mappingLength;
        }
        if (dops.length < 11 + mappingLength) {
            throw new MediaException.Malformed("dOps channel mapping truncated");
        }
        var head = new byte[19 + mappingLength];
        System.arraycopy(OPUS_HEAD, 0, head, 0, 8);
        head[8] = 1;
        head[9] = (byte) channels;
        ByteArrays.put16le(head, 10, ByteArrays.u16be(dops, 2));
        ByteArrays.put32le(head, 12, ByteArrays.i32be(dops, 4));
        ByteArrays.put16le(head, 16, ByteArrays.u16be(dops, 8));
        head[18] = (byte) family;
        System.arraycopy(dops, 11, head, 19, mappingLength);
        return head;
    }

    /**
     * Converts an {@code OpusHead} into the payload of an ISO base media {@code dOps} box, the
     * inverse of {@link #opusHeadFromDops(byte[])}.
     *
     * @param head the {@code OpusHead} bytes
     * @return the {@code dOps} payload
     * @throws MediaException.Malformed if the header is invalid
     */
    public static byte[] dopsFromOpusHead(byte[] head) {
        var config = parseOpusHead(head);
        var dops = new byte[11 + config.mapping().length];
        dops[0] = 0;
        dops[1] = (byte) config.channels();
        ByteArrays.put16be(dops, 2, config.preSkip());
        ByteArrays.put32be(dops, 4, (int) config.inputRate());
        ByteArrays.put16be(dops, 8, config.outputGain());
        dops[10] = (byte) config.family();
        System.arraycopy(config.mapping(), 0, dops, 11, config.mapping().length);
        return dops;
    }

    /**
     * Describes the fields of an {@code ALACSpecificConfig} a container needs.
     *
     * @param frameLength the number of samples per frame
     * @param bitDepth the sample size in bits
     * @param channels the channel count
     * @param sampleRate the sampling rate in hertz
     */
    public record AlacConfig(int frameLength, int bitDepth, int channels, int sampleRate) {
    }

    /**
     * Returns the 24-byte {@code ALACSpecificConfig} inside an ALAC magic cookie.
     *
     * <p>Accepts the bare configuration and the forms wrapped in an {@code alac} atom header
     * (size, type and version/flags, twelve bytes) or a version/flags word (four bytes), as
     * different writers store it.
     *
     * @param cookie the magic cookie
     * @return the 24-byte configuration
     * @throws MediaException.Malformed if no configuration can be located
     */
    public static byte[] alacConfig(byte[] cookie) {
        if (cookie.length >= 12 + ALAC_CONFIG_LENGTH && cookie[4] == 'a' && cookie[5] == 'l' && cookie[6] == 'a' && cookie[7] == 'c') {
            return Arrays.copyOfRange(cookie, 12, 12 + ALAC_CONFIG_LENGTH);
        }
        if (cookie.length == 4 + ALAC_CONFIG_LENGTH || cookie.length == 4 + ALAC_CONFIG_LENGTH + 24) {
            return Arrays.copyOfRange(cookie, 4, 4 + ALAC_CONFIG_LENGTH);
        }
        if (cookie.length >= ALAC_CONFIG_LENGTH) {
            return Arrays.copyOf(cookie, ALAC_CONFIG_LENGTH);
        }
        throw new MediaException.Malformed("ALAC configuration of " + cookie.length + " bytes is too short");
    }

    /**
     * Parses an {@code ALACSpecificConfig}, per Apple's ALAC magic cookie description.
     *
     * @param config the 24-byte configuration
     * @return the parsed fields
     * @throws MediaException.Malformed if the configuration is too short
     */
    public static AlacConfig parseAlac(byte[] config) {
        if (config.length < ALAC_CONFIG_LENGTH) {
            throw new MediaException.Malformed("ALAC configuration of " + config.length + " bytes is too short");
        }
        return new AlacConfig(ByteArrays.i32be(config, 0), config[5] & 0xFF, config[9] & 0xFF, ByteArrays.i32be(config, 20));
    }

    /**
     * Describes the fields of a FLAC {@code STREAMINFO} block a container needs.
     *
     * @param sampleRate the sampling rate in hertz
     * @param channels the channel count
     * @param bitDepth the sample size in bits
     */
    public record FlacConfig(int sampleRate, int channels, int bitDepth) {
    }

    /**
     * Parses the {@code STREAMINFO} block of a FLAC configuration ({@code fLaC} followed by the
     * metadata blocks), per RFC 9639 clause 8.2.
     *
     * @param config the configuration bytes
     * @return the parsed fields
     * @throws MediaException.Malformed if the configuration does not start with a complete {@code STREAMINFO} block
     */
    public static FlacConfig parseFlac(byte[] config) {
        if (config.length < 42 || !Arrays.equals(config, 0, 4, FLAC_MARKER, 0, 4) || (config[4] & 0x7F) != 0) {
            throw new MediaException.Malformed("FLAC configuration does not start with STREAMINFO");
        }
        var info = FlacStreamInfo.read(config, 8);
        return new FlacConfig(info.sampleRate(), info.channels(), info.bitsPerSample());
    }

    /**
     * Returns a FLAC configuration without the metadata blocks that only a native FLAC file uses,
     * for containers that store the configuration out of band.
     *
     * <p>{@code SEEKTABLE} offsets point into a native file and {@code PADDING} reserves room in
     * one, so both are dropped; every other block is kept in order, and the last-block flag is set
     * on the final one only. A truncated block chain ends at the last complete block.
     *
     * @param config the {@code fLaC} marker followed by the metadata blocks, starting with {@code STREAMINFO}
     * @return the configuration, the input itself when nothing is dropped
     * @throws MediaException.Malformed if the configuration does not start with a complete {@code STREAMINFO} block
     */
    public static byte[] flacContainerConfig(byte[] config) {
        parseFlac(config);
        var out = new byte[config.length];
        System.arraycopy(config, 0, out, 0, 4);
        var length = 4;
        var last = -1;
        var dropped = false;
        for (var offset = 4; offset + 4 <= config.length; ) {
            var type = config[offset] & 0x7F;
            var size = (config[offset + 1] & 0xFF) << 16 | (config[offset + 2] & 0xFF) << 8 | (config[offset + 3] & 0xFF);
            var end = offset + 4 + size;
            if (end > config.length) {
                dropped = true;
                break;
            }
            if (type == 1 || type == 3) {
                dropped = true;
            } else {
                System.arraycopy(config, offset, out, length, end - offset);
                out[length] &= 0x7F;
                last = length;
                length += end - offset;
            }
            var lastBlock = (config[offset] & 0x80) != 0;
            offset = end;
            if (lastBlock) {
                dropped |= offset != config.length;
                break;
            }
        }
        out[last] |= (byte) 0x80;
        if (!dropped && length == config.length && Arrays.equals(out, config)) {
            return config;
        }
        return Arrays.copyOf(out, length);
    }

    /**
     * Returns the number of samples a FLAC frame decodes to, from its frame header, per RFC 9639
     * clause 9.1.
     *
     * @param frame the frame bytes
     * @return the block size, or {@code 0} when the header is invalid
     */
    public static int flacFrameSamples(byte[] frame) {
        if (frame.length < 6 || (frame[0] & 0xFF) != 0xFF || (frame[1] & 0xFE) != 0xF8) {
            return 0;
        }
        var code = (frame[2] & 0xFF) >> 4;
        if (code == 1) {
            return 192;
        }
        if (code >= 2 && code <= 5) {
            return 576 << (code - 2);
        }
        if (code >= 8) {
            return 256 << (code - 8);
        }
        if (code != 6 && code != 7) {
            return 0;
        }
        var lead = frame[4] & 0xFF;
        var length = lead < 0x80 ? 1 : Integer.numberOfLeadingZeros(~lead << 24);
        if (length < 1 || length > 7 || (lead >= 0x80 && length == 1)) {
            return 0;
        }
        var position = 4 + length;
        if (code == 6) {
            return position < frame.length ? (frame[position] & 0xFF) + 1 : 0;
        }
        return position + 1 < frame.length ? (((frame[position] & 0xFF) << 8) | (frame[position + 1] & 0xFF)) + 1 : 0;
    }
}
