package com.github.auties00.smalt.internal.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.xml.XmlReader;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Parses a TTML document (W3C TTML1 and TTML2, and the IMSC 1.0.1 and 1.1 text profiles) into timed cues.
 *
 * <p>Timing follows the TTML timing model: {@code begin}, {@code end} and {@code dur} on {@code body},
 * {@code div}, {@code p} and {@code span}, relative to the parent in a {@code par} container (the default)
 * and to the previous sibling in a {@code seq} one; an element without an end lasts as long as its parent.
 * Time expressions are clock times ({@code 00:00:01.5}, {@code 00:00:01:12} with frames,
 * {@code 00:00:01:12.1} with sub-frames) and offset times ({@code 1.5s}, {@code 12f}, {@code 4500t},
 * {@code 2m}, {@code 1h}, {@code 100ms}) under the document's {@code ttp:frameRate},
 * {@code ttp:frameRateMultiplier}, {@code ttp:subFrameRate}, {@code ttp:tickRate} and, for the
 * {@code smpte} time base, {@code ttp:dropMode}. Under the media time base the frames of a clock time are
 * added to its seconds at the effective frame rate; under the {@code smpte} time base the whole clock time
 * counts frames, at the nominal rate with the drop-frame numbering, which the effective rate converts to
 * seconds.
 *
 * <p>Each paragraph becomes one cue per stretch of time in which its visible content does not change.
 * Styles are resolved as TTML specifies them, from referential styles (chained), the region, inherited
 * values and inline attributes: {@code tts:fontWeight}, {@code tts:fontStyle}, {@code tts:textDecoration},
 * {@code tts:color}, {@code tts:backgroundColor} of spans and paragraphs, {@code tts:fontFamily} and
 * {@code tts:fontSize} map to {@link StyledText} runs; the region's {@code tts:displayAlign}, the
 * paragraph's {@code tts:textAlign} and the region's {@code tts:origin} and {@code tts:extent} give the
 * placement. The font family and size the region and the containers above the paragraph give are the look
 * of the whole document and are not carried, nor is a white text color; content hidden by
 * {@code tts:display} or {@code tts:visibility} is skipped. A {@code set} element applies its styles to its
 * parent while it is active. A paragraph goes to the region it, a descendant span or an ancestor selects; in a
 * document that defines regions, a paragraph that selects none, or an unknown one, is not shown, and without
 * regions content goes to the default region, the whole frame. Whitespace is collapsed unless
 * {@code xml:space="preserve"}, and {@code br} breaks lines.
 */
public final class TtmlDocument {
    /**
     * The TTML namespace.
     */
    public static final String TT = "http://www.w3.org/ns/ttml";

    /**
     * The TTML styling namespace.
     */
    public static final String TTS = "http://www.w3.org/ns/ttml#styling";

    /**
     * The TTML parameter namespace.
     */
    public static final String TTP = "http://www.w3.org/ns/ttml#parameter";

    /**
     * The TTML metadata namespace.
     */
    public static final String TTM = "http://www.w3.org/ns/ttml#metadata";

    /**
     * The namespace of the TTML candidate recommendation drafts some DFXP files use.
     */
    private static final String DRAFT = "http://www.w3.org/2006/10/ttaf1";

    /**
     * The namespace of the older TTML draft some DFXP files use.
     */
    private static final String OLD_DRAFT = "http://www.w3.org/2006/04/ttaf1";

    /**
     * A time that never comes: the end of an element without an end.
     */
    private static final long INDEFINITE = Long.MAX_VALUE;

    /**
     * The largest number of cues a document yields.
     */
    private static final int MAX_CUES = 1 << 20;

    /**
     * The anchor distance below which a region's anchor is taken for the default one of its alignment.
     */
    private static final double DEFAULT_ANCHOR_TOLERANCE = 0.05;

    /**
     * The parsed cues.
     */
    private final List<Cue> cues;

    /**
     * The title of the document, empty when none.
     */
    private final String title;

    /**
     * The namespaces the document declares.
     */
    private final List<String> namespaces;

    /**
     * Holds one cue.
     *
     * @param startMicros the start in microseconds
     * @param endMicros the end in microseconds
     * @param text the characters to examine
     */
    public record Cue(long startMicros, long endMicros, StyledText text) {
    }

    /**
     * Constructs a parsed document.
     *
     * @param cues the cues in start order
     * @param title the {@code ttm:title} text
     * @param namespaces the declared namespaces
     */
    private TtmlDocument(List<Cue> cues, String title, List<String> namespaces) {
        this.cues = cues;
        this.title = title;
        this.namespaces = namespaces;
    }

    /**
     * {@return the cues in start order, document order among cues that start together}
     */
    public List<Cue> cues() {
        return cues;
    }

    /**
     * {@return the {@code ttm:title} of the document head, empty when none}
     */
    public String title() {
        return title;
    }

    /**
     * {@return the namespaces the root element declares, the root's namespace first and the others in
     * alphabetical order}
     */
    public List<String> namespaces() {
        return namespaces;
    }

    /**
     * Returns the end of the last cue.
     *
     * @return the end in microseconds, {@code 0} without cues
     */
    public long endMicros() {
        var end = 0L;
        for (var cue : cues) {
            end = Math.max(end, cue.endMicros());
        }
        return end;
    }

    /**
     * Returns whether bytes start like a TTML document: an optional XML declaration, comments and
     * processing instructions, then a {@code tt} root element.
     *
     * @param text the leading text of a file, without a byte order mark
     * @return {@code true} when the root element is {@code tt} or a prefixed {@code tt}
     */
    public static boolean isSignature(String text) {
        var index = 0;
        var length = text.length();
        while (index < length) {
            var character = text.charAt(index);
            if (Character.isWhitespace(character) || character == '\uFEFF') {
                index++;
            } else if (text.startsWith("<?", index)) {
                var end = text.indexOf("?>", index);
                if (end < 0) {
                    return false;
                }
                index = end + 2;
            } else if (text.startsWith("<!--", index)) {
                var end = text.indexOf("-->", index);
                if (end < 0) {
                    return false;
                }
                index = end + 3;
            } else if (character == '<') {
                var end = index + 1;
                while (end < length && !Character.isWhitespace(text.charAt(end)) && text.charAt(end) != '>'
                        && text.charAt(end) != '/') {
                    end++;
                }
                var name = text.substring(index + 1, end);
                var colon = name.indexOf(':');
                return (colon < 0 ? name : name.substring(colon + 1)).equals("tt");
            } else {
                return false;
            }
        }
        return false;
    }

    /**
     * Decodes the bytes of a document: a byte order mark, then the encoding of the XML declaration, then
     * UTF-8.
     *
     * @param data the document bytes
     * @return the text
     */
    public static String decode(byte[] data) {
        var bom = TextFiles.byteOrderMark(data);
        if (bom != null) {
            return TextFiles.decode(data);
        }
        var head = new String(data, 0, Math.min(data.length, 256), StandardCharsets.ISO_8859_1);
        if (head.startsWith("<?xml")) {
            var end = head.indexOf("?>");
            var declaration = end > 0 ? head.substring(0, end) : head;
            var at = declaration.indexOf("encoding");
            if (at > 0) {
                var quote = declaration.indexOf('"', at);
                var apostrophe = declaration.indexOf('\'', at);
                var open = quote < 0 ? apostrophe : apostrophe < 0 ? quote : Math.min(quote, apostrophe);
                if (open > 0) {
                    var close = declaration.indexOf(declaration.charAt(open), open + 1);
                    if (close > open) {
                        try {
                            var charset = Charset.forName(declaration.substring(open + 1, close).strip());
                            return new String(data, charset);
                        } catch (RuntimeException ignored) {
                            // an unknown encoding name falls back to UTF-8
                        }
                    }
                }
            }
        }
        return TextFiles.decodeUtf8OrLegacy(data, 0, data.length);
    }

    /**
     * Parses a document.
     *
     * @param data the document bytes
     * @return the parsed document
     * @throws MediaException.Malformed if the bytes are not a well-formed TTML document
     */
    public static TtmlDocument parse(byte[] data) {
        return parse(decode(data));
    }

    /**
     * Parses a document.
     *
     * @param text the document text
     * @return the parsed document
     * @throws MediaException.Malformed if the text is not a well-formed TTML document
     */
    public static TtmlDocument parse(String text) {
        var root = XmlReader.parse(text);
        if (!root.name().equals("tt") || !isTtml(root.namespace())) {
            throw new MediaException.Malformed("not a TTML document: the root element is not tt");
        }
        return new Parser(root).run();
    }

    /**
     * Returns whether a namespace is the TTML namespace or one of its drafts.
     *
     * @param namespace the namespace URI
     * @return {@code true} for TTML
     */
    static boolean isTtml(String namespace) {
        return namespace.equals(TT) || namespace.equals(DRAFT) || namespace.equals(OLD_DRAFT);
    }

    /**
     * Returns whether a namespace is the TTML styling namespace or one of its drafts.
     *
     * @param namespace the namespace URI
     * @return {@code true} for TTML styling
     */
    private static boolean isStyling(String namespace) {
        return namespace.equals(TTS) || namespace.equals(DRAFT + "#styling") || namespace.equals(DRAFT + "#style")
                || namespace.equals(OLD_DRAFT + "#styling") || namespace.equals(OLD_DRAFT + "#style");
    }

    /**
     * Returns whether a namespace is the TTML parameter namespace or one of its drafts.
     *
     * @param namespace the namespace URI
     * @return {@code true} for TTML parameters
     */
    private static boolean isParameter(String namespace) {
        return namespace.equals(TTP) || namespace.equals(DRAFT + "#parameter") || namespace.equals(OLD_DRAFT + "#parameter");
    }

    /**
     * Holds the computed values of the style properties the conversion uses.
     *
     * @param bold whether the font weight is bold
     * @param italic whether the font style is italic or oblique
     * @param underline whether the text is underlined
     * @param strikeout whether the text is struck through
     * @param color the text color
     * @param font the font family, empty for the default
     * @param size the font size in reference pixels
     * @param textAlign the column of the text alignment: 0 left, 1 center, 2 right
     * @param displayAlign the row of the display alignment: 0 bottom, 1 center, 2 top
     * @param hidden whether the content is invisible, as {@code tts:visibility} makes it; a descendant may
     * make itself visible again
     * @param undisplayed whether the content is not rendered, as {@code tts:display} makes it, with every
     * descendant
     */
    private record Computed(boolean bold, boolean italic, boolean underline, boolean strikeout, long color,
                            String font, double size, int textAlign, int displayAlign, boolean hidden,
                            boolean undisplayed) {
    }

    /**
     * Holds one region.
     *
     * @param styles the style properties the region specifies, by local name
     * @param x the left edge, as a fraction of the frame width
     * @param y the top edge, as a fraction of the frame height
     * @param width the width, as a fraction of the frame width
     * @param height the height, as a fraction of the frame height
     * @param active when the region is active, from its {@code begin}, {@code end} and {@code dur}
     */
    private record Region(Map<String, String> styles, double x, double y, double width, double height,
                          Interval active) {
    }

    /**
     * Holds one element's interval during the timing pass.
     *
     * @param begin the start in microseconds
     * @param end the end in microseconds, {@link #INDEFINITE} when it has none
     */
    private record Interval(long begin, long end) {
        /**
         * Returns whether a time lies inside the interval.
         *
         * @param time the moment, in microseconds
         * @return {@code true} when {@code begin <= time < end}
         */
        boolean contains(long time) {
            return begin <= time && time < end;
        }
    }

    /**
     * Walks one document.
     */
    private static final class Parser {
        /**
         * The root element.
         */
        private final XmlReader.Element root;

        /**
         * The style properties of each named style, its references resolved, by identifier.
         */
        private final Map<String, Map<String, String>> styles = new HashMap<>();

        /**
         * The style elements by identifier, before resolution.
         */
        private final Map<String, XmlReader.Element> styleElements = new HashMap<>();

        /**
         * The regions by identifier.
         */
        private final Map<String, Region> regions = new HashMap<>();

        /**
         * The interval of each timed element.
         */
        private final Map<XmlReader.Element, Interval> intervals = new IdentityHashMap<>();

        /**
         * The frame rate, frames per second.
         */
        private double frameRate = 30;

        /**
         * The sub-frame rate, sub-frames per frame.
         */
        private double subFrameRate = 1;

        /**
         * The tick rate, ticks per second.
         */
        private double tickRate = 1;

        /**
         * The number of rows of the cell grid.
         */
        private double rows = 15;

        /**
         * The number of columns of the cell grid.
         */
        private double columns = 32;

        /**
         * The frame width in pixels, {@code 0} when the root has no pixel extent.
         */
        private double pixelWidth;

        /**
         * The frame height in pixels, {@code 0} when the root has no pixel extent.
         */
        private double pixelHeight;

        /**
         * Whether clock times count SMPTE frames.
         */
        private boolean smpte;

        /**
         * The SMPTE drop mode: {@code nonDrop}, {@code dropNTSC} or {@code dropPAL}.
         */
        private String dropMode = "nonDrop";

        /**
         * The cues found so far.
         */
        private final List<Cue> cues = new ArrayList<>();

        /**
         * The time whose {@code set} animations apply to the styles computed, or {@code -1} for none.
         */
        private long current = -1;

        /**
         * Constructs a parser.
         *
         * @param root the root element
         */
        Parser(XmlReader.Element root) {
            this.root = root;
        }

        /**
         * Parses the document.
         *
         * @return the document
         */
        TtmlDocument run() {
            readParameters();
            var title = "";
            XmlReader.Element body = null;
            for (var child : root.elements()) {
                if (!isTtml(child.namespace())) {
                    continue;
                }
                if (child.name().equals("head")) {
                    title = readHead(child);
                } else if (child.name().equals("body")) {
                    body = child;
                }
            }
            if (body != null) {
                time(body, new Interval(0, INDEFINITE), false, 0);
                walkContainers(body, compute(body, initial(), null), List.of(body));
            }
            var finite = 0L;
            for (var interval : intervals.values()) {
                if (interval.begin() != INDEFINITE) {
                    finite = Math.max(finite, interval.begin());
                }
                if (interval.end() != INDEFINITE) {
                    finite = Math.max(finite, interval.end());
                }
            }
            var result = new ArrayList<Cue>(cues.size());
            for (var cue : cues) {
                var end = cue.endMicros() == INDEFINITE ? Math.max(finite, cue.startMicros()) : cue.endMicros();
                if (end > cue.startMicros()) {
                    result.add(new Cue(cue.startMicros(), end, cue.text()));
                }
            }
            result.sort(Comparator.comparingLong(Cue::startMicros));
            var declared = new TreeSet<>(root.declared().values());
            declared.remove(root.namespace());
            var namespaces = new ArrayList<String>();
            namespaces.add(root.namespace());
            namespaces.addAll(declared);
            return new TtmlDocument(List.copyOf(result), title, List.copyOf(namespaces));
        }

        /**
         * Reads the timing and layout parameters of the root element.
         */
        private void readParameters() {
            var multiplier = 1.0;
            String tickRateValue = null;
            String frameRateValue = null;
            for (var attribute : root.attributes()) {
                if (isParameter(attribute.namespace())) {
                    var value = attribute.value().strip();
                    switch (attribute.name()) {
                        case "frameRate" -> frameRateValue = value;
                        case "frameRateMultiplier" -> {
                            var parts = value.split("\\s+");
                            if (parts.length == 2) {
                                var numerator = number(parts[0]);
                                var denominator = number(parts[1]);
                                if (numerator > 0 && denominator > 0) {
                                    multiplier = numerator / denominator;
                                }
                            }
                        }
                        case "subFrameRate" -> {
                            var parsed = number(value);
                            subFrameRate = parsed > 0 ? parsed : subFrameRate;
                        }
                        case "tickRate" -> tickRateValue = value;
                        case "timeBase" -> smpte = value.equals("smpte");
                        case "dropMode" -> dropMode = value;
                        case "cellResolution" -> {
                            var parts = value.split("\\s+");
                            if (parts.length == 2 && number(parts[0]) > 0 && number(parts[1]) > 0) {
                                columns = number(parts[0]);
                                rows = number(parts[1]);
                            }
                        }
                        default -> {
                        }
                    }
                } else if (isStyling(attribute.namespace()) && attribute.name().equals("extent")) {
                    var parts = attribute.value().strip().split("\\s+");
                    if (parts.length == 2 && parts[0].endsWith("px") && parts[1].endsWith("px")) {
                        pixelWidth = Math.max(0, number(parts[0].substring(0, parts[0].length() - 2)));
                        pixelHeight = Math.max(0, number(parts[1].substring(0, parts[1].length() - 2)));
                    }
                }
            }
            if (frameRateValue != null && number(frameRateValue) > 0) {
                frameRate = number(frameRateValue);
            }
            frameRate *= multiplier;
            if (tickRateValue != null && number(tickRateValue) > 0) {
                tickRate = number(tickRateValue);
            } else if (frameRateValue != null) {
                tickRate = frameRate * subFrameRate;
            }
        }

        /**
         * Reads the styles and regions of the head.
         *
         * @param head the head element
         * @return the document title, empty when none
         */
        private String readHead(XmlReader.Element head) {
            var title = "";
            for (var section : head.elements()) {
                if (section.name().equals("styling") && isTtml(section.namespace())) {
                    for (var style : section.elements()) {
                        if (style.name().equals("style") && isTtml(style.namespace())) {
                            var id = id(style);
                            if (id != null) {
                                styleElements.putIfAbsent(id, style);
                            }
                        }
                    }
                } else if (section.name().equals("metadata") && isTtml(section.namespace())) {
                    for (var item : section.elements()) {
                        if (item.name().equals("title") && title.isEmpty()) {
                            title = item.text().strip();
                        }
                    }
                }
            }
            for (var section : head.elements()) {
                if (section.name().equals("layout") && isTtml(section.namespace())) {
                    for (var region : section.elements()) {
                        if (region.name().equals("region") && isTtml(region.namespace())) {
                            var id = id(region);
                            if (id != null) {
                                regions.putIfAbsent(id, region(region));
                            }
                        }
                    }
                }
            }
            return title;
        }

        /**
         * Returns the identifier of an element.
         *
         * @param element the TTML element
         * @return the {@code xml:id}, or {@code null}
         */
        private static String id(XmlReader.Element element) {
            return element.attribute(XmlReader.XML_NAMESPACE, "id");
        }

        /**
         * Builds a region from its element: its referential and inline styles and nested style elements.
         *
         * @param element the region element
         * @return the region
         */
        private Region region(XmlReader.Element element) {
            var specified = new LinkedHashMap<String, String>(referenced(element, 0));
            for (var child : element.elements()) {
                if (child.name().equals("style") && isTtml(child.namespace())) {
                    specified.putAll(styleOf(child, 0));
                }
            }
            specified.putAll(inline(element));
            var origin = pair(specified.get("origin"), 0, 0);
            var extent = pair(specified.get("extent"), 1, 1);
            var begin = element.attribute("", "begin") == null ? 0 : Math.max(0, timeOf(element.attribute("", "begin")));
            var end = INDEFINITE;
            if (element.attribute("", "end") != null && timeOf(element.attribute("", "end")) >= 0) {
                end = timeOf(element.attribute("", "end"));
            }
            if (element.attribute("", "dur") != null && timeOf(element.attribute("", "dur")) >= 0) {
                end = Math.min(end, saturatingAdd(begin, timeOf(element.attribute("", "dur"))));
            }
            return new Region(Map.copyOf(specified), origin[0], origin[1], extent[0], extent[1],
                    new Interval(begin, Math.max(begin, end)));
        }

        /**
         * Parses an {@code origin} or {@code extent} value into frame fractions.
         *
         * @param value the value, or {@code null}
         * @param x the horizontal value when the value is absent or not understood
         * @param y the vertical value when the value is absent or not understood
         * @return the horizontal and vertical fractions
         */
        private double[] pair(String value, double x, double y) {
            if (value == null) {
                return new double[]{x, y};
            }
            var parts = value.strip().split("\\s+");
            if (parts.length != 2) {
                return new double[]{x, y};
            }
            var first = length(parts[0], pixelWidth, 1.0 / columns);
            var second = length(parts[1], pixelHeight, 1.0 / rows);
            return new double[]{Double.isNaN(first) ? x : first, Double.isNaN(second) ? y : second};
        }

        /**
         * Converts a length to a fraction of the frame.
         *
         * @param value the length with its unit
         * @param pixels the frame size in pixels in that direction, {@code 0} when unknown
         * @param cell the size of a cell as a fraction of the frame in that direction
         * @return the fraction, or {@code NaN} when the length is not understood
         */
        private static double length(String value, double pixels, double cell) {
            if (value.endsWith("%")) {
                return number(value.substring(0, value.length() - 1)) / 100;
            }
            if (value.endsWith("px")) {
                var number = number(value.substring(0, value.length() - 2));
                return pixels > 0 ? number / pixels : Double.NaN;
            }
            if (value.endsWith("c")) {
                return number(value.substring(0, value.length() - 1)) * cell;
            }
            if (value.endsWith("rw") || value.endsWith("rh")) {
                return number(value.substring(0, value.length() - 2)) / 100;
            }
            return Double.NaN;
        }

        /**
         * Returns the style properties of a style element, its references resolved.
         *
         * @param style the style element
         * @param depth the reference depth, which bounds cycles
         * @return the properties by local name
         */
        private Map<String, String> styleOf(XmlReader.Element style, int depth) {
            var id = id(style);
            if (id != null && styles.containsKey(id)) {
                return styles.get(id);
            }
            var result = new LinkedHashMap<String, String>();
            if (depth < 16) {
                result.putAll(referenced(style, depth + 1));
            }
            result.putAll(inline(style));
            if (id != null) {
                styles.put(id, result);
            }
            return result;
        }

        /**
         * Returns the properties of the styles an element references.
         *
         * @param element the TTML element
         * @param depth the reference depth
         * @return the properties by local name, later references winning
         */
        private Map<String, String> referenced(XmlReader.Element element, int depth) {
            var result = new LinkedHashMap<String, String>();
            var references = element.attribute("", "style");
            if (references == null || depth >= 16) {
                return result;
            }
            for (var reference : references.strip().split("\\s+")) {
                var target = styleElements.get(reference);
                if (target != null && target != element) {
                    result.putAll(styleOf(target, depth));
                }
            }
            return result;
        }

        /**
         * Returns the style properties an element specifies inline.
         *
         * @param element the TTML element
         * @return the properties by local name
         */
        private static Map<String, String> inline(XmlReader.Element element) {
            var result = new LinkedHashMap<String, String>();
            for (var attribute : element.attributes()) {
                if (isStyling(attribute.namespace())) {
                    result.put(attribute.name(), attribute.value().strip());
                }
            }
            return result;
        }

        /**
         * Computes the intervals of an element and its descendants.
         *
         * @param element the TTML element
         * @param parent the interval of the parent
         * @param sequential whether the parent is a {@code seq} container
         * @param previousEnd the end of the previous sibling, the sync base in a {@code seq} container
         * @return the interval of the element
         */
        private Interval time(XmlReader.Element element, Interval parent, boolean sequential, long previousEnd) {
            var base = sequential ? previousEnd : parent.begin();
            var beginValue = element.attribute("", "begin");
            var endValue = element.attribute("", "end");
            var durValue = element.attribute("", "dur");
            var begin = saturatingAdd(base, beginValue == null ? 0 : Math.max(0, timeOf(beginValue)));
            var end = INDEFINITE;
            if (endValue != null) {
                var parsed = timeOf(endValue);
                end = parsed < 0 ? INDEFINITE : saturatingAdd(base, parsed);
            }
            if (durValue != null) {
                var parsed = timeOf(durValue);
                if (parsed >= 0) {
                    end = Math.min(end, saturatingAdd(begin, parsed));
                }
            }
            var container = "seq".equals(element.attribute("", "timeContainer"));
            var interval = new Interval(begin, Math.max(begin, Math.min(end, parent.end())));
            var childEnd = begin;
            var latest = begin;
            var anyChild = false;
            for (var child : element.elements()) {
                if (!isTtml(child.namespace()) || !isTimed(child.name())) {
                    continue;
                }
                var childInterval = time(child, interval, container, childEnd);
                childEnd = childInterval.end();
                latest = Math.max(latest, childInterval.end());
                anyChild = true;
            }
            if (end == INDEFINITE && container && latest != INDEFINITE) {
                // a seq container ends with its last child; its anonymous spans last no time, so one that holds
                // only text ends where it begins
                interval = new Interval(begin, Math.min(anyChild ? latest : begin, parent.end()));
                intervals.put(element, interval);
                for (var child : element.elements()) {
                    if (isTtml(child.namespace()) && isTimed(child.name())) {
                        clamp(child, interval);
                    }
                }
            }
            intervals.put(element, interval);
            return interval;
        }

        /**
         * Shortens the intervals of a subtree to a parent's interval.
         *
         * @param element the subtree root
         * @param parent the parent interval
         */
        private void clamp(XmlReader.Element element, Interval parent) {
            var interval = intervals.get(element);
            if (interval == null) {
                return;
            }
            var clamped = new Interval(Math.min(interval.begin(), parent.end()), Math.min(interval.end(), parent.end()));
            intervals.put(element, clamped);
            for (var child : element.elements()) {
                clamp(child, clamped);
            }
        }

        /**
         * Returns whether an element takes part in timing.
         *
         * @param name the local name
         * @return {@code true} for {@code body}, {@code div}, {@code p}, {@code span}, {@code br} and
         * {@code set}
         */
        private static boolean isTimed(String name) {
            return name.equals("body") || name.equals("div") || name.equals("p") || name.equals("span")
                    || name.equals("br") || name.equals("set");
        }

        /**
         * Parses a time expression.
         *
         * @param value the expression
         * @return the time in microseconds, or {@code -1} when it is not a time expression
         */
        private long timeOf(String value) {
            var text = value.strip();
            if (text.isEmpty()) {
                return -1;
            }
            if (text.indexOf(':') > 0) {
                var parts = text.split(":");
                if (parts.length < 3 || parts.length > 4) {
                    return -1;
                }
                var hours = number(parts[0]);
                var minutes = number(parts[1]);
                if (Double.isNaN(hours) || Double.isNaN(minutes) || hours < 0 || minutes < 0) {
                    return -1;
                }
                if (parts.length == 3) {
                    var seconds = number(parts[2]);
                    if (Double.isNaN(seconds) || seconds < 0) {
                        return -1;
                    }
                    return micros((hours * 60 + minutes) * 60 + seconds);
                }
                var seconds = number(parts[2]);
                var frameParts = parts[3].split("\\.", 2);
                var frames = number(frameParts[0]);
                var subFrames = frameParts.length == 2 ? number(frameParts[1]) : 0;
                if (Double.isNaN(seconds) || Double.isNaN(frames) || Double.isNaN(subFrames) || seconds < 0
                        || frames < 0) {
                    return -1;
                }
                if (!smpte) {
                    return micros((hours * 60 + minutes) * 60 + seconds + (frames + subFrames / subFrameRate) / frameRate);
                }
                var totalFrames = ((hours * 60 + minutes) * 60 + seconds) * Math.rint(frameRate) + frames;
                if (!dropMode.equals("nonDrop")) {
                    var totalMinutes = (long) (hours * 60 + minutes);
                    if (dropMode.equals("dropNTSC")) {
                        totalFrames -= 2 * (totalMinutes - totalMinutes / 10);
                    } else if (dropMode.equals("dropPAL")) {
                        totalFrames -= 4 * (totalMinutes / 2 - totalMinutes / 20);
                    }
                }
                return micros((totalFrames + subFrames / subFrameRate) / frameRate);
            }
            var unit = text.endsWith("ms") ? "ms" : text.substring(text.length() - 1);
            var number = number(text.substring(0, text.length() - unit.length()));
            if (Double.isNaN(number) || number < 0) {
                return -1;
            }
            return switch (unit) {
                case "h" -> micros(number * 3600);
                case "m" -> micros(number * 60);
                case "s" -> micros(number);
                case "ms" -> micros(number / 1000);
                case "f" -> micros(number / frameRate);
                case "t" -> micros(number / tickRate);
                default -> -1;
            };
        }

        /**
         * Converts seconds to microseconds, rounding and saturating.
         *
         * @param seconds the duration in seconds
         * @return the microseconds
         */
        private static long micros(double seconds) {
            var value = seconds * 1_000_000;
            return value >= Long.MAX_VALUE / 4.0 ? Long.MAX_VALUE / 4 : Math.round(value);
        }

        /**
         * {@return the initial values of the style properties}
         */
        private Computed initial() {
            return new Computed(false, false, false, false, 0xFFFFFFFFL, "", StyledText.REFERENCE_HEIGHT / rows, 0, 2,
                    false, false);
        }

        /**
         * Computes the style of an element from its parent's.
         *
         * @param element the TTML element
         * @param parent the computed style of the parent
         * @param region the region the content flows into, or {@code null}
         * @return the computed style
         */
        private Computed compute(XmlReader.Element element, Computed parent, Region region) {
            var specified = new LinkedHashMap<String, String>();
            if (region != null) {
                specified.putAll(region.styles());
            }
            specified.putAll(referenced(element, 0));
            for (var child : element.elements()) {
                if (child.name().equals("style") && isTtml(child.namespace())) {
                    specified.putAll(styleOf(child, 0));
                }
            }
            specified.putAll(inline(element));
            if (current >= 0) {
                for (var child : element.elements()) {
                    if (child.name().equals("set") && isTtml(child.namespace())) {
                        var interval = intervals.get(child);
                        if (interval != null && interval.contains(current)) {
                            specified.putAll(inline(child));
                        }
                    }
                }
            }
            return applied(parent, specified);
        }

        /**
         * Applies specified style properties over inherited values.
         *
         * @param parent the computed style of the parent
         * @param specified the properties the element specifies, by local name
         * @return the computed style
         */
        private Computed applied(Computed parent, Map<String, String> specified) {
            var bold = parent.bold();
            var italic = parent.italic();
            var underline = parent.underline();
            var strikeout = parent.strikeout();
            var color = parent.color();
            var font = parent.font();
            var size = parent.size();
            var textAlign = parent.textAlign();
            var displayAlign = parent.displayAlign();
            var hidden = parent.hidden();
            var undisplayed = parent.undisplayed();
            for (var entry : specified.entrySet()) {
                var value = entry.getValue();
                switch (entry.getKey()) {
                    case "fontWeight" -> bold = value.equals("bold");
                    case "fontStyle" -> italic = value.equals("italic") || value.equals("oblique");
                    case "textDecoration" -> {
                        for (var token : value.split("\\s+")) {
                            switch (token) {
                                case "none" -> {
                                    underline = false;
                                    strikeout = false;
                                }
                                case "underline" -> underline = true;
                                case "noUnderline" -> underline = false;
                                case "lineThrough" -> strikeout = true;
                                case "noLineThrough" -> strikeout = false;
                                default -> {
                                }
                            }
                        }
                    }
                    case "color" -> {
                        var parsed = SubtitleColors.parseCss(value);
                        color = parsed != StyledText.NO_COLOR ? parsed : color;
                    }
                    case "fontFamily" -> font = fontFamily(value);
                    case "fontSize" -> size = fontSize(value, parent.size());
                    case "textAlign" -> textAlign = switch (value) {
                        case "center" -> 1;
                        case "right", "end" -> 2;
                        case "left", "start", "justify" -> 0;
                        default -> textAlign;
                    };
                    case "displayAlign" -> displayAlign = switch (value) {
                        case "center" -> 1;
                        case "after" -> 0;
                        case "before", "justify" -> 2;
                        default -> displayAlign;
                    };
                    case "display" -> undisplayed |= value.equals("none");
                    case "visibility" -> hidden = value.equals("hidden");
                    default -> {
                    }
                }
            }
            return new Computed(bold, italic, underline, strikeout, color, font, size, textAlign, displayAlign, hidden,
                    undisplayed);
        }

        /**
         * Returns the first family of a {@code tts:fontFamily} value that names a font.
         *
         * @param value the value: a comma-separated list of family names, possibly quoted
         * @return the first family that is not one of the generic families ({@code default},
         * {@code monospace}, {@code sansSerif}, {@code serif} and their variants), empty when there is none
         */
        private static String fontFamily(String value) {
            for (var part : value.split(",")) {
                var name = part.strip();
                if (name.length() >= 2 && (name.startsWith("\"") && name.endsWith("\"")
                        || name.startsWith("'") && name.endsWith("'"))) {
                    return name.substring(1, name.length() - 1);
                }
                switch (name) {
                    case "default", "monospace", "sansSerif", "serif", "monospaceSansSerif", "monospaceSerif",
                         "proportionalSansSerif", "proportionalSerif" -> {
                    }
                    default -> {
                        if (!name.isEmpty()) {
                            return name;
                        }
                    }
                }
            }
            return "";
        }

        /**
         * Converts a {@code tts:fontSize} value to reference pixels.
         *
         * @param value the value: one or two lengths, the last giving the height
         * @param parent the font size of the parent
         * @return the size, the parent's when the value is not understood
         */
        private double fontSize(String value, double parent) {
            var parts = value.strip().split("\\s+");
            var last = parts[parts.length - 1];
            double size;
            if (last.endsWith("%")) {
                size = parent * number(last.substring(0, last.length() - 1)) / 100;
            } else if (last.endsWith("em")) {
                size = parent * number(last.substring(0, last.length() - 2));
            } else if (last.endsWith("px")) {
                var pixels = number(last.substring(0, last.length() - 2));
                size = pixelHeight > 0 ? pixels * StyledText.REFERENCE_HEIGHT / pixelHeight : pixels;
            } else if (last.endsWith("rh")) {
                size = number(last.substring(0, last.length() - 2)) * StyledText.REFERENCE_HEIGHT / 100;
            } else if (last.endsWith("rw")) {
                size = number(last.substring(0, last.length() - 2)) * StyledText.REFERENCE_HEIGHT * 16 / 9 / 100;
            } else if (last.endsWith("c")) {
                size = number(last.substring(0, last.length() - 1)) * StyledText.REFERENCE_HEIGHT / rows;
            } else {
                size = Double.NaN;
            }
            return Double.isFinite(size) && size > 0 ? size : parent;
        }

        /**
         * Walks the containers above the paragraphs.
         *
         * @param element a {@code body} or {@code div} element
         * @param computed its computed style, without a region
         * @param chain the containers from {@code body} to {@code element}
         */
        private void walkContainers(XmlReader.Element element, Computed computed, List<XmlReader.Element> chain) {
            if (chain.size() > 64) {
                return;
            }
            for (var child : element.elements()) {
                if (!isTtml(child.namespace())) {
                    continue;
                }
                if (child.name().equals("div")) {
                    var nested = new ArrayList<>(chain);
                    nested.add(child);
                    walkContainers(child, compute(child, computed, null), nested);
                } else if (child.name().equals("p")) {
                    paragraph(child, chain);
                }
            }
        }

        /**
         * Turns a paragraph into cues.
         *
         * <p>The region's styles are inherited by the content flowed into it: they apply below the styles of
         * the containers and the paragraph.
         *
         * @param paragraph the {@code p} element
         * @param chain the containers from {@code body} to the paragraph's parent
         */
        private void paragraph(XmlReader.Element paragraph, List<XmlReader.Element> chain) {
            var interval = intervals.get(paragraph);
            if (interval == null || interval.end() <= interval.begin() || cues.size() >= MAX_CUES) {
                return;
            }
            var regionId = regionOf(paragraph, chain);
            var region = regionId == null ? null : regions.get(regionId);
            if (region == null && !regions.isEmpty()) {
                return;
            }
            var times = new TreeSet<Long>();
            collectTimes(paragraph, times, interval);
            for (var element : chain) {
                for (var child : element.elements()) {
                    if (child.name().equals("set") && isTtml(child.namespace())) {
                        addInside(times, intervals.get(child), interval);
                    }
                }
            }
            if (region != null) {
                addInside(times, region.active(), interval);
            }
            times.add(interval.begin());
            times.add(interval.end());
            var preserve = preserved(chain, paragraph);
            Cue previous = null;
            Long start = null;
            for (var time : times) {
                if (start == null) {
                    start = time;
                    continue;
                }
                if (time > interval.end()) {
                    break;
                }
                current = start;
                var container = initial();
                if (region != null) {
                    container = applied(container, region.styles());
                }
                for (var element : chain) {
                    container = compute(element, container, null);
                }
                var computed = compute(paragraph, container, null);
                var builder = new StyledText.Builder();
                if (!computed.undisplayed() && (region == null || region.active().contains(start))) {
                    content(paragraph, computed, StyledText.NO_COLOR, container.font(), container.size(), start,
                            builder, preserve);
                }
                current = -1;
                var styled = trimLines(builder.build(), preserve);
                styled = placed(styled, computed, region);
                if (!styled.plain().isBlank()) {
                    if (previous != null && previous.endMicros() == start && sameContent(previous.text(), styled)) {
                        previous = new Cue(previous.startMicros(), time, previous.text());
                        cues.set(cues.size() - 1, previous);
                    } else {
                        previous = new Cue(start, time, styled);
                        cues.add(previous);
                    }
                }
                start = time;
            }
        }

        /**
         * Returns whether whitespace is preserved in a paragraph: the {@code xml:space} of the paragraph or of
         * its nearest ancestor that sets one, {@code default} otherwise.
         *
         * @param chain the containers from {@code body} to the paragraph's parent
         * @param paragraph the {@code p} element
         * @return {@code true} under {@code xml:space="preserve"}
         */
        private boolean preserved(List<XmlReader.Element> chain, XmlReader.Element paragraph) {
            var path = new ArrayList<XmlReader.Element>();
            path.add(root);
            path.addAll(chain);
            path.add(paragraph);
            var preserve = false;
            for (var element : path) {
                var space = element.attribute(XmlReader.XML_NAMESPACE, "space");
                if (space != null) {
                    preserve = space.equals("preserve");
                }
            }
            return preserve;
        }

        /**
         * Returns whether a span is a ruby container, whose whitespace-only text is not shown.
         *
         * @param element the TTML element
         * @return {@code true} for a {@code container}, {@code baseContainer} or {@code textContainer} span
         */
        private static boolean rubyContainer(XmlReader.Element element) {
            for (var attribute : element.attributes()) {
                if (isStyling(attribute.namespace()) && attribute.name().equals("ruby")) {
                    var value = attribute.value().strip();
                    return value.equals("container") || value.equals("baseContainer") || value.equals("textContainer");
                }
            }
            return false;
        }

        /**
         * Returns whether two texts look the same.
         *
         * @param a the first text
         * @param b the second text
         * @return {@code true} when their runs and placement are equal
         */
        private static boolean sameContent(StyledText a, StyledText b) {
            return a.runs().equals(b.runs()) && a.alignment() == b.alignment()
                    && Double.compare(a.x(), b.x()) == 0 && Double.compare(a.y(), b.y()) == 0;
        }

        /**
         * Returns the region a paragraph flows into: its own, a descendant span's, or an ancestor's.
         *
         * @param paragraph the {@code p} element
         * @param chain the containers from {@code body} to the paragraph's parent
         * @return the region identifier, or {@code null}
         */
        private String regionOf(XmlReader.Element paragraph, List<XmlReader.Element> chain) {
            var own = paragraph.attribute("", "region");
            if (own != null) {
                return own.strip();
            }
            var nested = nestedRegion(paragraph, 0);
            if (nested != null) {
                return nested;
            }
            for (var index = chain.size() - 1; index >= 0; index--) {
                var region = chain.get(index).attribute("", "region");
                if (region != null) {
                    return region.strip();
                }
            }
            return null;
        }

        /**
         * Returns the region of the first descendant span that names one.
         *
         * @param element the element to search
         * @param depth the nesting depth
         * @return the region identifier, or {@code null}
         */
        private static String nestedRegion(XmlReader.Element element, int depth) {
            if (depth > 64) {
                return null;
            }
            for (var child : element.elements()) {
                var region = child.attribute("", "region");
                if (region != null) {
                    return region.strip();
                }
                var nested = nestedRegion(child, depth + 1);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }

        /**
         * Collects the times at which the content of a paragraph changes.
         *
         * @param element an element of the paragraph
         * @param times receives the times
         * @param bounds the paragraph interval
         */
        private void collectTimes(XmlReader.Element element, TreeSet<Long> times, Interval bounds) {
            for (var child : element.elements()) {
                addInside(times, intervals.get(child), bounds);
                collectTimes(child, times, bounds);
            }
        }

        /**
         * Adds the start and end of an interval that fall strictly inside bounds.
         *
         * @param times receives the times
         * @param interval the interval, or {@code null}
         * @param bounds the interval of the paragraph
         */
        private static void addInside(TreeSet<Long> times, Interval interval, Interval bounds) {
            if (interval == null) {
                return;
            }
            if (interval.begin() > bounds.begin() && interval.begin() < bounds.end()) {
                times.add(interval.begin());
            }
            if (interval.end() > bounds.begin() && interval.end() < bounds.end()) {
                times.add(interval.end());
            }
        }

        /**
         * Appends the content of an element shown at a time.
         *
         * @param element the TTML element
         * @param computed its computed style
         * @param background the background color of the nearest span or paragraph that sets one
         * @param baseFont the font family of the paragraph's region and containers
         * @param baseSize the font size of the paragraph's region and containers
         * @param time the moment, in microseconds
         * @param builder receives the runs
         * @param preserve whether whitespace is preserved
         */
        private void content(XmlReader.Element element, Computed computed, long background, String baseFont,
                             double baseSize, long time, StyledText.Builder builder, boolean preserve) {
            var ownBackground = backgroundOf(element);
            var effectiveBackground = ownBackground != StyledText.NO_COLOR ? ownBackground : background;
            var style = (computed.bold() ? StyledText.BOLD : 0) | (computed.italic() ? StyledText.ITALIC : 0)
                    | (computed.underline() ? StyledText.UNDERLINE : 0)
                    | (computed.strikeout() ? StyledText.STRIKEOUT : 0);
            var format = new StyledText.Run("", style, computed.color() == 0xFFFFFFFFL ? StyledText.NO_COLOR
                    : computed.color(), effectiveBackground == 0 ? StyledText.NO_COLOR : effectiveBackground,
                    computed.font().equals(baseFont) ? "" : computed.font(),
                    Math.abs(computed.size() - baseSize) < 0.01 ? Double.NaN : computed.size(), StyledText.NO_KARAOKE);
            var sequential = "seq".equals(element.attribute("", "timeContainer"));
            var ruby = rubyContainer(element);
            for (var child : element.children()) {
                if (child instanceof String text) {
                    if (computed.hidden() || computed.undisplayed() || sequential || ruby && text.isBlank()) {
                        continue;
                    }
                    builder.format(format);
                    builder.append(preserve ? text.replace("\t", " ") : collapse(text));
                    continue;
                }
                var nested = (XmlReader.Element) child;
                if (!isTtml(nested.namespace())) {
                    continue;
                }
                var interval = intervals.get(nested);
                if (interval != null && !interval.contains(time)) {
                    continue;
                }
                if (nested.name().equals("br")) {
                    if (!computed.undisplayed()) {
                        builder.format(format);
                        builder.append('\n');
                    }
                } else if (nested.name().equals("span")) {
                    var space = nested.attribute(XmlReader.XML_NAMESPACE, "space");
                    var keep = space == null ? preserve : space.equals("preserve");
                    var spanStyle = compute(nested, computed, null);
                    if (!spanStyle.undisplayed()) {
                        content(nested, spanStyle, effectiveBackground, baseFont, baseSize, time, builder, keep);
                    }
                }
            }
        }

        /**
         * Returns the background color an element specifies itself; background colors are not inherited.
         *
         * @param element the TTML element
         * @return the color, or {@link StyledText#NO_COLOR} when it specifies none
         */
        private long backgroundOf(XmlReader.Element element) {
            var specified = new LinkedHashMap<String, String>(referenced(element, 0));
            specified.putAll(inline(element));
            var value = specified.get("backgroundColor");
            if (value == null) {
                return StyledText.NO_COLOR;
            }
            var color = SubtitleColors.parseCss(value);
            return color == StyledText.NO_COLOR || (color >>> 24) == 0 ? 0 : color;
        }

        /**
         * Collapses runs of whitespace into single spaces, as TTML's default whitespace handling does.
         *
         * @param text the character data
         * @return the collapsed text
         */
        private static String collapse(String text) {
            var builder = new StringBuilder(text.length());
            var space = false;
            for (var index = 0; index < text.length(); index++) {
                var character = text.charAt(index);
                if (character == ' ' || character == '\t' || character == '\n' || character == '\r') {
                    space = true;
                    continue;
                }
                if (space) {
                    builder.append(' ');
                    space = false;
                }
                builder.append(character);
            }
            if (space) {
                builder.append(' ');
            }
            return builder.toString();
        }

        /**
         * Removes the spaces collapsing leaves at the start and end of each line.
         *
         * @param styled the text of a paragraph
         * @param preserve whether whitespace is preserved
         * @return the trimmed text
         */
        private static StyledText trimLines(StyledText styled, boolean preserve) {
            if (preserve) {
                return styled;
            }
            var runs = new ArrayList<StyledText.Run>();
            var atLineStart = true;
            for (var run : styled.runs()) {
                var text = new StringBuilder();
                for (var index = 0; index < run.text().length(); index++) {
                    var character = run.text().charAt(index);
                    if (character == ' ' && atLineStart) {
                        continue;
                    }
                    if (character == '\n') {
                        trimEnd(runs, text);
                        atLineStart = true;
                        text.append('\n');
                        continue;
                    }
                    atLineStart = false;
                    text.append(character);
                }
                runs.add(run.withText(text.toString()));
            }
            trimEnd(runs, new StringBuilder());
            var merged = new StyledText.Builder();
            for (var run : runs) {
                if (!run.text().isEmpty()) {
                    merged.format(run);
                    merged.append(run.text());
                }
            }
            var result = merged.build();
            return new StyledText(result.runs(), styled.alignment(), styled.x(), styled.y(), styled.voice());
        }

        /**
         * Removes the spaces at the end of the current line.
         *
         * @param runs the runs completed so far
         * @param text the characters of the run being built
         */
        private static void trimEnd(List<StyledText.Run> runs, StringBuilder text) {
            while (!text.isEmpty() && text.charAt(text.length() - 1) == ' ') {
                text.setLength(text.length() - 1);
            }
            if (!text.isEmpty()) {
                return;
            }
            for (var index = runs.size() - 1; index >= 0; index--) {
                var run = runs.get(index);
                var value = run.text();
                var end = value.length();
                while (end > 0 && value.charAt(end - 1) == ' ') {
                    end--;
                }
                runs.set(index, run.withText(value.substring(0, end)));
                if (end > 0) {
                    return;
                }
            }
        }

        /**
         * Gives a paragraph's text the placement of its alignment and region.
         *
         * @param styled the text
         * @param computed the computed style of the paragraph
         * @param region the region, or {@code null} for the default region, the whole frame
         * @return the placed text
         */
        private static StyledText placed(StyledText styled, Computed computed, Region region) {
            var column = computed.textAlign();
            var row = computed.displayAlign();
            var alignment = row * 3 + column + 1;
            if (region == null) {
                return alignment == 2 ? styled : styled.withPlacement(alignment, Double.NaN, Double.NaN);
            }
            var x = region.x() + region.width() * column / 2.0;
            var y = region.y() + region.height() * (2 - row) / 2.0;
            var defaultX = column / 2.0 * 0.94 + 0.03;
            var defaultY = (2 - row) / 2.0 * 0.94 + 0.03;
            if (Math.abs(x - defaultX) < DEFAULT_ANCHOR_TOLERANCE && Math.abs(y - defaultY) < DEFAULT_ANCHOR_TOLERANCE) {
                return alignment == 2 ? styled : styled.withPlacement(alignment, Double.NaN, Double.NaN);
            }
            return styled.withPlacement(alignment, Math.clamp(x, 0, 1), Math.clamp(y, 0, 1));
        }
    }

    /**
     * Parses a decimal number.
     *
     * @param text the number
     * @return the value, or {@code NaN} when it is not a finite number
     */
    private static double number(String text) {
        var value = text.strip();
        if (value.isEmpty() || value.length() > 40) {
            return Double.NaN;
        }
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            if (!(character >= '0' && character <= '9' || character == '.' || character == '-' || character == '+')) {
                return Double.NaN;
            }
        }
        try {
            var parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) ? parsed : Double.NaN;
        } catch (NumberFormatException exception) {
            return Double.NaN;
        }
    }

    /**
     * Adds two times, saturating at {@link #INDEFINITE}.
     *
     * @param a a time
     * @param b a time
     * @return the sum
     */
    private static long saturatingAdd(long a, long b) {
        if (a == INDEFINITE || b == INDEFINITE) {
            return INDEFINITE;
        }
        var sum = a + b;
        return sum < a ? INDEFINITE : sum;
    }

}
