package com.github.auties00.smalt.container;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sample tables of an ISO base media file as GPAC's box dump records them: the {@code *_gpac.json} files the
 * MPEG file format conformance suite publishes next to its files ({@code MP4Box -diso} turned into JSON). For each
 * track it expands the size, the decoding duration and the composition offset of every sample from the movie's
 * {@code stsz}/{@code stz2}, {@code stts} and {@code ctts} entries and from the movie fragments' {@code trun}
 * entries with their {@code tfhd} and {@code trex} defaults (ISO/IEC 14496-12 8.6, 8.7 and 8.8), so that a demuxer
 * can be checked against a parser it shares nothing with.
 */
final class GpacDump {
    /**
     * The samples of one track: track id, media timescale, and per sample its size, decoding duration, composition
     * offset and whether it is a sync sample (1), not one (0), or the dump does not say (-1).
     */
    record Track(int id, long timescale, List<long[]> samples) {
    }

    private final Map<Integer, Track> tracks = new LinkedHashMap<>();

    private GpacDump() {
    }

    /** Returns the tracks of the dumps, the later dumps continuing the earlier ones (a media segment's dump after its
     * initialization segment's). */
    static GpacDump parse(List<String> dumps) {
        var result = new GpacDump();
        var defaults = new LinkedHashMap<Integer, long[]>();
        for (var dump : dumps) {
            var file = object(object(new Json(dump).value()).get("IsoMediaFile"));
            for (var movie : list(file.get("MovieBox"))) {
                var moov = object(movie);
                for (var trak : list(moov.get("TrackBox"))) {
                    result.addTrack(object(trak));
                }
                for (var mvex : list(moov.get("MovieExtendsBox"))) {
                    for (var trex : list(object(mvex).get("TrackExtendsBox"))) {
                        var box = object(trex);
                        defaults.put((int) number(box, "@TrackID", 0),
                                new long[]{number(box, "@SampleDuration", 0), number(box, "@SampleSize", 0)});
                    }
                }
            }
            for (var moof : list(file.get("MovieFragmentBox"))) {
                for (var traf : list(object(moof).get("TrackFragmentBox"))) {
                    result.addFragment(object(traf), defaults);
                }
            }
        }
        return result;
    }

    /** {@return the track of an id, or {@code null}} */
    Track track(int id) {
        return tracks.get(id);
    }

    /** {@return the tracks, in the order of the dump} */
    List<Track> tracks() {
        return List.copyOf(tracks.values());
    }

    private void addTrack(Map<String, Object> trak) {
        var id = (int) number(object(trak.get("TrackHeaderBox")), "@TrackID", 0);
        var mdia = object(trak.get("MediaBox"));
        var timescale = number(object(mdia.get("MediaHeaderBox")), "@TimeScale", 0);
        var stbl = object(object(mdia.get("MediaInformationBox")).get("SampleTableBox"));
        var sizes = new ArrayList<Long>();
        var stsz = stbl.containsKey("SampleSizeBox") ? object(stbl.get("SampleSizeBox"))
                : stbl.containsKey("CompactSampleSizeBox") ? object(stbl.get("CompactSampleSizeBox")) : null;
        if (stsz != null) {
            var constant = number(stsz, "@ConstantSampleSize", number(stsz, "@SampleSize", 0));
            var count = number(stsz, "@SampleCount", 0);
            if (constant > 0) {
                for (var i = 0; i < count; i++) {
                    sizes.add(constant);
                }
            } else {
                for (var entry : list(stsz.get("SampleSizeEntry"))) {
                    sizes.add(number(object(entry), "@Size", 0));
                }
            }
        }
        var durations = new ArrayList<Long>();
        if (stbl.containsKey("TimeToSampleBox")) {
            for (var entry : list(object(stbl.get("TimeToSampleBox")).get("TimeToSampleEntry"))) {
                var run = object(entry);
                for (var i = 0; i < number(run, "@SampleCount", 0); i++) {
                    durations.add(number(run, "@SampleDelta", 0));
                }
            }
        }
        var offsets = new ArrayList<Long>();
        if (stbl.containsKey("CompositionOffsetBox")) {
            for (var entry : list(object(stbl.get("CompositionOffsetBox")).get("CompositionOffsetEntry"))) {
                var run = object(entry);
                for (var i = 0; i < number(run, "@SampleCount", 0); i++) {
                    offsets.add(number(run, "@CompositionOffset", 0));
                }
            }
        }
        java.util.Set<Long> sync = null;
        if (stbl.containsKey("SyncSampleBox")) {
            sync = new java.util.HashSet<>();
            for (var entry : list(object(stbl.get("SyncSampleBox")).get("SyncSampleEntry"))) {
                sync.add(number(object(entry), "@sampleNumber", number(object(entry), "@SampleNumber", 0)));
            }
            // random access points of an open GOP: the samples of a "rap " or "sync" sample group entry
            for (var group : list(stbl.get("SampleGroupBox"))) {
                var box = object(group);
                var type = String.valueOf(box.get("@grouping_type"));
                if (!type.equals("rap ") && !type.equals("sync")) {
                    continue;
                }
                var sample = 1L;
                for (var entry : list(box.get("SampleGroupBoxEntry"))) {
                    var run = object(entry);
                    var count = number(run, "@sample_count", 0);
                    if (number(run, "@group_description_index", 0) != 0) {
                        for (var i = 0; i < count; i++) {
                            sync.add(sample + i);
                        }
                    }
                    sample += count;
                }
            }
        }
        var samples = new ArrayList<long[]>();
        for (var i = 0; i < sizes.size(); i++) {
            samples.add(new long[]{sizes.get(i), i < durations.size() ? durations.get(i) : 0,
                    i < offsets.size() ? offsets.get(i) : 0, sync == null || sync.contains(i + 1L) ? 1 : 0});
        }
        tracks.put(id, new Track(id, timescale, samples));
    }

    private void addFragment(Map<String, Object> traf, Map<Integer, long[]> defaults) {
        var tfhd = object(traf.get("TrackFragmentHeaderBox"));
        var id = (int) number(tfhd, "@TrackID", 0);
        var track = tracks.get(id);
        if (track == null) {
            return;
        }
        var trex = defaults.getOrDefault(id, new long[]{0, 0});
        var duration = number(tfhd, "@SampleDuration", trex[0]);
        var size = number(tfhd, "@SampleSize", trex[1]);
        for (var trun : list(traf.get("TrackRunBox"))) {
            var run = object(trun);
            var count = number(run, "@SampleCount", 0);
            var entries = list(run.get("TrackRunEntry"));
            for (var i = 0; i < count; i++) {
                var entry = i < entries.size() ? object(entries.get(i)) : Map.<String, Object>of();
                track.samples().add(new long[]{number(entry, "@Size", size), number(entry, "@Duration", duration),
                        number(entry, "@CTSOffset", 0), number(entry, "@Sync", -1)});
            }
        }
    }

    private static long number(Map<String, Object> box, String key, long fallback) {
        var value = box.get(key);
        if (value == null) {
            return fallback;
        }
        return Long.parseLong(value.toString().trim());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (value instanceof List<?> values && !values.isEmpty()) {
            return (Map<String, Object>) values.getFirst();
        }
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<Object> list(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> values) {
            return new ArrayList<>(values);
        }
        return List.of(value);
    }

    /** A minimal JSON reader: objects keep their key order, numbers and strings are kept as text. */
    private static final class Json {
        private final String text;
        private int position;

        Json(String text) {
            this.text = text;
        }

        Object value() {
            skip();
            var c = text.charAt(position);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                default -> literal();
            };
        }

        private Map<String, Object> object() {
            var result = new LinkedHashMap<String, Object>();
            position++;
            skip();
            if (text.charAt(position) == '}') {
                position++;
                return result;
            }
            while (true) {
                skip();
                var key = string();
                skip();
                expect(':');
                var value = value();
                // a repeated key (GPAC writes one per box) collects its values in order
                var previous = result.get(key);
                if (previous == null) {
                    result.put(key, value);
                } else if (previous instanceof RepeatedList repeated) {
                    repeated.add(value);
                } else {
                    var repeated = new RepeatedList();
                    repeated.add(previous);
                    repeated.add(value);
                    result.put(key, repeated);
                }
                skip();
                if (text.charAt(position) == ',') {
                    position++;
                    continue;
                }
                expect('}');
                return result;
            }
        }

        private List<Object> array() {
            var result = new ArrayList<Object>();
            position++;
            skip();
            if (text.charAt(position) == ']') {
                position++;
                return result;
            }
            while (true) {
                result.add(value());
                skip();
                if (text.charAt(position) == ',') {
                    position++;
                    continue;
                }
                expect(']');
                return result;
            }
        }

        private String string() {
            expect('"');
            var out = new StringBuilder();
            while (true) {
                var c = text.charAt(position++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    var escaped = text.charAt(position++);
                    switch (escaped) {
                        case 'n' -> out.append('\n');
                        case 't' -> out.append('\t');
                        case 'r' -> out.append('\r');
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'u' -> {
                            out.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                            position += 4;
                        }
                        default -> out.append(escaped);
                    }
                } else {
                    out.append(c);
                }
            }
        }

        private Object literal() {
            var start = position;
            while (position < text.length() && ",}] \t\r\n".indexOf(text.charAt(position)) < 0) {
                position++;
            }
            var word = text.substring(start, position);
            return word.equals("null") ? null : word;
        }

        private void skip() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }

        private void expect(char c) {
            if (text.charAt(position) != c) {
                throw new IllegalStateException("expected '" + c + "' at " + position + " of the GPAC dump");
            }
            position++;
        }
    }

    /** The values of a key that an object repeats. */
    private static final class RepeatedList extends ArrayList<Object> {
    }
}
