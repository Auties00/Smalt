package com.github.auties00.smalt.internal.caption;

import java.util.Arrays;

/**
 * Decodes the CEA-608 caption channel CC1, CC2, CC3 or CC4 from {@code cc_data} constructs (47 CFR 15.119, which
 * CEA-608 extends with the extended character sets).
 *
 * <p>The decoder keeps the displayed and non-displayed memories of 15 rows of 32 columns, the cursor and the
 * attributes, and runs the pop-on, roll-up and paint-on styles. It commits a caption to the timeline at a memory
 * flip (End of Caption), an erase of displayed memory, a carriage return in roll-up style and a change of style.
 * Text mode, extended data services and the optional background attributes are read past and not shown; a byte
 * that fails its odd parity check makes its pair ignored, as does the repetition of a control code in the pair that
 * follows it.
 */
public final class Cea608Decoder implements CaptionStream {
    private static final int ROWS = 15;
    private static final int COLUMNS = 32;

    /**
     * The rows (from 0) of the preamble address codes, by first byte (0x10 to 0x17) and by the bit 0x20 of the
     * second byte.
     */
    private static final int[][] PAC_ROWS = {{10, 10}, {0, 1}, {2, 3}, {11, 12}, {13, 14}, {4, 5}, {6, 7}, {8, 9}};

    /**
     * The colors of the preamble address and mid-row codes, two bits per component: white, green, blue, cyan, red,
     * yellow and magenta.
     */
    private static final int[] COLORS = {0x3F, 0x0C, 0x03, 0x0F, 0x30, 0x3C, 0x33};

    /**
     * The basic character set, from 0x20 to 0x7F.
     */
    private static final String BASIC = " !\"#$%&\u2019()\u00E1+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\u00E9]"
            + "\u00ED\u00F3\u00FAabcdefghijklmnopqrstuvwxyz\u00E7\u00F7\u00D1\u00F1\u2588";

    /**
     * The special characters, second bytes 0x30 to 0x3F after 0x11; 0x39 is the transparent space.
     */
    private static final String SPECIAL = "\u00AE\u00B0\u00BD\u00BF\u2122\u00A2\u00A3\u266A\u00E0\u00A0\u00E8\u00E2"
            + "\u00EA\u00EE\u00F4\u00FB";

    /**
     * The extended Spanish, miscellaneous and French characters, second bytes 0x20 to 0x3F after 0x12.
     */
    private static final String EXTENDED_1 = "\u00C1\u00C9\u00D3\u00DA\u00DC\u00FC\u2018\u00A1*'\u2014\u00A9\u2120"
            + "\u2022\u201C\u201D\u00C0\u00C2\u00C7\u00C8\u00CA\u00CB\u00EB\u00CE\u00CF\u00EF\u00D4\u00D9\u00F9"
            + "\u00DB\u00AB\u00BB";

    /**
     * The extended Portuguese, German and Danish characters, second bytes 0x20 to 0x3F after 0x13.
     */
    private static final String EXTENDED_2 = "\u00C3\u00E3\u00CD\u00CC\u00EC\u00D2\u00F2\u00D5\u00F5{}\\^_|~\u00C4"
            + "\u00E4\u00D6\u00F6\u00DF\u00A5\u00A4\u2502\u00C5\u00E5\u00D8\u00F8\u250C\u2510\u2514\u2518";

    private enum Style {
        NONE, POP_ON, ROLL_UP, PAINT_ON
    }

    private final CaptionTimeline timeline;
    private final int field;
    private final int channel;

    private int[][] displayed = new int[ROWS][COLUMNS];
    private int[][] hidden = new int[ROWS][COLUMNS];
    private Style style = Style.NONE;
    private int rollRows = 2;
    private int row = ROWS - 1;
    private int column;
    private int color = 0x3F;
    private boolean italic;
    private boolean underline;

    /**
     * The data channel the last control code of the field addressed, 0 or 1.
     */
    private int activeChannel;

    /**
     * Whether the selected channel is in caption mode rather than text mode.
     */
    private boolean captionMode = true;

    /**
     * Whether extended data service bytes are being read (field 2).
     */
    private boolean xds;

    /**
     * The last control code pair, as {@code b1 << 8 | b2}, or {@code -1}.
     */
    private int lastControl = -1;

    /**
     * The time of the pair decoded.
     */
    private long now;

    /**
     * Constructs a decoder of one channel.
     *
     * @param timeline receives the screen
     * @param channel the channel, 1 to 4 for CC1 to CC4
     */
    public Cea608Decoder(CaptionTimeline timeline, int channel) {
        this.timeline = timeline;
        this.field = channel <= 2 ? 0 : 1;
        this.channel = (channel - 1) & 1;
    }

    @Override
    public void decode(byte[] data, long micros) {
        now = micros;
        for (var index = 0; index + 3 <= data.length; index += 3) {
            var header = data[index] & 0xFF;
            if ((header & 0x04) == 0 || (header & 0x03) != field) {
                continue;
            }
            pair(data[index + 1] & 0xFF, data[index + 2] & 0xFF);
        }
    }

    @Override
    public void finish(long micros) {
        timeline.finish(micros);
    }

    @Override
    public void reset() {
        displayed = new int[ROWS][COLUMNS];
        hidden = new int[ROWS][COLUMNS];
        style = Style.NONE;
        rollRows = 2;
        row = ROWS - 1;
        column = 0;
        resetAttributes();
        activeChannel = 0;
        captionMode = true;
        xds = false;
        lastControl = -1;
        timeline.reset();
    }

    /**
     * Decodes one byte pair of the field.
     *
     * @param b1 the first byte, with its parity bit
     * @param b2 the second byte, with its parity bit
     */
    void pair(int b1, int b2) {
        var parity1 = Integer.bitCount(b1) % 2 == 1;
        var parity2 = Integer.bitCount(b2) % 2 == 1;
        b1 &= 0x7F;
        b2 &= 0x7F;
        if (b1 == 0 && b2 == 0) {
            lastControl = -1;
            return;
        }
        if (b1 >= 0x10 && b1 <= 0x1F) {
            if (!parity1 || !parity2) {
                return;
            }
            xds = false;
            var code = b1 << 8 | b2;
            if (code == lastControl) {
                lastControl = -1;
                return;
            }
            lastControl = code;
            activeChannel = (b1 & 0x08) != 0 ? 1 : 0;
            if (activeChannel == channel) {
                control(b1 & 0x17, b2);
            }
            return;
        }
        lastControl = -1;
        if (field == 1 && b1 >= 0x01 && b1 <= 0x0F) {
            xds = b1 != 0x0F;
            return;
        }
        if (xds || activeChannel != channel || !captionMode || style == Style.NONE) {
            return;
        }
        if (b1 >= 0x20 && parity1) {
            character(BASIC.charAt(b1 - 0x20));
        }
        if (b2 >= 0x20 && parity2) {
            character(BASIC.charAt(b2 - 0x20));
        }
        changed();
    }

    /**
     * Runs one control code of the selected channel.
     *
     * @param b1 the first byte without its channel bit, 0x10 to 0x17
     * @param b2 the second byte
     */
    private void control(int b1, int b2) {
        if (b2 >= 0x40) {
            preamble(b1, b2);
            return;
        }
        if (b2 < 0x20) {
            return;
        }
        switch (b1) {
            case 0x11 -> {
                if (b2 <= 0x2F) {
                    midRow(b2);
                } else if (captionMode && style != Style.NONE) {
                    character(SPECIAL.charAt(b2 - 0x30));
                }
                changed();
            }
            case 0x12, 0x13 -> {
                if (captionMode && style != Style.NONE) {
                    if (column > 0) {
                        column--;
                    }
                    character((b1 == 0x12 ? EXTENDED_1 : EXTENDED_2).charAt(b2 - 0x20));
                    changed();
                }
            }
            case 0x14, 0x15 -> {
                if (b2 <= 0x2F) {
                    command(b2);
                }
            }
            case 0x17 -> {
                if (b2 >= 0x21 && b2 <= 0x23) {
                    column = Math.min(column + b2 - 0x20, COLUMNS - 1);
                }
            }
            default -> {
            }
        }
    }

    private void preamble(int b1, int b2) {
        var target = PAC_ROWS[b1 & 7][(b2 & 0x20) != 0 ? 1 : 0];
        var attributes = b2 & 0x1F;
        resetAttributes();
        underline = (attributes & 1) != 0;
        if (attributes < 0x10) {
            var code = attributes >> 1;
            if (code == 7) {
                italic = true;
            } else {
                color = COLORS[code];
            }
            column = 0;
        } else {
            column = ((attributes & 0x0E) >> 1) * 4;
        }
        if (style == Style.ROLL_UP && target != row) {
            moveWindow(target);
        }
        row = target;
    }

    private void midRow(int b2) {
        var code = (b2 >> 1) & 7;
        underline = (b2 & 1) != 0;
        if (code == 7) {
            italic = true;
        } else {
            color = COLORS[code];
            italic = false;
        }
        if (captionMode && style != Style.NONE) {
            put(' ');
        }
    }

    private void command(int b2) {
        switch (b2) {
            case 0x20 -> {
                captionMode = true;
                if (style == Style.ROLL_UP) {
                    commit();
                    clear(displayed);
                    clear(hidden);
                    changed();
                }
                style = Style.POP_ON;
            }
            case 0x21 -> {
                if (captionMode && column > 0) {
                    column--;
                    memory()[row][column] = CaptionCells.EMPTY;
                    changed();
                }
            }
            case 0x24 -> {
                if (captionMode) {
                    Arrays.fill(memory()[row], column, COLUMNS, CaptionCells.EMPTY);
                    changed();
                }
            }
            case 0x25, 0x26, 0x27 -> {
                captionMode = true;
                var rows = b2 - 0x23;
                if (style != Style.ROLL_UP) {
                    commit();
                    clear(displayed);
                    clear(hidden);
                    style = Style.ROLL_UP;
                    row = ROWS - 1;
                    column = 0;
                } else if (rows < rollRows) {
                    for (var index = 0; index <= row - rows; index++) {
                        Arrays.fill(displayed[index], CaptionCells.EMPTY);
                    }
                }
                rollRows = rows;
                if (row < rollRows - 1) {
                    row = rollRows - 1;
                }
                changed();
            }
            case 0x29 -> {
                captionMode = true;
                if (style == Style.ROLL_UP) {
                    commit();
                    clear(displayed);
                    clear(hidden);
                    changed();
                }
                style = Style.PAINT_ON;
            }
            case 0x2A, 0x2B -> captionMode = false;
            case 0x2C -> {
                commit();
                clear(displayed);
                changed();
            }
            case 0x2D -> {
                if (captionMode && style == Style.ROLL_UP) {
                    commit();
                    var top = Math.max(0, row - rollRows + 1);
                    for (var index = top; index < row; index++) {
                        displayed[index] = displayed[index + 1];
                    }
                    if (top > 0) {
                        Arrays.fill(displayed[top - 1], CaptionCells.EMPTY);
                    }
                    displayed[row] = new int[COLUMNS];
                    column = 0;
                    resetAttributes();
                    changed();
                }
            }
            case 0x2E -> clear(hidden);
            case 0x2F -> {
                captionMode = true;
                commit();
                var swap = displayed;
                displayed = hidden;
                hidden = swap;
                style = Style.POP_ON;
                changed();
            }
            default -> {
            }
        }
    }

    /**
     * Moves the roll-up window, intact, to a new base row.
     */
    private void moveWindow(int base) {
        var moved = new int[ROWS][];
        for (var index = 0; index < ROWS; index++) {
            moved[index] = new int[COLUMNS];
        }
        for (var offset = 0; offset < rollRows; offset++) {
            var from = row - offset;
            var to = base - offset;
            if (from >= 0 && to >= 0) {
                moved[to] = displayed[from];
            }
        }
        displayed = moved;
        changed();
    }

    private void character(char value) {
        put(value);
    }

    private void put(char value) {
        memory()[row][column] = CaptionCells.cell(value, CaptionCells.attributes(color, italic, underline));
        if (column < COLUMNS - 1) {
            column++;
        }
    }

    private int[][] memory() {
        return style == Style.POP_ON ? hidden : displayed;
    }

    private void resetAttributes() {
        color = 0x3F;
        italic = false;
        underline = false;
    }

    private static void clear(int[][] memory) {
        for (var line : memory) {
            Arrays.fill(line, CaptionCells.EMPTY);
        }
    }

    private void commit() {
        timeline.commit(now);
    }

    private void changed() {
        var top = true;
        for (var index = 0; index < ROWS; index++) {
            if (CaptionCells.visible(displayed[index])) {
                top = index < ROWS / 2;
            }
        }
        timeline.changed(now, CaptionCells.text(displayed, top ? 8 : 0));
    }
}
