package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;

import com.github.auties00.smalt.internal.heif.BoxCursor;
import com.github.auties00.smalt.internal.io.FourCc;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a {@code moov} box held in memory into its tracks, sample indexes, fragment defaults and
 * metadata.
 *
 * <p>The sample tables of each track are expanded into a {@link SampleTable} following ISO/IEC
 * 14496-12 clause 8.6 and 8.7: {@code stsz}/{@code stz2} give the sizes, {@code stsc} and
 * {@code stco}/{@code co64} place samples in chunks, {@code stts} and {@code ctts} give the
 * timing, and {@code stss} (plus the QuickTime {@code stps}) marks sync samples. Tables that
 * disagree about the sample count are reconciled by truncating to the samples every table
 * covers. Uncompressed audio stored one frame per sample is regrouped into packets of at most
 * {@value #PCM_FRAMES_PER_PACKET} frames within each chunk.
 */
final class MovieParser {
    /**
     * The largest number of PCM frames grouped into one packet.
     */
    static final int PCM_FRAMES_PER_PACKET = 1024;

    /**
     * The largest number of tracks accepted in one movie.
     */
    private static final int MAX_TRACKS = 1024;

    /**
     * The limits bounding sample counts, picture sizes and channel counts.
     */
    private final MediaLimits limits;

    /**
     * The parsed tracks, in file order.
     */
    final List<Mp4Track> tracks;

    /**
     * The movie-level metadata tags.
     */
    final Map<String, String> metadata;

    /**
     * The movie timescale in ticks per second.
     */
    long movieTimescale;

    /**
     * The movie duration in movie timescale units.
     */
    long movieDuration;

    /**
     * The fragment duration declared by {@code mehd}, in movie timescale units.
     */
    long fragmentDuration;

    /**
     * Whether the movie declares fragments through {@code mvex}.
     */
    boolean fragmented;

    /**
     * The iTunes gapless tag, or {@code null}.
     */
    String gapless;

    /**
     * The cover pictures and Nero chapters of the movie metadata.
     */
    MetadataReader movieMetadata;

    /**
     * Constructs a parser.
     *
     * @param limits the limits bounding what the movie may declare
     */
    MovieParser(MediaLimits limits) {
        this.limits = limits;
        this.tracks = new ArrayList<>();
        this.metadata = new LinkedHashMap<>();
    }

    /**
     * Parses the payload of a {@code moov} box.
     *
     * @param moov the box payload
     * @throws MediaException.Malformed if a required box is missing or corrupt
     * @throws MediaException.Unsupported if a track is encrypted or the movie header is compressed
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits
     */
    void parse(BoxCursor moov) {
        var metadataReader = new MetadataReader(metadata);
        BoxCursor mvex = null;
        var sawHeader = false;
        while (moov.hasBox()) {
            var box = moov.nextBox();
            switch (box.type()) {
                case BoxTypes.MVHD -> {
                    parseMovieHeader(box);
                    sawHeader = true;
                }
                case BoxTypes.TRAK -> {
                    if (tracks.size() >= MAX_TRACKS) {
                        throw new MediaException.LimitExceeded("movie has more than " + MAX_TRACKS + " tracks");
                    }
                    var track = parseTrack(box);
                    if (track != null) {
                        tracks.add(track);
                    }
                }
                case BoxTypes.MVEX -> mvex = box;
                case BoxTypes.UDTA -> metadataReader.readUserData(box);
                case BoxTypes.META -> {
                    try {
                        metadataReader.readMeta(box, 0);
                    } catch (MediaException.Malformed ignored) {
                        // metadata is descriptive; a damaged meta box leaves the movie playable
                    }
                }
                default -> {
                    if (box.type() == FourCc.of("cmov")) {
                        throw new MediaException.Unsupported("quicktime compressed movie",
                                "the movie header is compressed (cmov)");
                    }
                }
            }
        }
        if (!sawHeader) {
            throw new MediaException.Malformed("moov has no mvhd");
        }
        if (mvex != null) {
            fragmented = true;
            parseMovieExtends(mvex);
        }
        gapless = metadataReader.gapless();
        movieMetadata = metadataReader;
        assignIds();
    }

    /**
     * Parses a {@code mvhd} box.
     *
     * @param mvhd the box payload
     */
    private void parseMovieHeader(BoxCursor mvhd) {
        var version = mvhd.fullBoxHeader() >>> 24;
        long creation;
        if (version == 1) {
            creation = mvhd.u64();
            mvhd.u64();
            movieTimescale = mvhd.u32();
            movieDuration = mvhd.u64();
        } else {
            creation = mvhd.u32();
            mvhd.u32();
            movieTimescale = mvhd.u32();
            movieDuration = mvhd.u32();
            if (movieDuration == 0xFFFFFFFFL) {
                movieDuration = 0;
            }
        }
        if (movieDuration < 0) {
            movieDuration = 0;
        }
        var time = MetadataReader.formatTime(creation);
        if (time != null) {
            metadata.put("creation_time", time);
        }
    }

    /**
     * Parses a {@code mvex} box: the fragment duration and the per-track sample defaults.
     *
     * @param mvex the box payload
     */
    private void parseMovieExtends(BoxCursor mvex) {
        while (mvex.hasBox()) {
            var box = mvex.nextBox();
            var body = box;
            if (box.type() == BoxTypes.MEHD) {
                var version = body.fullBoxHeader() >>> 24;
                fragmentDuration = version == 1 ? body.u64() : body.u32();
            } else if (box.type() == BoxTypes.TREX) {
                body.fullBoxHeader();
                var trackId = body.s32();
                var description = body.u32();
                var duration = body.u32();
                var size = body.u32();
                var flags = body.s32();
                for (var track : tracks) {
                    if (track.fileId == trackId) {
                        track.defaultDuration = duration;
                        track.defaultSize = size;
                        track.defaultFlags = flags;
                        track.defaultDescription = descriptionIndex(description, track.entries.length);
                        break;
                    }
                }
            }
        }
    }

    /**
     * Gives every track a unique positive identifier: the header's identifier when it is unique
     * and positive, the next free value otherwise.
     */
    private void assignIds() {
        var used = new java.util.HashSet<Integer>();
        for (var track : tracks) {
            track.id = track.fileId > 0 && used.add(track.fileId) ? track.fileId : 0;
        }
        var next = 1;
        for (var track : tracks) {
            if (track.id == 0) {
                while (used.contains(next)) {
                    next++;
                }
                track.id = next;
                used.add(next);
            }
        }
    }

    /**
     * Keeps the track identifiers a {@code tref} box lists under {@code chap}, the QuickTime
     * chapter tracks of a track, and under {@code scal}, the tracks whose samples the samples of a
     * scalable or multiview H.264 track extend (ISO/IEC 14496-15).
     *
     * @param tref the box payload
     * @param track receives the identifiers
     */
    private static void parseChapterReferences(BoxCursor tref, Mp4Track track) {
        while (tref.hasBox()) {
            var reference = tref.nextBox();
            var chapters = reference.type() == FourCc.of("chap");
            if (!chapters && reference.type() != FourCc.of("scal")) {
                continue;
            }
            var body = reference;
            var ids = new ArrayList<Integer>();
            while (body.remaining() >= 4 && ids.size() < 16) {
                ids.add((int) body.u32());
            }
            var array = ids.stream().mapToInt(Integer::intValue).toArray();
            if (chapters) {
                track.chapterTracks = array;
            } else {
                track.scalableTracks = array;
            }
        }
    }

    /**
     * Parses a {@code trak} box.
     *
     * @param trak the box payload
     * @return the track
     */
    private Mp4Track parseTrack(BoxCursor trak) {
        var track = new Mp4Track();
        BoxCursor mdia = null;
        while (trak.hasBox()) {
            var box = trak.nextBox();
            switch (box.type()) {
                case BoxTypes.TKHD -> parseTrackHeader(box, track);
                case BoxTypes.EDTS -> parseEdits(box, track);
                case BoxTypes.MDIA -> mdia = box;
                default -> {
                    if (box.type() == FourCc.of("tref")) {
                        parseChapterReferences(box, track);
                    }
                }
            }
        }
        if (mdia == null) {
            throw new MediaException.Malformed("track " + track.fileId + " has no mdia");
        }
        BoxCursor minf = null;
        var sawMediaHeader = false;
        while (mdia.hasBox()) {
            var box = mdia.nextBox();
            var body = box;
            switch (box.type()) {
                case BoxTypes.MDHD -> {
                    parseMediaHeader(body, track);
                    sawMediaHeader = true;
                }
                case BoxTypes.HDLR -> {
                    body.fullBoxHeader();
                    body.u32();
                    track.handler = body.s32();
                }
                case BoxTypes.MINF -> minf = body;
                default -> {
                }
            }
        }
        if (!sawMediaHeader) {
            throw new MediaException.Malformed("track " + track.fileId + " has no mdhd");
        }
        if (track.timescale <= 0) {
            throw new MediaException.Malformed("track " + track.fileId + " has timescale 0");
        }
        if (minf == null) {
            throw new MediaException.Malformed("track " + track.fileId + " has no minf");
        }
        BoxCursor stbl = null;
        BoxCursor dinf = null;
        while (minf.hasBox()) {
            var box = minf.nextBox();
            if (box.type() == BoxTypes.STBL) {
                stbl = box;
            } else if (box.type() == BoxTypes.DINF) {
                dinf = box;
            }
        }
        if (stbl == null) {
            throw new MediaException.Malformed("track " + track.fileId + " has no stbl");
        }
        parseSampleTable(stbl, track, dinf == null ? null : readDataReferences(dinf));
        return track;
    }

    /**
     * Reads which entries of a {@code dref} box refer to the file itself.
     *
     * @param dinf the payload of the {@code dinf} box
     * @return whether each data reference, indexed from zero, is self-contained, or {@code null}
     * when the box has no {@code dref}
     */
    private static boolean[] readDataReferences(BoxCursor dinf) {
        while (dinf.hasBox()) {
            var box = dinf.nextBox();
            if (box.type() != BoxTypes.DREF) {
                continue;
            }
            var dref = box;
            dref.fullBoxHeader();
            var count = (int) Math.min(dref.u32(), 256);
            var selfContained = new boolean[count];
            for (var index = 0; index < count && dref.hasBox(); index++) {
                var entry = dref.nextBox();
                selfContained[index] = entry.remaining() >= 4 && (entry.fullBoxHeader() & 1) != 0;
            }
            return selfContained;
        }
        return null;
    }

    /**
     * Parses a {@code tkhd} box.
     *
     * @param tkhd the box payload
     * @param track the track being built
     */
    private static void parseTrackHeader(BoxCursor tkhd, Mp4Track track) {
        var version = tkhd.fullBoxHeader() >>> 24;
        if (version == 1) {
            tkhd.skip(16);
            track.fileId = tkhd.s32();
            tkhd.skip(4 + 8);
        } else {
            tkhd.skip(8);
            track.fileId = tkhd.s32();
            tkhd.skip(4 + 4);
        }
        tkhd.skip(8 + 2 + 2 + 2 + 2);
        var matrix = new int[9];
        for (var index = 0; index < 9; index++) {
            matrix[index] = tkhd.s32();
        }
        track.matrix = matrix;
        track.headerWidth = (int) (tkhd.u32() >>> 16);
        track.headerHeight = (int) (tkhd.u32() >>> 16);
    }

    /**
     * Parses an {@code edts} box and its {@code elst}.
     *
     * @param edts the box payload
     * @param track the track being built
     */
    private static void parseEdits(BoxCursor edts, Mp4Track track) {
        while (edts.hasBox()) {
            var box = edts.nextBox();
            if (box.type() != BoxTypes.ELST) {
                continue;
            }
            var elst = box;
            var version = elst.fullBoxHeader() >>> 24;
            var count = elst.u32();
            var entrySize = version == 1 ? 20 : 12;
            if (count * entrySize > elst.remaining()) {
                throw new MediaException.Malformed("elst declares " + count + " entries in " + elst.remaining() + " bytes");
            }
            var durations = new long[(int) count];
            var mediaTimes = new long[(int) count];
            var rates = new int[(int) count];
            for (var index = 0; index < count; index++) {
                if (version == 1) {
                    durations[index] = elst.u64();
                    mediaTimes[index] = elst.u64();
                } else {
                    durations[index] = elst.u32();
                    mediaTimes[index] = elst.s32();
                }
                rates[index] = elst.s16();
                elst.u16();
                if (durations[index] < 0) {
                    durations[index] = 0;
                }
            }
            track.editDurations = durations;
            track.editMediaTimes = mediaTimes;
            track.editRates = rates;
            return;
        }
    }

    /**
     * Parses a {@code mdhd} box.
     *
     * @param mdhd the box payload
     * @param track the track being built
     */
    private static void parseMediaHeader(BoxCursor mdhd, Mp4Track track) {
        var version = mdhd.fullBoxHeader() >>> 24;
        if (version == 1) {
            mdhd.skip(16);
            track.timescale = mdhd.u32();
            track.mediaDuration = mdhd.u64();
        } else {
            mdhd.skip(8);
            track.timescale = mdhd.u32();
            track.mediaDuration = mdhd.u32();
        }
        var language = mdhd.u16();
        track.language = decodeLanguage(language);
    }

    /**
     * The ISO 639-2 codes of the QuickTime Macintosh language codes 0 to 23.
     */
    private static final String[] MACINTOSH_LANGUAGES = {"eng", "fra", "deu", "ita", "nld", "swe", "spa", "dan", "por",
            "nor", "heb", "jpn", "ara", "fin", "ell", "isl", "mlt", "tur", "hrv", "zho", "urd", "hin", "tha", "kor"};

    /**
     * Decodes a packed ISO 639-2/T language code: three five-bit letters offset by {@code 0x60}.
     *
     * @param packed the packed code
     * <p>A value below {@code 0x400} is a QuickTime Macintosh language code instead, of which the
     * first 24 are mapped to their ISO 639-2 codes as the QuickTime specification lists them.
     *
     * @return the language, or {@code null} for {@code und}, an unmapped QuickTime numeric code, or
     * an invalid code
     */
    static String decodeLanguage(int packed) {
        if (packed < MACINTOSH_LANGUAGES.length) {
            return MACINTOSH_LANGUAGES[packed];
        }
        if (packed < 0x400 || packed == 0x7FFF) {
            return null;
        }
        var chars = new char[3];
        for (var index = 0; index < 3; index++) {
            var value = (packed >> (10 - 5 * index)) & 0x1F;
            if (value == 0 || value > 26) {
                return null;
            }
            chars[index] = (char) (value + 0x60);
        }
        var result = new String(chars);
        return result.equals("und") ? null : result;
    }

    /**
     * Parses a {@code stbl} box into the track's sample description and sample index.
     *
     * @param stbl the box payload
     * @param track the track being built
     * @param selfContained whether each data reference of the track is self-contained, or
     * {@code null} when the track has no {@code dref}
     * @throws MediaException.Unsupported if the samples are stored in another file
     */
    private void parseSampleTable(BoxCursor stbl, Mp4Track track, boolean[] selfContained) {
        BoxCursor stsd = null, stts = null, ctts = null, stss = null, stps = null, sdtp = null;
        BoxCursor stsz = null, stz2 = null, stsc = null, stco = null, co64 = null, randomAccess = null;
        while (stbl.hasBox()) {
            var box = stbl.nextBox();
            var body = box;
            switch (box.type()) {
                case BoxTypes.STSD -> stsd = body;
                case BoxTypes.STTS -> stts = body;
                case BoxTypes.CTTS -> ctts = body;
                case BoxTypes.STSS -> stss = body;
                case BoxTypes.STPS -> stps = body;
                case BoxTypes.SDTP -> sdtp = body;
                case BoxTypes.SBGP -> {
                    if (randomAccess == null && isRandomAccessGroup(body)) {
                        randomAccess = body;
                    }
                }
                case BoxTypes.STSZ -> stsz = body;
                case BoxTypes.STZ2 -> stz2 = body;
                case BoxTypes.STSC -> stsc = body;
                case BoxTypes.STCO -> stco = body;
                case BoxTypes.CO64 -> co64 = body;
                default -> {
                }
            }
        }
        if (stsd == null) {
            throw new MediaException.Malformed("track " + track.fileId + " has no stsd");
        }
        var errors = new ArrayList<MediaException>();
        track.entries = SampleEntry.parseAll(stsd, track.handler, limits, selfContained, errors);
        track.entryErrors = errors.toArray(new MediaException[0]);
        track.entry = track.entries[0];

        var sizes = new SizeReader(stsz, stz2);
        var sampleCount = sizes.count;
        var chunkOffsets = readChunkOffsets(stco, co64, sampleCount);
        var stscEntries = readSampleToChunk(stsc, sampleCount);
        var timing = readRuns(stts, "stts");
        var composition = ctts == null ? null : readRuns(ctts, "ctts");

        var pcmGrouping = track.entry.codec().isPcm() && sizes.constant > 0 && allDeltasOne(timing);
        if (pcmGrouping) {
            track.samples = expandPcm(track, sizes, chunkOffsets, stscEntries, sampleCount);
            track.selectBaseEntry();
            return;
        }
        var capacity = (int) Math.min(Math.min(sampleCount, coverable(chunkOffsets, stscEntries)), Integer.MAX_VALUE);
        var table = new SampleTable(limits, capacity);
        track.samples = table;
        var filled = placeSamples(table, sizes, chunkOffsets, stscEntries, sampleCount, track.entries.length);
        applyTiming(table, timing, composition, filled);
        applySync(table, stss, stps, sdtp, filled);
        if (stss != null && randomAccess != null) {
            markRandomAccess(table, randomAccess, 0, filled);
        }
        table.count = filled;
        track.selectBaseEntry();
    }

    /**
     * Reads a sample size box.
     */
    private static final class SizeReader {
        /**
         * The number of samples declared.
         */
        private final long count;

        /**
         * The size shared by every sample, or {@code 0} when sizes are listed.
         */
        private final long constant;

        /**
         * The cursor over the listed sizes, or {@code null}.
         */
        private final BoxCursor entries;

        /**
         * The width of each listed size in bits.
         */
        private final int fieldBits;

        /**
         * Parses the header of an {@code stsz} or {@code stz2} box.
         *
         * @param stsz the {@code stsz} payload, or {@code null}
         * @param stz2 the {@code stz2} payload, or {@code null}
         */
        private SizeReader(BoxCursor stsz, BoxCursor stz2) {
            if (stsz != null) {
                stsz.fullBoxHeader();
                constant = stsz.u32();
                count = stsz.u32();
                fieldBits = 32;
                entries = stsz;
                if (constant == 0 && count * 4 > stsz.remaining()) {
                    throw new MediaException.Malformed("stsz declares " + count + " samples in " + stsz.remaining() + " bytes");
                }
            } else if (stz2 != null) {
                stz2.fullBoxHeader();
                stz2.u24();
                fieldBits = stz2.u8();
                count = stz2.u32();
                constant = 0;
                entries = stz2;
                if (fieldBits != 4 && fieldBits != 8 && fieldBits != 16) {
                    throw new MediaException.Malformed("stz2 field size " + fieldBits + " is invalid");
                }
                if ((count * fieldBits + 7) / 8 > stz2.remaining()) {
                    throw new MediaException.Malformed("stz2 declares " + count + " samples in " + stz2.remaining() + " bytes");
                }
            } else {
                count = 0;
                constant = 0;
                fieldBits = 32;
                entries = null;
            }
            if (constant > Integer.MAX_VALUE) {
                throw new MediaException.Malformed("sample size " + constant + " is too large");
            }
        }

        /**
         * Returns the size of the next sample in listing order.
         *
         * @param index the sample index, used for the four-bit layout
         * @return the size in bytes
         */
        private int next(long index) {
            if (constant != 0) {
                return (int) constant;
            }
            long size = switch (fieldBits) {
                case 32 -> entries.u32();
                case 16 -> entries.u16();
                case 8 -> entries.u8();
                default -> {
                    var data = entries.data();
                    var base = entries.position();
                    var value = (index & 1) == 0 ? (data[base] >> 4) & 0x0F : data[base] & 0x0F;
                    if ((index & 1) == 1) {
                        entries.skip(1);
                    }
                    yield value;
                }
            };
            if (size > Integer.MAX_VALUE) {
                throw new MediaException.Malformed("sample size " + size + " is too large");
            }
            return (int) size;
        }
    }

    /**
     * Reads the chunk offsets of an {@code stco} or {@code co64} box.
     *
     * @param stco the {@code stco} payload, or {@code null}
     * @param co64 the {@code co64} payload, or {@code null}
     * @param sampleCount the number of samples, which requires offsets when positive
     * @return the offsets
     */
    private static long[] readChunkOffsets(BoxCursor stco, BoxCursor co64, long sampleCount) {
        var cursor = stco != null ? stco : co64;
        if (cursor == null) {
            if (sampleCount > 0) {
                throw new MediaException.Malformed("sample table has samples but no chunk offsets");
            }
            return new long[0];
        }
        cursor.fullBoxHeader();
        var count = cursor.u32();
        var width = stco != null ? 4 : 8;
        if (count * width > cursor.remaining()) {
            throw new MediaException.Malformed("chunk offset box declares " + count + " chunks in " + cursor.remaining() + " bytes");
        }
        var offsets = new long[(int) count];
        for (var index = 0; index < count; index++) {
            offsets[index] = width == 4 ? cursor.u32() : cursor.u64();
            if (offsets[index] < 0) {
                throw new MediaException.Malformed("negative chunk offset");
            }
        }
        return offsets;
    }

    /**
     * Reads the entries of an {@code stsc} box as triples of first chunk (one-based), samples per
     * chunk and description index.
     *
     * @param stsc the box payload, or {@code null}
     * @param sampleCount the number of samples, which requires entries when positive
     * @return the entries, three longs each
     */
    private static long[] readSampleToChunk(BoxCursor stsc, long sampleCount) {
        if (stsc == null) {
            if (sampleCount > 0) {
                throw new MediaException.Malformed("sample table has samples but no stsc");
            }
            return new long[0];
        }
        stsc.fullBoxHeader();
        var count = stsc.u32();
        if (count * 12 > stsc.remaining()) {
            throw new MediaException.Malformed("stsc declares " + count + " entries in " + stsc.remaining() + " bytes");
        }
        var entries = new long[(int) count * 3];
        var previous = 0L;
        for (var index = 0; index < count; index++) {
            var first = stsc.u32();
            if (first <= previous) {
                throw new MediaException.Malformed("stsc first chunks are not increasing");
            }
            previous = first;
            entries[index * 3] = first;
            entries[index * 3 + 1] = stsc.u32();
            entries[index * 3 + 2] = stsc.u32();
        }
        return entries;
    }

    /**
     * Reads the (count, value) runs of an {@code stts} or {@code ctts} box.
     *
     * @param box the box payload, or {@code null}
     * @param name the box name, for diagnostics
     * @return the runs, two values each: the unsigned count and the signed 32-bit value
     */
    private static long[] readRuns(BoxCursor box, String name) {
        if (box == null) {
            return new long[0];
        }
        box.fullBoxHeader();
        var count = box.u32();
        if (count * 8 > box.remaining()) {
            throw new MediaException.Malformed(name + " declares " + count + " entries in " + box.remaining() + " bytes");
        }
        var runs = new long[(int) count * 2];
        for (var index = 0; index < count; index++) {
            runs[index * 2] = box.u32();
            runs[index * 2 + 1] = box.s32();
        }
        return runs;
    }

    /**
     * Returns whether every run of a timing table has a delta of one.
     *
     * @param timing the {@code stts} runs
     * @return {@code true} when the table is non-empty and every delta is one
     */
    private static boolean allDeltasOne(long[] timing) {
        if (timing.length == 0) {
            return false;
        }
        for (var index = 1; index < timing.length; index += 2) {
            if (timing[index] != 1 && timing[index - 1] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the number of samples the chunks of a track can hold.
     *
     * @param chunkOffsets the chunk offsets
     * @param stsc the sample-to-chunk entries
     * @return the sum of the samples per chunk over every chunk, saturated at {@link Long#MAX_VALUE}
     */
    private static long coverable(long[] chunkOffsets, long[] stsc) {
        var entries = stsc.length / 3;
        var total = 0L;
        for (var entry = 0; entry < entries; entry++) {
            var first = stsc[entry * 3] - 1;
            var last = Math.min(entry + 1 < entries ? stsc[(entry + 1) * 3] - 1 : chunkOffsets.length, chunkOffsets.length);
            if (last > first) {
                var add = Math.multiplyHigh(last - first, stsc[entry * 3 + 1]) != 0
                        ? Long.MAX_VALUE : (last - first) * stsc[entry * 3 + 1];
                total = add < 0 || total + add < 0 ? Long.MAX_VALUE : total + add;
            }
        }
        return total;
    }

    /**
     * Places samples in their chunks, filling offsets and sizes.
     *
     * @param table the table to fill
     * @param sizes the size reader
     * @param chunkOffsets the chunk offsets
     * @param stsc the sample-to-chunk entries
     * @param sampleCount the declared number of samples
     * @param descriptions the number of sample descriptions of the track
     * @return the number of samples the chunks cover, at most {@code sampleCount}
     */
    private static int placeSamples(SampleTable table, SizeReader sizes, long[] chunkOffsets, long[] stsc, long sampleCount,
                                    int descriptions) {
        var entries = stsc.length / 3;
        var index = 0;
        for (var entry = 0; entry < entries && index < sampleCount; entry++) {
            var first = stsc[entry * 3] - 1;
            var last = entry + 1 < entries ? stsc[(entry + 1) * 3] - 1 : chunkOffsets.length;
            last = Math.min(last, chunkOffsets.length);
            var perChunk = stsc[entry * 3 + 1];
            var description = descriptionIndex(stsc[entry * 3 + 2], descriptions);
            for (var chunk = first; chunk < last && index < sampleCount; chunk++) {
                var offset = chunkOffsets[(int) chunk];
                var inChunk = Math.min(perChunk, sampleCount - index);
                for (var sample = 0L; sample < inChunk; sample++) {
                    var size = sizes.next(index);
                    table.offsets[index] = offset;
                    table.sizes[index] = size;
                    table.setDescription(index, description);
                    offset += size;
                    index++;
                }
            }
        }
        return index;
    }

    /**
     * Converts a one-based sample description index into an index of the parsed descriptions.
     *
     * @param value the {@code sample_description_index} of an {@code stsc} entry, a {@code tfhd}
     * or a {@code trex}
     * @param descriptions the number of parsed descriptions
     * @return the zero-based index; the first description for a value out of range, such as the
     * zero of files that leave the field unset
     */
    static int descriptionIndex(long value, int descriptions) {
        return value >= 1 && value <= descriptions ? (int) value - 1 : 0;
    }

    /**
     * Fills decoding timestamps, durations and composition offsets from the timing runs.
     *
     * @param table the table to fill
     * @param timing the {@code stts} runs
     * @param composition the {@code ctts} runs, or {@code null}
     * @param count the number of samples
     */
    private static void applyTiming(SampleTable table, long[] timing, long[] composition, int count) {
        var index = 0;
        var dts = 0L;
        var delta = 0;
        for (var run = 0; run < timing.length && index < count; run += 2) {
            var take = Math.min(timing[run], count - index);
            delta = (int) timing[run + 1];
            for (var sample = 0; sample < take; sample++) {
                table.dts[index] = dts;
                table.durations[index] = delta;
                dts += Integer.toUnsignedLong(delta);
                index++;
            }
        }
        while (index < count) {
            table.dts[index] = dts;
            table.durations[index] = delta;
            dts += Integer.toUnsignedLong(delta);
            index++;
        }
        if (composition == null) {
            return;
        }
        index = 0;
        for (var run = 0; run < composition.length && index < count; run += 2) {
            var take = Math.min(composition[run], count - index);
            var offset = (int) composition[run + 1];
            for (var sample = 0; sample < take; sample++) {
                table.ctsOffsets[index++] = offset;
            }
        }
    }

    /**
     * Marks sync samples.
     *
     * <p>Without an {@code stss} every sample is a sync sample, except samples an {@code sdtp}
     * declares dependent on others. With an {@code stss}, only the listed samples and the
     * QuickTime partial sync samples of {@code stps} are; an {@code stss} that marks no sample
     * at all is treated as absent.
     *
     * @param table the table to fill
     * @param stss the {@code stss} payload, or {@code null}
     * @param stps the {@code stps} payload, or {@code null}
     * @param sdtp the {@code sdtp} payload, or {@code null}
     * @param count the number of samples
     */
    private static void applySync(SampleTable table, BoxCursor stss, BoxCursor stps, BoxCursor sdtp, int count) {
        if (stss == null) {
            var dependencies = sdtp != null && sdtp.remaining() >= 4 + count ? sdtp : null;
            if (dependencies != null) {
                dependencies.fullBoxHeader();
            }
            for (var index = 0; index < count; index++) {
                var key = true;
                if (dependencies != null) {
                    key = ((dependencies.u8() >> 4) & 3) != 1;
                }
                table.flags[index] = key ? SampleTable.KEYFRAME : 0;
            }
            return;
        }
        markListed(table, stss, count);
        if (stps != null) {
            markListed(table, stps, count);
        }
        var any = false;
        for (var index = 0; index < count && !any; index++) {
            any = table.flags[index] != 0;
        }
        if (!any) {
            for (var index = 0; index < count; index++) {
                table.flags[index] = SampleTable.KEYFRAME;
            }
        }
    }

    /**
     * Returns whether an {@code sbgp} box groups random access points: the {@code rap } group of
     * ISO/IEC 14496-12 clause 10.4 or the {@code sync} group of ISO/IEC 14496-15.
     *
     * @param sbgp a cursor over the box payload, not moved
     * @return {@code true} for a random access or sync sample grouping
     */
    static boolean isRandomAccessGroup(BoxCursor sbgp) {
        var cursor = sbgp.duplicate();
        if (cursor.remaining() < 8) {
            return false;
        }
        cursor.fullBoxHeader();
        var type = cursor.s32();
        return type == BoxTypes.RAP || type == BoxTypes.SYNC;
    }

    /**
     * Marks the samples an {@code sbgp} box assigns to a random access group as sync samples:
     * open-GOP random access points are decodable starting points that {@code stss} omits.
     *
     * @param table the table to fill
     * @param sbgp the box payload
     * @param first the index of the first sample the box describes
     * @param count the number of samples in the table
     */
    static void markRandomAccess(SampleTable table, BoxCursor sbgp, int first, int count) {
        var version = sbgp.fullBoxHeader() >>> 24;
        sbgp.s32();
        if (version == 1) {
            sbgp.u32();
        }
        var entries = sbgp.u32();
        if (entries * 8 > sbgp.remaining()) {
            throw new MediaException.Malformed("sbgp declares " + entries + " entries in " + sbgp.remaining() + " bytes");
        }
        var sample = (long) first;
        for (var entry = 0L; entry < entries && sample < count; entry++) {
            var run = sbgp.u32();
            var group = sbgp.u32();
            var end = Math.min(count, sample + run);
            if (group != 0) {
                for (var index = sample; index < end; index++) {
                    table.flags[(int) index] |= SampleTable.KEYFRAME;
                }
            }
            sample = end;
        }
    }

    /**
     * Marks the one-based sample numbers of an {@code stss} or {@code stps} box as sync samples.
     *
     * @param table the table to fill
     * @param box the box payload
     * @param count the number of samples
     */
    private static void markListed(SampleTable table, BoxCursor box, int count) {
        box.fullBoxHeader();
        var entries = box.u32();
        if (entries == 0) {
            return;
        }
        if (entries * 4 > box.remaining()) {
            throw new MediaException.Malformed("sync sample box declares " + entries + " entries in " + box.remaining() + " bytes");
        }
        for (var index = 0L; index < entries; index++) {
            var number = box.u32();
            if (number >= 1 && number <= count) {
                table.flags[(int) (number - 1)] |= SampleTable.KEYFRAME;
            }
        }
    }

    /**
     * Builds the packet index of an uncompressed audio track stored one frame per sample,
     * grouping up to {@value #PCM_FRAMES_PER_PACKET} consecutive frames of a chunk into one
     * packet.
     *
     * @param track the track being built
     * @param sizes the size reader, whose sizes are constant
     * @param chunkOffsets the chunk offsets
     * @param stsc the sample-to-chunk entries
     * @param sampleCount the declared number of frames
     * @return the packet index
     */
    private SampleTable expandPcm(Mp4Track track, SizeReader sizes, long[] chunkOffsets, long[] stsc, long sampleCount) {
        var entries = stsc.length / 3;
        var packets = 0L;
        var covered = 0L;
        for (var entry = 0; entry < entries && covered < sampleCount; entry++) {
            var first = stsc[entry * 3] - 1;
            var last = Math.min(entry + 1 < entries ? stsc[(entry + 1) * 3] - 1 : chunkOffsets.length, chunkOffsets.length);
            var perChunk = stsc[entry * 3 + 1];
            if (last <= first || perChunk == 0) {
                continue;
            }
            var chunks = last - first;
            var full = Math.min(chunks, (sampleCount - covered) / perChunk);
            packets += full * ((perChunk + PCM_FRAMES_PER_PACKET - 1) / PCM_FRAMES_PER_PACKET);
            covered += full * perChunk;
            if (full < chunks && covered < sampleCount) {
                var rest = sampleCount - covered;
                packets += (rest + PCM_FRAMES_PER_PACKET - 1) / PCM_FRAMES_PER_PACKET;
                covered = sampleCount;
            }
            LimitChecks.checkFrames(limits, packets);
        }
        var table = new SampleTable(limits, (int) packets);
        var frame = 0L;
        for (var entry = 0; entry < entries && frame < sampleCount; entry++) {
            var first = stsc[entry * 3] - 1;
            var last = Math.min(entry + 1 < entries ? stsc[(entry + 1) * 3] - 1 : chunkOffsets.length, chunkOffsets.length);
            var perChunk = stsc[entry * 3 + 1];
            var description = descriptionIndex(stsc[entry * 3 + 2], track.entries.length);
            var described = track.entries[description] != null ? track.entries[description] : track.entry;
            var frameBytes = sizes.constant;
            if (frameBytes == 1 && described.frameBytes() > 1) {
                frameBytes = described.frameBytes();
            }
            for (var chunk = first; chunk < last && frame < sampleCount; chunk++) {
                var offset = chunkOffsets[(int) chunk];
                var inChunk = Math.min(perChunk, sampleCount - frame);
                while (inChunk > 0) {
                    var frames = (int) Math.min(inChunk, PCM_FRAMES_PER_PACKET);
                    var bytes = frames * frameBytes;
                    if (bytes > Integer.MAX_VALUE) {
                        throw new MediaException.Malformed("PCM packet of " + bytes + " bytes is too large");
                    }
                    table.add(offset, (int) bytes, frame, frames, 0, true);
                    table.setDescription(table.count - 1, description);
                    offset += bytes;
                    frame += frames;
                    inChunk -= frames;
                }
            }
        }
        return table;
    }
}
