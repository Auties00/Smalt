package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.raw.AnnexBDemuxer;
import com.github.auties00.smalt.internal.codec.MpegVideoHeaders;
import com.github.auties00.smalt.internal.header.AdtsHeader;
import com.github.auties00.smalt.internal.header.MpegAudioHeader;
import com.github.auties00.smalt.track.Codec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Recognizes the coding of a video ({@code stream_id 0xE0} to {@code 0xEF}) or audio ({@code 0xC0} to {@code 0xDF})
 * stream of a program stream that no program stream map describes, and hands the stream to the parser of that coding.
 *
 * <p>A video stream is MPEG-1 or MPEG-2 video at its first valid sequence header, MPEG-4 Part 2 at its first video
 * object plane, and H.264 or HEVC when its bytes are NAL units with a sequence parameter set that parses. An audio
 * stream is AAC at an ADTS header, AAC in LATM at a LOAS sync word and MPEG audio at a valid MPEG audio header,
 * whichever starts first in its first payload that holds one. The payloads
 * are held until the coding is known and then given to the chosen parser in order; a stream not recognized within
 * {@value #MAX_HELD} PES packets becomes a track of {@link Codec#UNKNOWN_VIDEO} or {@link Codec#UNKNOWN_AUDIO} whose
 * packets are its PES payloads. The recognizing parser exists only during the probe: {@link #fresh()} returns a
 * fresh parser of the recognized coding. Instances are not thread-safe.
 */
final class PsSniffer extends ElementaryParser {
    /**
     * The largest number of PES packets held while the coding is unknown.
     */
    static final int MAX_HELD = 64;

    /**
     * Whether the stream is a video stream rather than an audio stream.
     */
    private final boolean video;

    /**
     * The parser the stream was handed to, or {@code null} while the coding is unknown.
     */
    private ElementaryParser delegate;

    /**
     * The PES packets held while the coding is unknown, each with a copy of its payload.
     */
    private final List<Pes> heldPes = new ArrayList<>();

    /**
     * Constructs a sniffer.
     *
     * @param entry the stream entry
     * @param limits the resource bounds
     * @param video {@code true} for a video stream, {@code false} for an audio stream
     */
    PsSniffer(Psi.StreamEntry entry, MediaLimits limits, boolean video) {
        super(entry, limits);
        this.video = video;
    }

    @Override
    void accept(Pes pes, TsClock clock, UnitSink sink) {
        if (delegate == null) {
            delegate = recognize(pes.data(), pes.offset(), pes.offset() + pes.length());
            if (delegate == null) {
                var copy = Arrays.copyOfRange(pes.data(), pes.offset(), pes.offset() + pes.length());
                heldPes.add(new Pes(copy, 0, copy.length, pes.pts(), pes.dts(), pes.fileOffset(), pes.corrupt(),
                        pes.randomAccess()));
                if (heldPes.size() < MAX_HELD) {
                    return;
                }
                delegate = new PesParser(entry, limits, video ? Codec.UNKNOWN_VIDEO : Codec.UNKNOWN_AUDIO, null);
            }
            UnitSink forward = (packet, offset) -> {
                track = delegate.track();
                sink.accept(packet, offset);
            };
            for (var held : heldPes) {
                delegate.accept(held, clock, forward);
            }
            heldPes.clear();
            delegate.accept(pes, clock, forward);
            track = delegate.track();
            return;
        }
        delegate.accept(pes, clock, (packet, offset) -> {
            track = delegate.track();
            sink.accept(packet, offset);
        });
        track = delegate.track();
    }

    @Override
    void flush(TsClock clock, UnitSink sink) {
        if (delegate == null && !heldPes.isEmpty()) {
            delegate = new PesParser(entry, limits, video ? Codec.UNKNOWN_VIDEO : Codec.UNKNOWN_AUDIO, null);
            for (var held : heldPes) {
                delegate.accept(held, clock, sink);
            }
            heldPes.clear();
        }
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
        return delegate != null ? delegate.fresh()
                : new PesParser(entry, limits, video ? Codec.UNKNOWN_VIDEO : Codec.UNKNOWN_AUDIO, null);
    }

    @Override
    void keepLeadingUnits() {
        if (delegate != null) {
            delegate.keepLeadingUnits();
        }
    }

    /**
     * Chooses the parser of the stream from the payloads held so far and one more.
     *
     * @param data the bytes
     * @param offset the index of the payload
     * @param end the index after it
     * @return the parser, or {@code null} when the payloads do not tell
     */
    private ElementaryParser recognize(byte[] data, int offset, int end) {
        if (!video) {
            return recognizeAudio(data, offset, end);
        }
        if (heldPes.isEmpty()) {
            return recognizeVideo(entry, limits, data, offset, end);
        }
        var total = end - offset;
        for (var held : heldPes) {
            total += held.length();
        }
        var joined = new byte[total];
        var position = 0;
        for (var held : heldPes) {
            System.arraycopy(held.data(), held.offset(), joined, position, held.length());
            position += held.length();
        }
        System.arraycopy(data, offset, joined, position, end - offset);
        return recognizeVideo(entry, limits, joined, 0, total);
    }

    /**
     * Chooses the parser of a video stream from its leading payload bytes.
     *
     * <p>A valid MPEG-1 or MPEG-2 sequence header or a video object plane start code ({@code 00 00 01 B6}, which
     * neither MPEG-2 video nor a NAL unit header can take) decides at once; otherwise the bytes from the first start
     * code are an H.264 or HEVC stream when they hold valid NAL unit headers only, among them a sequence parameter set
     * that parses.
     *
     * @param entry the stream entry
     * @param limits the resource bounds
     * @param data the bytes
     * @param offset the index of the first byte
     * @param end the index after the last byte
     * @return the parser, or {@code null} when the bytes do not tell
     */
    static ElementaryParser recognizeVideo(Psi.StreamEntry entry, MediaLimits limits, byte[] data, int offset,
                                           int end) {
        var first = -1;
        for (var index = offset; index + 3 < end; index++) {
            if (data[index] != 0 || data[index + 1] != 0 || data[index + 2] != 1) {
                continue;
            }
            if (first < 0) {
                first = index;
            }
            var code = data[index + 3] & 0xFF;
            if (code == MpegVideoHeaders.SEQUENCE_HEADER && MpegVideoHeaders.parse(data, index, end) != null) {
                return new AccessUnitFramer(new MpegVideoParser(entry, limits, Codec.MPEG2_VIDEO), false);
            }
            if (code == 0xB6) {
                return new AccessUnitFramer(new Mpeg4VideoParser(entry, limits), true);
            }
        }
        if (first < 0) {
            return null;
        }
        var format = AnnexBDemuxer.detect(Arrays.copyOfRange(data, first, end)).orElse(null);
        if (format == null) {
            return null;
        }
        return new NalParser(entry, limits, format == ContainerFormat.HEVC);
    }

    /**
     * Chooses the parser of an audio stream from one payload.
     *
     * @param data the bytes
     * @param offset the index of the payload
     * @param end the index after it
     * @return the parser, or {@code null} when the payload holds no frame header
     */
    private ElementaryParser recognizeAudio(byte[] data, int offset, int end) {
        for (var index = offset; index + 7 <= end; index++) {
            var b0 = data[index] & 0xFF;
            var b1 = data[index + 1] & 0xFF;
            if (b0 == 0xFF && (b1 & 0xF6) == 0xF0 && AdtsHeader.parse(data, index) != null) {
                return new AdtsParser(entry, limits);
            }
            if (b0 == 0x56 && (b1 & 0xE0) == 0xE0) {
                return new LatmParser(entry, limits);
            }
            if (b0 == 0xFF && MpegAudioHeader.isValid(MpegAudioHeader.read(data, index))) {
                return new MpegAudioParser(entry, limits);
            }
        }
        return null;
    }
}
