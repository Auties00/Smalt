package com.github.auties00.smalt.container.internal;

import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;

/**
 * Converts language tags to the ISO 639-2 codes {@link com.github.auties00.smalt.track.Track#language()} reports.
 */
public final class Languages {
    /**
     * The ISO 639-2 bibliographic codes of the languages whose terminology code differs, keyed by
     * the terminology code.
     */
    private static final Map<String, String> BIBLIOGRAPHIC = Map.ofEntries(Map.entry("sqi", "alb"),
            Map.entry("hye", "arm"), Map.entry("eus", "baq"), Map.entry("mya", "bur"), Map.entry("zho", "chi"),
            Map.entry("ces", "cze"), Map.entry("nld", "dut"), Map.entry("fra", "fre"), Map.entry("kat", "geo"),
            Map.entry("deu", "ger"), Map.entry("ell", "gre"), Map.entry("isl", "ice"), Map.entry("mkd", "mac"),
            Map.entry("mri", "mao"), Map.entry("msa", "may"), Map.entry("fas", "per"), Map.entry("ron", "rum"),
            Map.entry("slk", "slo"), Map.entry("bod", "tib"), Map.entry("cym", "wel"));

    private Languages() {
        throw new AssertionError();
    }

    /**
     * Converts a BCP 47 language tag to its ISO 639-2 bibliographic code.
     *
     * @param tag the tag, such as {@code fr-CA}
     * @return the three-letter code, or {@code und} when the tag names no language with one
     */
    public static String bibliographicCode(String tag) {
        String code;
        try {
            code = Locale.forLanguageTag(tag).getISO3Language();
        } catch (MissingResourceException exception) {
            return "und";
        }
        return code.isEmpty() ? "und" : BIBLIOGRAPHIC.getOrDefault(code, code);
    }
}
