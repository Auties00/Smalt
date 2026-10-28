package com.github.auties00.smalt.testkit.validate;

import com.github.auties00.smalt.testkit.Conformance;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.ls.LSInput;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks an MPEG-DASH presentation against ISO/IEC 23009-1 in the manner of the DASH-IF conformance tool: the MPD
 * against the normative XML schema (fetched through {@code tools/testdata/vectors.tsv}), the attributes the profiles
 * require, the segment addressing of every Representation ({@code SegmentTemplate} with or without a
 * {@code SegmentTimeline}, {@code SegmentList} and {@code SegmentBase} with its index), every initialization and
 * media segment (validated as ISOBMFF segments), the timeline of the segments against {@code tfdt} and the fragment
 * durations, {@code codecs}, {@code width} and {@code height} against the sample entries, the presentation duration,
 * and {@code bandwidth} with {@code minBufferTime} against the segment sizes. A dynamic MPD must declare its
 * {@code availabilityStartTime} and {@code publishTime}, an update period until it declares its duration, and list
 * only segments available at its publish time; a trick-play adaptation set (DASH-IF IOP clause 3.2.9) must name a
 * video adaptation set of its period, and its representations hold sync samples only and declare
 * {@code codingDependency="false"} and a {@code maxPlayoutRate}; text adaptation sets carry ISOBMFF text tracks.
 */
public final class DashValidator {
    /** The schema files of ISO/IEC 23009-1 and the W3C schemas it imports, by the name they are imported with. */
    private static final Map<String, String> SCHEMAS = Map.of("DASH-MPD.xsd", "dash-schema-cc941bd-DASH-MPD.xsd",
            "DASH-MPD-UP.xsd", "dash-schema-cc941bd-DASH-MPD-UP.xsd", "xlink.xsd", "w3c-xlink-2008-06.xsd",
            "xml.xsd", "w3c-xml-2001.xsd");

    /** The MPD namespace. */
    private static final String NAMESPACE = "urn:mpeg:dash:schema:mpd:2011";

    /** A template identifier, with its optional width format tag. */
    private static final Pattern IDENTIFIER = Pattern.compile("\\$(RepresentationID|Number|Bandwidth|Time|SubNumber)"
            + "(?:%0(\\d+)d)?\\$|\\$\\$");

    private static Schema schema;

    private final Findings findings;

    /** The availability start of a dynamic MPD, or {@code null}. */
    private java.time.Instant availabilityStart;

    /** The publish time of a dynamic MPD, or {@code null}. */
    private java.time.Instant publishTime;

    private DashValidator(Findings findings) {
        this.findings = findings;
    }

    /**
     * Validates a manifest with every segment it references.
     *
     * @param manifest the MPD
     * @param findings receives the problems
     */
    public static void validate(Path manifest, Findings findings) {
        new DashValidator(findings).run(manifest);
    }

    private static synchronized Schema schema() {
        if (schema == null) {
            var files = new HashMap<String, Path>();
            for (var entry : SCHEMAS.entrySet()) {
                files.put(entry.getKey(), Conformance.fetchListed(entry.getValue()));
            }
            try {
                var factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
                factory.setResourceResolver((type, namespace, publicId, systemId, baseUri) -> {
                    if (systemId == null) {
                        return null;
                    }
                    var name = systemId.substring(systemId.lastIndexOf('/') + 1);
                    var file = files.get(name);
                    if (file == null) {
                        throw new IllegalStateException("the DASH schema imports " + systemId
                                + ", which tools/testdata/vectors.tsv does not list");
                    }
                    return new LocalInput(file, systemId, publicId, baseUri);
                });
                schema = factory.newSchema(new StreamSource(files.get("DASH-MPD.xsd").toFile()));
            } catch (org.xml.sax.SAXException exception) {
                throw new IllegalStateException("the DASH schema does not load", exception);
            }
        }
        return schema;
    }

    private void run(Path manifest) {
        var where = manifest.getFileName().toString();
        var problems = new ArrayList<String>();
        try {
            var validator = schema().newValidator();
            validator.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(SAXParseException exception) {
                }

                @Override
                public void error(SAXParseException exception) {
                    problems.add("line " + exception.getLineNumber() + ": " + exception.getMessage());
                }

                @Override
                public void fatalError(SAXParseException exception) {
                    problems.add("line " + exception.getLineNumber() + ": " + exception.getMessage());
                }
            });
            validator.validate(new StreamSource(manifest.toFile()));
        } catch (org.xml.sax.SAXException | IOException exception) {
            problems.add(exception.getMessage());
        }
        problems.forEach(problem -> findings.add(where + " (schema)", problem));
        Element mpd;
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            mpd = factory.newDocumentBuilder().parse(manifest.toFile()).getDocumentElement();
        } catch (Exception exception) {
            findings.add(where, "does not parse: " + exception.getMessage());
            return;
        }
        findings.require(NAMESPACE.equals(mpd.getNamespaceURI()) && mpd.getLocalName().equals("MPD"), where,
                "the root is not an MPD of " + NAMESPACE);
        findings.require(!mpd.getAttribute("profiles").isBlank(), where, "MPD@profiles is missing");
        findings.require(!mpd.getAttribute("minBufferTime").isBlank(), where, "MPD@minBufferTime is missing");
        var type = mpd.hasAttribute("type") ? mpd.getAttribute("type") : "static";
        var presentation = seconds(mpd.getAttribute("mediaPresentationDuration"), where);
        if (type.equals("static")) {
            findings.require(presentation >= 0, where, "a static MPD without mediaPresentationDuration");
        } else {
            availabilityStart = dateTime(mpd.getAttribute("availabilityStartTime"), where);
            publishTime = dateTime(mpd.getAttribute("publishTime"), where);
            findings.require(availabilityStart != null, where, "a dynamic MPD without availabilityStartTime");
            findings.require(publishTime != null, where, "a dynamic MPD without publishTime");
            if (availabilityStart != null && publishTime != null) {
                findings.require(!publishTime.isBefore(availabilityStart), where, "publishTime before "
                        + "availabilityStartTime");
            }
            findings.require(presentation >= 0 || mpd.hasAttribute("minimumUpdatePeriod"), where, "a dynamic MPD "
                    + "with neither mediaPresentationDuration nor minimumUpdatePeriod");
            for (var timing : children(mpd, "UTCTiming")) {
                var scheme = timing.getAttribute("schemeIdUri");
                findings.require(scheme.startsWith("urn:mpeg:dash:utc:"), where, "UTCTiming scheme " + scheme);
                if (scheme.equals("urn:mpeg:dash:utc:direct:2014")) {
                    findings.require(dateTime(timing.getAttribute("value"), where) != null, where, "a direct UTCTiming "
                            + "whose value is not a date");
                }
            }
        }
        var minBuffer = Math.max(0, seconds(mpd.getAttribute("minBufferTime"), where));
        var base = manifest.toAbsolutePath().getParent();
        var longest = 0.0;
        var periods = children(mpd, "Period");
        findings.require(!periods.isEmpty(), where, "no Period");
        for (var period : periods) {
            var periodBase = baseUrl(base, period);
            var periodStart = Math.max(0, seconds(period.getAttribute("start"), where));
            var ids = new HashSet<String>();
            var videoSets = new HashSet<String>();
            for (var set : children(period, "AdaptationSet")) {
                var content = set.getAttribute("contentType");
                if (content.equals("video") || set.getAttribute("mimeType").startsWith("video/")) {
                    videoSets.add(set.getAttribute("id"));
                }
            }
            for (var set : children(period, "AdaptationSet")) {
                var setBase = baseUrl(periodBase, set);
                var representations = children(set, "Representation");
                findings.require(!representations.isEmpty(), where, "an AdaptationSet without a Representation");
                var trick = false;
                for (var property : children(set, "EssentialProperty")) {
                    if (property.getAttribute("schemeIdUri").equals("http://dashif.org/guidelines/trickmode")) {
                        trick = true;
                        findings.require(videoSets.contains(property.getAttribute("value")), where, "a trick-play "
                                + "AdaptationSet names " + property.getAttribute("value") + ", no video AdaptationSet");
                    }
                }
                if (set.getAttribute("contentType").equals("text")) {
                    findings.require(List.of("application/mp4", "text/vtt", "application/ttml+xml")
                            .contains(set.getAttribute("mimeType")), where, "a text AdaptationSet of mimeType "
                            + set.getAttribute("mimeType"));
                }
                for (var representation : representations) {
                    var id = representation.getAttribute("id");
                    var at = where + " Representation " + id;
                    findings.require(!id.isBlank() && !id.contains(" "), at, "an id that is empty or has spaces");
                    findings.require(ids.add(id), at, "a duplicate Representation id");
                    if (trick) {
                        findings.require("false".equals(attribute(representation, set, "codingDependency")), at,
                                "a trick-play Representation without codingDependency=\"false\"");
                        var rate = attribute(representation, set, "maxPlayoutRate");
                        findings.require(rate.matches("\\d+(\\.\\d+)?") && Double.parseDouble(rate) > 0, at,
                                "a trick-play Representation with maxPlayoutRate " + rate);
                    }
                    var duration = representation(representation, set, period, baseUrl(setBase, representation),
                            minBuffer, at, periodStart, trick);
                    longest = Math.max(longest, duration);
                }
            }
        }
        if (presentation >= 0 && longest > 0) {
            findings.require(Math.abs(presentation - longest) <= 0.1, where, "mediaPresentationDuration " + presentation
                    + " s, the longest Representation lasts " + longest + " s");
        }
    }

    /** Returns the duration of a Representation's segments, in seconds, after checking them. */
    private double representation(Element representation, Element set, Element period, Path base, double minBuffer,
                                  String at, double periodStart, boolean trick) {
        var bandwidth = attribute(representation, set, "bandwidth");
        findings.require(bandwidth.matches("\\d+") && Long.parseLong(bandwidth) > 0, at, "bandwidth " + bandwidth);
        var codecs = attribute(representation, set, "codecs");
        findings.require(!codecs.isBlank(), at, "no codecs on the Representation or its AdaptationSet");
        var mime = attribute(representation, set, "mimeType");
        findings.require(!mime.isBlank(), at, "no mimeType on the Representation or its AdaptationSet");
        var segments = new ArrayList<Segment>();
        Segment init = null;
        var timescale = 1L;
        var timeOffset = 0L;
        var template = inherited(representation, set, period, "SegmentTemplate");
        var list = inherited(representation, set, period, "SegmentList");
        var segmentBase = inherited(representation, set, period, "SegmentBase");
        var schemes = (template != null ? 1 : 0) + (list != null ? 1 : 0) + (segmentBase != null && template == null
                && list == null ? 1 : 0);
        findings.require(schemes == 1, at, "not exactly one of SegmentTemplate, SegmentList and SegmentBase");
        if (template != null) {
            timescale = number(template, "timescale", 1);
            var initialization = template.getAttribute("initialization");
            if (!initialization.isBlank()) {
                init = new Segment(resolve(base, substitute(initialization, representation, 0, 0, at), at), null,
                        -1, -1);
            }
            var media = template.getAttribute("media");
            findings.require(!media.isBlank(), at, "SegmentTemplate without media");
            var number = number(template, "startNumber", 1);
            var offset = number(template, "presentationTimeOffset", 0);
            timeOffset = offset;
            for (var entry : timeline(template, timescale, at)) {
                available(entry, timescale, offset, periodStart, at);
                var file = resolve(base, substitute(media, representation, number, entry[0], at), at);
                segments.add(new Segment(file, null, entry[0], entry[1]));
                number++;
            }
        } else if (list != null) {
            timescale = number(list, "timescale", 1);
            var initialization = child(list, "Initialization");
            if (initialization != null) {
                init = new Segment(resolve(base, initialization.getAttribute("sourceURL"), at),
                        range(initialization.getAttribute("range"), at), -1, -1);
            }
            var urls = children(list, "SegmentURL");
            var times = timeline(list, timescale, at);
            if (times.isEmpty() || times.size() == urls.size()) {
                for (var index = 0; index < urls.size(); index++) {
                    var url = urls.get(index);
                    var media = url.hasAttribute("media") ? resolve(base, url.getAttribute("media"), at)
                            : base;
                    var time = times.isEmpty() ? -1 : times.get(index)[0];
                    var duration = times.isEmpty() ? -1 : times.get(index)[1];
                    segments.add(new Segment(media, range(url.getAttribute("mediaRange"), at), time, duration));
                }
            } else {
                findings.add(at, "a SegmentTimeline of " + times.size() + " segments for " + urls.size()
                        + " SegmentURL elements");
            }
        } else if (segmentBase != null) {
            return indexed(segmentBase, base, codecs, representation, set, minBuffer, at);
        } else {
            return 0;
        }
        if (init == null || init.file() == null) {
            findings.add(at, "no initialization segment");
            return 0;
        }
        var initBytes = bytes(init.file(), init.range(), at);
        if (initBytes == null) {
            return 0;
        }
        var initFindings = new Findings(at);
        var movie = IsoBmffValidator.validate(initBytes, initFindings, IsoBmffValidator.Kind.INIT_SEGMENT, Map.of());
        initFindings.problems().forEach(problem -> findings.add(at + " initialization", problem));
        tracks(movie, codecs, representation, set, at);
        var total = 0.0;
        var sizes = new ArrayList<long[]>();
        var nextTime = new HashMap<Integer, Long>();
        for (var segment : segments) {
            if (segment.file() == null) {
                continue;
            }
            var bytes = bytes(segment.file(), segment.range(), at);
            if (bytes == null) {
                continue;
            }
            var segmentFindings = new Findings(at);
            var media = IsoBmffValidator.validate(bytes, segmentFindings, IsoBmffValidator.Kind.MEDIA_SEGMENT,
                    movie.tracks());
            if (trick) {
                for (var fragment : media.fragments()) {
                    segmentFindings.require(fragment.syncSamples() == fragment.samples(), "track " + fragment.trackId(),
                            "a trick-play fragment of " + fragment.samples() + " samples, " + fragment.syncSamples()
                                    + " of them sync samples");
                }
            }
            var duration = segmentDuration(media, movie.tracks(), segment, timescale, nextTime,
                    segmentFindings);
            segmentFindings.problems().forEach(problem -> findings.add(at + " " + segment.file().getFileName()
                    + (segment.range() != null ? " " + segment.range()[0] + "-" + segment.range()[1] : ""), problem));
            total += duration;
            sizes.add(new long[]{bytes.length, Math.round(duration * 1_000_000)});
        }
        bucket(Long.parseLong(bandwidth.matches("\\d+") ? bandwidth : "0"), minBuffer, sizes, at);
        if (availabilityStart == null) {
            return total;
        }
        // a dynamic MPD lists a window of segments: the presentation lasts until the end of the last one
        var end = 0.0;
        for (var segment : segments) {
            if (segment.time() >= 0) {
                end = Math.max(end, (double) (segment.time() + segment.duration() - timeOffset) / timescale);
            }
        }
        return Math.max(total, end);
    }

    /** Checks a single indexed file addressed by a {@code SegmentBase}, and returns its duration in seconds. */
    private double indexed(Element segmentBase, Path base, String codecs,
                           Element representation, Element set, double minBuffer, String at) {
        var file = base;
        if (!Files.isRegularFile(file)) {
            findings.add(at, "the BaseURL of a SegmentBase names no file: " + file);
            return 0;
        }
        var bytes = bytes(file, null, at);
        if (bytes == null) {
            return 0;
        }
        var fileFindings = new Findings(at);
        var movie = IsoBmffValidator.validate(bytes, fileFindings, IsoBmffValidator.Kind.FILE, Map.of());
        fileFindings.problems().forEach(problem -> findings.add(at + " " + file.getFileName(), problem));
        tracks(movie, codecs, representation, set, at);
        var index = range(segmentBase.getAttribute("indexRange"), at);
        var sidx = movie.boxes().stream().filter(box -> box.type().equals("sidx")).findFirst();
        if (index == null || sidx.isEmpty()) {
            findings.add(at, "a SegmentBase without an indexRange over a sidx");
        } else {
            findings.require(index[0] == sidx.get().offset() && index[1] == sidx.get().end() - 1, at, "indexRange "
                    + index[0] + "-" + index[1] + " is not the sidx at " + sidx.get().offset() + "-"
                    + (sidx.get().end() - 1));
        }
        var initialization = child(segmentBase, "Initialization");
        if (initialization != null) {
            var range = range(initialization.getAttribute("range"), at);
            var moov = movie.boxes().stream().filter(box -> box.type().equals("moov")).findFirst();
            if (range != null && moov.isPresent()) {
                findings.require(range[0] == 0 && range[1] == moov.get().end() - 1, at, "the Initialization range "
                        + range[0] + "-" + range[1] + " does not end with the moov at " + (moov.get().end() - 1));
            }
        }
        var total = 0L;
        var timescaleOfTrack = movie.tracks().values().stream().findFirst().map(IsoBmffValidator.Track::timescale)
                .orElse(1L);
        var sizes = new ArrayList<long[]>();
        for (var fragment : movie.fragments()) {
            total += fragment.duration();
            sizes.add(new long[]{fragment.bytes(), fragment.duration() * 1_000_000 / timescaleOfTrack});
        }
        var bandwidth = attribute(representation, set, "bandwidth");
        bucket(bandwidth.matches("\\d+") ? Long.parseLong(bandwidth) : 0, minBuffer, sizes, at);
        return (double) total / timescaleOfTrack;
    }

    private void tracks(IsoBmffValidator.Movie movie, String codecs, Element representation, Element set, String at) {
        findings.require(movie.tracks().size() == 1, at, movie.tracks().size() + " tracks, a Representation of"
                + " ISOBMFF segments carries one");
        for (var track : movie.tracks().values()) {
            if (!track.codecs().isEmpty() && !codecs.isBlank()) {
                findings.require(SampleEntryCodecs.agree(codecs, track.codecs()), at, "codecs " + codecs
                        + ", the sample entry is " + track.codecs());
            }
            if (track.width() > 0) {
                var width = attribute(representation, set, "width");
                var height = attribute(representation, set, "height");
                findings.require(width.equals(Integer.toString(track.width()))
                        && height.equals(Integer.toString(track.height())), at, "width and height " + width + "x"
                        + height + ", the sample entry is " + track.width() + "x" + track.height());
            }
        }
    }

    /** Checks a media segment's timeline and returns its duration in seconds. */
    private double segmentDuration(IsoBmffValidator.Movie media, Map<Integer, IsoBmffValidator.Track> tracks,
                                   Segment segment, long timescale, Map<Integer, Long> nextTime,
                                   Findings segmentFindings) {
        var duration = 0.0;
        var perTrack = new HashMap<Integer, long[]>();
        for (var fragment : media.fragments()) {
            var entry = perTrack.computeIfAbsent(fragment.trackId(), key -> new long[]{fragment.baseDecodeTime(), 0});
            entry[1] += fragment.duration();
            if (perTrack.size() == 1 && entry[1] == fragment.duration()) {
                segmentFindings.require(fragment.firstSync(), "track " + fragment.trackId(),
                        "a segment that does not start with a sync sample");
            }
        }
        for (var entry : perTrack.entrySet()) {
            var track = tracks.get(entry.getKey());
            if (track == null || track.timescale() == 0) {
                continue;
            }
            var start = entry.getValue()[0];
            var length = entry.getValue()[1];
            duration = Math.max(duration, (double) length / track.timescale());
            var previous = nextTime.get(entry.getKey());
            if (previous != null && start >= 0) {
                segmentFindings.require(previous == start, "track " + entry.getKey(), "starts at " + start
                        + ", the previous segment ended at " + previous);
            }
            nextTime.put(entry.getKey(), start + length);
            if (segment.time() >= 0 && start >= 0) {
                // S@t counts on the media timeline (tfdt) or on the presentation timeline, after the edit list's
                // skip of the initialization segment, which also shortens the first segment
                var skip = track.editOffset();
                var expected = (double) segment.time() * track.timescale() / timescale;
                segmentFindings.require(Math.abs(expected - start) <= 1 || Math.abs(expected - (start - skip)) <= 1,
                        "track " + entry.getKey(), "tfdt " + start + ", the timeline places the segment at "
                                + segment.time());
                var declared = (double) segment.duration() * track.timescale() / timescale;
                var shortened = start <= skip ? length - (skip - start) : length;
                segmentFindings.require(Math.abs(declared - length) <= 1 || Math.abs(declared - shortened) <= 1,
                        "track " + entry.getKey(), "the fragments last " + length + ", the timeline says "
                                + segment.duration());
            }
        }
        return duration;
    }

    /**
     * Checks the necessary condition of {@code bandwidth} and {@code minBufferTime}: delivered at the bandwidth from the
     * start of any segment, every segment arrives before the end of its own presentation interval.
     */
    private void bucket(long bandwidth, double minBuffer, List<long[]> sizes, String at) {
        if (bandwidth <= 0) {
            return;
        }
        for (var start = 0; start < sizes.size(); start++) {
            var bits = 0.0;
            var played = 0.0;
            for (var index = start; index < sizes.size(); index++) {
                bits += sizes.get(index)[0] * 8.0;
                played += sizes.get(index)[1] / 1e6;
                if (bits / bandwidth > minBuffer + played + 1e-6) {
                    findings.add(at, "at bandwidth " + bandwidth + " the segments from " + start + " to " + index
                            + " arrive " + String.format(Locale.ROOT, "%.3f", bits / bandwidth - minBuffer - played)
                            + " s after their presentation ends");
                    return;
                }
            }
        }
    }

    /** Checks that a dynamic MPD lists a segment only once it is available, at its publish time. */
    private void available(long[] entry, long timescale, long offset, double periodStart, String at) {
        if (availabilityStart == null || publishTime == null) {
            return;
        }
        var end = availabilityStart.toEpochMilli() / 1000.0 + periodStart
                + (double) (entry[0] + entry[1] - offset) / timescale;
        var publish = publishTime.toEpochMilli() / 1000.0;
        findings.require(end <= publish + 0.002, at, "the segment at " + entry[0] + " ends " + (end - publish)
                + " s after the publish time");
    }

    /** Parses an {@code xs:dateTime}, or returns {@code null} for none or a malformed one. */
    private java.time.Instant dateTime(String text, String at) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(text).toInstant();
        } catch (java.time.format.DateTimeParseException exception) {
            findings.add(at, "date " + text);
            return null;
        }
    }

    /** Returns the start and duration of each segment of a timeline, or of the segments of a fixed duration. */
    private List<long[]> timeline(Element scheme, long timescale, String at) {
        var segments = new ArrayList<long[]>();
        var timeline = child(scheme, "SegmentTimeline");
        if (timeline == null) {
            if (scheme.hasAttribute("duration")) {
                findings.add(at, "a fixed segment duration without a SegmentTimeline is not validated");
            }
            return segments;
        }
        var time = 0L;
        for (var s : children(timeline, "S")) {
            if (s.hasAttribute("t")) {
                var t = Long.parseLong(s.getAttribute("t"));
                findings.require(segments.isEmpty() || t == time, at, "S@t " + t + " leaves a gap or an overlap after "
                        + time);
                time = t;
            }
            var d = Long.parseLong(s.getAttribute("d"));
            findings.require(d > 0, at, "S@d " + d);
            var repeat = s.hasAttribute("r") ? Long.parseLong(s.getAttribute("r")) : 0;
            findings.require(repeat >= 0, at, "a negative S@r");
            for (var index = 0; index <= repeat; index++) {
                segments.add(new long[]{time, d});
                time += d;
            }
        }
        return segments;
    }

    private String substitute(String template, Element representation, long number, long time, String at) {
        var matcher = IDENTIFIER.matcher(template);
        var out = new StringBuilder();
        while (matcher.find()) {
            String value;
            if (matcher.group(1) == null) {
                value = "$";
            } else {
                value = switch (matcher.group(1)) {
                    case "RepresentationID" -> representation.getAttribute("id");
                    case "Number" -> Long.toString(number);
                    case "Bandwidth" -> representation.getAttribute("bandwidth");
                    case "Time" -> Long.toString(time);
                    default -> {
                        findings.add(at, "template identifier " + matcher.group());
                        yield "";
                    }
                };
                if (matcher.group(2) != null) {
                    var width = Integer.parseInt(matcher.group(2));
                    value = "0".repeat(Math.max(0, width - value.length())) + value;
                }
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private Path resolve(Path base, String uri, String at) {
        if (uri.contains("://")) {
            findings.add(at, "an absolute URL " + uri + " in a local presentation");
            return null;
        }
        var directory = Files.isDirectory(base) ? base : base.getParent();
        var file = directory.resolve(uri).normalize();
        if (!Files.isRegularFile(file)) {
            findings.add(at, uri + " does not exist");
            return null;
        }
        return file;
    }

    private Path baseUrl(Path base, Element element) {
        var url = child(element, "BaseURL");
        if (url == null) {
            return base;
        }
        var directory = Files.isDirectory(base) ? base : base.getParent();
        return directory.resolve(url.getTextContent().trim()).normalize();
    }

    private byte[] bytes(Path file, long[] range, String at) {
        try {
            var all = Files.readAllBytes(file);
            if (range == null) {
                return all;
            }
            if (range[0] < 0 || range[1] < range[0] || range[1] >= all.length) {
                findings.add(at, "range " + range[0] + "-" + range[1] + " outside the " + all.length + "-byte "
                        + file.getFileName());
                return null;
            }
            return java.util.Arrays.copyOfRange(all, (int) range[0], (int) range[1] + 1);
        } catch (IOException exception) {
            findings.add(at, "cannot read " + file + ": " + exception);
            return null;
        }
    }

    private long[] range(String text, String at) {
        if (text == null || text.isBlank()) {
            return null;
        }
        var parts = text.split("-");
        try {
            return new long[]{Long.parseLong(parts[0]), Long.parseLong(parts[1])};
        } catch (RuntimeException exception) {
            findings.add(at, "byte range " + text);
            return null;
        }
    }

    private static long number(Element element, String name, long fallback) {
        return element.hasAttribute(name) ? Long.parseLong(element.getAttribute(name)) : fallback;
    }

    private static String attribute(Element representation, Element set, String name) {
        return representation.hasAttribute(name) ? representation.getAttribute(name) : set.getAttribute(name);
    }

    private static Element inherited(Element representation, Element set, Element period, String name) {
        for (var element : List.of(representation, set, period)) {
            var found = child(element, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Element child(Element parent, String name) {
        var all = children(parent, name);
        return all.isEmpty() ? null : all.getFirst();
    }

    private static List<Element> children(Element parent, String name) {
        var found = new ArrayList<Element>();
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(node.getLocalName())) {
                found.add((Element) node);
            }
        }
        return found;
    }

    /** Parses an {@code xs:duration} of days, hours, minutes and seconds, or returns -1 for none. */
    private double seconds(String text, String at) {
        if (text == null || text.isBlank()) {
            return -1;
        }
        var matcher = Pattern.compile("P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?")
                .matcher(text);
        if (!matcher.matches() || text.equals("P") || text.endsWith("T")) {
            findings.add(at, "duration " + text);
            return -1;
        }
        var value = 0.0;
        value += matcher.group(1) != null ? Long.parseLong(matcher.group(1)) * 86400.0 : 0;
        value += matcher.group(2) != null ? Long.parseLong(matcher.group(2)) * 3600.0 : 0;
        value += matcher.group(3) != null ? Long.parseLong(matcher.group(3)) * 60.0 : 0;
        value += matcher.group(4) != null ? Double.parseDouble(matcher.group(4)) : 0;
        return value;
    }

    /**
     * A segment to check.
     *
     * @param file the file, or {@code null} when it could not be resolved
     * @param range the first and last byte, or {@code null} for the whole file
     * @param time the timeline start, in the scheme's timescale, or -1 when there is no timeline
     * @param duration the timeline duration, or -1 when there is no timeline
     */
    private record Segment(Path file, long[] range, long time, long duration) {
    }

    /** A schema file the resolver hands to the schema loader. */
    private static final class LocalInput implements LSInput {
        private final Path file;
        private String systemId;
        private String publicId;
        private String baseUri;

        LocalInput(Path file, String systemId, String publicId, String baseUri) {
            this.file = file;
            this.systemId = systemId;
            this.publicId = publicId;
            this.baseUri = baseUri;
        }

        @Override
        public Reader getCharacterStream() {
            return null;
        }

        @Override
        public void setCharacterStream(Reader characterStream) {
        }

        @Override
        public InputStream getByteStream() {
            try {
                return Files.newInputStream(file);
            } catch (IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        }

        @Override
        public void setByteStream(InputStream byteStream) {
        }

        @Override
        public String getStringData() {
            return null;
        }

        @Override
        public void setStringData(String stringData) {
        }

        @Override
        public String getSystemId() {
            return systemId;
        }

        @Override
        public void setSystemId(String systemId) {
            this.systemId = systemId;
        }

        @Override
        public String getPublicId() {
            return publicId;
        }

        @Override
        public void setPublicId(String publicId) {
            this.publicId = publicId;
        }

        @Override
        public String getBaseURI() {
            return baseUri;
        }

        @Override
        public void setBaseURI(String baseURI) {
            this.baseUri = baseURI;
        }

        @Override
        public String getEncoding() {
            return null;
        }

        @Override
        public void setEncoding(String encoding) {
        }

        @Override
        public boolean getCertifiedText() {
            return false;
        }

        @Override
        public void setCertifiedText(boolean certifiedText) {
        }
    }
}
