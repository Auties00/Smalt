package com.github.auties00.smalt.container.hls;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackDisposition;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the sections of an HLS media playlist, the runs of segments between two discontinuities or two
 * initialization sections (RFC 8216 clauses 4.3.2.3 and 4.3.2.5), one after the other as one stream.
 *
 * <p>Every section has its own demuxer, all opened before the first is read, so {@link #tracks()} is complete from
 * the start. The tracks of the first section are the stream's; a track of a later section continues the first
 * track of an earlier section of the same kind and codec that no other track of its section continues, and any
 * other track is appended. A continued track keeps the description of the section that introduced it, but for its
 * duration, which is unknown: its packets are rescaled to its time base, and the first packet a section gives it
 * carries the section's codec configuration as {@link PacketSideData#newCodecConfig()} when it differs from the
 * configuration in force, which after a {@link #seek(long)} is the track's own.
 *
 * <p>The sections are placed on the stream's timeline by {@link #targets(long[])}: a section's timestamps are moved
 * so that its earliest presentation time falls on its target, and the sections of one discontinuity sequence number
 * share the move of the first of them, whose timestamps they continue. A section's earliest presentation time is
 * measured on its first packets, read ahead when the section is reached. The independent packets of an audio or
 * data track that start at or after the target of the next section, when that section continues the track, are left
 * out, so that the track's timestamps keep increasing; the frames of a video track are all kept.
 *
 * <p>Instances are not thread-safe.
 */
final class DiscontinuityDemuxer implements Demuxer {
    /**
     * The fewest packets read ahead of a section once every track has one: a video track's first packets in
     * decoding order hold its earliest presentation time among them when frames are reordered.
     */
    private static final int MIN_LOOKAHEAD = 16;

    /**
     * The most packets read ahead of a section.
     */
    private static final int MAX_LOOKAHEAD = 256;

    /**
     * The most bytes read ahead of a section.
     */
    private static final long MAX_LOOKAHEAD_BYTES = 8L << 20;

    /**
     * How far, in microseconds, the packets of a section may run past the start the playlist gives the next one: a
     * seek that lands this close to the start of a section starts in the section before, so that the frames of the
     * earlier section still shown at the target are read.
     */
    private static final long SEEK_OVERLAP_MICROS = 250_000;

    /**
     * A start not yet measured.
     */
    private static final long UNMEASURED = Long.MIN_VALUE;

    /**
     * One section of the playlist.
     *
     * @param demuxer the demuxer of its segments, closed with this demuxer
     * @param resources what is closed after the demuxer
     * @param discontinuity the discontinuity sequence number of its segments
     * @param positionMicros the time of its first segment on the playlist's timeline
     */
    record Section(Demuxer demuxer, List<AutoCloseable> resources, long discontinuity, long positionMicros) {
        /**
         * Copies the resources.
         */
        Section {
            resources = List.copyOf(resources);
        }
    }

    /**
     * The sections, in playlist order.
     */
    private final List<Section> sections;

    /**
     * The tracks, numbered from one.
     */
    private final List<Track> tracks = new ArrayList<>();

    /**
     * The section that introduced each track, by identifier less one.
     */
    private final List<Integer> origins = new ArrayList<>();

    /**
     * The inner identifier each track has in the section that introduced it, by identifier less one.
     */
    private final List<Integer> originIds = new ArrayList<>();

    /**
     * The outer identifier of each inner track, by section.
     */
    private final List<Map<Integer, Integer>> outerIds = new ArrayList<>();

    /**
     * The inner tracks, by section and inner identifier.
     */
    private final List<Map<Integer, Track>> innerTracks = new ArrayList<>();

    /**
     * The codec configuration a reader of each track has last been given, by identifier less one; {@code null}
     * for none.
     */
    private final byte[][] current;

    /**
     * The configuration the next packet of each track carries, by identifier less one, or {@code null}.
     */
    private final byte[][] pending;

    /**
     * Whether the packets of each track in the section being read are in the track's time base already, by
     * identifier less one.
     */
    private final boolean[] sameTimeBase;

    /**
     * The first section of each section's discontinuity sequence number, by section.
     */
    private final int[] leaders;

    /**
     * The earliest presentation time of each section's inner timeline, in microseconds, or {@link #UNMEASURED}.
     */
    private final long[] starts;

    /**
     * The target of each section, in microseconds; zero for the sections of the first section's number.
     */
    private final long[] targets;

    /**
     * The packets read ahead of each section.
     */
    private final List<ArrayDeque<Packet>> lookahead = new ArrayList<>();

    /**
     * Whether each section is unread: its demuxer is where it was opened but for the packets read ahead, and none of
     * those has been returned.
     */
    private final boolean[] pristine;

    /**
     * Whether each section's demuxer must be moved back to its start when the section is reached.
     */
    private final boolean[] rewind;

    /**
     * The section being read.
     */
    private int index;

    /**
     * Whether the section being read has been prepared.
     */
    private boolean entered;

    /**
     * Joins the sections of a playlist and matches their tracks.
     *
     * @param sections the sections, in playlist order, at least one
     * @throws IllegalArgumentException if there is no section
     */
    DiscontinuityDemuxer(List<Section> sections) {
        if (sections.isEmpty()) {
            throw new IllegalArgumentException("no section");
        }
        this.sections = List.copyOf(sections);
        var count = this.sections.size();
        this.leaders = new int[count];
        this.starts = new long[count];
        this.targets = new long[count];
        this.pristine = new boolean[count];
        this.rewind = new boolean[count];
        Arrays.fill(starts, UNMEASURED);
        Arrays.fill(pristine, true);
        var counts = new ArrayList<Integer>();
        for (var position = 0; position < count; position++) {
            var section = this.sections.get(position);
            leaders[position] = position;
            for (var earlier = 0; earlier < position; earlier++) {
                if (this.sections.get(earlier).discontinuity() == section.discontinuity()) {
                    leaders[position] = earlier;
                    break;
                }
            }
            lookahead.add(new ArrayDeque<>());
            var mapping = new HashMap<Integer, Integer>();
            var inner = new HashMap<Integer, Track>();
            var taken = new HashSet<Integer>();
            for (var track : section.demuxer().tracks()) {
                inner.put(track.id(), track);
                var outer = -1;
                for (var candidate = 0; candidate < tracks.size() && position > 0; candidate++) {
                    var known = tracks.get(candidate);
                    if (!taken.contains(candidate) && known.kind() == track.kind() && known.codec() == track.codec()) {
                        outer = candidate;
                        break;
                    }
                }
                if (outer < 0) {
                    outer = tracks.size();
                    tracks.add(renumber(track, outer + 1));
                    origins.add(position);
                    originIds.add(track.id());
                    counts.add(0);
                }
                taken.add(outer);
                mapping.put(track.id(), outer + 1);
                counts.set(outer, counts.get(outer) + 1);
            }
            outerIds.add(mapping);
            innerTracks.add(inner);
        }
        this.current = new byte[tracks.size()][];
        this.pending = new byte[tracks.size()][];
        this.sameTimeBase = new boolean[tracks.size()];
        for (var track = 0; track < tracks.size(); track++) {
            if (counts.get(track) > 1) {
                // the duration the introducing section declares ends where the next section continues the track
                tracks.set(track, withoutDuration(tracks.get(track)));
            }
            current[track] = tracks.get(track).codecConfig().orElse(null);
        }
    }

    /**
     * Places the sections on the stream's timeline. Called once, before the first packet is read.
     *
     * @param sectionTargets the time on the stream's timeline of the earliest presentation time of each section
     * that is the first of its discontinuity sequence number, in microseconds; the values of the other sections,
     * and of the sections of the first section's number, are ignored
     * @throws IllegalArgumentException if there is not one target per section
     */
    void targets(long[] sectionTargets) {
        if (sectionTargets.length != sections.size()) {
            throw new IllegalArgumentException("one target per section");
        }
        for (var position = 0; position < sections.size(); position++) {
            targets[position] = leaders[position] == leaders[0] ? 0 : sectionTargets[leaders[position]];
        }
    }

    /**
     * Returns the earliest presentation time of a section on its own timeline, measured on its first packets.
     *
     * @param section the section index
     * @return the time in microseconds, zero for a section whose first packets carry no timestamp
     * @throws MediaException.Malformed if the section is corrupt
     */
    long startMicros(int section) {
        if (starts[section] != UNMEASURED) {
            return starts[section];
        }
        var demuxer = sections.get(section).demuxer();
        var queue = lookahead.get(section);
        var inner = innerTracks.get(section);
        var seen = new HashSet<Integer>();
        var earliest = Long.MAX_VALUE;
        var bytes = 0L;
        while (queue.size() < MAX_LOOKAHEAD && bytes < MAX_LOOKAHEAD_BYTES
                && (seen.size() < inner.size() || queue.size() < MIN_LOOKAHEAD)) {
            var packet = demuxer.read();
            if (packet == null) {
                break;
            }
            queue.add(packet);
            bytes += packet.data().length;
            var track = inner.get(packet.trackId());
            if (track == null) {
                continue;
            }
            seen.add(packet.trackId());
            var time = packet.pts() != Packet.NO_TIMESTAMP ? packet.pts() : packet.dts();
            if (time != Packet.NO_TIMESTAMP) {
                earliest = Math.min(earliest, micros(track.timeBase(), time));
            }
        }
        starts[section] = earliest == Long.MAX_VALUE ? 0 : earliest;
        return starts[section];
    }

    /**
     * Returns the time added to a section's timestamps.
     *
     * @param section the section index
     * @return the offset in microseconds
     */
    long offsetMicros(int section) {
        var leader = leaders[section];
        if (leader == leaders[0]) {
            return 0;
        }
        return targets[leader] - startMicros(leader);
    }

    /**
     * Converts a time to microseconds.
     *
     * @param timeBase the time base of the value
     * @param value the time, in units of {@code timeBase}
     * @return the microseconds
     * @throws MediaException.Malformed if the time does not fit in a {@code long}
     */
    private static long micros(Rational timeBase, long value) {
        try {
            return timeBase.toMicros(value);
        } catch (ArithmeticException exception) {
            throw new MediaException.Malformed("an HLS segment timestamp beyond 63 bits of microseconds");
        }
    }

    /**
     * Returns a track under another identifier.
     *
     * @param track the track to number
     * @param id the identifier
     * @return the track
     */
    private static Track renumber(Track track, int id) {
        return switch (track) {
            case VideoTrack video -> video.toBuilder().id(id).build();
            case AudioTrack audio -> audio.toBuilder().id(id).build();
            case DataTrack data -> data.toBuilder().id(id).build();
        };
    }

    /**
     * Returns a track of unknown duration.
     *
     * @param track the track whose duration is dropped
     * @return the track
     */
    private static Track withoutDuration(Track track) {
        return switch (track) {
            case VideoTrack video -> video.toBuilder().duration(Track.UNKNOWN_DURATION).build();
            case AudioTrack audio -> audio.toBuilder().duration(Track.UNKNOWN_DURATION).build();
            case DataTrack data -> data.toBuilder().duration(Track.UNKNOWN_DURATION).build();
        };
    }

    /**
     * Prepares the section being read: moves its demuxer back to its start when it was read before, measures its
     * start, and queues the codec configuration its first packets carry.
     */
    private void enter() {
        entered = true;
        var section = sections.get(index);
        if (rewind[index]) {
            rewind[index] = false;
            lookahead.get(index).clear();
            section.demuxer().seek(startMicros(index));
        } else {
            startMicros(index);
        }
        offsetMicros(index);
        queueConfigs(index);
    }

    /**
     * Queues the codec configuration of a section's tracks for their first packets, where it differs from the one
     * a reader holds, and notes which of them keep their track's time base.
     *
     * @param section the section index
     */
    private void queueConfigs(int section) {
        var inner = innerTracks.get(section);
        for (var entry : outerIds.get(section).entrySet()) {
            var outer = entry.getValue() - 1;
            var track = inner.get(entry.getKey());
            sameTimeBase[outer] = track.timeBase().reduce().equals(tracks.get(outer).timeBase().reduce());
            var config = track.codecConfig().orElse(null);
            pending[outer] = null;
            if (config != null && !Arrays.equals(config, current[outer])) {
                pending[outer] = config;
            }
        }
    }

    @Override
    public ContainerFormat format() {
        return sections.getFirst().demuxer().format();
    }

    @Override
    public String formatName() {
        return sections.getFirst().demuxer().formatName();
    }

    @Override
    public List<Track> tracks() {
        return List.copyOf(tracks);
    }

    @Override
    public Map<String, String> metadata() {
        return sections.getFirst().demuxer().metadata();
    }

    @Override
    public Map<String, String> trackMetadata(int trackId) {
        if (trackId < 1 || trackId > tracks.size()) {
            return Map.of();
        }
        return sections.get(origins.get(trackId - 1)).demuxer().trackMetadata(originIds.get(trackId - 1));
    }

    @Override
    public Set<TrackDisposition> trackDisposition(int trackId) {
        if (trackId < 1 || trackId > tracks.size()) {
            return Set.of();
        }
        return sections.get(origins.get(trackId - 1)).demuxer().trackDisposition(originIds.get(trackId - 1));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The duration is the end of the last section on the stream's timeline, or -1 when its demuxer reports none.
     */
    @Override
    public long durationMicros() {
        var last = sections.size() - 1;
        var inner = sections.get(last).demuxer().durationMicros();
        return inner < 0 ? -1 : inner + offsetMicros(last);
    }

    @Override
    public Packet read() {
        while (index < sections.size()) {
            if (!entered) {
                enter();
            }
            pristine[index] = false;
            var packet = lookahead.get(index).poll();
            if (packet == null) {
                packet = sections.get(index).demuxer().read();
            }
            if (packet == null) {
                index++;
                entered = false;
                continue;
            }
            var outer = outerIds.get(index).get(packet.trackId());
            if (outer == null || overruns(packet, outer)) {
                continue;
            }
            return move(packet, outer);
        }
        return null;
    }

    /**
     * Returns whether a packet of the section being read is an independent audio or data packet that starts at or
     * after the start of the next discontinuity, which continues its track: the encoder padding that runs past the
     * duration the playlist gives a section, which would otherwise overlap the next section's first packets.
     *
     * @param packet a packet of the section being read
     * @param outer the identifier of its track
     * @return {@code true} to leave the packet out
     */
    private boolean overruns(Packet packet, int outer) {
        var next = index + 1;
        if (next >= sections.size() || leaders[next] != next || !outerIds.get(next).containsValue(outer)
                || !packet.keyframe() || packet.pts() == Packet.NO_TIMESTAMP
                || tracks.get(outer - 1).kind() == TrackKind.VIDEO) {
            return false;
        }
        var time = micros(innerTracks.get(index).get(packet.trackId()).timeBase(), packet.pts());
        return time + offsetMicros(index) >= targets[next];
    }

    /**
     * Moves a packet of the section being read onto the stream's timeline and its track.
     *
     * @param packet a packet of the section being read
     * @param outer the identifier of its track
     * @return the moved packet
     * @throws MediaException.Malformed if a timestamp does not fit in a {@code long}
     */
    private Packet move(Packet packet, int outer) {
        var from = innerTracks.get(index).get(packet.trackId()).timeBase();
        var to = tracks.get(outer - 1).timeBase();
        var same = sameTimeBase[outer - 1];
        var sideData = packet.sideData();
        try {
            var offset = Rational.MICROSECONDS.rescale(offsetMicros(index), to);
            var pts = packet.pts() == Packet.NO_TIMESTAMP ? Packet.NO_TIMESTAMP
                    : Math.addExact(same ? packet.pts() : from.rescale(packet.pts(), to), offset);
            var dts = packet.dts() == Packet.NO_TIMESTAMP ? Packet.NO_TIMESTAMP
                    : Math.addExact(same ? packet.dts() : from.rescale(packet.dts(), to), offset);
            var duration = same || packet.duration() <= 0 ? packet.duration() : from.rescale(packet.duration(), to);
            if (!same && (sideData.discardStart() > 0 || sideData.discardEnd() > 0)) {
                sideData = sideData.toBuilder().discard(from.rescale(sideData.discardStart(), to),
                        from.rescale(sideData.discardEnd(), to)).build();
            }
            var own = sideData.newCodecConfig().orElse(null);
            if (own != null) {
                current[outer - 1] = own;
                pending[outer - 1] = null;
            } else if (pending[outer - 1] != null) {
                sideData = sideData.toBuilder().newCodecConfig(pending[outer - 1]).build();
                current[outer - 1] = pending[outer - 1];
                pending[outer - 1] = null;
            }
            return new Packet(outer, packet.data(), pts, dts, duration, packet.flags(), sideData);
        } catch (ArithmeticException exception) {
            throw new MediaException.Malformed("an HLS segment timestamp beyond 63 bits");
        }
    }

    @Override
    public boolean isSeekable() {
        for (var section : sections) {
            if (!section.demuxer().isSeekable()) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The section seeked into is the last one whose first segment starts, on the playlist's timeline placed by
     * the targets, at least {@value #SEEK_OVERLAP_MICROS} microseconds before the requested time; the sections after
     * it are read from their start.
     */
    @Override
    public void seek(long micros) {
        var target = 0;
        for (var position = 1; position < sections.size(); position++) {
            if (approximateStart(position) <= micros - SEEK_OVERLAP_MICROS) {
                target = position;
            }
        }
        var offset = offsetMicros(target);
        for (var position = 0; position < sections.size(); position++) {
            if (position > target && !pristine[position]) {
                rewind[position] = true;
            }
        }
        lookahead.get(target).clear();
        pristine[target] = false;
        rewind[target] = false;
        sections.get(target).demuxer().seek(micros - offset);
        for (var track = 0; track < tracks.size(); track++) {
            current[track] = tracks.get(track).codecConfig().orElse(null);
            pending[track] = null;
        }
        index = target;
        entered = true;
        queueConfigs(target);
    }

    /**
     * Returns where a section starts on the stream's timeline: its target, or the target of the first section of
     * its discontinuity sequence number moved by the playlist time between them.
     *
     * @param section the section index
     * @return the time in microseconds
     */
    private long approximateStart(int section) {
        var leader = leaders[section];
        var base = leader == leaders[0] ? startMicros(leaders[0]) : targets[leader];
        return base + sections.get(section).positionMicros() - sections.get(leader).positionMicros();
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (var section : sections) {
            try {
                section.demuxer().close();
            } catch (RuntimeException exception) {
                failure = failure == null ? exception : failure;
            }
            for (var resource : section.resources()) {
                try {
                    resource.close();
                } catch (Exception exception) {
                    if (failure == null) {
                        failure = exception instanceof RuntimeException runtime ? runtime
                                : new IllegalStateException(exception);
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
