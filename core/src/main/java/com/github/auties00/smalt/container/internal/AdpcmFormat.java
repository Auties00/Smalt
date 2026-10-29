package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;

import java.util.Arrays;

/**
 * The {@code WAVEFORMATEX} of an ADPCM or telephony track that a muxer writes, and the packets it accepts for it:
 * IMA ADPCM ({@code IMAADPCMWAVEFORMAT}, tag {@code 0x0011}), Microsoft ADPCM ({@code ADPCMWAVEFORMAT}, tag
 * {@code 0x0002}, with the coefficient table of the track's configuration or the seven standard pairs), GSM 06.10
 * ({@code GSM610WAVEFORMAT}, tag {@code 0x0031}), G.722 (tag {@code 0x028F}) and G.726 (tag {@code 0x0045}, the
 * code size as the bits per sample), as WAVE, AVI and Matroska {@code A_MS/ACM} tracks store them.
 *
 * <p>The block size of IMA and Microsoft ADPCM, which the track does not declare, is the size of the first packet;
 * every later packet is a block of that size except the last, which may be shorter. Before the first packet the
 * format declares blocks of 256 bytes per channel, or of only the block headers when those do not fit the 16-bit
 * field, unless {@link #declare()} fixed that size for the packets to come.
 *
 * <p>Instances are not thread-safe.
 */
public final class AdpcmFormat {
    /**
     * The seven standard predictor coefficient pairs of Microsoft ADPCM, as an {@code ADPCMWAVEFORMAT} stores them.
     */
    private static final int[] MS_ADPCM_COEFFICIENTS = {256, 0, 512, -256, 0, 0, 192, 64, 240, 0, 460, -208, 392, -232};

    /**
     * The codec.
     */
    private final Codec codec;

    /**
     * The channel count.
     */
    private final int channels;

    /**
     * The sampling rate in hertz.
     */
    private final int sampleRate;

    /**
     * The G.726 code size in bits, 4 for IMA and Microsoft ADPCM, 0 for the others.
     */
    private final int codeSize;

    /**
     * {@code wNumCoef} and the coefficient pairs of Microsoft ADPCM, or {@code null}.
     */
    private final byte[] coefficients;

    /**
     * The size of the first packet, or of the declared blocks, 0 before either.
     */
    private int first;

    /**
     * Whether a packet shorter than the blocks, or not ending on a unit boundary, was accepted, so that it was the
     * last.
     */
    private boolean ended;

    /**
     * Creates the format of a track.
     *
     * @param track an audio track of one of the codecs {@link AdpcmBlocks#supports(Codec)} accepts
     * @param container the container's name, for the messages of the exceptions
     * @throws IllegalArgumentException if the track has no channel layout or sampling rate the format can declare,
     * several channels of a mono codec, a G.726 code size outside 2 to 5 bits, codes of another size than 4 bits
     * for IMA or Microsoft ADPCM, or a Microsoft ADPCM configuration shorter than its coefficient table
     */
    public AdpcmFormat(AudioTrack track, String container) {
        this.codec = track.codec();
        if (!AdpcmBlocks.supports(codec)) {
            throw new IllegalArgumentException("not an ADPCM codec: " + codec);
        }
        this.channels = track.layout().map(layout -> layout.channels()).orElse(1);
        this.sampleRate = track.sampleRate();
        var name = codec.displayName();
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("a " + container + " " + name + " track needs its sample rate");
        }
        if (!AdpcmBlocks.hasBlocks(codec) && channels != 1) {
            throw new IllegalArgumentException("a " + container + " " + name + " track must have one channel");
        }
        if (AdpcmBlocks.hasBlocks(codec) && (channels > 0xFFFF || AdpcmBlocks.headerBytes(codec, channels) > 0xFFFF)) {
            throw new IllegalArgumentException("a " + name + " block of " + channels
                    + " channels is larger than a WAVE format can declare");
        }
        switch (codec) {
            case ADPCM_G726 -> {
                this.codeSize = track.bitsPerSample();
                if (codeSize < 2 || codeSize > 5) {
                    throw new IllegalArgumentException("a " + container + " G.726 track needs its code size, 2 to 5 "
                            + "bits per sample");
                }
            }
            case ADPCM_IMA_WAV, ADPCM_MS -> {
                if (track.bitsPerSample() != 0 && track.bitsPerSample() != 4) {
                    throw new IllegalArgumentException("a " + container + " " + name + " track has 4-bit codes, not "
                            + track.bitsPerSample());
                }
                this.codeSize = 4;
            }
            default -> this.codeSize = 0;
        }
        this.coefficients = codec == Codec.ADPCM_MS ? coefficients(track.codecConfig().orElse(null)) : null;
    }

    /**
     * Returns the predictor coefficients of a Microsoft ADPCM configuration.
     *
     * @param config the configuration, the {@code ADPCMWAVEFORMAT} fields after {@code cbSize}, or {@code null}
     * @return {@code wNumCoef} and the coefficient pairs, the seven standard pairs without a configuration
     * @throws IllegalArgumentException if the configuration is shorter than its coefficient table, or declares none
     */
    public static byte[] coefficients(byte[] config) {
        if (config == null) {
            var out = new ByteWriter(2 + 2 * MS_ADPCM_COEFFICIENTS.length);
            out.u16le(MS_ADPCM_COEFFICIENTS.length / 2);
            for (var coefficient : MS_ADPCM_COEFFICIENTS) {
                out.u16le(coefficient & 0xFFFF);
            }
            return out.toByteArray();
        }
        var count = config.length >= 4 ? (config[2] & 0xFF) | (config[3] & 0xFF) << 8 : 0;
        if (count == 0 || config.length < 4 + 4 * count) {
            throw new IllegalArgumentException("a Microsoft ADPCM configuration of " + config.length + " bytes and "
                    + count + " coefficient pairs");
        }
        return Arrays.copyOfRange(config, 2, 4 + 4 * count);
    }

    /**
     * Returns the {@code WAVEFORMATEX} of an ADPCM or telephony stream.
     *
     * @param codec one of the codecs {@link AdpcmBlocks#supports(Codec)} accepts
     * @param channels the channel count
     * @param sampleRate the sampling rate in hertz
     * @param codeSize the G.726 code size in bits, ignored for the other codecs
     * @param coefficients {@code wNumCoef} and the coefficient pairs of Microsoft ADPCM, ignored for the other codecs
     * @param blockAlign the block size of IMA and Microsoft ADPCM, ignored for the other codecs
     * @param channelMask the speaker mask, which a {@code WAVEFORMATEX} without a sub-format does not store
     * @return the format: the block alignment, the average byte rate of the definitions and, for the formats that
     * have one, {@code wSamplesPerBlock} and the coefficient table
     */
    public static WaveFormat waveFormat(Codec codec, int channels, int sampleRate, int codeSize, byte[] coefficients,
                                        int blockAlign, long channelMask) {
        int tag;
        long byteRate;
        var bits = 4;
        var extra = new byte[0];
        switch (codec) {
            case ADPCM_IMA_WAV, ADPCM_MS -> {
                tag = codec == Codec.ADPCM_MS ? WaveFormat.MS_ADPCM : WaveFormat.IMA_ADPCM;
                var samplesPerBlock = blockAlign > 0 ? AdpcmBlocks.samples(codec, blockAlign, channels, 0) : 0;
                byteRate = samplesPerBlock > 0 ? (long) sampleRate * blockAlign / samplesPerBlock : 0;
                var out = new ByteWriter(2 + (coefficients == null ? 0 : coefficients.length));
                out.u16le((int) samplesPerBlock);
                if (codec == Codec.ADPCM_MS && coefficients != null) {
                    out.bytes(coefficients);
                }
                extra = out.toByteArray();
            }
            case GSM_MS -> {
                tag = WaveFormat.GSM610;
                blockAlign = AdpcmBlocks.GSM_BLOCK;
                bits = 0;
                byteRate = (long) sampleRate * AdpcmBlocks.GSM_BLOCK / AdpcmBlocks.GSM_SAMPLES;
                var out = new ByteWriter(2);
                out.u16le(AdpcmBlocks.GSM_SAMPLES);
                extra = out.toByteArray();
            }
            case ADPCM_G722 -> {
                tag = WaveFormat.G722;
                blockAlign = 1;
                byteRate = sampleRate / 2;
            }
            case ADPCM_G726 -> {
                tag = WaveFormat.G726;
                blockAlign = AdpcmBlocks.unitBytes(codec, codeSize);
                bits = codeSize;
                byteRate = (long) sampleRate * codeSize / 8;
            }
            default -> throw new IllegalArgumentException("not an ADPCM codec: " + codec);
        }
        return new WaveFormat(tag, channels, sampleRate, Math.min(byteRate, 0xFFFFFFFFL), blockAlign, bits, bits,
                channelMask, WaveFormat.SubFormat.NONE, extra);
    }

    /**
     * Fixes the block size of IMA and Microsoft ADPCM to the one {@link #blockAlign()} declares now, for a muxer
     * that writes the format before the first packet and cannot rewrite it; nothing for the other codecs or after the
     * first packet.
     */
    public void declare() {
        if (AdpcmBlocks.hasBlocks(codec) && first == 0) {
            first = blockAlign();
        }
    }

    /**
     * Checks the size of a packet against the packets accepted before it, and records it.
     *
     * @param size the packet size
     * @throws IllegalArgumentException if the packet is empty, not a block, a block larger than the first or of
     * another size after a shorter one, larger than a WAVE format can declare, not a whole number of GSM blocks,
     * codes no sample, or follows the last packet
     */
    public void accept(int size) {
        var name = codec.displayName();
        if (size == 0) {
            throw new IllegalArgumentException("an empty " + name + " packet");
        }
        if (ended) {
            throw new IllegalArgumentException("a " + name + " packet after the last one, which was shorter than the "
                    + "others or ended inside a unit of codes");
        }
        if (AdpcmBlocks.hasBlocks(codec)) {
            if (!AdpcmBlocks.isBlock(codec, size, channels)) {
                throw new IllegalArgumentException("a " + name + " packet of " + size + " bytes is not a block of "
                        + channels + " channels");
            }
            if (size > 0xFFFF || AdpcmBlocks.samples(codec, size, channels, 0) > 0xFFFF) {
                throw new IllegalArgumentException("a " + name + " block of " + size + " bytes is larger than a WAVE "
                        + "format can declare");
            }
            if (first > 0 && size > first) {
                throw new IllegalArgumentException("a " + name + " block of " + size + " bytes after blocks of "
                        + first);
            }
            if (first == 0) {
                first = size;
            } else if (size < first) {
                ended = true;
            }
            return;
        }
        if (codec == Codec.GSM_MS && size % AdpcmBlocks.GSM_BLOCK != 0) {
            throw new IllegalArgumentException("a GSM 06.10 packet of " + size + " bytes is not whole 65-byte blocks");
        }
        if (AdpcmBlocks.samples(codec, size, channels, codeSize) == 0) {
            throw new IllegalArgumentException("a " + name + " packet of " + size + " bytes codes no sample");
        }
        if (size % AdpcmBlocks.unitBytes(codec, codeSize) != 0) {
            ended = true;
        }
    }

    /**
     * {@return the block alignment the format declares: the first packet's size for IMA and Microsoft ADPCM (256
     * bytes per channel, or the block headers, before it), the unit of the other codecs}
     */
    public int blockAlign() {
        if (!AdpcmBlocks.hasBlocks(codec)) {
            return AdpcmBlocks.unitBytes(codec, codeSize);
        }
        if (first > 0) {
            return first;
        }
        return 256 * channels <= 0xFFFF ? 256 * channels : (int) AdpcmBlocks.headerBytes(codec, channels);
    }

    /**
     * {@return the samples per channel of a block of {@link #blockAlign()} bytes}
     */
    public int blockSamples() {
        return (int) AdpcmBlocks.samples(codec, blockAlign(), channels, codeSize);
    }

    /**
     * Returns the blocks or units a packet occupies, a partial one counted whole.
     *
     * @param size the packet size
     * @return the count
     */
    public long units(int size) {
        var unit = blockAlign();
        return (size + unit - 1L) / unit;
    }

    /**
     * {@return the bytes of the {@code WAVEFORMATEX} for the packets accepted so far, with {@code cbSize}; its size
     * does not change with them}
     */
    public byte[] waveFormatBytes() {
        var format = waveFormat(codec, channels, sampleRate, codeSize, coefficients, blockAlign(), 0);
        var out = new ByteWriter(format.size(true));
        format.write(out, true);
        return out.toByteArray();
    }
}
