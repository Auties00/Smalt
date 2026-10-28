package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.internal.io.ByteWriter;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Lays out a QuickTime chapter track (Apple QuickTime File Format, "Chapter Lists"): a disabled text track,
 * named by a {@code tref/chap} reference of the tracks the chapters apply to, whose samples hold the titles.
 *
 * <p>Each sample is a 16-bit big-endian length, the title in UTF-8 (cut at a character boundary to
 * {@value #MAX_TITLE_BYTES} bytes) and an {@code encd} atom declaring UTF-8 (text encoding 256); all samples
 * form one chunk. A chapter's sample lasts until the next chapter starts, the last one until its end, bounded
 * by the movie end when that is known and later than its start. A first chapter that starts after zero is
 * preceded by an empty edit. The media timescale is 1000, or 1000000 when a boundary is not a whole
 * millisecond and every sample duration still fits 32 bits. Instances are immutable and thread-safe.
 *
 * @implNote This implementation flags the track as in the movie but not enabled, gives it a QuickTime
 * base media information header ({@code gmhd} holding {@code gmin} and a {@code text} matrix) rather than
 * {@code nmhd}, and zeroes the display fields of the text sample description, the form QuickTime players
 * read chapter tracks in.
 */
public final class Mp4ChapterTrack {
    /**
     * The longest title stored, in UTF-8 bytes.
     */
    static final int MAX_TITLE_BYTES = 0x7FFF;

    /**
     * The most chapters a Nero {@code chpl} box holds.
     */
    static final int MAX_NERO_CHAPTERS = 255;

    /**
     * The shortest UTF-8 length of a Nero {@code chpl} title that may have been cut: the 255-byte limit less
     * the three bytes a cut at a character boundary can drop.
     */
    static final int CUT_NERO_TITLE_BYTES = 252;

    /**
     * The largest value a 32-bit field holds.
     */
    private static final long U32_MAX = 0xFFFFFFFFL;

    /**
     * The {@code encd} atom that follows every title: size 12, type, encoding 256 (UTF-8).
     */
    private static final byte[] ENCODING = {0, 0, 0, 12, 'e', 'n', 'c', 'd', 0, 0, 1, 0};

    /**
     * The packed ISO 639-2 code {@code und}.
     */
    private static final int LANGUAGE_UNDETERMINED = 0x55C4;

    /**
     * The media timescale.
     */
    private final long timescale;

    /**
     * The start of the first chapter, in microseconds.
     */
    private final long startMicros;

    /**
     * The end of the last sample, in microseconds.
     */
    private final long endMicros;

    /**
     * The sample durations, in media timescale units.
     */
    private final long[] durations;

    /**
     * The sample sizes in bytes.
     */
    private final int[] sizes;

    /**
     * The samples, back to back.
     */
    private final byte[] data;

    private Mp4ChapterTrack(long timescale, long startMicros, long endMicros, long[] durations, int[] sizes,
                            byte[] data) {
        this.timescale = timescale;
        this.startMicros = startMicros;
        this.endMicros = endMicros;
        this.durations = durations;
        this.sizes = sizes;
        this.data = data;
    }

    /**
     * Lays out the chapter track of a list of chapters.
     *
     * @param chapters the chapters, sorted by start time, at least one
     * @param movieEndMicros the movie duration in microseconds, or a value at or below zero when unknown
     * @return the layout
     * @throws IllegalArgumentException if {@code chapters} is empty
     * @throws MediaException.LimitExceeded if a chapter lasts longer than a 32-bit sample duration in
     * milliseconds holds
     */
    public static Mp4ChapterTrack of(List<Chapter> chapters, long movieEndMicros) {
        if (chapters.isEmpty()) {
            throw new IllegalArgumentException("no chapters");
        }
        var count = chapters.size();
        var boundaries = new long[count + 1];
        for (var index = 0; index < count; index++) {
            boundaries[index] = chapters.get(index).startMicros();
        }
        var last = chapters.getLast();
        var end = last.endMicros() > last.startMicros() ? last.endMicros() : movieEndMicros;
        if (movieEndMicros > last.startMicros()) {
            end = Math.min(end, movieEndMicros);
        }
        boundaries[count] = Math.max(end, last.startMicros());
        var timescale = 1_000_000L;
        var whole = true;
        for (var index = 0; index < count; index++) {
            whole &= boundaries[index] % 1000 == 0;
            if (boundaries[index + 1] - boundaries[index] > U32_MAX) {
                timescale = 1000;
            }
        }
        if (whole && boundaries[count] % 1000 == 0) {
            timescale = 1000;
        }
        var units = new long[count + 1];
        for (var index = 0; index <= count; index++) {
            units[index] = timescale == 1000 ? Math.floorDiv(boundaries[index] + 500, 1000) : boundaries[index];
        }
        if (units[count] == units[count - 1]) {
            units[count]++;
        }
        var durations = new long[count];
        for (var index = 0; index < count; index++) {
            durations[index] = units[index + 1] - units[index];
            if (durations[index] > U32_MAX) {
                throw new MediaException.LimitExceeded("chapter " + (index + 1)
                        + " is too long for an MP4 chapter track");
            }
        }
        var sizes = new int[count];
        var out = new ByteWriter(count * 32);
        for (var index = 0; index < count; index++) {
            var title = utf8(chapters.get(index).title().orElse(""));
            out.u16be(title.length);
            out.bytes(title);
            out.bytes(ENCODING);
            sizes[index] = 2 + title.length + ENCODING.length;
        }
        var endMicros = timescale == 1000 ? units[count] * 1000 : units[count];
        return new Mp4ChapterTrack(timescale, boundaries[0], endMicros, durations, sizes, out.toByteArray());
    }

    /**
     * {@return the samples, back to back, which the {@code trak} describes as one chunk; a new array}
     */
    public byte[] data() {
        return data.clone();
    }

    /**
     * {@return the number of sample bytes}
     */
    public int dataLength() {
        return data.length;
    }

    /**
     * Builds the {@code trak} box.
     *
     * @param trackId the track identifier
     * @param movieTimescale the movie timescale, in units per second
     * @param chunkOffset the file offset of the samples
     * @param co64 whether the chunk offset is written as a {@code co64} box rather than {@code stco}
     * @return the box
     * @throws IllegalArgumentException if {@code co64} is {@code false} and the offset does not fit 32 bits
     */
    public byte[] trak(long trackId, long movieTimescale, long chunkOffset, boolean co64) {
        if (!co64 && (chunkOffset < 0 || chunkOffset > U32_MAX)) {
            throw new IllegalArgumentException("chunk offset " + chunkOffset + " needs co64");
        }
        var mediaDuration = 0L;
        for (var duration : durations) {
            mediaDuration += duration;
        }
        var delay = rescale(startMicros, movieTimescale);
        var presented = rescale(endMicros, movieTimescale) - delay;
        var trackDuration = delay + presented;
        var trak = new ByteWriter(512 + data.length / 2);
        var header = new ByteWriter(96);
        var wide = trackDuration > U32_MAX;
        header.u32be(((wide ? 1L : 0L) << 24) | 2);
        header.zeros(wide ? 16 : 8);
        header.u32be(trackId);
        header.u32be(0);
        length(header, trackDuration, wide);
        header.zeros(8);
        header.u16be(0);
        header.u16be(0);
        header.u16be(0);
        header.u16be(0);
        identity(header);
        header.u32be(0);
        header.u32be(0);
        Mp4Boxes.box(trak, "tkhd", header.toByteArray());
        if (delay > 0) {
            var editsWide = trackDuration > U32_MAX;
            var elst = new ByteWriter(48);
            elst.u32be((editsWide ? 1L : 0L) << 24);
            elst.u32be(2);
            length(elst, delay, editsWide);
            length(elst, -1, editsWide);
            elst.u32be(0x00010000);
            length(elst, presented, editsWide);
            length(elst, 0, editsWide);
            elst.u32be(0x00010000);
            Mp4Boxes.box(trak, "edts", Mp4Boxes.box("elst", elst.toByteArray()));
        }
        var mdia = new ByteWriter(400 + data.length / 2);
        var mdhd = new ByteWriter(40);
        var mediaWide = mediaDuration > U32_MAX;
        mdhd.u32be((mediaWide ? 1L : 0L) << 24);
        mdhd.zeros(mediaWide ? 16 : 8);
        mdhd.u32be(timescale);
        length(mdhd, mediaDuration, mediaWide);
        mdhd.u16be(LANGUAGE_UNDETERMINED);
        mdhd.u16be(0);
        Mp4Boxes.box(mdia, "mdhd", mdhd.toByteArray());
        var hdlr = new ByteWriter(40);
        hdlr.u32be(0);
        hdlr.u32be(0);
        hdlr.fourCc("text");
        hdlr.zeros(12);
        hdlr.cString("ChapterHandler");
        Mp4Boxes.box(mdia, "hdlr", hdlr.toByteArray());
        var minf = new ByteWriter(300 + data.length / 2);
        var gmhd = new ByteWriter(80);
        var gmin = new ByteWriter(16);
        gmin.u32be(0);
        gmin.u16be(0x40);
        gmin.u16be(0x8000);
        gmin.u16be(0x8000);
        gmin.u16be(0x8000);
        gmin.u16be(0);
        gmin.u16be(0);
        Mp4Boxes.box(gmhd, "gmin", gmin.toByteArray());
        var text = new ByteWriter(36);
        identity(text);
        Mp4Boxes.box(gmhd, "text", text.toByteArray());
        Mp4Boxes.box(minf, "gmhd", gmhd.toByteArray());
        var dref = new ByteWriter(24);
        dref.u32be(0);
        dref.u32be(1);
        dref.u32be(12);
        dref.fourCc("url ");
        dref.u32be(1);
        Mp4Boxes.box(minf, "dinf", Mp4Boxes.box("dref", dref.toByteArray()));
        Mp4Boxes.box(minf, "stbl", sampleTable(chunkOffset, co64));
        Mp4Boxes.box(mdia, "minf", minf.toByteArray());
        Mp4Boxes.box(trak, "mdia", mdia.toByteArray());
        return Mp4Boxes.box("trak", trak.toByteArray());
    }

    /**
     * Returns whether the chapters of a Nero {@code chpl} box may have been cut to fit it: the box holds at most
     * {@value #MAX_NERO_CHAPTERS} chapters and titles of at most 255 bytes, so a full box or a title of at least
     * {@value #CUT_NERO_TITLE_BYTES} UTF-8 bytes may have lost chapters or title text a chapter track keeps.
     *
     * @param nero the chapters of the {@code chpl} box
     * @return {@code true} when the box is full or holds such a title
     */
    public static boolean mayBeCut(List<Chapter> nero) {
        if (nero.size() >= MAX_NERO_CHAPTERS) {
            return true;
        }
        for (var chapter : nero) {
            if (chapter.title().orElse("").getBytes(StandardCharsets.UTF_8).length >= CUT_NERO_TITLE_BYTES) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether the chapters of a chapter track complete those of a Nero {@code chpl} box that cut them
     * ({@link #mayBeCut(List)}).
     *
     * <p>The track supersedes the box when it holds the box's chapters, each starting within a millisecond and
     * titled with the box's title or a longer text that begins with it, and adds chapters to a full box or
     * text to a title of at least {@value #CUT_NERO_TITLE_BYTES} UTF-8 bytes.
     *
     * @param track the chapters of the chapter track
     * @param nero the chapters of the {@code chpl} box
     * @return {@code true} when {@code track} should be read instead of {@code nero}
     */
    public static boolean supersedes(List<Chapter> track, List<Chapter> nero) {
        if (track.size() < nero.size() || !mayBeCut(nero)) {
            return false;
        }
        var completes = nero.size() >= MAX_NERO_CHAPTERS && track.size() > nero.size();
        for (var index = 0; index < nero.size(); index++) {
            var cut = nero.get(index);
            var full = track.get(index);
            var cutTitle = cut.title().orElse("");
            var fullTitle = full.title().orElse("");
            if (Math.abs(cut.startMicros() - full.startMicros()) > 1000 || !fullTitle.startsWith(cutTitle)) {
                return false;
            }
            completes |= fullTitle.length() > cutTitle.length()
                    && cutTitle.getBytes(StandardCharsets.UTF_8).length >= CUT_NERO_TITLE_BYTES;
        }
        return completes;
    }

    /**
     * Builds the {@code chap} track reference box that names the chapter track, a child of {@code tref}.
     *
     * @param trackId the identifier of the chapter track
     * @return the box
     */
    public static byte[] chapReference(long trackId) {
        var payload = new byte[4];
        payload[0] = (byte) (trackId >>> 24);
        payload[1] = (byte) (trackId >>> 16);
        payload[2] = (byte) (trackId >>> 8);
        payload[3] = (byte) trackId;
        return Mp4Boxes.box("chap", payload);
    }

    /**
     * Builds the {@code tref} box of a track that the chapters apply to, holding only its reference
     * to the chapter track.
     *
     * @param trackId the identifier of the chapter track
     * @return the box
     */
    public static byte[] trackReference(long trackId) {
        return Mp4Boxes.box("tref", chapReference(trackId));
    }

    /**
     * Builds the payload of the {@code stbl} box.
     *
     * @param chunkOffset the file offset of the samples
     * @param co64 whether the offset is 64-bit
     * @return the payload
     */
    private byte[] sampleTable(long chunkOffset, boolean co64) {
        var out = new ByteWriter(160 + sizes.length * 12);
        var stsd = new ByteWriter(72);
        stsd.u32be(0);
        stsd.u32be(1);
        var entry = new ByteWriter(64);
        entry.zeros(6);
        entry.u16be(1);
        entry.u32be(0);
        entry.u32be(0);
        entry.zeros(6);
        entry.zeros(8);
        entry.zeros(8);
        entry.u16be(0);
        entry.u16be(0);
        entry.u8(0);
        entry.u16be(0);
        entry.zeros(6);
        entry.u8(0);
        Mp4Boxes.box(stsd, "text", entry.toByteArray());
        Mp4Boxes.box(out, "stsd", stsd.toByteArray());
        var stts = new ByteWriter(16 + durations.length * 8);
        stts.u32be(0);
        var runsAt = stts.size();
        stts.u32be(0);
        var runs = 0;
        for (var index = 0; index < durations.length; ) {
            var next = index + 1;
            while (next < durations.length && durations[next] == durations[index]) {
                next++;
            }
            stts.u32be(next - index);
            stts.u32be(durations[index]);
            runs++;
            index = next;
        }
        stts.patch32be(runsAt, runs);
        Mp4Boxes.box(out, "stts", stts.toByteArray());
        var stsc = new ByteWriter(20);
        stsc.u32be(0);
        stsc.u32be(1);
        stsc.u32be(1);
        stsc.u32be(sizes.length);
        stsc.u32be(1);
        Mp4Boxes.box(out, "stsc", stsc.toByteArray());
        var stsz = new ByteWriter(12 + sizes.length * 4);
        stsz.u32be(0);
        stsz.u32be(0);
        stsz.u32be(sizes.length);
        for (var size : sizes) {
            stsz.u32be(size);
        }
        Mp4Boxes.box(out, "stsz", stsz.toByteArray());
        var offsets = new ByteWriter(16);
        offsets.u32be(0);
        offsets.u32be(1);
        if (co64) {
            offsets.u64be(chunkOffset);
        } else {
            offsets.u32be(chunkOffset);
        }
        Mp4Boxes.box(out, co64 ? "co64" : "stco", offsets.toByteArray());
        return out.toByteArray();
    }

    /**
     * Converts microseconds to a timescale, rounding to nearest.
     *
     * @param micros the time, at least zero
     * @param timescale the units per second
     * @return the time in timescale units
     */
    private static long rescale(long micros, long timescale) {
        var whole = Math.multiplyHigh(micros, timescale) == 0 && micros * timescale >= 0;
        if (whole) {
            return (micros * timescale + 500_000) / 1_000_000;
        }
        return Math.round(micros / 1_000_000.0 * timescale);
    }

    /**
     * Writes a 32- or 64-bit duration or media time field.
     *
     * @param out the destination
     * @param value the value, {@code -1} for the media time of an empty edit
     * @param wide whether the field is 64 bits wide
     */
    private static void length(ByteWriter out, long value, boolean wide) {
        if (wide) {
            out.u64be(value);
        } else {
            out.u32be(value & U32_MAX);
        }
    }

    /**
     * Writes the identity transformation matrix.
     *
     * @param out the destination
     */
    private static void identity(ByteWriter out) {
        out.u32be(0x00010000);
        out.zeros(12);
        out.u32be(0x00010000);
        out.zeros(12);
        out.u32be(0x40000000);
    }

    /**
     * Encodes a title in UTF-8, cut at a character boundary to fit {@value #MAX_TITLE_BYTES} bytes.
     *
     * @param title the chapter title to encode
     * @return the bytes
     */
    private static byte[] utf8(String title) {
        var bytes = title.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_TITLE_BYTES) {
            return bytes;
        }
        var end = MAX_TITLE_BYTES;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return Arrays.copyOf(bytes, end);
    }
}
