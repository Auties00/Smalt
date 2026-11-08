package com.github.auties00.smalt.internal.subtitle;

import com.github.auties00.smalt.internal.xml.XmlReader;
import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Rational;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes cues as a TTML document in the IMSC 1.0.1 text profile.
 *
 * <p>The document has a media time base, the default 32 by 15 cell grid and one region per placement the
 * cues use: for an alignment, a region over the middle 94 percent of the frame with the matching
 * {@code tts:displayAlign} and {@code tts:textAlign}; for an anchored cue, the largest region whose anchor point
 * for that alignment is the cue's anchor. Each cue is a {@code p} in its region, its line breaks {@code br}
 * elements and its
 * runs {@code span} elements with {@code tts:fontWeight}, {@code tts:fontStyle}, {@code tts:textDecoration},
 * {@code tts:color}, {@code tts:backgroundColor}, {@code tts:fontFamily} and {@code tts:fontSize} (a
 * percentage of the default size); karaoke times and the speaker are not written.
 */
public final class TtmlWriter {
    /**
     * The namespaces the documents declare, the TTML namespace first.
     */
    private static final List<String> NAMESPACES = List.of(TtmlDocument.TT, TtmlDocument.TTS, TtmlDocument.TTP,
            TtmlDocument.TTM);

    /**
     * The font size of the document's default, one cell, in reference pixels.
     */
    private static final double DEFAULT_SIZE = StyledText.REFERENCE_HEIGHT / 15.0;

    private TtmlWriter() {
        throw new AssertionError();
    }

    /**
     * Returns a TTML track: a millisecond time base and the namespaces of the documents this class writes.
     *
     * @param id the track identifier
     * @return the track
     */
    public static DataTrack track(int id) {
        return DataTrack.builder().id(id).codec(Codec.TTML).timeBase(Rational.MILLISECONDS).codecConfig(config(NAMESPACES))
                .build();
    }

    /**
     * Returns the {@code stpp} configuration of documents that use some namespaces.
     *
     * @param namespaces the namespaces, the TTML namespace first
     * @return the namespace list, an empty schema location and an empty auxiliary MIME type list, each
     * null-terminated
     */
    public static byte[] config(List<String> namespaces) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(String.join(" ", namespaces.isEmpty() ? List.of(TtmlDocument.TT) : namespaces)
                .getBytes(StandardCharsets.UTF_8));
        out.write(0);
        out.write(0);
        out.write(0);
        return out.toByteArray();
    }

    /**
     * Writes cues as a document.
     *
     * @param cues the cues, in start order
     * @param markup the markup of the cue texts
     * @return the document in UTF-8
     */
    public static byte[] write(List<SubtitleCue> cues, SubtitleMarkup markup) {
        var styled = new ArrayList<TtmlDocument.Cue>(cues.size());
        for (var cue : cues) {
            styled.add(new TtmlDocument.Cue(cue.startMicros(), cue.endMicros(),
                    Markup.parseCue(cue, markup, Markup.Context.of(cue))));
        }
        return writeStyled(styled);
    }

    /**
     * Writes styled cues as a document.
     *
     * @param cues the cues, in start order
     * @return the document in UTF-8
     */
    public static byte[] writeStyled(List<TtmlDocument.Cue> cues) {
        var regions = new LinkedHashMap<String, String>();
        var body = new StringBuilder();
        for (var cue : cues) {
            var styled = cue.text();
            var region = region(styled, regions);
            body.append("      <p begin=\"").append(clock(cue.startMicros())).append("\" end=\"")
                    .append(clock(cue.endMicros())).append("\" region=\"").append(region).append("\">");
            for (var run : styled.runs()) {
                var attributes = attributes(run);
                if (!attributes.isEmpty()) {
                    body.append("<span").append(attributes).append('>');
                }
                var lines = run.text().split("\n", -1);
                for (var index = 0; index < lines.length; index++) {
                    if (index > 0) {
                        body.append("<br/>");
                    }
                    body.append(XmlReader.escape(lines[index]));
                }
                if (!attributes.isEmpty()) {
                    body.append("</span>");
                }
            }
            body.append("</p>\n");
        }
        var out = new StringBuilder(body.length() + 1024);
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        out.append("<tt xmlns=\"").append(TtmlDocument.TT).append("\" xmlns:tts=\"").append(TtmlDocument.TTS)
                .append("\" xmlns:ttp=\"").append(TtmlDocument.TTP).append("\" xmlns:ttm=\"").append(TtmlDocument.TTM)
                .append("\" ttp:timeBase=\"media\" ttp:cellResolution=\"32 15\" xml:lang=\"\">\n");
        out.append("  <head>\n    <layout>\n");
        if (regions.isEmpty()) {
            regions.put(regionName(2), regionElement(regionName(2), 2, Double.NaN, Double.NaN));
        }
        for (var element : regions.values()) {
            out.append("      ").append(element).append('\n');
        }
        out.append("    </layout>\n  </head>\n  <body>\n    <div>\n");
        out.append(body);
        out.append("    </div>\n  </body>\n</tt>\n");
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Returns the region of a cue, adding it to the regions written when it is new.
     *
     * @param styled the cue text
     * @param regions the region elements by identifier
     * @return the identifier
     */
    private static String region(StyledText styled, Map<String, String> regions) {
        var alignment = styled.alignment() == 0 ? 2 : styled.alignment();
        String name;
        if (styled.hasPosition()) {
            name = String.format(Locale.ROOT, "a%d_%d_%d", alignment, Math.round(styled.x() * 10000),
                    Math.round(styled.y() * 10000));
        } else {
            name = regionName(alignment);
        }
        if (!regions.containsKey(name)) {
            regions.put(name, regionElement(name, alignment, styled.x(), styled.y()));
        }
        return name;
    }

    /**
     * Returns the identifier of the region of an alignment.
     *
     * @param alignment the numpad alignment
     * @return the identifier
     */
    private static String regionName(int alignment) {
        return switch (alignment) {
            case 1 -> "bottomLeft";
            case 3 -> "bottomRight";
            case 4 -> "middleLeft";
            case 5 -> "middle";
            case 6 -> "middleRight";
            case 7 -> "topLeft";
            case 8 -> "top";
            case 9 -> "topRight";
            default -> "bottom";
        };
    }

    /**
     * Writes a region element.
     *
     * @param name the identifier
     * @param alignment the numpad alignment
     * @param x the anchor position, or {@code NaN} for the middle of the frame
     * @param y the anchor position, or {@code NaN} for the middle of the frame
     * @return the element
     */
    private static String regionElement(String name, int alignment, double x, double y) {
        var column = (alignment - 1) % 3;
        var row = (alignment - 1) / 3;
        double width;
        double height;
        double left;
        double top;
        if (Double.isNaN(x) || Double.isNaN(y)) {
            width = 0.94;
            height = 0.94;
            left = 0.03;
            top = 0.03;
        } else {
            width = Math.max(0.01, column == 0 ? 1 - x : column == 2 ? x : 2 * Math.min(x, 1 - x));
            height = Math.max(0.01, row == 2 ? 1 - y : row == 0 ? y : 2 * Math.min(y, 1 - y));
            left = Math.clamp(x - width * column / 2.0, 0, 1 - width);
            top = Math.clamp(y - height * (2 - row) / 2.0, 0, 1 - height);
        }
        var display = row == 0 ? "after" : row == 1 ? "center" : "before";
        var text = column == 0 ? "start" : column == 1 ? "center" : "end";
        return "<region xml:id=\"" + name + "\" tts:origin=\"" + percent(left) + " " + percent(top) + "\" tts:extent=\""
                + percent(width) + " " + percent(height) + "\" tts:displayAlign=\"" + display + "\" tts:textAlign=\""
                + text + "\"/>";
    }

    /**
     * Returns the inline style attributes of a run.
     *
     * @param run the styled run
     * @return the attributes with a leading space each, empty for a plain run
     */
    private static String attributes(StyledText.Run run) {
        var builder = new StringBuilder();
        if ((run.style() & StyledText.BOLD) != 0) {
            builder.append(" tts:fontWeight=\"bold\"");
        }
        if ((run.style() & StyledText.ITALIC) != 0) {
            builder.append(" tts:fontStyle=\"italic\"");
        }
        var underline = (run.style() & StyledText.UNDERLINE) != 0;
        var strikeout = (run.style() & StyledText.STRIKEOUT) != 0;
        if (underline || strikeout) {
            builder.append(" tts:textDecoration=\"").append(underline && strikeout ? "underline lineThrough"
                    : underline ? "underline" : "lineThrough").append('"');
        }
        if (run.color() != StyledText.NO_COLOR) {
            builder.append(" tts:color=\"").append(SubtitleColors.css(run.color())).append('"');
        }
        if (run.background() != StyledText.NO_COLOR) {
            builder.append(" tts:backgroundColor=\"").append(SubtitleColors.css(run.background())).append('"');
        }
        if (!run.font().isEmpty()) {
            builder.append(" tts:fontFamily=\"").append(XmlReader.escape(run.font())).append('"');
        }
        if (!Double.isNaN(run.size())) {
            builder.append(" tts:fontSize=\"").append(percentNumber(run.size() / DEFAULT_SIZE)).append("%\"");
        }
        return builder.toString();
    }

    /**
     * Formats a fraction as a percentage with at most two decimals.
     *
     * @param fraction the share of the frame, from 0 to 1
     * @return the percentage with its sign
     */
    private static String percent(double fraction) {
        return percentNumber(fraction) + "%";
    }

    /**
     * Formats a fraction as a percentage number with at most two decimals.
     *
     * @param fraction the share of the frame, from 0 to 1
     * @return the number
     */
    private static String percentNumber(double fraction) {
        var value = Math.round(fraction * 10000) / 100.0;
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    /**
     * Formats a media time as {@code HH:MM:SS.mmm}, rounding to the millisecond.
     *
     * @param micros the time in microseconds; negative values format as zero
     * @return the clock time
     */
    private static String clock(long micros) {
        var millis = Math.max(0, Math.floorDiv(micros + 500, 1000));
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", millis / 3_600_000, millis / 60_000 % 60,
                millis / 1000 % 60, millis % 1000);
    }
}
