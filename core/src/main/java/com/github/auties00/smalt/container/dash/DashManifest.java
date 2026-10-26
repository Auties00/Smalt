package com.github.auties00.smalt.container.dash;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.internal.SegmentSource;
import com.github.auties00.smalt.container.internal.Uris;
import com.github.auties00.smalt.internal.xml.XmlReader;
import com.github.auties00.smalt.track.TrackDisposition;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a DASH media presentation description (ISO/IEC 23009-1 clause 5) into the segments of each
 * representation: its periods, adaptation sets and representations, the base URLs, and the segment addressing of
 * {@code SegmentTemplate} (with a {@code SegmentTimeline} or a segment duration), {@code SegmentList} and
 * {@code SegmentBase}, inherited from the period and the adaptation set.
 */
final class DashManifest {
    /**
     * The MPD namespace.
     */
    static final String NAMESPACE = "urn:mpeg:dash:schema:mpd:2011";

    /**
     * The most segments one representation may list.
     */
    private static final int MAX_SEGMENTS = 1 << 16;

    /**
     * The scheme of the trick-play property, whose adaptation sets are read only by trick play.
     */
    private static final String TRICK_MODE = "http://dashif.org/guidelines/trickmode";

    /**
     * An identifier of a segment template, with its optional width format tag.
     */
    private static final Pattern IDENTIFIER = Pattern.compile("\\$(RepresentationID|Number|Bandwidth|Time|SubNumber)"
            + "(?:%0(\\d+)d)?\\$|\\$\\$");

    /**
     * An {@code xs:duration} of days, hours, minutes and seconds.
     */
    private static final Pattern DURATION = Pattern.compile(
            "P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d*)?)S)?)?");

    private DashManifest() {
        throw new AssertionError();
    }

    /**
     * One representation to read.
     *
     * @param id the representation identifier
     * @param mimeType the MIME type of its segments, lower case, empty when undeclared
     * @param parts its initialization segment and media segments
     * @param offsetMicros the presentation time of media time zero: the period start less the presentation time
     * offset
     * @param language the language of the adaptation set or representation, or {@code null}
     * @param label the label, or {@code null}
     * @param dispositions the dispositions its roles and accessibility descriptors give
     */
    record Representation(String id, String mimeType, List<SegmentSource.Part> parts, long offsetMicros,
                          String language, String label, Set<TrackDisposition> dispositions) {
    }

    /**
     * A manifest.
     *
     * @param representations the representations to read, in document order
     * @param durationMicros the presentation duration, or -1 when undeclared
     */
    record Manifest(List<Representation> representations, long durationMicros) {
    }

    /**
     * Parses a manifest.
     *
     * @param text the manifest text
     * @return the manifest
     * @throws MediaException.Malformed if the manifest is not an MPD or its addressing is malformed
     * @throws MediaException.Unsupported if a live manifest addresses segments by duration without a publish time
     * @throws MediaException.LimitExceeded if a representation lists too many segments
     */
    static Manifest parse(String text) {
        try {
            return parse(XmlReader.parse(text));
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new MediaException.Malformed("a DASH manifest value is out of range", exception);
        }
    }

    /**
     * Parses the root element of a manifest.
     *
     * @param mpd the root element
     * @return the manifest
     */
    private static Manifest parse(XmlReader.Element mpd) {
        if (!mpd.name().equals("MPD") || !mpd.namespace().equals(NAMESPACE) && !mpd.namespace().isEmpty()) {
            throw new MediaException.Malformed("not a DASH manifest: the root element is " + mpd.name());
        }
        var dynamic = "dynamic".equals(mpd.attribute("", "type"));
        var presentation = duration(mpd.attribute("", "mediaPresentationDuration"));
        var availability = time(mpd.attribute("", "availabilityStartTime"));
        var publish = time(mpd.attribute("", "publishTime"));
        var base = baseUrl("", mpd);
        var periods = children(mpd, "Period");
        var representations = new ArrayList<Representation>();
        var start = 0L;
        for (var index = 0; index < periods.size(); index++) {
            var period = periods.get(index);
            var declared = duration(period.attribute("", "start"));
            if (declared >= 0) {
                start = declared;
            }
            var length = duration(period.attribute("", "duration"));
            if (length < 0 && index + 1 < periods.size()) {
                var next = duration(periods.get(index + 1).attribute("", "start"));
                length = next >= 0 ? next - start : -1;
            }
            if (length < 0 && presentation >= 0) {
                length = presentation - start;
            }
            var available = -1L;
            if (dynamic && length < 0 && availability != null && publish != null) {
                available = Math.max(0, (publish.toEpochMilli() - availability.toEpochMilli()) * 1000 - start);
            }
            var periodBase = baseUrl(base, period);
            for (var set : children(period, "AdaptationSet")) {
                if (!children(set, "EssentialProperty").isEmpty()) {
                    continue;
                }
                var setBase = baseUrl(periodBase, set);
                for (var element : children(set, "Representation")) {
                    representations.add(representation(element, set, period, baseUrl(setBase, element), start,
                            length, available));
                }
            }
            if (length >= 0) {
                start += length;
            }
        }
        return new Manifest(representations, presentation);
    }

    /**
     * Describes one representation.
     *
     * @param element the representation
     * @param set its adaptation set
     * @param period its period
     * @param base its base URL
     * @param periodStart the period start, in microseconds
     * @param periodLength the period duration, or -1 when unknown
     * @param available the media time a live period has reached, or -1
     * @return the representation
     */
    private static Representation representation(XmlReader.Element element, XmlReader.Element set,
                                                 XmlReader.Element period, String base, long periodStart,
                                                 long periodLength, long available) {
        var id = element.attribute("", "id");
        if (id == null) {
            throw new MediaException.Malformed("a DASH Representation without an id");
        }
        var mime = attribute(element, set, "mimeType");
        var levels = List.of(element, set, period);
        var template = first(levels, "SegmentTemplate");
        var list = first(levels, "SegmentList");
        var segmentBase = first(levels, "SegmentBase");
        var parts = new ArrayList<SegmentSource.Part>();
        var pto = 0L;
        var timescale = 1L;
        if (template != null) {
            timescale = timescale(inherited(levels, "SegmentTemplate", "timescale"));
            pto = number(inherited(levels, "SegmentTemplate", "presentationTimeOffset"), 0);
            template(levels, element, base, timescale, pto, periodLength, available, parts);
        } else if (list != null) {
            timescale = timescale(inherited(levels, "SegmentList", "timescale"));
            pto = number(inherited(levels, "SegmentList", "presentationTimeOffset"), 0);
            var initialization = firstChild(levels, "SegmentList", "Initialization");
            if (initialization != null) {
                parts.add(part(base, initialization.attribute("", "sourceURL"), initialization.attribute("", "range")));
            }
            for (var url : children(list, "SegmentURL")) {
                parts.add(part(base, url.attribute("", "media"), url.attribute("", "mediaRange")));
                if (parts.size() > MAX_SEGMENTS) {
                    throw new MediaException.LimitExceeded("a DASH representation of more than " + MAX_SEGMENTS
                            + " segments");
                }
            }
        } else {
            if (segmentBase != null) {
                timescale = timescale(inherited(levels, "SegmentBase", "timescale"));
                pto = number(inherited(levels, "SegmentBase", "presentationTimeOffset"), 0);
            }
            parts.add(SegmentSource.Part.of(base));
        }
        var language = element.attribute("", "lang") != null ? element.attribute("", "lang") : set.attribute("", "lang");
        var labels = children(element, "Label").isEmpty() ? children(set, "Label") : children(element, "Label");
        var dispositions = new HashSet<TrackDisposition>();
        for (var role : children(set, "Role")) {
            if ("urn:mpeg:dash:role:2011".equals(role.attribute("", "schemeIdUri"))) {
                switch (String.valueOf(role.attribute("", "value"))) {
                    case "main" -> dispositions.add(TrackDisposition.DEFAULT);
                    case "forced-subtitle" -> dispositions.add(TrackDisposition.FORCED);
                    case "caption" -> dispositions.add(TrackDisposition.HEARING_IMPAIRED);
                    case "description" -> dispositions.add(TrackDisposition.VISUAL_IMPAIRED);
                    case "commentary" -> dispositions.add(TrackDisposition.COMMENTARY);
                    case "dub" -> dispositions.add(TrackDisposition.DUB);
                    default -> {
                    }
                }
            }
        }
        for (var accessibility : children(set, "Accessibility")) {
            if ("urn:tva:metadata:cs:AudioPurposeCS:2007".equals(accessibility.attribute("", "schemeIdUri"))
                    && "1".equals(accessibility.attribute("", "value"))) {
                dispositions.add(TrackDisposition.VISUAL_IMPAIRED);
            }
        }
        var offset = periodStart - Math.multiplyExact(pto, 1_000_000L) / timescale;
        return new Representation(id, mime == null ? "" : mime.toLowerCase(Locale.ROOT), parts, offset, language,
                labels.isEmpty() ? null : labels.getFirst().text().strip(), dispositions);
    }

    /**
     * Lists the segments of a {@code SegmentTemplate}.
     *
     * @param levels the representation, its adaptation set and its period
     * @param element the representation
     * @param base its base URL
     * @param timescale the template timescale
     * @param pto the presentation time offset
     * @param periodLength the period duration in microseconds, or -1
     * @param available the media time a live period has reached, or -1
     * @param parts receives the parts
     */
    private static void template(List<XmlReader.Element> levels, XmlReader.Element element, String base,
                                 long timescale, long pto, long periodLength, long available,
                                 List<SegmentSource.Part> parts) {
        var media = inherited(levels, "SegmentTemplate", "media");
        var initialization = inherited(levels, "SegmentTemplate", "initialization");
        var number = number(inherited(levels, "SegmentTemplate", "startNumber"), 1);
        var endNumber = number(inherited(levels, "SegmentTemplate", "endNumber"), Long.MAX_VALUE);
        if (initialization != null) {
            parts.add(SegmentSource.Part.of(Uris.resolve(base, substitute(initialization, element, 0, 0))));
        } else {
            var child = firstChild(levels, "SegmentTemplate", "Initialization");
            if (child != null) {
                parts.add(part(base, child.attribute("", "sourceURL"), child.attribute("", "range")));
            }
        }
        if (media == null) {
            throw new MediaException.Malformed("a DASH SegmentTemplate without media");
        }
        var timeline = firstChild(levels, "SegmentTemplate", "SegmentTimeline");
        var end = periodLength >= 0 ? pto + Math.multiplyExact(periodLength, timescale) / 1_000_000 : Long.MAX_VALUE;
        if (timeline != null) {
            var time = 0L;
            var entries = children(timeline, "S");
            for (var index = 0; index < entries.size(); index++) {
                var s = entries.get(index);
                if (s.attribute("", "t") != null) {
                    time = number(s.attribute("", "t"), 0);
                }
                var duration = number(s.attribute("", "d"), -1);
                if (duration <= 0) {
                    throw new MediaException.Malformed("a DASH segment timeline entry without a positive duration");
                }
                var repeat = signed(s.attribute("", "r"));
                if (repeat < 0) {
                    var stop = index + 1 < entries.size() && entries.get(index + 1).attribute("", "t") != null
                            ? number(entries.get(index + 1).attribute("", "t"), 0) : end;
                    if (stop == Long.MAX_VALUE) {
                        throw new MediaException.Unsupported("dash", "a segment timeline repeated to the end of a "
                                + "period of unknown length");
                    }
                    repeat = Math.max(0, (stop - time + duration - 1) / duration - 1);
                }
                if (repeat >= MAX_SEGMENTS || parts.size() + repeat >= MAX_SEGMENTS + 1) {
                    throw new MediaException.LimitExceeded("a DASH representation of more than " + MAX_SEGMENTS
                            + " segments");
                }
                for (var count = 0L; count <= repeat && number <= endNumber; count++) {
                    parts.add(SegmentSource.Part.of(Uris.resolve(base, substitute(media, element, number, time))));
                    if (parts.size() > MAX_SEGMENTS) {
                        throw new MediaException.LimitExceeded("a DASH representation of more than " + MAX_SEGMENTS
                                + " segments");
                    }
                    number++;
                    time += duration;
                }
            }
            return;
        }
        var duration = number(inherited(levels, "SegmentTemplate", "duration"), -1);
        if (duration <= 0) {
            throw new MediaException.Malformed("a DASH SegmentTemplate without a timeline or a segment duration");
        }
        long count;
        if (periodLength >= 0) {
            count = (Math.multiplyExact(periodLength, timescale) / 1_000_000 + duration - 1) / duration;
        } else if (available >= 0) {
            count = Math.multiplyExact(available, timescale) / 1_000_000 / duration;
        } else {
            throw new MediaException.Unsupported("dash", "a live manifest that addresses segments by duration "
                    + "without a publish time");
        }
        if (count > MAX_SEGMENTS) {
            throw new MediaException.LimitExceeded("a DASH representation of more than " + MAX_SEGMENTS + " segments");
        }
        for (var index = 0L; index < count && number <= endNumber; index++, number++) {
            parts.add(SegmentSource.Part.of(Uris.resolve(base, substitute(media, element, number,
                    pto + index * duration))));
        }
    }

    /**
     * Returns a part named by a URL and a byte range.
     *
     * @param base the base URL
     * @param url the URL, or {@code null} for the base URL itself
     * @param range the byte range {@code first-last}, or {@code null} for the whole file
     * @return the part
     * @throws MediaException.Malformed if the range is malformed
     */
    private static SegmentSource.Part part(String base, String url, String range) {
        var uri = url == null ? base : Uris.resolve(base, url);
        if (range == null) {
            return SegmentSource.Part.of(uri);
        }
        var dash = range.indexOf('-');
        try {
            var first = Long.parseLong(range.substring(0, dash).strip());
            var last = Long.parseLong(range.substring(dash + 1).strip());
            if (first < 0 || last < first) {
                throw new NumberFormatException(range);
            }
            return new SegmentSource.Part(uri, first, last - first + 1, null, null);
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("a DASH byte range " + range);
        }
    }

    /**
     * Substitutes the identifiers of a segment template (ISO/IEC 23009-1 clause 5.3.9.4.4).
     *
     * @param template the template, with its identifiers
     * @param representation the representation whose identifier and bandwidth fill the template
     * @param number the segment number
     * @param time the segment time
     * @return the URL
     * @throws MediaException.Unsupported if the template uses {@code $SubNumber$}
     */
    private static String substitute(String template, XmlReader.Element representation, long number, long time) {
        var matcher = IDENTIFIER.matcher(template);
        var out = new StringBuilder();
        while (matcher.find()) {
            String value;
            if (matcher.group(1) == null) {
                value = "$";
            } else {
                value = switch (matcher.group(1)) {
                    case "RepresentationID" -> String.valueOf(representation.attribute("", "id"));
                    case "Number" -> Long.toString(number);
                    case "Bandwidth" -> String.valueOf(representation.attribute("", "bandwidth"));
                    case "Time" -> Long.toString(time);
                    default -> throw new MediaException.Unsupported("dash", "the template identifier "
                            + matcher.group());
                };
                if (matcher.group(2) != null) {
                    var width = Math.min(32, Integer.parseInt(matcher.group(2)));
                    value = "0".repeat(Math.max(0, width - value.length())) + value;
                }
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * Returns the base URL of an element: its first {@code BaseURL} resolved against its parent's.
     *
     * @param parent the parent's base URL
     * @param element the element whose base URL is returned
     * @return the base URL
     */
    private static String baseUrl(String parent, XmlReader.Element element) {
        var urls = children(element, "BaseURL");
        if (urls.isEmpty()) {
            return parent;
        }
        return Uris.resolve(parent, urls.getFirst().text().strip());
    }

    /**
     * Returns the first element of a name among the levels, lowest first.
     *
     * @param levels the representation, its adaptation set and its period
     * @param name the element name
     * @return the element, or {@code null}
     */
    private static XmlReader.Element first(List<XmlReader.Element> levels, String name) {
        for (var level : levels) {
            var found = children(level, name);
            if (!found.isEmpty()) {
                return found.getFirst();
            }
        }
        return null;
    }

    /**
     * Returns an attribute of an addressing element, inherited from the higher levels.
     *
     * @param levels the representation, its adaptation set and its period
     * @param element the addressing element name
     * @param attribute the attribute name
     * @return the value of the lowest level that has it, or {@code null}
     */
    private static String inherited(List<XmlReader.Element> levels, String element, String attribute) {
        for (var level : levels) {
            for (var found : children(level, element)) {
                var value = found.attribute("", attribute);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * Returns a child of an addressing element, inherited from the higher levels.
     *
     * @param levels the representation, its adaptation set and its period
     * @param element the addressing element name
     * @param child the child name
     * @return the child of the lowest level that has it, or {@code null}
     */
    private static XmlReader.Element firstChild(List<XmlReader.Element> levels, String element, String child) {
        for (var level : levels) {
            for (var found : children(level, element)) {
                var children = children(found, child);
                if (!children.isEmpty()) {
                    return children.getFirst();
                }
            }
        }
        return null;
    }

    /**
     * Returns an attribute of a representation or of its adaptation set.
     *
     * @param representation the representation element
     * @param set its adaptation set
     * @param name the attribute
     * @return the value, or {@code null}
     */
    private static String attribute(XmlReader.Element representation, XmlReader.Element set, String name) {
        var value = representation.attribute("", name);
        return value != null ? value : set.attribute("", name);
    }

    /**
     * Returns the children of an element with a local name.
     *
     * @param parent the element
     * @param name the local name
     * @return the children, in order
     */
    private static List<XmlReader.Element> children(XmlReader.Element parent, String name) {
        var found = new ArrayList<XmlReader.Element>();
        for (var child : parent.elements()) {
            if (child.name().equals(name)) {
                found.add(child);
            }
        }
        return found;
    }

    /**
     * Parses a non-negative integer attribute.
     *
     * @param text the value, or {@code null}
     * @param fallback the value when absent
     * @return the value
     * @throws MediaException.Malformed if it is not a non-negative integer
     */
    private static long number(String text, long fallback) {
        if (text == null) {
            return fallback;
        }
        try {
            var value = Long.parseLong(text.strip());
            if (value < 0) {
                throw new NumberFormatException(text);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new MediaException.Malformed("a DASH number " + text);
        }
    }

    /**
     * Parses a timescale.
     *
     * @param text the value, or {@code null} for the default of 1
     * @return the timescale
     * @throws MediaException.Malformed if it is not a positive integer
     */
    private static long timescale(String text) {
        var value = number(text, 1);
        if (value == 0) {
            throw new MediaException.Malformed("a DASH timescale of 0");
        }
        return value;
    }

    /**
     * Parses a segment timeline repeat count.
     *
     * @param text the value, or {@code null}
     * @return the count, zero when absent, negative to repeat to the next entry
     * @throws MediaException.Malformed if it is not an integer
     */
    private static long signed(String text) {
        if (text == null) {
            return 0;
        }
        try {
            return Long.parseLong(text.strip());
        } catch (NumberFormatException exception) {
            throw new MediaException.Malformed("a DASH repeat count " + text);
        }
    }

    /**
     * Parses an {@code xs:duration}.
     *
     * @param text the value, or {@code null}
     * @return the duration in microseconds, or -1 when absent
     * @throws MediaException.Malformed if it is malformed or uses years or months
     */
    static long duration(String text) {
        if (text == null) {
            return -1;
        }
        var matcher = DURATION.matcher(text.strip());
        if (!matcher.matches() || text.strip().equals("P") || text.strip().endsWith("T")) {
            throw new MediaException.Malformed("a DASH duration " + text);
        }
        if (matcher.group(1) != null || matcher.group(2) != null) {
            throw new MediaException.Malformed("a DASH duration in years or months: " + text);
        }
        try {
            var micros = 0L;
            micros = Math.addExact(micros, Math.multiplyExact(group(matcher, 3), 86_400_000_000L));
            micros = Math.addExact(micros, Math.multiplyExact(group(matcher, 4), 3_600_000_000L));
            micros = Math.addExact(micros, Math.multiplyExact(group(matcher, 5), 60_000_000L));
            if (matcher.group(6) != null) {
                var seconds = new java.math.BigDecimal(matcher.group(6)).movePointRight(6);
                micros = Math.addExact(micros, seconds.setScale(0, java.math.RoundingMode.HALF_UP).longValueExact());
            }
            return micros;
        } catch (ArithmeticException exception) {
            throw new MediaException.Malformed("a DASH duration " + text + " out of range");
        }
    }

    /**
     * Returns a numeric group of a duration.
     *
     * @param matcher the matched duration
     * @param group the index of the group
     * @return the number, zero when absent
     */
    private static long group(Matcher matcher, int group) {
        return matcher.group(group) == null ? 0 : Long.parseLong(matcher.group(group));
    }

    /**
     * Parses an {@code xs:dateTime}.
     *
     * @param text the value, or {@code null}
     * @return the time, UTC when it has no offset, or {@code null} when absent
     * @throws MediaException.Malformed if it is malformed
     */
    private static Instant time(String text) {
        if (text == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text.strip()).toInstant();
        } catch (DateTimeParseException exception) {
            try {
                return LocalDateTime.parse(text.strip()).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                throw new MediaException.Malformed("a DASH time " + text);
            }
        }
    }
}
