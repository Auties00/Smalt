package com.github.auties00.smalt.testkit.validate;

import com.github.auties00.smalt.testkit.validate.IsoBmff.Box;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.github.auties00.smalt.testkit.validate.IsoBmff.flags;
import static com.github.auties00.smalt.testkit.validate.IsoBmff.fourcc;
import static com.github.auties00.smalt.testkit.validate.IsoBmff.s32;
import static com.github.auties00.smalt.testkit.validate.IsoBmff.s64;
import static com.github.auties00.smalt.testkit.validate.IsoBmff.u16;
import static com.github.auties00.smalt.testkit.validate.IsoBmff.u32;
import static com.github.auties00.smalt.testkit.validate.IsoBmff.u8;
import static com.github.auties00.smalt.testkit.validate.IsoBmff.version;

/**
 * Checks an ISO base media file, a fragmented one, a CMAF track file, or an initialization or media segment of one,
 * against the structural and semantic rules of ISO/IEC 14496-12, the codec bindings of ISO/IEC 14496-15 and the
 * VP9, AV1, Opus and FLAC ISOBMFF bindings, and ISO/IEC 23000-19 (CMAF), in the manner of GPAC's isovalidator.
 *
 * <p>The checks cover the box layout (sizes, versions, the boxes each container requires and the boxes that must be
 * unique), the sample tables (counts that agree across {@code stts}, {@code ctts}, {@code stsz}, {@code stsc},
 * {@code stco} and {@code stss}, samples inside an {@code mdat} and not overlapping), the durations of
 * {@code mvhd}, {@code tkhd} and {@code mdhd}, the codec configuration of each sample entry, and, for fragments, the
 * {@code trex} defaults, the sequence numbers, the data offsets of every {@code trun}, the continuity of
 * {@code tfdt}, and the {@code sidx} and {@code mfra} indexes.
 */
public final class IsoBmffValidator {
    /** What a validated file is. */
    public enum Kind {
        /** A complete movie: {@code ftyp}, {@code moov} and, when it has samples, {@code mdat}; or a fragmented one. */
        FILE,
        /** An initialization segment: {@code ftyp} and a {@code moov} with {@code mvex}, without samples. */
        INIT_SEGMENT,
        /** A media segment: optional {@code styp} and {@code sidx}, then movie fragments. */
        MEDIA_SEGMENT
    }

    /**
     * A track of the movie.
     *
     * @param id the track id
     * @param handler the handler type, for example {@code vide} or {@code soun}
     * @param timescale the media timescale
     * @param sampleEntry the type of the first sample entry
     * @param codecs the RFC 6381 codecs parameter of the first sample entry
     * @param defaultDuration the {@code trex} default sample duration, or 0 without one
     * @param defaultSize the {@code trex} default sample size, or 0 without one
     * @param defaultFlags the {@code trex} default sample flags, or 0 without one
     * @param width the visual width of the sample entry, or 0 for a track without pictures
     * @param height the visual height of the sample entry, or 0 for a track without pictures
     * @param editOffset the media time the edit list skips before the presentation starts, in the media timescale:
     *                   the media time of a first edit that is not empty, 0 without one
     */
    public record Track(int id, String handler, long timescale, String sampleEntry, String codecs, long defaultDuration,
                        long defaultSize, long defaultFlags, int width, int height, long editOffset) {
    }

    /**
     * A track fragment.
     *
     * @param moofOffset the offset of the enclosing {@code moof}
     * @param sequence the {@code mfhd} sequence number
     * @param trackId the track id
     * @param baseDecodeTime the {@code tfdt} base media decode time, or -1 without one
     * @param duration the sum of the sample durations, in the track's timescale
     * @param samples the number of samples
     * @param firstSync whether the first sample is a sync sample
     * @param bytes the number of sample bytes
     * @param syncSamples the number of sync samples
     */
    public record Fragment(long moofOffset, long sequence, int trackId, long baseDecodeTime, long duration, int samples,
                           boolean firstSync, long bytes, int syncSamples) {
    }

    /**
     * What validation learned of a file.
     *
     * @param boxes the top-level boxes
     * @param brands the major brand followed by the compatible brands of {@code ftyp} or {@code styp}
     * @param tracks the tracks by id, in file order
     * @param fragments the track fragments, in file order
     */
    public record Movie(List<Box> boxes, List<String> brands, Map<Integer, Track> tracks, List<Fragment> fragments) {
    }

    /** The visual sample entries and the configuration box each requires. */
    private static final Map<String, String> VISUAL_CONFIG = Map.ofEntries(Map.entry("avc1", "avcC"),
            Map.entry("avc3", "avcC"), Map.entry("hvc1", "hvcC"), Map.entry("hev1", "hvcC"), Map.entry("vp08", "vpcC"),
            Map.entry("vp09", "vpcC"), Map.entry("av01", "av1C"), Map.entry("mp4v", "esds"));

    /** The audio sample entries and the configuration box each requires. */
    private static final Map<String, String> AUDIO_CONFIG = Map.of("mp4a", "esds", "Opus", "dOps", "fLaC", "dfLa",
            "ac-3", "dac3", "ec-3", "dec3", "ipcm", "pcmC", "fpcm", "pcmC", "alac", "alac");

    /** The flag of a sample that is not a sync sample, in the sample flags. */
    private static final long NON_SYNC = 0x10000;

    private final byte[] data;
    private final Findings findings;
    private final Kind kind;
    private final Map<Integer, Track> knownTracks;
    private boolean quickTime;

    private IsoBmffValidator(byte[] data, Findings findings, Kind kind, Map<Integer, Track> knownTracks) {
        this.data = data;
        this.findings = findings;
        this.kind = kind;
        this.knownTracks = knownTracks;
    }

    /**
     * Validates a file.
     *
     * @param data the file
     * @param findings receives the problems
     * @param kind what the file is
     * @param tracks the tracks of the initialization segment, for a media segment; empty otherwise
     * @return what validation learned of the file
     */
    public static Movie validate(byte[] data, Findings findings, Kind kind, Map<Integer, Track> tracks) {
        return new IsoBmffValidator(data, findings, kind, tracks).run();
    }

    private Movie run() {
        var boxes = IsoBmff.parse(data, findings);
        var brands = brands(boxes);
        quickTime = brands.contains("qt  ");
        var cmaf = brands.contains("cmfc") || brands.contains("cmf2");
        var tracks = new LinkedHashMap<Integer, Track>(knownTracks);
        var fragments = new ArrayList<Fragment>();
        var moovs = boxes.stream().filter(box -> box.type().equals("moov")).toList();
        var moofs = boxes.stream().filter(box -> box.type().equals("moof")).toList();
        var mdats = boxes.stream().filter(box -> box.type().equals("mdat")).toList();
        switch (kind) {
            case FILE, INIT_SEGMENT -> {
                topLevelOrder(boxes, "ftyp");
                // a HEIF still image (ISO/IEC 23008-12) describes its items in a top-level meta, without a movie
                var still = kind == Kind.FILE && moovs.isEmpty() && IsoBmff.top(boxes, "meta").isPresent();
                findings.require(moovs.size() == 1 || still, "/", moovs.size() + " moov boxes, exactly one required");
                if (kind == Kind.INIT_SEGMENT) {
                    findings.require(moofs.isEmpty() && mdats.isEmpty(), "/",
                            "an initialization segment holds no movie fragment or media data");
                }
            }
            case MEDIA_SEGMENT -> {
                findings.require(moovs.isEmpty(), "/", "a media segment holds no moov");
                findings.require(!moofs.isEmpty(), "/", "a media segment holds at least one moof");
                if (!boxes.isEmpty()) {
                    var first = boxes.getFirst().type();
                    findings.require(Set.of("styp", "sidx", "moof", "emsg", "prft", "free", "skip").contains(first),
                            "/" + first, "a media segment starts with styp, sidx, emsg, prft or moof");
                }
            }
        }
        if (moovs.size() == 1) {
            movie(moovs.getFirst(), tracks, mdats);
            if (kind == Kind.INIT_SEGMENT) {
                findings.require(moovs.getFirst().child("mvex").isPresent(), "/moov",
                        "an initialization segment's moov holds an mvex");
            }
        }
        if (!moofs.isEmpty()) {
            if (kind == Kind.FILE) {
                findings.require(moovs.size() == 1 && moovs.getFirst().child("mvex").isPresent(), "/moov",
                        "a file with movie fragments declares them with an mvex");
            }
            fragments(boxes, tracks, fragments, cmaf);
        }
        for (var box : boxes) {
            switch (box.type()) {
                case "sidx" -> segmentIndex(boxes, box, tracks, fragments);
                case "mfra" -> randomAccess(boxes, box, tracks);
                default -> {
                }
            }
        }
        return new Movie(boxes, brands, Map.copyOf(tracks), List.copyOf(fragments));
    }

    private List<String> brands(List<Box> boxes) {
        var brands = new ArrayList<String>();
        for (var type : List.of("ftyp", "styp")) {
            var box = IsoBmff.top(boxes, type);
            if (box.isPresent()) {
                var b = box.get();
                var where = "/" + type;
                if (b.payloadSize() < 8 || (b.payloadSize() - 8) % 4 != 0) {
                    findings.add(where, "size " + b.size() + " is not 16 plus 4 per compatible brand");
                    continue;
                }
                brands.add(fourcc(data, b.payload()));
                for (var offset = b.payload() + 8; offset < b.end(); offset += 4) {
                    brands.add(fourcc(data, offset));
                }
                return brands;
            }
        }
        return brands;
    }

    private void topLevelOrder(List<Box> boxes, String first) {
        var significant = boxes.stream().filter(box -> !Set.of("free", "skip", "wide", "pdin").contains(box.type()))
                .toList();
        if (significant.isEmpty()) {
            findings.add("/", "no boxes");
            return;
        }
        findings.require(significant.getFirst().type().equals(first), "/" + significant.getFirst().type(),
                "the first box is " + significant.getFirst().type() + ", not " + first);
        findings.require(boxes.stream().filter(box -> box.type().equals(first)).count() == 1, "/" + first,
                "exactly one " + first + " box");
    }

    private void unique(Box parent, String where, String... types) {
        for (var type : types) {
            var count = parent.all(type).size();
            findings.require(count <= 1, where + "/" + type, count + " boxes where at most one is allowed");
        }
    }

    private boolean required(Box parent, String where, String... types) {
        var all = true;
        for (var type : types) {
            var count = parent.all(type).size();
            if (count != 1) {
                findings.add(where + "/" + type, count + " boxes where exactly one is required");
                all = false;
            }
        }
        return all;
    }

    private void fullBoxSize(Box box, String where, long expected) {
        findings.require(box.payloadSize() == expected, where, "size " + box.size() + ", expected "
                + (expected + box.header()) + " for version " + version(data, box));
    }

    private void movie(Box moov, Map<Integer, Track> tracks, List<Box> mdats) {
        var where = "/moov";
        if (!required(moov, where, "mvhd")) {
            return;
        }
        var mvhd = moov.child("mvhd").orElseThrow();
        var mvhdVersion = version(data, mvhd);
        fullBoxSize(mvhd, where + "/mvhd", mvhdVersion == 1 ? 112 : 100);
        if (mvhd.payloadSize() < (mvhdVersion == 1 ? 112 : 100)) {
            return;
        }
        var movieTimescale = u32(data, mvhd.payload() + (mvhdVersion == 1 ? 20 : 12));
        var movieDuration = mvhdVersion == 1 ? s64(data, mvhd.payload() + 24) : u32(data, mvhd.payload() + 16);
        var nextTrackId = u32(data, mvhd.end() - 4);
        findings.require(movieTimescale > 0, where + "/mvhd", "timescale 0");
        var traks = moov.all("trak");
        findings.require(!traks.isEmpty(), where, "no trak");
        var mvex = moov.child("mvex");
        unique(moov, where, "mvex", "iods", "meta");
        var fragmented = mvex.isPresent();
        var trexes = new HashMap<Integer, Box>();
        if (fragmented) {
            var mvexWhere = where + "/mvex";
            unique(mvex.get(), mvexWhere, "mehd");
            for (var trex : mvex.get().all("trex")) {
                fullBoxSize(trex, mvexWhere + "/trex", 24);
                if (trex.payloadSize() >= 24) {
                    var id = (int) u32(data, trex.payload() + 4);
                    findings.require(trexes.put(id, trex) == null, mvexWhere + "/trex", "two trex for track " + id);
                }
            }
        }
        var ids = new HashSet<Integer>();
        var enabled = false;
        var longest = 0L;
        var allSamples = new ArrayList<long[]>();
        for (var trak : traks) {
            var track = track(trak, movieTimescale, fragmented, trexes, mdats, allSamples);
            if (track == null) {
                continue;
            }
            findings.require(track.id > 0, where + "/trak", "track id 0");
            findings.require(ids.add(track.id), where + "/trak", "duplicate track id " + track.id);
            findings.require(track.id < nextTrackId, where + "/mvhd", "next_track_ID " + nextTrackId
                    + " is not above track id " + track.id);
            if (fragmented) {
                findings.require(trexes.containsKey(track.id), where + "/mvex", "no trex for track " + track.id);
            }
            enabled |= track.enabled;
            longest = Math.max(longest, track.presentationDuration);
            tracks.put(track.id, track.summary);
        }
        findings.require(enabled || traks.isEmpty(), where, "no track is enabled");
        if (!fragmented && longest > 0) {
            findings.require(Math.abs(movieDuration - longest) <= 1, where + "/mvhd", "duration " + movieDuration
                    + " differs from the longest track's " + longest);
        }
        for (var id : trexes.keySet()) {
            findings.require(ids.contains(id), where + "/mvex/trex", "trex of unknown track " + id);
        }
        allSamples.sort((a, b) -> Long.compare(a[0], b[0]));
        for (var index = 1; index < allSamples.size(); index++) {
            if (allSamples.get(index)[0] < allSamples.get(index - 1)[1]) {
                findings.add(where, "the samples at " + allSamples.get(index - 1)[0] + " and "
                        + allSamples.get(index)[0]
                        + " overlap");
                break;
            }
        }
    }

    /** The parsed facts of one {@code trak}. */
    private record TrackFacts(int id, boolean enabled, long presentationDuration, Track summary) {
    }

    private TrackFacts track(Box trak, long movieTimescale, boolean fragmented, Map<Integer, Box> trexes,
                             List<Box> mdats, List<long[]> allSamples) {
        var where = "/moov/trak@" + trak.offset();
        if (!required(trak, where, "tkhd", "mdia")) {
            return null;
        }
        unique(trak, where, "edts", "tref", "meta", "udta");
        var tkhd = trak.child("tkhd").orElseThrow();
        var tkhdVersion = version(data, tkhd);
        fullBoxSize(tkhd, where + "/tkhd", tkhdVersion == 1 ? 96 : 84);
        if (tkhd.payloadSize() < (tkhdVersion == 1 ? 96 : 84)) {
            return null;
        }
        var trackId = (int) u32(data, tkhd.payload() + (tkhdVersion == 1 ? 20 : 12));
        var trackDuration = tkhdVersion == 1 ? s64(data, tkhd.payload() + 28) : u32(data, tkhd.payload() + 20);
        var volume = u16(data, tkhd.payload() + (tkhdVersion == 1 ? 48 : 36));
        where = "/moov/trak[" + trackId + "]";
        var mdia = trak.child("mdia").orElseThrow();
        if (!required(mdia, where + "/mdia", "mdhd", "hdlr", "minf")) {
            return new TrackFacts(trackId, (flags(data, tkhd) & 1) != 0, trackDuration, null);
        }
        var mdhd = mdia.child("mdhd").orElseThrow();
        var mdhdVersion = version(data, mdhd);
        fullBoxSize(mdhd, where + "/mdhd", mdhdVersion == 1 ? 36 : 24);
        if (mdhd.payloadSize() < (mdhdVersion == 1 ? 36 : 24)) {
            return new TrackFacts(trackId, (flags(data, tkhd) & 1) != 0, trackDuration, null);
        }
        var timescale = u32(data, mdhd.payload() + (mdhdVersion == 1 ? 20 : 12));
        var mediaDuration = mdhdVersion == 1 ? s64(data, mdhd.payload() + 24) : u32(data, mdhd.payload() + 16);
        var language = u16(data, mdhd.payload() + (mdhdVersion == 1 ? 32 : 20));
        findings.require(timescale > 0, where + "/mdhd", "timescale 0");
        findings.require(quickTime || (language & 0x8000) == 0, where + "/mdhd", "the pad bit of the language is set");
        var hdlr = mdia.child("hdlr").orElseThrow();
        findings.require(hdlr.payloadSize() >= 24, where + "/hdlr", "shorter than 24 bytes of payload");
        var handler = hdlr.payloadSize() >= 12 ? fourcc(data, hdlr.payload() + 8) : "????";
        if (!quickTime && hdlr.payloadSize() >= 24) {
            var terminated = false;
            for (var offset = hdlr.payload() + 24; offset < hdlr.end(); offset++) {
                terminated |= data[(int) offset] == 0;
            }
            findings.require(terminated, where + "/hdlr", "the name is not null-terminated");
        }
        if (handler.equals("soun")) {
            findings.require(volume != 0, where + "/tkhd", "an audio track with volume 0");
        } else if (!quickTime) {
            findings.require(volume == 0, where + "/tkhd", "a " + handler + " track with a volume");
        }
        var minf = mdia.child("minf").orElseThrow();
        var minfWhere = where + "/minf";
        if (handler.equals("vide")) {
            if (required(minf, minfWhere, "vmhd")) {
                var vmhd = minf.child("vmhd").orElseThrow();
                fullBoxSize(vmhd, minfWhere + "/vmhd", 12);
                findings.require(flags(data, vmhd) == 1, minfWhere + "/vmhd", "flags " + flags(data, vmhd)
                        + ", not 1");
            }
        } else if (handler.equals("soun") && required(minf, minfWhere, "smhd")) {
            fullBoxSize(minf.child("smhd").orElseThrow(), minfWhere + "/smhd", 8);
        }
        // QuickTime's base media information of a text track: a 36-byte display matrix
        minf.child("gmhd").flatMap(gmhd -> gmhd.child("text")).ifPresent(text -> findings.require(
                text.payloadSize() == 36, minfWhere + "/gmhd/text", "a base media text atom of " + text.size()
                        + " bytes, not 44"));
        var drefCount = 1L;
        if (required(minf, minfWhere, "dinf", "stbl")) {
            var dinf = minf.child("dinf").orElseThrow();
            if (required(dinf, minfWhere + "/dinf", "dref")) {
                var dref = dinf.child("dref").orElseThrow();
                drefCount = dref.payloadSize() >= 8 ? u32(data, dref.payload() + 4) : 0;
                findings.require(drefCount >= 1 && drefCount == dref.children().size(), minfWhere + "/dinf/dref",
                        "entry count " + drefCount + " for " + dref.children().size() + " entries");
                for (var entry : dref.children()) {
                    if ((entry.type().equals("url ") || entry.type().equals("alis")) && entry.payloadSize() >= 4
                            && (flags(data, entry) & 1) == 0) {
                        findings.require(entry.payloadSize() > 4, minfWhere + "/dinf/dref/" + entry.type(),
                                "an external data reference without a location");
                    }
                }
            }
        } else {
            return new TrackFacts(trackId, (flags(data, tkhd) & 1) != 0, trackDuration, null);
        }
        var stbl = minf.child("stbl").orElseThrow();
        var stblWhere = minfWhere + "/stbl";
        unique(stbl, stblWhere, "ctts", "stss", "stps", "sdtp", "cslg");
        var sampleEntry = "????";
        var codecs = "";
        var width = 0;
        var height = 0;
        if (required(stbl, stblWhere, "stsd")) {
            var stsd = stbl.child("stsd").orElseThrow();
            var count = stsd.payloadSize() >= 8 ? u32(data, stsd.payload() + 4) : 0;
            findings.require(count >= 1 && count == stsd.children().size(), stblWhere + "/stsd",
                    "entry count " + count + " for " + stsd.children().size() + " entries");
            for (var entry : stsd.children()) {
                sampleEntry(entry, handler, drefCount, stblWhere + "/stsd/" + entry.type());
            }
            if (!stsd.children().isEmpty()) {
                var first = stsd.children().getFirst();
                sampleEntry = first.type();
                codecs = SampleEntryCodecs.of(data, first, handler);
                if (handler.equals("vide") && first.size() >= 8 + 78) {
                    width = u16(data, first.offset() + 32);
                    height = u16(data, first.offset() + 34);
                }
            }
        }
        var samples = sampleTable(stbl, stblWhere, timescale, mediaDuration, fragmented, mdats, allSamples);
        var trex = trexes.get(trackId);
        var summary = new Track(trackId, handler, timescale, sampleEntry, codecs,
                trex != null ? u32(data, trex.payload() + 12) : 0, trex != null ? u32(data, trex.payload() + 16) : 0,
                trex != null ? u32(data, trex.payload() + 20) : 0, width, height,
                trak.child("edts").map(this::editOffset).orElse(0L));
        var expectedTrackDuration = timescale > 0 ? Math.round((double) mediaDuration * movieTimescale / timescale) : 0;
        var edts = trak.child("edts");
        var repeating = false;
        if (edts.isPresent()) {
            expectedTrackDuration = editList(edts.get(), where + "/edts", mediaDuration);
            repeating = edts.get().child("elst").filter(elst -> elst.payloadSize() >= 4)
                    .map(elst -> (flags(data, elst) & 1) != 0).orElse(false);
        }
        if (!fragmented && samples > 0 && repeating) {
            // a repeating edit list (ISO/IEC 14496-12 8.6.6) plays its edits again until the track duration, which
            // is a whole number of plays or, for endless repetition, indefinite (all ones)
            var indefinite = trackDuration == 0xFFFFFFFFL || trackDuration == -1L;
            var plays = expectedTrackDuration > 0 ? Math.round((double) trackDuration / expectedTrackDuration) : 0;
            findings.require(indefinite || plays >= 1 && Math.abs(trackDuration - plays * expectedTrackDuration)
                    <= plays, where + "/tkhd", "duration " + trackDuration + " is not a whole number of plays of"
                    + " the repeating edit duration " + expectedTrackDuration + " in the movie timescale");
        } else if (!fragmented && samples > 0) {
            findings.require(Math.abs(trackDuration - expectedTrackDuration) <= 1, where + "/tkhd", "duration "
                    + trackDuration + " differs from the media or edit duration " + expectedTrackDuration
                    + " in the movie timescale");
        }
        return new TrackFacts(trackId, (flags(data, tkhd) & 1) != 0, trackDuration, summary);
    }

    /** Returns the media time of the first edit, when it is not empty, or 0. */
    private long editOffset(Box edts) {
        var elst = edts.child("elst");
        if (elst.isEmpty() || elst.get().payloadSize() < 8 || u32(data, elst.get().payload() + 4) == 0) {
            return 0;
        }
        var v1 = version(data, elst.get()) == 1;
        var at = elst.get().payload() + 8;
        if (elst.get().payloadSize() < 8 + (v1 ? 20 : 12)) {
            return 0;
        }
        var mediaTime = v1 ? s64(data, at + 8) : s32(data, at + 4);
        return Math.max(0, mediaTime);
    }

    private long editList(Box edts, String where, long mediaDuration) {
        var elsts = edts.all("elst");
        findings.require(elsts.size() == 1, where, elsts.size() + " elst boxes, exactly one expected");
        if (elsts.isEmpty()) {
            return 0;
        }
        var elst = elsts.getFirst();
        var v1 = version(data, elst) == 1;
        var count = elst.payloadSize() >= 8 ? u32(data, elst.payload() + 4) : 0;
        var entry = v1 ? 20 : 12;
        if (elst.payloadSize() != 8 + count * entry) {
            findings.add(where + "/elst", "size " + elst.size() + " for " + count + " entries");
            return 0;
        }
        var total = 0L;
        for (var index = 0; index < count; index++) {
            var offset = elst.payload() + 8 + (long) index * entry;
            var segment = v1 ? s64(data, offset) : u32(data, offset);
            var mediaTime = v1 ? s64(data, offset + 8) : s32(data, offset + 4);
            findings.require(mediaTime >= -1, where + "/elst", "media time " + mediaTime);
            findings.require(mediaTime == -1 || mediaTime <= mediaDuration || mediaDuration == 0, where + "/elst",
                    "media time " + mediaTime + " past the media duration " + mediaDuration);
            total += segment;
        }
        return total;
    }

    private void sampleEntry(Box entry, String handler, long drefCount, String where) {
        if (entry.size() < 16) {
            findings.add(where, "a sample entry shorter than 16 bytes");
            return;
        }
        var reference = u16(data, entry.offset() + 14);
        findings.require(reference >= 1 && reference <= drefCount, where, "data reference index " + reference
                + " outside 1.." + drefCount);
        var type = entry.type().equals("encv") || entry.type().equals("enca") ? original(entry) : entry.type();
        if (handler.equals("vide") && entry.size() >= 8 + 78) {
            var width = u16(data, entry.offset() + 32);
            var height = u16(data, entry.offset() + 34);
            findings.require(width > 0 && height > 0, where, "picture size " + width + "x" + height);
            findings.require(u16(data, entry.offset() + 82) == 0x18 || quickTime, where, "depth "
                    + u16(data, entry.offset() + 82) + ", not 0x18");
            var config = VISUAL_CONFIG.get(type);
            if (config != null && required(entry, where, config)) {
                configuration(entry.child(config).orElseThrow(), where + "/" + config);
            }
            unique(entry, where, "pasp", "clap", "colr", "btrt");
        } else if (handler.equals("soun") && entry.size() >= 8 + 28) {
            var channels = u16(data, entry.offset() + 24);
            var rate = u32(data, entry.offset() + 32) >>> 16;
            var version = u16(data, entry.offset() + 16);
            findings.require(channels > 0 || version == 2, where, "channel count 0");
            // a rate above 16.16 is left to the codec configuration, the srat box or the media timescale
            var configured = entry.children().stream().anyMatch(child -> Set.of("esds", "dOps", "dfLa", "dac3",
                    "dec3", "alac", "pcmC", "srat", "wave").contains(child.type()));
            findings.require(rate > 0 || version == 2 || configured, where, "sample rate 0");
            entry.child("chnl").ifPresent(chnl -> channelLayout(chnl, channels, where + "/chnl"));
            var config = AUDIO_CONFIG.get(type);
            if (config != null) {
                var holder = quickTime && entry.child(config).isEmpty() && entry.child("wave").isPresent()
                        ? entry.child("wave").get() : entry;
                if (required(holder, where, config)) {
                    configuration(holder.child(config).orElseThrow(), where + "/" + config);
                }
            }
        }
    }

    private String original(Box entry) {
        var frma = entry.find("sinf", "frma");
        return frma.isPresent() && frma.get().payloadSize() >= 4 ? fourcc(data, frma.get().payload()) : entry.type();
    }

    private void configuration(Box box, String where) {
        var p = box.payload();
        var size = box.payloadSize();
        switch (box.type()) {
            case "avcC" -> {
                if (size < 7) {
                    findings.add(where, "shorter than 7 bytes");
                    return;
                }
                findings.require(u8(data, p) == 1, where, "configuration version " + u8(data, p));
                var lengthSize = (u8(data, p + 4) & 3) + 1;
                findings.require(lengthSize != 3, where, "NAL length size 3");
                var offset = p + 5;
                var end = box.end();
                var sps = u8(data, offset++) & 0x1F;
                findings.require(sps >= 1, where, "no sequence parameter set");
                for (var kind = 0; kind < 2; kind++) {
                    var count = kind == 0 ? sps : offset < end ? u8(data, offset++) : 0;
                    for (var index = 0; index < count; index++) {
                        if (offset + 2 > end) {
                            findings.add(where, "a parameter set runs past the box");
                            return;
                        }
                        var length = u16(data, offset);
                        if (offset + 2 + length > end || length == 0) {
                            findings.add(where, "a parameter set of " + length + " bytes does not fit");
                            return;
                        }
                        var nal = u8(data, offset + 2) & 0x1F;
                        findings.require(nal == (kind == 0 ? 7 : 8), where, "parameter set NAL type " + nal);
                        offset += 2 + length;
                    }
                    if (kind == 1) {
                        findings.require(count >= 1, where, "no picture parameter set");
                    }
                }
                var profile = u8(data, p + 1);
                if (profile == 100 || profile == 110 || profile == 122 || profile == 144) {
                    // ISO/IEC 14496-15 clause 5.3.3.1: the High profiles carry the chroma format and bit depths
                    if (end - offset < 4) {
                        findings.add(where, "profile " + profile + " without the chroma format and bit depth fields");
                    } else {
                        findings.require((u8(data, offset) & 0xFC) == 0xFC && (u8(data, offset + 1) & 0xF8) == 0xF8
                                && (u8(data, offset + 2) & 0xF8) == 0xF8, where, "the reserved bits of the chroma"
                                + " format and bit depth fields are not set");
                    }
                }
            }
            case "hvcC" -> {
                findings.require(size >= 23, where, "shorter than 23 bytes");
                if (size >= 23) {
                    findings.require(u8(data, p) == 1, where, "configuration version " + u8(data, p));
                    findings.require((u8(data, p + 21) & 3) != 2, where, "NAL length size 3");
                }
            }
            case "av1C" -> {
                findings.require(size >= 4, where, "shorter than 4 bytes");
                if (size >= 4) {
                    findings.require(u8(data, p) == 0x81, where, "marker and version byte " + u8(data, p)
                            + ", not 0x81");
                }
            }
            case "vpcC" -> {
                findings.require(size >= 12 && version(data, box) == 1, where, "not a version 1 vpcC of 12 bytes");
            }
            case "dOps" -> {
                findings.require(size >= 11 && u8(data, p) == 0, where, "not a version 0 dOps of 11 bytes");
                if (size >= 11) {
                    var channels = u8(data, p + 1);
                    var family = u8(data, p + 10);
                    findings.require(channels >= 1, where, "output channel count 0");
                    // family 3 (RFC 8486) replaces the channel mapping with a demixing matrix of 16-bit gains, one
                    // per output channel and stream
                    var expected = family == 0 ? 11 : family == 3 && size >= 13
                            ? 13 + 2 * channels * (u8(data, p + 11) + u8(data, p + 12)) : 13 + channels;
                    findings.require(size == expected, where, "size " + box.size()
                            + " for channel mapping family " + family);
                }
            }
            case "dfLa" -> {
                findings.require(size >= 4 + 4 + 34, where, "shorter than a STREAMINFO block");
                if (size >= 42) {
                    findings.require((u8(data, p + 4) & 0x7F) == 0, where, "the first block is not STREAMINFO");
                    findings.require(IsoBmff.u24(data, p + 5) == 34, where, "a STREAMINFO length other than 34");
                }
            }
            case "esds" -> esds(box, where);
            case "pcmC" -> findings.require(size == 6, where, "size " + box.size() + ", not 14");
            case "dac3" -> findings.require(size == 3, where, "size " + box.size() + ", not 11");
            case "dec3" -> findings.require(size >= 5, where, "shorter than 5 bytes");
            default -> {
            }
        }
    }

    /**
     * A descriptor of ISO/IEC 14496-1.
     *
     * @param tag the tag
     * @param body the offset of its body
     * @param end the offset past it
     */
    private record Descriptor(int tag, long body, long end) {
    }

    /** Reads the descriptor at an offset, or returns {@code null} when its header or body runs past {@code limit}. */
    private Descriptor descriptor(long offset, long limit) {
        if (offset >= limit) {
            return null;
        }
        var tag = u8(data, offset++);
        var length = 0L;
        for (var i = 0; i < 4; i++) {
            if (offset >= limit) {
                return null;
            }
            var b = u8(data, offset++);
            length = length << 7 | b & 0x7F;
            if ((b & 0x80) == 0) {
                break;
            }
        }
        return offset + length > limit ? null : new Descriptor(tag, offset, offset + length);
    }

    /**
     * Checks an {@code esds} box: one ES_Descriptor (tag 3) holding a DecoderConfigDescriptor (tag 4, with an
     * optional DecoderSpecificInfo, tag 5) and the SLConfigDescriptor (tag 6) that ISO/IEC 14496-14 requires, with
     * the predefined value 2.
     */
    private void esds(Box box, String where) {
        var es = descriptor(box.payload() + 4, box.end());
        if (es == null || es.tag() != 3) {
            findings.add(where, "no ES_Descriptor");
            return;
        }
        findings.require(es.end() == box.end(), where, (box.end() - es.end()) + " bytes after the ES_Descriptor");
        var flags = u8(data, es.body() + 2);
        var offset = es.body() + 3 + ((flags & 0x80) != 0 ? 2 : 0);
        if ((flags & 0x40) != 0) {
            offset += 1 + u8(data, offset);
        }
        offset += (flags & 0x20) != 0 ? 2 : 0;
        var config = false;
        var sl = false;
        while (offset < es.end()) {
            var child = descriptor(offset, es.end());
            if (child == null) {
                findings.add(where, "a descriptor runs past the ES_Descriptor");
                return;
            }
            if (child.tag() == 4) {
                config = true;
                findings.require(child.end() - child.body() >= 13, where, "a DecoderConfigDescriptor of "
                        + (child.end() - child.body()) + " bytes");
                var inner = child.body() + 13;
                while (inner < child.end()) {
                    var info = descriptor(inner, child.end());
                    if (info == null) {
                        findings.add(where, "a descriptor runs past the DecoderConfigDescriptor");
                        break;
                    }
                    inner = info.end();
                }
            } else if (child.tag() == 6) {
                sl = true;
                findings.require(child.end() - child.body() >= 1 && u8(data, child.body()) == 2, where,
                        "an SLConfigDescriptor whose predefined value is not 2");
            }
            offset = child.end();
        }
        findings.require(config, where, "no DecoderConfigDescriptor");
        findings.require(sl, where, "no SLConfigDescriptor");
    }

    /**
     * Checks a {@code chnl} box (ISO/IEC 14496-12 clause 12.2.4) against the channel count of its sample entry: the
     * explicit speaker positions or the omitted channels map of version 0, or the version 1 layout.
     */
    private void channelLayout(Box chnl, int channels, String where) {
        var p = chnl.payload() + 4;
        var end = chnl.end();
        var version = version(data, chnl);
        if (p >= end) {
            findings.add(where, "an empty channel layout");
            return;
        }
        int structure;
        var count = channels;
        if (version == 0) {
            structure = u8(data, p++);
        } else {
            structure = u8(data, p) >> 4;
            p += 2;
        }
        if ((structure & 1) != 0) {
            if (p >= end) {
                findings.add(where, "no defined layout");
                return;
            }
            var defined = u8(data, p++);
            if (defined == 0) {
                if (version != 0) {
                    count = p < end ? u8(data, p++) : 0;
                }
                for (var i = 0; i < count; i++) {
                    if (p >= end) {
                        findings.add(where, "the speaker positions of " + count + " channels run past the box");
                        return;
                    }
                    var position = u8(data, p++);
                    if (position == 126) {
                        p += 3;
                    }
                }
            } else if (version == 0) {
                p += 8;
            } else {
                var omitted = p < end && (u8(data, p) & 1) != 0;
                p += 1 + (omitted ? 8 : 0);
            }
        }
        if ((structure & 2) != 0) {
            p++;
        }
        findings.require(p == end, where, "size " + chnl.size() + ", the layout of " + channels
                + " channels takes " + (p - chnl.offset()));
    }

    private int sampleTable(Box stbl, String where, long timescale, long mediaDuration, boolean fragmented,
                            List<Box> mdats, List<long[]> allSamples) {
        var ok = required(stbl, where, "stts", "stsc");
        var stsz = stbl.child("stsz").or(() -> stbl.child("stz2"));
        var stco = stbl.child("stco").or(() -> stbl.child("co64"));
        if (stsz.isEmpty()) {
            findings.add(where, "no stsz or stz2");
            ok = false;
        }
        if (stco.isEmpty()) {
            findings.add(where, "no stco or co64");
            ok = false;
        }
        if (!ok) {
            return 0;
        }
        var sizes = sampleSizes(stsz.get(), where + "/" + stsz.get().type());
        if (sizes == null) {
            return 0;
        }
        var count = sizes.length;
        var stts = stbl.child("stts").orElseThrow();
        var entries = u32(data, stts.payload() + 4);
        if (stts.payloadSize() != 8 + entries * 8) {
            findings.add(where + "/stts", "size " + stts.size() + " for " + entries + " entries");
            return count;
        }
        var total = 0L;
        var duration = 0L;
        for (var index = 0; index < entries; index++) {
            var samples = u32(data, stts.payload() + 8 + index * 8L);
            var delta = u32(data, stts.payload() + 12 + index * 8L);
            findings.require(delta > 0 || index == entries - 1, where + "/stts", "a sample delta of 0");
            total += samples;
            duration += samples * delta;
        }
        findings.require(total == count, where + "/stts", total + " samples, stsz has " + count);
        if (!fragmented || count > 0) {
            findings.require(duration == mediaDuration, where.replace("/minf/stbl", "/mdhd"), "duration "
                    + mediaDuration + ", the samples last " + duration);
        }
        stbl.child("ctts").ifPresent(ctts -> {
            var n = u32(data, ctts.payload() + 4);
            if (ctts.payloadSize() != 8 + n * 8) {
                findings.add(where + "/ctts", "size " + ctts.size() + " for " + n + " entries");
                return;
            }
            var sum = 0L;
            var v0 = version(data, ctts) == 0;
            for (var index = 0; index < n; index++) {
                sum += u32(data, ctts.payload() + 8 + index * 8L);
                if (v0 && s32(data, ctts.payload() + 12 + index * 8L) < 0) {
                    findings.add(where + "/ctts", "a negative composition offset in a version 0 ctts");
                    break;
                }
            }
            findings.require(sum == count, where + "/ctts", sum + " samples, stsz has " + count);
        });
        stbl.child("stss").ifPresent(stss -> {
            var n = u32(data, stss.payload() + 4);
            if (stss.payloadSize() != 8 + n * 4) {
                findings.add(where + "/stss", "size " + stss.size() + " for " + n + " entries");
                return;
            }
            var previous = 0L;
            for (var index = 0; index < n; index++) {
                var sample = u32(data, stss.payload() + 8 + index * 4L);
                if (sample <= previous || sample > count) {
                    findings.add(where + "/stss", "sync sample " + sample + " after " + previous + " of " + count);
                    break;
                }
                previous = sample;
            }
        });
        var offsets = chunkOffsets(stco.get(), where + "/" + stco.get().type());
        if (offsets == null) {
            return count;
        }
        var stsc = stbl.child("stsc").orElseThrow();
        var stscEntries = u32(data, stsc.payload() + 4);
        if (stsc.payloadSize() != 8 + stscEntries * 12) {
            findings.add(where + "/stsc", "size " + stsc.size() + " for " + stscEntries + " entries");
            return count;
        }
        var descriptions = stbl.child("stsd").map(stsd -> stsd.children().size()).orElse(0);
        var sample = 0;
        for (var index = 0; index < stscEntries; index++) {
            var base = stsc.payload() + 8 + index * 12L;
            var firstChunk = u32(data, base);
            var perChunk = u32(data, base + 4);
            var description = u32(data, base + 8);
            var nextChunk = index + 1 < stscEntries ? u32(data, base + 12) : offsets.length + 1L;
            if (index == 0) {
                findings.require(firstChunk == 1, where + "/stsc", "the first entry starts at chunk " + firstChunk);
            }
            findings.require(nextChunk > firstChunk, where + "/stsc", "first chunks do not increase");
            findings.require(perChunk > 0, where + "/stsc", "0 samples per chunk");
            findings.require(description >= 1 && description <= descriptions, where + "/stsc",
                    "sample description index " + description);
            for (var chunk = firstChunk; chunk < nextChunk && chunk <= offsets.length; chunk++) {
                var offset = offsets[(int) chunk - 1];
                for (var i = 0; i < perChunk && sample < count; i++) {
                    var end = offset + sizes[sample];
                    if (!insideMdat(mdats, offset, end)) {
                        findings.add(where, "sample " + (sample + 1) + " at " + offset + ".." + end
                                + " is outside every mdat");
                        return count;
                    }
                    if (sizes[sample] > 0) {
                        allSamples.add(new long[]{offset, end});
                    }
                    offset = end;
                    sample++;
                }
            }
        }
        findings.require(sample == count, where + "/stsc", "the chunks hold " + sample + " samples, stsz has "
                + count);
        return count;
    }

    private long[] sampleSizes(Box box, String where) {
        if (box.type().equals("stsz")) {
            if (box.payloadSize() < 12) {
                findings.add(where, "shorter than 12 bytes of payload");
                return null;
            }
            var constant = u32(data, box.payload() + 4);
            var count = u32(data, box.payload() + 8);
            if (box.payloadSize() != 12 + (constant == 0 ? count * 4 : 0)) {
                findings.add(where, "size " + box.size() + " for " + count + " samples");
                return null;
            }
            var sizes = new long[(int) count];
            for (var index = 0; index < count; index++) {
                sizes[index] = constant != 0 ? constant : u32(data, box.payload() + 12 + index * 4L);
            }
            return sizes;
        }
        var field = u8(data, box.payload() + 7);
        var count = u32(data, box.payload() + 8);
        if (field != 4 && field != 8 && field != 16) {
            findings.add(where, "field size " + field);
            return null;
        }
        var sizes = new long[(int) count];
        for (var index = 0; index < count; index++) {
            var bit = (long) index * field;
            var offset = box.payload() + 12 + bit / 8;
            sizes[index] = field == 16 ? u16(data, offset) : field == 8 ? u8(data, offset)
                    : (u8(data, offset) >> (bit % 8 == 0 ? 4 : 0)) & 0xF;
        }
        return sizes;
    }

    private long[] chunkOffsets(Box box, String where) {
        var wide = box.type().equals("co64");
        var count = u32(data, box.payload() + 4);
        if (box.payloadSize() != 8 + count * (wide ? 8 : 4)) {
            findings.add(where, "size " + box.size() + " for " + count + " chunks");
            return null;
        }
        var offsets = new long[(int) count];
        for (var index = 0; index < count; index++) {
            offsets[index] = wide ? s64(data, box.payload() + 8 + index * 8L)
                    : u32(data, box.payload() + 8 + index * 4L);
        }
        return offsets;
    }

    private static boolean insideMdat(List<Box> mdats, long from, long to) {
        for (var mdat : mdats) {
            if (from >= mdat.payload() && to <= mdat.end()) {
                return true;
            }
        }
        return false;
    }

    private void fragments(List<Box> boxes, Map<Integer, Track> tracks, List<Fragment> fragments, boolean cmaf) {
        var previousSequence = -1L;
        var nextDecodeTime = new HashMap<Integer, Long>();
        for (var index = 0; index < boxes.size(); index++) {
            var moof = boxes.get(index);
            if (!moof.type().equals("moof")) {
                continue;
            }
            var where = "/moof@" + moof.offset();
            if (!required(moof, where, "mfhd")) {
                continue;
            }
            var mfhd = moof.child("mfhd").orElseThrow();
            fullBoxSize(mfhd, where + "/mfhd", 8);
            var sequence = u32(data, mfhd.payload() + 4);
            findings.require(sequence > previousSequence, where + "/mfhd", "sequence number " + sequence
                    + " does not increase from " + previousSequence);
            previousSequence = sequence;
            var trafs = moof.all("traf");
            findings.require(!trafs.isEmpty(), where, "no traf");
            if (cmaf) {
                findings.require(trafs.size() == 1, where, "a CMAF fragment holds one traf, not " + trafs.size());
                var next = index + 1 < boxes.size() ? boxes.get(index + 1).type() : "end of file";
                findings.require(next.equals("mdat"), where, "a CMAF fragment's moof is followed by " + next
                        + ", not mdat");
            }
            var mdats = new ArrayList<Box>();
            for (var after = index + 1; after < boxes.size() && !boxes.get(after).type().equals("moof"); after++) {
                if (boxes.get(after).type().equals("mdat")) {
                    mdats.add(boxes.get(after));
                }
            }
            var dataEnd = moof.offset();
            var trackIds = new HashSet<Integer>();
            for (var traf : trafs) {
                var fragment = trackFragment(traf, where + "/traf@" + traf.offset(), moof, sequence, tracks, mdats,
                        cmaf, dataEnd, trafs.getFirst() == traf);
                if (fragment == null) {
                    continue;
                }
                dataEnd = fragment.dataEnd;
                var f = fragment.fragment;
                findings.require(trackIds.add(f.trackId()), where, "two traf of track " + f.trackId());
                fragments.add(f);
                if (f.baseDecodeTime() >= 0) {
                    var expected = nextDecodeTime.get(f.trackId());
                    if (expected != null) {
                        findings.require(expected == f.baseDecodeTime(), where + "/traf/tfdt", "base media decode time "
                                + f.baseDecodeTime() + " of track " + f.trackId() + " after fragments ending at "
                                + expected);
                    }
                    nextDecodeTime.put(f.trackId(), f.baseDecodeTime() + f.duration());
                } else {
                    nextDecodeTime.remove(f.trackId());
                }
            }
        }
    }

    /** A parsed track fragment and the end of its data. */
    private record ParsedFragment(Fragment fragment, long dataEnd) {
    }

    private ParsedFragment trackFragment(Box traf, String where, Box moof, long sequence, Map<Integer, Track> tracks,
                                         List<Box> mdats, boolean cmaf, long previousEnd, boolean first) {
        if (!required(traf, where, "tfhd")) {
            return null;
        }
        unique(traf, where, "tfdt");
        var tfhd = traf.child("tfhd").orElseThrow();
        findings.require(traf.children().getFirst().type().equals("tfhd"), where, "tfhd is not the first box");
        var flags = flags(data, tfhd);
        var expected = 8L + ((flags & 1) != 0 ? 8 : 0) + ((flags & 2) != 0 ? 4 : 0) + ((flags & 8) != 0 ? 4 : 0)
                + ((flags & 0x10) != 0 ? 4 : 0) + ((flags & 0x20) != 0 ? 4 : 0);
        if (tfhd.payloadSize() != expected) {
            findings.add(where + "/tfhd", "size " + tfhd.size() + " for flags 0x" + Integer.toHexString(flags));
            return null;
        }
        var trackId = (int) u32(data, tfhd.payload() + 4);
        var track = tracks.get(trackId);
        findings.require(track != null, where + "/tfhd", "unknown track " + trackId);
        var offset = tfhd.payload() + 8;
        long base = -1;
        if ((flags & 1) != 0) {
            base = s64(data, offset);
            offset += 8;
            findings.require(!cmaf, where + "/tfhd", "a CMAF fragment carries no base data offset");
        }
        if ((flags & 2) != 0) {
            var description = u32(data, offset);
            findings.require(description >= 1, where + "/tfhd", "sample description index 0");
            offset += 4;
        }
        var defaultDuration = track != null ? track.defaultDuration() : 0;
        var defaultSize = track != null ? track.defaultSize() : 0;
        var defaultFlags = track != null ? track.defaultFlags() : 0;
        if ((flags & 8) != 0) {
            defaultDuration = u32(data, offset);
            offset += 4;
        }
        if ((flags & 0x10) != 0) {
            defaultSize = u32(data, offset);
            offset += 4;
        }
        if ((flags & 0x20) != 0) {
            defaultFlags = u32(data, offset);
        }
        if (cmaf) {
            findings.require((flags & 0x20000) != 0, where + "/tfhd", "a CMAF fragment sets default-base-is-moof");
        }
        if (base < 0) {
            base = (flags & 0x20000) != 0 || first ? moof.offset() : previousEnd;
        }
        var baseDecodeTime = -1L;
        var tfdt = traf.child("tfdt");
        if (tfdt.isPresent()) {
            var v1 = version(data, tfdt.get()) == 1;
            fullBoxSize(tfdt.get(), where + "/tfdt", v1 ? 12 : 8);
            baseDecodeTime = v1 ? s64(data, tfdt.get().payload() + 4) : u32(data, tfdt.get().payload() + 4);
        } else if (cmaf) {
            findings.add(where, "a CMAF fragment carries a tfdt");
        }
        var duration = 0L;
        var samples = 0;
        var bytes = 0L;
        var firstSync = true;
        var syncSamples = 0;
        var dataPosition = base;
        var dataEnd = base;
        for (var trun : traf.all("trun")) {
            var trunWhere = where + "/trun@" + trun.offset();
            var trunFlags = flags(data, trun);
            var count = u32(data, trun.payload() + 4);
            var perSample = ((trunFlags & 0x100) != 0 ? 4 : 0) + ((trunFlags & 0x200) != 0 ? 4 : 0)
                    + ((trunFlags & 0x400) != 0 ? 4 : 0) + ((trunFlags & 0x800) != 0 ? 4 : 0);
            var header = 8L + ((trunFlags & 1) != 0 ? 4 : 0) + ((trunFlags & 4) != 0 ? 4 : 0);
            if (trun.payloadSize() != header + count * perSample) {
                findings.add(trunWhere, "size " + trun.size() + " for " + count + " samples and flags 0x"
                        + Integer.toHexString(trunFlags));
                continue;
            }
            if ((trunFlags & 0x4) != 0 && (trunFlags & 0x400) != 0) {
                findings.add(trunWhere, "first-sample-flags-present together with sample-flags-present");
            }
            var p = trun.payload() + 8;
            if ((trunFlags & 1) != 0) {
                dataPosition = base + s32(data, p);
                p += 4;
            } else if (cmaf) {
                findings.add(trunWhere, "a CMAF fragment's trun carries a data offset");
            }
            var firstFlags = -1L;
            if ((trunFlags & 4) != 0) {
                firstFlags = u32(data, p);
                p += 4;
            }
            var runStart = dataPosition;
            for (var index = 0; index < count; index++) {
                var sampleDuration = defaultDuration;
                var sampleSize = defaultSize;
                var sampleFlags = defaultFlags;
                if ((trunFlags & 0x100) != 0) {
                    sampleDuration = u32(data, p);
                    p += 4;
                }
                if ((trunFlags & 0x200) != 0) {
                    sampleSize = u32(data, p);
                    p += 4;
                }
                if ((trunFlags & 0x400) != 0) {
                    sampleFlags = u32(data, p);
                    p += 4;
                }
                if ((trunFlags & 0x800) != 0) {
                    p += 4;
                }
                if (index == 0 && firstFlags >= 0) {
                    sampleFlags = firstFlags;
                }
                if (samples == 0) {
                    firstSync = (sampleFlags & NON_SYNC) == 0;
                }
                if ((sampleFlags & NON_SYNC) == 0) {
                    syncSamples++;
                }
                duration += sampleDuration;
                bytes += sampleSize;
                dataPosition += sampleSize;
                samples++;
            }
            if (!insideMdat(mdats, runStart, dataPosition)) {
                findings.add(trunWhere, "the data at " + runStart + ".." + dataPosition
                        + " is outside the mdat that follows the moof");
            }
            dataEnd = Math.max(dataEnd, dataPosition);
        }
        findings.require(samples > 0 || kind == Kind.FILE, where, "a track fragment without samples");
        return new ParsedFragment(new Fragment(moof.offset(), sequence, trackId, baseDecodeTime, duration, samples,
                firstSync, bytes, syncSamples), dataEnd);
    }

    private void segmentIndex(List<Box> boxes, Box sidx, Map<Integer, Track> tracks, List<Fragment> fragments) {
        var where = "/sidx@" + sidx.offset();
        var v1 = version(data, sidx) == 1;
        var p = sidx.payload() + 4;
        var referenceId = (int) u32(data, p);
        var timescale = u32(data, p + 4);
        p += 8 + (v1 ? 16 : 8);
        var firstOffset = v1 ? s64(data, p - 8) : u32(data, p - 4);
        var count = u16(data, p + 2);
        p += 4;
        if (sidx.end() - p != count * 12L) {
            findings.add(where, "size " + sidx.size() + " for " + count + " references");
            return;
        }
        findings.require(timescale > 0, where, "timescale 0");
        var track = tracks.get(referenceId);
        findings.require(track != null, where, "reference id " + referenceId + " names no track");
        var position = sidx.end() + firstOffset;
        for (var index = 0; index < count; index++, p += 12) {
            var word = u32(data, p);
            var referenceType = (int) (word >>> 31);
            var size = word & 0x7FFFFFFF;
            var duration = u32(data, p + 4);
            var sap = u32(data, p + 8);
            var startsWithSap = (sap >>> 31) != 0;
            var at = boxAt(boxes, position);
            if (at == null) {
                findings.add(where, "reference " + index + " at " + position + " does not start at a top-level box");
                return;
            }
            if (referenceType == 1) {
                findings.require(at.type().equals("sidx"), where, "reference " + index + " to a " + at.type()
                        + ", not a sidx");
            } else {
                findings.require(Set.of("moof", "styp", "emsg", "prft").contains(at.type()), where, "reference "
                        + index + " to a " + at.type() + ", not a movie fragment");
                var end = position + size;
                var after = boxAt(boxes, end);
                findings.require(after != null || end == data.length, where, "reference " + index + " ends at " + end
                        + ", inside a box");
                if (track != null && track.timescale() > 0) {
                    var fragmentDuration = 0L;
                    Fragment firstFragment = null;
                    for (var fragment : fragments) {
                        if (fragment.trackId() == referenceId && fragment.moofOffset() >= position
                                && fragment.moofOffset() < end) {
                            fragmentDuration += fragment.duration();
                            if (firstFragment == null) {
                                firstFragment = fragment;
                            }
                        }
                    }
                    var scaled = (double) fragmentDuration * timescale / track.timescale();
                    findings.require(Math.abs(scaled - duration) <= 1, where, "reference " + index + " lasts "
                            + duration + ", its fragments " + String.format(Locale.ROOT, "%.1f", scaled));
                    if (startsWithSap && firstFragment != null) {
                        findings.require(firstFragment.firstSync(), where, "reference " + index
                                + " claims to start with a SAP, its first sample is not a sync sample");
                    }
                }
            }
            position += size;
        }
    }

    private static Box boxAt(List<Box> boxes, long position) {
        for (var box : boxes) {
            if (box.offset() == position) {
                return box;
            }
        }
        return null;
    }

    private void randomAccess(List<Box> boxes, Box mfra, Map<Integer, Track> tracks) {
        var where = "/mfra";
        findings.require(mfra.end() == data.length, where, "mfra is not the last box");
        var mfro = mfra.child("mfro");
        if (mfro.isEmpty()) {
            findings.add(where, "no mfro");
        } else {
            findings.require(mfra.children().getLast() == mfro.get(), where + "/mfro", "mfro is not the last box");
            findings.require(mfro.get().payloadSize() == 8 && u32(data, mfro.get().payload() + 4) == mfra.size(),
                    where + "/mfro", "the mfra size it records is not " + mfra.size());
        }
        for (var tfra : mfra.all("tfra")) {
            var p = tfra.payload();
            var v1 = version(data, tfra) == 1;
            var trackId = (int) u32(data, p + 4);
            findings.require(tracks.containsKey(trackId), where + "/tfra", "unknown track " + trackId);
            var lengths = u32(data, p + 8);
            var trafBytes = ((lengths >> 4) & 3) + 1;
            var trunBytes = ((lengths >> 2) & 3) + 1;
            var sampleBytes = (lengths & 3) + 1;
            var entries = u32(data, p + 12);
            var entry = (v1 ? 16 : 8) + trafBytes + trunBytes + sampleBytes;
            if (tfra.payloadSize() != 16 + entries * entry) {
                findings.add(where + "/tfra", "size " + tfra.size() + " for " + entries + " entries");
                continue;
            }
            for (var index = 0; index < entries; index++) {
                var e = p + 16 + index * entry;
                var moofOffset = v1 ? s64(data, e + 8) : u32(data, e + 4);
                var at = boxAt(boxes, moofOffset);
                if (at == null || !at.type().equals("moof")) {
                    findings.add(where + "/tfra", "entry " + index + " points at " + moofOffset + ", not at a moof");
                    break;
                }
            }
        }
    }
}
