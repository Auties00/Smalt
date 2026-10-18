package com.github.auties00.smalt.container.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.subtitle.TextFiles;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleEncoder;
import com.github.auties00.smalt.subtitle.Subtitles;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Demuxes a text subtitle file that is read whole: one track holding every cue in start order.
 *
 * <p>The file is decoded ({@link TextFiles}) and parsed at construction, and its cues sorted by
 * start time, which subtitle files do not guarantee; packets are then served from memory, so
 * seeking is exact and cheap. The track has a millisecond time base.
 */
abstract sealed class CueFileDemuxer implements Demuxer permits SubRipDemuxer, WebVttDemuxer {
    /**
     * The track.
     */
    private final DataTrack track;

    /**
     * The cues in start order.
     */
    private final List<SubtitleCue> cues;

    /**
     * The packets of the cues, encoded once.
     */
    private final Packet[] packets;

    /**
     * The index of the next packet.
     */
    private int next;

    /**
     * Constructs a demuxer from parsed cues.
     *
     * @param codec the track codec
     * @param config the track configuration, or {@code null}
     * @param cues the cues in start order, their text in the codec's markup
     */
    CueFileDemuxer(Codec codec, byte[] config, List<SubtitleCue> cues) {
        var duration = 0L;
        for (var cue : cues) {
            duration = Math.max(duration, cue.endMicros());
        }
        var base = SubtitleEncoder.track(codec, 1);
        this.track = base.toBuilder().codecConfig(config).duration(cues.isEmpty() ? 0 : (duration + 500) / 1000).build();
        this.cues = List.copyOf(cues);
        var encoder = new SubtitleEncoder(track);
        this.packets = new Packet[cues.size()];
        for (var index = 0; index < packets.length; index++) {
            packets[index] = encoder.encode(cues.get(index), Subtitles.markupOf(codec));
        }
    }

    /**
     * Reads and decodes a whole text file.
     *
     * @param source the input
     * @param limits the limits bounding the file size
     * @return the text, with line feeds only and without a byte order mark
     * @throws MediaException.LimitExceeded if the file is larger than the limits allow or than an
     * array can hold
     * @throws MediaException.Io if reading the source fails
     */
    static String readText(MediaSource source, MediaLimits limits) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        var size = source.size();
        if (size < 0) {
            throw new MediaException.Unsupported("subtitle file", "the subtitle file has no known size");
        }
        LimitChecks.checkAllocation(limits, size);
        if (size > Integer.MAX_VALUE - 16) {
            throw new MediaException.LimitExceeded("subtitle file of " + size + " bytes is too large");
        }
        var data = new byte[(int) size];
        source.readFully(0, data, 0, data.length);
        return TextFiles.decode(data);
    }

    /**
     * Returns the cues in start order.
     *
     * @return the cues, unmodifiable
     */
    public List<SubtitleCue> cues() {
        return cues;
    }

    @Override
    public List<Track> tracks() {
        return List.of(track);
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of();
    }

    @Override
    public long durationMicros() {
        return cues.isEmpty() ? 0 : track.timeBase().toMicros(track.duration());
    }

    @Override
    public Packet read() {
        return next < packets.length ? packets[next++] : null;
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

    /**
     * {@inheritDoc}
     *
     * <p>The next packet is the first cue that is still shown at the target, or the first cue
     * that starts after it; a negative target restarts from the first cue.
     */
    @Override
    public void seek(long micros) {
        var index = 0;
        while (index < cues.size() && cues.get(index).endMicros() <= micros && cues.get(index).startMicros() < micros) {
            index++;
        }
        next = index;
    }
}
