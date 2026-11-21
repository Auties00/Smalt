package com.github.auties00.smalt.mpeg.behavior;

import com.github.auties00.smalt.container.avi.AviMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.mpeg4.Mpeg4ElementaryStream;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Packages a raw MPEG-4 Part 2 or H.263 elementary stream into an AVI file of one track, the packets of the elementary
 * stream reader as stored frames (AVI timestamps the stored frames in decoding order), so that the stream runs
 * through {@code VideoReader} under the I/O behavior harness.
 *
 * <p>Every picture takes one stored frame of 1/30 second. An H.263 PB or improved PB frame (Annexes G and M), which
 * holds two pictures, is followed by an empty stored frame, which AVI readers skip while keeping its time slot. An
 * H.263 picture that shares the temporal reference of the picture before it, an enhancement layer picture of the same
 * instant (Annex O) or a redundant picture (Annex N), joins its stored frame, since the decoder hands out one picture
 * per instant. The {@code N} sub-bitstreams of an H.263 stream in continuous presence multipoint mode (Annex C) share
 * the instants of their temporal references, so each instant takes {@code N} stored frames of {@code 1/(30 N)}
 * second, and decoding can only start where every sub-bitstream continues with an intra picture: only those packets
 * are key frames.
 */
final class ElementarySamples {
    private ElementarySamples() {
    }

    /**
     * A packet of the elementary stream and what its picture header says.
     *
     * @param packet the packet
     * @param pictures the number of pictures it holds
     * @param subBitstream the H.263 sub-bitstream it belongs to, 0 without continuous presence multipoint
     * @param intra whether it is an intra picture
     * @param temporalReference the H.263 temporal reference, or -1
     */
    private record Unit(Packet packet, int pictures, int subBitstream, boolean intra, int temporalReference) {
    }

    /**
     * Returns an AVI file holding an elementary stream.
     *
     * @param stream the elementary stream
     * @param width the display width the track declares
     * @param height the display height the track declares
     */
    static byte[] avi(byte[] stream, int width, int height) {
        var sink = MediaSink.memory();
        try (var source = MediaSource.of(stream); var elementary = Mpeg4ElementaryStream.open(source);
             var muxer = new AviMuxer(sink)) {
            var units = new ArrayList<Unit>();
            var subBitstreams = 1;
            for (var packet = elementary.read(); packet != null; packet = elementary.read()) {
                var unit = unit(packet);
                subBitstreams = Math.max(subBitstreams, unit.subBitstream() + 1);
                var previous = units.isEmpty() ? null : units.getLast();
                if (previous != null && unit.temporalReference() >= 0
                        && unit.temporalReference() == previous.temporalReference()
                        && unit.subBitstream() == previous.subBitstream()) {
                    var data = previous.packet().data();
                    var merged = Arrays.copyOf(data, data.length + unit.packet().data().length);
                    System.arraycopy(unit.packet().data(), 0, merged, data.length, unit.packet().data().length);
                    units.set(units.size() - 1, new Unit(new Packet(previous.packet().trackId(), merged,
                            previous.packet().pts(), previous.packet().dts(), previous.packet().duration(),
                            previous.packet().keyframe()), previous.pictures(), previous.subBitstream(),
                            previous.intra(), previous.temporalReference()));
                } else {
                    units.add(unit);
                }
            }
            var track = elementary.track().toBuilder().width(width).height(height)
                    .timeBase(new Rational(1, 30 * subBitstreams)).build();
            muxer.addTrack(track);
            var position = 0L;
            for (var i = 0; i < units.size(); i++) {
                var unit = units.get(i);
                var keyframe = subBitstreams == 1 ? unit.packet().keyframe() : restartsAll(units, i, subBitstreams);
                muxer.writePacket(new Packet(track.id(), unit.packet().data(), position, position, 1, keyframe));
                position++;
                for (var picture = 1; picture < unit.pictures(); picture++) {
                    muxer.writePacket(new Packet(track.id(), new byte[0], position, position, 1, false));
                    position++;
                }
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }

    /**
     * Returns whether every sub-bitstream continues with an intra picture from a packet on, the packet itself being
     * intra.
     */
    private static boolean restartsAll(ArrayList<Unit> units, int from, int subBitstreams) {
        if (!units.get(from).intra()) {
            return false;
        }
        var seen = new boolean[subBitstreams];
        var remaining = subBitstreams;
        for (var i = from; i < units.size() && remaining > 0; i++) {
            var unit = units.get(i);
            if (!seen[unit.subBitstream()]) {
                if (!unit.intra()) {
                    return false;
                }
                seen[unit.subBitstream()] = true;
                remaining--;
            }
        }
        return true;
    }

    /**
     * Reads the picture header of an H.263 packet (ITU-T H.263 5.1.1 to 5.1.5); an MPEG-4 packet is one picture of
     * sub-bitstream 0.
     */
    private static Unit unit(Packet packet) {
        var data = packet.data();
        if (data.length < 3 || data[0] != 0 || data[1] != 0 || (data[2] & 0xFC) != 0x80) {
            return new Unit(packet, 1, 0, packet.keyframe(), -1);
        }
        var bits = new Bits(data, 22);
        var temporalReference = bits.read(8);
        bits.read(5);
        var format = bits.read(3);
        int type;
        boolean pb;
        if (format != 7) {
            type = bits.read(1);
            bits.read(3);
            pb = bits.read(1) == 1;
            bits.read(5);
        } else {
            var ufep = bits.read(3);
            if (ufep == 1) {
                bits.read(3);
                if (bits.read(1) == 1) {
                    throw new IllegalArgumentException("custom picture clock frequencies are not packaged");
                }
                bits.read(14);
            }
            type = bits.read(3);
            bits.read(6);
            pb = type == 2;
        }
        var subBitstream = bits.read(1) == 1 ? bits.read(2) : 0;
        return new Unit(packet, pb ? 2 : 1, subBitstream, type == 0, temporalReference);
    }

    /**
     * A big-endian bit reader over a packet.
     */
    private static final class Bits {
        private final byte[] data;
        private int position;

        Bits(byte[] data, int position) {
            this.data = data;
            this.position = position;
        }

        int read(int count) {
            var value = 0;
            for (var i = 0; i < count; i++, position++) {
                var index = position >> 3;
                var bit = index < data.length ? (data[index] >> (7 - (position & 7))) & 1 : 0;
                value = value << 1 | bit;
            }
            return value;
        }
    }
}
