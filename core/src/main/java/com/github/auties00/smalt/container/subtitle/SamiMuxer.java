package com.github.auties00.smalt.container.subtitle;

import com.github.auties00.smalt.internal.subtitle.SamiText;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.Codec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;

/**
 * Writes a SAMI ({@code .smi}) file from one or more {@link Codec#SUBRIP} tracks, one language class each.
 *
 * <p>The file declares UTF-8 in a {@code META} tag and one class per track, named after the track's
 * language ({@code ENCC} for an English track, {@code CC} plus the track number for a track without a
 * language) with the language as its {@code lang}. A {@code SYNC} element is written at every time a cue
 * starts or ends, holding for each class the cues shown from then on, overlapping cues of a track on
 * separate lines, or {@code &nbsp;} when it shows nothing. The SubRip styles, colors and fonts become
 * {@code b}, {@code i}, {@code u}, {@code s} and {@code font} tags; times are rounded to the millisecond.
 * Consecutive packets of a track with the same text that follow each other without a gap become one cue.
 *
 * <p>Instances are not thread-safe.
 */
public final class SamiMuxer extends BufferedCueMuxer {
    /**
     * Constructs a muxer writing to a sink from its current position.
     *
     * @param output the sink; not closed by the muxer
     */
    public SamiMuxer(MediaSink output) {
        super(Codec.SUBRIP, 64, output);
    }

    /**
     * Returns whether the muxer accepts tracks of a codec.
     *
     * @param codec the codec to look up
     * @return {@code true} for {@link Codec#SUBRIP} only
     */
    public static boolean supports(Codec codec) {
        return codec == Codec.SUBRIP;
    }

    @Override
    String name() {
        return "SAMI";
    }

    @Override
    String format(List<Entry> entries) {
        var tracks = new ArrayList<SamiText.Track>();
        var used = new HashSet<String>();
        for (var entry : entries) {
            var two = twoLetter(entry.track().language().orElse(""));
            var locale = two.isEmpty() ? null : Locale.of(two);
            var name = two.isEmpty() ? "CC" + entry.track().id() : two.toUpperCase(Locale.ROOT) + "CC";
            if (!used.add(name.toLowerCase(Locale.ROOT))) {
                name = name + entry.track().id();
                used.add(name.toLowerCase(Locale.ROOT));
            }
            var lang = locale == null ? "" : locale.toLanguageTag();
            var title = locale == null ? "" : locale.getDisplayLanguage(Locale.ENGLISH);
            tracks.add(new SamiText.Track(name, lang, title, entry.cues()));
        }
        return SamiText.write(tracks);
    }

    /**
     * Returns the two-letter code of a language.
     *
     * @param code the ISO 639-2 or ISO 639-1 code of a track
     * @return the ISO 639-1 code, or empty when the language has none or is unknown
     */
    private static String twoLetter(String code) {
        var lower = code.strip().toLowerCase(Locale.ROOT);
        if (lower.length() == 2) {
            return lower;
        }
        if (lower.length() != 3) {
            return "";
        }
        for (var candidate : Locale.getISOLanguages()) {
            try {
                if (Locale.of(candidate).getISO3Language().equals(lower)) {
                    return candidate;
                }
            } catch (MissingResourceException ignored) {
                // a language without a three-letter code cannot match
            }
        }
        return "";
    }
}
