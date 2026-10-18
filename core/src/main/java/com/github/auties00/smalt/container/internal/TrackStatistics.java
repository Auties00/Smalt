package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.avi.AviDemuxer;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;

import java.util.function.IntToLongFunction;

/**
 * Gives the totals of a track that a demuxer knows from its index without reading the packets,
 * for the probe API.
 *
 * @param sampleCount the number of samples of a track, by track identifier, or {@code -1} when unknown
 * @param sampleBytes the total size in bytes of the samples of a track, by track identifier, or {@code -1}
 *                    when unknown
 */
public record TrackStatistics(IntToLongFunction sampleCount, IntToLongFunction sampleBytes) {
    /**
     * Returns the statistics a demuxer can give from its index.
     *
     * @param demuxer the open demuxer whose index is read
     * @return the statistics, or {@code null} when the demuxer keeps no index of sample sizes
     */
    public static TrackStatistics of(Demuxer demuxer) {
        return switch (demuxer) {
            case Mp4Demuxer mp4 -> new TrackStatistics(mp4::sampleCount, mp4::sampleBytes);
            case AviDemuxer avi -> new TrackStatistics(avi::sampleCount, avi::sampleBytes);
            default -> null;
        };
    }
}
