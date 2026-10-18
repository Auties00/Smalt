package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.header.MpegAudioHeader;
import com.github.auties00.smalt.track.Codec;

/**
 * Recognizes the coding of a private PES stream ({@code stream_type 0x06}) that no descriptor
 * describes, from its first payload, and hands the stream to the parser of that coding.
 *
 * <p>Some writers (M2TS writers among them) declare AAC or AC-3 audio as private data without a
 * descriptor. The first PES payload that starts with an ADTS header makes the stream AAC, a LOAS
 * sync word AAC in LATM, an AC-3 sync word AC-3 or E-AC-3, and a valid MPEG audio header MPEG
 * audio; any other payload makes it a data track with the handler {@code "private"}. The
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
            delegate = recognize(pes.data(), pes.offset());
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
     * @return the parser
     */
    private ElementaryParser recognize(byte[] data, int offset) {
        var b0 = data[offset] & 0xFF;
        var b1 = data[offset + 1] & 0xFF;
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
}
