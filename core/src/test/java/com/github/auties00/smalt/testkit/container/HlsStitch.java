package com.github.auties00.smalt.testkit.container;

import java.util.ArrayList;
import java.util.List;

/**
 * Joins HLS media playlists into one, as a server that splices encodings does: the segments of each playlist in turn,
 * an {@code EXT-X-DISCONTINUITY} before every playlist but the first, each playlist's {@code EXT-X-MAP} and keys kept
 * in front of its segments.
 */
public final class HlsStitch {
    private HlsStitch() {
        throw new AssertionError();
    }

    /**
     * Joins media playlists.
     *
     * @param playlists the playlists, in order
     * @param discontinuities whether a discontinuity precedes every playlist but the first; without, only the
     *                        initialization section changes, so the playlists must continue each other's timestamps
     * @param tags extra lines written before the segments of each playlist (an {@code EXT-X-PROGRAM-DATE-TIME}),
     *             one entry per playlist, or empty for none
     * @return the joined playlist, ended
     */
    public static String join(List<String> playlists, boolean discontinuities, List<String> tags) {
        var target = 1L;
        var version = 3L;
        for (var playlist : playlists) {
            for (var line : lines(playlist)) {
                if (line.startsWith("#EXT-X-TARGETDURATION:")) {
                    target = Math.max(target, Long.parseLong(line.substring(22).strip()));
                } else if (line.startsWith("#EXT-X-VERSION:")) {
                    version = Math.max(version, Long.parseLong(line.substring(15).strip()));
                }
            }
        }
        var text = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:").append(version).append("\n#EXT-X-TARGETDURATION:")
                .append(target).append("\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n");
        for (var index = 0; index < playlists.size(); index++) {
            if (index > 0 && discontinuities) {
                text.append("#EXT-X-DISCONTINUITY\n");
            }
            if (!tags.isEmpty() && !tags.get(index).isEmpty()) {
                text.append(tags.get(index)).append('\n');
            }
            for (var line : body(playlists.get(index))) {
                text.append(line).append('\n');
            }
        }
        return text.append("#EXT-X-ENDLIST\n").toString();
    }

    /**
     * Returns the lines of a media playlist that describe its segments: initialization sections, keys, byte ranges,
     * dates, durations, gaps and URIs.
     *
     * @param playlist the playlist
     * @return the lines, in order
     */
    public static List<String> body(String playlist) {
        var body = new ArrayList<String>();
        for (var line : lines(playlist)) {
            if (line.startsWith("#EXT-X-MAP:") || line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXTINF:")
                    || line.startsWith("#EXT-X-BYTERANGE:") || line.startsWith("#EXT-X-PROGRAM-DATE-TIME:")
                    || line.equals("#EXT-X-GAP") || !line.isEmpty() && !line.startsWith("#")) {
                body.add(line);
            }
        }
        return body;
    }

    /**
     * {@return the sum of the {@code EXTINF} durations of a media playlist, in microseconds}
     *
     * @param playlist the playlist
     */
    public static long durationMicros(String playlist) {
        var total = 0L;
        for (var line : lines(playlist)) {
            if (line.startsWith("#EXTINF:")) {
                var value = line.substring(8);
                var comma = value.indexOf(',');
                total += Math.round(Double.parseDouble((comma >= 0 ? value.substring(0, comma) : value).strip())
                        * 1_000_000);
            }
        }
        return total;
    }

    private static List<String> lines(String playlist) {
        var lines = new ArrayList<String>();
        for (var line : playlist.replace("\r\n", "\n").split("\n")) {
            lines.add(line.strip());
        }
        return lines;
    }
}
