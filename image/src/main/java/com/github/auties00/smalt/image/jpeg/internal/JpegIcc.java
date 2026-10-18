/* Ports libjpeg-turbo (jdicc.c, jpeg_read_icc_profile). Copyright (C) 1997-1998, Thomas G. Lane, Todd Newman;
   Copyright (C) 2017, D. R. Commander; IJG License. Modified for Smalt. */
package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Splits an ICC profile into {@code APP2 ICC_PROFILE} segments and reassembles one from them, the
 * one rule for every JPEG reader and writer of this module.
 *
 * <p>Each segment carries the identifier {@code "ICC_PROFILE\0"}, a 1-based sequence number, the
 * total number of segments, and one chunk of the profile. A collector returns the profile only when
 * every segment agrees on the total, every sequence number from 1 to the total appears exactly
 * once, and the chunks are not all empty; otherwise the profile is absent, since a partial profile
 * is worse than none. A collector is not thread-safe.
 */
public final class JpegIcc {
    /**
     * The identifier at the start of every {@code APP2 ICC_PROFILE} payload.
     */
    public static final byte[] ID = "ICC_PROFILE\0".getBytes(StandardCharsets.ISO_8859_1);

    /**
     * The size of the payload header: the identifier, the sequence number and the segment count.
     */
    public static final int HEADER = 14;

    /**
     * The largest profile chunk of one segment: the 65533-byte payload limit less the header.
     */
    private static final int MAX_CHUNK = 65533 - HEADER;

    /**
     * The largest number of segments, the range of the count byte.
     */
    private static final int MAX_SEGMENTS = 255;

    /**
     * The chunk of each sequence number, index 0 unused.
     */
    private final byte[][] chunks = new byte[MAX_SEGMENTS + 1][];

    /**
     * The total number of segments declared by the first segment, or zero before it.
     */
    private int total;

    /**
     * Whether an inconsistent segment was seen, which discards the profile.
     */
    private boolean invalid;

    /**
     * Constructs a collector without any chunk.
     */
    public JpegIcc() {
    }

    /**
     * Splits a profile into complete {@code APP2} segments, marker and length included, of at most
     * 65519 profile bytes each, numbered from 1; an empty profile gives one empty segment.
     *
     * @param profile the ICC profile bytes
     * @return the segments, in order
     * @throws MediaException.LimitExceeded if the profile needs more than 255 segments
     */
    public static List<byte[]> segments(byte[] profile) {
        var count = Math.max(1, Math.ceilDiv(profile.length, MAX_CHUNK));
        if (count > MAX_SEGMENTS) {
            throw new MediaException.LimitExceeded("ICC profile of " + profile.length + " bytes exceeds "
                    + MAX_SEGMENTS + " JPEG segments");
        }
        var result = new ArrayList<byte[]>(count);
        for (var index = 0; index < count; index++) {
            var start = index * MAX_CHUNK;
            var length = Math.min(MAX_CHUNK, profile.length - start);
            var segment = new byte[4 + HEADER + length];
            segment[0] = (byte) 0xFF;
            segment[1] = (byte) 0xE2;
            segment[2] = (byte) ((2 + HEADER + length) >>> 8);
            segment[3] = (byte) (2 + HEADER + length);
            System.arraycopy(ID, 0, segment, 4, ID.length);
            segment[4 + ID.length] = (byte) (index + 1);
            segment[5 + ID.length] = (byte) count;
            System.arraycopy(profile, start, segment, 4 + HEADER, length);
            result.add(segment);
        }
        return result;
    }

    /**
     * {@return whether a segment payload is an {@code APP2 ICC_PROFILE} payload: the identifier
     * followed by the sequence number and the segment count}
     *
     * @param data the array holding the payload
     * @param offset the index of the payload's first byte
     * @param length the payload length
     */
    public static boolean matches(byte[] data, int offset, int length) {
        return length >= HEADER && Arrays.equals(data, offset, offset + ID.length, ID, 0, ID.length);
    }

    /**
     * Records the payload of one {@code APP2 ICC_PROFILE} segment; a payload that does not
     * {@link #matches match} is ignored.
     *
     * @param data the array holding the payload
     * @param offset the index of the payload's first byte, the start of the identifier
     * @param length the payload length
     */
    public void add(byte[] data, int offset, int length) {
        if (!matches(data, offset, length) || invalid) {
            return;
        }
        var sequence = data[offset + ID.length] & 0xFF;
        var count = data[offset + ID.length + 1] & 0xFF;
        if (count == 0 || (total != 0 && count != total) || sequence == 0 || sequence > count
                || chunks[sequence] != null) {
            invalid = true;
            return;
        }
        total = count;
        chunks[sequence] = Arrays.copyOfRange(data, offset + HEADER, offset + length);
    }

    /**
     * {@return the reassembled profile, or {@code null} when it is absent, incomplete, inconsistent
     * or empty}
     */
    public byte[] profile() {
        if (invalid || total == 0) {
            return null;
        }
        var length = 0L;
        for (var sequence = 1; sequence <= total; sequence++) {
            if (chunks[sequence] == null) {
                return null;
            }
            length += chunks[sequence].length;
        }
        if (length == 0 || length > Integer.MAX_VALUE - 16) {
            return null;
        }
        var profile = new byte[(int) length];
        var at = 0;
        for (var sequence = 1; sequence <= total; sequence++) {
            System.arraycopy(chunks[sequence], 0, profile, at, chunks[sequence].length);
            at += chunks[sequence].length;
        }
        return profile;
    }
}
