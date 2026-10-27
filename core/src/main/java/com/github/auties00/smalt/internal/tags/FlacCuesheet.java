package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts between chapters and the body of a FLAC {@code CUESHEET} metadata block (RFC 9639 section 8.7).
 *
 * <p>Each track but the lead-out becomes a chapter that starts at its index point 1 (at its first index point
 * when it has none numbered 1) and ends where the next track's chapter starts, the last one at the lead-out.
 * Chapters become a cue sheet that is not a CD-DA one: no media catalog number and no lead-in, tracks numbered
 * from 1 that start at the chapters' starts, each with one index point 1 at its start, and a lead-out track 255 at
 * the end of the stream.
 */
public final class FlacCuesheet {
    /**
     * The size of the fields before the first track: the media catalog number, the lead-in, the CD-DA flag and
     * the reserved bits, and the track count.
     */
    private static final int HEADER = 128 + 8 + 259 + 1;

    /**
     * The size of a track's fields before its index points.
     */
    private static final int TRACK = 8 + 1 + 12 + 14 + 1;

    /**
     * The size of an index point.
     */
    private static final int INDEX = 12;

    /**
     * The number of the lead-out track of a cue sheet that is not a CD-DA one.
     */
    private static final int LEAD_OUT = 255;

    /**
     * The largest number of chapters a cue sheet holds, the track count less the lead-out.
     */
    public static final int MAX_CHAPTERS = 254;

    private FlacCuesheet() {
        throw new AssertionError();
    }

    /**
     * Decodes the chapters of a {@code CUESHEET} block.
     *
     * @param data the bytes holding the block body
     * @param offset the index of the body
     * @param length the length of the body
     * @param sampleRate the sampling rate of the stream, in hertz
     * @return the chapters in track order; empty when the cue sheet holds only the lead-out
     * @throws MediaException.Malformed if the body is truncated, a track has no index point, or the offsets
     * decrease
     */
    public static List<Chapter> decode(byte[] data, int offset, int length, int sampleRate) {
        if (sampleRate <= 0) {
            return List.of();
        }
        if (length < HEADER) {
            throw new MediaException.Malformed("FLAC CUESHEET of " + length + " bytes is truncated");
        }
        var end = offset + length;
        var count = data[offset + HEADER - 1] & 0xFF;
        var position = offset + HEADER;
        var starts = new ArrayList<Long>();
        var leadOut = -1L;
        for (var track = 0; track < count; track++) {
            if (end - position < TRACK) {
                throw new MediaException.Malformed("FLAC CUESHEET track " + track + " is truncated");
            }
            var trackOffset = ByteArrays.i64be(data, position);
            var number = data[position + 8] & 0xFF;
            var points = data[position + TRACK - 1] & 0xFF;
            position += TRACK;
            if ((long) points * INDEX > end - position || trackOffset < 0) {
                throw new MediaException.Malformed("FLAC CUESHEET track " + track + " is truncated");
            }
            if (track == count - 1) {
                leadOut = trackOffset;
                position += points * INDEX;
                break;
            }
            if (points == 0) {
                throw new MediaException.Malformed("FLAC CUESHEET track " + number + " has no index point");
            }
            var start = -1L;
            for (var point = 0; point < points; point++) {
                var pointOffset = ByteArrays.i64be(data, position + point * INDEX);
                var pointNumber = data[position + point * INDEX + 8] & 0xFF;
                if (pointOffset < 0) {
                    throw new MediaException.Malformed("negative FLAC CUESHEET index offset");
                }
                if (start < 0 || pointNumber == 1) {
                    start = trackOffset + pointOffset;
                    if (pointNumber == 1) {
                        break;
                    }
                }
            }
            position += points * INDEX;
            if (start < 0 || (!starts.isEmpty() && start < starts.getLast())) {
                throw new MediaException.Malformed("FLAC CUESHEET track offsets decrease");
            }
            starts.add(start);
        }
        var chapters = new ArrayList<Chapter>(starts.size());
        for (var index = 0; index < starts.size(); index++) {
            var start = starts.get(index);
            var stop = index + 1 < starts.size() ? starts.get(index + 1) : Math.max(start, leadOut);
            chapters.add(Chapter.of(micros(start, sampleRate), micros(stop, sampleRate), null));
        }
        return chapters;
    }

    /**
     * Encodes the {@code CUESHEET} block body of chapters.
     *
     * @param chapters the chapters, in start order, at most {@value #MAX_CHAPTERS}
     * @param sampleRate the sampling rate of the stream, in hertz
     * @param totalSamples the number of samples of the stream, or 0 when unknown, which puts the lead-out at the
     * end of the last chapter
     * @return the body, or {@code null} when there are no chapters, more than {@value #MAX_CHAPTERS} or the rate
     * is not positive
     */
    public static byte[] encode(List<Chapter> chapters, int sampleRate, long totalSamples) {
        if (chapters.isEmpty() || chapters.size() > MAX_CHAPTERS || sampleRate <= 0) {
            return null;
        }
        var out = new ByteWriter(HEADER + (chapters.size() + 1) * (TRACK + INDEX));
        out.zeros(128);
        out.zeros(8);
        out.zeros(259);
        out.u8(chapters.size() + 1);
        var last = 0L;
        for (var index = 0; index < chapters.size(); index++) {
            var start = Math.max(last, samples(chapters.get(index).startMicros(), sampleRate));
            last = start;
            out.u64be(start);
            out.u8(index + 1);
            out.zeros(12);
            out.zeros(14);
            out.u8(1);
            out.u64be(0);
            out.u8(1);
            out.zeros(3);
        }
        var end = totalSamples > 0 ? totalSamples : samples(chapters.getLast().endMicros(), sampleRate);
        out.u64be(Math.max(last, end));
        out.u8(LEAD_OUT);
        out.zeros(12);
        out.zeros(14);
        out.u8(0);
        return out.toByteArray();
    }

    private static long micros(long samples, int sampleRate) {
        return Math.multiplyHigh(samples, 1_000_000L) == 0 && samples * 1_000_000L >= 0
                ? samples * 1_000_000L / sampleRate : (long) (samples / (double) sampleRate * 1e6);
    }

    private static long samples(long micros, int sampleRate) {
        return Math.multiplyHigh(micros, sampleRate) == 0 && micros * sampleRate >= 0
                ? (micros * sampleRate + 500_000) / 1_000_000 : Math.round(micros / 1e6 * sampleRate);
    }
}
