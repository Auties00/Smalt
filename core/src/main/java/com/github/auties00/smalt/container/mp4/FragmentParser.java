package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.heif.BoxCursor;

import java.util.List;

/**
 * Appends the samples of a movie fragment ({@code moof}) to the sample indexes of its tracks.
 *
 * <p>Follows ISO/IEC 14496-12 clause 8.8: {@code tfhd} selects the track and overrides the
 * {@code trex} defaults, {@code tfdt} states the decoding time of the first sample, and each
 * {@code trun} lists samples whose data starts at the base data offset plus the run's data
 * offset. The base data offset is the {@code tfhd} value when present, otherwise the start of
 * the {@code moof} for the first track fragment (and for every track fragment when
 * {@code default-base-is-moof} is set), otherwise the end of the previous track fragment's data.
 * Runs without a data offset continue where the previous run's data ended. Sample flags mark a
 * sample as a sync sample unless {@code sample_is_non_sync_sample} is set or
 * {@code sample_depends_on} says it depends on other samples; samples a track fragment's
 * {@code sbgp} assigns to a random access ({@code rap } or {@code sync}) group are sync samples
 * too.
 */
final class FragmentParser {
    /**
     * The {@code tfhd} flag announcing a base data offset.
     */
    private static final int TFHD_BASE_DATA_OFFSET = 0x000001;

    /**
     * The {@code tfhd} flag announcing a sample description index.
     */
    private static final int TFHD_DESCRIPTION_INDEX = 0x000002;

    /**
     * The {@code tfhd} flag announcing a default sample duration.
     */
    private static final int TFHD_DEFAULT_DURATION = 0x000008;

    /**
     * The {@code tfhd} flag announcing a default sample size.
     */
    private static final int TFHD_DEFAULT_SIZE = 0x000010;

    /**
     * The {@code tfhd} flag announcing default sample flags.
     */
    private static final int TFHD_DEFAULT_FLAGS = 0x000020;

    /**
     * The {@code tfhd} flag making the enclosing {@code moof} the base of every data offset.
     */
    private static final int TFHD_DEFAULT_BASE_IS_MOOF = 0x020000;

    /**
     * The {@code trun} flag announcing a data offset.
     */
    private static final int TRUN_DATA_OFFSET = 0x000001;

    /**
     * The {@code trun} flag announcing the flags of the first sample.
     */
    private static final int TRUN_FIRST_FLAGS = 0x000004;

    /**
     * The {@code trun} flag announcing per-sample durations.
     */
    private static final int TRUN_DURATION = 0x000100;

    /**
     * The {@code trun} flag announcing per-sample sizes.
     */
    private static final int TRUN_SIZE = 0x000200;

    /**
     * The {@code trun} flag announcing per-sample flags.
     */
    private static final int TRUN_FLAGS = 0x000400;

    /**
     * The {@code trun} flag announcing per-sample composition offsets.
     */
    private static final int TRUN_CTS = 0x000800;

    /**
     * The sample flag {@code sample_is_non_sync_sample}.
     */
    private static final int NON_SYNC = 0x00010000;

    private FragmentParser() {
        throw new AssertionError();
    }

    /**
     * Parses one {@code moof} and appends its samples.
     *
     * @param moof the box payload
     * @param moofStart the absolute file offset of the first byte of the {@code moof} box
     * @param tracks the tracks of the movie
     * @throws MediaException.Malformed if a box is corrupt
     * @throws MediaException.LimitExceeded if a track exceeds the sample limit
     */
    static void parse(BoxCursor moof, long moofStart, List<Mp4Track> tracks) {
        var previousEnd = moofStart;
        var firstTraf = true;
        while (moof.hasBox()) {
            var box = moof.nextBox();
            if (box.type() != BoxTypes.TRAF) {
                continue;
            }
            previousEnd = parseTrackFragment(box, moofStart, firstTraf ? moofStart : previousEnd, tracks);
            firstTraf = false;
        }
    }

    /**
     * Parses one {@code traf}.
     *
     * @param traf the box payload
     * @param moofStart the absolute offset of the enclosing {@code moof}
     * @param implicitBase the base data offset to use when neither {@code tfhd} nor
     * {@code default-base-is-moof} gives one
     * @param tracks the tracks of the movie
     * @return the absolute offset just past the last sample of this track fragment
     */
    private static long parseTrackFragment(BoxCursor traf, long moofStart, long implicitBase, List<Mp4Track> tracks) {
        BoxCursor tfhd = null;
        BoxCursor tfdt = null;
        BoxCursor randomAccess = null;
        var start = traf.position();
        while (traf.hasBox()) {
            var box = traf.nextBox();
            if (box.type() == BoxTypes.TFHD && tfhd == null) {
                tfhd = box;
            } else if (box.type() == BoxTypes.TFDT && tfdt == null) {
                tfdt = box;
            } else if (box.type() == BoxTypes.SBGP && randomAccess == null && MovieParser.isRandomAccessGroup(box)) {
                randomAccess = box;
            }
        }
        if (tfhd == null) {
            throw new MediaException.Malformed("traf without tfhd");
        }
        var flags = tfhd.fullBoxHeader() & 0xFFFFFF;
        var trackId = tfhd.s32();
        Mp4Track track = null;
        for (var candidate : tracks) {
            if (candidate.fileId == trackId) {
                track = candidate;
                break;
            }
        }
        if (track == null) {
            return implicitBase;
        }
        var base = implicitBase;
        if ((flags & TFHD_BASE_DATA_OFFSET) != 0) {
            base = tfhd.u64();
            if (base < 0) {
                throw new MediaException.Malformed("negative base data offset");
            }
        } else if ((flags & TFHD_DEFAULT_BASE_IS_MOOF) != 0) {
            base = moofStart;
        }
        if ((flags & TFHD_DESCRIPTION_INDEX) != 0) {
            tfhd.u32();
        }
        var defaultDuration = (flags & TFHD_DEFAULT_DURATION) != 0 ? tfhd.u32() : track.defaultDuration;
        var defaultSize = (flags & TFHD_DEFAULT_SIZE) != 0 ? tfhd.u32() : track.defaultSize;
        var defaultFlags = (flags & TFHD_DEFAULT_FLAGS) != 0 ? tfhd.s32() : track.defaultFlags;
        if (tfdt != null) {
            var version = tfdt.fullBoxHeader() >>> 24;
            track.nextFragmentDts = version == 1 ? tfdt.u64() : tfdt.u32();
        }
        var position = base;
        var first = track.samples.count;
        traf.position(start);
        while (traf.hasBox()) {
            var box = traf.nextBox();
            if (box.type() == BoxTypes.TRUN) {
                position = parseRun(box, track, base, position, defaultDuration, defaultSize, defaultFlags);
            }
        }
        if (randomAccess != null) {
            MovieParser.markRandomAccess(track.samples, randomAccess, first, track.samples.count);
        }
        return position;
    }

    /**
     * Parses one {@code trun} and appends its samples to the track.
     *
     * <p>Composition offsets are read as signed values in both versions of the box, because
     * writers use version 0 for negative offsets too.
     *
     * @param trun the box payload
     * @param track the track the run belongs to
     * @param base the base data offset of the track fragment
     * @param position the offset where the previous run's data ended
     * @param defaultDuration the default sample duration
     * @param defaultSize the default sample size
     * @param defaultFlags the default sample flags
     * @return the absolute offset just past the run's data
     */
    private static long parseRun(BoxCursor trun, Mp4Track track, long base, long position, long defaultDuration,
                                 long defaultSize, int defaultFlags) {
        var flags = trun.fullBoxHeader() & 0xFFFFFF;
        var count = trun.u32();
        if ((flags & TRUN_DATA_OFFSET) != 0) {
            position = base + trun.s32();
            if (position < 0) {
                throw new MediaException.Malformed("negative trun data offset");
            }
        }
        var firstFlags = (flags & TRUN_FIRST_FLAGS) != 0 ? trun.s32() : 0;
        var recordSize = ((flags & TRUN_DURATION) != 0 ? 4 : 0) + ((flags & TRUN_SIZE) != 0 ? 4 : 0)
                + ((flags & TRUN_FLAGS) != 0 ? 4 : 0) + ((flags & TRUN_CTS) != 0 ? 4 : 0);
        if (count * recordSize > trun.remaining()) {
            throw new MediaException.Malformed("trun declares " + count + " samples in " + trun.remaining() + " bytes");
        }
        var table = track.samples;
        table.ensureCapacity(count);
        var dts = track.nextFragmentDts;
        for (var index = 0L; index < count; index++) {
            var duration = (flags & TRUN_DURATION) != 0 ? trun.u32() : defaultDuration;
            var size = (flags & TRUN_SIZE) != 0 ? trun.u32() : defaultSize;
            int sampleFlags;
            if ((flags & TRUN_FLAGS) != 0) {
                sampleFlags = trun.s32();
            } else if (index == 0 && (flags & TRUN_FIRST_FLAGS) != 0) {
                sampleFlags = firstFlags;
            } else {
                sampleFlags = defaultFlags;
            }
            var cts = (flags & TRUN_CTS) != 0 ? trun.s32() : 0;
            if (size > Integer.MAX_VALUE) {
                throw new MediaException.Malformed("fragment sample size " + size + " is too large");
            }
            var keyframe = (sampleFlags & NON_SYNC) == 0 && ((sampleFlags >>> 24) & 3) != 1;
            table.add(position, (int) size, dts, (int) duration, cts, keyframe);
            position += size;
            dts += duration;
        }
        track.nextFragmentDts = dts;
        return position;
    }
}
