package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.internal.codec.DtsConfigs;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;

import java.util.Arrays;

/**
 * Splits a DTS stream (ETSI TS 102 114 Annex F) into access units, for pass-through and decoding.
 *
 * <p>Every packet is one access unit: a core frame together with the DTS-HD extension substream frames that follow
 * it, or, in a stream without core (DTS-HD Master Audio without core, DTS Express), one extension substream frame.
 * The track declares {@link Codec#DTS}, the sample rate and layout of the richest presentation it can read from the
 * first unit (the extension substream's asset when there is one), and timestamps count core samples, or reference
 * clock periods of the extension substream in a stream without core. The track carries no configuration: the
 * frames describe themselves.
 */
final class DtsParser extends FrameParser {
    /**
     * Constructs a parser.
     *
     * @param entry the program map entry of the stream
     * @param limits the resource bounds
     */
    DtsParser(Psi.StreamEntry entry, MediaLimits limits) {
        super(entry, limits);
    }

    @Override
    ElementaryParser fresh() {
        return inheritTiming(new DtsParser(entry, limits));
    }

    @Override
    boolean isSyncByte(byte value) {
        return value == 0x7F || value == 0x64;
    }

    @Override
    int frameLength(byte[] data, int offset, int available) {
        if (available < DtsConfigs.HEADER_SIZE + 1) {
            return 0;
        }
        if (DtsConfigs.isSubstreamSync(data, offset)) {
            return DtsConfigs.substreamSize(data, offset, available);
        }
        if (!DtsConfigs.isSync(data, offset)) {
            return -1;
        }
        var length = DtsConfigs.frameSize(data, offset);
        if (length < DtsConfigs.MIN_FRAME_SIZE || DtsConfigs.sampleRate(data, offset) == 0) {
            return -1;
        }
        while (true) {
            if (available - length < 12) {
                return flushing() || available - length < 0 ? length : 0;
            }
            var next = offset + length;
            if (!DtsConfigs.isSubstreamSync(data, next)) {
                return length;
            }
            var size = DtsConfigs.substreamSize(data, next, available - length);
            if (size == 0) {
                return flushing() ? length : 0;
            }
            if (size < 0) {
                return length;
            }
            length += size;
        }
    }

    @Override
    void frame(byte[] data, int offset, int length, boolean first, FrameOutput output) {
        int samples;
        if (DtsConfigs.isSync(data, offset)) {
            samples = DtsConfigs.samples(data, offset);
            if (track == null) {
                var rate = DtsConfigs.sampleRate(data, offset);
                var layout = DtsConfigs.layout(data, offset);
                var core = DtsConfigs.frameSize(data, offset);
                var outputRate = rate;
                if (core + 4 <= length && DtsConfigs.isSubstreamSync(data, offset + core)) {
                    var substream = DtsConfigs.describeSubstream(data, offset + core, length - core);
                    if (substream != null) {
                        outputRate = Math.max(rate, substream.sampleRate());
                        var assetLayout = DtsConfigs.layoutOfMask(substream.speakerMask());
                        if (assetLayout != null) {
                            layout = assetLayout;
                        }
                    }
                }
                describe(rate, outputRate, layout);
            }
        } else {
            var substream = DtsConfigs.describeSubstream(data, offset, length);
            if (track == null) {
                if (substream == null) {
                    return;
                }
                describe(substream.clockRate(), substream.sampleRate(),
                        DtsConfigs.layoutOfMask(substream.speakerMask()));
            }
            samples = substream != null ? substream.frameDuration() : 512;
        }
        output.emit(Arrays.copyOfRange(data, offset, offset + length), samples, samples);
    }

    /**
     * Creates the track.
     *
     * @param clockRate the rate the timestamps count
     * @param sampleRate the output sample rate
     * @param layout the output layout, or {@code null} if unknown
     */
    private void describe(int clockRate, int sampleRate, ChannelLayout layout) {
        timestampRate = clockRate;
        var builder = AudioTrack.builder()
                .id(trackId())
                .codec(Codec.DTS)
                .timeBase(Rational.timeBaseOf(clockRate))
                .language(entry.language())
                .sampleRate(sampleRate);
        if (layout != null) {
            builder.layout(layout);
        }
        track = builder.build();
    }
}
