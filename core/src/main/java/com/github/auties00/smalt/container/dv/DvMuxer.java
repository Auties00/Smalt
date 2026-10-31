package com.github.auties00.smalt.container.dv;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.internal.DifAudioTracks;
import com.github.auties00.smalt.internal.header.DifAudio;
import com.github.auties00.smalt.internal.header.DifProfile;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Writes raw DV streams ({@code .dv}): the DIF frames of one {@link Codec#DV} video track, one after the other, with
 * the audio of up to four PCM tracks laid into their audio blocks.
 *
 * <p>Each video packet must be one DIF frame of a single format, as {@code DvEncoder} or a DV demuxer gives it. An
 * audio track is 16-bit PCM ({@link Codec#PCM_S16LE} or {@link Codec#PCM_S16BE}), mono or stereo, at 48 kHz, or for
 * the IEC 61834 formats (25 Mb/s DV) also 44.1 or 32 kHz; it takes one audio pair: a DIF channel, so one track at
 * 25 Mb/s, two for DVCPRO50 and four for DVCPRO HD, whose 720-line frames carry pairs 0 and 1 in the frames of DIF
 * channels 0 and 1 and pairs 2 and 3 in the others. A mono track fills both channels of its pair. The audio is laid in
 * the order it arrives, regardless of gaps in its timestamps, as locked audio: each frame takes the sample count of
 * SMPTE ST 314 clause 4.6.2.1.5 (the five-frame sequence 1600, 1602, 1602, 1602, 1602 at 48 kHz in 60 Hz systems),
 * and the AAUX source and source control packs describe it. Frames wait in the muxer until every audio track has
 * delivered their samples; {@link #finish()} writes the rest, the samples that never came as silence. Without audio
 * tracks the frames are written as they come, their audio blocks untouched; with them, the pairs that no track takes
 * are marked as carrying no audio.
 *
 * <p>The sink is written from its position at construction and is never closed by the muxer. Instances are not
 * thread-safe.
 */
public final class DvMuxer implements Muxer {
    /**
     * The most audio pairs a DIF frame carries.
     */
    private static final int MAX_PAIRS = 4;

    private final MediaSink output;
    private VideoTrack video;
    private final List<AudioTrack> audio = new ArrayList<>();
    private final List<short[]> queues = new ArrayList<>();
    private int[] queued = new int[0];
    private final ArrayDeque<byte[]> frames = new ArrayDeque<>();
    private DifProfile profile;
    private long[] audioFrames;
    private short[] scratch = new short[0];
    private boolean started;
    private boolean finished;

    /**
     * Constructs a muxer writing to a sink.
     *
     * @param output the sink, written from its position; not closed by this muxer
     */
    public DvMuxer(MediaSink output) {
        this.output = Objects.requireNonNull(output, "output");
    }

    /**
     * Returns whether a raw DV stream can carry a codec.
     *
     * @param codec the codec of the track to write
     * @return {@code true} for {@link Codec#DV}, {@link Codec#PCM_S16LE} and {@link Codec#PCM_S16BE}
     */
    public static boolean supports(Codec codec) {
        return switch (Objects.requireNonNull(codec, "codec")) {
            case DV, PCM_S16LE, PCM_S16BE -> true;
            default -> false;
        };
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the track is not a DV video track or a 16-bit PCM audio track of one or
     * two channels at 48, 44.1 or 32 kHz, or is a second video track or a fifth audio track
     * @throws IllegalStateException if a packet was already written or the muxer is finished
     */
    @Override
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track");
        if (started || finished) {
            throw new IllegalStateException("tracks must be declared before the first packet");
        }
        if (track instanceof VideoTrack candidate && track.codec() == Codec.DV) {
            if (video != null) {
                throw new IllegalArgumentException("a DV stream carries one video track");
            }
            video = candidate;
            return;
        }
        if (!(track instanceof AudioTrack candidate)
                || track.codec() != Codec.PCM_S16LE && track.codec() != Codec.PCM_S16BE) {
            throw new IllegalArgumentException("a DV stream cannot carry " + track.codec().displayName());
        }
        var channels = candidate.layout().map(ChannelLayout::channels).orElse(0);
        if (channels < 1 || channels > 2) {
            throw new IllegalArgumentException("a DV audio pair carries one or two channels, not " + channels);
        }
        if (DifAudio.code(candidate.sampleRate()) < 0) {
            throw new IllegalArgumentException("DV audio is sampled at 48, 44.1 or 32 kHz, not "
                    + candidate.sampleRate() + " Hz");
        }
        if (audio.size() == MAX_PAIRS) {
            throw new IllegalArgumentException("a DV stream carries at most " + MAX_PAIRS + " audio pairs");
        }
        audio.add(candidate);
        queues.add(new short[0]);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the packet belongs to an undeclared track, a video packet is not a DIF frame
     * of the stream's format, or the format cannot carry the audio tracks
     * @throws IllegalStateException if no video track was declared or the muxer is finished
     */
    @Override
    public void writePacket(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        if (finished) {
            throw new IllegalStateException("the muxer is finished");
        }
        if (video == null) {
            throw new IllegalStateException("a DV stream needs a video track");
        }
        if (!started) {
            started = true;
            queued = new int[audio.size()];
            audioFrames = new long[audio.size()];
        }
        if (packet.trackId() == video.id()) {
            writeFrame(packet.data());
            return;
        }
        for (var index = 0; index < audio.size(); index++) {
            if (audio.get(index).id() == packet.trackId()) {
                queueAudio(index, packet.data());
                drain(false);
                return;
            }
        }
        throw new IllegalArgumentException("packet for undeclared track " + packet.trackId());
    }

    /**
     * Checks a DIF frame and queues it.
     *
     * @param data the frame
     */
    private void writeFrame(byte[] data) {
        var detected = DifProfile.detect(data, 0, data.length);
        if (detected == null || data.length != detected.frameSize()) {
            throw new IllegalArgumentException("a DV video packet must be one DIF frame");
        }
        if (profile == null) {
            var pairs = detected.progressive() ? MAX_PAIRS : detected.channels();
            if (audio.size() > pairs) {
                throw new IllegalArgumentException(detected + " carries " + pairs + " audio pairs, not "
                        + audio.size());
            }
            for (var track : audio) {
                if (track.sampleRate() != 48000 && detected.applicationId() != 0) {
                    throw new IllegalArgumentException(detected + " carries 48 kHz audio only");
                }
            }
            profile = detected;
        } else if (detected.frameSize() != profile.frameSize()) {
            throw new IllegalArgumentException("the DIF frames change size from " + profile.frameSize() + " to "
                    + detected.frameSize() + " bytes");
        }
        if (audio.isEmpty()) {
            output.write(ByteBuffer.wrap(data));
            return;
        }
        frames.add(data.clone());
        drain(false);
    }

    /**
     * Appends the samples of an audio packet to the queue of its track, as interleaved stereo.
     *
     * @param index the track
     * @param data the packet's samples
     */
    private void queueAudio(int index, byte[] data) {
        var track = audio.get(index);
        var stereo = track.layout().map(ChannelLayout::channels).orElse(0) == 2;
        var little = track.codec() == Codec.PCM_S16LE;
        var samples = data.length / 2;
        var values = stereo ? samples : 2 * samples;
        var queue = queues.get(index);
        if (queue.length < queued[index] + values) {
            queue = java.util.Arrays.copyOf(queue, Math.max(2 * queue.length, queued[index] + values));
            queues.set(index, queue);
        }
        var at = queued[index];
        for (var i = 0; i < samples; i++) {
            var low = data[2 * i + (little ? 0 : 1)] & 0xFF;
            var high = data[2 * i + (little ? 1 : 0)];
            var value = (short) (high << 8 | low);
            if (stereo) {
                queue[at++] = value;
            } else {
                queue[at++] = value;
                queue[at++] = value;
            }
        }
        queued[index] = at;
    }

    /**
     * Returns the pair an audio track takes in a frame, or -1 when the frame carries none of its samples.
     *
     * @param index the track
     * @param base the first DIF channel of the frame, 0 or 2
     * @return the DIF channel within the frame
     */
    private int channelOf(int index, int base) {
        if (!profile.progressive()) {
            return index;
        }
        return index - base >= 0 && index - base < profile.channels() ? index - base : -1;
    }

    /**
     * Writes the queued frames whose audio has arrived, or all of them.
     *
     * @param all whether to write every queued frame, padding missing samples with silence
     */
    private void drain(boolean all) {
        while (!frames.isEmpty()) {
            var frame = frames.peek();
            var base = DifAudioTracks.channelBase(profile, frame, 0);
            if (!all) {
                for (var index = 0; index < audio.size(); index++) {
                    if (channelOf(index, base) >= 0 && queued[index] < 2 * needed(index)) {
                        return;
                    }
                }
            }
            frames.poll();
            for (var channel = 0; channel < profile.channels(); channel++) {
                DifAudio.clear(frame, 0, profile, channel);
            }
            for (var index = 0; index < audio.size(); index++) {
                var channel = channelOf(index, base);
                if (channel < 0) {
                    continue;
                }
                var count = needed(index);
                var queue = queues.get(index);
                if (scratch.length < 2 * count) {
                    scratch = new short[2 * DifAudio.capacity(profile, false)];
                }
                var available = Math.min(queued[index], 2 * count);
                System.arraycopy(queue, 0, scratch, 0, available);
                java.util.Arrays.fill(scratch, available, 2 * count, (short) 0);
                System.arraycopy(queue, available, queue, 0, queued[index] - available);
                queued[index] -= available;
                DifAudio.write(frame, 0, profile, channel, audio.get(index).sampleRate(), false, scratch, 0, count);
                audioFrames[index]++;
            }
            output.write(ByteBuffer.wrap(frame));
        }
    }

    /**
     * Returns the samples per channel the next frame of a track takes.
     *
     * @param index the track
     * @return the count
     */
    private int needed(int index) {
        return DifAudio.samplesPerFrame(profile, audio.get(index).sampleRate(), audioFrames[index]);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if no video track was declared or the muxer is already finished
     * @throws MediaException.Io if writing fails
     */
    @Override
    public void finish() {
        if (finished) {
            throw new IllegalStateException("the muxer is already finished");
        }
        if (video == null) {
            throw new IllegalStateException("a DV stream needs a video track");
        }
        finished = true;
        if (profile != null) {
            drain(true);
        }
    }

    /**
     * Marks the muxer finished, dropping the frames still waiting for audio.
     */
    @Override
    public void close() {
        finished = true;
        frames.clear();
    }
}
