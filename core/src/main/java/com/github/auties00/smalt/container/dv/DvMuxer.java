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
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Writes raw DV streams ({@code .dv}): the DIF frames of one {@link Codec#DV} video track, one after the other, with
 * the audio of PCM tracks laid into their audio blocks.
 *
 * <p>Each video packet must be one DIF frame of a single format, as {@code DvEncoder} or a DV demuxer gives it. An
 * audio track is 16-bit PCM ({@link Codec#PCM_S16LE} or {@link Codec#PCM_S16BE}) of one to eight channels, at 48 kHz,
 * or for the consumer formats of IEC 61834 (see {@link DvFormat#consumer()}) also 44.1 or 32 kHz. The audio of a
 * frame comes in pairs of channels: the tracks take consecutive pairs in the order they are declared, a track of
 * {@code n} channels taking {@code (n + 1) / 2} of them; a track's channels fill its pairs in order, and the last
 * channel of a track of an odd count (the channel of a mono track) fills both channels of its pair. A format carries
 * {@link DvFormat#maxAudioChannels(int)} channels: at 16 bits one pair per DIF channel, so one pair at 25 Mb/s, two for
 * DVCPRO50 and four for DVCPRO HD, whose 720-line frames carry pairs 0 and 1 in the frames of DIF channels 0 and 1 and
 * pairs 2 and 3 in the others, so that a track there takes pairs of one of those groups only. A consumer format
 * carries two pairs, four channels, when every track is at 32 kHz: they are then coded in 12 bits, both pairs in the
 * one DIF channel. A raw DV stream carries no channel layout, so readers see one stereo track per pair.
 *
 * <p>The audio is laid in the order it arrives, regardless of gaps in its timestamps, the AAUX packs marking 48 kHz
 * audio as locked and the other rates as unlocked: each frame takes the sample count of SMPTE ST 314 clause
 * 4.6.2.1.5 (the five-frame sequence 1600, 1602, 1602, 1602, 1602 at 48 kHz in 60 Hz systems), and the AAUX source
 * and source control packs describe it. Frames wait in the muxer until every audio track has delivered their
 * samples; {@link #finish()} writes the rest, the samples that never came as silence. Without audio tracks the frames
 * are written as they come, their audio blocks untouched; with them, the pairs that no track takes are marked as
 * carrying no audio.
 *
 * <p>The sink is written from its position at construction and is never closed by the muxer. Instances are not
 * thread-safe.
 */
public final class DvMuxer implements Muxer {
    /**
     * The most audio pairs a DIF frame carries.
     */
    private static final int MAX_PAIRS = 4;

    /**
     * The most channels an audio track may have: every pair of a frame.
     */
    private static final int MAX_CHANNELS = 2 * MAX_PAIRS;

    private final MediaSink output;
    private VideoTrack video;
    private final List<AudioTrack> audio = new ArrayList<>();

    /**
     * The first pair of each audio track.
     */
    private int[] firstPairs = new int[0];

    /**
     * The pairs each audio track takes.
     */
    private int[] pairCounts = new int[0];

    /**
     * The pairs the declared tracks take together.
     */
    private int pairs;

    /**
     * The queued samples of each audio track, {@code 2 * pairCounts[track]} values per sample instant.
     */
    private final List<short[]> queues = new ArrayList<>();

    /**
     * The values queued for each track.
     */
    private int[] queued = new int[0];

    private final ArrayDeque<byte[]> frames = new ArrayDeque<>();
    private DifProfile profile;

    /**
     * Whether the pairs are coded in 12 bits, two per DIF channel.
     */
    private boolean twelveBit;

    /**
     * The audio frames each track has written.
     */
    private long[] audioFrames;

    /**
     * The interleaved stereo samples of the pair being written.
     */
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
     * @throws IllegalArgumentException if the track is not a DV video track or a 16-bit PCM audio track of one to eight
     * channels at 48, 44.1 or 32 kHz, is a second video track, or takes pairs beyond the fourth
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
        if (channels < 1 || channels > MAX_CHANNELS) {
            throw new IllegalArgumentException("a DV audio track carries one to " + MAX_CHANNELS + " channels, not "
                    + channels);
        }
        if (DifAudio.code(candidate.sampleRate()) < 0) {
            throw new IllegalArgumentException("DV audio is sampled at 48, 44.1 or 32 kHz, not "
                    + candidate.sampleRate() + " Hz");
        }
        var count = (channels + 1) / 2;
        if (pairs + count > MAX_PAIRS) {
            throw new IllegalArgumentException("a DV stream carries at most " + MAX_PAIRS + " audio pairs, and the "
                    + (audio.size() + 1) + " audio tracks take " + (pairs + count));
        }
        var index = audio.size();
        firstPairs = Arrays.copyOf(firstPairs, index + 1);
        pairCounts = Arrays.copyOf(pairCounts, index + 1);
        firstPairs[index] = pairs;
        pairCounts[index] = count;
        pairs += count;
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
                if (profile != null) {
                    drain(false);
                }
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
            twelveBit = checkAudio(detected);
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
     * Checks that a format carries the declared audio tracks.
     *
     * @param detected the format of the first frame
     * @return whether the pairs are coded in 12 bits
     * @throws IllegalArgumentException if the format cannot carry the tracks
     */
    private boolean checkAudio(DifProfile detected) {
        if (audio.isEmpty()) {
            return false;
        }
        var consumer = detected.applicationId() == 0;
        for (var track : audio) {
            if (track.sampleRate() != 48000 && !consumer) {
                throw new IllegalArgumentException(detected + " carries 48 kHz audio only");
            }
        }
        var capacity = detected.progressive() ? MAX_PAIRS : detected.channels();
        if (pairs <= capacity) {
            if (detected.progressive()) {
                for (var index = 0; index < audio.size(); index++) {
                    if (firstPairs[index] / 2 != (firstPairs[index] + pairCounts[index] - 1) / 2) {
                        throw new IllegalArgumentException("audio track " + audio.get(index).id() + " takes pairs "
                                + firstPairs[index] + " to " + (firstPairs[index] + pairCounts[index] - 1)
                                + ", which " + detected + " carries in different frames");
                    }
                }
            }
            return false;
        }
        if (!consumer || detected.channels() != 1 || pairs > 2) {
            throw new IllegalArgumentException(detected + " carries " + capacity + " audio pairs, not " + pairs);
        }
        for (var track : audio) {
            if (track.sampleRate() != 32000) {
                throw new IllegalArgumentException(detected + " carries two audio pairs at 32 kHz only, not at "
                        + track.sampleRate() + " Hz");
            }
        }
        return true;
    }

    /**
     * Appends the samples of an audio packet to the queue of its track, each sample instant widened to the track's
     * pairs.
     *
     * @param index the track
     * @param data the packet's samples
     */
    private void queueAudio(int index, byte[] data) {
        var track = audio.get(index);
        var channels = track.layout().map(ChannelLayout::channels).orElse(0);
        var width = 2 * pairCounts[index];
        var little = track.codec() == Codec.PCM_S16LE;
        var instants = data.length / (2 * channels);
        var queue = queues.get(index);
        if (queue.length < queued[index] + instants * width) {
            queue = Arrays.copyOf(queue, Math.max(2 * queue.length, queued[index] + instants * width));
            queues.set(index, queue);
        }
        var at = queued[index];
        var read = 0;
        for (var i = 0; i < instants; i++) {
            for (var channel = 0; channel < channels; channel++) {
                var low = data[read + (little ? 0 : 1)] & 0xFF;
                var high = data[read + (little ? 1 : 0)];
                read += 2;
                queue[at + channel] = (short) (high << 8 | low);
            }
            if ((channels & 1) != 0) {
                queue[at + channels] = queue[at + channels - 1];
            }
            at += width;
        }
        queued[index] = at;
    }

    /**
     * Returns whether a frame carries the samples of a track.
     *
     * @param index the track
     * @param base the first DIF channel of the frame, 0 or 2
     * @return {@code false} for a 720-line frame of the DIF channels of the other pairs
     */
    private boolean carries(int index, int base) {
        return !profile.progressive() || firstPairs[index] / 2 == base / 2;
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
                    if (carries(index, base) && queued[index] < 2 * pairCounts[index] * needed(index)) {
                        return;
                    }
                }
            }
            frames.poll();
            for (var channel = 0; channel < profile.channels(); channel++) {
                DifAudio.clear(frame, 0, profile, channel);
            }
            for (var index = 0; index < audio.size(); index++) {
                if (carries(index, base)) {
                    writeTrack(frame, index, base);
                }
            }
            output.write(ByteBuffer.wrap(frame));
        }
    }

    /**
     * Lays the next audio frame of a track into a frame and removes its samples from the queue.
     *
     * @param frame the DIF frame
     * @param index the track
     * @param base the first DIF channel of the frame
     */
    private void writeTrack(byte[] frame, int index, int base) {
        var count = needed(index);
        var width = 2 * pairCounts[index];
        var queue = queues.get(index);
        var available = Math.min(queued[index] / width, count);
        if (scratch.length < 2 * count) {
            scratch = new short[2 * Math.max(count, DifAudio.capacity(profile, false))];
        }
        for (var pair = 0; pair < pairCounts[index]; pair++) {
            for (var n = 0; n < available; n++) {
                scratch[2 * n] = queue[n * width + 2 * pair];
                scratch[2 * n + 1] = queue[n * width + 2 * pair + 1];
            }
            Arrays.fill(scratch, 2 * available, 2 * count, (short) 0);
            var target = firstPairs[index] + pair;
            if (!twelveBit && profile.progressive()) {
                target -= base;
            }
            DifAudio.write(frame, 0, profile, target, audio.get(index).sampleRate(), twelveBit, scratch, 0, count);
        }
        var used = available * width;
        System.arraycopy(queue, used, queue, 0, queued[index] - used);
        queued[index] -= used;
        audioFrames[index]++;
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
