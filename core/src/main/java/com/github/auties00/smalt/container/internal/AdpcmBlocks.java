package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.track.Codec;

/**
 * The packet layout rules of the ADPCM and telephony codecs a WAVE file carries: IMA and Microsoft ADPCM blocks,
 * Microsoft's GSM 06.10 blocks, and the G.722 and G.726 code streams, as {@link Codec} defines their packets and
 * WAVE, AVI and Matroska {@code A_MS/ACM} streams carry them.
 *
 * <p>A unit is the smallest run of bytes that holds whole codes and starts on a byte boundary: a block of IMA or
 * Microsoft ADPCM, a 65-byte GSM block, one G.722 byte, and for G.726 one byte of 2 or 4-bit codes or the 3 or 5 bytes
 * of eight 3 or 5-bit codes.
 */
public final class AdpcmBlocks {
    /**
     * The size of a Microsoft GSM 06.10 block, two frames.
     */
    public static final int GSM_BLOCK = 65;

    /**
     * The samples of a Microsoft GSM 06.10 block.
     */
    public static final int GSM_SAMPLES = 320;

    private AdpcmBlocks() {
        throw new AssertionError();
    }

    /**
     * {@return whether a codec is one of the ADPCM and telephony codecs}
     *
     * @param codec any codec
     */
    public static boolean supports(Codec codec) {
        return switch (codec) {
            case ADPCM_IMA_WAV, ADPCM_MS, GSM_MS, ADPCM_G722, ADPCM_G726 -> true;
            default -> false;
        };
    }

    /**
     * {@return whether the packets of a codec are blocks of one size, IMA and Microsoft ADPCM}
     *
     * @param codec an ADPCM codec
     */
    public static boolean hasBlocks(Codec codec) {
        return codec == Codec.ADPCM_IMA_WAV || codec == Codec.ADPCM_MS;
    }

    /**
     * Returns the size of the header of an IMA or Microsoft ADPCM block.
     *
     * @param codec {@link Codec#ADPCM_IMA_WAV} or {@link Codec#ADPCM_MS}
     * @param channels the channel count
     * @return the bytes before the codes
     */
    public static long headerBytes(Codec codec, int channels) {
        return (codec == Codec.ADPCM_IMA_WAV ? 4L : 7L) * channels;
    }

    /**
     * Returns whether a number of bytes is a block of IMA or Microsoft ADPCM: a header for every channel followed by
     * codes of whole samples (whole groups of eight samples per channel for IMA ADPCM).
     *
     * @param codec {@link Codec#ADPCM_IMA_WAV} or {@link Codec#ADPCM_MS}
     * @param bytes the size
     * @param channels the channel count
     * @return whether the size is one of a block
     */
    public static boolean isBlock(Codec codec, long bytes, int channels) {
        var codes = bytes - headerBytes(codec, channels);
        if (codes < 0) {
            return false;
        }
        return codec == Codec.ADPCM_IMA_WAV ? codes % (4L * channels) == 0 : codes * 2 % channels == 0;
    }

    /**
     * Returns the samples per channel the packet of a number of bytes codes.
     *
     * @param codec an ADPCM codec
     * @param bytes the packet size
     * @param channels the channel count
     * @param codeSize the G.726 code size in bits, ignored for the other codecs
     * @return the samples per channel of the whole codes of the packet, 0 when it holds none
     */
    public static long samples(Codec codec, long bytes, int channels, int codeSize) {
        return switch (codec) {
            case ADPCM_IMA_WAV -> {
                var codes = bytes - headerBytes(codec, channels);
                yield codes < 0 ? 0 : 1 + 8 * (codes / (4L * channels));
            }
            case ADPCM_MS -> {
                var codes = bytes - headerBytes(codec, channels);
                yield codes < 0 ? 0 : 2 + codes * 2 / channels;
            }
            case GSM_MS -> bytes / GSM_BLOCK * GSM_SAMPLES;
            case ADPCM_G722 -> bytes * 2;
            case ADPCM_G726 -> bytes * 8 / codeSize;
            default -> throw new IllegalArgumentException("not an ADPCM codec: " + codec);
        };
    }

    /**
     * Returns the size of the units of a code stream, the codecs whose packets are not blocks of one size.
     *
     * @param codec {@link Codec#GSM_MS}, {@link Codec#ADPCM_G722} or {@link Codec#ADPCM_G726}
     * @param codeSize the G.726 code size in bits, ignored for the other codecs
     * @return the bytes every packet but the last is a multiple of
     */
    public static int unitBytes(Codec codec, int codeSize) {
        return switch (codec) {
            case GSM_MS -> GSM_BLOCK;
            case ADPCM_G726 -> codeSize == 3 || codeSize == 5 ? codeSize : 1;
            default -> 1;
        };
    }

    /**
     * Returns the samples per channel of one unit of a code stream.
     *
     * @param codec {@link Codec#GSM_MS}, {@link Codec#ADPCM_G722} or {@link Codec#ADPCM_G726}
     * @param codeSize the G.726 code size in bits, ignored for the other codecs
     * @return the samples
     */
    public static int unitSamples(Codec codec, int codeSize) {
        return (int) samples(codec, unitBytes(codec, codeSize), 1, codeSize);
    }

    /**
     * Returns the leading bytes of the truncated end of a stream that form its last packet.
     *
     * @param codec an ADPCM codec
     * @param bytes the bytes left, fewer than a block for IMA and Microsoft ADPCM
     * @param channels the channel count
     * @param codeSize the G.726 code size in bits, ignored for the other codecs
     * @return the size of the last packet: the header and whole sample groups of a block, whole GSM blocks, every
     * byte of G.722 and G.726; 0 when they code no sample
     */
    public static int tail(Codec codec, int bytes, int channels, int codeSize) {
        return switch (codec) {
            case ADPCM_IMA_WAV, ADPCM_MS -> {
                var header = headerBytes(codec, channels);
                var codes = bytes - header;
                if (codes < 0) {
                    yield 0;
                }
                // whole groups of eight samples per channel, or codes of whole samples of every channel
                long step = codec == Codec.ADPCM_IMA_WAV ? 4L * channels : channels % 2 == 0 ? channels / 2 : channels;
                yield (int) (header + codes / step * step);
            }
            case GSM_MS -> bytes / GSM_BLOCK * GSM_BLOCK;
            default -> samples(codec, bytes, channels, codeSize) == 0 ? 0 : bytes;
        };
    }

    /**
     * Returns the code size of an ADPCM or telephony stream in an AVI or Matroska {@code A_MS/ACM} track, checking
     * that its format describes a stream of the codec.
     *
     * @param codec the codec of the format tag
     * @param wave the format
     * @return 4 for IMA and Microsoft ADPCM, the code size of G.726, 0 for GSM 06.10 and G.722, or -1 when the
     * format is not one of a stream of the codec (blocks of another size or code, several channels of a mono codec)
     */
    public static int codeSize(Codec codec, WaveFormat wave) {
        var channels = wave.channels();
        if (channels <= 0) {
            return -1;
        }
        return switch (codec) {
            case ADPCM_IMA_WAV, ADPCM_MS -> wave.bitsPerSample() == 4
                    && isBlock(codec, wave.blockAlign(), channels)
                    && samples(codec, wave.blockAlign(), channels, 0) > 1 ? 4 : -1;
            case GSM_MS -> channels == 1 && wave.blockAlign() == GSM_BLOCK ? 0 : -1;
            case ADPCM_G722 -> channels == 1 ? 0 : -1;
            default -> {
                var bits = wave.bitsPerSample();
                if ((bits < 2 || bits > 5) && wave.sampleRate() > 0 && wave.byteRate() * 8 % wave.sampleRate() == 0) {
                    bits = (int) Math.min(wave.byteRate() * 8 / wave.sampleRate(), 6);
                }
                yield channels == 1 && bits >= 2 && bits <= 5 ? bits : -1;
            }
        };
    }
}
