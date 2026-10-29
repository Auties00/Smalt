package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.raw.AnnexBDemuxer;
import com.github.auties00.smalt.internal.codec.MpegVideoHeaders;
import com.github.auties00.smalt.internal.header.AdtsHeader;
import com.github.auties00.smalt.internal.header.MpegAudioHeader;
import com.github.auties00.smalt.internal.limits.LimitChecks;
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
 * whichever starts first in its first payload that holds one; a header is taken when the header of the frame after
 * it lies in the payload too, or, when that frame starts past the payload, when it opens the payload. The payloads
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
     * The number of bytes before the end of the joined payloads searched again when a payload is added, which covers
     * a sequence header (with its quantizer matrices) cut by the end of the previous payload.
     */
    private static final int RESCAN_BYTES = 256;

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
     * The payloads of a video stream joined, while the coding is unknown.
     */
    private byte[] joined = new byte[0];

    /**
     * The number of bytes in {@link #joined}.
     */
    private int joinedLength;

    /**
     * The index of the first start code in {@link #joined}, or {@code -1}.
     */
    private int firstStart = -1;

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
            joined = new byte[0];
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
            joined = new byte[0];
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
        var from = Math.max(0, joinedLength - RESCAN_BYTES);
        var length = end - offset;
        if (joined.length < joinedLength + length) {
            var size = Math.max(joinedLength + length, 2 * joined.length);
            LimitChecks.checkAllocation(limits, size);
            joined = Arrays.copyOf(joined, size);
        }
        System.arraycopy(data, offset, joined, joinedLength, length);
        joinedLength += length;
        var payloads = heldPes.size() + 1;
        return recognizeVideo(from, (payloads & (payloads - 1)) == 0 || payloads == MAX_HELD);
    }

    /**
     * Chooses the parser of a video stream from its payloads joined so far.
     *
     * <p>A valid MPEG-1 or MPEG-2 sequence header or a video object plane start code ({@code 00 00 01 B6}, which
     * neither MPEG-2 video nor a NAL unit header can take) decides at once; otherwise the bytes from the first start
     * code are an H.264 or HEVC stream when they hold valid NAL unit headers only, among them a sequence parameter set
     * that parses.
     *
     * @param from the index from which the joined payloads are searched for a sequence header or a video object plane
     * @param nalUnits whether the joined payloads are also checked for an H.264 or HEVC stream, which is done after
     * one, two, four and so on payloads and after the last one held, so that the work stays linear in the bytes held
     * @return the parser, or {@code null} when the bytes do not tell
     */
    private ElementaryParser recognizeVideo(int from, boolean nalUnits) {
        for (var index = from; index + 3 < joinedLength; index++) {
            if (joined[index] != 0 || joined[index + 1] != 0 || joined[index + 2] != 1) {
                continue;
            }
            if (firstStart < 0) {
                firstStart = index;
            }
            var code = joined[index + 3] & 0xFF;
            if (code == MpegVideoHeaders.SEQUENCE_HEADER && MpegVideoHeaders.parse(joined, index, joinedLength) != null) {
                return new AccessUnitFramer(new MpegVideoParser(entry, limits, Codec.MPEG2_VIDEO), false);
            }
            if (code == 0xB6) {
                return new AccessUnitFramer(new Mpeg4VideoParser(entry, limits), true);
            }
        }
        if (!nalUnits || firstStart < 0) {
            return null;
        }
        var format = AnnexBDemuxer.detect(Arrays.copyOfRange(joined, firstStart, joinedLength)).orElse(null);
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
            if (b0 == 0xFF && (b1 & 0xF6) == 0xF0) {
                var adts = AdtsHeader.parse(data, index);
                if (adts != null) {
                    var next = index + adts.frameLength();
                    if (next + AdtsHeader.SIZE <= end ? AdtsHeader.parse(data, next) != null : index == offset) {
                        return new AdtsParser(entry, limits);
                    }
                }
            }
            if (b0 == 0x56 && (b1 & 0xE0) == 0xE0) {
                var next = index + 3 + ((b1 & 0x1F) << 8 | data[index + 2] & 0xFF);
                if (next + 2 <= end ? data[next] == 0x56 && (data[next + 1] & 0xE0) == 0xE0 : index == offset) {
                    return new LatmParser(entry, limits);
                }
            }
            if (b0 == 0xFF) {
                var header = MpegAudioHeader.read(data, index);
                if (MpegAudioHeader.isValid(header)) {
                    var size = MpegAudioHeader.frameBytes(header, 0);
                    var next = size > 0 ? index + size + MpegAudioHeader.padding(header) : end;
                    if (next + 4 <= end ? MpegAudioHeader.isCompatible(header, MpegAudioHeader.read(data, next))
                            : index == offset) {
                        return new MpegAudioParser(entry, limits);
                    }
                }
            }
        }
        return null;
    }
}
