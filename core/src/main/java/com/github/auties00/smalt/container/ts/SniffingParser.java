package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.header.MpegAudioHeader;
import com.github.auties00.smalt.track.Codec;

/**
 * Recognizes the coding of a private PES stream ({@code stream_type 0x06}) that no descriptor
 * describes, from its first payload, and hands the stream to the parser of that coding.
 *
 * <p>Some writers (M2TS writers among them) declare AAC or AC-3 audio and MPEG-4 Part 2 video as
 * private data without a descriptor. The first PES payload that starts with an ADTS header makes the
 * stream AAC, a LOAS sync word AAC in LATM, an AC-3 sync word AC-3 or E-AC-3, a valid MPEG audio
 * header MPEG audio, and an MPEG-4 Part 2 start code (visual object sequence, visual object, video
 * object layer, group of video object planes or video object plane) in a payload that holds a video
 * object plane MPEG-4 Part 2 video; any other payload (H.264, HEVC and MPEG-2 video among them) makes it a data
 * track with the handler {@code "private"}. The
 * recognizing parser itself only exists during the probe: {@link #fresh()} returns a fresh
 * parser of the recognized coding.
 */
final class SniffingParser extends ElementaryParser {
    /**
     * The parser the stream was handed to, or {@code null} before the first payload.
     */
    private ElementaryParser delegate;

    /**
     * Constructs a parser.
     *
     * @param entry the program map entry of the stream
     * @param limits the resource bounds
     */
    SniffingParser(Psi.StreamEntry entry, MediaLimits limits) {
        super(entry, limits);
    }

    @Override
    void accept(Pes pes, TsClock clock, UnitSink sink) {
        if (delegate == null) {
            if (pes.length() < 4) {
                return;
            }
            delegate = recognize(pes.data(), pes.offset(), pes.offset() + pes.length());
        }
        delegate.accept(pes, clock, (packet, offset) -> {
            track = delegate.track();
            sink.accept(packet, offset);
        });
        track = delegate.track();
    }

    @Override
    void flush(TsClock clock, UnitSink sink) {
        if (delegate != null) {
            delegate.flush(clock, (packet, offset) -> {
                track = delegate.track();
                sink.accept(packet, offset);
            });
            track = delegate.track();
        }
    }

    @Override
    ElementaryParser fresh() {
        return delegate != null ? delegate.fresh() : new PesParser(entry, limits, Codec.DATA, "private");
    }

    /**
     * Chooses the parser of a stream from the start of its first payload.
     *
     * @param data the bytes
     * @param offset the index of the payload
     * @param end the index after the payload
     * @return the parser
     */
    private ElementaryParser recognize(byte[] data, int offset, int end) {
        var b0 = data[offset] & 0xFF;
        var b1 = data[offset + 1] & 0xFF;
        if (b0 == 0 && b1 == 0 && data[offset + 2] == 1) {
            var code = data[offset + 3] & 0xFF;
            if ((code == 0xB0 || code == 0xB3 || code == 0xB5 || code == 0xB6 || code <= 0x2F)
                    && hasVideoObjectPlane(data, offset, end)) {
                return new Mpeg4VideoParser(entry, limits);
            }
        }
        if (b0 == 0xFF && (b1 & 0xF6) == 0xF0) {
            return new AdtsParser(entry, limits);
        }
        if (b0 == 0x56 && (b1 & 0xE0) == 0xE0) {
            return new LatmParser(entry, limits);
        }
        if (b0 == 0x0B && b1 == 0x77) {
            return new Ac3Parser(entry, limits);
        }
        if (b0 == 0xFF && MpegAudioHeader.isValid(MpegAudioHeader.read(data, offset))) {
            return new MpegAudioParser(entry, limits);
        }
        return new PesParser(entry, limits, Codec.DATA, "private");
    }

    /**
     * Returns whether a payload holds a video object plane start code ({@code 00 00 01 B6}), which tells MPEG-4
     * Part 2 apart from H.264, HEVC and MPEG-2 video, whose start codes share the values of its headers but never
     * take that one.
     *
     * @param data the bytes
     * @param offset the index of the payload
     * @param end the index after the payload
     * @return {@code true} when the payload holds one
     */
    private static boolean hasVideoObjectPlane(byte[] data, int offset, int end) {
        for (var index = offset; index + 3 < end; index++) {
            if (data[index] == 0 && data[index + 1] == 0 && data[index + 2] == 1 && (data[index + 3] & 0xFF) == 0xB6) {
                return true;
            }
        }
        return false;
    }
}
