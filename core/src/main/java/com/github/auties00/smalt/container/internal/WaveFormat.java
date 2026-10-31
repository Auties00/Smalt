package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.Ambisonics;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.track.Codec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;

/**
 * Holds a {@code WAVEFORMATEX} structure, or a {@code WAVEFORMATEXTENSIBLE} one, the audio format
 * of the WAVE {@code fmt } chunk and of the AVI {@code strf} chunk.
 *
 * {@snippet :
 * u16 wFormatTag        0xFFFE announces WAVEFORMATEXTENSIBLE
 * u16 nChannels
 * u32 nSamplesPerSec
 * u32 nAvgBytesPerSec
 * u16 nBlockAlign
 * u16 wBitsPerSample
 * u16 cbSize            the bytes that follow, absent from a 16-byte PCM structure
 * u16 wValidBitsPerSample   WAVEFORMATEXTENSIBLE only, with the next two fields
 * u32 dwChannelMask
 * GUID SubFormat        the format tag in its first two bytes
 * }
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param tag the format tag; for a {@code WAVEFORMATEXTENSIBLE} structure, the tag in its sub-format
 * @param channels the channel count
 * @param sampleRate the sampling rate, in hertz
 * @param byteRate the average number of bytes per second
 * @param blockAlign the size of one block of samples of every channel, in bytes
 * @param bitsPerSample the size of one sample container, in bits, or 0 when the structure is too short to declare it
 * @param validBits the significant bits of each sample: {@code wValidBitsPerSample} when it is between 1 and
 * {@code bitsPerSample}, {@code bitsPerSample} otherwise
 * @param channelMask the speaker positions, one bit per {@link Speaker} in declaration order, or 0 when undeclared
 * @param subFormat the family of the sub-format GUID of a {@code WAVEFORMATEXTENSIBLE} structure, or
 * {@link SubFormat#NONE} for a plain {@code WAVEFORMATEX}
 * @param extra the codec-specific bytes after the structure, such as an AAC {@code AudioSpecificConfig}
 */
public record WaveFormat(int tag, int channels, long sampleRate, long byteRate, int blockAlign, int bitsPerSample,
                         int validBits, long channelMask, SubFormat subFormat, byte[] extra) {
    /**
     * The format tag of integer PCM.
     */
    public static final int PCM = 0x0001;

    /**
     * The format tag of IEEE float PCM.
     */
    public static final int FLOAT = 0x0003;

    /**
     * The format tag of G.711 A-law.
     */
    public static final int ALAW = 0x0006;

    /**
     * The format tag of G.711 mu-law.
     */
    public static final int MULAW = 0x0007;

    /**
     * The format tag of Microsoft ADPCM ({@code WAVE_FORMAT_ADPCM}).
     */
    public static final int MS_ADPCM = 0x0002;

    /**
     * The format tag of IMA ADPCM ({@code WAVE_FORMAT_DVI_ADPCM}, also {@code WAVE_FORMAT_IMA_ADPCM}).
     */
    public static final int IMA_ADPCM = 0x0011;

    /**
     * The format tag of GSM 06.10 in Microsoft's 65-byte blocks ({@code WAVE_FORMAT_GSM610}).
     */
    public static final int GSM610 = 0x0031;

    /**
     * The format tag of G.726 ADPCM whose codes are packed from the most significant bit, as ffmpeg reads and writes
     * it.
     */
    public static final int G726 = 0x0045;

    /**
     * The format tag of G.722 ADPCM, as ffmpeg reads and writes it.
     */
    public static final int G722 = 0x028F;

    /**
     * The format tag of MPEG-1 Layer I or II audio.
     */
    public static final int MPEG = 0x0050;

    /**
     * The format tag of MPEG Layer III audio.
     */
    public static final int MPEG_LAYER3 = 0x0055;

    /**
     * The format tag of AC-3 carried in IEC 61937 bursts ({@code WAVE_FORMAT_DOLBY_AC3_SPDIF}).
     */
    public static final int DOLBY_AC3_SPDIF = 0x0092;

    /**
     * The format tag of DTS carried in IEC 61937 bursts ({@code WAVE_FORMAT_DTS}, the tag of the
     * {@code KSDATAFORMAT_SUBTYPE_IEC61937_DTS} sub-format).
     */
    public static final int DTS_SPDIF = 0x0008;

    /**
     * The format tag of DTS frames stored back to back.
     */
    public static final int DTS = 0x2001;

    /**
     * The format tag of raw AAC ({@code WAVE_FORMAT_RAW_AAC1}).
     */
    public static final int RAW_AAC = 0x00FF;

    /**
     * The format tag of AAC in ADTS framing ({@code WAVE_FORMAT_MPEG_ADTS_AAC}).
     */
    public static final int MPEG_ADTS_AAC = 0x1600;

    /**
     * The format tag of AAC in LOAS framing ({@code WAVE_FORMAT_MPEG_LOAS}).
     */
    public static final int MPEG_LOAS = 0x1602;

    /**
     * The format tag of AAC described by an {@code HEAACWAVEINFO} ({@code WAVE_FORMAT_MPEG_HEAAC}), whose
     * {@code wPayloadType} names the framing.
     */
    public static final int MPEG_HEAAC = 0x1610;

    /**
     * The format tag of AMR-NB storage-format frames ({@code WAVE_FORMAT_AMR_NB}).
     */
    public static final int AMR_NB = 0x0057;

    /**
     * The format tag of AMR-WB storage-format frames ({@code WAVE_FORMAT_AMR_WB}).
     */
    public static final int AMR_WB = 0x0058;

    /**
     * The format tag of AC-3 sync frames ({@code WAVE_FORMAT_DOLBY_AC3}).
     */
    public static final int DOLBY_AC3 = 0x2000;

    /**
     * The format tag of Opus.
     */
    public static final int OPUS = 0x704F;

    /**
     * The format tag of FLAC frames.
     */
    public static final int FLAC = 0xF1AC;

    /**
     * The CEA-861 audio coding type of AC-3, the tag of an {@link SubFormat#IEC61937} sub-format.
     */
    public static final int CEA_AC3 = 2;

    /**
     * The CEA-861 audio coding type of E-AC-3, the tag of an {@link SubFormat#IEC61937} sub-format.
     */
    public static final int CEA_EAC3 = 10;

    /**
     * The format tag that announces a {@code WAVEFORMATEXTENSIBLE} structure.
     */
    public static final int EXTENSIBLE = 0xFFFE;

    /**
     * The size of a {@code WAVEFORMATEXTENSIBLE} structure without extra bytes.
     */
    public static final int EXTENSIBLE_SIZE = 40;

    /**
     * The bytes of a {@code KSDATAFORMAT_SUBTYPE} GUID after its 16-bit format tag.
     */
    private static final byte[] SUBTYPE_SUFFIX = {0x00, 0x00, 0x00, 0x00, 0x10, 0x00, (byte) 0x80, 0x00, 0x00, (byte) 0xAA,
            0x00, 0x38, (byte) 0x9B, 0x71};

    /**
     * The last 12 bytes of an Ambisonic B-format GUID ({@code 0000000x-0721-11d3-8644-c8c1ca000000}).
     */
    private static final byte[] AMBISONIC_SUFFIX = {0x21, 0x07, (byte) 0xD3, 0x11, (byte) 0x86, 0x44, (byte) 0xC8,
            (byte) 0xC1, (byte) 0xCA, 0x00, 0x00, 0x00};

    /**
     * The last 12 bytes of an IEC 61937 GUID named by a CEA-861 audio coding type
     * ({@code 000000xx-0cea-0010-8000-00aa00389b71}).
     */
    private static final byte[] IEC61937_SUFFIX = {(byte) 0xEA, 0x0C, 0x10, 0x00, (byte) 0x80, 0x00, 0x00, (byte) 0xAA,
            0x00, 0x38, (byte) 0x9B, 0x71};

    /**
     * The DirectShow {@code MEDIASUBTYPE_DOLBY_AC3} GUID ({@code e06d802c-db46-11cf-b4d1-00805f6cbbea}).
     */
    private static final byte[] DOLBY_AC3_GUID = {0x2C, (byte) 0x80, 0x6D, (byte) 0xE0, 0x46, (byte) 0xDB, (byte) 0xCF,
            0x11, (byte) 0xB4, (byte) 0xD1, 0x00, (byte) 0x80, 0x5F, 0x6C, (byte) 0xBB, (byte) 0xEA};

    /**
     * The {@code MEDIASUBTYPE_DOLBY_DDPLUS} GUID ({@code a7fb87af-2d02-42fb-a4d4-05cd93843bdd}).
     */
    private static final byte[] DOLBY_DDPLUS_GUID = {(byte) 0xAF, (byte) 0x87, (byte) 0xFB, (byte) 0xA7, 0x02, 0x2D,
            (byte) 0xFB, 0x42, (byte) 0xA4, (byte) 0xD4, 0x05, (byte) 0xCD, (byte) 0x93, (byte) 0x84, 0x3B, (byte) 0xDD};

    /**
     * Families of the sub-format GUID of a {@code WAVEFORMATEXTENSIBLE} structure.
     */
    public enum SubFormat {
        /**
         * No sub-format: a plain {@code WAVEFORMATEX}.
         */
        NONE,

        /**
         * A {@code KSDATAFORMAT_SUBTYPE} GUID wrapping a format tag ({@code xxxx0000-0000-0010-8000-00aa00389b71}).
         */
        STANDARD,

        /**
         * An Ambisonic B-format GUID; the tag is {@link #PCM} or {@link #FLOAT}.
         */
        AMBISONIC,

        /**
         * An IEC 61937 GUID of the CEA-861 family; the tag is the CEA-861 audio coding type, and the samples are
         * 16-bit stereo frames carrying IEC 61937 bursts.
         */
        IEC61937,

        /**
         * The DirectShow AC-3 GUID; the data is AC-3 sync frames.
         */
        DOLBY_AC3,

        /**
         * The E-AC-3 GUID; the data is E-AC-3 sync frames.
         */
        DOLBY_DIGITAL_PLUS,

        /**
         * Any other GUID.
         */
        UNKNOWN
    }

    /**
     * Copies the extra bytes, so that the record owns them.
     */
    public WaveFormat {
        Objects.requireNonNull(subFormat, "subFormat");
        extra = extra.clone();
    }

    /**
     * {@return whether the structure is a {@code WAVEFORMATEXTENSIBLE}}
     */
    public boolean extensible() {
        return subFormat != SubFormat.NONE;
    }

    /**
     * Parses a structure.
     *
     * <p>The fields a short structure lacks read as 0: {@code bitsPerSample} below 16 bytes, the
     * extra bytes below 18. A {@code WAVEFORMATEXTENSIBLE} tag in a structure shorter than
     * {@value #EXTENSIBLE_SIZE} bytes is kept as the tag, with the sub-format {@link SubFormat#NONE}. The
     * extra bytes are bounded by both {@code cbSize} and {@code length}. The tag of a
     * {@code WAVEFORMATEXTENSIBLE} structure is the first 16 bits of its GUID, or the first 32 bits for the
     * {@link SubFormat#AMBISONIC} and {@link SubFormat#IEC61937} families.
     *
     * @param data the bytes holding the structure from index 0
     * @param length the number of bytes of the structure available, at least 14
     * @param bigEndian whether the fields are big-endian (RIFX)
     * @return the format
     * @throws MediaException.Malformed if {@code length} is below 14
     */
    public static WaveFormat parse(byte[] data, int length, boolean bigEndian) {
        if (length < 14) {
            throw new MediaException.Malformed("WAVEFORMATEX of " + length + " bytes is too short");
        }
        var tag = ByteArrays.u16(data, 0, !bigEndian);
        var channels = ByteArrays.u16(data, 2, !bigEndian);
        var sampleRate = ByteArrays.u32(data, 4, !bigEndian);
        var byteRate = ByteArrays.u32(data, 8, !bigEndian);
        var blockAlign = ByteArrays.u16(data, 12, !bigEndian);
        var bits = length >= 16 ? ByteArrays.u16(data, 14, !bigEndian) : 0;
        var extraLength = length >= 18 ? Math.min(ByteArrays.u16(data, 16, !bigEndian), length - 18) : 0;
        var extraStart = 18;
        var validBits = bits;
        var mask = 0L;
        var subFormat = SubFormat.NONE;
        if (tag == EXTENSIBLE && length >= EXTENSIBLE_SIZE) {
            var declared = ByteArrays.u16(data, 18, !bigEndian);
            if (declared != 0 && declared <= bits) {
                validBits = declared;
            }
            mask = ByteArrays.u32(data, 20, !bigEndian);
            tag = ByteArrays.u16(data, 24, !bigEndian);
            subFormat = subFormat(data, 24);
            if (subFormat == SubFormat.AMBISONIC || subFormat == SubFormat.IEC61937) {
                tag = (int) ByteArrays.u32le(data, 24);
            }
            extraLength = Math.max(0, extraLength - (EXTENSIBLE_SIZE - 18));
            extraStart = EXTENSIBLE_SIZE;
        }
        return new WaveFormat(tag, channels, sampleRate, byteRate, blockAlign, bits, validBits, mask, subFormat,
                extraLength == 0 ? new byte[0] : Arrays.copyOfRange(data, extraStart, extraStart + extraLength));
    }

    /**
     * Classifies a sub-format GUID, stored in the little-endian layout of the {@code GUID} structure.
     *
     * @param data the bytes
     * @param offset the index of the GUID's first byte
     * @return the family
     */
    private static SubFormat subFormat(byte[] data, int offset) {
        if (Arrays.equals(data, offset + 2, offset + 16, SUBTYPE_SUFFIX, 0, SUBTYPE_SUFFIX.length)) {
            return SubFormat.STANDARD;
        }
        var upperZero = data[offset + 2] == 0 && data[offset + 3] == 0;
        if (upperZero && data[offset + 1] == 0 && (data[offset] == PCM || data[offset] == FLOAT)
                && Arrays.equals(data, offset + 4, offset + 16, AMBISONIC_SUFFIX, 0, AMBISONIC_SUFFIX.length)) {
            return SubFormat.AMBISONIC;
        }
        if (upperZero && Arrays.equals(data, offset + 4, offset + 16, IEC61937_SUFFIX, 0, IEC61937_SUFFIX.length)) {
            return SubFormat.IEC61937;
        }
        if (Arrays.equals(data, offset, offset + 16, DOLBY_AC3_GUID, 0, 16)) {
            return SubFormat.DOLBY_AC3;
        }
        if (Arrays.equals(data, offset, offset + 16, DOLBY_DDPLUS_GUID, 0, 16)) {
            return SubFormat.DOLBY_DIGITAL_PLUS;
        }
        return SubFormat.UNKNOWN;
    }

    /**
     * {@return the speaker layout of the channel mask: the speakers of the set mask bits in mask order,
     * padded with {@link Speaker#UNKNOWN}, or the default layout of the channel count without a mask; for the
     * {@link SubFormat#AMBISONIC} sub-formats, the layout of {@link #ambisonicLayout(int)} whatever the mask}
     */
    public ChannelLayout layout() {
        if (subFormat == SubFormat.AMBISONIC) {
            return ambisonicLayout(channels);
        }
        if (channelMask == 0) {
            return ChannelLayout.defaultFor(channels);
        }
        var speakers = new ArrayList<Speaker>(channels);
        var positions = Speaker.values();
        for (var bit = 0; bit <= Speaker.TOP_BACK_RIGHT.ordinal() && speakers.size() < channels; bit++) {
            if ((channelMask & (1L << bit)) != 0) {
                speakers.add(positions[bit]);
            }
        }
        while (speakers.size() < channels) {
            speakers.add(Speaker.UNKNOWN);
        }
        return new ChannelLayout(speakers);
    }

    /**
     * Returns the layout of the Ambisonic B-format sub-formats: the Furse-Malham channels of a count, in the order
     * {@code W X Y Z R S T U V K L M N O P Q} restricted to the set of the count.
     *
     * @param channels the channel count
     * @return the B-format layout for 3, 4, 5, 6, 7, 8, 9, 11 and 16 channels, channels of unknown position
     * without ambisonics for other counts
     */
    public static ChannelLayout ambisonicLayout(int channels) {
        return switch (channels) {
            case 3, 4, 5, 6, 7, 8, 9, 11, 16 -> ChannelLayout.ofAmbisonics(Ambisonics.ofFurseMalham(channels));
            default -> new ChannelLayout(Collections.nCopies(channels, Speaker.UNKNOWN));
        };
    }

    /**
     * Returns whether a layout is a Furse-Malham B-format that the Ambisonic sub-formats describe, so that a
     * writer declares it with {@link SubFormat#AMBISONIC}.
     *
     * @param layout the channel layout of a track
     * @return {@code true} when every channel carries a component in the B-format order of its channel count
     */
    public static boolean isAmbisonicBFormat(ChannelLayout layout) {
        var ambisonics = layout.ambisonics();
        return ambisonics.isPresent() && ambisonics.get().channels() == layout.channels()
                && ambisonicLayout(layout.channels()).equals(layout);
    }

    /**
     * Returns the channel mask of a layout.
     *
     * @param layout the speakers, in channel order
     * @return one bit per speaker, in {@link Speaker} declaration order, or 0 when a channel has no position or one
     * the mask cannot express (those after {@link Speaker#TOP_BACK_RIGHT}), or the speakers are not in mask order
     */
    public static long channelMask(ChannelLayout layout) {
        var mask = 0L;
        var previous = -1;
        for (var speaker : layout.speakers()) {
            if (speaker.ordinal() > Speaker.TOP_BACK_RIGHT.ordinal() || speaker.ordinal() <= previous) {
                return 0;
            }
            previous = speaker.ordinal();
            mask |= 1L << speaker.ordinal();
        }
        return mask;
    }

    /**
     * Returns the size of the structure {@link #write} writes.
     *
     * @param cbSize whether a plain {@code WAVEFORMATEX} carries its {@code cbSize} field
     * @return the size, in bytes
     */
    public int size(boolean cbSize) {
        if (extensible()) {
            return EXTENSIBLE_SIZE + extra.length;
        }
        return cbSize ? 18 + extra.length : 16;
    }

    /**
     * Writes the structure in little-endian order.
     *
     * <p>A {@code WAVEFORMATEXTENSIBLE} structure is written with the GUID of its sub-format family
     * and {@link #tag()} ({@link SubFormat#UNKNOWN} writes the {@code KSDATAFORMAT_SUBTYPE} GUID); a
     * plain one without {@code cbSize} drops its extra bytes.
     *
     * @param out the destination
     * @param cbSize whether a plain {@code WAVEFORMATEX} carries its {@code cbSize} field and extra bytes
     */
    public void write(ByteWriter out, boolean cbSize) {
        var extensible = extensible();
        out.u16le(extensible ? EXTENSIBLE : tag);
        out.u16le(channels);
        out.u32le(sampleRate);
        out.u32le(byteRate);
        out.u16le(blockAlign);
        out.u16le(bitsPerSample);
        if (extensible) {
            out.u16le(EXTENSIBLE_SIZE - 18 + extra.length);
            out.u16le(validBits);
            out.u32le(channelMask);
            switch (subFormat) {
                case AMBISONIC -> {
                    out.u32le(tag);
                    out.bytes(AMBISONIC_SUFFIX);
                }
                case IEC61937 -> {
                    out.u32le(tag);
                    out.bytes(IEC61937_SUFFIX);
                }
                case DOLBY_AC3 -> out.bytes(DOLBY_AC3_GUID);
                case DOLBY_DIGITAL_PLUS -> out.bytes(DOLBY_DDPLUS_GUID);
                default -> {
                    out.u16le(tag);
                    out.bytes(SUBTYPE_SUFFIX);
                }
            }
            out.bytes(extra);
        } else if (cbSize) {
            out.u16le(extra.length);
            out.bytes(extra);
        }
    }

    /**
     * Returns the codec of this structure, as AVI and Matroska {@code A_MS/ACM} streams declare it.
     *
     * @return the codec of the tag for a plain structure and the {@link SubFormat#STANDARD} and
     * {@link SubFormat#AMBISONIC} families, {@link Codec#AC3} and {@link Codec#EAC3} for the Dolby GUIDs,
     * {@link Codec#UNKNOWN_AUDIO} for IEC 61937 bursts and unknown GUIDs
     */
    public Codec codec() {
        return switch (subFormat) {
            case NONE, STANDARD, AMBISONIC -> codec(tag, bitsPerSample);
            case DOLBY_AC3 -> Codec.AC3;
            case DOLBY_DIGITAL_PLUS -> Codec.EAC3;
            case IEC61937, UNKNOWN -> Codec.UNKNOWN_AUDIO;
        };
    }

    /**
     * Maps a format tag to a codec, as AVI and Matroska {@code A_MS/ACM} streams declare it; WAVE
     * files map their PCM tags by sample container size instead.
     *
     * @param tag the format tag, the sub-format tag for a {@code WAVEFORMATEXTENSIBLE} structure
     * @param bits the bits per sample, which selects the PCM codec
     * @return the codec, {@link Codec#UNKNOWN_AUDIO} when unrecognized
     */
    public static Codec codec(int tag, int bits) {
        return switch (tag) {
            case PCM -> switch (bits) {
                case 8 -> Codec.PCM_U8;
                case 16 -> Codec.PCM_S16LE;
                case 24 -> Codec.PCM_S24LE;
                case 32 -> Codec.PCM_S32LE;
                case 64 -> Codec.PCM_S64LE;
                default -> Codec.UNKNOWN_AUDIO;
            };
            case FLOAT -> bits == 64 ? Codec.PCM_F64LE : bits == 32 ? Codec.PCM_F32LE : Codec.UNKNOWN_AUDIO;
            case ALAW -> Codec.PCM_ALAW;
            case MULAW -> Codec.PCM_MULAW;
            case MPEG -> Codec.MP2;
            case MPEG_LAYER3 -> Codec.MP3;
            case RAW_AAC, MPEG_ADTS_AAC, MPEG_LOAS, MPEG_HEAAC, 0x706D, 0x4143, 0xA106 -> Codec.AAC;
            case DOLBY_AC3 -> Codec.AC3;
            case OPUS -> Codec.OPUS;
            case FLAC -> Codec.FLAC;
            case 0x0038, AMR_NB, 0x00FE -> Codec.AMR_NB;
            case AMR_WB -> Codec.AMR_WB;
            case DTS_SPDIF, DTS -> Codec.DTS;
            case IMA_ADPCM -> Codec.ADPCM_IMA_WAV;
            case MS_ADPCM -> Codec.ADPCM_MS;
            case GSM610 -> Codec.GSM_MS;
            case G722 -> Codec.ADPCM_G722;
            case G726 -> Codec.ADPCM_G726;
            default -> Codec.UNKNOWN_AUDIO;
        };
    }

    /**
     * Returns the Microsoft ADPCM configuration of a format's extra bytes: {@code wSamplesPerBlock},
     * {@code wNumCoef} and the coefficient pairs, without what follows the table.
     *
     * @param extra the bytes after {@code cbSize}
     * @return the configuration, or {@code null} when the extra bytes hold no complete table (the decoder then uses
     * the seven standard pairs)
     */
    public static byte[] msAdpcmConfig(byte[] extra) {
        if (extra.length < 4) {
            return null;
        }
        var count = (extra[2] & 0xFF) | (extra[3] & 0xFF) << 8;
        var length = 4 + 4 * count;
        if (count == 0 || extra.length < length) {
            return null;
        }
        return extra.length == length ? extra : java.util.Arrays.copyOf(extra, length);
    }

    /**
     * Returns the format tag of a codec, as AVI stores it.
     *
     * @param codec the audio codec
     * @return the format tag, or {@code -1} when the codec has none AVI writes
     */
    public static int tagOf(Codec codec) {
        return switch (codec) {
            case PCM_U8, PCM_S16LE, PCM_S24LE, PCM_S32LE, PCM_S64LE -> PCM;
            case PCM_F32LE, PCM_F64LE -> FLOAT;
            case PCM_ALAW -> ALAW;
            case PCM_MULAW -> MULAW;
            case MP2 -> MPEG;
            case MP3 -> MPEG_LAYER3;
            case AAC -> RAW_AAC;
            case AC3 -> DOLBY_AC3;
            case ADPCM_IMA_WAV -> IMA_ADPCM;
            case ADPCM_MS -> MS_ADPCM;
            case GSM_MS -> GSM610;
            case ADPCM_G722 -> G722;
            case ADPCM_G726 -> G726;
            default -> -1;
        };
    }

    /**
     * {@return a copy of the codec-specific bytes after the structure}
     */
    @Override
    public byte[] extra() {
        return extra.clone();
    }
}
