package com.github.auties00.smalt.container;

import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.ToLongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Compares a demuxer's output with ffprobe's listing of the same file.
 */
public final class DemuxAssertions {
    private DemuxAssertions() {
        throw new AssertionError();
    }

    /** Returns ffprobe's codec_name for one of our codecs. */
    public static String ffprobeName(Codec codec) {
        return switch (codec) {
            case AMR_NB -> "amr_nb";
            case AMR_WB -> "amr_wb";
            default -> codec.displayName();
        };
    }

    /**
     * Re-expresses the audio tracks whose time base differs from the ffprobe stream's (Matroska
     * audio, which uses the sample period where ffmpeg keeps the segment time base) in the stream's
     * time base, so that packets compare tick for tick.
     */
    public static ContainerProbe.Demuxed inProbeTimeBase(ContainerProbe.Demuxed ours, ContainerProbe.Probe probe) {
        var tracks = new ArrayList<Track>();
        var bases = new HashMap<Integer, Rational[]>();
        for (var index = 0; index < ours.tracks().size(); index++) {
            var track = ours.tracks().get(index);
            var stream = probe.streams().get(index);
            var target = new Rational(stream.timeBaseNumerator(), stream.timeBaseDenominator()).reduce();
            if (track instanceof AudioTrack audio && !audio.timeBase().reduce().equals(target)) {
                bases.put(audio.id(), new Rational[]{audio.timeBase(), target});
                var duration = audio.duration() < 0 ? audio.duration() : audio.timeBase().rescale(audio.duration(), target);
                tracks.add(audio.toBuilder().timeBase(target).duration(duration).build());
            } else {
                tracks.add(track);
            }
        }
        var packets = new LinkedHashMap<Integer, List<Packet>>();
        for (var track : tracks) {
            packets.put(track.id(), new ArrayList<>());
        }
        var order = new ArrayList<Packet>();
        for (var packet : ours.order()) {
            var base = bases.get(packet.trackId());
            var converted = base == null ? packet : new Packet(packet.trackId(), packet.data(),
                    packet.pts() == Packet.NO_TIMESTAMP ? Packet.NO_TIMESTAMP : base[0].rescale(packet.pts(), base[1]),
                    packet.dts() == Packet.NO_TIMESTAMP ? Packet.NO_TIMESTAMP : base[0].rescale(packet.dts(), base[1]),
                    base[0].rescale(packet.duration(), base[1]), packet.keyframe());
            order.add(converted);
            packets.computeIfAbsent(converted.trackId(), key -> new ArrayList<>()).add(converted);
        }
        return new ContainerProbe.Demuxed(tracks, packets, order, ours.metadata(), ours.durationMicros());
    }

    /**
     * Asserts that every track matches the ffprobe stream at the same index and every packet
     * matches the ffprobe packet at the same position within its stream.
     *
     * @param ptsOffset the expected difference {@code ours - ffprobe} of the timestamps of a track,
     *                  in its time base; ffprobe's own timestamp may be missing (N/A)
     * @param compareDts whether decoding timestamps are compared
     */
    public static void assertPacketsMatch(ContainerProbe.Demuxed ours, ContainerProbe.Probe probe,
                                          ToLongFunction<Track> ptsOffset, boolean compareDts) {
        assertPacketsMatch(ours, probe, ptsOffset, compareDts, 0);
    }

    /**
     * Asserts like {@link #assertPacketsMatch(ContainerProbe.Demuxed, ContainerProbe.Probe,
     * ToLongFunction, boolean)}, with a presentation timestamp tolerance.
     *
     * <p>ffmpeg drops the samples an edit list leaves out of the presentation (before the key
     * frame that precedes its start, and after its end); we keep them, so ffprobe's packets must
     * appear as one contiguous run of ours, with our extra packets outside the presentation.
     *
     * @param ptsTolerance the largest accepted difference of a presentation timestamp, in ticks
     */
    public static void assertPacketsMatch(ContainerProbe.Demuxed ours, ContainerProbe.Probe probe,
                                          ToLongFunction<Track> ptsOffset, boolean compareDts, long ptsTolerance) {
        assertEquals(probe.streams().size(), ours.tracks().size(), "track count");
        for (var index = 0; index < ours.tracks().size(); index++) {
            var track = ours.tracks().get(index);
            var stream = probe.streams().get(index);
            var label = "track " + index + " (" + track.codec() + ")";
            // ffmpeg names every MPEG-1/2 audio object type in an ISO file mp3; we read the layer
            var mp2AsMp3 = track.codec() == Codec.MP2 && "mp3".equals(stream.codecName());
            // ffmpeg leaves the codec of a tx3g sample entry under a text handler unidentified
            var unnamedText = track.codec().isSubtitle() && "unknown".equals(stream.codecName());
            if (track.codec() != Codec.DATA && track.codec() != Codec.UNKNOWN_AUDIO && track.codec() != Codec.UNKNOWN_VIDEO
                    && !mp2AsMp3 && !unnamedText) {
                assertEquals(stream.codecName(), ffprobeName(track.codec()), label + " codec");
            }
            var expected = probe.packetsOf(stream.index());
            var all = ours.packetsOf(track);
            var offset = all.isEmpty() ? 0 : ptsOffset.applyAsLong(track);
            assertTrue(all.size() >= expected.size(), label + " packet count " + all.size() + " < " + expected.size());
            var first = 0;
            if (all.size() > expected.size() && !expected.isEmpty()) {
                var head = expected.getFirst();
                while (first < all.size() - expected.size()
                        && !(ContainerProbe.crc32(all.get(first).data()).equals(head.hash())
                        && (head.pts() == Packet.NO_TIMESTAMP || Math.abs(all.get(first).pts() - offset - head.pts()) <= ptsTolerance))) {
                    first++;
                }
            }
            for (var extra = 0; extra < first; extra++) {
                assertTrue(all.get(extra).pts() == Packet.NO_TIMESTAMP || expected.getFirst().pts() == Packet.NO_TIMESTAMP
                                || all.get(extra).pts() - offset < Math.max(0, expected.getFirst().pts()) + 1
                                || all.get(extra).pts() - offset < 0,
                        label + " leading extra packet " + extra + " lies inside the presentation");
            }
            for (var extra = first + expected.size(); extra < all.size(); extra++) {
                assertTrue(track.duration() >= 0 && all.get(extra).pts() - offset >= track.duration() - ptsTolerance,
                        label + " trailing extra packet " + extra + " lies inside the presentation");
            }
            var actual = all.subList(first, first + expected.size());
            for (var position = 0; position < expected.size(); position++) {
                var want = expected.get(position);
                var got = actual.get(position);
                var where = label + " packet " + position;
                assertEquals(want.size(), got.size(), where + " size");
                assertEquals(want.hash(), ContainerProbe.crc32(got.data()), where + " data hash");
                if (track.codec() != Codec.DATA) {
                    assertEquals(want.key(), got.keyframe(), where + " keyframe");
                }
                // a lace frame without a declared duration has no timestamp of its own; ffmpeg derives one from the codec
                if (want.pts() != Packet.NO_TIMESTAMP && got.pts() != Packet.NO_TIMESTAMP) {
                    assertTrue(Math.abs(want.pts() - (got.pts() - offset)) <= ptsTolerance,
                            where + " pts: expected " + want.pts() + " but was " + (got.pts() - offset));
                }
                if (compareDts && want.dts() != Packet.NO_TIMESTAMP) {
                    assertEquals(want.dts(), got.dts() - offset, where + " dts");
                }
                if (ptsTolerance == 0 && want.duration() != Packet.NO_TIMESTAMP && want.duration() > 0 && got.duration() > 0
                        && want.duration() != got.duration() && position + 1 < expected.size()) {
                    // ffprobe reports the parser's frame duration; ours must be the container's
                    var next = actual.get(position + 1);
                    if (next.dts() != Packet.NO_TIMESTAMP && got.dts() != Packet.NO_TIMESTAMP) {
                        assertEquals(next.dts() - got.dts(), got.duration(), where + " duration");
                    }
                }
            }
        }
    }

    /** Asserts the basic parameters of each track against ffprobe's stream fields. */
    public static void assertStreamsMatch(List<Track> tracks, ContainerProbe.Probe probe) {
        for (var index = 0; index < tracks.size(); index++) {
            var track = tracks.get(index);
            var stream = probe.streams().get(index);
            switch (track) {
                case VideoTrack video -> {
                    if (video.codec() != Codec.UNKNOWN_VIDEO && stream.get("width") != null) {
                        assertEquals(Integer.parseInt(stream.get("width")), video.width(), "width of track " + index);
                        assertEquals(Integer.parseInt(stream.get("height")), video.height(), "height of track " + index);
                    }
                }
                case AudioTrack audio -> {
                    if (stream.get("sample_rate") != null && audio.sampleRate() > 0) {
                        assertEquals(Integer.parseInt(stream.get("sample_rate")), audio.sampleRate(), "sample rate of track " + index);
                    }
                    if (stream.get("channels") != null && audio.layout().isPresent()) {
                        assertEquals(Integer.parseInt(stream.get("channels")), audio.layout().orElseThrow().channels(),
                                "channels of track " + index);
                    }
                }
                default -> {
                }
            }
        }
    }

    /** Asserts that packets of each track are in non-decreasing decoding order when known. */
    public static void assertMonotonicDts(ContainerProbe.Demuxed ours) {
        for (var track : ours.tracks()) {
            var previous = Long.MIN_VALUE;
            for (var packet : ours.packetsOf(track)) {
                if (packet.dts() == Packet.NO_TIMESTAMP) {
                    continue;
                }
                if (packet.dts() < previous) {
                    fail("dts goes backwards in track " + track.id() + ": " + previous + " -> " + packet.dts());
                }
                previous = packet.dts();
            }
        }
        assertTrue(true);
    }
}
