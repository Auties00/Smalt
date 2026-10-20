package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Trims the packets a demuxer reads after jumping to a position before a seek target, so that
 * every track restarts at the last packet a decoder can start from at or before the target.
 *
 * <p>A demuxer that seeks by moving to a byte position (a Matroska cluster, an Ogg page) usually
 * lands before the start packet of each track, because one position serves every track. It feeds
 * every packet it reads from there to {@link #offer(Packet)}, and returns what {@link #poll()}
 * releases, in the order offered. For each track the filter keeps a candidate: the latest key frame
 * whose time (presentation time, or decoding time when the packet has none) is at or before the
 * target. Packets of the track before the candidate are dropped; packets after it are held until
 * the track is resolved, which happens when a packet of the track lies past the target, when any
 * packet lies more than {@value #MARGIN_MICROS} microseconds past it, when more than
 * {@value #MAX_HELD_PACKETS} packets are held, or at the end of the input. A track whose first
 * packet past the target arrives without a candidate restarts at its next key frame. A track
 * whose packets carry no time passes unfiltered. At the end of the input, a track whose last
 * packet ends at or before the target is dropped entirely, so a target past the end yields no
 * packet of it; a packet that declares no duration is taken to last as long as the interval
 * since the packet before it.
 *
 * <p>A dropped packet that carries a {@link PacketSideData#newCodecConfig() new codec
 * configuration} passes it on to the next packet of its track that is queued, unless that packet
 * carries one of its own, so that a decoder restarted at the start packet uses the configuration
 * in force there.
 *
 * <p>Instances are not thread-safe.
 */
public final class SeekFilter {
    /**
     * The distance past the target after which every track with a candidate is resolved.
     */
    private static final long MARGIN_MICROS = 2_000_000;

    /**
     * The number of held packets after which every track with a candidate is resolved.
     */
    private static final int MAX_HELD_PACKETS = 8192;

    /**
     * The state of each track, by track identifier.
     */
    private final Map<Integer, TrackState> states;

    /**
     * The target in microseconds.
     */
    private final long targetMicros;

    /**
     * The packets offered and not yet released or dropped, in the order offered.
     */
    private final ArrayDeque<Entry> queue;

    /**
     * The number of tracks not yet resolved.
     */
    private int unresolved;

    /**
     * Whether {@link #finish()} ran.
     */
    private boolean finished;

    /**
     * Constructs a filter for a seek target.
     *
     * @param tracks the tracks of the demuxer
     * @param targetMicros the seek target in microseconds
     */
    public SeekFilter(List<Track> tracks, long targetMicros) {
        this.states = new HashMap<>();
        this.targetMicros = targetMicros;
        this.queue = new ArrayDeque<>();
        for (var track : tracks) {
            var target = rescale(targetMicros, track.timeBase());
            states.put(track.id(), new TrackState(track.timeBase(), target));
        }
        this.unresolved = states.size();
    }

    /**
     * Converts the target to a track time base, rounding down and saturating instead of
     * overflowing.
     *
     * @param micros the target in microseconds
     * @param timeBase the time base
     * @return the target in units of {@code timeBase}
     */
    private static long rescale(long micros, Rational timeBase) {
        return SeekTimes.floor(micros, timeBase);
    }

    /**
     * Returns whether the filter no longer changes anything: every track is resolved and no packet
     * is held, so the demuxer can drop the filter and return packets directly.
     *
     * @return {@code true} when the filter passes everything through
     */
    public boolean isTransparent() {
        return unresolved == 0 && queue.isEmpty();
    }

    /**
     * Receives the next packet read after the jump.
     *
     * @param packet a packet read after the jump
     */
    public void offer(Packet packet) {
        var state = states.get(packet.trackId());
        if (state == null) {
            queue.add(new Entry(packet, null));
            return;
        }
        if (state.mode == Mode.RESOLVED) {
            queue.add(new Entry(carry(state, packet), state));
            return;
        }
        var time = packet.pts() != Packet.NO_TIMESTAMP ? packet.pts() : packet.dts();
        if (time == Packet.NO_TIMESTAMP) {
            if (state.candidate == null && state.mode == Mode.PENDING) {
                resolve(state);
                queue.add(new Entry(carry(state, packet), state));
            } else if (state.candidate != null) {
                hold(state, packet);
            } else {
                remember(state, packet);
            }
            return;
        }
        var interval = state.lastTime == Long.MIN_VALUE ? 1 : Math.max(1, time - state.lastTime);
        state.lastTime = Math.max(state.lastTime, time);
        state.lastEnd = Math.max(state.lastEnd, time + (packet.duration() > 0 ? packet.duration() : interval));
        if (state.mode == Mode.WAIT_KEY) {
            if (packet.keyframe()) {
                resolve(state);
                queue.add(new Entry(carry(state, packet), state));
            } else {
                remember(state, packet);
            }
            return;
        }
        if (packet.keyframe() && time <= state.target) {
            for (var entry : state.held) {
                entry.dropped = true;
                remember(state, entry.packet);
            }
            state.held.clear();
            var entry = new Entry(carry(state, packet), state);
            state.candidate = entry;
            state.held.add(entry);
            queue.add(entry);
        } else if (time > state.target) {
            if (state.candidate != null) {
                hold(state, packet);
                resolve(state);
            } else if (packet.keyframe()) {
                resolve(state);
                queue.add(new Entry(carry(state, packet), state));
            } else {
                state.mode = Mode.WAIT_KEY;
                remember(state, packet);
            }
        } else if (state.candidate != null) {
            hold(state, packet);
        } else {
            remember(state, packet);
        }
        if (overMargin(state, time) || queue.size() > MAX_HELD_PACKETS) {
            resolveAll();
        }
    }

    /**
     * Holds a packet that follows the candidate of a pending track.
     *
     * @param state the track state
     * @param packet a packet of the track, after its candidate
     */
    private void hold(TrackState state, Packet packet) {
        var entry = new Entry(carry(state, packet), state);
        state.held.add(entry);
        queue.add(entry);
    }

    /**
     * Notes the codec configuration change of a dropped packet.
     *
     * @param state the state of the packet's track
     * @param packet the dropped packet
     */
    private static void remember(TrackState state, Packet packet) {
        var config = packet.sideData().newCodecConfig();
        if (config.isPresent()) {
            state.droppedConfig = config.get();
        }
    }

    /**
     * Gives a packet about to be queued the codec configuration change of the dropped packets of
     * its track before it, unless it carries one of its own.
     *
     * @param state the state of the packet's track
     * @param packet the packet about to be queued
     * @return the packet to queue
     */
    private static Packet carry(TrackState state, Packet packet) {
        var config = state.droppedConfig;
        if (config == null) {
            return packet;
        }
        state.droppedConfig = null;
        if (packet.sideData().newCodecConfig().isPresent()) {
            return packet;
        }
        return packet.withSideData(packet.sideData().toBuilder().newCodecConfig(config).build());
    }

    /**
     * Returns whether a time of a track lies more than the margin past the target.
     *
     * @param state the track state
     * @param time the time in the track's time base
     * @return {@code true} past the margin
     */
    private boolean overMargin(TrackState state, long time) {
        try {
            return state.timeBase.toMicros(time) - targetMicros > MARGIN_MICROS;
        } catch (ArithmeticException exception) {
            return true;
        }
    }

    /**
     * Resolves every pending track: those with a candidate restart at it, the others at their next
     * key frame.
     */
    private void resolveAll() {
        for (var state : states.values()) {
            if (state.mode == Mode.PENDING) {
                if (state.candidate != null) {
                    resolve(state);
                } else {
                    state.mode = Mode.WAIT_KEY;
                }
            }
        }
    }

    /**
     * Marks a track resolved.
     *
     * @param state the track state
     */
    private void resolve(TrackState state) {
        if (state.mode != Mode.RESOLVED) {
            state.mode = Mode.RESOLVED;
            state.held.clear();
            unresolved--;
        }
    }

    /**
     * Signals the end of the input, resolving every pending track.
     */
    public void finish() {
        if (finished) {
            return;
        }
        finished = true;
        for (var state : states.values()) {
            if (state.mode != Mode.PENDING) {
                continue;
            }
            if (state.candidate != null && state.lastEnd <= state.target) {
                for (var entry : state.held) {
                    entry.dropped = true;
                }
            }
            resolve(state);
        }
        for (var state : states.values()) {
            if (state.mode == Mode.WAIT_KEY) {
                state.mode = Mode.RESOLVED;
                unresolved--;
            }
        }
    }

    /**
     * Returns the next packet to release.
     *
     * @return the packet, or {@code null} when the next packet depends on packets not offered yet, or when nothing is
     * held
     */
    public Packet poll() {
        while (!queue.isEmpty()) {
            var head = queue.peek();
            if (head.dropped) {
                queue.poll();
                continue;
            }
            if (head.state != null && head.state.mode != Mode.RESOLVED) {
                return null;
            }
            queue.poll();
            return head.packet;
        }
        return null;
    }

    /**
     * The filtering stage of one track.
     */
    private enum Mode {
        /**
         * The start packet is not known yet.
         */
        PENDING,

        /**
         * The track restarts at its next key frame.
         */
        WAIT_KEY,

        /**
         * The start packet is known; every later packet passes.
         */
        RESOLVED
    }

    /**
     * The filtering state of one track.
     */
    private static final class TrackState {
        /**
         * The time base of the track.
         */
        private final Rational timeBase;

        /**
         * The target in the track's time base.
         */
        private final long target;

        /**
         * The held packets of the track from its candidate on.
         */
        private final List<Entry> held;

        /**
         * The candidate start packet, or {@code null}.
         */
        private Entry candidate;

        /**
         * The stage of the track.
         */
        private Mode mode;

        /**
         * The largest packet end seen, in the track's time base.
         */
        private long lastEnd;

        /**
         * The largest packet time seen, in the track's time base, or {@link Long#MIN_VALUE}.
         */
        private long lastTime = Long.MIN_VALUE;

        /**
         * The codec configuration the latest dropped packet that changed it established, not yet
         * passed on to a queued packet, or {@code null}.
         */
        private byte[] droppedConfig;

        /**
         * Constructs the state of a pending track.
         *
         * @param timeBase the time base
         * @param target the target in that time base
         */
        private TrackState(Rational timeBase, long target) {
            this.timeBase = timeBase;
            this.target = target;
            this.held = new ArrayList<>();
            this.mode = Mode.PENDING;
            this.lastEnd = Long.MIN_VALUE;
        }
    }

    /**
     * One packet in the queue.
     */
    private static final class Entry {
        /**
         * The packet.
         */
        private final Packet packet;

        /**
         * The state of the packet's track, or {@code null} for a track the filter does not know.
         */
        private final TrackState state;

        /**
         * Whether the packet was dropped after being queued.
         */
        private boolean dropped;

        /**
         * Constructs an entry.
         *
         * @param packet the packet held
         * @param state the track state, or {@code null}
         */
        private Entry(Packet packet, TrackState state) {
            this.packet = packet;
            this.state = state;
        }
    }
}
