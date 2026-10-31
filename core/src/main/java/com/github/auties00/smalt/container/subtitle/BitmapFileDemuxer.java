package com.github.auties00.smalt.container.subtitle;

import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;

import java.util.List;
import java.util.Map;

/**
 * Demuxes a bitmap subtitle file that is read whole: its tracks and every packet in file order, served from memory.
 *
 * <p>Seeking is exact: for each track the next packet is the last key packet (one from which decoding can start)
 * that starts at or before the target, or the track's first packet when none does.
 */
abstract sealed class BitmapFileDemuxer implements Demuxer permits SupDemuxer, VobSubDemuxer {
    private final List<Track> tracks;
    private final Packet[] packets;
    private final long[] starts;
    private final Map<String, String> metadata;
    private final long duration;

    /**
     * The start of the last packet in microseconds.
     */
    private final long lastStart;

    /**
     * The index of the next packet.
     */
    private int next;

    /**
     * The index of the first packet returned of each track, by position in {@link #tracks}.
     */
    private int[] firsts;

    /**
     * Constructs a demuxer.
     *
     * @param tracks the tracks of the file, in identifier order
     * @param packets the packets in file order, every one timed
     * @param metadata the container-level tags
     */
    BitmapFileDemuxer(List<Track> tracks, List<Packet> packets, Map<String, String> metadata) {
        this.tracks = List.copyOf(tracks);
        this.packets = packets.toArray(new Packet[0]);
        this.metadata = Map.copyOf(metadata);
        this.starts = new long[this.packets.length];
        var end = 0L;
        var last = 0L;
        for (var index = 0; index < this.packets.length; index++) {
            var packet = this.packets[index];
            var timeBase = track(packet.trackId()).timeBase();
            starts[index] = timeBase.toMicros(packet.pts());
            var length = packet.duration() > 0 ? timeBase.toMicros(packet.duration()) : 0;
            end = Math.max(end, starts[index] + length);
            last = Math.max(last, starts[index]);
        }
        this.duration = end;
        this.lastStart = last;
        this.firsts = new int[this.tracks.size()];
    }

    private Track track(int id) {
        for (var track : tracks) {
            if (track.id() == id) {
                return track;
            }
        }
        throw new IllegalStateException("packet of undeclared track " + id);
    }

    @Override
    public List<Track> tracks() {
        return tracks;
    }

    @Override
    public Map<String, String> metadata() {
        return metadata;
    }

    @Override
    public long durationMicros() {
        return duration;
    }

    @Override
    public Packet read() {
        while (next < packets.length) {
            var index = next++;
            var packet = packets[index];
            if (index >= firsts[position(packet.trackId())]) {
                return packet;
            }
        }
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}
     */
    @Override
    public boolean isSeekable() {
        return true;
    }

    @Override
    public void seek(long micros) {
        var chosen = new int[tracks.size()];
        var earliest = packets.length;
        for (var trackIndex = 0; trackIndex < tracks.size(); trackIndex++) {
            var id = tracks.get(trackIndex).id();
            var first = -1;
            var key = -1;
            var any = false;
            for (var index = 0; index < packets.length; index++) {
                if (packets[index].trackId() != id) {
                    continue;
                }
                if (first < 0) {
                    first = index;
                }
                if (starts[index] <= micros) {
                    any = true;
                    if (packets[index].keyframe()) {
                        key = index;
                    }
                }
            }
            var pastEnd = micros > duration || micros == duration && duration > lastStart;
            var start = pastEnd ? packets.length : !any ? Math.max(first, 0) : key >= 0 ? key : Math.max(first, 0);
            if (first < 0) {
                start = packets.length;
            }
            chosen[trackIndex] = start;
            earliest = Math.min(earliest, start);
        }
        firsts = chosen;
        next = earliest;
    }

    private int position(int trackId) {
        for (var index = 0; index < tracks.size(); index++) {
            if (tracks.get(index).id() == trackId) {
                return index;
            }
        }
        return 0;
    }
}
