package com.github.auties00.smalt.internal.caption;

import com.github.auties00.smalt.internal.subtitle.StyledText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Decodes one page of a DVB teletext stream (ETSI EN 300 706 at presentation level 1.5, carried as ETSI EN 300 472
 * describes) into text.
 *
 * <p>The decoder follows the page through its header packets in parallel or serial magazine mode, stores its display
 * rows 1 to 23 (erasing them when the header's erase bit is set), applies the characters with diacritical marks and
 * the supplementary characters of its X/26 packets, and selects the character set from the X/28/0 or M/29/0
 * designation when the stream sends one and otherwise from the header's national option bits within the language
 * group the track's language implies. A page is shown when its transmission ends (the next header of its magazine,
 * or of any magazine in serial mode) and, for a subtitle page, also at the end of each packet that carried part of
 * it, from the time of the packet that carried its header. On a subtitle or newsflash page only the boxed text is
 * shown; the text keeps the alpha colors of the page, while mosaic characters, concealed text and the header row are
 * not shown. Rows holding Hebrew or Arabic letters, transmitted in the order they are shown, are put in reading
 * order.
 */
public final class TeletextDecoder implements CaptionStream {
    /**
     * The colors of the alpha color codes, two bits per component: black, red, green, yellow, blue, magenta, cyan
     * and white.
     */
    private static final int[] COLORS = {0x00, 0x30, 0x0C, 0x3C, 0x03, 0x33, 0x0F, 0x3F};

    /**
     * The decoded value of every Hamming 8/4 byte, or {@code -1} for a double error.
     */
    private static final byte[] HAMMING_8_4 = hammingTable();

    private final CaptionTimeline timeline;
    private final int magazine;
    private final int page;
    private final int group;

    /**
     * The page number each magazine is transmitting, or {@code -1}.
     */
    private final int[] transmitting = new int[8];

    /**
     * The designation each magazine's packet M/29/0 sets, or {@code -1}.
     */
    private final int[] magazineDesignation = new int[8];

    /**
     * The display rows of the page, 7-bit codes or {@code -1} for a byte that failed its parity check.
     */
    private final int[][] rows = new int[25][40];

    private final List<Integer> enhancements = new ArrayList<>();
    private int pageDesignation = -1;
    private int national;
    private boolean subtitle;
    private boolean receiving;
    private boolean open;
    private boolean touched;
    private boolean serial;
    private long headerMicros;
    private long now;
    private StyledText shown = StyledText.EMPTY;

    /**
     * Constructs a decoder of one page.
     *
     * @param timeline receives the page's text
     * @param pageNumber the page number as written, read as hexadecimal digits: {@code 0x100} to {@code 0x8FF}, the
     * first digit the magazine
     * @param group the designation code group (bits 3 to 6 of a table 32 code) to use with the national option
     * bits of the header when the stream designates no character set
     */
    public TeletextDecoder(CaptionTimeline timeline, int pageNumber, int group) {
        this.timeline = timeline;
        this.magazine = (pageNumber >> 8) & 7;
        this.page = pageNumber & 0xFF;
        this.group = group & 0x78;
        Arrays.fill(transmitting, -1);
        Arrays.fill(magazineDesignation, -1);
        clearRows();
    }

    @Override
    public void decode(byte[] data, long micros) {
        now = micros;
        if (data.length < 1) {
            return;
        }
        touched = false;
        var position = 1;
        while (position + 2 <= data.length) {
            var id = data[position] & 0xFF;
            var length = data[position + 1] & 0xFF;
            var body = position + 2;
            if (body + length > data.length) {
                break;
            }
            if ((id == 0x02 || id == 0x03) && length == 44) {
                unit(data, body);
            }
            position = body + length;
        }
        if (open && touched && subtitle) {
            show(headerMicros);
        }
    }

    @Override
    public void finish(long micros) {
        if (open) {
            show(headerMicros);
            open = false;
        }
        timeline.finish(micros);
        shown = StyledText.EMPTY;
    }

    @Override
    public void reset() {
        Arrays.fill(transmitting, -1);
        Arrays.fill(magazineDesignation, -1);
        clearRows();
        enhancements.clear();
        receiving = false;
        open = false;
        shown = StyledText.EMPTY;
        timeline.reset();
    }

    /**
     * Decodes one teletext packet of a data unit.
     *
     * @param data the PES data field
     * @param offset the index of the data unit's {@code field_parity} byte
     */
    private void unit(byte[] data, int offset) {
        var address1 = hamming(data[offset + 2]);
        var address2 = hamming(data[offset + 3]);
        if (address1 < 0 || address2 < 0) {
            return;
        }
        var packetMagazine = address1 & 7;
        var row = address1 >> 3 | address2 << 1;
        if (row == 0) {
            header(data, offset, packetMagazine);
        } else if (row <= 25) {
            if (receiving && packetMagazine == magazine) {
                for (var column = 0; column < 40; column++) {
                    var value = reverse(data[offset + 4 + column]);
                    rows[row - 1][column] = Integer.bitCount(value) % 2 == 1 ? value & 0x7F : -1;
                }
                touched = true;
            }
        } else if (row == 26) {
            if (receiving && packetMagazine == magazine && hamming(data[offset + 4]) >= 0) {
                for (var triplet = 0; triplet < 13; triplet++) {
                    var value = hamming24(data, offset + 5 + triplet * 3);
                    if (value >= 0) {
                        enhancements.add(value);
                    }
                }
                touched = true;
            }
        } else if (row == 28 || row == 29) {
            var designationCode = hamming(data[offset + 4]);
            var triplet = hamming24(data, offset + 5);
            if (designationCode == 0 && triplet >= 0) {
                var designation = (triplet >> 7) & 0x7F;
                if (row == 29) {
                    magazineDesignation[packetMagazine] = designation;
                } else if (receiving && packetMagazine == magazine) {
                    pageDesignation = designation;
                }
            }
        }
    }

    private void header(byte[] data, int offset, int packetMagazine) {
        var units = hamming(data[offset + 4]);
        var tens = hamming(data[offset + 5]);
        var s2 = hamming(data[offset + 7]);
        var s4 = hamming(data[offset + 9]);
        var control = hamming(data[offset + 11]);
        if (units < 0 || tens < 0 || s2 < 0 || s4 < 0 || control < 0) {
            return;
        }
        serial = (control & 1) != 0;
        if (open && (serial || packetMagazine == magazine)) {
            show(headerMicros);
            open = false;
        }
        var number = tens << 4 | units;
        transmitting[packetMagazine] = number;
        if (packetMagazine == magazine && number == page) {
            receiving = true;
            open = true;
            touched = true;
            headerMicros = now;
            if ((s2 & 8) != 0) {
                clearRows();
                enhancements.clear();
            }
            pageDesignation = -1;
            subtitle = (s4 & 0xC) != 0;
            national = ((control >> 1) & 1) << 2 | ((control >> 2) & 1) << 1 | ((control >> 3) & 1);
        } else if (packetMagazine == magazine || serial) {
            receiving = false;
        }
    }

    /**
     * Shows the page as received so far.
     *
     * @param micros the time the page appears
     */
    private void show(long micros) {
        var text = render();
        if (!text.equals(shown)) {
            timeline.commit(micros);
            timeline.changed(micros, text);
            shown = text;
        }
    }

    /**
     * Renders rows 1 to 23 of the page.
     */
    private StyledText render() {
        var designation = pageDesignation >= 0 ? pageDesignation
                : magazineDesignation[magazine] >= 0 ? magazineDesignation[magazine] : group | national;
        var g0 = TeletextCharsets.g0(designation);
        var plain = TeletextCharsets.g0Plain(designation);
        var g2 = TeletextCharsets.g2(designation);
        var overrides = new int[24][40];
        var activeRow = 0;
        for (var triplet : enhancements) {
            var address = triplet & 0x3F;
            var mode = (triplet >> 6) & 0x1F;
            var value = (triplet >> 11) & 0x7F;
            if (address >= 40) {
                if (mode == 0x1F) {
                    break;
                }
                if (mode == 0x01 || mode == 0x04) {
                    activeRow = address == 40 ? 24 : address - 40;
                } else if (mode == 0x07 && address == 0x3F) {
                    activeRow = 0;
                }
                continue;
            }
            if (activeRow < 1 || activeRow > 23 || value < 0x20) {
                continue;
            }
            if (mode == 0x0F) {
                var character = g2.charAt(value - 0x20);
                if (character != 0) {
                    overrides[activeRow][address] = character;
                }
            } else if (mode == 0x09) {
                overrides[activeRow][address] = plain.charAt(value - 0x20);
            } else if (mode >= 0x10) {
                var base = mode == 0x10 && value == 0x2A ? '@' : plain.charAt(value - 0x20);
                overrides[activeRow][address] = mode == 0x10 ? base : TeletextCharsets.compose(base, mode - 0x10);
            }
        }
        var screen = new int[23][];
        var lastVisible = -1;
        var skip = false;
        for (var row = 1; row <= 23; row++) {
            if (skip) {
                skip = false;
                screen[row - 1] = new int[40];
                continue;
            }
            var cells = new int[40];
            var color = 7;
            var mosaic = false;
            var boxed = false;
            var concealed = false;
            for (var column = 0; column < 40; column++) {
                var value = rows[row - 1][column];
                if (value >= 0 && value < 0x20) {
                    switch (value) {
                        case 0x18 -> concealed = true;
                        case 0x0D, 0x0F -> skip = true;
                        default -> {
                        }
                    }
                    cells[column] = CaptionCells.EMPTY;
                    switch (value) {
                        case 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07 -> {
                            color = value;
                            mosaic = false;
                            concealed = false;
                        }
                        case 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17 -> {
                            mosaic = true;
                            concealed = false;
                        }
                        case 0x0A -> boxed = false;
                        case 0x0B -> boxed = true;
                        default -> {
                        }
                    }
                    continue;
                }
                var shownHere = (!subtitle || boxed) && !concealed;
                if (!shownHere || value < 0 || (mosaic && (value & 0x20) != 0)) {
                    cells[column] = CaptionCells.EMPTY;
                    continue;
                }
                var character = overrides[row][column] != 0 ? overrides[row][column] : g0.charAt(value - 0x20);
                cells[column] = CaptionCells.cell(character, CaptionCells.attributes(COLORS[color], false, false));
            }
            cells = readingOrder(cells);
            if (CaptionCells.visible(cells)) {
                lastVisible = row;
            }
            screen[row - 1] = cells;
        }
        return CaptionCells.text(screen, lastVisible >= 0 && lastVisible <= 12 ? 8 : 0);
    }

    /**
     * Puts the cells of a display row in reading order.
     *
     * <p>A row is transmitted as it is shown, from left to right. A row that holds right-to-left letters reads from
     * right to left, except for the runs of left-to-right letters and European digits and the runs of Arabic-Indic
     * digits inside it: the row is reversed, those runs are put back in their order, the brackets outside them are
     * mirrored, the cells that only complete a final letter form are removed and every lam-alef ligature becomes a
     * lam and an alef. Any other row is returned as it is.
     *
     * @param cells the cells of the row, from the left
     * @return the cells in reading order
     */
    private static int[] readingOrder(int[] cells) {
        var rightToLeft = false;
        for (var cell : cells) {
            if (direction(CaptionCells.codePoint(cell)) < 0) {
                rightToLeft = true;
                break;
            }
        }
        if (!rightToLeft) {
            return cells;
        }
        var count = 0;
        var expanded = 0;
        var reversed = new int[cells.length];
        for (var index = cells.length - 1; index >= 0; index--) {
            var codePoint = CaptionCells.codePoint(cells[index]);
            if (codePoint == TeletextCharsets.JOINING_TAIL) {
                continue;
            }
            if (codePoint == TeletextCharsets.LAM_ALEF) {
                expanded++;
            }
            reversed[count++] = cells[index];
        }
        var start = 0;
        while (start < count) {
            var codePoint = CaptionCells.codePoint(reversed[start]);
            if (direction(codePoint) <= 0) {
                var mirrored = mirror(codePoint);
                if (mirrored != codePoint) {
                    reversed[start] = CaptionCells.withCodePoint(reversed[start], mirrored);
                }
                start++;
                continue;
            }
            var run = direction(codePoint);
            var end = start;
            for (var scan = start + 1; scan < count; scan++) {
                var direction = direction(CaptionCells.codePoint(reversed[scan]));
                if (direction == run) {
                    end = scan;
                } else if (direction != 0) {
                    break;
                }
            }
            for (int low = start, high = end; low < high; low++, high--) {
                var swap = reversed[low];
                reversed[low] = reversed[high];
                reversed[high] = swap;
            }
            start = end + 1;
        }
        var ordered = new int[count + expanded];
        var at = 0;
        for (var index = 0; index < count; index++) {
            var cell = reversed[index];
            if (CaptionCells.codePoint(cell) == TeletextCharsets.LAM_ALEF) {
                ordered[at++] = CaptionCells.withCodePoint(cell, 0x0644);
                ordered[at++] = CaptionCells.withCodePoint(cell, 0x0627);
            } else {
                ordered[at++] = cell;
            }
        }
        return ordered;
    }

    /**
     * Returns the reading direction of a character.
     *
     * @param codePoint the character
     * @return {@code -1} for a right-to-left letter, {@code 1} for a left-to-right letter or a European digit,
     * {@code 2} for an Arabic-Indic digit, {@code 0} for any other character
     */
    private static int direction(int codePoint) {
        return switch (Character.getDirectionality(codePoint)) {
            case Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> -1;
            case Character.DIRECTIONALITY_LEFT_TO_RIGHT, Character.DIRECTIONALITY_EUROPEAN_NUMBER -> 1;
            case Character.DIRECTIONALITY_ARABIC_NUMBER -> 2;
            default -> 0;
        };
    }

    /**
     * Returns the bracket that faces the other way, or the character itself when it is not a bracket.
     *
     * @param codePoint the character
     * @return the mirrored character
     */
    private static int mirror(int codePoint) {
        return switch (codePoint) {
            case '(' -> ')';
            case ')' -> '(';
            case '[' -> ']';
            case ']' -> '[';
            case '{' -> '}';
            case '}' -> '{';
            case '<' -> '>';
            case '>' -> '<';
            case 0xAB -> 0xBB;
            case 0xBB -> 0xAB;
            default -> codePoint;
        };
    }

    private void clearRows() {
        for (var row : rows) {
            Arrays.fill(row, 0x20);
        }
    }

    /**
     * Reverses the bit order of a byte as the PES packet carries it.
     */
    private static int reverse(byte value) {
        return Integer.reverse(value & 0xFF) >>> 24;
    }

    /**
     * Decodes a Hamming 8/4 byte as the PES packet carries it.
     *
     * @return the four data bits, D1 as the least significant, or {@code -1} for a double error
     */
    private static int hamming(byte value) {
        return HAMMING_8_4[reverse(value)];
    }

    /**
     * Decodes a Hamming 24/18 triplet as the PES packet carries it.
     *
     * @return the 18 data bits, D1 as the least significant, or {@code -1} for a double error
     */
    static int hamming24(byte[] data, int offset) {
        var word = reverse(data[offset]) | reverse(data[offset + 1]) << 8 | reverse(data[offset + 2]) << 16;
        var syndrome = 0;
        for (var test = 0; test < 5; test++) {
            var parity = 0;
            for (var bit = 1; bit <= 23; bit++) {
                if ((bit >> test & 1) != 0) {
                    parity ^= word >> (bit - 1) & 1;
                }
            }
            if (parity == 0) {
                syndrome |= 1 << test;
            }
        }
        var overall = Integer.bitCount(word & 0xFFFFFF) % 2 == 1;
        if (syndrome != 0) {
            if (overall) {
                return -1;
            }
            if (syndrome <= 24) {
                word ^= 1 << (syndrome - 1);
            }
        }
        return (word >> 2 & 1) | (word >> 4 & 7) << 1 | (word >> 8 & 0x7F) << 4 | (word >> 16 & 0x7F) << 11;
    }

    private static byte[] hammingTable() {
        var codewords = new int[16];
        for (var value = 0; value < 16; value++) {
            var d1 = value & 1;
            var d2 = value >> 1 & 1;
            var d3 = value >> 2 & 1;
            var d4 = value >> 3 & 1;
            var p1 = 1 ^ d1 ^ d3 ^ d4;
            var p2 = 1 ^ d1 ^ d2 ^ d4;
            var p3 = 1 ^ d1 ^ d2 ^ d3;
            var p4 = 1 ^ p1 ^ d1 ^ p2 ^ d2 ^ p3 ^ d3 ^ d4;
            codewords[value] = p1 | d1 << 1 | p2 << 2 | d2 << 3 | p3 << 4 | d3 << 5 | p4 << 6 | d4 << 7;
        }
        var table = new byte[256];
        for (var input = 0; input < 256; input++) {
            table[input] = -1;
            for (var value = 0; value < 16; value++) {
                if (Integer.bitCount(input ^ codewords[value]) <= 1) {
                    table[input] = (byte) value;
                }
            }
        }
        return table;
    }
}
