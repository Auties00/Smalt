package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Track;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Turns the closed captions carried in a video track into the packets of a {@link Codec#CEA_708} track, in
 * presentation order.
 *
 * <p>Video packets arrive in decoding order, while caption data belongs to the picture it rides with and must be
 * read in the order pictures are shown. The extractor reads each packet's caption data
 * ({@link ClosedCaptions#ccData}) and holds the caption packets until no picture still to come can be shown
 * before them: until a video packet whose decoding timestamp reaches their presentation timestamp arrives, or,
 * for packets without timestamps, until 16 later packets (the deepest reordering H.264 and HEVC allow) have
 * arrived. A caption packet has the presentation timestamp and duration of its picture, in the video track's
 * time base, and is a key frame; pictures without captions give no packet.
 *
 * <p>An instance is not thread-safe.
 */
public final class CaptionExtractor {
    /**
     * The most packets held without timestamps.
     */
    private static final int REORDER_DEPTH = 16;

    private final Track video;
    private final DataTrack track;
    private final PriorityQueue<Packet> held = new PriorityQueue<>(Comparator.comparingLong(Packet::pts));
    private long sequence;

    /**
     * Constructs an extractor for a video track.
     *
     * @param video the video track
     * @throws IllegalArgumentException if {@code video} is not a video track
     */
    public CaptionExtractor(Track video) {
        this.video = Objects.requireNonNull(video, "video");
        this.track = ClosedCaptions.track(video);
    }

    /**
     * Returns the caption track the extracted packets belong to.
     *
     * @return the {@link Codec#CEA_708} track, with the video track's identifier and time base
     */
    public DataTrack track() {
        return track;
    }

    /**
     * Reads the captions of one video packet.
     *
     * @param packet the next packet of the video track, in decoding order
     * @return the caption packets now known to be in presentation order, often empty
     */
    public List<Packet> extract(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        var data = ClosedCaptions.ccData(video, packet);
        var pts = packet.pts() != Packet.NO_TIMESTAMP ? packet.pts() : packet.dts() != Packet.NO_TIMESTAMP
                ? packet.dts() : sequence;
        sequence++;
        if (data.length > 0) {
            held.add(new Packet(track.id(), data, pts, pts, packet.duration(), Set.of(PacketFlag.KEY),
                    PacketSideData.NONE));
        }
        var result = new ArrayList<Packet>();
        var dts = packet.dts();
        while (!held.isEmpty() && (dts != Packet.NO_TIMESTAMP ? held.peek().pts() <= dts : held.size() > REORDER_DEPTH)) {
            result.add(held.poll());
        }
        return result;
    }

    /**
     * Returns the caption packets still held, after the last video packet.
     *
     * @return the packets, in presentation order
     */
    public List<Packet> flush() {
        var result = new ArrayList<Packet>();
        while (!held.isEmpty()) {
            result.add(held.poll());
        }
        sequence = 0;
        return result;
    }
}
