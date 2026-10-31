package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.track.PacketSideData;

import java.util.Optional;
import java.util.TreeMap;

/**
 * Reads RFC 3640 payloads into access units.
 *
 * <p>The AU timestamps come from the RTP timestamp, the {@code CTS-delta} fields and, without them, the AU index
 * deltas times the AU duration. A fragmented AU is joined from its fragments and dropped when one is lost; without
 * AU sizes, which tell where an AU starts, the AU whose packets follow a loss is emitted as incomplete. When the
 * sender interleaves (an index delta above 0 or a {@code maxDisplacement}), AUs wait in a de-interleaving buffer
 * until no earlier AU can still arrive: until a packet whose first AU is later arrives, or until they are
 * {@code maxDisplacement} older than the latest AU.
 */
final class Mpeg4GenericAssembler extends Assembler {
    /** One AU waiting in the de-interleaving buffer. */
    private record Unit(byte[] data, boolean key, boolean complete) {
    }

    private final Mpeg4GenericFormat format;
    private final TreeMap<Long, Unit> buffer = new TreeMap<>();
    private final ByteSink fragment;
    private boolean fragmentOpen;
    private long fragmentTimestamp;
    private int fragmentSize;
    private boolean fragmentKey;
    private boolean fragmentComplete;
    private boolean interleaved;
    private long lastEmitted = Long.MIN_VALUE;

    Mpeg4GenericAssembler(Output out, long limit, Mpeg4GenericFormat format) {
        super(out, limit);
        this.format = format;
        this.fragment = new ByteSink(limit);
        this.interleaved = format.maxDisplacement > 0;
    }

    @Override
    Optional<byte[]> codecConfig() {
        return format.config.length == 0 ? Optional.empty() : Optional.of(format.config.clone());
    }

    @Override
    void accept(RtpPacket packet, long timestamp, boolean gap) {
        var payload = packet.payload();
        if (gap && fragmentOpen) {
            fragmentOpen = false;
        }
        var count = 0;
        var sizes = new int[8];
        var times = new long[8];
        var keys = new boolean[8];
        var position = 0;
        if (format.hasHeaders()) {
            if (payload.length < 2) {
                throw new MediaException.Malformed("AU-headers-length is truncated");
            }
            var bits = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
            var bytes = (bits + 7) / 8;
            if (2 + bytes > payload.length) {
                throw new MediaException.Malformed("AU header section of " + bits + " bits runs past the payload");
            }
            var reader = new MsbBitReader(payload, 2, bytes);
            long index = 0;
            while (reader.bitPosition() < bits) {
                var first = count == 0;
                var size = (int) reader.readLong(format.sizeLength);
                var indexBits = first ? format.indexLength : format.indexDeltaLength;
                var indexValue = reader.readLong(indexBits);
                if (!first) {
                    index += indexValue + 1;
                    if (indexValue != 0) {
                        interleaved = true;
                    }
                }
                var time = timestamp + index * format.duration;
                if (format.ctsDeltaLength > 0 && reader.read(1) == 1) {
                    var delta = reader.readLong(format.ctsDeltaLength);
                    var shift = 64 - format.ctsDeltaLength;
                    var signed = (delta << shift) >> shift;
                    if (!first) {
                        time = timestamp + signed;
                    }
                }
                if (format.dtsDeltaLength > 0 && reader.read(1) == 1) {
                    reader.readLong(format.dtsDeltaLength);
                }
                var key = !format.randomAccessIndication || reader.read(1) == 1;
                reader.readLong(format.streamStateIndication);
                if (reader.bitPosition() > bits) {
                    throw new MediaException.Malformed("AU header runs past AU-headers-length");
                }
                if (count == sizes.length) {
                    sizes = java.util.Arrays.copyOf(sizes, count * 2);
                    times = java.util.Arrays.copyOf(times, count * 2);
                    keys = java.util.Arrays.copyOf(keys, count * 2);
                }
                sizes[count] = format.sizeLength > 0 ? size : format.constantSize;
                times[count] = time;
                keys[count] = key;
                count++;
            }
            position = 2 + bytes;
        }
        if (format.auxiliaryDataSizeLength > 0) {
            var reader = new MsbBitReader(payload, position, payload.length - position);
            var auxBits = reader.readLong(format.auxiliaryDataSizeLength);
            var total = (format.auxiliaryDataSizeLength + auxBits + 7) / 8;
            if (reader.overrun() || total > payload.length - position) {
                throw new MediaException.Malformed("auxiliary section runs past the payload");
            }
            position += (int) total;
        }
        var remaining = payload.length - position;
        if (remaining <= 0) {
            throw new MediaException.Malformed("RFC 3640 payload holds no access unit data");
        }
        if (count == 0) {
            if (format.constantSize > 0) {
                count = remaining / format.constantSize;
                if (count * format.constantSize != remaining) {
                    throw new MediaException.Malformed("payload of " + remaining + " bytes is not a multiple of "
                            + "constantSize=" + format.constantSize);
                }
                if (count > sizes.length) {
                    sizes = new int[count];
                    times = new long[count];
                    keys = new boolean[count];
                }
                for (var index = 0; index < count; index++) {
                    sizes[index] = format.constantSize;
                    times[index] = timestamp + (long) index * format.duration;
                    keys[index] = true;
                }
            } else {
                count = 1;
                sizes[0] = -1;
                times[0] = timestamp;
                keys[0] = true;
            }
        }
        if (count == 1 && (sizes[0] < 0 || sizes[0] > remaining)) {
            acceptFragment(payload, position, remaining, sizes[0], timestamp, keys[0], packet.marker(), gap);
            return;
        }
        long total = 0;
        for (var index = 0; index < count; index++) {
            total += sizes[index];
        }
        if (total != remaining) {
            throw new MediaException.Malformed("AU sizes add up to " + total + " bytes, the payload holds "
                    + remaining);
        }
        fragmentOpen = false;
        if (interleaved) {
            release(times[0], false);
        }
        for (var index = 0; index < count; index++) {
            var data = java.util.Arrays.copyOfRange(payload, position, position + sizes[index]);
            position += sizes[index];
            queue(times[index], data, keys[index], true);
        }
        release(interleaved ? times[0] : Long.MAX_VALUE, false);
        if (interleaved && format.maxDisplacement > 0 && !buffer.isEmpty()) {
            release(buffer.lastKey() - format.maxDisplacement + 1, false);
        }
    }

    private void acceptFragment(byte[] payload, int position, int length, int size, long timestamp, boolean key,
                                boolean marker, boolean gap) {
        if (fragmentOpen && fragmentTimestamp != timestamp) {
            fragmentOpen = false;
        }
        if (!fragmentOpen) {
            fragment.clear();
            fragmentOpen = true;
            fragmentTimestamp = timestamp;
            fragmentSize = size;
            fragmentKey = key;
            fragmentComplete = size >= 0 || !gap;
        }
        fragment.write(payload, position, length);
        var done = size >= 0 ? fragment.size() >= size : marker;
        if (size >= 0 && fragment.size() > size) {
            fragmentOpen = false;
            throw new MediaException.Malformed("AU fragments exceed the AU size " + size);
        }
        if (!done && marker) {
            fragmentOpen = false;
            return;
        }
        if (done) {
            fragmentOpen = false;
            if (interleaved) {
                release(timestamp, false);
            }
            queue(timestamp, fragment.toByteArray(), fragmentKey, fragmentComplete);
            release(interleaved ? timestamp : Long.MAX_VALUE, false);
        }
    }

    private void queue(long timestamp, byte[] data, boolean key, boolean complete) {
        if (timestamp <= lastEmitted) {
            return;
        }
        buffer.putIfAbsent(timestamp, new Unit(data, key, complete));
    }

    private void release(long before, boolean all) {
        while (!buffer.isEmpty() && (all || buffer.firstKey() < before)) {
            var entry = buffer.pollFirstEntry();
            lastEmitted = entry.getKey();
            out.add(entry.getValue().data(), entry.getKey(), entry.getValue().key(), entry.getValue().complete(),
                    format.duration, PacketSideData.NONE);
        }
    }

    @Override
    void flush() {
        fragmentOpen = false;
        release(Long.MAX_VALUE, true);
        lastEmitted = Long.MIN_VALUE;
        interleaved = format.maxDisplacement > 0;
    }
}
