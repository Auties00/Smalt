package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.internal.header.MpegAudioHeader;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;

import java.util.Arrays;

/**
 * Splits an MPEG-1 or MPEG-2 audio stream ({@code stream_type 0x03} or {@code 0x04}, ISO/IEC
 * 11172-3 and 13818-3) into frames.
 *
 * <p>Layer III streams become {@link Codec#MP3} tracks and Layer I and II streams
 * {@link Codec#MP2} tracks; every packet is one whole frame, header included, as the MPEG audio
 * demuxer produces it. Frames of another version, layer or sampling rate than the first one are
 * skipped as garbage, and free-format streams, whose frame size no header states, are not
 * supported (no frame is found).
 */
final class MpegAudioParser extends FrameParser {
    /**
     * The header of the first frame, or {@code 0} before the parser is configured.
     */
    private int reference;

    /**
     * Constructs a parser.
     *
     * @param entry the program map entry of the stream
     * @param limits the resource bounds
     */
    MpegAudioParser(Psi.StreamEntry entry, MediaLimits limits) {
        super(entry, limits);
    }

    @Override
    ElementaryParser fresh() {
        var parser = inheritTiming(new MpegAudioParser(entry, limits));
        parser.reference = reference;
        return parser;
    }

    @Override
    boolean isSyncByte(byte value) {
        return value == (byte) 0xFF;
    }

    @Override
    int frameLength(byte[] data, int offset, int available) {
        if (available < MpegAudioHeader.SIZE) {
            return available >= 2 && (data[offset + 1] & 0xE0) != 0xE0 ? -1 : 0;
        }
        var header = MpegAudioHeader.read(data, offset);
        if (!MpegAudioHeader.isValid(header) || MpegAudioHeader.isFreeFormat(header)
                || (reference != 0 && !MpegAudioHeader.isCompatible(reference, header))) {
            return -1;
        }
        return MpegAudioHeader.frameBytes(header, 0) + MpegAudioHeader.padding(header);
    }

    @Override
    void frame(byte[] data, int offset, int length, boolean first, FrameOutput output) {
        var header = MpegAudioHeader.read(data, offset);
        if (track == null) {
            var rate = MpegAudioHeader.sampleRate(header);
            track = AudioTrack.builder()
                    .id(trackId())
                    .codec(MpegAudioHeader.layer(header) == 3 ? Codec.MP3 : Codec.MP2)
                    .timeBase(Rational.timeBaseOf(rate))
                    .language(entry.language())
                    .sampleRate(rate)
                    .layout(ChannelLayout.defaultFor(MpegAudioHeader.channels(header)))
                    .build();
            timestampRate = rate;
            reference = header;
        }
        var samples = MpegAudioHeader.samplesPerFrame(header);
        output.emit(Arrays.copyOfRange(data, offset, offset + length), samples, samples);
    }
}
