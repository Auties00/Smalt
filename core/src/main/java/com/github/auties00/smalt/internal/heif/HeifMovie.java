package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.io.FourCc;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.io.MediaSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Describes the tracks of the {@code moov} box of a HEIF image sequence (ISO/IEC 23008-12
 * clause 7) as far as choosing and timing them needs: identifiers, handlers, the track
 * references that link an auxiliary track to its master, the properties of the first sample
 * entry, the sample count and the loop count of the edit list.
 *
 * <p>The samples themselves are read through the MP4 demuxer of {@code container.mp4}, which
 * presents {@code pict} and {@code auxv} tracks as video tracks with these identifiers.
 *
 * @param tracks the tracks in file order
 * @implNote This implementation derives the loop count as libavif's {@code avifParseTrackBox}
 * ({@code src/read.c}, BSD 2-clause) derives its repetition count, except that a track without an
 * edit list loops forever, as browsers play such sequences.
 */
public record HeifMovie(List<Track> tracks) {
    /**
     * The {@code tkhd} duration that means an indefinite track.
     */
    private static final long INDEFINITE_32 = 0xFFFFFFFFL;

    /**
     * Describes one track.
     *
     * @param id the {@code tkhd} track identifier
     * @param handler the {@code hdlr} handler type, for example {@code "pict"} or {@code "auxv"}
     * @param width the {@code tkhd} width in pixels, or {@code 0}
     * @param height the {@code tkhd} height in pixels, or {@code 0}
     * @param references the track references by type ({@code auxl}, {@code prem}, ...)
     * @param sampleEntry the type of the first sample entry, for example {@code "av01"}, or the
     * empty string
     * @param sampleProperties the boxes of the first sample entry parsed as properties
     * ({@code av1C}, {@code colr}, {@code auxi}, {@code clli}, ...)
     * @param sampleCount the number of samples the sample size box declares
     * @param loopCount how many times the sequence plays, {@code 0} meaning forever
     */
    public record Track(int id, String handler, int width, int height, Map<String, int[]> references,
                        String sampleEntry, List<HeifProperty> sampleProperties, long sampleCount, int loopCount) {
        /**
         * Returns whether the track references another with a reference type.
         *
         * @param type the reference type
         * @param target the referenced track identifier
         * @return {@code true} when the reference exists
         */
        public boolean references(String type, int target) {
            var targets = references.get(type);
            if (targets != null) {
                for (var value : targets) {
                    if (value == target) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Returns the first sample entry property of a kind.
         *
         * @param kind the property class
         * @param <T> the property type
         * @return the property, or {@code null} when absent
         */
        public <T extends HeifProperty> T property(Class<T> kind) {
            for (var property : sampleProperties) {
                if (kind.isInstance(property)) {
                    return kind.cast(property);
                }
            }
            return null;
        }
    }

    /**
     * Reads and parses the {@code moov} box of a file.
     *
     * @param source the file
     * @param file the parsed file structure, which must have a {@code moov} box
     * @param limits the bounds of the box size and of the sample counts
     * @return the movie
     * @throws MediaException.Malformed if the box structure is invalid
     * @throws MediaException.LimitExceeded if the box or a sample count exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static HeifMovie parse(MediaSource source, HeifFile file, MediaLimits limits) {
        if (!file.hasMovie()) {
            throw new MediaException.Malformed("heif: the file has no moov box");
        }
        var data = HeifFile.readPayload(source, source.size(), "moov", file.movieOffset(), file.movieSize(), limits);
        var moov = BoxCursor.strict(data, FourCc.of("moov"));
        var tracks = new ArrayList<Track>();
        while (moov.hasBox()) {
            var child = moov.nextBox();
            if (FourCc.toString(child.type()).equals("trak")) {
                tracks.add(parseTrack(child, limits));
            }
        }
        return new HeifMovie(List.copyOf(tracks));
    }

    /**
     * Parses one {@code trak} box.
     *
     * @param trak the cursor over the payload
     * @param limits the bound of the sample count
     * @return the track
     */
    private static Track parseTrack(BoxCursor trak, MediaLimits limits) {
        var id = 0;
        var width = 0;
        var height = 0;
        long trackDuration = 0;
        var tkhdSeen = false;
        var handler = "";
        var references = new LinkedHashMap<String, int[]>();
        var edits = false;
        var repeating = false;
        long segmentDuration = 0;
        var sampleEntry = "";
        List<HeifProperty> properties = List.of();
        long samples = 0;
        while (trak.hasBox()) {
            var child = trak.nextBox();
            switch (FourCc.toString(child.type())) {
                case "tkhd" -> {
                    if (tkhdSeen) {
                        throw new MediaException.Malformed("heif: duplicate tkhd box");
                    }
                    tkhdSeen = true;
                    var version = child.fullBoxHeader() >>> 24;
                    if (version == 1) {
                        child.skip(16);
                        id = (int) child.u32();
                        child.skip(4);
                        trackDuration = child.u64();
                    } else {
                        child.skip(8);
                        id = (int) child.u32();
                        child.skip(4);
                        var duration = child.u32();
                        trackDuration = duration == INDEFINITE_32 ? -1 : duration;
                    }
                    child.skip(52);
                    width = (int) (child.u32() >>> 16);
                    height = (int) (child.u32() >>> 16);
                }
                case "tref" -> {
                    while (child.hasBox()) {
                        var reference = child.nextBox();
                        var targets = new int[reference.remaining() / 4];
                        for (var i = 0; i < targets.length; i++) {
                            targets[i] = (int) reference.u32();
                        }
                        references.put(FourCc.toString(reference.type()), targets);
                    }
                }
                case "edts" -> {
                    edits = true;
                    while (child.hasBox()) {
                        var elst = child.nextBox();
                        if (!FourCc.toString(elst.type()).equals("elst")) {
                            continue;
                        }
                        var versionAndFlags = elst.fullBoxHeader();
                        repeating = (versionAndFlags & 1) != 0;
                        if (repeating) {
                            var count = elst.u32();
                            if (count != 1) {
                                throw new MediaException.Malformed("heif: a repeating edit list holds " + count + " entries");
                            }
                            segmentDuration = (versionAndFlags >>> 24) == 1 ? elst.u64() : elst.u32();
                            if (segmentDuration <= 0) {
                                throw new MediaException.Malformed("heif: a repeating edit has no duration");
                            }
                        }
                    }
                }
                case "mdia" -> {
                    while (child.hasBox()) {
                        var media = child.nextBox();
                        switch (FourCc.toString(media.type())) {
                            case "hdlr" -> {
                                media.fullBoxHeader();
                                media.u32();
                                handler = FourCc.toString(media.fourCc());
                            }
                            case "minf" -> {
                                var stbl = find(media, "stbl");
                                if (stbl != null) {
                                    while (stbl.hasBox()) {
                                        var table = stbl.nextBox();
                                        switch (FourCc.toString(table.type())) {
                                            case "stsd" -> {
                                                table.fullBoxHeader();
                                                if (table.u32() > 0 && table.hasBox()) {
                                                    var entry = table.nextBox();
                                                    sampleEntry = FourCc.toString(entry.type());
                                                    properties = sampleEntryProperties(entry);
                                                }
                                            }
                                            case "stsz", "stz2" -> {
                                                table.fullBoxHeader();
                                                table.u32();
                                                samples = table.u32();
                                                LimitChecks.checkFrames(limits, samples);
                                            }
                                            default -> {
                                            }
                                        }
                                    }
                                }
                            }
                            default -> {
                            }
                        }
                    }
                }
                default -> {
                }
            }
        }
        if (!tkhdSeen) {
            throw new MediaException.Malformed("heif: a trak box has no tkhd box");
        }
        return new Track(id, handler, width, height, Map.copyOf(references), sampleEntry, properties, samples,
                loopCount(edits, repeating, trackDuration, segmentDuration));
    }

    /**
     * Returns the first child box of a type.
     *
     * @param parent the cursor over the parent's payload; it is advanced past the child
     * @param type the child type
     * @return the child, or {@code null} when absent
     */
    private static BoxCursor find(BoxCursor parent, String type) {
        while (parent.hasBox()) {
            var child = parent.nextBox();
            if (FourCc.toString(child.type()).equals(type)) {
                return child;
            }
        }
        return null;
    }

    /**
     * Parses the child boxes of a visual sample entry as properties.
     *
     * @param entry the cursor over the sample entry payload
     * @return the properties, empty when the entry is too short to be a visual sample entry
     */
    private static List<HeifProperty> sampleEntryProperties(BoxCursor entry) {
        // ISO/IEC 14496-12 12.1.3: VisualSampleEntry has 78 bytes of fields before its boxes.
        if (entry.remaining() < 78) {
            return List.of();
        }
        entry.skip(78);
        var properties = new ArrayList<HeifProperty>();
        while (entry.hasBox()) {
            properties.add(PropertyParser.parse(entry.nextBox()));
        }
        return List.copyOf(properties);
    }

    /**
     * Derives how many times a track plays from its edit list.
     *
     * @param edits whether the track has an edit box
     * @param repeating whether the edit list repeats
     * @param trackDuration the {@code tkhd} duration, or {@code -1} when indefinite
     * @param segmentDuration the duration of the repeated edit
     * @return the number of plays, {@code 0} meaning forever
     */
    private static int loopCount(boolean edits, boolean repeating, long trackDuration, long segmentDuration) {
        if (!edits) {
            return 0;
        }
        if (!repeating) {
            return 1;
        }
        if (trackDuration < 0) {
            return 0;
        }
        var plays = trackDuration / segmentDuration + (trackDuration % segmentDuration != 0 ? 1 : 0);
        return plays <= 0 || plays > Integer.MAX_VALUE ? 0 : (int) plays;
    }
}
