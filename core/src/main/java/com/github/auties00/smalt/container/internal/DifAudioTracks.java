package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.internal.header.DifAudio;
import com.github.auties00.smalt.internal.header.DifProfile;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Turns the audio that DIF frames carry into PCM tracks: one 16-bit little-endian stereo track per audio pair whose
 * AAUX source pack the first DIF frame names, with packets of the samples of each frame.
 *
 * <p>At 16 bits a pair is a DIF channel; at 12 bits (the four-channel 32 kHz mode) each DIF channel carries two pairs,
 * one in each half of its sequences, and the 12-bit samples are expanded to 16 bits. A 720-line packet carries two
 * DIF channels, 0 and 1 or 2 and 3 by the FSP bit of its block IDs, so its pairs alternate between packets. The packets
 * of a track are timestamped in samples, counting what the earlier frames carried; a frame whose block holds no source
 * pack contributes the nominal count of SMPTE ST 314 clause 4.6.2.1.5 for its rate.
 *
 * <p>Instances are not thread-safe.
 */
public final class DifAudioTracks {
    private final DifProfile profile;
    private final boolean twelveBit;
    private final List<AudioTrack> tracks;
    private final int[] channels;
    private final int[] halves;
    private final int[] rates;
    private final long[] positions;
    private final long[] frames;
    private final long[] skipBefore;
    private final int[][] counts;
    private final int[] counted;
    private short[] samples = new short[0];

    private DifAudioTracks(DifProfile profile, boolean twelveBit, List<AudioTrack> tracks, int[] channels,
                           int[] halves, int[] rates) {
        this.profile = profile;
        this.twelveBit = twelveBit;
        this.tracks = List.copyOf(tracks);
        this.channels = channels;
        this.halves = halves;
        this.rates = rates;
        this.positions = new long[channels.length];
        this.frames = new long[channels.length];
        this.skipBefore = new long[channels.length];
        this.counts = new int[channels.length][16];
        this.counted = new int[channels.length];
    }

    /**
     * Finds the audio pairs of a stream.
     *
     * @param profile the profile of the stream
     * @param data the array holding the first packet, and for a 720-line profile the second one after it
     * @param offset the index of the first packet's first byte
     * @param length the number of bytes available from {@code offset}: one packet, or two for a 720-line profile
     * when the stream has two
     * @param firstId the track ID of the first audio track; the others follow
     * @return the tracks, possibly none
     */
    public static DifAudioTracks of(DifProfile profile, byte[] data, int offset, int length, int firstId) {
        var size = profile.frameSize();
        var first = DifAudio.format(data, offset, profile, 0);
        var twelveBit = first != null && first.twelveBit();
        var tracks = new ArrayList<AudioTrack>();
        var channels = new ArrayList<Integer>();
        var halves = new ArrayList<Integer>();
        var rates = new ArrayList<Integer>();
        var packets = profile.progressive() ? Math.min(2, length / size) : 1;
        for (var packet = 0; packet < packets; packet++) {
            var at = offset + packet * size;
            var base = channelBase(profile, data, at);
            if (packet == 1 && base == channelBase(profile, data, offset)) {
                break;
            }
            for (var channel = 0; channel < profile.channels(); channel++) {
                for (var half = 0; half < (twelveBit ? 2 : 1); half++) {
                    var format = DifAudio.format(data, at, profile, channel, half);
                    if (format == null || !format.valid() || format.twelveBit() != twelveBit) {
                        continue;
                    }
                    var id = firstId + tracks.size();
                    tracks.add(AudioTrack.builder()
                            .id(id)
                            .codec(Codec.PCM_S16LE)
                            .timeBase(Rational.timeBaseOf(format.sampleRate()))
                            .sampleRate(format.sampleRate())
                            .layout(ChannelLayout.STEREO)
                            .bitsPerSample(16)
                            .build());
                    channels.add(base + channel);
                    halves.add(half);
                    rates.add(format.sampleRate());
                }
            }
        }
        return new DifAudioTracks(profile, twelveBit, tracks, toArray(channels), toArray(halves), toArray(rates));
    }

    /**
     * Returns the first DIF channel a packet carries: 2 for the second video frame of a 720-line DIF frame, whose
     * block IDs have FSP 0, otherwise 0.
     *
     * @param profile the DV format
     * @param data the array holding the packet
     * @param offset the index of the packet's first byte
     * @return 0 or 2
     */
    public static int channelBase(DifProfile profile, byte[] data, int offset) {
        return profile.progressive() && (data[offset + 1] & 0x04) == 0 ? 2 : 0;
    }

    private static int[] toArray(List<Integer> values) {
        var array = new int[values.size()];
        for (var i = 0; i < array.length; i++) {
            array[i] = values.get(i);
        }
        return array;
    }

    /**
     * {@return the audio tracks}
     */
    public List<AudioTrack> tracks() {
        return tracks;
    }

    /**
     * Restarts every track for a seek: the stream resumes at a frame, and each track at its last packet that starts
     * at or before the target, whose earlier packets of the frames read from there are dropped.
     *
     * <p>The timestamps count the samples of the audio frames already read and the nominal count of SMPTE ST 314
     * clause 4.6.2.1.5 for the others.
     *
     * @param frame the index of the next packet of the stream
     * @param micros the seek target in microseconds
     */
    public void restart(long frame, long micros) {
        for (var track = 0; track < channels.length; track++) {
            long audioFrame;
            if (profile.progressive()) {
                // pairs 0 and 1 ride the even frames, 2 and 3 the odd ones; an audio frame spans two of them
                audioFrame = channels[track] >= 2 ? frame / 2 : (frame + 1) / 2;
            } else {
                audioFrame = frame;
            }
            var position = 0L;
            for (var f = 0L; f < audioFrame; f++) {
                position += count(track, f);
            }
            var target = Math.floorDiv(micros * rates[track], 1_000_000L);
            var chosen = audioFrame;
            var start = position;
            while (start + count(track, chosen) <= target) {
                start += count(track, chosen);
                chosen++;
            }
            positions[track] = position;
            frames[track] = audioFrame;
            skipBefore[track] = chosen;
        }
    }

    /**
     * Returns the sample count of an audio frame of a track: the one read, or the nominal one for a frame not read.
     *
     * @param track the index of the track
     * @param frame the audio frame
     * @return the count
     */
    private int count(int track, long frame) {
        if (frame < counted[track]) {
            return counts[track][(int) frame];
        }
        return DifAudio.samplesPerFrame(profile, rates[track], frame);
    }

    /**
     * Records the sample count of an audio frame of a track as it is read.
     *
     * @param track the index of the track
     * @param frame the audio frame
     * @param count the number of samples per channel the frame carries
     */
    private void record(int track, long frame, int count) {
        if (frame != counted[track] || frame >= Integer.MAX_VALUE) {
            return;
        }
        if (counted[track] == counts[track].length) {
            counts[track] = java.util.Arrays.copyOf(counts[track], 2 * counts[track].length);
        }
        counts[track][counted[track]++] = count;
    }

    /**
     * Extracts the audio of a packet.
     *
     * @param data the array holding the packet
     * @param offset the index of the packet's first byte
     * @param output receives one packet per track the packet carries
     */
    public void split(byte[] data, int offset, Consumer<Packet> output) {
        var base = channelBase(profile, data, offset);
        for (var track = 0; track < channels.length; track++) {
            var channel = channels[track] - base;
            if (channel < 0 || channel >= profile.channels()) {
                continue;
            }
            var half = halves[track];
            var format = DifAudio.format(data, offset, profile, channel, half);
            int count;
            if (format != null && format.twelveBit() == twelveBit && format.sampleRate() == rates[track]) {
                count = format.samples();
            } else {
                count = DifAudio.samplesPerFrame(profile, rates[track], frames[track]);
            }
            count = Math.min(count, DifAudio.capacity(profile, twelveBit));
            record(track, frames[track], count);
            if (frames[track] < skipBefore[track]) {
                positions[track] += count;
                frames[track]++;
                continue;
            }
            if (samples.length < 2 * count) {
                samples = new short[2 * DifAudio.capacity(profile, false)];
            }
            var pair = twelveBit ? 2 * channel + half : channel;
            DifAudio.read(data, offset, profile, pair, twelveBit, count, samples, 0);
            var bytes = new byte[4 * count];
            for (var i = 0; i < 2 * count; i++) {
                bytes[2 * i] = (byte) samples[i];
                bytes[2 * i + 1] = (byte) (samples[i] >> 8);
            }
            var pts = positions[track];
            output.accept(new Packet(tracks.get(track).id(), bytes, pts, pts, count, true));
            positions[track] += count;
            frames[track]++;
        }
    }
}
