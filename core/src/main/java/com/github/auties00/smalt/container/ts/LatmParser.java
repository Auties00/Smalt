package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.internal.AacExtensions;
import com.github.auties00.smalt.container.internal.Latm;
import com.github.auties00.smalt.container.internal.Latm.Config;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;

import java.util.Arrays;

/**
 * Splits an AAC stream in LOAS/LATM framing ({@code stream_type 0x11}, ISO/IEC 14496-3 clause
 * 1.7) into raw access units.
 *
 * <p>Each {@code AudioSyncStream} frame holds one {@code AudioMuxElement} with in-band
 * configuration, in the layouts {@link Latm} reads. The {@code AudioSpecificConfig} of the first
 * stream of the {@code StreamMuxConfig} (program 0, layer 0) becomes the track configuration, a
 * program config element included, in which case the track declares no layout, as for ADTS; a
 * stream of a layout {@link Latm} does not support is not exposed. Every payload of the first stream
 * becomes a packet of 1024 samples of the core rate. Elements
 * that reuse the configuration before one was seen are skipped. An in-band
 * {@code AudioSpecificConfig} that differs from the previous one is announced on the next packet,
 * refined for HE-AAC as the track's is, as its new codec configuration; durations stay in samples
 * of the track's rate.
 */
final class LatmParser extends FrameParser {
    /**
     * The configuration last seen in band, or {@code null}.
     */
    private Config config;

    /**
     * The in-band {@code AudioSpecificConfig} the track was configured from, shared with the
     * fresh parsers, or {@code null} before the track is configured.
     */
    private byte[] baseConfig;

    /**
     * Whether the track was just configured and its first payload must still be decoded to
     * detect HE-AAC.
     */
    private boolean refinePending;

    /**
     * The in-band {@code AudioSpecificConfig} the track's configuration or the last announced
     * one comes from, or {@code null} before the first.
     */
    private byte[] reportedConfig;

    /**
     * A configuration met in band that differs from {@link #reportedConfig}, announced with the
     * next payload, or {@code null}.
     */
    private Config changePending;

    /**
     * The core sampling rate of the current configuration.
     */
    private int rate;

    /**
     * Constructs a parser.
     *
     * @param entry the program map entry of the stream
     * @param limits the resource bounds
     */
    LatmParser(Psi.StreamEntry entry, MediaLimits limits) {
        super(entry, limits);
    }

    @Override
    ElementaryParser fresh() {
        var parser = inheritTiming(new LatmParser(entry, limits));
        parser.baseConfig = baseConfig;
        parser.reportedConfig = baseConfig;
        parser.rate = timestampRate;
        return parser;
    }

    @Override
    boolean isSyncByte(byte value) {
        return value == 0x56;
    }

    @Override
    int frameLength(byte[] data, int offset, int available) {
        return Latm.frameLength(data, offset, available);
    }

    @Override
    void frame(byte[] data, int offset, int length, boolean first, FrameOutput output) {
        var bits = new MsbBitReader(data, offset + 3, length - 3);
        try {
            if (!bits.readFlag()) {
                var parsed = Latm.readStreamMuxConfig(bits);
                if (parsed == null) {
                    return;
                }
                if (track == null) {
                    configure(parsed);
                } else if (reportedConfig != null && !Arrays.equals(parsed.audioSpecificConfig(), reportedConfig)) {
                    changePending = parsed;
                }
                config = parsed;
            } else if (config == null) {
                return;
            }
            var payloads = Latm.readPayloads(bits, config, length);
            if (payloads == null) {
                return;
            }
            for (var payload : payloads) {
                if (refinePending) {
                    refinePending = false;
                    track = AacExtensions.refine((AudioTrack) track, payload);
                }
                if (changePending != null) {
                    announceChange(changePending, payload);
                    changePending = null;
                }
                var samples = toTimestampRate(1024, rate);
                output.emit(payload, samples, samples);
            }
        } catch (RuntimeException exception) {
            // a damaged element loses its payloads; the stream continues at the next sync word
        }
    }

    /**
     * Describes the stream from its first configuration.
     *
     * <p>Whether the stream is HE-AAC is decided on the first payload, which
     * {@link AacExtensions#refine(AudioTrack, byte[])} decodes.
     *
     * @param parsed the configuration
     */
    private void configure(Config parsed) {
        LimitChecks.checkChannels(limits, parsed.declaredChannels());
        var described = AudioTrack.builder()
                .id(trackId())
                .codec(Codec.AAC)
                .timeBase(Rational.timeBaseOf(parsed.coreRate()))
                .codecConfig(parsed.audioSpecificConfig())
                .language(entry.language())
                .sampleRate(parsed.coreRate())
                .layout(layoutOf(parsed))
                .build();
        track = described;
        timestampRate = parsed.coreRate();
        rate = parsed.coreRate();
        refinePending = true;
        baseConfig = parsed.audioSpecificConfig();
        reportedConfig = baseConfig;
    }

    /**
     * Returns the layout a configuration declares.
     *
     * @param parsed the configuration
     * @return the layout of its channel configuration, or {@code null} when a program config element declares
     * the channels, as for ADTS
     */
    private static ChannelLayout layoutOf(Config parsed) {
        var count = parsed.channels() == 7 ? 8 : parsed.channels();
        return count > 0 ? ChannelLayout.defaultFor(count) : null;
    }

    /**
     * Announces a configuration that differs from the reported one.
     *
     * @param parsed the new configuration
     * @param payload the first payload coded with it, which tells HE-AAC apart
     */
    private void announceChange(Config parsed, byte[] payload) {
        var described = ((AudioTrack) track).toBuilder()
                .codecConfig(parsed.audioSpecificConfig())
                .sampleRate(parsed.coreRate())
                .layout(layoutOf(parsed))
                .build();
        described = AacExtensions.refine(described, payload);
        reportedConfig = parsed.audioSpecificConfig();
        rate = parsed.coreRate();
        announce(described.codecConfig().orElseThrow());
    }
}
