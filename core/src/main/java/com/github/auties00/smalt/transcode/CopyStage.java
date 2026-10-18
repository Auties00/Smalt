package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Copies the packets of one track into the output without decoding them.
 *
 * <p>Without a start trim the packets keep their timestamps, so the timing conventions of the
 * input (encoder priming, Ogg's negative Opus timestamps, edit lists) carry over unchanged. With a
 * start trim the timestamps are moved to the output timeline:
 * <ul>
 *   <li>video starts at the last key frame at or before the start, so the frames before the start
 *       get negative presentation timestamps; the packets of that key frame's group are held until
 *       a packet decodes after the start, which bounds the memory to one group of pictures;</li>
 *   <li>audio starts at the packet holding the start, preceded by one packet of decoder pre-roll;
 *       its timestamps become presentation times (the encoder priming is subtracted and the output
 *       track declares none), so the leading part before the start is negative too.</li>
 * </ul>
 * PCM needs no decoder, so its packets are cut to exactly the samples of the range instead, in
 * every container.
 * Containers with edit lists present negative timestamps before time zero, which makes the trim
 * exact; the others shift the whole output so it starts at the first copied packet. With an end
 * trim, video stops at the first packet that decodes at or after the end (later frames are needed
 * by none of the kept ones) and audio stops after the packet holding the end, whose duration is
 * shortened so muxers trim the excess; the output track declares the kept duration, which the MP4
 * edit list and the final Ogg granule position honor.
 *
 * <p>An instance is not thread-safe.
 */
final class CopyStage implements Stage {
    private final Track source;
    private final Track declared;
    private final OutputMuxer output;
    private final boolean video;

    /**
     * The start of the kept range in the track's time base.
     */
    private final long start;

    /**
     * The end of the kept range in the track's time base, or {@code -1} for the end of the track.
     */
    private final long end;

    /**
     * The video packets of the group of pictures that may hold the start, in decoding order.
     */
    private final List<Packet> group;

    /**
     * Whether packets are being written.
     */
    private boolean started;

    private boolean done;

    /**
     * The audio packet received but not processed yet, or {@code null}.
     */
    private Packet held;

    /**
     * The last audio packet that ends before the start, kept as decoder pre-roll, or {@code null}.
     */
    private Packet preRoll;

    /**
     * The encoder priming of the audio track in its time base, or {@link Long#MIN_VALUE} before the
     * first packet.
     */
    private long priming = Long.MIN_VALUE;

    /**
     * The bytes of one PCM sample frame (every channel), or {@code 0} for a coded format.
     */
    private final int pcmFrameBytes;

    /**
     * The output time written, in the track's time base.
     */
    private long position;

    /**
     * Constructs a stage and declares its output track.
     *
     * @param source the input track
     * @param outputId the output track identifier
     * @param output the muxer receiving the packets
     * @param startMicros the start of the kept range, in microseconds
     * @param endMicros the end of the kept range in microseconds, or {@code -1} for the end of the track
     */
    CopyStage(Track source, int outputId, OutputMuxer output, long startMicros, long endMicros) {
        this.source = source;
        this.output = output;
        this.video = source instanceof VideoTrack;
        var timeBase = source.timeBase();
        this.start = Rational.MICROSECONDS.rescale(startMicros, timeBase);
        this.end = endMicros < 0 ? -1 : Rational.MICROSECONDS.rescale(endMicros, timeBase);
        this.group = new ArrayList<>();
        this.started = start <= 0 && video;
        this.pcmFrameBytes = source instanceof AudioTrack audio ? pcmFrameBytes(audio) : 0;
        this.declared = outputTrack(source, outputId, start, end);
        output.declare(declared);
    }

    /**
     * Derives the output track from the input track.
     *
     * @param source the input track
     * @param outputId the output track identifier
     * @param start the start of the kept range in the track's time base
     * @param end the end of the kept range in the track's time base, or {@code -1} for the end of the track
     * @return the output track
     */
    private static Track outputTrack(Track source, int outputId, long start, long end) {
        var duration = end >= 0 ? end - start : source.duration() >= 0 && start > 0 ? Math.max(0, source.duration() - start) : source.duration();
        return switch (source) {
            case VideoTrack track -> track.toBuilder().id(outputId).duration(duration).build();
            case AudioTrack track -> {
                var builder = track.toBuilder().id(outputId).duration(duration);
                if (start > 0) {
                    builder.skipSamples(0);
                }
                yield builder.build();
            }
            default -> throw new IllegalArgumentException("cannot copy a " + source.codec().displayName() + " track");
        };
    }

    @Override
    public int sourceTrackId() {
        return source.id();
    }

    @Override
    public void accept(Packet packet) {
        if (done) {
            return;
        }
        if (video) {
            acceptVideo(packet);
        } else {
            if (held != null) {
                var duration = held.duration();
                if (duration <= 0 && held.pts() != Packet.NO_TIMESTAMP && packet.pts() != Packet.NO_TIMESTAMP
                        && packet.pts() > held.pts()) {
                    held = held.withTiming(held.pts(), held.dts(), packet.pts() - held.pts());
                }
                processAudio(held);
            }
            held = packet;
        }
    }

    /**
     * Copies one video packet.
     *
     * @param packet a video packet of the copied track
     */
    private void acceptVideo(Packet packet) {
        var decodeTime = packet.dts() != Packet.NO_TIMESTAMP ? packet.dts() : packet.pts();
        if (end >= 0 && decodeTime != Packet.NO_TIMESTAMP && decodeTime >= end) {
            done = true;
            return;
        }
        if (!started) {
            var presentation = packet.pts() != Packet.NO_TIMESTAMP ? packet.pts() : decodeTime;
            if (packet.keyframe() && presentation <= start) {
                group.clear();
                group.add(packet);
                return;
            }
            if (group.isEmpty()) {
                if (!packet.keyframe()) {
                    return;
                }
            } else if (decodeTime != Packet.NO_TIMESTAMP && decodeTime <= start && !packet.keyframe()) {
                group.add(packet);
                return;
            }
            started = true;
            flushGroup();
        }
        writeVideo(packet);
    }

    /**
     * Writes the held group of pictures.
     */
    private void flushGroup() {
        for (var packet : group) {
            writeVideo(packet);
        }
        group.clear();
    }

    /**
     * Writes one video packet on the output timeline.
     *
     * @param packet the video packet, already inside the kept range
     */
    private void writeVideo(Packet packet) {
        var pts = shift(packet.pts());
        var dts = shift(packet.dts());
        if (pts != Packet.NO_TIMESTAMP) {
            position = Math.max(position, pts + Math.max(0, packet.duration()));
        }
        output.write(new Packet(declared.id(), packet.data(), pts, dts, packet.duration(), packet.flags(), packet.sideData()));
    }

    /**
     * Moves a timestamp to the output timeline.
     *
     * @param timestamp the input timestamp, or {@link Packet#NO_TIMESTAMP}
     * @return the output timestamp
     */
    private long shift(long timestamp) {
        return timestamp == Packet.NO_TIMESTAMP || start <= 0 ? timestamp : timestamp - start;
    }

    /**
     * Copies one audio packet whose duration is known, unless it lies outside the kept range.
     *
     * @param packet an audio packet of the copied track
     */
    private void processAudio(Packet packet) {
        if (priming == Long.MIN_VALUE) {
            priming = priming((AudioTrack) source, packet);
        }
        if (packet.pts() == Packet.NO_TIMESTAMP) {
            writeAudio(packet, packet.duration());
            return;
        }
        var presentationStart = packet.pts() - priming;
        var presentationEnd = presentationStart + Math.max(0, packet.duration());
        if (pcmFrameBytes > 0) {
            processPcm(packet, presentationStart, presentationEnd);
            return;
        }
        if (!started) {
            if (start > 0 && presentationEnd <= start && packet.duration() > 0) {
                preRoll = packet;
                return;
            }
            started = true;
            if (preRoll != null) {
                writeAudio(preRoll, preRoll.duration());
                preRoll = null;
            }
        }
        if (end >= 0 && presentationStart >= end) {
            done = true;
            return;
        }
        var duration = packet.duration();
        if (end >= 0 && presentationEnd > end) {
            duration = end - presentationStart;
            done = true;
        }
        writeAudio(packet, duration);
    }

    /**
     * Copies the samples of one PCM packet that lie inside the kept range.
     *
     * @param packet a PCM packet of the copied track
     * @param presentationStart its presentation start in the track's time base
     * @param presentationEnd its presentation end in the track's time base
     */
    private void processPcm(Packet packet, long presentationStart, long presentationEnd) {
        if (presentationEnd <= start && packet.duration() > 0) {
            return;
        }
        if (end >= 0 && presentationStart >= end) {
            done = true;
            return;
        }
        started = true;
        var timeBase = source.timeBase();
        var samples = Rational.timeBaseOf(((AudioTrack) source).sampleRate());
        var frames = packet.size() / pcmFrameBytes;
        var lead = presentationStart < start ? (int) Math.min(frames, timeBase.rescale(start - presentationStart, samples)) : 0;
        var kept = frames - lead;
        if (end >= 0 && presentationEnd > end) {
            kept = (int) Math.clamp(timeBase.rescale(end - presentationStart, samples) - lead, 0, kept);
            done = true;
        }
        if (kept <= 0) {
            return;
        }
        var data = lead == 0 && kept == frames ? packet.data()
                : Arrays.copyOfRange(packet.data(), lead * pcmFrameBytes, (lead + kept) * pcmFrameBytes);
        var pts = packet.pts() + samples.rescale(lead, timeBase);
        var dts = packet.dts() == Packet.NO_TIMESTAMP ? Packet.NO_TIMESTAMP : packet.dts() + samples.rescale(lead, timeBase);
        writeAudio(new Packet(packet.trackId(), data, pts, dts, samples.rescale(kept, timeBase), packet.flags(),
                packet.sideData()), samples.rescale(kept, timeBase));
    }

    /**
     * Returns the size of one sample frame of a PCM track.
     *
     * @param track a PCM audio track
     * @return the bytes of one sample of every channel, or {@code 0} when the track is not PCM or
     * its channel count is unknown
     */
    private static int pcmFrameBytes(AudioTrack track) {
        var channels = track.layout().map(ChannelLayout::channels).orElse(0);
        var bits = track.codec().pcmBitsPerSample();
        if (bits.isEmpty() || channels == 0 || track.sampleRate() <= 0) {
            return 0;
        }
        return bits.getAsInt() / 8 * channels;
    }

    /**
     * Writes one audio packet on the output timeline.
     *
     * @param packet the audio packet, already inside the kept range
     * @param duration the duration to declare
     */
    private void writeAudio(Packet packet, long duration) {
        var pts = packet.pts();
        var dts = packet.dts();
        if (start > 0) {
            pts = pts == Packet.NO_TIMESTAMP ? pts : pts - priming - start;
            dts = dts == Packet.NO_TIMESTAMP ? dts : dts - priming - start;
        }
        if (pts != Packet.NO_TIMESTAMP) {
            var presentation = start > 0 ? pts : pts - Math.max(0, priming);
            position = Math.max(position, presentation + Math.max(0, duration));
        }
        output.write(new Packet(declared.id(), packet.data(), pts, dts, duration, packet.flags(), packet.sideData()));
    }

    /**
     * Returns the encoder priming of an audio track, the offset between its packet timestamps and
     * its presentation times.
     *
     * <p>The priming is the track's {@link AudioTrack#skipSamples()}; an Opus track that declares
     * none and whose first packet starts at or after zero carries it in its {@code OpusHead}
     * pre-skip instead, while an Opus track whose packets start before zero (as Ogg Opus does)
     * already presents its first sample at time zero.
     *
     * @param track the copied audio track
     * @param first the first packet of the track
     * @return the priming in the track's time base
     */
    private static long priming(AudioTrack track, Packet first) {
        var timeBase = track.timeBase();
        if (track.skipSamples() > 0) {
            return track.sampleRate() > 0
                    ? Rational.timeBaseOf(track.sampleRate()).rescale(track.skipSamples(), timeBase)
                    : track.skipSamples();
        }
        var preSkip = track.codec() == Codec.OPUS ? AudioConfigs.opusPreSkip(track.codecConfig().orElse(null)) : -1;
        if (preSkip >= 0 && first.pts() >= 0) {
            return Rational.timeBaseOf(48_000).rescale(preSkip, timeBase);
        }
        return 0;
    }

    @Override
    public void finish() {
        if (video) {
            if (!started && !done) {
                started = true;
                flushGroup();
            }
            return;
        }
        if (held != null && !done) {
            processAudio(held);
        }
        held = null;
    }

    @Override
    public boolean done() {
        return done;
    }

    @Override
    public long positionMicros() {
        return source.timeBase().toMicros(Math.max(0, position));
    }

    @Override
    public TranscodedStream result() {
        return new TranscodedStream(source.id(), declared, true);
    }

    @Override
    public void close() {
    }
}
