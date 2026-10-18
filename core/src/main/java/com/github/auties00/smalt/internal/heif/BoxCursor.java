package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.FourCc;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A reader of the big-endian fields and child boxes of an ISO base media box (ISO/IEC 14496-12)
 * held in memory.
 *
 * <p>A cursor covers a range of an array and knows the type of the box that range holds. Every
 * read checks the range first and throws {@link MediaException.Malformed} naming that box when the
 * field does not fit, so a truncated or lying box never reads past its parent or out of the array.
 * Child cursors share the array and follow the rules of their parent, which come in two forms:
 * <ul>
 *   <li>{@link #of}: the movie rules. Eight zero bytes where a child header would start end the
 *   child list, as the QuickTime terminator does, and the payload of a {@code uuid} box starts with
 *   its 16-byte extended type.</li>
 *   <li>{@link #strict}: the item rules of HEIF. Any eight bytes start a child, and the extended
 *   type of a {@code uuid} box is part of its header.</li>
 * </ul>
 * Instances are not thread-safe.
 */
public final class BoxCursor {
    /**
     * The type of the {@code uuid} box, whose header may carry a 16-byte extended type.
     */
    private static final int UUID = FourCc.of("uuid");

    private final byte[] data;

    /**
     * The index one past the last readable byte.
     */
    private final int end;

    private final int type;

    /**
     * Whether this cursor follows the item rules of {@link #strict}.
     */
    private final boolean strict;

    private int position;

    /**
     * Constructs a cursor over {@code data[offset, end)}.
     *
     * @param data the array
     * @param offset the index of the first readable byte
     * @param end the index one past the last readable byte
     * @param type the four-character code of the box the range holds
     * @param strict whether the item rules of {@link #strict} apply
     * @throws MediaException.Malformed if the range does not lie within {@code data}
     */
    private BoxCursor(byte[] data, int offset, int end, int type, boolean strict) {
        if (offset < 0 || end < offset || end > data.length) {
            throw new MediaException.Malformed("invalid range " + offset + ".." + end + " of box '"
                    + FourCc.printable(type) + "'");
        }
        this.data = data;
        this.position = offset;
        this.end = end;
        this.type = type;
        this.strict = strict;
    }

    /**
     * Returns a cursor over a whole array that follows the movie rules.
     *
     * @param data the payload of the box
     * @param type the four-character code of the box
     * @return the cursor, at the first byte
     */
    public static BoxCursor of(byte[] data, int type) {
        return new BoxCursor(data, 0, data.length, type, false);
    }

    /**
     * Returns a cursor over a whole array that follows the item rules of HEIF.
     *
     * @param data the payload of the box
     * @param type the four-character code of the box
     * @return the cursor, at the first byte
     */
    public static BoxCursor strict(byte[] data, int type) {
        return new BoxCursor(data, 0, data.length, type, true);
    }

    /**
     * {@return the four-character code of the box this cursor reads}
     */
    public int type() {
        return type;
    }

    /**
     * {@return the array this cursor reads, shared, not copied}
     */
    public byte[] data() {
        return data;
    }

    /**
     * {@return the index in {@link #data()} of the next byte to read}
     */
    public int position() {
        return position;
    }

    /**
     * Moves to an index of {@link #data()}, from the start of the array up to the end of the range.
     *
     * @param position the index of the next byte to read
     * @throws MediaException.Malformed if {@code position} is negative or past the end of the range
     */
    public void position(int position) {
        if (position < 0 || position > end) {
            throw new MediaException.Malformed("position " + position + " outside box '" + FourCc.printable(type) + "'");
        }
        this.position = position;
    }

    /**
     * {@return the number of bytes left to read}
     */
    public int remaining() {
        return end - position;
    }

    /**
     * Returns an independent cursor over the unread part of the range, at this cursor's position.
     *
     * @return the copy
     */
    public BoxCursor duplicate() {
        return new BoxCursor(data, position, end, type, strict);
    }

    /**
     * Checks that a number of bytes is left to read.
     *
     * @param count the number of bytes about to be read
     * @throws MediaException.Malformed if {@code count} is negative or fewer bytes are left
     */
    private void need(long count) {
        if (count < 0 || count > end - position) {
            throw new MediaException.Malformed("box '" + FourCc.printable(type) + "' is truncated: needs " + count
                    + " bytes, " + (end - position) + " remain");
        }
    }

    /**
     * Returns the exception that reports this box as truncated, for parsers that detect it
     * themselves.
     *
     * @return the exception, not thrown
     */
    public MediaException.Malformed truncated() {
        return new MediaException.Malformed("box '" + FourCc.printable(type) + "' is truncated");
    }

    /**
     * Skips bytes.
     *
     * @param count the number of bytes to skip
     * @throws MediaException.Malformed if fewer bytes are left
     */
    public void skip(long count) {
        need(count);
        position += (int) count;
    }

    /**
     * Reads an unsigned 8-bit field.
     *
     * @return the value, from 0 to 255
     * @throws MediaException.Malformed if the field does not fit
     */
    public int u8() {
        need(1);
        return data[position++] & 0xFF;
    }

    /**
     * Reads an unsigned 16-bit field.
     *
     * @return the value, from 0 to 65535
     * @throws MediaException.Malformed if the field does not fit
     */
    public int u16() {
        need(2);
        var value = ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
        position += 2;
        return value;
    }

    /**
     * Reads a signed 16-bit field.
     *
     * @return the value, from -32768 to 32767
     * @throws MediaException.Malformed if the field does not fit
     */
    public int s16() {
        return (short) u16();
    }

    /**
     * Reads an unsigned 24-bit field.
     *
     * @return the value, from 0 to 2^24 - 1
     * @throws MediaException.Malformed if the field does not fit
     */
    public int u24() {
        need(3);
        var value = ((data[position] & 0xFF) << 16) | ((data[position + 1] & 0xFF) << 8) | (data[position + 2] & 0xFF);
        position += 3;
        return value;
    }

    /**
     * Reads a signed 32-bit field.
     *
     * @return the value
     * @throws MediaException.Malformed if the field does not fit
     */
    public int s32() {
        need(4);
        var value = ((data[position] & 0xFF) << 24) | ((data[position + 1] & 0xFF) << 16)
                | ((data[position + 2] & 0xFF) << 8) | (data[position + 3] & 0xFF);
        position += 4;
        return value;
    }

    /**
     * Reads an unsigned 32-bit field.
     *
     * @return the value, from 0 to 2^32 - 1
     * @throws MediaException.Malformed if the field does not fit
     */
    public long u32() {
        return s32() & 0xFFFFFFFFL;
    }

    /**
     * Reads an unsigned 64-bit field.
     *
     * @return the value; values of 2^63 and above are negative
     * @throws MediaException.Malformed if the field does not fit
     */
    public long u64() {
        return ((long) s32() << 32) | u32();
    }

    /**
     * Reads an unsigned field of 0, 1, 2, 4 or 8 bytes, as the size fields of {@code iloc}
     * declare.
     *
     * @param size the field size in bytes
     * @return the value, 0 for a zero-byte field; values of 2^63 and above are negative
     * @throws MediaException.Malformed if {@code size} is not one of the allowed sizes or the field
     * does not fit
     */
    public long uN(int size) {
        return switch (size) {
            case 0 -> 0;
            case 1 -> u8();
            case 2 -> u16();
            case 4 -> u32();
            case 8 -> u64();
            default -> throw new MediaException.Malformed("invalid field size " + size + " in box '"
                    + FourCc.printable(type) + "'");
        };
    }

    /**
     * Reads a four-character code.
     *
     * @return the code as a big-endian integer
     * @throws MediaException.Malformed if the field does not fit
     */
    public int fourCc() {
        return s32();
    }

    /**
     * Reads the version and flags of a full box.
     *
     * @return the version in bits 24 to 31 and the flags in bits 0 to 23
     * @throws MediaException.Malformed if the field does not fit
     */
    public int fullBoxHeader() {
        return s32();
    }

    /**
     * Copies bytes out of the box.
     *
     * @param count the number of bytes
     * @return a new array holding them
     * @throws MediaException.Malformed if fewer bytes are left
     */
    public byte[] bytes(int count) {
        need(count);
        var result = Arrays.copyOfRange(data, position, position + count);
        position += count;
        return result;
    }

    /**
     * Copies every byte left in the box.
     *
     * @return a new array holding them
     */
    public byte[] rest() {
        return bytes(remaining());
    }

    /**
     * Reads a null-terminated UTF-8 string; a string that runs to the end of the box without a
     * terminator ends there.
     *
     * @return the string, without its terminator
     */
    public String string() {
        var start = position;
        var stop = start;
        while (stop < end && data[stop] != 0) {
            stop++;
        }
        position = stop < end ? stop + 1 : end;
        return new String(data, start, stop - start, StandardCharsets.UTF_8);
    }

    /**
     * Reads a UTF-8 string field of a fixed length; the string ends at the first zero byte of the
     * field, if any.
     *
     * @param count the field length in bytes
     * @return the string
     * @throws MediaException.Malformed if fewer bytes are left
     */
    public String string(int count) {
        need(count);
        var stop = position;
        var limit = position + count;
        while (stop < limit && data[stop] != 0) {
            stop++;
        }
        var result = new String(data, position, stop - position, StandardCharsets.UTF_8);
        position = limit;
        return result;
    }

    /**
     * Returns a cursor over the next bytes and skips them in this cursor. The slice reports the
     * same box type.
     *
     * @param count the length of the slice
     * @return the slice
     * @throws MediaException.Malformed if fewer bytes are left
     */
    public BoxCursor slice(int count) {
        need(count);
        var slice = new BoxCursor(data, position, position + count, type, strict);
        position += count;
        return slice;
    }

    /**
     * Returns whether a child box starts at the position.
     *
     * @return {@code true} when at least eight bytes are left and, under the movie rules, they are
     * not all zero
     */
    public boolean hasBox() {
        if (end - position < 8) {
            return false;
        }
        if (strict) {
            return true;
        }
        for (var index = position; index < position + 8; index++) {
            if (data[index] != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the header of the child box at the position and returns a cursor over its payload,
     * moving this cursor past the whole child.
     *
     * <p>A 32-bit size of 1 announces a 64-bit size after the type, and a size of 0 extends the
     * child to the end of this cursor's range.
     *
     * @return the cursor over the child's payload, reporting the child's type
     * @throws MediaException.Malformed if the header does not fit, or the size is smaller than the
     * header or runs past the end of the range
     */
    public BoxCursor nextBox() {
        var start = position;
        var size = u32();
        var child = s32();
        if (size == 1) {
            size = u64();
        } else if (size == 0) {
            size = end - start;
        }
        if (strict && child == UUID) {
            skip(16);
        }
        var header = position - start;
        if (size < header || size > end - start) {
            throw new MediaException.Malformed("box '" + FourCc.printable(child) + "' at " + start + " declares size "
                    + size + " outside its " + (end - start) + " byte parent '" + FourCc.printable(type) + "'");
        }
        var limit = start + (int) size;
        var result = new BoxCursor(data, position, limit, child, strict);
        position = limit;
        return result;
    }
}
