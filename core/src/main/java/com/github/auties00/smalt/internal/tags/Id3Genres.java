package com.github.auties00.smalt.internal.tags;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Maps between genre names and the numeric genre codes of ID3v1, ID3v2 {@code TCON} references
 * and the MP4 {@code gnre} atom.
 */
public final class Id3Genres {
    /**
     * The genre names by code: codes 0 to 79 from the ID3v1 specification and 80 to 191 from the
     * Winamp extensions every tagger recognizes.
     */
    private static final String[] NAMES = {
            "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal",
            "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial",
            "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop",
            "Vocal", "Jazz+Funk", "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game",
            "Sound Clip", "Gospel", "Noise", "AlternRock", "Bass", "Soul", "Punk", "Space", "Meditative",
            "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic", "Darkwave", "Techno-Industrial",
            "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta", "Top 40",
            "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave", "Psychedelic", "Rave",
            "Showtunes", "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical",
            "Rock & Roll", "Hard Rock", "Folk", "Folk-Rock", "National Folk", "Swing", "Fast Fusion", "Bebop",
            "Latin", "Revival", "Celtic", "Bluegrass", "Avantgarde", "Gothic Rock", "Progressive Rock",
            "Psychedelic Rock", "Symphonic Rock", "Slow Rock", "Big Band", "Chorus", "Easy Listening", "Acoustic",
            "Humour", "Speech", "Chanson", "Opera", "Chamber Music", "Sonata", "Symphony", "Booty Bass", "Primus",
            "Porn Groove", "Satire", "Slow Jam", "Club", "Tango", "Samba", "Folklore", "Ballad", "Power Ballad",
            "Rhythmic Soul", "Freestyle", "Duet", "Punk Rock", "Drum Solo", "A capella", "Euro-House",
            "Dance Hall", "Goa", "Drum & Bass", "Club-House", "Hardcore", "Terror", "Indie", "BritPop", "Afro-Punk",
            "Polsk Punk", "Beat", "Christian Gangsta Rap", "Heavy Metal", "Black Metal", "Crossover",
            "Contemporary Christian", "Christian Rock", "Merengue", "Salsa", "Thrash Metal", "Anime", "JPop",
            "Synthpop", "Abstract", "Art Rock", "Baroque", "Bhangra", "Big Beat", "Breakbeat", "Chillout",
            "Downtempo", "Dub", "EBM", "Eclectic", "Electro", "Electroclash", "Emo", "Experimental", "Garage",
            "Global", "IDM", "Illbient", "Industro-Goth", "Jam Band", "Krautrock", "Leftfield", "Lounge",
            "Math Rock", "New Romantic", "Nu-Breakz", "Post-Punk", "Post-Rock", "Psytrance", "Shoegaze",
            "Space Rock", "Trop Rock", "World Music", "Neoclassical", "Audiobook", "Audio Theatre",
            "Neue Deutsche Welle", "Podcast", "Indie Rock", "G-Funk", "Dubstep", "Garage Rock", "Psybient"
    };
    private Id3Genres() {
        throw new AssertionError();
    }

    /**
     * Returns the name of a genre code.
     *
     * @param code the ID3v1 genre number
     * @return the name, or {@code null} for an unknown code
     */
    public static String name(int code) {
        return code >= 0 && code < NAMES.length ? NAMES[code] : null;
    }

    /**
     * Returns the code of a genre name, compared ignoring case.
     *
     * @param name the genre name
     * @return the code, or {@code -1} when the name has none
     */
    public static int code(String name) {
        for (var i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equalsIgnoreCase(name.trim())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Expands the genre references of an ID3v2 {@code TCON} value.
     *
     * <p>Version 2.3 writes references as {@code "(17)"}, optionally followed by a refinement
     * ({@code "(17)Rock and roll"}), with {@code "(RX)"} for remix, {@code "(CR)"} for cover and
     * {@code "(("} for a literal parenthesis; version 2.4 writes one genre per value, a bare
     * number being a reference. Each reference becomes its name.
     *
     * @param value one {@code TCON} value
     * @return the genre names, in order
     */
    public static List<String> expand(String value) {
        var result = new ArrayList<String>();
        var text = value.trim();
        if (!text.isEmpty() && text.chars().allMatch(Character::isDigit) && text.length() <= 3) {
            var name = name(Integer.parseInt(text));
            result.add(name != null ? name : text);
            return result;
        }
        var position = 0;
        while (position < text.length() && text.charAt(position) == '(') {
            if (text.startsWith("((", position)) {
                break;
            }
            var close = text.indexOf(')', position);
            if (close < 0) {
                break;
            }
            var reference = text.substring(position + 1, close);
            var named = switch (reference.toUpperCase(Locale.ROOT)) {
                case "RX" -> "Remix";
                case "CR" -> "Cover";
                default -> {
                    try {
                        yield reference.length() <= 3 ? name(Integer.parseInt(reference)) : null;
                    } catch (NumberFormatException exception) {
                        yield null;
                    }
                }
            };
            if (named == null) {
                break;
            }
            result.add(named);
            position = close + 1;
        }
        var rest = text.substring(position);
        if (rest.startsWith("((")) {
            rest = rest.substring(1);
        }
        if (!rest.isEmpty()) {
            if (!result.isEmpty() && result.getLast().equalsIgnoreCase(rest)) {
                return result;
            }
            if (result.size() == 1) {
                result.set(0, rest);
            } else {
                result.add(rest);
            }
        }
        return result;
    }
}
