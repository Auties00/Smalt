package com.github.auties00.smalt.internal.caption;

import java.text.Normalizer;

/**
 * Maps teletext character codes to Unicode: the G0 primary sets with the Latin national option sub-sets and the G2
 * supplementary sets of ETSI EN 300 706 clause 15.6, selected by the designation codes of its tables 32 and 33.
 *
 * <p>Every table holds the 96 characters of codes 0x20 to 0x7F; a G2 table holds the combining form of each
 * diacritical mark of its column 4, and {@code U+0000} where no character is defined.
 *
 * <p>The Arabic sets draw each letter in the joining form the transmitter chose (isolated, initial, medial or
 * final); every form maps to the letter itself, so the text shapes again wherever it is shown, and the two lam-alef
 * ligatures map to {@link #LAM_ALEF}. The cell that draws the descending end of a final seen, sheen, sad or dad maps
 * to {@link #JOINING_TAIL}, which stands for no character.
 */
final class TeletextCharsets {
    /**
     * The character of the Arabic cell that only completes the final form of the letter before it.
     */
    static final char JOINING_TAIL = '\uFFFF';

    /**
     * The lam-alef ligature, which stands for a lam followed by an alef.
     */
    static final char LAM_ALEF = '\uFEFB';

    /**
     * The positions of the national option characters, in the order of the sub-sets.
     */
    private static final int[] NATIONAL_POSITIONS = {0x23, 0x24, 0x40, 0x5B, 0x5C, 0x5D, 0x5E, 0x5F, 0x60, 0x7B,
            0x7C, 0x7D, 0x7E};

    static final String LATIN = " !\"#\u00A4%&'()*+,-./"
            + "0123456789:;<=>?"
            + "@ABCDEFGHIJKLMNO"
            + "PQRSTUVWXYZ[\\]^_"
            + "`abcdefghijklmno"
            + "pqrstuvwxyz{|}~\u25A0";

    static final String CYRILLIC_1 = " !\"#$%&'()*+,-./"
            + "0123456789:;<=>?"
            + "\u0427\u0410\u0411\u0426\u0414\u0415\u0424\u0413\u0425\u0418\u0408\u041A\u041B\u041C\u041D\u041E"
            + "\u041F\u040C\u0420\u0421\u0422\u0423\u0412\u0403\u0409\u040A\u0417\u040B\u0416\u0402\u0428\u040F"
            + "\u0447\u0430\u0431\u0446\u0434\u0435\u0444\u0433\u0445\u0438\u0458\u043A\u043B\u043C\u043D\u043E"
            + "\u043F\u045C\u0440\u0441\u0442\u0443\u0432\u0453\u0459\u045A\u0437\u045B\u0436\u0452\u0448\u25A0";

    static final String CYRILLIC_2 = " !\"#$%\u044B'()*+,-./"
            + "0123456789:;<=>?"
            + "\u042E\u0410\u0411\u0426\u0414\u0415\u0424\u0413\u0425\u0418\u0419\u041A\u041B\u041C\u041D\u041E"
            + "\u041F\u042F\u0420\u0421\u0422\u0423\u0416\u0412\u042C\u042A\u0417\u0428\u042D\u0429\u0427\u042B"
            + "\u044E\u0430\u0431\u0446\u0434\u0435\u0444\u0433\u0445\u0438\u0439\u043A\u043B\u043C\u043D\u043E"
            + "\u043F\u044F\u0440\u0441\u0442\u0443\u0436\u0432\u044C\u044A\u0437\u0448\u044D\u0449\u0447\u25A0";

    static final String CYRILLIC_3 = " !\"#$%\u0457'()*+,-./"
            + "0123456789:;<=>?"
            + "\u042E\u0410\u0411\u0426\u0414\u0415\u0424\u0413\u0425\u0418\u0419\u041A\u041B\u041C\u041D\u041E"
            + "\u041F\u042F\u0420\u0421\u0422\u0423\u0416\u0412\u042C\u0406\u0417\u0428\u0404\u0429\u0427\u0407"
            + "\u044E\u0430\u0431\u0446\u0434\u0435\u0444\u0433\u0445\u0438\u0439\u043A\u043B\u043C\u043D\u043E"
            + "\u043F\u044F\u0440\u0441\u0442\u0443\u0436\u0432\u044C\u0456\u0437\u0448\u0454\u0449\u0447\u25A0";

    static final String GREEK = " !\"#$%&'()*+,-./"
            + "0123456789:;\u00AB=\u00BB?"
            + "\u0390\u0391\u0392\u0393\u0394\u0395\u0396\u0397\u0398\u0399\u039A\u039B\u039C\u039D\u039E\u039F"
            + "\u03A0\u03A1\u0384\u03A3\u03A4\u03A5\u03A6\u03A7\u03A8\u03A9\u03AA\u03AB\u03AC\u03AD\u03AE\u03AF"
            + "\u03B0\u03B1\u03B2\u03B3\u03B4\u03B5\u03B6\u03B7\u03B8\u03B9\u03BA\u03BB\u03BC\u03BD\u03BE\u03BF"
            + "\u03C0\u03C1\u03C2\u03C3\u03C4\u03C5\u03C6\u03C7\u03C8\u03C9\u03CA\u03CB\u03CC\u03CD\u03CE\u25A0";

    static final String HEBREW = " !\"\u00A3$%&'()*+,-./"
            + "0123456789:;<=>?"
            + "@ABCDEFGHIJKLMNO"
            + "PQRSTUVWXYZ\u2190\u00BD\u2192\u2191#"
            + "\u05D0\u05D1\u05D2\u05D3\u05D4\u05D5\u05D6\u05D7\u05D8\u05D9\u05DA\u05DB\u05DC\u05DD\u05DE\u05DF"
            + "\u05E0\u05E1\u05E2\u05E3\u05E4\u05E5\u05E6\u05E7\u05E8\u05E9\u05EA\u20AA\u2016\u00BE\u00F7\u25A0";

    static final String ARABIC = " !\"\u00A3$%\uFFFF\u064A)(*+,-./"
            + "0123456789:\u061B>=<\u061F"
            + "\u0629\u0621\u0628\u0628\u062A\u062A\u0627\u0627\u0628\u0629\u062A\u062B\u062C\u062D\u062E\u062F"
            + "\u0630\u0631\u0632\u0633\u0634\u0635\u0636\u0637\u0638\u0639\u063A\u062B\u062C\u062D\u062E#"
            + "\u0640\u0641\u0642\u0643\u0644\u0645\u0646\u0647\u0648\u0649\u064A\u062B\u062C\u062D\u062E\u064A"
            + "\u0649\u0639\u063A\u0641\u0641\u0642\u0642\u0643\u0644\u0644\u0645\u0645\u0646\u0646\uFEFB\u25A0";

    static final String LATIN_G2 = " \u00A1\u00A2\u00A3$\u00A5#\u00A7\u00A4\u2018\u201C\u00AB\u2190\u2191\u2192\u2193"
            + "\u00B0\u00B1\u00B2\u00B3\u00D7\u00B5\u00B6\u00B7\u00F7\u2019\u201D\u00BB\u00BC\u00BD\u00BE\u00BF"
            + "\u0000\u0300\u0301\u0302\u0303\u0304\u0306\u0307\u0308\u0323\u030A\u0327\u0332\u030B\u0328\u030C"
            + "\u2015\u00B9\u00AE\u00A9\u2122\u266A\u20AC\u2030\u03B1\u0000\u0000\u0000\u215B\u215C\u215D\u215E"
            + "\u03A9\u00C6\u0110\u00AA\u0126\u0000\u0132\u013F\u0141\u00D8\u0152\u00BA\u00DE\u0166\u014A\u0149"
            + "\u0138\u00E6\u0111\u00F0\u0127\u0131\u0133\u0140\u0142\u00F8\u0153\u00DF\u00FE\u0167\u014B\u25A0";

    static final String CYRILLIC_G2 = " \u00A1\u00A2\u00A3"
            + "$\u00A5\u0000\u00A7\u0000\u2018\u201C\u00AB\u2190\u2191\u2192\u2193"
            + "\u00B0\u00B1\u00B2\u00B3\u00D7\u00B5\u00B6\u00B7\u00F7\u2019\u201D\u00BB\u00BC\u00BD\u00BE\u00BF"
            + "\u0000\u0300\u0301\u0302\u0303\u0304\u0306\u0307\u0308\u0323\u030A\u0327\u0332\u030B\u0328\u030C"
            + "\u2015\u00B9\u00AE\u00A9\u2122\u266A\u20AC\u2030\u03B1\u0141\u0197\u00DF\u215B\u215C\u215D\u215E"
            + "DEFGIJKLNQRSUVWZ"
            + "defgijklnqrsuvwz";

    static final String GREEK_G2 = " ab\u00A3ehi\u00A7:\u2018\u201Ck\u2190\u2191\u2192\u2193"
            + "\u00B0\u00B1\u00B2\u00B3xmnp\u00F7\u2019\u201Dt\u00BC\u00BD\u00BEx"
            + "\u0000\u0300\u0301\u0302\u0303\u0304\u0306\u0307\u0308\u0323\u030A\u0327\u0332\u030B\u0328\u030C"
            + "?\u00B9\u00AE\u00A9\u2122\u266A\u20AC\u2030\u03B1\u038A\u038E\u038F\u215B\u215C\u215D\u215E"
            + "CDFGJLQRSUVWYZ\u0386\u0388"
            + "cdfgjlqrsuvwyz\u0389\u25A0";

    static final String ARABIC_G2 = " \u0639\u0622\u0623\u0624"
            + "\u0625\u0626\u0626\u0686\u0686\u0686\u067E\u067E\u067E\u0698\u06AF"
            + "\u0660\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668\u0669\u063A\u063A\uFEFB\u0647\u0647\u0647"
            + "\u00E0ABCDEFGHIJKLMNO"
            + "PQRSTUVWXYZ\u00EB\u00EA\u00F9\u00EE\u0639"
            + "\u00E9abcdefghijklmno"
            + "pqrstuvwxyz\u00E2\u00F4\u00FB\u00E7\u0000";

    static final String NATIONAL_ENGLISH = "\u00A3$@\u2190\u00BD\u2192\u2191"
            + "#\u2015\u00BC\u2016\u00BE\u00F7";

    static final String NATIONAL_GERMAN = "#$\u00A7\u00C4\u00D6\u00DC^"
            + "_\u00B0\u00E4\u00F6\u00FC\u00DF";

    static final String NATIONAL_SWEDISH = "#\u00A4\u00C9\u00C4\u00D6\u00C5\u00DC"
            + "_\u00E9\u00E4\u00F6\u00E5\u00FC";

    static final String NATIONAL_ITALIAN = "\u00A3$\u00E9\u00B0\u00E7\u2192\u2191"
            + "#\u00F9\u00E0\u00F2\u00E8\u00EC";

    static final String NATIONAL_FRENCH = "\u00E9\u00EF\u00E0\u00EB\u00EA\u00F9\u00EE"
            + "#\u00E8\u00E2\u00F4\u00FB\u00E7";

    static final String NATIONAL_PORTUGUESE_SPANISH = "\u00E7$\u00A1\u00E1\u00E9\u00ED\u00F3"
            + "\u00FA\u00BF\u00FC\u00F1\u00E8\u00E0";

    static final String NATIONAL_CZECH_SLOVAK = "#\u016F\u010D\u0165\u017E\u00FD\u00ED"
            + "\u0159\u00E9\u00E1\u011B\u00FA\u0161";

    static final String NATIONAL_POLISH = "#\u0144\u0105\u01B5\u015A\u0141\u0107"
            + "\u00F3\u0119\u017C\u015B\u0142\u017A";

    static final String NATIONAL_TURKISH = "\u20BA\u011F\u0130\u015E\u00D6\u00C7\u00DC"
            + "\u011E\u0131\u015F\u00F6\u00E7\u00FC";

    static final String NATIONAL_SERBIAN = "#\u00CB\u010C\u0106\u017D\u0110\u0160"
            + "\u00EB\u010D\u0107\u017E\u0111\u0161";

    static final String NATIONAL_RUMANIAN = "#\u00A4\u0162\u00C2\u015E\u0102\u00CE"
            + "\u0131\u0163\u00E2\u015F\u0103\u00EE";

    static final String NATIONAL_ESTONIAN = "#\u00F5\u0160\u00C4\u00D6\u017D\u00DC"
            + "\u00D5\u0161\u00E4\u00F6\u017E\u00FC";

    static final String NATIONAL_LETTISH = "#$\u0160\u0117\u0119\u017D\u010D"
            + "\u016B\u0161\u0105\u0173\u017E\u012F";

    private TeletextCharsets() {
        throw new AssertionError();
    }

    /**
     * Returns the G0 set and national option sub-set a designation code selects.
     *
     * @param designation the 7-bit designation code of table 32
     * @return the 96 characters of the set, with the national option characters in place
     */
    static String g0(int designation) {
        var national = switch (designation) {
            case 0x00, 0x10, 0x40 -> NATIONAL_ENGLISH;
            case 0x01, 0x09, 0x11, 0x21 -> NATIONAL_GERMAN;
            case 0x02, 0x0A, 0x12 -> NATIONAL_SWEDISH;
            case 0x03, 0x0B, 0x13 -> NATIONAL_ITALIAN;
            case 0x04, 0x0C, 0x14, 0x44 -> NATIONAL_FRENCH;
            case 0x05, 0x15 -> NATIONAL_PORTUGUESE_SPANISH;
            case 0x06, 0x0E, 0x26 -> NATIONAL_CZECH_SLOVAK;
            case 0x08 -> NATIONAL_POLISH;
            case 0x16, 0x36 -> NATIONAL_TURKISH;
            case 0x1D -> NATIONAL_SERBIAN;
            case 0x1F -> NATIONAL_RUMANIAN;
            case 0x22 -> NATIONAL_ESTONIAN;
            case 0x23 -> NATIONAL_LETTISH;
            default -> null;
        };
        var base = switch (designation) {
            case 0x20 -> CYRILLIC_1;
            case 0x24 -> CYRILLIC_2;
            case 0x25 -> CYRILLIC_3;
            case 0x37 -> GREEK;
            case 0x55 -> HEBREW;
            case 0x47, 0x57 -> ARABIC;
            default -> LATIN;
        };
        if (base != LATIN) {
            return base;
        }
        var characters = (national != null ? national : NATIONAL_ENGLISH).toCharArray();
        var table = LATIN.toCharArray();
        for (var index = 0; index < NATIONAL_POSITIONS.length; index++) {
            table[NATIONAL_POSITIONS[index] - 0x20] = characters[index];
        }
        return new String(table);
    }

    /**
     * Returns the G0 set a designation code selects as packets X/26 address it, without national option
     * characters.
     *
     * @param designation the 7-bit designation code of table 32
     * @return the 96 characters of the set
     */
    static String g0Plain(int designation) {
        return switch (designation) {
            case 0x20 -> CYRILLIC_1;
            case 0x24 -> CYRILLIC_2;
            case 0x25 -> CYRILLIC_3;
            case 0x37 -> GREEK;
            case 0x55 -> HEBREW;
            case 0x47, 0x57 -> ARABIC;
            default -> LATIN;
        };
    }

    /**
     * Returns the G2 set that goes with a designation code.
     *
     * @param designation the 7-bit designation code of table 32
     * @return the 96 characters of the set
     */
    static String g2(int designation) {
        return switch (designation) {
            case 0x20, 0x24, 0x25 -> CYRILLIC_G2;
            case 0x37 -> GREEK_G2;
            case 0x40, 0x44, 0x47, 0x55, 0x57 -> ARABIC_G2;
            default -> LATIN_G2;
        };
    }

    /**
     * Composes a character with a diacritical mark of column 4 of a G2 set.
     *
     * @param base the character
     * @param mark the mark index, 1 to 15
     * @return the composed character, or the character alone when no precomposed form exists
     */
    static int compose(int base, int mark) {
        var combining = LATIN_G2.charAt(0x40 - 0x20 + mark);
        var composed = Normalizer.normalize(new String(Character.toChars(base)) + combining, Normalizer.Form.NFC);
        return composed.codePointCount(0, composed.length()) == 1 ? composed.codePointAt(0) : base;
    }
}
