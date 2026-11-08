/* Ports libavif (src/write.c: the image sequence tracks of avifEncoderFinish). Copyright 2019 Joe Drago; BSD
   2-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.internal.io.FourCc;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Writes the {@code moov} box of a HEIF image sequence (ISO/IEC 23008-12 clause 7 over ISO/IEC 14496-12): one
 * visual track per sequence, every sample of a track in one chunk, a sync sample table when not every sample is a
 * sync sample, composition offsets when the samples are reordered, and one repeating edit of one play whose
 * repetitions fill the track duration, the duration of every play (indefinite for an endless loop). The movie and
 * track headers take their 64-bit form when the duration of every play does not fit 32 bits.
 *
 * @implNote This implementation lays out the tracks and sample tables as libavif's {@code avifEncoderFinish}
 * ({@code src/write.c}, BSD-2) does for image sequences.
 */
public final class HeifTrackWriter {
    /**
     * The sentinel {@link Track#compositionOffsets()} value of a track whose samples are not reordered.
     */
    private static final int[] NONE = new int[0];

    /**
     * One track.
     *
     * @param id the track identifier, from 1
     * @param reference the track an auxiliary track describes with an {@code auxl} reference, 0 for none
     * @param premultipliedBy the alpha track whose samples this track's color is premultiplied by, signaled with a
     * {@code prem} reference, 0 for none
     * @param handler the handler type, {@code "pict"} for a color track and {@code "auxv"} for an auxiliary one
     * @param chunkOffset the offset of the track's chunk within the samples
     * @param sizes the sample sizes, in decoding order
     * @param deltas the difference between the decoding time of each sample and the next one, the last entry
     * the duration of the last sample, in the timescale
     * @param compositionOffsets the composition time of each sample minus its decoding time, at least 0, or
     * {@code null} when every sample is presented when it is decoded
     * @param sync the sync flag of each sample, or {@code null} when every sample is a sync sample
     * @param mediaTime the composition time of the first sample presented, in the timescale
     * @param sampleEntry writes the sample entry box of the track's {@code stsd} box
     */
    public record Track(int id, int reference, int premultipliedBy, String handler, long chunkOffset, int[] sizes,
                        long[] deltas, int[] compositionOffsets, boolean[] sync, long mediaTime,
                        Consumer<ByteWriter> sampleEntry) {
    }

    /**
     * Prevents instantiation.
     */
    private HeifTrackWriter() {
        throw new AssertionError();
    }

    /**
     * Builds the {@code moov} box.
     *
     * @param base the file offset of the first sample
     * @param width the track width, in pixels
     * @param height the track height, in pixels
     * @param timescale the units per second of every time
     * @param duration the presentation duration of one play of the sequence, in the timescale
     * @param loops the number of plays, 0 for forever
     * @param tracks the tracks, the color track first
     * @return the box, whose size does not depend on {@code base}
     */
    public static byte[] moov(long base, int width, int height, long timescale, long duration, int loops,
                              List<Track> tracks) {
        var movieDuration = loops == 0 ? 0xFFFFFFFFL : duration * loops;
        // all ones in a 32-bit duration means indefinite, so a finite duration of every play that reaches it takes
        // the 64-bit headers
        var wide = loops != 0 && movieDuration >= 0xFFFFFFFFL;
        var samples = 0;
        for (var track : tracks) {
            samples += track.sizes().length;
        }
        var out = new ByteWriter(1024 + 16 * samples);
        out.begin(FourCc.of("moov"));
        mvhd(out, timescale, movieDuration, wide, tracks.size() + 1);
        for (var track : tracks) {
            trak(out, track, base, width, height, timescale, duration, movieDuration, wide);
        }
        out.end();
        return out.toByteArray();
    }

    /**
     * Writes a visual sample entry: its fixed fields followed by its child boxes.
     *
     * @param out the output
     * @param type the four-character sample entry type, for example {@code "hvc1"}
     * @param width the sample width, in pixels
     * @param height the sample height, in pixels
     * @param children writes the child boxes, the codec configuration first
     */
    public static void visualSampleEntry(ByteWriter out, String type, int width, int height,
                                         Consumer<ByteWriter> children) {
        out.begin(FourCc.of(type));
        out.zeros(6);
        out.u16be(1);
        out.zeros(16);
        out.u16be(width);
        out.u16be(height);
        out.u32be(0x00480000);
        out.u32be(0x00480000);
        out.u32be(0);
        out.u16be(1);
        out.zeros(32);
        out.u16be(0x0018);
        out.u16be(0xFFFF);
        children.accept(out);
        out.end();
    }

    /**
     * Writes a coding constraints box ({@code ccst}).
     *
     * @param out the output
     * @param allReferencesIntra whether every reference picture is intra coded
     * @param intraPrediction whether intra prediction may be used
     * @param maxReferences the most reference pictures a picture uses, from 0 to 15, 15 meaning any number
     */
    public static void ccst(ByteWriter out, boolean allReferencesIntra, boolean intraPrediction, int maxReferences) {
        out.beginFull(FourCc.of("ccst"), 0, 0);
        out.u32be((allReferencesIntra ? 1L << 31 : 0) | (intraPrediction ? 1L << 30 : 0)
                | ((long) (maxReferences & 15) << 26));
        out.end();
    }

    /**
     * Writes an auxiliary type information box ({@code auxi}).
     *
     * @param out the output
     * @param urn the auxiliary type
     */
    public static void auxi(ByteWriter out, String urn) {
        out.beginFull(FourCc.of("auxi"), 0, 0);
        out.cString(urn);
        out.end();
    }

    /**
     * Writes the {@code mvhd} box.
     *
     * @param out the output
     * @param timescale the units per second
     * @param duration the movie duration, in the timescale
     * @param wide whether to write the 64-bit form (version 1)
     * @param nextTrackId the identifier after the last track's
     */
    private static void mvhd(ByteWriter out, long timescale, long duration, boolean wide, int nextTrackId) {
        out.beginFull(FourCc.of("mvhd"), wide ? 1 : 0, 0);
        if (wide) {
            out.u64be(0);
            out.u64be(0);
            out.u32be(timescale);
            out.u64be(duration);
        } else {
            out.u32be(0);
            out.u32be(0);
            out.u32be(timescale);
            out.u32be(duration);
        }
        out.u32be(0x00010000);
        out.u16be(0x0100);
        out.u16be(0);
        out.u32be(0);
        out.u32be(0);
        matrix(out);
        out.zeros(24);
        out.u32be(nextTrackId);
        out.end();
    }

    /**
     * Writes the unity transformation matrix.
     *
     * @param out the output
     */
    private static void matrix(ByteWriter out) {
        long[] unity = {0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000};
        for (var v : unity) {
            out.u32be(v);
        }
    }

    /**
     * Writes a {@code trak} box.
     *
     * @param out the output
     * @param track the track to write, its samples starting at {@code base} plus its chunk offset
     * @param base the file offset of the first sample
     * @param width the track width
     * @param height the track height
     * @param timescale the units per second
     * @param duration the presentation duration of one play, in the timescale
     * @param movieDuration the presentation duration of every play, in the timescale
     * @param wide whether to write the 64-bit form (version 1) of the track header
     */
    private static void trak(ByteWriter out, Track track, long base, int width, int height, long timescale,
                             long duration, long movieDuration, boolean wide) {
        long mediaDuration = 0;
        for (var delta : track.deltas()) {
            mediaDuration += delta;
        }
        out.begin(FourCc.of("trak"));
        out.beginFull(FourCc.of("tkhd"), wide ? 1 : 0, 1);
        if (wide) {
            out.u64be(0);
            out.u64be(0);
            out.u32be(track.id());
            out.u32be(0);
            out.u64be(movieDuration);
        } else {
            out.u32be(0);
            out.u32be(0);
            out.u32be(track.id());
            out.u32be(0);
            out.u32be(movieDuration);
        }
        out.zeros(16);
        matrix(out);
        out.u32be((long) width << 16);
        out.u32be((long) height << 16);
        out.end();
        if (track.reference() != 0 || track.premultipliedBy() != 0) {
            out.begin(FourCc.of("tref"));
            if (track.reference() != 0) {
                out.begin(FourCc.of("auxl"));
                out.u32be(track.reference());
                out.end();
            }
            if (track.premultipliedBy() != 0) {
                out.begin(FourCc.of("prem"));
                out.u32be(track.premultipliedBy());
                out.end();
            }
            out.end();
        }
        out.begin(FourCc.of("edts"));
        out.beginFull(FourCc.of("elst"), 0, 1);
        out.u32be(1);
        // the edit covers one play and repeats until the track duration, which is that of every play
        out.u32be(duration);
        out.u32be(track.mediaTime());
        out.u16be(1);
        out.u16be(0);
        out.end();
        out.end();
        out.begin(FourCc.of("mdia"));
        out.beginFull(FourCc.of("mdhd"), 0, 0);
        out.u32be(0);
        out.u32be(0);
        out.u32be(timescale);
        out.u32be(mediaDuration);
        out.u16be(('u' - 0x60) << 10 | ('n' - 0x60) << 5 | ('d' - 0x60));
        out.u16be(0);
        out.end();
        HeifWriter.hdlr(out, track.handler());
        out.begin(FourCc.of("minf"));
        out.beginFull(FourCc.of("vmhd"), 0, 1);
        out.zeros(8);
        out.end();
        out.begin(FourCc.of("dinf"));
        out.beginFull(FourCc.of("dref"), 0, 0);
        out.u32be(1);
        out.beginFull(FourCc.of("url "), 0, 1);
        out.end();
        out.end();
        out.end();
        stbl(out, track, base + track.chunkOffset());
        out.end();
        out.end();
        out.end();
    }

    /**
     * Writes an {@code stbl} box: the sample entry, the decoding times, the composition offsets, the chunk, the
     * sample sizes and the sync sample table.
     *
     * @param out the output
     * @param track the track whose sample tables to write
     * @param chunkOffset the file offset of the track's chunk
     */
    private static void stbl(ByteWriter out, Track track, long chunkOffset) {
        var sizes = track.sizes();
        out.begin(FourCc.of("stbl"));
        out.beginFull(FourCc.of("stsd"), 0, 0);
        out.u32be(1);
        track.sampleEntry().accept(out);
        out.end();
        var runs = runs(track.deltas());
        out.beginFull(FourCc.of("stts"), 0, 0);
        out.u32be(runs.size());
        for (var run : runs) {
            out.u32be(run[0]);
            out.u32be(run[1]);
        }
        out.end();
        var offsets = track.compositionOffsets() == null ? NONE : track.compositionOffsets();
        if (offsets.length > 0) {
            var deltas = new long[offsets.length];
            for (var i = 0; i < offsets.length; i++) {
                deltas[i] = offsets[i];
            }
            var offsetRuns = runs(deltas);
            out.beginFull(FourCc.of("ctts"), 0, 0);
            out.u32be(offsetRuns.size());
            for (var run : offsetRuns) {
                out.u32be(run[0]);
                out.u32be(run[1]);
            }
            out.end();
        }
        out.beginFull(FourCc.of("stsc"), 0, 0);
        out.u32be(1);
        out.u32be(1);
        out.u32be(sizes.length);
        out.u32be(1);
        out.end();
        out.beginFull(FourCc.of("stsz"), 0, 0);
        out.u32be(0);
        out.u32be(sizes.length);
        for (var size : sizes) {
            out.u32be(size);
        }
        out.end();
        var sync = track.sync();
        if (sync != null) {
            var count = 0;
            for (var s : sync) {
                count += s ? 1 : 0;
            }
            out.beginFull(FourCc.of("stss"), 0, 0);
            out.u32be(count);
            for (var i = 0; i < sync.length; i++) {
                if (sync[i]) {
                    out.u32be(i + 1);
                }
            }
            out.end();
        }
        out.beginFull(FourCc.of("stco"), 0, 0);
        out.u32be(1);
        out.u32be(chunkOffset);
        out.end();
        out.end();
    }

    /**
     * Returns the runs of equal values of a table.
     *
     * @param values the table, in sample order
     * @return the runs, each its length then its value
     */
    private static List<long[]> runs(long[] values) {
        var runs = new ArrayList<long[]>();
        for (var v : values) {
            if (!runs.isEmpty() && runs.getLast()[1] == v) {
                runs.getLast()[0]++;
            } else {
                runs.add(new long[]{1, v});
            }
        }
        return runs;
    }
}
