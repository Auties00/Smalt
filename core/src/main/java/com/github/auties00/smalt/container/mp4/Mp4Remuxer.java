package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.matroska.MatroskaDemuxer;
import com.github.auties00.smalt.internal.io.LocalSource;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Copies the audio and video of an ISO base media or Matroska file into a fast-start MP4 or M4A
 * without re-encoding.
 *
 * <p>{@link #remux} detects the input container by its magic, keeps one video track and one
 * audio track (the first of each kind, or the ones {@link RemuxOptions} names), and copies their
 * packets unchanged into an {@link Mp4Muxer} with fast start, so the output has its {@code moov}
 * before the media data. For an ISO base media input, whose sample index is known up front, the
 * output layout is planned from the index first, so the {@code moov} is written before the media
 * data and the media data is written once, straight to the output; other inputs go through the
 * muxer's spool, except a Matroska input larger than the spool's memory that is local (in memory
 * or in a file, see {@link LocalSource}), whose layout is planned by demuxing it once before it
 * is demuxed again to write the media data. An input that is not local, such as one fetched over
 * the network, is always read once. Presentation timing is preserved: edit lists, encoder priming
 * ({@link AudioTrack#skipSamples()}), composition offsets and the display orientation carry over.
 * Timing is repaired where the input is inconsistent: decoding timestamps that step backwards are
 * held, Matroska video (which stores presentation timestamps only) gets decoding timestamps
 * derived from them, and Matroska audio, whose stored timestamps are rounded to the segment time
 * base (a millisecond by default), is retimed to exact sample counts read from the frames.
 *
 * <p>The output carries H.264 or HEVC video and AAC, Opus, MP3, ALAC or FLAC audio; a selected
 * track with any other codec raises {@link MediaException.Unsupported} naming the codec, so the
 * caller can transcode it instead.
 */
public final class Mp4Remuxer {
    private Mp4Remuxer() {
        throw new AssertionError();
    }

    /**
     * Remuxes the first video and audio tracks of an input into a fast-start MP4 under the default
     * limits.
     *
     * @param input the input; not closed
     * @param output the sink to write, from its position; not closed
     * @return what was written
     * @throws MediaException.Unsupported if the input container is not recognized, it has no
     * selectable track, or a selected track's codec cannot be stored in MP4
     * @throws MediaException.Malformed if the input is corrupt
     * @throws MediaException.LimitExceeded if the input exceeds the limits
     * @throws MediaException.Io if reading the input or writing the output fails
     * @see #remux(MediaSource, MediaSink, RemuxOptions)
     */
    public static RemuxResult remux(MediaSource input, MediaSink output) {
        return remux(input, output, RemuxOptions.defaults());
    }

    /**
     * Remuxes an input into a fast-start MP4 or M4A.
     *
     * <p>The output is written strictly in order, so any sink works, a streaming one included.
     *
     * @param input the input; not closed
     * @param output the sink to write, from its position; not closed
     * @param options the track selection, brand and limits
     * @return what was written
     * @throws IllegalArgumentException if a selected track identifier does not name a track of the
     * right kind
     * @throws MediaException.Unsupported if the input container is not recognized, it has no
     * selectable track, or a selected track's codec cannot be stored in MP4
     * @throws MediaException.Malformed if the input is corrupt
     * @throws MediaException.LimitExceeded if the input exceeds the limits
     * @throws MediaException.Io if reading the input or writing the output fails
     */
    public static RemuxResult remux(MediaSource input, MediaSink output, RemuxOptions options) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options");
        var format = detect(input);
        var start = output.position();
        try (var demuxer = open(input, format, options)) {
            var copies = prepare(demuxer, options, format);
            var muxerOptions = Mp4MuxerOptions.builder()
                    .brand(options.brand())
                    .fastStart(true)
                    .temporaryDirectory(options.temporaryDirectory().orElse(null))
                    .build();
            try (var muxer = new Mp4Muxer(output, muxerOptions)) {
                for (var copy : copies) {
                    muxer.addTrack(copy.output);
                }
                long packets;
                try {
                    if (demuxer instanceof Mp4Demuxer indexed && plannable(copies)) {
                        muxer.streamAfter(plan(indexed, copies, muxerOptions));
                    } else if (format == ContainerFormat.MATROSKA && input.size() > MdatSpool.MEMORY_LIMIT && LocalSource.isLocal(input)) {
                        var header = planByReading(input, format, options, muxerOptions);
                        if (header != null) {
                            muxer.streamAfter(header);
                        }
                    }
                    packets = copyPackets(demuxer, muxer, copies);
                    muxer.finish();
                } catch (MediaException exception) {
                    throw exception;
                } catch (IllegalArgumentException | IllegalStateException | ArithmeticException exception) {
                    throw new MediaException.Malformed("inconsistent input timing: " + exception.getMessage(), exception);
                } catch (RuntimeException exception) {
                    throw new MediaException.Malformed("cannot remux the input: " + exception, exception);
                }
                var tracks = new ArrayList<Track>();
                for (var copy : copies) {
                    tracks.add(copy.output);
                }
                var end = output.position();
                return new RemuxResult(format, tracks, packets, muxer.durationMicros(), end - start);
            }
        }
    }

    /**
     * Opens the demuxer of an input.
     *
     * @param input the input to demux; not closed
     * @param format the detected container
     * @param options the remux options
     * @return the demuxer
     */
    private static Demuxer open(MediaSource input, ContainerFormat format, RemuxOptions options) {
        var demuxerOptions = DemuxerOptions.builder().limits(options.limits()).build();
        return format == ContainerFormat.MP4 ? new Mp4Demuxer(input, demuxerOptions)
                : new MatroskaDemuxer(input, demuxerOptions);
    }

    /**
     * Selects the tracks to copy and plans their conversion.
     *
     * @param demuxer the input
     * @param options the remux options
     * @param format the detected container
     * @return the track plans, video first
     * @throws MediaException.Unsupported if nothing can be copied or a codec cannot be stored
     */
    private static List<Copy> prepare(Demuxer demuxer, RemuxOptions options, ContainerFormat format) {
        var video = options.brand() == Mp4MuxerOptions.Brand.M4A ? null
                : select(demuxer.tracks(), TrackKind.VIDEO, options.selection(true));
        var audio = select(demuxer.tracks(), TrackKind.AUDIO, options.selection(false));
        if (video == null && audio == null) {
            throw new MediaException.Unsupported("no audio or video", "the input has no track to remux");
        }
        var copies = new ArrayList<Copy>();
        if (video != null) {
            copies.add(prepareVideo(video, copies.size() + 1, format));
        }
        if (audio != null) {
            copies.add(prepareAudio(audio, copies.size() + 1, format));
        }
        return copies;
    }

    /**
     * Returns whether an input is an ISO base media file whose {@code moov} precedes its first
     * {@code mdat}, so that it can be played while it downloads.
     *
     * @param input the input; not closed
     * @return {@code true} for a fast-start ISO base media file, {@code false} for any other file
     * @throws MediaException.Io if reading the input fails
     */
    public static boolean isFastStart(MediaSource input) {
        Objects.requireNonNull(input, "input");
        try {
            if (detect(input) != ContainerFormat.MP4) {
                return false;
            }
            return TopLevelScan.scan(input, true).isFastStart();
        } catch (MediaException.Malformed | MediaException.Unsupported exception) {
            return false;
        }
    }

    /**
     * Identifies the container of an input by its first bytes.
     *
     * @param input the input, read from its start; not closed
     * @return {@link ContainerFormat#MP4} or {@link ContainerFormat#MATROSKA}
     * @throws MediaException.Unsupported if the input is neither
     */
    static ContainerFormat detect(MediaSource input) {
        return Containers.detect(input)
                .filter(format -> format == ContainerFormat.MP4 || format == ContainerFormat.MATROSKA)
                .orElseThrow(() -> new MediaException.Unsupported("unknown container",
                        "the input is neither ISO base media nor Matroska"));
    }

    /**
     * Selects a track of a kind.
     *
     * @param tracks the input tracks
     * @param kind the kind wanted
     * @param selection a positive track identifier, a negative value for the first track of the
     * kind, or {@code 0} for none
     * @return the track, or {@code null} when none is selected or the input has none of the kind
     * @throws IllegalArgumentException if the identifier names no track of the kind
     */
    private static Track select(List<Track> tracks, TrackKind kind, int selection) {
        if (selection == 0) {
            return null;
        }
        for (var track : tracks) {
            if (track.kind() == kind && (selection < 0 || track.id() == selection)) {
                return track;
            }
        }
        if (selection > 0) {
            throw new IllegalArgumentException("the input has no " + kind.name().toLowerCase() + " track " + selection);
        }
        return null;
    }

    /**
     * Holds the plan for copying one track.
     */
    private static final class Copy {
        /**
         * The input track.
         */
        private final Track input;

        /**
         * The output track description.
         */
        private final Track output;

        /**
         * The retimer converting Matroska audio timestamps, or {@code null}.
         */
        private final AudioRetimer retimer;

        /**
         * The last decoding timestamp written, for repairs.
         */
        private long lastDts = Long.MIN_VALUE;

        /**
         * Constructs a plan.
         *
         * @param input the input track
         * @param output the output track
         * @param retimer the retimer, or {@code null}
         */
        private Copy(Track input, Track output, AudioRetimer retimer) {
            this.input = input;
            this.output = output;
            this.retimer = retimer;
        }
    }

    /**
     * Plans the copy of a video track.
     *
     * @param track the input track
     * @param id the output identifier
     * @param format the input container
     * @return the plan
     * @throws MediaException.Unsupported if the codec cannot be stored or its configuration is
     * missing
     */
    private static Copy prepareVideo(Track track, int id, ContainerFormat format) {
        var video = (VideoTrack) track;
        if (video.codec() != Codec.H264 && video.codec() != Codec.HEVC) {
            throw new MediaException.Unsupported(video.codec().displayName(),
                    "MP4 remuxing carries H.264 or HEVC video, not " + video.codec().displayName());
        }
        var config = video.codecConfig().orElse(null);
        if (config == null || config.length < 7 || config[0] != 1) {
            throw new MediaException.Unsupported(video.codec().displayName() + " without configuration record",
                    "the " + video.codec().displayName() + " track has no decoder configuration record (" + format + ")");
        }
        var output = video.toBuilder().id(id).build();
        return new Copy(track, output, null);
    }

    /**
     * Plans the copy of an audio track.
     *
     * @param track the input track
     * @param id the output identifier
     * @param format the input container
     * @return the plan
     * @throws MediaException.Unsupported if the codec cannot be stored or its configuration is
     * missing
     */
    private static Copy prepareAudio(Track track, int id, ContainerFormat format) {
        var audio = (AudioTrack) track;
        var codec = audio.codec();
        switch (codec) {
            case AAC, OPUS, ALAC, FLAC -> {
                if (audio.codecConfig().isEmpty()) {
                    throw new MediaException.Unsupported(codec.displayName() + " without configuration",
                            "the " + codec.displayName() + " track has no codec configuration");
                }
            }
            case MP3 -> {
            }
            default -> throw new MediaException.Unsupported(codec.displayName(),
                    "MP4 remuxing carries AAC, Opus, MP3, ALAC or FLAC audio, not " + codec.displayName());
        }
        if (format == ContainerFormat.MATROSKA) {
            var rate = codec == Codec.OPUS ? 48000 : audio.sampleRate();
            if (rate > 0) {
                var retimer = new AudioRetimer(audio, rate);
                var duration = audio.duration() > 0 ? audio.timeBase().rescale(audio.duration(), retimer.timeBase()) : -1;
                var output = audio.toBuilder().id(id).timeBase(retimer.timeBase()).duration(duration).build();
                return new Copy(track, output, retimer);
            }
        }
        var output = audio.toBuilder().id(id).build();
        return new Copy(track, output, null);
    }

    /**
     * Returns whether the output layout can be planned without the sample bytes: the muxer reads
     * the first frame of an MP3 track only when the track declares no sampling rate or channel
     * layout.
     *
     * @param copies the track plans
     * @return {@code true} when planning is possible
     */
    private static boolean plannable(List<Copy> copies) {
        for (var copy : copies) {
            if (copy.output instanceof AudioTrack audio && audio.codec() == Codec.MP3
                    && (audio.sampleRate() <= 0 || audio.layout().isEmpty())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Plans the fast-start layout of the output from the input's sample index, then rewinds the
     * input.
     *
     * <p>The plan lets the muxer write the {@code moov} first and every chunk straight after it,
     * so the media data is written once instead of being spooled and copied.
     *
     * @param demuxer the input
     * @param copies the track plans
     * @param options the options of the output muxer
     * @return the header the output muxer writes before the media data
     */
    private static byte[] plan(Mp4Demuxer demuxer, List<Copy> copies, Mp4MuxerOptions options) {
        var planner = Mp4Muxer.planner(options);
        for (var copy : copies) {
            planner.addTrack(copy.output);
        }
        var sample = new Mp4Demuxer.SampleInfo();
        while (demuxer.nextSample(sample)) {
            var copy = find(copies, sample.trackId);
            if (copy != null) {
                var dts = repairDts(copy, sample.dts);
                planner.planSample(copy.output.id(), sample.size, repairPts(copy, sample.pts, dts), dts, sample.duration,
                        sample.keyframe);
            }
        }
        var header = planner.finishPlan();
        demuxer.rewind();
        for (var copy : copies) {
            copy.lastDts = Long.MIN_VALUE;
        }
        return header;
    }

    /**
     * Plans the fast-start layout of the output by demuxing the whole input once, for inputs
     * without a sample index; the input is then demuxed again to write the media data straight
     * to the output.
     *
     * <p>Reading a large local input twice costs less than spooling its media data to a temporary
     * file and copying it back; an input that is not local is spooled instead, since reading it
     * twice could transfer it twice.
     *
     * @param input the input, demuxed once to plan the layout; not closed
     * @param format the detected container
     * @param options the remux options
     * @param muxerOptions the options of the output muxer
     * @return the header the output muxer writes before the media data, or {@code null} when a
     * sample description depends on sample bytes and the layout cannot be planned
     */
    private static byte[] planByReading(MediaSource input, ContainerFormat format, RemuxOptions options,
                                        Mp4MuxerOptions muxerOptions) {
        try (var demuxer = open(input, format, options)) {
            var copies = prepare(demuxer, options, format);
            if (!plannable(copies)) {
                return null;
            }
            var planner = Mp4Muxer.planner(muxerOptions);
            for (var copy : copies) {
                planner.addTrack(copy.output);
            }
            transfer(demuxer, copies, packet -> planner.planSample(packet.trackId(), packet.size(), packet.pts(),
                    packet.dts(), packet.duration(), packet.keyframe()));
            return planner.finishPlan();
        }
    }

    /**
     * Returns the plan of the track a packet belongs to.
     *
     * @param copies the track plans
     * @param trackId the input track identifier
     * @return the plan, or {@code null} when the track is not copied
     */
    private static Copy find(List<Copy> copies, int trackId) {
        for (var copy : copies) {
            if (copy.input.id() == trackId) {
                return copy;
            }
        }
        return null;
    }

    /**
     * Holds a decoding timestamp that would step backwards at the previous one.
     *
     * @param copy the track plan, whose last decoding timestamp is updated
     * @param dts the decoding timestamp, or {@link Packet#NO_TIMESTAMP}
     * @return the repaired decoding timestamp
     */
    private static long repairDts(Copy copy, long dts) {
        if (dts == Packet.NO_TIMESTAMP) {
            return dts;
        }
        if (copy.lastDts != Long.MIN_VALUE && dts < copy.lastDts) {
            dts = copy.lastDts;
        }
        copy.lastDts = dts;
        return dts;
    }

    /**
     * Keeps an audio presentation timestamp from preceding its decoding timestamp.
     *
     * @param copy the track plan
     * @param pts the presentation timestamp, or {@link Packet#NO_TIMESTAMP}
     * @param dts the repaired decoding timestamp, or {@link Packet#NO_TIMESTAMP}
     * @return the repaired presentation timestamp
     */
    private static long repairPts(Copy copy, long pts, long dts) {
        if (pts != Packet.NO_TIMESTAMP && dts != Packet.NO_TIMESTAMP && pts < dts && copy.input.kind() == TrackKind.AUDIO) {
            return dts;
        }
        return pts;
    }

    /**
     * Copies the packets of the selected tracks, repairing their timing.
     *
     * @param demuxer the input
     * @param muxer the output
     * @param copies the track plans
     * @return the number of packets copied
     */
    private static long copyPackets(Demuxer demuxer, Mp4Muxer muxer, List<Copy> copies) {
        if (demuxer instanceof Mp4Demuxer indexed) {
            return copySamples(indexed, muxer, copies);
        }
        return transfer(demuxer, copies, muxer::writePacket);
    }

    /**
     * Copies the samples of an MP4 input straight from the demuxer's buffers into the muxer's
     * chunks, with the timestamp repairs of {@link #transfer}.
     *
     * <p>No packet or payload array is allocated per sample; the samples, their order, their
     * timestamps and the exceptions are those of the packet path.
     *
     * @param demuxer the demuxer of the MP4 input
     * @param muxer the muxer, with every output track declared
     * @param copies the selected tracks
     * @return the number of samples written
     */
    private static long copySamples(Mp4Demuxer demuxer, Mp4Muxer muxer, List<Copy> copies) {
        var count = 0L;
        var sample = new Mp4Demuxer.SampleInfo();
        while (demuxer.readSample(sample)) {
            var copy = find(copies, sample.trackId);
            if (copy == null) {
                continue;
            }
            var dts = repairDts(copy, sample.dts);
            var pts = repairPts(copy, sample.pts, dts);
            muxer.writeSample(copy.output.id(), sample.data, sample.dataOffset, sample.size, pts, dts, sample.duration,
                    sample.keyframe);
            count++;
        }
        return count;
    }

    /**
     * Reads the packets of the selected tracks, repairs their timing, and hands them to a sink
     * with their output track identifiers.
     *
     * @param demuxer the input
     * @param copies the track plans
     * @param sink receives the repaired packets
     * @return the number of packets handed over
     */
    private static long transfer(Demuxer demuxer, List<Copy> copies, java.util.function.Consumer<Packet> sink) {
        var count = 0L;
        Packet packet;
        while ((packet = demuxer.read()) != null) {
            var copy = find(copies, packet.trackId());
            if (copy == null) {
                continue;
            }
            Packet repaired;
            if (copy.retimer != null) {
                repaired = copy.retimer.retime(packet, copy.output.id());
            } else {
                var dts = repairDts(copy, packet.dts());
                var pts = repairPts(copy, packet.pts(), dts);
                repaired = new Packet(copy.output.id(), packet.data(), pts, dts, packet.duration(), packet.flags(),
                        packet.sideData());
            }
            sink.accept(repaired);
            count++;
        }
        return count;
    }
}
