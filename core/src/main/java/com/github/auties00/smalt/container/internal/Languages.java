package com.github.auties00.smalt.container.internal;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;

/**
 * Converts language tags to the ISO 639-2 codes {@link com.github.auties00.smalt.track.Track#language()} reports,
 * and those codes back to the shortest language tags.
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

    /**
     * The ISO 639-1 codes of the ISO 639-2 terminology and bibliographic codes that have one.
     */
    private static final Map<String, String> SHORT = shortCodes();

    private Languages() {
        throw new AssertionError();
    }

    /**
     * Builds the table of {@link #SHORT}.
     *
     * @return the ISO 639-1 code of each ISO 639-2 code that has one
     */
    private static Map<String, String> shortCodes() {
        var codes = new HashMap<String, String>();
        for (var code : Locale.getISOLanguages()) {
            String three;
            try {
                three = Locale.of(code).getISO3Language();
            } catch (MissingResourceException exception) {
                continue;
            }
            if (!three.isEmpty()) {
                codes.put(three, code);
                codes.put(BIBLIOGRAPHIC.getOrDefault(three, three), code);
            }
        }
        return Map.copyOf(codes);
    }

    /**
     * Converts an ISO 639-2 code to the language tag of RFC 5646: its ISO 639-1 code when it has one.
     *
     * @param code the three-letter code, such as {@code fre}, or a language tag
     * @return the tag, such as {@code fr}, or {@code code} itself when it has no shorter form
     */
    public static String languageTag(String code) {
        return SHORT.getOrDefault(code.toLowerCase(Locale.ROOT), code);
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
