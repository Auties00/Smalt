package com.github.auties00.smalt.internal.io;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Reads and writes multi-byte integers at arbitrary offsets of byte arrays.
 *
 * <p>Every accessor performs the usual array bounds check and throws
 * {@link IndexOutOfBoundsException} when the value does not fit in the array; the 16-, 32- and
 * 64-bit accessors compile to a single unaligned load or store on platforms that support one.
 */
public final class ByteArrays {
    /**
     * Big-endian {@code short} view.
     */
    private static final VarHandle SHORT_BE = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.BIG_ENDIAN);

    /**
     * Little-endian {@code short} view.
     */
    private static final VarHandle SHORT_LE = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * Big-endian {@code int} view.
     */
    private static final VarHandle INT_BE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);

    /**
     * Little-endian {@code int} view.
     */
    private static final VarHandle INT_LE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * Big-endian {@code long} view.
     */
    private static final VarHandle LONG_BE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    /**
     * Little-endian {@code long} view.
     */
    private static final VarHandle LONG_LE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * Prevents instantiation.
     */
    private ByteArrays() {
        throw new AssertionError();
    }

    /**
     * Reads an unsigned big-endian 16-bit value.
     *
     * @param data the array
     * @param offset the index of the first byte
     * @return the value, from 0 to 65535
     */
    public static int u16be(byte[] data, int offset) {
        return (short) SHORT_BE.get(data, offset) & 0xFFFF;
    }

    /**
     * Reads an unsigned little-endian 16-bit value.
     *
     * @param data the array
     * @param offset the index of the first byte
     * @return the value, from 0 to 65535
     */
    public static int u16le(byte[] data, int offset) {
        return (short) SHORT_LE.get(data, offset) & 0xFFFF;
    }

    /**
     * Reads an unsigned 16-bit value in either byte order.
     *
     * @param data the array
     * @param offset the index of the first byte
     * @param little whether the value is little endian
     * @return the value, from 0 to 65535
     */
    public static int u16(byte[] data, int offset, boolean little) {
        return little ? u16le(data, offset) : u16be(data, offset);
    }

    /**
     * Reads an unsigned big-endian 24-bit value.
     *
     * @param data the array
     * @param offset the index of the first byte
     * @return the value, from 0 to {@code 2^24 - 1}
     */
    public static int u24be(byte[] data, int offset) {
        Objects.checkFromIndexSize(offset, 3, data.length);
        return (data[offset] & 0xFF) << 16 | (data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF);
    }

    /**
     * Reads an unsigned little-endian 24-bit value.
     *
     * @param data the array
     * @param offset the index of the first byte
     * @return the value, from 0 to {@code 2^24 - 1}
     */
    public static int u24le(byte[] data, int offset) {
        Objects.checkFromIndexSize(offset, 3, data.length);
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF) << 16;
    }

    /**
     * Reads an unsigned big-endian 32-bit value.
     *
     * @param data the array
     * @param offset the index of the first byte
     * @return the value, from 0 to {@code 2^32 - 1}
     */
    public static long u32be(byte[] data, int offset) {
        return Integer.toUnsignedLong((int) INT_BE.get(data, offset));
    }

    /**
     * Reads an unsigned little-endian 32-bit value.
     *
     * @param data the array
     * @param offset the index of the first byte
     * @return the value, from 0 to {@code 2^32 - 1}
     */
    public static long u32le(byte[] data, int offset) {
        return Integer.toUnsignedLong((int) INT_LE.get(data, offset));
    }

    /**
     * Reads an unsigned 32-bit value in either byte order.
     *
     * @param data the array
     * @param offset the index of the first byte
     * @param little whether the value is little endian
     * @return the value, from 0 to {@code 2^32 - 1}
     */
    public static long u32(byte[] data, int offset, boolean little) {
        return little ? u32le(data, offset) : u32be(data, offset);
    }

    /**
     * Reads a big-endian 32-bit value.
     *
     * @param data the array
     * @param offset the index of the first byte
     * @return the value
     */
    public static int i32be(byte[] data, int offset) {
        return (int) INT_BE.get(data, offset);
    }

    /**
     * Reads a little-endian 32-bit value.
     *
     * @param data the array
     * @param offset the index of the first byte
     * @return the value
     */
    public static int i32le(byte[] data, int offset) {
        return (int) INT_LE.get(data, offset);
    }

    /**
     * Reads a big-endian 64-bit value.
     *
     * @param data the array
     * @param offset the index of the first byte
     * @return the value
     */
    public static long i64be(byte[] data, int offset) {
        return (long) LONG_BE.get(data, offset);
    }

    /**
     * Reads a little-endian 64-bit value.
     *
     * @param data the array
     * @param offset the index of the first byte
     * @return the value
     */
    public static long i64le(byte[] data, int offset) {
        return (long) LONG_LE.get(data, offset);
    }

    /**
     * Writes a big-endian 16-bit value.
     *
     * @param data the array
     * @param offset the index of the first byte
     * @param value the value; bits above 16 are ignored
     */
    public static void put16be(byte[] data, int offset, int value) {
        SHORT_BE.set(data, offset, (short) value);
    }

    /**
     * Writes a little-endian 16-bit value.
     *
     * @param data the array
     * @param offset the index of the first byte
     * @param value the value; bits above 16 are ignored
     */
    public static void put16le(byte[] data, int offset, int value) {
        SHORT_LE.set(data, offset, (short) value);
    }

    /**
     * Writes a big-endian 32-bit value.
     *
     * @param data the array
     * @param offset the index of the first byte
     * @param value the value to write
     */
    public static void put32be(byte[] data, int offset, int value) {
        INT_BE.set(data, offset, value);
    }

    /**
     * Writes a big-endian 32-bit value given as a {@code long}, such as an unsigned 32-bit size.
     *
     * @param data the array
     * @param offset the index of the first byte
     * @param value the value; bits above 32 are ignored
     */
    public static void put32be(byte[] data, int offset, long value) {
        INT_BE.set(data, offset, (int) value);
    }

    /**
     * Writes a little-endian 32-bit value.
     *
     * @param data the array
     * @param offset the index of the first byte
     * @param value the value to write
     */
    public static void put32le(byte[] data, int offset, int value) {
        INT_LE.set(data, offset, value);
    }

    /**
     * Writes a little-endian 32-bit value given as a {@code long}, such as an unsigned 32-bit size.
     *
     * @param data the array
     * @param offset the index of the first byte
     * @param value the value; bits above 32 are ignored
     */
    public static void put32le(byte[] data, int offset, long value) {
        INT_LE.set(data, offset, (int) value);
    }

    /**
     * Writes a big-endian 24-bit value.
     *
     * @param data the array
     * @param offset the index of the first byte
     * @param value the value; bits above 24 are ignored
     */
    public static void put24be(byte[] data, int offset, int value) {
        Objects.checkFromIndexSize(offset, 3, data.length);
        data[offset] = (byte) (value >>> 16);
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) value;
    }

    /**
     * Writes a little-endian 24-bit value.
     *
     * @param data the array
     * @param offset the index of the first byte
     * @param value the value; bits above 24 are ignored
     */
    public static void put24le(byte[] data, int offset, int value) {
        Objects.checkFromIndexSize(offset, 3, data.length);
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
    }

    /**
     * Writes a 16-bit value in either byte order.
     *
     * @param data the array
     * @param offset the index of the first byte
     * @param value the value; bits above 16 are ignored
     * @param little whether to write little endian
     */
    public static void put16(byte[] data, int offset, int value, boolean little) {
        if (little) {
            put16le(data, offset, value);
        } else {
            put16be(data, offset, value);
        }
    }

    /**
     * Writes a 32-bit value in either byte order.
     *
     * @param data the array
     * @param offset the index of the first byte
     * @param value the value; bits above 32 are ignored
     * @param little whether to write little endian
     */
    public static void put32(byte[] data, int offset, long value, boolean little) {
        if (little) {
            put32le(data, offset, value);
        } else {
            put32be(data, offset, value);
        }
    }

    /**
     * Writes a big-endian 64-bit value.
     *
     * @param data the array
     * @param offset the index of the first byte
     * @param value the value to write
     */
    public static void put64be(byte[] data, int offset, long value) {
        LONG_BE.set(data, offset, value);
    }

    /**
     * Writes a little-endian 64-bit value.
     *
     * @param data the array
     * @param offset the index of the first byte
     * @param value the value to write
     */
    public static void put64le(byte[] data, int offset, long value) {
        LONG_LE.set(data, offset, value);
    }
}
