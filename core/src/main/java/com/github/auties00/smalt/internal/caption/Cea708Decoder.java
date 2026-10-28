package com.github.auties00.smalt.internal.caption;

import com.github.auties00.smalt.internal.subtitle.StyledText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Decodes one CEA-708 caption service from the DTVCC caption channel packets carried in {@code cc_data}
 * constructs.
 *
 * <p>The decoder assembles the caption channel packets from the {@code cc_type} 3 and 2 byte pairs, picks the
 * service blocks of its service (1 to 63) and runs the eight windows of the service: their definition, visibility,
 * pen location and attributes (italics, underline, foreground color), the text of the G0, G1 and G2 code sets and
 * the C0 and C1 commands. What a service shows is the text of its visible windows, from the top window to the
 * bottom one; a command that changes the windows shown (a window shown, hidden, cleared, deleted or defined, a
 * carriage return, a form feed, an end of text) completes the caption shown before it. Window and pen styles,
 * fonts, sizes, edges, justification, print direction, scroll effects and the delay command are not modeled: text
 * is shown when it is received.
 */
public final class Cea708Decoder implements CaptionStream {
    private static final int MAX_ROWS = 16;
    private static final int MAX_COLUMNS = 64;

    private final CaptionTimeline timeline;
    private final int service;
    private final Window[] windows = new Window[8];

    /**
     * The caption channel packet being assembled.
     */
    private final byte[] packet = new byte[128];
    private int packetLength;
    private int packetSize = -1;

    private int current = -1;
    private long now;

    /**
     * Constructs a decoder of one service.
     *
     * @param timeline receives the screen
     * @param service the caption service number, 1 to 63
     */
    public Cea708Decoder(CaptionTimeline timeline, int service) {
        this.timeline = timeline;
        this.service = service;
        for (var index = 0; index < windows.length; index++) {
            windows[index] = new Window();
        }
    }

    @Override
    public void decode(byte[] data, long micros) {
        now = micros;
        for (var index = 0; index + 3 <= data.length; index += 3) {
            var header = data[index] & 0xFF;
            if ((header & 0x04) == 0) {
                continue;
            }
            var type = header & 3;
            if (type == 3) {
                if (packetSize > 0) {
                    packet(packetLength);
                }
                var code = data[index + 1] & 0x3F;
                packetSize = code == 0 ? 128 : code * 2;
                packetLength = 0;
                append(data[index + 1], data[index + 2]);
            } else if (type == 2 && packetSize > 0) {
                append(data[index + 1], data[index + 2]);
            }
        }
    }

    @Override
    public void finish(long micros) {
        if (packetSize > 0) {
            packet(packetLength);
        }
        timeline.finish(micros);
    }

    @Override
    public void reset() {
        for (var window : windows) {
            window.delete();
        }
        current = -1;
        packetSize = -1;
        packetLength = 0;
        timeline.reset();
    }

    private void append(byte first, byte second) {
        if (packetLength < packetSize) {
            packet[packetLength++] = first;
        }
        if (packetLength < packetSize) {
            packet[packetLength++] = second;
        }
        if (packetLength >= packetSize) {
            packet(packetLength);
        }
    }

    /**
     * Interprets the service blocks of the packet assembled.
     *
     * @param length the number of bytes assembled
     */
    private void packet(int length) {
        packetSize = -1;
        var position = 1;
        while (position < length) {
            var header = packet[position++] & 0xFF;
            var number = header >> 5;
            var size = header & 0x1F;
            if (number == 7 && size != 0) {
                if (position >= length) {
                    break;
                }
                number = packet[position++] & 0x3F;
            }
            if (number == 0 || size == 0) {
                break;
            }
            var end = Math.min(position + size, length);
            if (number == service) {
                block(position, end);
            }
            position += size;
        }
    }

    /**
     * Runs the codes of one service block.
     */
    private void block(int start, int end) {
        var position = start;
        while (position < end) {
            var code = packet[position++] & 0xFF;
            if (code == 0x10) {
                if (position >= end) {
                    return;
                }
                position = extended(packet[position++] & 0xFF, position, end);
            } else if (code < 0x20) {
                position = c0(code, position, end);
            } else if (code < 0x80) {
                character(code == 0x7F ? 0x266A : code);
            } else if (code < 0xA0) {
                position = c1(code, position, end);
            } else {
                character(code);
            }
        }
    }

    private int c0(int code, int position, int end) {
        switch (code) {
            case 0x03 -> structural(() -> {
            });
            case 0x08 -> {
                var window = window();
                if (window != null && window.column > 0) {
                    window.column--;
                    window.cells[window.row][window.column] = CaptionCells.EMPTY;
                    changed();
                }
            }
            case 0x0C -> structural(() -> {
                var window = window();
                if (window != null) {
                    window.clear();
                    window.row = 0;
                    window.column = 0;
                }
            });
            case 0x0D -> structural(() -> {
                var window = window();
                if (window != null) {
                    window.carriageReturn();
                }
            });
            case 0x0E -> structural(() -> {
                var window = window();
                if (window != null) {
                    Arrays.fill(window.cells[window.row], CaptionCells.EMPTY);
                    window.column = 0;
                }
            });
            default -> {
                if (code >= 0x18) {
                    if (code == 0x18 && position + 2 <= end) {
                        character((packet[position] & 0xFF) << 8 | (packet[position + 1] & 0xFF));
                    }
                    return position + 2;
                }
                if (code >= 0x11) {
                    return position + 1;
                }
            }
        }
        return position;
    }

    private int c1(int code, int position, int end) {
        if (code <= 0x87) {
            if (windows[code - 0x80].defined) {
                current = code - 0x80;
            }
            return position;
        }
        var parameters = switch (code) {
            case 0x88, 0x89, 0x8A, 0x8B, 0x8C, 0x8D -> 1;
            case 0x90, 0x92 -> 2;
            case 0x91 -> 3;
            case 0x97 -> 4;
            case 0x98, 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0x9F -> 6;
            default -> 0;
        };
        if (position + parameters > end) {
            return end;
        }
        var p = position;
        switch (code) {
            case 0x88 -> structural(() -> eachWindow(packet[p], Window::clear));
            case 0x89 -> structural(() -> eachWindow(packet[p], window -> window.visible = true));
            case 0x8A -> structural(() -> eachWindow(packet[p], window -> window.visible = false));
            case 0x8B -> structural(() -> eachWindow(packet[p], window -> window.visible = !window.visible));
            case 0x8C -> structural(() -> {
                eachWindow(packet[p], Window::delete);
                if (current >= 0 && !windows[current].defined) {
                    current = -1;
                }
            });
            case 0x8F -> structural(() -> {
                for (var window : windows) {
                    window.delete();
                }
                current = -1;
            });
            case 0x90 -> {
                var window = window();
                if (window != null) {
                    window.italic = (packet[p + 1] & 0x80) != 0;
                    window.underline = (packet[p + 1] & 0x40) != 0;
                }
            }
            case 0x91 -> {
                var window = window();
                if (window != null) {
                    window.color = packet[p] & 0x3F;
                }
            }
            case 0x92 -> {
                var window = window();
                if (window != null) {
                    window.row = Math.min(packet[p] & 0x0F, window.rows - 1);
                    window.column = Math.min(packet[p + 1] & 0x3F, window.columns - 1);
                }
            }
            case 0x98, 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0x9F -> structural(() -> define(code - 0x98, p));
            default -> {
            }
        }
        return position + parameters;
    }

    private int extended(int code, int position, int end) {
        if (code < 0x20) {
            return position + (code < 0x08 ? 0 : code < 0x10 ? 1 : code < 0x18 ? 2 : 3);
        }
        if (code < 0x80) {
            var mapped = g2(code);
            if (mapped != 0) {
                character(mapped);
            }
            return position;
        }
        if (code < 0x88) {
            return position + 4;
        }
        if (code < 0x90) {
            return position + 5;
        }
        if (code < 0xA0) {
            return position >= end ? end : position + 1 + (packet[position] & 0x3F);
        }
        if (code == 0xA0) {
            character('_');
        }
        return position;
    }

    /**
     * Maps a character of the G2 code set.
     *
     * @return the code point, or 0 for an unassigned code
     */
    private static int g2(int code) {
        return switch (code) {
            case 0x20 -> ' ';
            case 0x21 -> 0xA0;
            case 0x25 -> 0x2026;
            case 0x2A -> 0x160;
            case 0x2C -> 0x152;
            case 0x30 -> 0x2588;
            case 0x31 -> 0x2018;
            case 0x32 -> 0x2019;
            case 0x33 -> 0x201C;
            case 0x34 -> 0x201D;
            case 0x35 -> 0x2022;
            case 0x39 -> 0x2122;
            case 0x3A -> 0x161;
            case 0x3C -> 0x153;
            case 0x3D -> 0x2120;
            case 0x3F -> 0x178;
            case 0x76 -> 0x215B;
            case 0x77 -> 0x215C;
            case 0x78 -> 0x215D;
            case 0x79 -> 0x215E;
            case 0x7A -> 0x2502;
            case 0x7B -> 0x2510;
            case 0x7C -> 0x2514;
            case 0x7D -> 0x2500;
            case 0x7E -> 0x2518;
            case 0x7F -> 0x250C;
            default -> 0;
        };
    }

    /**
     * Defines or redefines a window (DF0 to DF7) and makes it the current one.
     */
    private void define(int index, int p) {
        var window = windows[index];
        var rows = Math.min((packet[p + 3] & 0x0F) + 1, MAX_ROWS);
        var columns = Math.min((packet[p + 4] & 0x3F) + 1, MAX_COLUMNS);
        if (!window.defined) {
            window.reset(rows, columns);
        } else if (rows != window.rows || columns != window.columns) {
            window.resize(rows, columns);
        }
        window.visible = (packet[p] & 0x20) != 0;
        window.priority = packet[p] & 7;
        var relative = (packet[p + 1] & 0x80) != 0;
        var vertical = packet[p + 1] & 0x7F;
        window.top = relative ? vertical / 100.0 : vertical / 75.0;
        current = index;
    }

    private void eachWindow(byte bitmap, java.util.function.Consumer<Window> action) {
        for (var index = 0; index < 8; index++) {
            if ((bitmap >> index & 1) != 0 && windows[index].defined) {
                action.accept(windows[index]);
            }
        }
    }

    private Window window() {
        return current >= 0 && windows[current].defined ? windows[current] : null;
    }

    private void character(int codePoint) {
        var window = window();
        if (window == null) {
            return;
        }
        window.cells[window.row][window.column] = CaptionCells.cell(codePoint,
                CaptionCells.attributes(window.color, window.italic, window.underline));
        if (window.column < window.columns - 1) {
            window.column++;
        }
        if (window.visible) {
            changed();
        }
    }

    /**
     * Runs a command that may change the windows shown, completing the caption shown before it when it does.
     */
    private void structural(Runnable command) {
        var before = text();
        command.run();
        var after = text();
        if (!after.equals(before)) {
            timeline.commit(now);
            timeline.changed(now, after);
        }
    }

    private void changed() {
        timeline.changed(now, text());
    }

    /**
     * Returns the text of the visible windows, from the top window down.
     */
    private StyledText text() {
        var visible = new ArrayList<Window>();
        for (var window : windows) {
            if (window.defined && window.visible) {
                visible.add(window);
            }
        }
        if (visible.isEmpty()) {
            return StyledText.EMPTY;
        }
        visible.sort(Comparator.comparingDouble((Window window) -> window.top));
        var rows = new ArrayList<int[]>();
        for (var window : visible) {
            rows.addAll(Arrays.asList(window.cells).subList(0, window.rows));
        }
        var top = visible.getLast().top < 0.5;
        return CaptionCells.text(rows.toArray(new int[0][]), top ? 8 : 0);
    }

    /**
     * Holds one caption window.
     */
    private static final class Window {
        boolean defined;
        boolean visible;
        int priority;
        double top;
        int rows;
        int columns;
        int[][] cells = new int[0][];
        int row;
        int column;
        int color = 0x3F;
        boolean italic;
        boolean underline;

        void reset(int rows, int columns) {
            defined = true;
            this.rows = rows;
            this.columns = columns;
            cells = new int[rows][columns];
            row = 0;
            column = 0;
            color = 0x3F;
            italic = false;
            underline = false;
        }

        void resize(int rows, int columns) {
            var resized = new int[rows][columns];
            for (var index = 0; index < Math.min(rows, this.rows); index++) {
                System.arraycopy(cells[index], 0, resized[index], 0, Math.min(columns, this.columns));
            }
            cells = resized;
            this.rows = rows;
            this.columns = columns;
            row = Math.min(row, rows - 1);
            column = Math.min(column, columns - 1);
        }

        void clear() {
            for (var line : cells) {
                Arrays.fill(line, CaptionCells.EMPTY);
            }
        }

        void delete() {
            defined = false;
            visible = false;
            cells = new int[0][];
            rows = 0;
            columns = 0;
        }

        void carriageReturn() {
            column = 0;
            if (row < rows - 1) {
                row++;
                return;
            }
            for (var index = 0; index < rows - 1; index++) {
                cells[index] = cells[index + 1];
            }
            cells[rows - 1] = new int[columns];
        }
    }
}
