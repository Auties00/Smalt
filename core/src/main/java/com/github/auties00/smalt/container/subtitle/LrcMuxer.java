package com.github.auties00.smalt.container.subtitle;

import com.github.auties00.smalt.internal.subtitle.LrcText;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes an LRC ({@code .lrc}) lyrics file from one {@link Codec#WEBVTT} track.
 *
 * <p>Each line of a cue is one LRC line at the cue start, in hundredths of a second, its WebVTT timestamp
 * tags written as the word times of the enhanced format and its other markup dropped; a cue that ends
 * before the next one starts is followed by an empty line at its end, which clears it. Consecutive packets
 * with the same payload that follow each other without a gap become one cue.
 *
 * <p>Instances are not thread-safe.
 */
public final class LrcMuxer extends BufferedCueMuxer {
    /**
     * The ID tags to write, by name.
     */
    private final Map<String, String> tags = new LinkedHashMap<>();

    /**
     * Constructs a muxer writing to a sink from its current position.
     *
     * @param output the sink; not closed by the muxer
     */
    public LrcMuxer(MediaSink output) {
        super(Codec.WEBVTT, 1, output);
    }

    /**
     * Returns whether the muxer accepts tracks of a codec.
     *
     * @param codec the codec to look up
     * @return {@code true} for {@link Codec#WEBVTT} only
     */
    public static boolean supports(Codec codec) {
        return codec == Codec.WEBVTT;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}
     */
    @Override
    public boolean supportsTags() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The title, artist and album become the {@code ti}, {@code ar} and {@code al} ID tags at the top of the
     * file; the other tags, pictures and chapters are not written.
     *
     * @throws IllegalStateException {@inheritDoc}
     */
    @Override
    public void setTags(MediaTags tags) {
        Objects.requireNonNull(tags, "tags");
        if (started()) {
            throw new IllegalStateException("tags must be set before the first packet");
        }
        this.tags.clear();
        tags.get(StandardTag.TITLE).ifPresent(value -> this.tags.put("ti", value));
        tags.get(StandardTag.ARTIST).ifPresent(value -> this.tags.put("ar", value));
        tags.get(StandardTag.ALBUM).ifPresent(value -> this.tags.put("al", value));
    }

    @Override
    String name() {
        return "LRC";
    }

    @Override
    String format(List<Entry> entries) {
        return LrcText.write(tags, entries.isEmpty() ? List.of() : entries.getFirst().cues());
    }
}
