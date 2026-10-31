package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Attachment;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.Timecode;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackDisposition;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Presents several demuxers, one per file, as one whose files play one after the other.
 *
 * <p>The tracks are the first file's, matched by position in the later files, which must have as many tracks of the
 * same kinds. Each file's timeline is moved so that its earliest timestamp falls where the previous file ends, the
 * end of a file being the end of its video when its other tracks end less than a frame later, and otherwise the
 * latest end of its packets in any track; a packet keeps its track's time base, the first file's. The earliest
 * timestamp of a file is the smallest one among its first packets, read ahead of the others. The tracks declare
 * the sum of the files' durations when every file declares one. Before the first packet of a track in a later file,
 * the {@link SegmentedDemuxer.Listener} learns the file's track and start. The demuxer cannot seek; the files'
 * demuxers are closed with it.
 *
 * <p>An instance is not thread-safe.
 */
final class ConcatDemuxer implements SegmentedDemuxer {
    /**
     * The most packets a file's start is read ahead to find its earliest timestamp.
     */
    private static final int ORIGIN_PACKETS = 32;

    /**
     * How far past a file's first packet, in microseconds, the read-ahead for its earliest timestamp goes.
     */
    private static final long ORIGIN_SPAN_MICROS = 1_000_000;

    private final List<Demuxer> files;
    private final List<Track> tracks;

    /**
     * The tracks of every file, by file then position, with the first file's identifiers and time bases.
     */
    private final List<List<Track>> fileTracks;

    /**
     * The position of each track of each file, by file then track identifier.
     */
    private final List<Map<Integer, Integer>> positions;

    private SegmentedDemuxer.Listener listener;

    /**
     * The index of the file being read.
     */
    private int file;

    /**
     * The packets of the current file read ahead.
     */
    private final ArrayDeque<Packet> ahead = new ArrayDeque<>();

    /**
     * The start of the current file on the concatenated timeline, in microseconds.
     */
    private long offsetMicros;

    /**
     * The earliest timestamp of the current file, in microseconds.
     */
    private long originMicros;

    /**
     * The latest end of a packet of the current file on the concatenated timeline, in microseconds.
     */
    private long endMicros;

    /**
     * The latest end of a video packet of the current file on the concatenated timeline, in microseconds, or
     * {@link Long#MIN_VALUE} before the first.
     */
    private long videoEndMicros;

    /**
     * The duration of one frame of the current file's video, in microseconds.
     */
    private long frameMicros;

    /**
     * The positions whose first packet of the current file was read.
     */
    private final Set<Integer> started = new HashSet<>();

    /**
     * The last positive decoding-order step of each position, in microseconds, which stands in for a missing packet
     * duration.
     */
    private final long[] steps;

    /**
     * The last decoding timestamp of each position in microseconds, or {@link Long#MIN_VALUE}.
     */
    private final long[] lastDts;

    /**
     * Constructs a demuxer over the files' demuxers, which it owns.
     *
     * @param files the demuxers, in playing order, each at its start
     * @throws IllegalArgumentException if a file's tracks do not match the first file's
     */
    ConcatDemuxer(List<Demuxer> files) {
        this.files = List.copyOf(files);
        var first = this.files.getFirst().tracks();
        this.fileTracks = new ArrayList<>();
        this.positions = new ArrayList<>();
        var total = 0L;
        var known = true;
        for (var index = 0; index < this.files.size(); index++) {
            var demuxer = this.files.get(index);
            var own = demuxer.tracks();
            if (own.size() != first.size()) {
                throw new IllegalArgumentException("file " + (index + 1) + " of the concatenation has " + own.size()
                        + " tracks, the first has " + first.size());
            }
            var mapped = new ArrayList<Track>();
            var byId = new HashMap<Integer, Integer>();
            for (var position = 0; position < own.size(); position++) {
                var model = first.get(position);
                var track = own.get(position);
                if (track.kind() != model.kind()) {
                    throw new IllegalArgumentException("track " + (position + 1) + " of file " + (index + 1)
                            + " of the concatenation is " + track.kind() + ", the first file's is " + model.kind());
                }
                mapped.add(index == 0 ? model : retimed(track, model.id(), model.timeBase()));
                byId.put(track.id(), position);
            }
            fileTracks.add(List.copyOf(mapped));
            positions.add(byId);
            var duration = demuxer.durationMicros();
            known &= duration >= 0;
            total += Math.max(0, duration);
        }
        var concatenated = new ArrayList<Track>();
        for (var track : first) {
            concatenated.add(known && this.files.size() > 1 ? withDuration(track,
                    Rational.MICROSECONDS.rescale(total, track.timeBase())) : track);
        }
        this.tracks = List.copyOf(concatenated);
        this.steps = new long[first.size()];
        this.lastDts = new long[first.size()];
        startFile();
    }

    /**
     * {@return a later file's track with the first file's identifier and time base, its duration converted}
     *
     * @param track the file's track
     * @param id the first file's identifier
     * @param timeBase the first file's time base
     */
    private static Track retimed(Track track, int id, Rational timeBase) {
        var duration = track.duration() < 0 ? -1 : track.timeBase().rescale(track.duration(), timeBase);
        return switch (track) {
            case VideoTrack video -> video.toBuilder().id(id).timeBase(timeBase).duration(duration).build();
            case AudioTrack audio -> audio.toBuilder().id(id).timeBase(timeBase).duration(duration).build();
            case DataTrack data -> data.toBuilder().id(id).timeBase(timeBase).duration(duration).build();
        };
    }

    /**
     * {@return a track with another duration}
     *
     * @param track the track to change
     * @param duration the duration in the track's time base
     */
    private static Track withDuration(Track track, long duration) {
        return switch (track) {
            case VideoTrack video -> video.toBuilder().duration(duration).build();
            case AudioTrack audio -> audio.toBuilder().duration(duration).build();
            case DataTrack data -> data.toBuilder().duration(duration).build();
        };
    }

    /**
     * Returns the tracks every file has at a track's position.
     *
     * @param trackId the identifier of a track of {@link #tracks()}
     * @return the tracks, one per file in playing order, with the first file's identifier and time base
     * @throws IllegalArgumentException if no track has the identifier
     */
    List<Track> fileTracks(int trackId) {
        var position = positions.getFirst().get(trackId);
        if (position == null) {
            throw new IllegalArgumentException("no track " + trackId);
        }
        var result = new ArrayList<Track>();
        for (var own : fileTracks) {
            result.add(own.get(position));
        }
        return result;
    }

    /**
     * Reads the start of the current file ahead to find its earliest timestamp.
     */
    private void startFile() {
        var demuxer = files.get(file);
        var own = demuxer.tracks();
        var origin = Long.MAX_VALUE;
        var firstMicros = Long.MIN_VALUE;
        while (ahead.size() < ORIGIN_PACKETS) {
            var packet = demuxer.read();
            if (packet == null) {
                break;
            }
            ahead.add(packet);
            var position = positions.get(file).get(packet.trackId());
            var time = packet.pts() != Packet.NO_TIMESTAMP ? packet.pts() : packet.dts();
            if (position == null || time == Packet.NO_TIMESTAMP) {
                continue;
            }
            var micros = own.get(position).timeBase().toMicros(time);
            origin = Math.min(origin, micros);
            if (firstMicros == Long.MIN_VALUE) {
                firstMicros = micros;
            } else if (micros - firstMicros >= ORIGIN_SPAN_MICROS) {
                break;
            }
        }
        originMicros = origin == Long.MAX_VALUE ? 0 : origin;
        endMicros = offsetMicros;
        videoEndMicros = Long.MIN_VALUE;
        frameMicros = 0;
        started.clear();
        Arrays.fill(steps, 0);
        Arrays.fill(lastDts, Long.MIN_VALUE);
    }

    @Override
    public void segmentListener(SegmentedDemuxer.Listener listener) {
        this.listener = listener;
    }

    @Override
    public ContainerFormat format() {
        return files.getFirst().format();
    }

    @Override
    public String formatName() {
        return files.getFirst().formatName();
    }

    @Override
    public List<Track> tracks() {
        return tracks;
    }

    @Override
    public Map<String, String> metadata() {
        return files.getFirst().metadata();
    }

    @Override
    public Map<String, String> trackMetadata(int trackId) {
        return files.getFirst().trackMetadata(trackId);
    }

    @Override
    public Set<TrackDisposition> trackDisposition(int trackId) {
        return files.getFirst().trackDisposition(trackId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The chapters of every file, each file's moved by the durations the earlier files declare; the chapters of
     * the files after one without a declared duration are left out.
     */
    @Override
    public List<Chapter> chapters() {
        var result = new ArrayList<Chapter>();
        var offset = 0L;
        for (var demuxer : files) {
            for (var chapter : demuxer.chapters()) {
                result.add(shifted(chapter, offset));
            }
            var duration = demuxer.durationMicros();
            if (duration < 0) {
                break;
            }
            offset += duration;
        }
        return result;
    }

    /**
     * {@return a chapter and its nested chapters moved later}
     *
     * @param chapter the chapter to move
     * @param offset the shift in microseconds
     */
    private static Chapter shifted(Chapter chapter, long offset) {
        var children = new ArrayList<Chapter>();
        for (var child : chapter.children()) {
            children.add(shifted(child, offset));
        }
        return new Chapter(chapter.startMicros() + offset, chapter.endMicros() + offset, chapter.title(),
                chapter.titles(), children);
    }

    @Override
    public List<Attachment> attachments() {
        return files.getFirst().attachments();
    }

    @Override
    public Optional<Timecode> timecode() {
        return files.getFirst().timecode();
    }

    @Override
    public List<AttachedPicture> attachedPictures() {
        return files.getFirst().attachedPictures();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The sum of the files' durations, or {@code -1} when a file declares none.
     */
    @Override
    public long durationMicros() {
        var total = 0L;
        for (var demuxer : files) {
            var duration = demuxer.durationMicros();
            if (duration < 0) {
                return -1;
            }
            total += duration;
        }
        return total;
    }

    @Override
    public Packet read() {
        while (true) {
            var packet = ahead.isEmpty() ? files.get(file).read() : ahead.poll();
            if (packet != null) {
                var mapped = map(packet);
                if (mapped != null) {
                    return mapped;
                }
                continue;
            }
            if (file + 1 >= files.size()) {
                return null;
            }
            file++;
            offsetMicros = fileEnd();
            startFile();
        }
    }

    /**
     * Moves a packet of the current file to the concatenated timeline and the first file's track.
     *
     * @param packet a packet of the current file
     * @return the moved packet, or {@code null} for a packet of a track the file does not match
     */
    private Packet map(Packet packet) {
        var position = positions.get(file).get(packet.trackId());
        if (position == null) {
            return null;
        }
        var own = files.get(file).tracks().get(position);
        var target = tracks.get(position);
        var timeBase = target.timeBase();
        var shift = Rational.MICROSECONDS.rescale(offsetMicros - originMicros, timeBase);
        var pts = packet.pts() == Packet.NO_TIMESTAMP ? Packet.NO_TIMESTAMP
                : own.timeBase().rescale(packet.pts(), timeBase) + shift;
        var dts = packet.dts() == Packet.NO_TIMESTAMP ? Packet.NO_TIMESTAMP
                : own.timeBase().rescale(packet.dts(), timeBase) + shift;
        var duration = packet.duration() <= 0 ? packet.duration() : own.timeBase().rescale(packet.duration(), timeBase);
        var time = pts != Packet.NO_TIMESTAMP ? pts : dts;
        if (time != Packet.NO_TIMESTAMP) {
            var micros = timeBase.toMicros(time);
            var decodeMicros = dts != Packet.NO_TIMESTAMP ? timeBase.toMicros(dts) : micros;
            if (lastDts[position] != Long.MIN_VALUE && decodeMicros > lastDts[position]) {
                steps[position] = decodeMicros - lastDts[position];
            }
            lastDts[position] = decodeMicros;
            var length = duration > 0 ? timeBase.toMicros(duration) : estimatedDuration(target, position);
            endMicros = Math.max(endMicros, micros + length);
            if (target instanceof VideoTrack) {
                videoEndMicros = Math.max(videoEndMicros, micros + length);
                frameMicros = Math.max(frameMicros, length);
            }
        }
        if (file > 0 && started.add(position) && listener != null) {
            listener.segmentStarted(target.id(), fileTracks.get(file).get(position), offsetMicros);
        }
        return packet.withTrackId(target.id()).withTiming(pts, dts, duration);
    }

    /**
     * {@return the end of the current file on the concatenated timeline: the end of its video when the other tracks
     * end less than a frame later, as the rounding of a container's timestamps makes them, and otherwise the latest
     * end of a packet in any track}
     */
    private long fileEnd() {
        if (videoEndMicros != Long.MIN_VALUE && endMicros - videoEndMicros < frameMicros) {
            return videoEndMicros;
        }
        return endMicros;
    }

    /**
     * {@return the duration of a packet that declares none, in microseconds: one frame of a video track's rate, or
     * the track's last decoding-order step}
     *
     * @param track the track of the packet
     * @param position the track's position
     */
    private long estimatedDuration(Track track, int position) {
        if (track instanceof VideoTrack video && video.frameRate().isPresent()) {
            return video.frameRate().get().invert().toMicros(1);
        }
        return steps[position];
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (var demuxer : files) {
            try {
                demuxer.close();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
