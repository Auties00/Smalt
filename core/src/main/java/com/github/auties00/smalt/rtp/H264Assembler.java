package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.codec.NalConfigs;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.track.PacketSideData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Reassembles H.264 access units from RFC 6184 payloads of every packetization mode.
 *
 * <p>In modes 0 and 1 the NAL units are written in arrival order and an access unit ends at the marker bit or at
 * a timestamp change. In mode 2 the NAL units go through a de-interleaving buffer ordered by decoding order number,
 * released as section 7.2.2 prescribes, and an access unit ends where the timestamp of the released NAL units
 * changes.
 */
final class H264Assembler extends Assembler {
    private final H264Format format;
    private final Map<Integer, byte[]> sequenceSets = new TreeMap<>();
    private final Map<Integer, byte[]> pictureSets = new TreeMap<>();
    private byte[] config;
    private byte[] reportedConfig;
    private final ByteSink unit;
    private boolean open;
    private long unitTimestamp;
    private boolean unitComplete;
    private boolean unitKey;
    private boolean suspect;
    private int fragmentStart = -1;

    private final TreeMap<Long, Nal> interleaved = new TreeMap<>();
    private final ByteSink fragment;
    private long fragmentDon;
    private long fragmentTimestamp;
    private boolean fragmentOpen;
    private boolean donStarted;
    private long lastDon;
    private long lastReleasedDon = Long.MIN_VALUE;
    private int vclCount;

    /** One NAL unit waiting in the de-interleaving buffer. */
    private record Nal(byte[] data, long timestamp) {
    }

    H264Assembler(Output out, long limit, RtpPayloadFormat format) {
        super(out, limit);
        this.format = H264Format.of(format);
        this.unit = new ByteSink(limit);
        this.fragment = new ByteSink(limit);
        for (var set : this.format.parameterSets) {
            noteParameterSet(set, 0, set.length);
        }
        reportedConfig = config;
    }

    @Override
    Optional<byte[]> codecConfig() {
        return Optional.ofNullable(config).map(byte[]::clone);
    }

    @Override
    boolean decodeOrderIsPresentationOrder() {
        return false;
    }

    @Override
    void accept(RtpPacket packet, long timestamp, boolean gap) {
        var payload = packet.payload();
        if (payload.length == 0) {
            throw new MediaException.Malformed("empty H.264 payload");
        }
        if (format.mode == 2) {
            acceptInterleaved(payload, timestamp, gap);
            return;
        }
        if (gap) {
            if (open) {
                unitComplete = false;
                dropFragment();
                suspect |= timestamp != unitTimestamp;
            } else {
                suspect = true;
            }
        }
        if (open && timestamp != unitTimestamp) {
            finish();
        }
        var type = H264Format.type(payload[0]);
        switch (type) {
            case H264Format.STAP_A, H264Format.STAP_B -> {
                var start = type == H264Format.STAP_A ? 1 : 3;
                checkAggregate(payload, start, 0);
                begin(timestamp);
                for (var position = start; position < payload.length; ) {
                    var size = ((payload[position] & 0xFF) << 8) | (payload[position + 1] & 0xFF);
                    addNal(payload, position + 2, size);
                    position += 2 + size;
                }
            }
            case H264Format.MTAP16, H264Format.MTAP24 -> {
                var offsetSize = type == H264Format.MTAP16 ? 2 : 3;
                checkAggregate(payload, 3, 1 + offsetSize);
                begin(timestamp);
                for (var position = 3; position < payload.length; ) {
                    var size = ((payload[position] & 0xFF) << 8) | (payload[position + 1] & 0xFF);
                    addNal(payload, position + 3 + offsetSize, size);
                    position += 3 + offsetSize + size;
                }
            }
            case H264Format.FU_A, H264Format.FU_B -> acceptFragment(payload, timestamp, type == H264Format.FU_B);
            case 0, 30, 31 -> throw new MediaException.Malformed("reserved H.264 NAL unit type " + type);
            default -> {
                begin(timestamp);
                addNal(payload, 0, payload.length);
            }
        }
        if (packet.marker() && fragmentStart < 0) {
            finish();
        }
    }

    /**
     * Checks that the aggregation units from {@code start} cover the payload exactly; each unit has a 2-byte size,
     * {@code extra} more bytes, then a NAL unit of that size.
     */
    private static void checkAggregate(byte[] payload, int start, int extra) {
        if (payload.length <= start) {
            throw new MediaException.Malformed("H.264 aggregation packet holds no NAL unit");
        }
        var position = start;
        while (position < payload.length) {
            if (position + 2 + extra > payload.length) {
                throw new MediaException.Malformed("H.264 aggregation unit header is truncated");
            }
            var size = ((payload[position] & 0xFF) << 8) | (payload[position + 1] & 0xFF);
            if (size == 0 || position + 2 + extra + size > payload.length) {
                throw new MediaException.Malformed("H.264 aggregation unit of " + size + " bytes does not fit");
            }
            position += 2 + extra + size;
        }
    }

    private void acceptFragment(byte[] payload, long timestamp, boolean withDon) {
        var headerSize = withDon ? 4 : 2;
        if (payload.length <= headerSize) {
            throw new MediaException.Malformed("H.264 fragmentation unit of " + payload.length + " bytes");
        }
        var header = payload[1] & 0xFF;
        var start = (header & 0x80) != 0;
        var end = (header & 0x40) != 0;
        if (start && end) {
            throw new MediaException.Malformed("H.264 fragmentation unit with both start and end bits");
        }
        if (start) {
            if (fragmentStart >= 0) {
                dropFragment();
                unitComplete = false;
            }
            begin(timestamp);
            fragmentStart = unit.size();
            unit.writeInt(0);
            unit.write((payload[0] & 0xE0) | (header & 0x1F));
        } else if (fragmentStart < 0) {
            if (open) {
                unitComplete = false;
            } else {
                suspect = true;
            }
            return;
        }
        unit.write(payload, headerSize, payload.length - headerSize);
        if (end) {
            var length = unit.size() - fragmentStart - 4;
            var array = unit.array();
            array[fragmentStart] = (byte) (length >>> 24);
            array[fragmentStart + 1] = (byte) (length >>> 16);
            array[fragmentStart + 2] = (byte) (length >>> 8);
            array[fragmentStart + 3] = (byte) length;
            noteNal(array, fragmentStart + 4, length);
            fragmentStart = -1;
        }
    }

    private void dropFragment() {
        if (fragmentStart >= 0) {
            unit.truncate(fragmentStart);
            fragmentStart = -1;
        }
    }

    private void begin(long timestamp) {
        if (!open) {
            open = true;
            unitTimestamp = timestamp;
            unitComplete = !suspect;
            unitKey = false;
            suspect = false;
            unit.clear();
        }
    }

    private void addNal(byte[] data, int offset, int length) {
        unit.writeInt(length);
        unit.write(data, offset, length);
        noteNal(data, offset, length);
    }

    private void noteNal(byte[] data, int offset, int length) {
        var type = H264Format.type(data[offset]);
        if (type == H264Format.IDR) {
            unitKey = true;
        } else if (type == H264Format.SPS || type == H264Format.PPS) {
            noteParameterSet(data, offset, length);
        }
    }

    private void noteParameterSet(byte[] data, int offset, int length) {
        var type = H264Format.type(data[offset]);
        var rbsp = NalUnits.unescape(data, offset + 1, length - 1);
        var reader = new MsbBitReader(rbsp);
        int id;
        try {
            if (type == H264Format.SPS) {
                if (rbsp.length < 4) {
                    return;
                }
                reader.skip(24);
            }
            id = reader.readUe();
        } catch (MediaException.Malformed exception) {
            return;
        }
        if (reader.overrun() || id < 0 || id > 255) {
            return;
        }
        var set = Arrays.copyOfRange(data, offset, offset + length);
        var map = type == H264Format.SPS ? sequenceSets : pictureSets;
        var previous = map.put(id, set);
        if (previous == null || !Arrays.equals(previous, set)) {
            var built = NalConfigs.avcConfig(new ArrayList<>(sequenceSets.values()),
                    new ArrayList<>(pictureSets.values()));
            if (built != null) {
                config = built;
            }
        }
    }

    private void finish() {
        if (!open) {
            return;
        }
        if (fragmentStart >= 0) {
            dropFragment();
            unitComplete = false;
        }
        open = false;
        if (unit.size() == 0) {
            return;
        }
        var side = PacketSideData.NONE;
        if (config != null && !Arrays.equals(config, reportedConfig)) {
            reportedConfig = config;
            side = PacketSideData.builder().newCodecConfig(config.clone()).build();
        }
        out.add(unit.toByteArray(), unitTimestamp, unitKey, unitComplete, -1, side);
    }

    private void acceptInterleaved(byte[] payload, long timestamp, boolean gap) {
        if (gap && fragmentOpen) {
            fragment.clear();
            fragmentOpen = false;
            suspect = true;
        }
        var type = H264Format.type(payload[0]);
        switch (type) {
            case H264Format.STAP_B -> {
                if (payload.length < 3) {
                    throw new MediaException.Malformed("STAP-B without decoding order number");
                }
                checkAggregate(payload, 3, 0);
                var don = extendDon(((payload[1] & 0xFF) << 8) | (payload[2] & 0xFF));
                for (var position = 3; position < payload.length; don++) {
                    var size = ((payload[position] & 0xFF) << 8) | (payload[position + 1] & 0xFF);
                    queue(don, Arrays.copyOfRange(payload, position + 2, position + 2 + size), timestamp);
                    position += 2 + size;
                }
            }
            case H264Format.MTAP16, H264Format.MTAP24 -> {
                if (payload.length < 3) {
                    throw new MediaException.Malformed("MTAP without decoding order number base");
                }
                var offsetSize = type == H264Format.MTAP16 ? 2 : 3;
                checkAggregate(payload, 3, 1 + offsetSize);
                var base = ((payload[1] & 0xFF) << 8) | (payload[2] & 0xFF);
                for (var position = 3; position < payload.length; ) {
                    var size = ((payload[position] & 0xFF) << 8) | (payload[position + 1] & 0xFF);
                    var don = extendDon((base + (payload[position + 2] & 0xFF)) & 0xFFFF);
                    long offset = 0;
                    for (var index = 0; index < offsetSize; index++) {
                        offset = (offset << 8) | (payload[position + 3 + index] & 0xFF);
                    }
                    var start = position + 3 + offsetSize;
                    queue(don, Arrays.copyOfRange(payload, start, start + size), timestamp + offset);
                    position = start + size;
                }
            }
            case H264Format.FU_A, H264Format.FU_B -> acceptInterleavedFragment(payload, timestamp,
                    type == H264Format.FU_B);
            case 0, 30, 31 -> throw new MediaException.Malformed("reserved H.264 NAL unit type " + type);
            default -> throw new MediaException.Malformed("NAL unit type " + type
                    + " is not allowed in the interleaved packetization mode");
        }
        releaseInterleaved(false);
    }

    private void acceptInterleavedFragment(byte[] payload, long timestamp, boolean withDon) {
        var headerSize = withDon ? 4 : 2;
        if (payload.length <= headerSize) {
            throw new MediaException.Malformed("H.264 fragmentation unit of " + payload.length + " bytes");
        }
        var header = payload[1] & 0xFF;
        var start = (header & 0x80) != 0;
        var end = (header & 0x40) != 0;
        if (start) {
            if (!withDon) {
                throw new MediaException.Malformed("interleaved mode fragment starts with FU-A instead of FU-B");
            }
            if (fragmentOpen) {
                suspect = true;
            }
            fragmentDon = extendDon(((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF));
            fragmentTimestamp = timestamp;
            fragment.clear();
            fragment.write((payload[0] & 0xE0) | (header & 0x1F));
            fragmentOpen = true;
        } else if (!fragmentOpen) {
            suspect = true;
            return;
        }
        fragment.write(payload, headerSize, payload.length - headerSize);
        if (end) {
            queue(fragmentDon, fragment.toByteArray(), fragmentTimestamp);
            fragmentOpen = false;
        }
    }

    private long extendDon(int don) {
        if (!donStarted) {
            donStarted = true;
            lastDon = don + 0x10000L;
            return lastDon;
        }
        var extended = lastDon + (short) (don - (int) (lastDon & 0xFFFF));
        lastDon = Math.max(lastDon, extended);
        return extended;
    }

    private void queue(long don, byte[] nal, long timestamp) {
        if (nal.length == 0 || don <= lastReleasedDon || interleaved.containsKey(don)) {
            return;
        }
        interleaved.put(don, new Nal(nal, timestamp));
        if (H264Format.isVcl(H264Format.type(nal[0]))) {
            vclCount++;
        }
    }

    private void releaseInterleaved(boolean all) {
        var depth = format.interleavingDepth + 1;
        while (!interleaved.isEmpty()) {
            var release = all || vclCount >= depth;
            if (!release && format.maxDonDiff >= 0) {
                release = interleaved.lastKey() - interleaved.firstKey() > format.maxDonDiff;
            }
            if (!release) {
                return;
            }
            var entry = interleaved.pollFirstEntry();
            var nal = entry.getValue();
            if (H264Format.isVcl(H264Format.type(nal.data()[0]))) {
                vclCount--;
            }
            if (open && nal.timestamp() != unitTimestamp) {
                finish();
            }
            if (lastReleasedDon != Long.MIN_VALUE && entry.getKey() != lastReleasedDon + 1) {
                if (open) {
                    unitComplete = false;
                } else {
                    suspect = true;
                }
            }
            lastReleasedDon = entry.getKey();
            begin(nal.timestamp());
            addNal(nal.data(), 0, nal.data().length);
            if (!all && vclCount < depth && (format.maxDonDiff < 0 || interleaved.isEmpty()
                    || interleaved.lastKey() - interleaved.firstKey() <= format.maxDonDiff)) {
                return;
            }
        }
    }

    @Override
    void flush() {
        if (format.mode == 2) {
            if (fragmentOpen) {
                fragment.clear();
                fragmentOpen = false;
                suspect = true;
            }
            releaseInterleaved(true);
            finish();
            donStarted = false;
            lastReleasedDon = Long.MIN_VALUE;
            vclCount = 0;
        } else {
            finish();
        }
        suspect = false;
    }
}
