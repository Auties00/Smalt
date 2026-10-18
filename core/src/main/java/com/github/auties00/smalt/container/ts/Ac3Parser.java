package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.codec.Ac3Configs;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;

import java.util.Arrays;

/**
 * Splits an AC-3 or E-AC-3 stream (ATSC A/52 and its Annex E) into access units, for
 * pass-through.
 *
 * <p>Every packet is one access unit: an AC-3 sync frame or an independent E-AC-3 frame of
 * substream zero, together with the frames of the dependent and further substreams that follow it,
 * headers included, as MP4 and Matroska store them. This covers E-AC-3 streams whose core is an
 * AC-3 frame extended by an E-AC-3 dependent substream (7.1 on Blu-ray and broadcast). The track
 * declares the codec ({@link Codec#EAC3} when the first unit holds any E-AC-3 frame), the sampling
 * rate and the layout of program zero with its channel extensions, and timestamps count samples at
 * that rate (256 per audio block). The track carries no configuration: the frames describe
 * themselves.
 */
final class Ac3Parser extends FrameParser {
    /**
     * Constructs a parser.
     *
     * @param entry the program map entry of the stream
     * @param limits the resource bounds
     */
    Ac3Parser(Psi.StreamEntry entry, MediaLimits limits) {
        super(entry, limits);
    }

    @Override
    ElementaryParser fresh() {
        return inheritTiming(new Ac3Parser(entry, limits));
    }

    @Override
    boolean isSyncByte(byte value) {
        return value == 0x0B;
    }

    @Override
    int frameLength(byte[] data, int offset, int available) {
        var length = Ac3Configs.frameLength(data, offset, available);
        if (length <= 0) {
            return length;
        }
        if (!startsUnit(data, offset)) {
            return -1;
        }
        while (true) {
            if (available - length < 6) {
                return flushing() || available - length < 0 ? length : 0;
            }
            var next = offset + length;
            var size = Ac3Configs.frameLength(data, next, available - length);
            if (size <= 0 || startsUnit(data, next)) {
                return length;
            }
            length += size;
        }
    }

    /**
     * Returns whether the frame whose header starts at an index begins an access unit.
     *
     * @param data the bytes, at least six from {@code offset}
     * @param offset the index of the header
     * @return {@code true} for an AC-3 frame or an independent E-AC-3 frame of substream zero
     */
    private static boolean startsUnit(byte[] data, int offset) {
        if (((data[offset + 5] & 0xFF) >> 3) <= 10) {
            return true;
        }
        return ((data[offset + 2] & 0xFF) >> 6) != 1 && ((data[offset + 2] >> 3) & 0x07) == 0;
    }

    @Override
    void frame(byte[] data, int offset, int length, boolean first, FrameOutput output) {
        var header = Ac3Configs.parse(data, offset, length);
        if (header == null) {
            return;
        }
        if (track == null) {
            var stream = Ac3Configs.describe(data, offset, length);
            if (stream == null || stream.sampleRate() <= 0) {
                return;
            }
            timestampRate = stream.sampleRate();
            track = AudioTrack.builder()
                    .id(pid())
                    .codec(stream.codec())
                    .timeBase(Rational.timeBaseOf(stream.sampleRate()))
                    .language(entry.language())
                    .sampleRate(stream.sampleRate())
                    .layout(stream.layout())
                    .build();
        }
        var samples = 256 * header.blocks();
        output.emit(Arrays.copyOfRange(data, offset, offset + length), samples, samples);
    }
}
