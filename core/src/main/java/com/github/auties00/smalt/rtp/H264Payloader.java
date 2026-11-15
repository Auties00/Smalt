package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.nal.NalUnits;

import java.util.Arrays;

/**
 * Packetizes H.264 access units per RFC 6184 in packetization mode 0, 1 or 2.
 */
final class H264Payloader extends Payloader {
    private final H264Format format;
    private final boolean repeatParameterSets;
    private final int lengthSize;
    private byte[][] arrays = new byte[16][];
    private int[] offsets = new int[16];
    private int[] lengths = new int[16];
    private int count;
    private int don;

    H264Payloader(int maxPayload, RtpPayloadFormat format, RtpPacketizerOptions options, int lengthSize) {
        super(maxPayload);
        this.format = H264Format.of(format);
        this.repeatParameterSets = options.parameterSetsBeforeKeyFrames();
        this.lengthSize = lengthSize;
    }

    @Override
    void packetize(byte[] data, long timestamp, boolean key, Output out) {
        count = 0;
        if (NalUnits.isLengthPrefixed(data, lengthSize)) {
            NalUnits.forEachLengthPrefixed(data, lengthSize, this::add);
        } else if (NalUnits.looksLikeAnnexB(data)) {
            NalUnits.forEachAnnexB(data, this::add);
        } else {
            throw new MediaException.Malformed("H.264 access unit is neither " + lengthSize
                    + "-byte length-prefixed nor Annex B");
        }
        if (repeatParameterSets && !format.parameterSets.isEmpty()) {
            var idr = false;
            var sets = false;
            for (var index = 0; index < count; index++) {
                var type = H264Format.type(arrays[index][offsets[index]]);
                idr |= type == H264Format.IDR;
                sets |= type == H264Format.SPS || type == H264Format.PPS;
            }
            if (idr && !sets) {
                prependParameterSets();
            }
        }
        if (count == 0) {
            return;
        }
        switch (format.mode) {
            case 0 -> packetizeSingle(timestamp, out);
            case 1 -> packetizeNonInterleaved(timestamp, out);
            default -> packetizeInterleaved(timestamp, out);
        }
    }

    private void add(byte[] data, int offset, int length) {
        if (length == 0) {
            return;
        }
        if (count == offsets.length) {
            arrays = Arrays.copyOf(arrays, count * 2);
            offsets = Arrays.copyOf(offsets, count * 2);
            lengths = Arrays.copyOf(lengths, count * 2);
        }
        arrays[count] = data;
        offsets[count] = offset;
        lengths[count] = length;
        count++;
    }

    private void prependParameterSets() {
        var sets = format.parameterSets.size();
        var total = count + sets;
        var newArrays = new byte[Math.max(total, arrays.length)][];
        var newOffsets = new int[newArrays.length];
        var newLengths = new int[newArrays.length];
        for (var index = 0; index < sets; index++) {
            newArrays[index] = format.parameterSets.get(index);
            newLengths[index] = newArrays[index].length;
        }
        System.arraycopy(arrays, 0, newArrays, sets, count);
        System.arraycopy(offsets, 0, newOffsets, sets, count);
        System.arraycopy(lengths, 0, newLengths, sets, count);
        arrays = newArrays;
        offsets = newOffsets;
        lengths = newLengths;
        count = total;
    }

    private void packetizeSingle(long timestamp, Output out) {
        for (var index = 0; index < count; index++) {
            if (lengths[index] > maxPayload) {
                throw new MediaException.LimitExceeded("NAL unit of " + lengths[index]
                        + " bytes does not fit a packet in packetization mode 0");
            }
            out.add(Arrays.copyOfRange(arrays[index], offsets[index], offsets[index] + lengths[index]),
                    index == count - 1, timestamp);
        }
    }

    private void packetizeNonInterleaved(long timestamp, Output out) {
        var index = 0;
        while (index < count) {
            if (lengths[index] > maxPayload) {
                fragment(index, timestamp, index == count - 1, false, out);
                index++;
                continue;
            }
            var end = aggregateEnd(index, 1);
            if (end <= index + 1) {
                end = index + 1;
                out.add(Arrays.copyOfRange(arrays[index], offsets[index], offsets[index] + lengths[index]),
                        index == count - 1, timestamp);
            } else {
                out.add(aggregate(index, end, H264Format.STAP_A, -1), end == count, timestamp);
            }
            index = end;
        }
    }

    private void packetizeInterleaved(long timestamp, Output out) {
        var index = 0;
        while (index < count) {
            if (3 + 2 + lengths[index] > maxPayload) {
                fragment(index, timestamp, index == count - 1, true, out);
                don = (don + 1) & 0xFFFF;
                index++;
                continue;
            }
            var end = aggregateEnd(index, 3);
            out.add(aggregate(index, end, H264Format.STAP_B, don), end == count, timestamp);
            don = (don + end - index) & 0xFFFF;
            index = end;
        }
    }

    /** Returns one past the last NAL unit, from {@code start}, that fits one aggregation packet. */
    private int aggregateEnd(int start, int headerSize) {
        var size = headerSize;
        var end = start;
        while (end < count && size + 2 + lengths[end] <= maxPayload) {
            size += 2 + lengths[end];
            end++;
        }
        return end;
    }

    private byte[] aggregate(int start, int end, int type, int decodingOrder) {
        var size = decodingOrder < 0 ? 1 : 3;
        var nri = 0;
        var forbidden = 0;
        for (var index = start; index < end; index++) {
            size += 2 + lengths[index];
            var header = arrays[index][offsets[index]];
            nri = Math.max(nri, header & 0x60);
            forbidden |= header & 0x80;
        }
        var payload = new byte[size];
        payload[0] = (byte) (forbidden | nri | type);
        var position = 1;
        if (decodingOrder >= 0) {
            payload[1] = (byte) (decodingOrder >>> 8);
            payload[2] = (byte) decodingOrder;
            position = 3;
        }
        for (var index = start; index < end; index++) {
            payload[position] = (byte) (lengths[index] >>> 8);
            payload[position + 1] = (byte) lengths[index];
            System.arraycopy(arrays[index], offsets[index], payload, position + 2, lengths[index]);
            position += 2 + lengths[index];
        }
        return payload;
    }

    private void fragment(int index, long timestamp, boolean last, boolean interleaved, Output out) {
        var data = arrays[index];
        var header = data[offsets[index]] & 0xFF;
        var position = offsets[index] + 1;
        var end = offsets[index] + lengths[index];
        var indicator = (header & 0xE0);
        var type = header & 0x1F;
        if (!interleaved) {
            var sizes = balancedSizes(end - position, maxPayload - 2);
            for (var fragment = 0; fragment < sizes.length; fragment++) {
                var payload = new byte[2 + sizes[fragment]];
                payload[0] = (byte) (indicator | H264Format.FU_A);
                var start = fragment == 0 ? 0x80 : 0;
                var stop = fragment == sizes.length - 1 ? 0x40 : 0;
                payload[1] = (byte) (start | stop | type);
                System.arraycopy(data, position, payload, 2, sizes[fragment]);
                position += sizes[fragment];
                out.add(payload, last && stop != 0, timestamp);
            }
            return;
        }
        var first = true;
        while (position < end) {
            var room = maxPayload - (first ? 4 : 2);
            var size = Math.min(room, end - position);
            var stop = position + size == end ? 0x40 : 0;
            var payload = new byte[(first ? 4 : 2) + size];
            payload[0] = (byte) (indicator | (first ? H264Format.FU_B : H264Format.FU_A));
            payload[1] = (byte) ((first ? 0x80 : 0) | stop | type);
            var dataStart = 2;
            if (first) {
                payload[2] = (byte) (don >>> 8);
                payload[3] = (byte) don;
                dataStart = 4;
            }
            System.arraycopy(data, position, payload, dataStart, size);
            position += size;
            out.add(payload, last && stop != 0, timestamp);
            first = false;
        }
    }
}
