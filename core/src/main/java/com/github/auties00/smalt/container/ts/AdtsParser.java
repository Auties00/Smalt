package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.internal.AacExtensions;
import com.github.auties00.smalt.internal.header.AdtsHeader;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;

import java.util.Arrays;

/**
 * Splits an AAC stream in ADTS framing ({@code stream_type 0x0F}, ISO/IEC 13818-7 clause 6.2)
 * into raw data blocks.
 *
 * <p>The track's configuration is an {@code AudioSpecificConfig} synthesized from the first
 * header (object type, sampling frequency index, channel configuration), with the
 * backward-compatible signaling of HE-AAC added when the first frame decodes to twice the core
 * rate through the registered AAC decoder, as the ADTS demuxer does. Timestamps count samples of
 * the core rate. Each packet is the payload of one ADTS frame, header and CRC words removed; a
 * frame of several raw data blocks stays one packet lasting {@code 1024} samples per block. A frame
 * whose version, profile, sampling frequency or channel configuration differ from the previous one
 * starts a new stream configuration when the frame after it repeats them, and is skipped as
 * garbage otherwise; the first packet of a new configuration carries its
 * {@code AudioSpecificConfig}, refined for HE-AAC as the track's is, as its new codec
 * configuration, and its durations stay in samples of the track's rate. A {@linkplain #fresh()
 * fresh} parser, which a read pass or a seek starts with, starts from the track's configuration,
 * so the first frame of another configuration it meets is announced again.
 *
 * <p>The frames of a {@code SAMPLE-AES} stream ({@code stream_type 0xCF}) are decrypted before they are read. Its
 * audio setup information gives the track's {@link AudioTrack#skipSamples() skipped samples} (the priming, in samples
 * of the core rate) and, when it declares HE-AAC ({@code zach} or {@code zacp}) with an {@code AudioSpecificConfig}
 * of the same core rate and channel configuration as the headers, the track's configuration.
 */
final class AdtsParser extends FrameParser {
    /**
     * The bits of the second and third header bytes that every frame of one stream shares: the
     * MPEG version, layer, profile and sampling frequency index, plus the top bit of the channel
     * configuration.
     */
    private static final int STREAM_MASK = 0x0EFD;

    /**
     * The {@code audio_type} {@code zach} of the {@code SAMPLE-AES} setup information: HE-AAC.
     */
    private static final int ZACH = 0x7A616368;

    /**
     * The {@code audio_type} {@code zacp} of the {@code SAMPLE-AES} setup information: HE-AAC v2.
     */
    private static final int ZACP = 0x7A616370;

    /**
     * The shared header bits of the configured stream, or {@code -1}.
     */
    private int reference = -1;

    /**
     * The channel configuration of the configured stream.
     */
    private int channels;

    /**
     * The sampling rate of the current stream configuration.
     */
    private int rate;

    /**
     * The shared header bits of the stream configuration the track describes, or {@code -1}
     * before the track is configured.
     */
    private int baseReference = -1;

    /**
     * The channel configuration of the stream configuration the track describes.
     */
    private int baseChannels;

    /**
     * Constructs a parser.
     *
     * @param entry the program map entry of the stream
     * @param limits the resource bounds
     */
    AdtsParser(Psi.StreamEntry entry, MediaLimits limits) {
        super(entry, limits);
    }

    /**
     * Constructs a parser of a stream that may be encrypted.
     *
     * @param entry the program map entry of the stream
     * @param limits the resource bounds
     * @param sampleAes the decryptor of a {@code SAMPLE-AES} stream, or {@code null} for a clear one
     */
    AdtsParser(Psi.StreamEntry entry, MediaLimits limits, SampleAesDecryptor sampleAes) {
        super(entry, limits, sampleAes);
    }

    @Override
    ElementaryParser fresh() {
        var parser = inheritTiming(new AdtsParser(entry, limits, sampleAes));
        parser.reference = baseReference;
        parser.channels = baseChannels;
        parser.baseReference = baseReference;
        parser.baseChannels = baseChannels;
        parser.rate = timestampRate;
        return parser;
    }

    @Override
    boolean isSyncByte(byte value) {
        return value == (byte) 0xFF;
    }

    @Override
    int frameLength(byte[] data, int offset, int available) {
        if (available < 2) {
            return 0;
        }
        if ((data[offset + 1] & 0xF6) != 0xF0) {
            return -1;
        }
        if (available < AdtsHeader.SIZE) {
            return 0;
        }
        var header = AdtsHeader.parse(data, offset);
        if (header == null) {
            return -1;
        }
        var shared = ((data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF)) & STREAM_MASK;
        if (reference >= 0 && (shared != reference || header.channelConfiguration() != channels)) {
            return repeated(data, offset, available, header, shared);
        }
        return header.frameLength();
    }

    /**
     * Measures a frame whose configuration differs from the stream's, which starts a new
     * configuration only when the next frame repeats it.
     *
     * @param data the bytes
     * @param offset the index of the frame
     * @param available the number of bytes available from {@code offset}
     * @param header the frame's header
     * @param shared the stream bits of the frame's header
     * @return the frame length, {@code 0} when more bytes are needed to tell, or {@code -1} when
     * the next frame does not repeat the configuration
     */
    private int repeated(byte[] data, int offset, int available, AdtsHeader header, int shared) {
        var length = header.frameLength();
        if (available < length + AdtsHeader.SIZE) {
            return flushing() && available >= length ? -1 : 0;
        }
        var next = offset + length;
        if ((data[next] & 0xFF) != 0xFF || (data[next + 1] & 0xF6) != 0xF0) {
            return -1;
        }
        var following = AdtsHeader.parse(data, next);
        var followingShared = ((data[next + 1] & 0xFF) << 8 | (data[next + 2] & 0xFF)) & STREAM_MASK;
        return following != null && followingShared == shared
                && following.channelConfiguration() == header.channelConfiguration() ? length : -1;
    }

    @Override
    void decrypt(byte[] data, int offset, int length, long fileOffset, boolean corrupt) {
        if (length >= 7) {
            // protection_absent, the last bit of the second header byte: a CRC makes the header 9 bytes
            sampleAes.adts(data, offset, length, (data[offset + 1] & 0x01) == 0 ? 9 : 7, fileOffset);
        }
    }

    @Override
    void frame(byte[] data, int offset, int length, boolean first, FrameOutput output) {
        var header = AdtsHeader.parse(data, offset);
        var payload = Arrays.copyOfRange(data, offset + header.headerSize(), offset + length);
        var shared = ((data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF)) & STREAM_MASK;
        if (track == null) {
            configure(header, data, offset, payload);
        } else if (shared != reference || header.channelConfiguration() != channels) {
            reconfigure(header, shared, payload);
        }
        var samples = toTimestampRate(1024 * header.blocks(), rate);
        output.emit(payload, samples, samples);
    }

    /**
     * Switches to the stream configuration of a frame and announces its
     * {@code AudioSpecificConfig}.
     *
     * @param header the frame's header
     * @param shared the stream bits of the frame's header
     * @param payload the frame's raw data block
     */
    private void reconfigure(AdtsHeader header, int shared, byte[] payload) {
        reference = shared;
        channels = header.channelConfiguration();
        rate = header.sampleRate();
        var count = channels == 7 ? 8 : channels;
        var described = ((AudioTrack) track).toBuilder()
                .codecConfig(header.audioSpecificConfig())
                .sampleRate(rate)
                .layout(count > 0 ? ChannelLayout.defaultFor(count) : null)
                .build();
        if (header.profile() == 1 && rate <= AacExtensions.MAX_IMPLICIT_CORE_RATE) {
            described = AacExtensions.refine(described, payload);
        }
        announce(described.codecConfig().orElseThrow());
    }

    /**
     * Describes the stream from its first frame.
     *
     * @param header the first header
     * @param data the bytes
     * @param offset the index of the first header in {@code data}
     * @param payload the first raw data block
     */
    private void configure(AdtsHeader header, byte[] data, int offset, byte[] payload) {
        var rate = header.sampleRate();
        var channelConfiguration = header.channelConfiguration();
        var count = channelConfiguration == 7 ? 8 : channelConfiguration;
        var setup = entry.sampleAes();
        var config = setupConfig(header);
        var described = AudioTrack.builder()
                .id(trackId())
                .codec(Codec.AAC)
                .timeBase(Rational.timeBaseOf(rate))
                .codecConfig(config != null ? config : header.audioSpecificConfig())
                .language(entry.language())
                .sampleRate(rate)
                .layout(count > 0 ? ChannelLayout.defaultFor(count) : null)
                .skipSamples(setup != null ? setup.priming() : 0)
                .build();
        if (config != null) {
            described = AacExtensions.refine(described, payload);
        } else if (header.profile() == 1 && rate <= AacExtensions.MAX_IMPLICIT_CORE_RATE) {
            described = AacExtensions.refine(described, payload);
        }
        this.track = described;
        this.timestampRate = rate;
        this.rate = rate;
        this.reference = ((data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF)) & STREAM_MASK;
        this.channels = channelConfiguration;
        this.baseReference = reference;
        this.baseChannels = channelConfiguration;
    }

    /**
     * Returns the {@code AudioSpecificConfig} of the {@code SAMPLE-AES} audio setup information when it declares
     * HE-AAC over the core the headers describe.
     *
     * @param header the first header
     * @return the configuration, or {@code null} when the stream has none to use
     */
    private byte[] setupConfig(AdtsHeader header) {
        var setup = entry.sampleAes();
        if (setup == null || setup.setup() == null || setup.setup().length < 2
                || setup.audioType() != ZACH && setup.audioType() != ZACP) {
            return null;
        }
        var config = setup.setup();
        var objectType = (config[0] & 0xFF) >>> 3;
        var index = (config[0] & 0x07) << 1 | (config[1] & 0xFF) >>> 7;
        var channelConfiguration = (config[1] >>> 3) & 0x0F;
        return (objectType == 5 || objectType == 29) && index == header.frequencyIndex()
                && channelConfiguration == header.channelConfiguration() ? config.clone() : null;
    }
}
