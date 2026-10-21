package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
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

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;

/**
 * Accumulates what the demuxer learns about one {@code trak}: its header fields, edit list, sample
 * descriptions and sample index, and the fragment defaults of its {@code trex}.
 *
 * <p>Once every box is parsed, {@link #resolveTiming(long, String, MediaLimits)} turns the edit
 * list into the presentation timeline the demuxer follows, and {@link #toTrack()} builds the
 * public {@link Track}.
 *
 * <p>An edit list made of leading empty edits (media time {@code -1}) and one media edit, the form
 * almost every file uses, keeps the media samples as they are and shifts their timestamps: the
 * empty edits delay the presentation, and the media time of the media edit marks the media
 * timestamp presented first. Video and data timestamps are shifted by {@code delay - mediaTime},
 * so the first presented picture has timestamp {@code delay} and pictures decoded only as
 * references have negative timestamps. Audio timestamps are shifted by {@code delay} only: the
 * media time becomes {@link AudioTrack#skipSamples()}, the number of leading decoded samples to
 * discard, so the first sample kept has the timestamp of the first packet; a final edit of
 * duration zero, which a fragmented movie uses because its length is unknown when the
 * {@code moov} is written, extends to the end of the media. Without an edit list, an iTunes
 * {@code iTunSMPB} tag supplies the skip and, through its original sample count, the duration.
 * Opus signals the same priming in band through its pre-skip, so only the part of the media time
 * beyond the pre-skip is reported.
 *
 * <p>Any other edit list (several media edits, an empty edit after a media edit, a dwell edit of
 * media rate zero) is expanded into a presentation index (ISO/IEC 14496-12 clause 8.6.6): each
 * edit presents the media samples overlapping its media window at its place in the presentation
 * timeline, preceded by the samples a decoder needs first (back to the key frame at or before them
 * for video and data, the packets that rebuild the codec's state for audio, by codec as
 * {@code prerollPackets} lists them), which carry the discard flag. A sample presented by
 * several edits appears once per edit. Video and
 * data samples are cut to their edit: a sample straddling the edit start is presented from the
 * edit start, and the last one ends with the edit. Audio packets keep their timing and carry the
 * part outside their edit as their discard start and end. A dwell edit presents the video or data
 * sample shown at its media time for its whole duration, and presents nothing of an audio track,
 * whose timestamps then skip its duration. The edit's media rate is otherwise not applied: every
 * media edit plays at normal speed. Decoding timestamps never decrease, so the pre-roll of an edit
 * that goes back in the media is decoded at the decoding time of the sample before it.
 *
 * <p>The image sequence and auxiliary video tracks of HEIF and AVIF files ({@code pict} and
 * {@code auxv} handlers) are never expanded: their timestamps follow the first media edit as in the
 * simple form, so each of their samples is returned once, as image sequence players show them.
 *
 * <p>When composition offsets are negative (version 1 {@code ctts} or {@code trun}), decoding
 * timestamps are additionally lowered by the magnitude of the most negative offset, as the
 * {@code cslg} box's {@code compositionToDTSShift} describes, so that every sample is decoded no
 * later than it is presented; presentation timestamps are unaffected.
 */
final class Mp4Track {
    /**
     * The pre-roll an Opus decoder needs to converge after a jump, 80 ms as RFC 7845 recommends,
     * in microseconds.
     */
    private static final long OPUS_PREROLL_MICROS = 80_000;

    /**
     * The largest {@code main_data_begin} of an MP3 frame, the number of bytes its main data
     * starts before the frame.
     */
    private static final int MP3_MAX_MAIN_DATA_BEGIN = 511;

    /**
     * The most bytes of an MP3 frame that are not main data: the header, the CRC and the side
     * information of an MPEG-1 stereo frame.
     */
    private static final int MP3_FRAME_OVERHEAD = 4 + 2 + 32;

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
     * Every sample description, by zero-based index; {@code null} where a description failed to
     * parse.
     */
    SampleEntry[] entries;

    /**
     * The failure of each sample description that did not parse, by zero-based index, otherwise
     * {@code null}.
     */
    MediaException[] entryErrors;

    /**
     * The zero-based index of the sample description of the track's first sample in decoding
     * order, which the public track describes.
     */
    int baseDescription;

    /**
     * The sample description the public track describes: the one at {@link #baseDescription},
     * possibly with a refined codec.
     */
    SampleEntry entry;

    /**
     * The codec configuration of the public track, which may refine that of {@link #entry} (an
     * HE-AAC configuration made explicit), or {@code null} without one.
     */
    byte[] publicConfig;

    /**
     * The index the demuxer reads: the media samples, or the presentation index after an edit
     * list expansion.
     */
    SampleTable samples;

    /**
     * The media samples in decoding order, which {@link #samples} replaces when an edit list is
     * expanded.
     */
    SampleTable media;

    /**
     * The indices in {@link #samples}, ascending, of the audio packets of a presentation index
     * that their edit cuts; empty for every other track.
     */
    int[] trimIndex = new int[0];

    /**
     * The discard start and end of each packet of {@link #trimIndex}, two values per packet.
     */
    long[] trimAmounts = new long[0];

    /**
     * Whether {@link #samples} is a presentation index expanded from the edit list.
     */
    boolean expanded;

    /**
     * The composition shift of the media samples, used when they are read without the edit list.
     */
    long mediaCompositionShift;

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
     * The zero-based default sample description index of the track's fragments.
     */
    int defaultDescription;

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
     * Returns whether the edit list is absent, in the simple form the timestamps shift alone
     * follows (empty edits, then at most one media edit, which comes last), or the edit list of an
     * image sequence, whose first media edit alone is followed.
     *
     * @return {@code false} when the edit list needs a presentation index
     */
    boolean hasSimpleEdits() {
        if (!hasEdits() || handler == BoxTypes.PICT || handler == BoxTypes.AUXV) {
            return true;
        }
        var last = editDurations.length - 1;
        for (var index = 0; index < last; index++) {
            if (editMediaTimes[index] >= 0) {
                return false;
            }
        }
        return editMediaTimes[last] < 0 || editRates[last] != 0;
    }

    /**
     * Makes the description of the track's first sample the one the public track describes; a
     * track without samples uses the fragment default.
     *
     * @throws MediaException.Malformed if that description failed to parse as malformed
     * @throws MediaException.Unsupported if that description failed to parse as unsupported
     */
    void selectBaseEntry() {
        baseDescription = samples.count > 0 ? samples.description(0) : defaultDescription;
        entry = description(baseDescription);
    }

    /**
     * Returns a sample description that samples of this track use.
     *
     * @param index the zero-based description index
     * @return the description
     * @throws MediaException.Malformed if the description failed to parse as malformed
     * @throws MediaException.Unsupported if the description failed to parse as unsupported, or
     * cannot follow the track's base description
     */
    SampleEntry description(int index) {
        var found = index < entries.length ? entries[index] : null;
        if (found == null) {
            if (index < entryErrors.length && entryErrors[index] != null) {
                throw entryErrors[index];
            }
            throw new MediaException.Malformed("track " + fileId + " uses missing sample description " + (index + 1));
        }
        var base = entries[baseDescription];
        if (base != null && index != baseDescription && !base.canSwitchTo(found)) {
            throw new MediaException.Unsupported("mp4 sample description change",
                    "track " + fileId + " switches from '" + FourCc.printable(base.format()) + "' (" + base.codec()
                            + ") to '" + FourCc.printable(found.format()) + "' (" + found.codec() + ")");
        }
        return found;
    }

    /**
     * Returns the codec configuration the samples of a sample description establish: the public
     * track's for its own description, the description's otherwise.
     *
     * @param index the zero-based description index
     * @return the configuration, or {@code null} without one
     * @throws MediaException.Malformed if the description failed to parse as malformed
     * @throws MediaException.Unsupported if the description failed to parse as unsupported, or
     * cannot follow the track's base description
     */
    byte[] configOf(int index) {
        var found = description(index);
        return index == baseDescription ? publicConfig : found.config();
    }

    /**
     * Resolves the edit list into the timing of the samples the demuxer reads: either the
     * {@link #shift}, {@link #skipSamples} and {@link #duration} of a simple edit list, or a
     * presentation index.
     *
     * @param movieTimescale the movie timescale that edit durations use
     * @param gapless the iTunes gapless tag of the movie, or {@code null}
     * @param limits the limits bounding the size of a presentation index
     * @throws MediaException.LimitExceeded if the presentation index exceeds the limits
     */
    void resolveTiming(long movieTimescale, String gapless, MediaLimits limits) {
        var minimumOffset = 0;
        for (var index = 0; index < samples.count; index++) {
            minimumOffset = Math.min(minimumOffset, samples.ctsOffsets[index]);
        }
        compositionShift = -(long) minimumOffset;
        mediaCompositionShift = compositionShift;
        media = samples;
        if (!hasSimpleEdits() && movieTimescale > 0) {
            expand(movieTimescale, limits);
            return;
        }
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
                duration = saturatingAdd(delay, Math.max(0, mediaEnd() - mediaTime));
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
     * Returns the end of the last media sample.
     *
     * @return the decoding time after the last sample, in media timescale units
     */
    private long mediaEnd() {
        return samples.count == 0 ? 0
                : samples.dts[samples.count - 1] + Integer.toUnsignedLong(samples.durations[samples.count - 1]);
    }

    /**
     * Replaces {@link #samples} with the presentation index of a multi-segment edit list.
     *
     * @param movieTimescale the movie timescale that edit durations use
     * @param limits the limits bounding the size of the index
     * @throws MediaException.LimitExceeded if the index exceeds the limits
     */
    private void expand(long movieTimescale, MediaLimits limits) {
        var source = samples;
        var count = source.count;
        var audio = entry.kind() == TrackKind.AUDIO;
        var byPts = new Integer[count];
        for (var index = 0; index < count; index++) {
            byPts[index] = index;
        }
        Arrays.sort(byPts, (a, b) -> {
            var order = Long.compare(source.pts(a), source.pts(b));
            return order != 0 ? order : Integer.compare(a, b);
        });
        var sortedPts = new long[count];
        for (var index = 0; index < count; index++) {
            sortedPts[index] = source.pts(byPts[index]);
        }
        var previousKey = new int[count];
        var key = -1;
        for (var index = 0; index < count; index++) {
            if (source.isKeyframe(index)) {
                key = index;
            }
            previousKey[index] = key;
        }
        var out = new SampleTable(limits, Math.min(count, 1024));
        var cuts = new HashMap<Integer, long[]>();
        var presented = new int[count];
        Arrays.fill(presented, -1);
        var mediaEnd = mediaEnd();
        var elapsed = 0L;
        var lastDecoded = -1;
        var lastDts = Long.MIN_VALUE;
        for (var edit = 0; edit < editDurations.length; edit++) {
            var start = rescale(elapsed, movieTimescale, timescale);
            elapsed = saturatingAdd(elapsed, editDurations[edit]);
            var end = rescale(elapsed, movieTimescale, timescale);
            var mediaTime = editMediaTimes[edit];
            if (mediaTime < 0 || mediaTime > Long.MAX_VALUE / 4 || count == 0) {
                continue;
            }
            var length = end - start;
            if (editDurations[edit] == 0 && edit == editDurations.length - 1) {
                // ISO/IEC 14496-12 8.6.6: in a fragmented movie an edit of duration zero spans every fragment
                length = Math.max(0, mediaEnd - mediaTime);
                end = saturatingAdd(start, length);
                elapsed = saturatingAdd(elapsed, rescale(length, timescale, movieTimescale));
            }
            if (length <= 0) {
                continue;
            }
            int first;
            int last;
            if (editRates[edit] == 0) {
                if (audio) {
                    continue;
                }
                var shown = shownAt(byPts, sortedPts, mediaTime);
                // the pre-roll of a dwell is timed from the picture it holds
                mediaTime = source.pts(shown);
                presented[shown] = edit;
                first = shown;
                last = shown;
            } else {
                var window = overlapping(source, byPts, sortedPts, mediaTime, mediaTime + length);
                if (window[0] >= window[1]) {
                    continue;
                }
                first = Integer.MAX_VALUE;
                last = -1;
                for (var position = window[0]; position < window[1]; position++) {
                    var index = byPts[position];
                    presented[index] = edit;
                    first = Math.min(first, index);
                    last = Math.max(last, index);
                }
            }
            var from = audio ? Math.max(0, first - prerollPackets(source, first, lastDecoded))
                    : Math.max(0, previousKey[first]);
            if (lastDecoded + 1 <= first && lastDecoded + 1 >= from) {
                from = lastDecoded + 1;
            }
            out.ensureCapacity(last - from + 1L);
            for (var index = from; index <= last; index++) {
                var pts = source.pts(index);
                var sampleDuration = Integer.toUnsignedLong(source.durations[index]);
                var dts = start + (source.dts[index] - compositionShift - mediaTime);
                dts = Math.max(dts, lastDts);
                lastDts = dts;
                var outPts = start + pts - mediaTime;
                var outDuration = sampleDuration;
                var discard = presented[index] != edit;
                long trimStart = 0;
                long trimEnd = 0;
                if (!discard && editRates[edit] == 0) {
                    outPts = start;
                    outDuration = length;
                } else if (!discard && audio) {
                    trimStart = Math.max(0, mediaTime - pts);
                    trimEnd = Math.max(0, pts + sampleDuration - (mediaTime + length));
                    outDuration = Math.max(0, sampleDuration - trimEnd);
                } else if (!discard) {
                    var visible = Math.max(pts, mediaTime);
                    outPts = start + visible - mediaTime;
                    outDuration = Math.max(0, Math.min(pts + sampleDuration, mediaTime + length) - visible);
                }
                var offset = outPts - dts;
                if (offset != (int) offset) {
                    throw new MediaException.Malformed("edit " + edit + " of track " + fileId
                            + " moves a sample too far from its decoding time");
                }
                out.add(source.offsets[index], source.sizes[index], dts, (int) Math.min(outDuration, 0xFFFFFFFFL),
                        (int) offset, source.isKeyframe(index));
                var added = out.count - 1;
                out.setDescription(added, source.description(index));
                if (discard) {
                    out.flags[added] |= SampleTable.DISCARD;
                } else if (trimStart > 0 || trimEnd > 0) {
                    cuts.put(added, new long[]{trimStart, trimEnd});
                }
            }
            lastDecoded = last;
        }
        if (!audio) {
            separateDiscardedTimes(out);
        } else if (entry.codec() == Codec.OPUS) {
            absorbPreSkip(out, cuts);
        }
        trimIndex = cuts.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
        trimAmounts = new long[trimIndex.length * 2];
        for (var position = 0; position < trimIndex.length; position++) {
            var cut = cuts.get(trimIndex[position]);
            trimAmounts[position * 2] = cut[0];
            trimAmounts[position * 2 + 1] = cut[1];
        }
        samples = out;
        expanded = true;
        shift = 0;
        compositionShift = 0;
        skipSamples = 0;
        duration = rescale(elapsed, movieTimescale, timescale);
        durationFromSamples = false;
    }

    /**
     * Returns the sample shown at a media time: the one with the latest presentation time at or
     * before it, or the earliest sample when every sample starts later.
     *
     * @param byPts the sample indices sorted by presentation time
     * @param sortedPts the presentation times in the same order
     * @param time the media time
     * @return the sample index
     */
    private static int shownAt(Integer[] byPts, long[] sortedPts, long time) {
        var position = upperBound(sortedPts, time) - 1;
        return byPts[Math.max(0, position)];
    }

    /**
     * Returns the samples whose presentation overlaps a media window, as a range of positions in
     * presentation order.
     *
     * @param source the media samples
     * @param byPts the sample indices sorted by presentation time
     * @param sortedPts the presentation times in the same order
     * @param from the start of the window
     * @param to the end of the window, exclusive
     * @return the first position and the position after the last one
     */
    private static int[] overlapping(SampleTable source, Integer[] byPts, long[] sortedPts, long from, long to) {
        var end = lowerBound(sortedPts, to);
        var begin = upperBound(sortedPts, from);
        while (begin > 0) {
            var candidate = byPts[begin - 1];
            var stop = sortedPts[begin - 1] + Math.max(1, Integer.toUnsignedLong(source.durations[candidate]));
            if (stop <= from) {
                break;
            }
            begin--;
        }
        return new int[]{begin, Math.max(begin, end)};
    }

    /**
     * Returns the first position whose value is at least a key.
     *
     * @param values the sorted values
     * @param key the value searched
     * @return the position, from zero to the length
     */
    private static int lowerBound(long[] values, long key) {
        var low = 0;
        var high = values.length;
        while (low < high) {
            var middle = (low + high) >>> 1;
            if (values[middle] < key) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    /**
     * Returns the first position whose value is above a key.
     *
     * @param values the sorted values
     * @param key the value searched
     * @return the position, from zero to the length
     */
    private static int upperBound(long[] values, long key) {
        var low = 0;
        var high = values.length;
        while (low < high) {
            var middle = (low + high) >>> 1;
            if (values[middle] <= key) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    /**
     * Returns how many packets before an audio packet a decoder needs to reproduce its output
     * after a jump:
     * <ul>
     *   <li>none for codecs without state between packets (PCM, FLAC, ALAC);</li>
     *   <li>one for MPEG audio Layer II, whose synthesis filter bank remembers less than a
     *       frame;</li>
     *   <li>for MP3, the frame before it, which fills the overlap and the synthesis filter bank,
     *       and the frames holding the main data that frame's {@code main_data_begin} reaches
     *       back to (a 9-bit byte count);</li>
     *   <li>the packets of 80 ms for Opus;</li>
     *   <li>for AAC whose object type predicts from the start of the stream (Main, LTP, ER LTP,
     *       LD and ELD with low delay SBR), the packets since the last one decoded, so that the
     *       first edit and every edit after a forward jump decode as the whole stream does, and
     *       two after a jump back, from which the prediction only converges; three for ELD, whose
     *       window spans four frames; two for any other AAC, so that the spectral band
     *       replication and parametric stereo state is rebuilt too, even where only the first
     *       packet signals them;</li>
     *   <li>one for any other codec, whose transform overlaps the previous packet.</li>
     * </ul>
     *
     * @param source the media samples
     * @param index the index of the first packet presented
     * @param lastDecoded the index of the last packet the previous edits decode, or {@code -1}
     * @return the number of pre-roll packets, at most {@code index}
     */
    private int prerollPackets(SampleTable source, int index, int lastDecoded) {
        var codec = entry.codec();
        if (codec.isPcm() || codec == Codec.FLAC || codec == Codec.ALAC) {
            return 0;
        }
        return Math.min(index, switch (codec) {
            case MP2 -> 1;
            case MP3 -> mp3Preroll(source, index);
            case OPUS -> {
                var needed = rescale(OPUS_PREROLL_MICROS, 1_000_000, timescale);
                var packets = 0;
                var covered = 0L;
                for (var previous = index - 1; previous >= 0 && covered < needed; previous--) {
                    covered += Integer.toUnsignedLong(source.durations[previous]);
                    packets++;
                }
                yield packets;
            }
            case AAC -> aacPreroll(index, lastDecoded);
            default -> 1;
        });
    }

    /**
     * Returns the MP3 pre-roll of {@link #prerollPackets(SampleTable, int, int)}.
     *
     * @param source the media samples
     * @param index the index of the first packet presented
     * @return the number of pre-roll packets
     */
    private static int mp3Preroll(SampleTable source, int index) {
        var packets = 1;
        var reach = 0L;
        for (var previous = index - 2; previous >= 0 && reach < MP3_MAX_MAIN_DATA_BEGIN; previous--) {
            reach += Math.max(1, source.sizes[previous] - MP3_FRAME_OVERHEAD);
            packets++;
        }
        return packets;
    }

    /**
     * Returns the AAC pre-roll of {@link #prerollPackets(SampleTable, int, int)}.
     *
     * @param index the index of the first packet presented
     * @param lastDecoded the index of the last packet the previous edits decode, or {@code -1}
     * @return the number of pre-roll packets
     */
    private int aacPreroll(int index, int lastDecoded) {
        var config = entry.config();
        if (config == null) {
            return 2;
        }
        try {
            var aac = AudioConfigs.parseAac(config);
            return switch (aac.objectType()) {
                case 1, 4, 19, 23 -> index > lastDecoded ? index - (lastDecoded + 1) : 2;
                case 39 -> !aac.sbr() ? 3 : index > lastDecoded ? index - (lastDecoded + 1) : 2;
                default -> 2;
            };
        } catch (MediaException exception) {
            return 2;
        }
    }

    /**
     * Gives every discarded video or data sample of a presentation index a presentation time that
     * no presented sample has, so that a reader can tell the frames to drop by their time.
     *
     * @param index the presentation index
     */
    private static void separateDiscardedTimes(SampleTable index) {
        var shown = new HashSet<Long>();
        for (var sample = 0; sample < index.count; sample++) {
            if (!index.isDiscarded(sample)) {
                shown.add(index.pts(sample));
            }
        }
        for (var sample = 0; sample < index.count; sample++) {
            if (index.isDiscarded(sample)) {
                var pts = index.pts(sample);
                while (shown.contains(pts)) {
                    pts--;
                }
                index.ctsOffsets[sample] = (int) (pts - index.dts[sample]);
            }
        }
    }

    /**
     * Accounts for the Opus pre-skip, which the decoder drops from the start of its output, in the
     * discard starts of the first packets of a presentation index.
     *
     * @param index the presentation index
     * @param trims the discard start and end of the cut packets, by index, updated
     */
    private void absorbPreSkip(SampleTable index, Map<Integer, long[]> trims) {
        var preSkip = AudioConfigs.opusPreSkip(entry.config());
        if (preSkip <= 0) {
            return;
        }
        var remaining = rescale(preSkip, 48000, timescale);
        for (var sample = 0; sample < index.count && remaining > 0; sample++) {
            var trim = trims.get(sample);
            var length = Integer.toUnsignedLong(index.durations[sample]) + (trim == null ? 0 : trim[1]);
            var dropped = Math.min(remaining, length);
            remaining -= dropped;
            if (index.isDiscarded(sample)) {
                continue;
            }
            var start = trim == null ? 0 : trim[0];
            var end = trim == null ? 0 : trim[1];
            if (end > 0) {
                index.durations[sample] = (int) Math.max(0, Integer.toUnsignedLong(index.durations[sample]) - dropped);
            }
            start = Math.max(0, start - dropped);
            if (start == 0 && end == 0) {
                trims.remove(sample);
            } else {
                trims.put(sample, new long[]{start, end});
            }
        }
    }

    /**
     * Finds the discard start and end of a sample of {@link #samples}.
     *
     * @param index the sample index
     * @return the position {@code p} of the sample in {@link #trimIndex}, whose discard start and
     * end are {@code trimAmounts[2 * p]} and {@code trimAmounts[2 * p + 1]}, or a negative value
     * when the sample is not cut
     */
    int trimOf(int index) {
        return trimIndex.length == 0 ? -1 : Arrays.binarySearch(trimIndex, index);
    }

    /**
     * Returns the edit list in media timescale units, for a writer that copies the media samples
     * unchanged: each edit's duration, media time ({@code -1} for an empty edit) and media rate.
     *
     * @param movieTimescale the movie timescale that edit durations use
     * @return three arrays: durations, media times and rates
     */
    long[][] editsInMediaUnits(long movieTimescale) {
        var count = editDurations.length;
        var durations = new long[count];
        var times = new long[count];
        var rates = new long[count];
        var elapsed = 0L;
        for (var index = 0; index < count; index++) {
            var start = rescale(elapsed, movieTimescale, timescale);
            elapsed = saturatingAdd(elapsed, editDurations[index]);
            durations[index] = rescale(elapsed, movieTimescale, timescale) - start;
            times[index] = editMediaTimes[index];
            rates[index] = editRates[index];
        }
        return new long[][]{durations, times, rates};
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
                var builder = VideoTrack.builder().id(id).codec(entry.codec()).timeBase(timeBase).duration(duration)
                        .codecConfig(config.orElse(null))
                        .language(lang.orElse(null))
                        .width(width)
                        .height(height)
                        .orientation(orientation)
                        .frameRate(frameRate())
                        .color(entry.color());
                if (entry.sampleAspect() != null) {
                    builder.sampleAspectRatio(entry.sampleAspect());
                }
                if (entry.interlacing() != null) {
                    builder.interlacing(entry.interlacing());
                }
                yield builder.build();
            }
            case AUDIO -> AudioTrack.builder().id(id).codec(entry.codec()).timeBase(timeBase).duration(duration)
                    .codecConfig(config.orElse(null))
                    .language(lang.orElse(null))
                    .sampleRate(entry.sampleRate() > 0 || !entry.codec().isPcm() || timescale > Integer.MAX_VALUE
                            ? entry.sampleRate() : (int) timescale)
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
     * Derives the frame rate from the durations of the media samples.
     *
     * <p>When every sample but the last has the same duration the rate is exact; otherwise it is
     * the average rate rounded to a thousandth of a frame per second.
     *
     * @return the frame rate, or {@code null} for a track with fewer than two samples
     */
    private Rational frameRate() {
        var table = media != null ? media : samples;
        var count = table.count;
        if (count < 2) {
            if (count == 1 && table.durations[0] != 0) {
                return new Rational(timescale, Integer.toUnsignedLong(table.durations[0])).reduce();
            }
            return null;
        }
        var first = table.durations[0];
        var constant = first != 0;
        var total = 0L;
        for (var index = 0; index < count - 1; index++) {
            var value = table.durations[index];
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
