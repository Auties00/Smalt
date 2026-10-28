package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.internal.codec.DtsConfigs;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;

import java.util.Arrays;

/**
 * Splits a DTS Coherent Acoustics stream (ETSI TS 102 114) in the 16-bit big-endian form into frames, for
 * pass-through: the DTS audio substreams of DVD-Video ({@code sub_stream_id 0x88} to {@code 0x8F} of private
 * stream 1).
 *
 * <p>Every packet is one core frame together with the DTS-HD extension substreams (sync word {@code 0x64582025})
 * that follow it, as {@link Codec#DTS} defines a packet. The track declares the sampling rate and channel layout of
 * the first core frame, and timestamps count samples at that rate ({@code (NBLKS + 1) * 32} per frame). The track
 * carries no configuration. Instances are not thread-safe.
 */
final class DtsParser extends FrameParser {
    /**
     * Constructs a parser.
     *
     * @param entry the stream entry
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
        return value == 0x7F;
    }

    @Override
    int frameLength(byte[] data, int offset, int available) {
        if (available < DtsConfigs.HEADER_SIZE) {
            return 0;
        }
        if (!DtsConfigs.isSync(data, offset)) {
            return -1;
        }
        var length = DtsConfigs.frameSize(data, offset);
        if (length < DtsConfigs.MIN_FRAME_SIZE || DtsConfigs.sampleRate(data, offset) == 0) {
            return -1;
        }
        while (true) {
            if (available - length < DtsConfigs.EXTENSION_HEADER_SIZE) {
                return flushing() || available < length ? length : 0;
            }
            if (!DtsConfigs.isExtensionSync(data, offset + length)) {
                return length;
            }
            length += DtsConfigs.extensionSize(data, offset + length);
        }
    }

    @Override
    void frame(byte[] data, int offset, int length, boolean first, FrameOutput output) {
        var rate = DtsConfigs.sampleRate(data, offset);
        if (track == null) {
            var layout = DtsConfigs.layout(data, offset);
            timestampRate = rate;
            track = AudioTrack.builder()
                    .id(trackId())
                    .codec(Codec.DTS)
                    .timeBase(Rational.timeBaseOf(rate))
                    .language(entry.language())
                    .sampleRate(rate)
                    .layout(layout != null ? layout : ChannelLayout.STEREO)
                    .build();
        }
        var samples = toTimestampRate(DtsConfigs.samples(data, offset), rate);
        output.emit(Arrays.copyOfRange(data, offset, offset + length), samples, samples);
    }
}
