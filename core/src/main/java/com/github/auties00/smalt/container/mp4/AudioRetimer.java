package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.codec.Ac3Configs;
import com.github.auties00.smalt.internal.codec.AmrConfigs;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;

/**
 * Converts the timestamps of an audio track whose stored timestamps were rounded to a time base
 * other than its sampling rate (Matroska block timestamps, a millisecond by default; the 90 kHz
 * clock of a transport stream; the chunk timing of AVI) into exact sample counts.
 *
 * <p>Each packet's duration is read from its bitstream when the codec has self-describing frames
 * (AAC frame length, Opus TOC, MPEG audio header, FLAC block size, ALAC frame length, the blocks
 * of AC-3 and E-AC-3 sync frames, the AMR frames of a storage-format packet, the frames of PCM); the
 * timestamps then advance by exactly those durations, and a stored timestamp is honored only
 * when it departs from the running count by more than {@value #TOLERANCE_MILLIS} ms (a gap in
 * the stream). Packets whose stored timestamp would move backwards keep the running count, so the
 * output never overlaps. A stored duration clearly shorter than the frame (a Matroska
 * {@code DiscardPadding} trimming the end of the stream) replaces the frame duration.
 */
final class AudioRetimer {
    /**
     * The largest disagreement between a stored timestamp and the running count that is treated
     * as rounding, in milliseconds.
     */
    private static final int TOLERANCE_MILLIS = 2;

    /**
     * The track being retimed.
     */
    private final AudioTrack track;

    /**
     * The target time base, one tick per sample.
     */
    private final Rational target;

    /**
     * The sampling rate of the target time base.
     */
    private final int rate;

    /**
     * The AAC configuration, or {@code null} for other codecs.
     */
    private final AudioConfigs.AacConfig aac;

    /**
     * The ALAC frame length, or {@code 0}.
     */
    private final int alacFrameLength;

    /**
     * The size of one frame of a PCM track, or {@code 0} for other codecs.
     */
    private final int pcmFrameBytes;

    /**
     * The disagreement treated as rounding, in samples.
     */
    private final long tolerance;

    /**
     * The timestamp the next packet is expected at, or {@link Long#MIN_VALUE} before the first.
     */
    private long next = Long.MIN_VALUE;

    /**
     * Constructs a retimer.
     *
     * @param track the source track
     * @param rate the sampling rate of the output time base; positive
     */
    AudioRetimer(AudioTrack track, int rate) {
        this.track = track;
        this.rate = rate;
        this.target = Rational.timeBaseOf(rate);
        this.tolerance = (long) rate * TOLERANCE_MILLIS / 1000 + 1;
        AudioConfigs.AacConfig parsedAac = null;
        var alac = 0;
        var config = track.codecConfig().orElse(null);
        try {
            switch (track.codec()) {
                case AAC -> parsedAac = config == null ? null : AudioConfigs.parseAac(config);
                case ALAC -> alac = config == null ? 0 : AudioConfigs.parseAlac(AudioConfigs.alacConfig(config)).frameLength();
                default -> {
                }
            }
        } catch (MediaException.Malformed exception) {
            parsedAac = null;
            alac = 0;
        }
        this.aac = parsedAac;
        this.alacFrameLength = Math.max(alac, 0);
        this.pcmFrameBytes = track.codec().isPcm() && track.layout().isPresent()
                ? track.layout().get().channels() * (track.codec().pcmBitsPerSample().orElseThrow() / 8) : 0;
    }

    /**
     * Returns the time base of the retimed packets.
     *
     * @return one tick per sample
     */
    Rational timeBase() {
        return target;
    }

    /**
     * Retimes one packet.
     *
     * @param packet the source packet, in decoding order
     * @param trackId the identifier the retimed packet carries
     * @return the packet with timestamps and duration in samples
     */
    Packet retime(Packet packet, int trackId) {
        var samples = samplesIn(packet.data());
        long timestamp;
        if (packet.pts() == Packet.NO_TIMESTAMP) {
            timestamp = next == Long.MIN_VALUE ? 0 : next;
        } else {
            timestamp = track.timeBase().rescale(packet.pts(), target);
            if (next != Long.MIN_VALUE && (Math.abs(timestamp - next) <= tolerance || timestamp < next)) {
                timestamp = next;
            }
        }
        var declared = packet.duration() > 0 ? track.timeBase().rescale(packet.duration(), target) : 0;
        var duration = samples <= 0 ? declared : declared > 0 && declared < samples - tolerance ? declared : samples;
        next = timestamp + duration;
        var sideData = packet.sideData();
        if (sideData.discardStart() != 0 || sideData.discardEnd() != 0) {
            sideData = sideData.toBuilder().discard(track.timeBase().rescale(sideData.discardStart(), target),
                    track.timeBase().rescale(sideData.discardEnd(), target)).build();
        }
        return new Packet(trackId, packet.data(), timestamp, timestamp, duration, packet.flags(), sideData);
    }

    /**
     * Returns the number of samples a packet decodes to, read from its bitstream.
     *
     * @param data the packet bytes
     * @return the sample count at the target rate, or {@code 0} when unknown
     */
    private long samplesIn(byte[] data) {
        return switch (track.codec()) {
            case AAC -> aac == null || aac.sampleRate() <= 0 ? 0 : (long) aac.frameLength() * rate / aac.sampleRate();
            case OPUS -> (long) AudioConfigs.opusPacketSamples(data) * rate / 48000;
            case MP3, MP2 -> {
                var header = AudioConfigs.parseMpegHeader(data, 0, data.length);
                yield header == null || header.sampleRate() <= 0 ? 0 : (long) header.samplesPerFrame() * rate / header.sampleRate();
            }
            case FLAC -> AudioConfigs.flacFrameSamples(data);
            case ALAC -> alacFrameLength;
            case AC3, EAC3 -> {
                var stream = Ac3Configs.describe(data, 0, data.length);
                yield stream == null || stream.sampleRate() <= 0 ? 0 : (long) stream.samples() * rate / stream.sampleRate();
            }
            case AMR_NB, AMR_WB -> {
                var wideband = track.codec() == Codec.AMR_WB;
                var frames = 0L;
                for (var position = 0; position < data.length; frames++) {
                    position += 1 + AmrConfigs.payloadSize(wideband, (data[position] >> 3) & 0x0F);
                }
                yield frames * (wideband ? 320 : 160) * rate / (wideband ? 16000 : 8000);
            }
            default -> pcmFrameBytes > 0 && track.sampleRate() > 0
                    ? (long) (data.length / pcmFrameBytes) * rate / track.sampleRate() : 0;
        };
    }
}
