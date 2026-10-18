package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.io.FourCc;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.Optional;

/**
 * Accumulates what the demuxer learns about one {@code trak}: its header fields, edit list, sample
 * description and sample index, and the fragment defaults of its {@code trex}.
 *
 * <p>Once every box is parsed, {@link #resolveTiming(long, String)} turns the edit list into the
 * timestamp shift the demuxer applies, and {@link #toTrack()} builds the public {@link Track}.
 *
 * <p>Edit list handling: leading empty edits (media time {@code -1}) delay the presentation, and
 * the media time of the first non-empty edit marks the media timestamp presented first. Video and
 * data timestamps are shifted by {@code delay - mediaTime}, so the first presented picture has
 * timestamp {@code delay} and pictures decoded only as references have negative timestamps. Audio
 * timestamps are shifted by {@code delay} only: the media time becomes
 * {@link AudioTrack#skipSamples()}, the number of leading decoded samples to discard, so the first
 * sample kept has the timestamp of the first packet; a final edit of duration zero, which a
 * fragmented movie uses because its length is unknown when the {@code moov} is written, extends
 * to the end of the media. Without an edit list, an iTunes
 * {@code iTunSMPB} tag supplies the skip and, through its original sample count, the duration.
 * Opus signals the same priming in band
 * through its pre-skip, so only the part of the media time beyond the pre-skip is reported. Edits
 * after the first non-empty one are ignored.
 *
 * <p>When composition offsets are negative (version 1 {@code ctts} or {@code trun}), decoding
 * timestamps are additionally lowered by the magnitude of the most negative offset, as the
 * {@code cslg} box's {@code compositionToDTSShift} describes, so that every sample is decoded no
 * later than it is presented; presentation timestamps are unaffected.
 */
final class Mp4Track {
    /**
     * The track identifier declared by the track header.
     */
    int fileId;

    /**
     * The identifier the public track and its packets carry.
     */
    int id;

    /**
     * The handler type, for example {@code vide} or {@code soun}.
     */
    int handler;

    /**
     * The media timescale in ticks per second.
     */
    long timescale;

    /**
     * The media duration declared by the media header, in media timescale units.
     */
    long mediaDuration;

    /**
     * The ISO 639-2 language, or {@code null} when undeclared.
     */
    String language;

    /**
     * The display matrix of the track header, or {@code null} when absent.
     */
    int[] matrix;

    /**
     * The identifiers of the QuickTime chapter tracks the track references, or {@code null}.
     */
    int[] chapterTracks;

    /**
     * The integer part of the track header's display width.
     */
    int headerWidth;

    /**
     * The integer part of the track header's display height.
     */
    int headerHeight;

    /**
     * The segment durations of the edit list in movie timescale units, or {@code null} when the
     * track has no edit list.
     */
    long[] editDurations;

    /**
     * The media times of the edit list in media timescale units; {@code -1} marks an empty edit.
     */
    long[] editMediaTimes;

    /**
     * The integer media rates of the edit list; {@code 0} marks a dwell.
     */
    int[] editRates;

    /**
     * The first sample description.
     */
    SampleEntry entry;

    /**
     * The expanded sample index.
     */
    SampleTable samples;

    /**
     * The default sample duration of the track's fragments.
     */
    long defaultDuration;

    /**
     * The default sample size of the track's fragments.
     */
    long defaultSize;

    /**
     * The default sample flags of the track's fragments.
     */
    int defaultFlags;

    /**
     * The decoding time of the next fragment sample when no {@code tfdt} states it.
     */
    long nextFragmentDts;

    /**
     * The shift added to every decoding and presentation timestamp.
     */
    long shift;

    /**
     * The amount subtracted from decoding timestamps so that no sample is decoded after it is
     * presented, which negative composition offsets would otherwise cause.
     */
    long compositionShift;

    /**
     * The leading decoded samples to discard, for audio tracks.
     */
    long skipSamples;

    /**
     * The presentation duration in media timescale units, or {@code -1} when unknown.
     */
    long duration;

    /**
     * Whether {@link #duration} was measured from the indexed samples rather than declared by
     * the media header, the edit list or a gapless tag, so that it grows with every fragment
     * indexed later.
     */
    boolean durationFromSamples;

    /**
     * Returns the kind of the track.
     *
     * @return the kind implied by the sample description
     */
    TrackKind kind() {
        return entry.kind();
    }

    /**
     * Returns whether the track has an edit list.
     *
     * @return {@code true} when an {@code elst} was parsed
     */
    boolean hasEdits() {
        return editDurations != null && editDurations.length > 0;
    }

    /**
     * Resolves the edit list into {@link #shift}, {@link #skipSamples} and {@link #duration}.
     *
     * @param movieTimescale the movie timescale that edit durations use
     * @param gapless the iTunes gapless tag of the movie, or {@code null}
     */
    void resolveTiming(long movieTimescale, String gapless) {
        var minimumOffset = 0;
        for (var index = 0; index < samples.count; index++) {
            minimumOffset = Math.min(minimumOffset, samples.ctsOffsets[index]);
        }
        compositionShift = -(long) minimumOffset;
        var delay = 0L;
        var mediaTime = 0L;
        var totalEdits = 0L;
        var found = false;
        var openEnded = false;
        if (hasEdits() && movieTimescale > 0) {
            for (var index = 0; index < editDurations.length; index++) {
                totalEdits = saturatingAdd(totalEdits, editDurations[index]);
                if (found) {
                    continue;
                }
                if (editMediaTimes[index] < 0) {
                    delay = saturatingAdd(delay, editDurations[index]);
                } else if (editRates[index] != 0) {
                    mediaTime = editMediaTimes[index];
                    found = true;
                    openEnded = editDurations[index] == 0 && index == editDurations.length - 1;
                }
            }
            delay = rescale(delay, movieTimescale, timescale);
            duration = rescale(totalEdits, movieTimescale, timescale);
            if (openEnded && samples.count > 0) {
                // ISO/IEC 14496-12 8.6.6: in a fragmented movie an edit of duration zero spans every fragment
                var mediaEnd = samples.dts[samples.count - 1] + Integer.toUnsignedLong(samples.durations[samples.count - 1]);
                duration = saturatingAdd(delay, Math.max(0, mediaEnd - mediaTime));
                durationFromSamples = true;
            }
        } else {
            var declared = mediaDuration > 0 && mediaDuration != 0xFFFFFFFFL ? mediaDuration : 0;
            duration = Math.max(declared, samples.totalDuration());
            durationFromSamples = samples.totalDuration() > declared;
        }
        if (entry.kind() == TrackKind.AUDIO) {
            shift = delay;
            var sampleRate = entry.sampleRate() > 0 ? entry.sampleRate() : timescale;
            var skip = found ? rescale(mediaTime, timescale, sampleRate) : 0;
            if (!found && gapless != null) {
                skip = parseGaplessField(gapless, 1);
                var length = parseGaplessField(gapless, 3);
                if (length > 0) {
                    duration = rescale(length, sampleRate, timescale);
                    durationFromSamples = false;
                }
            }
            var preSkip = entry.codec() == Codec.OPUS ? AudioConfigs.opusPreSkip(entry.config()) : -1;
            if (preSkip >= 0) {
                skip = Math.max(0, skip - rescale(preSkip, 48000, sampleRate));
            }
            skipSamples = Math.max(0, skip);
        } else {
            shift = delay - mediaTime;
        }
    }

    /**
     * Returns one field of an iTunes {@code iTunSMPB} tag.
     *
     * @param gapless the tag value: space-separated hexadecimal fields of which the second is the
     * encoder delay, the third the end padding and the fourth the original sample count
     * @param index the zero-based field index
     * @return the field value in samples, or {@code 0} when the tag cannot be parsed or the value
     * is implausible
     */
    private static long parseGaplessField(String gapless, int index) {
        var fields = gapless.trim().split("\\s+");
        if (fields.length <= index || fields[index].length() > 16) {
            return 0;
        }
        try {
            var value = Long.parseUnsignedLong(fields[index], 16);
            return value >= 0 && value < 1L << 40 ? value : 0;
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    /**
     * Converts a duration between timescales, rounding to nearest.
     *
     * @param value the duration
     * @param from the source timescale; positive
     * @param to the target timescale; positive
     * @return the converted duration, saturated to the {@code long} range
     */
    static long rescale(long value, long from, long to) {
        if (from == to || value == 0) {
            return value;
        }
        try {
            return new Rational(1, from).rescale(value, new Rational(1, to));
        } catch (ArithmeticException exception) {
            return value < 0 ? Long.MIN_VALUE / 4 : Long.MAX_VALUE / 4;
        }
    }

    /**
     * Adds two non-negative durations without overflowing.
     *
     * @param a the first addend
     * @param b the second addend
     * @return the sum, saturated to a quarter of the {@code long} range
     */
    private static long saturatingAdd(long a, long b) {
        var sum = a + b;
        return sum < 0 || sum > Long.MAX_VALUE / 4 ? Long.MAX_VALUE / 4 : sum;
    }

    /**
     * Builds the public description of the track.
     *
     * @return the track
     */
    Track toTrack() {
        var timeBase = new Rational(1, timescale);
        var config = Optional.ofNullable(entry.config());
        var lang = Optional.ofNullable(language);
        return switch (entry.kind()) {
            case VIDEO -> {
                var width = entry.width() > 0 ? entry.width() : headerWidth;
                var height = entry.height() > 0 ? entry.height() : headerHeight;
                var orientation = matrix == null ? Orientation.NORMAL : DisplayMatrix.toOrientation(matrix);
                yield VideoTrack.builder().id(id).codec(entry.codec()).timeBase(timeBase).duration(duration)
                        .codecConfig(config.orElse(null))
                        .language(lang.orElse(null))
                        .width(width)
                        .height(height)
                        .orientation(orientation)
                        .frameRate(frameRate())
                        .color(entry.color())
                        .build();
            }
            case AUDIO -> AudioTrack.builder().id(id).codec(entry.codec()).timeBase(timeBase).duration(duration)
                    .codecConfig(config.orElse(null))
                    .language(lang.orElse(null))
                    .sampleRate(entry.sampleRate())
                    .layout(entry.layout())
                    .bitsPerSample(entry.bitsPerSample())
                    .skipSamples(skipSamples)
                    .build();
            case DATA -> DataTrack.builder().id(id).codec(entry.codec()).timeBase(timeBase).duration(duration)
                    .codecConfig(config.orElse(null))
                    .language(lang.orElse(null))
                    .handler(FourCc.printable(handler).trim())
                    .build();
        };
    }

    /**
     * Derives the frame rate from the sample durations.
     *
     * <p>When every sample but the last has the same duration the rate is exact; otherwise it is
     * the average rate rounded to a thousandth of a frame per second.
     *
     * @return the frame rate, or {@code null} for a track with fewer than two samples
     */
    private Rational frameRate() {
        var count = samples.count;
        if (count < 2) {
            if (count == 1 && samples.durations[0] != 0) {
                return new Rational(timescale, Integer.toUnsignedLong(samples.durations[0])).reduce();
            }
            return null;
        }
        var first = samples.durations[0];
        var constant = first != 0;
        var total = 0L;
        for (var index = 0; index < count - 1; index++) {
            var value = samples.durations[index];
            constant &= value == first;
            total += Integer.toUnsignedLong(value);
        }
        if (constant) {
            return new Rational(timescale, Integer.toUnsignedLong(first)).reduce();
        }
        if (total <= 0) {
            return null;
        }
        var milli = Math.round((count - 1) * (double) timescale * 1000.0 / total);
        return milli > 0 ? new Rational(milli, 1000).reduce() : null;
    }
}
