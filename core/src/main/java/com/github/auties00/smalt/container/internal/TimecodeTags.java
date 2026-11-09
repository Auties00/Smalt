package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.container.Timecode;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.List;
import java.util.Map;

/**
 * Reads the timecode a container stores as a {@code timecode} tag rather than as a timecode track.
 */
public final class TimecodeTags {
    private TimecodeTags() {
        throw new AssertionError();
    }

    /**
     * Returns the value of a tag looked up ignoring case.
     *
     * @param tags the tags to search
     * @param name the tag name
     * @return the stripped value, or {@code null} when the tags hold none
     */
    public static String lookup(Map<String, String> tags, String name) {
        for (var entry : tags.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue().strip();
            }
        }
        return null;
    }

    /**
     * Returns the first video track of a list.
     *
     * @param tracks the tracks to search, in order
     * @return the track, or {@code null} when there is no video track
     */
    public static VideoTrack firstVideo(List<Track> tracks) {
        for (var track : tracks) {
            if (track instanceof VideoTrack video) {
                return video;
            }
        }
        return null;
    }

    /**
     * Parses a timecode tag, counted at the nominal rate of a video track's frame rate rounded up, or at 30
     * frames per second for a drop-frame address when there is no such rate.
     *
     * @param text the tag value, or {@code null}
     * @param video the video track whose frame rate the address counts at, or {@code null}
     * @return the timecode, or {@code null} when the text is absent or no valid address at that rate
     */
    public static Timecode parse(String text, VideoTrack video) {
        if (text == null) {
            return null;
        }
        var nominal = video == null ? 0 : video.frameRate().map(TimecodeTags::nominalRate).orElse(0);
        try {
            if (nominal <= 0) {
                var timecode = Timecode.parse(text, 30);
                return timecode.dropFrame() ? timecode : null;
            }
            return Timecode.parse(text, nominal);
        } catch (IllegalArgumentException ignored) {
            // a malformed timecode tag stays a tag
            return null;
        }
    }

    /**
     * Returns the whole number of frames per second a timecode counts at a frame rate.
     *
     * @param rate the frame rate
     * @return the rate rounded up, or 0 when it is outside 1 to 255
     */
    private static int nominalRate(Rational rate) {
        if (rate.numerator() <= 0 || rate.denominator() <= 0) {
            return 0;
        }
        var nominal = (rate.numerator() + rate.denominator() - 1) / rate.denominator();
        return nominal >= 1 && nominal <= 255 ? (int) nominal : 0;
    }
}
