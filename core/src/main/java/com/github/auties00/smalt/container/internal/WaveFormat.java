package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.track.Codec;

import java.util.ArrayList;
import java.util.Arrays;

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
 * @param extensible whether the structure is a {@code WAVEFORMATEXTENSIBLE}
 * @param knownSubFormat whether the sub-format GUID is a {@code KSDATAFORMAT_SUBTYPE} that wraps a format tag;
 * {@code true} for a plain {@code WAVEFORMATEX}
 * @param extra the codec-specific bytes after the structure, such as an AAC {@code AudioSpecificConfig}
 */
public record WaveFormat(int tag, int channels, long sampleRate, long byteRate, int blockAlign, int bitsPerSample,
                         int validBits, long channelMask, boolean extensible, boolean knownSubFormat, byte[] extra) {
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
     * The format tag of MPEG-1 Layer I or II audio.
     */
    public static final int MPEG = 0x0050;

    /**
     * The format tag of MPEG Layer III audio.
     */
    public static final int MPEG_LAYER3 = 0x0055;

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
     * Copies the extra bytes, so that the record owns them.
     */
    public WaveFormat {
        extra = extra.clone();
    }

    /**
     * Parses a structure.
     *
     * <p>The fields a short structure lacks read as 0: {@code bitsPerSample} below 16 bytes, the
     * extra bytes below 18. A {@code WAVEFORMATEXTENSIBLE} tag in a structure shorter than
     * {@value #EXTENSIBLE_SIZE} bytes is kept as the tag, with {@code extensible} clear. The extra
     * bytes are bounded by both {@code cbSize} and {@code length}.
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
        var extensible = tag == EXTENSIBLE && length >= EXTENSIBLE_SIZE;
        var known = true;
        if (extensible) {
            var declared = ByteArrays.u16(data, 18, !bigEndian);
            if (declared != 0 && declared <= bits) {
                validBits = declared;
            }
            mask = ByteArrays.u32(data, 20, !bigEndian);
            tag = ByteArrays.u16(data, 24, !bigEndian);
            known = Arrays.equals(data, 28, 40, SUBTYPE_SUFFIX, 2, SUBTYPE_SUFFIX.length);
            extraLength = Math.max(0, extraLength - (EXTENSIBLE_SIZE - 18));
            extraStart = EXTENSIBLE_SIZE;
        }
        return new WaveFormat(tag, channels, sampleRate, byteRate, blockAlign, bits, validBits, mask, extensible, known,
                extraLength == 0 ? new byte[0] : Arrays.copyOfRange(data, extraStart, extraStart + extraLength));
    }

    /**
     * {@return the speaker layout of the channel mask: the speakers of the set mask bits in mask order,
     * padded with {@link Speaker#UNKNOWN}, or the default layout of the channel count without a mask}
     */
    public ChannelLayout layout() {
        if (channelMask == 0) {
            return ChannelLayout.defaultFor(channels);
        }
        var speakers = new ArrayList<Speaker>(channels);
        var positions = Speaker.values();
        for (var bit = 0; bit < positions.length - 1 && speakers.size() < channels; bit++) {
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
     * Returns the channel mask of a layout.
     *
     * @param layout the speakers, in channel order
     * @return one bit per speaker, in {@link Speaker} declaration order, or 0 when a channel has no position or the
     * speakers are not in mask order
     */
    public static long channelMask(ChannelLayout layout) {
        var mask = 0L;
        var previous = -1;
        for (var speaker : layout.speakers()) {
            if (speaker == Speaker.UNKNOWN || speaker.ordinal() <= previous) {
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
        if (extensible) {
            return EXTENSIBLE_SIZE + extra.length;
        }
        return cbSize ? 18 + extra.length : 16;
    }

    /**
     * Writes the structure in little-endian order.
     *
     * <p>A {@code WAVEFORMATEXTENSIBLE} structure is written with the
     * {@code KSDATAFORMAT_SUBTYPE} GUID of {@link #tag()}; a plain one without {@code cbSize}
     * drops its extra bytes.
     *
     * @param out the destination
     * @param cbSize whether a plain {@code WAVEFORMATEX} carries its {@code cbSize} field and extra bytes
     */
    public void write(ByteWriter out, boolean cbSize) {
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
            out.u16le(tag);
            out.bytes(SUBTYPE_SUFFIX);
            out.bytes(extra);
        } else if (cbSize) {
            out.u16le(extra.length);
            out.bytes(extra);
        }
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
                default -> Codec.UNKNOWN_AUDIO;
            };
            case FLOAT -> bits == 64 ? Codec.PCM_F64LE : bits == 32 ? Codec.PCM_F32LE : Codec.UNKNOWN_AUDIO;
            case ALAW -> Codec.PCM_ALAW;
            case MULAW -> Codec.PCM_MULAW;
            case MPEG -> Codec.MP2;
            case MPEG_LAYER3 -> Codec.MP3;
            case 0x00FF, 0x1610, 0x706D, 0x4143, 0xA106 -> Codec.AAC;
            case 0x2000 -> Codec.AC3;
            case 0x704F -> Codec.OPUS;
            case 0xF1AC -> Codec.FLAC;
            case 0x0038, 0x0057, 0x00FE -> Codec.AMR_NB;
            case 0x0058 -> Codec.AMR_WB;
            default -> Codec.UNKNOWN_AUDIO;
        };
    }

    /**
     * Returns the format tag of a codec, as AVI stores it.
     *
     * @param codec the audio codec
     * @return the format tag, or {@code -1} when the codec has none AVI writes
     */
    public static int tagOf(Codec codec) {
        return switch (codec) {
            case PCM_U8, PCM_S16LE, PCM_S24LE, PCM_S32LE -> PCM;
            case PCM_F32LE, PCM_F64LE -> FLOAT;
            case PCM_ALAW -> ALAW;
            case PCM_MULAW -> MULAW;
            case MP2 -> MPEG;
            case MP3 -> MPEG_LAYER3;
            case AAC -> 0x00FF;
            case AC3 -> 0x2000;
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
