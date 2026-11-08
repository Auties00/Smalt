package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.internal.FrameHeader;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;

/**
 * Chooses the decoder model times of a stream's frame headers: each decodable frame's removal from
 * the smoothing buffer ({@code buffer_removal_time}) and each shown frame's presentation
 * ({@code frame_presentation_time}).
 *
 * <p>A shown frame is presented its timestamp's distance from the last key frame after that key
 * frame's presentation, and a key frame its distance from the previous key frame after that one's,
 * so the presentation times count display ticks of the time base as the AV1 specification defines
 * them. A decodable frame is removed at its nominal time, its timestamp's distance from the first
 * frame after the first frame's removal (a hidden frame at the nominal time of the frame before
 * it), delayed to the first decoding tick at which the Annex E decoder model of the stream's level
 * holds: the minimum decode time after the frame before, the arrival of the frame's whole frame
 * group at the level's bitrate, a free frame buffer and, for a key frame, a decoder buffer delay
 * since the arrival of the frame group before. The removal is chosen once the frame is coded, from
 * its coded size, and holds both for the whole stream and for the stream that starts at the last
 * key frame, so the stream also conforms, up to the next key frame, when decoding starts there.
 * Removal times count decoding ticks from the last key frame's removal, a key frame's from the
 * previous key frame's.
 *
 * <p>An instance belongs to one encoder and is used by one thread at a time.
 */
final class FrameTiming {
    /**
     * The relative margin a removal time keeps above its bounds, which absorbs the rounding of the
     * model's floating-point times.
     */
    private static final double MARGIN = 1e-9;

    /**
     * The sequence header of the models, or {@code null} before the first frame.
     */
    private SequenceHeader seq;

    /**
     * The conformance requirements broken in the streams that start at key frames before the last.
     */
    private int retiredViolations;

    /**
     * The model of the whole stream, or {@code null} before the first frame.
     */
    private DecoderModel stream;

    /**
     * The model of the stream that starts at the last key frame, or {@code null} before the first
     * frame.
     */
    private DecoderModel segment;

    /**
     * The removal time of the last key frame in the whole stream's model.
     */
    private double segmentStart;

    /**
     * The timestamp of the first frame.
     */
    private long firstPts;

    /**
     * The timestamp of the last key frame.
     */
    private long keyPts;

    /**
     * Whether a key frame was coded.
     */
    private boolean keyed;

    /**
     * The decoding ticks from the first frame's removal to the last key frame's.
     */
    private long keyTicks;

    /**
     * The decoding ticks from the first frame's removal to the last decodable frame's.
     */
    private long lastTicks = -1;

    /**
     * The decoding ticks from the first frame's removal to the nominal removal of the last
     * decodable frame.
     */
    private long lastNominal;

    /**
     * The bytes of the shown existing frames since the last decodable frame, which belong to the
     * next frame group.
     */
    private long pendingBytes;

    /**
     * The decoding ticks of the frame {@link #schedule} chose last.
     */
    private long scheduledTicks;

    /**
     * The decoding ticks to the nominal removal of the frame {@link #schedule} chose last.
     */
    private long scheduledNominal;

    /**
     * The size in bytes of the frame {@link #schedule} chose last, from its temporal unit's start.
     */
    private long scheduledBytes = -1;

    /**
     * Creates the times of a stream.
     */
    FrameTiming() {
    }

    /**
     * Sets the presentation time of a frame header that is about to be coded and a provisional
     * removal time; does nothing unless the sequence signals the decoder model.
     *
     * @param h the frame header, with its type, shown flag and temporal layer set
     * @param seq the sequence header
     * @param pts the timestamp of the frame, in time base units
     */
    void fill(FrameHeader h, SequenceHeader seq, long pts) {
        h.bufferRemovalTimePresent = seq.decoderModelInfoPresent;
        if (!seq.decoderModelInfoPresent) {
            return;
        }
        for (var i = 0; i < seq.numOperatingPoints; i++) {
            h.bufferRemovalTime[i] = 0;
        }
        var key = h.frameType == FrameHeader.KEY && h.showFrame;
        h.framePresentationTime = key && !keyed ? 0 : wrap(pts - keyPts, seq.framePresentationDelayLength);
    }

    /**
     * Returns the presentation time of a frame a show-existing header shows.
     *
     * @param seq the sequence header
     * @param pts the timestamp of the frame, in time base units
     * @return the presentation time, or 0 unless the sequence signals the decoder model
     */
    long presentation(SequenceHeader seq, long pts) {
        return seq.decoderModelInfoPresent ? wrap(pts - keyPts, seq.framePresentationDelayLength) : 0;
    }

    /**
     * Sets the removal time of a coded frame from its size; does nothing unless the sequence
     * signals the decoder model. The time holds until the next call and is committed by
     * {@link #advance}.
     *
     * @param h the frame header, with its removal times present
     * @param seq the sequence header
     * @param pts the timestamp of the frame, in time base units
     * @param bytes the size of the frame's OBUs from the start of its temporal unit, the temporal
     * delimiter and sequence header included
     */
    void schedule(FrameHeader h, SequenceHeader seq, long pts, long bytes) {
        if (!seq.decoderModelInfoPresent) {
            return;
        }
        if (stream == null) {
            this.seq = seq;
            stream = new DecoderModel(seq);
            segment = stream;
            firstPts = pts;
        }
        var key = h.frameType == FrameHeader.KEY && h.showFrame;
        var bits = (pendingBytes + bytes) * 8;
        long ticks;
        long nominal;
        if (stream.empty()) {
            ticks = 0;
            nominal = 0;
        } else {
            nominal = h.showFrame ? nominalTicks(seq, pts - firstPts) : lastNominal;
            var delay = stream.decoderDelay();
            var earliest = stream.earliestRemoval(bits, key);
            // TODO: bound the removals by the frame buffers and presentations of the streams that
            // start at the key frames before the last too; their smoothing buffers are covered, since
            // a later key frame's decoder buffer delay makes its stream's arrivals the latest
            if (segment != stream) {
                earliest = Math.max(earliest, segmentStart - delay + segment.earliestRemoval(bits, key));
            }
            var bound = (long) Math.ceil((earliest - delay) / stream.decodingTick() * (1 + MARGIN) + MARGIN);
            ticks = Math.max(Math.max(lastTicks + 1, nominal), bound);
        }
        var relative = ticks - keyTicks;
        for (var i = 0; i < seq.numOperatingPoints; i++) {
            h.bufferRemovalTime[i] = wrap(relative, seq.bufferRemovalDelayLength);
        }
        scheduledTicks = ticks;
        scheduledNominal = nominal;
        scheduledBytes = bytes;
    }

    /**
     * Counts a coded frame with the removal time {@link #schedule} chose last.
     *
     * @param h the frame header
     * @param pts the timestamp of the frame
     */
    void advance(FrameHeader h, long pts) {
        var key = h.frameType == FrameHeader.KEY && h.showFrame;
        if (scheduledBytes >= 0) {
            var at = stream.decoderDelay() + scheduledTicks * stream.decodingTick();
            var bits = (pendingBytes + scheduledBytes) * 8;
            var first = stream.empty();
            stream.decode(at, bits, h, pts);
            if (first) {
                segment = stream;
                segmentStart = at;
            } else if (key) {
                if (segment != stream) {
                    retiredViolations += segment.violations();
                }
                segment = new DecoderModel(seq);
                segmentStart = at;
                segment.decode(0, scheduledBytes * 8, h, pts);
            } else if (segment != stream) {
                segment.decode(at - segmentStart + segment.decoderDelay(), bits, h, pts);
            }
            lastTicks = scheduledTicks;
            lastNominal = scheduledNominal;
            if (key) {
                keyTicks = scheduledTicks;
            }
            pendingBytes = 0;
            scheduledBytes = -1;
        }
        if (key) {
            keyPts = pts;
            keyed = true;
        }
    }

    /**
     * Counts a coded show-existing header.
     *
     * @param slot the reference slot it shows
     * @param pts the timestamp of the frame it shows
     * @param bytes the size of its OBUs, the temporal delimiter included
     */
    void showExisting(int slot, long pts, long bytes) {
        if (stream == null) {
            return;
        }
        pendingBytes += bytes;
        stream.showExisting(slot, pts);
        if (segment != stream) {
            segment.showExisting(slot, pts);
        }
    }

    /**
     * {@return the conformance requirements of the decoder model the coded frames broke, in the
     * whole stream and in the streams that start at its key frames}
     */
    int violations() {
        return stream == null ? 0
                : retiredViolations + stream.violations() + (segment != stream ? segment.violations() : 0);
    }

    /**
     * Returns the decoding ticks that cover a distance in display ticks.
     *
     * @param seq the sequence header
     * @param displayTicks the distance, in display ticks
     * @return the smallest count of decoding ticks at least as long
     */
    private static long nominalTicks(SequenceHeader seq, long displayTicks) {
        try {
            return Math.ceilDiv(Math.multiplyExact(displayTicks, seq.numUnitsInTick), seq.numUnitsInDecodingTick);
        } catch (ArithmeticException overflow) {
            return (long) Math.ceil((double) displayTicks * seq.numUnitsInTick / seq.numUnitsInDecodingTick);
        }
    }

    /**
     * Returns a count wrapped to a field.
     *
     * @param value the count
     * @param bits the length of the field
     * @return the count modulo the field's range
     */
    private static long wrap(long value, int bits) {
        return bits >= 64 ? value : value & ((1L << bits) - 1);
    }
}
