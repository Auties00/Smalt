package com.github.auties00.smalt.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.mp4.Mp4Remuxer;
import com.github.auties00.smalt.container.mp4.RemuxOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.TagEditor;
import com.github.auties00.smalt.metadata.TagReadOptions;
import com.github.auties00.smalt.testkit.fuzz.Fuzz;
import com.github.auties00.smalt.track.Packet;

import java.util.List;

/**
 * Fuzz target of the MP4 remuxer ({@link Mp4Remuxer}), which reads any container Smalt demuxes and writes its
 * tracks to a fast-start MP4 file. A remuxed file must be read back by Smalt's demuxer with the tracks and the
 * packet count the remuxer reports; with the input's tags carried over, its tags must read back and its chapters
 * add their chapter track.
 */
class RemuxFuzzTest {
    @FuzzTest
    void mp4(byte[] data) {
        var run = Fuzz.start("remux");
        var options = RemuxOptions.builder()
                .limits(Fuzz.LIMITS)
                .allAudioTracks()
                .allSubtitleTracks()
                .noTags()
                .build();
        var sink = MediaSink.memory();
        run.value("remux", () -> Mp4Remuxer.remux(MediaSource.of(data), sink, options)).ifPresent(result -> {
            var output = sink.toByteArray();
            if (output.length != result.outputSize()) {
                throw new AssertionError("wrote " + output.length + " bytes, reported " + result.outputSize());
            }
            // the remuxer copies every packet of a track while the MP4 demuxer counts samples against the frame
            // limit, so the output is read back with a limit that holds every packet
            var limits = Fuzz.LIMITS.toBuilder().maxFrames((int) Math.min(Integer.MAX_VALUE, result.packets() + 1))
                    .build();
            try (var demuxer = Containers.openDemuxer(MediaSource.of(output), DemuxerOptions.builder()
                    .limits(limits)
                    .build())) {
                if (demuxer.tracks().size() != result.tracks().size()) {
                    throw new AssertionError("the remuxed file holds " + demuxer.tracks() + ", reported "
                            + result.tracks());
                }
                var packets = 0L;
                while (packets <= result.packets() && demuxer.read() != null) {
                    packets++;
                }
                // an MP4 input's samples are copied with their edit list, which trims the packets a demuxer
                // presents, so the output must present the input's packets of the copied tracks
                var expected = result.inputFormat() == ContainerFormat.MP4 && packets < result.packets()
                        ? presented(data, limits, result.sourceTrackIds()) : result.packets();
                if (packets != expected) {
                    throw new AssertionError("the remuxed file holds " + packets + " packets, expected " + expected
                            + " (reported " + result.packets() + ")");
                }
            } catch (MediaException exception) {
                throw new AssertionError("the remuxed file does not demux", exception);
            }
            run.output();
        });
    }

    /** {@return the number of packets a demuxer presents for the given tracks of an input} */
    private static long presented(byte[] data, MediaLimits limits, List<Integer> trackIds) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(data), DemuxerOptions.builder()
                .limits(limits)
                .build())) {
            var count = 0L;
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                if (trackIds.contains(packet.trackId())) {
                    count++;
                }
            }
            return count;
        } catch (MediaException exception) {
            throw new AssertionError("the remuxed input no longer demuxes", exception);
        }
    }

    @FuzzTest
    void tags(byte[] data) {
        var run = Fuzz.start("remux tags");
        var options = RemuxOptions.builder()
                .limits(Fuzz.LIMITS)
                .allAudioTracks()
                .build();
        var sink = MediaSink.memory();
        run.value("remux", () -> Mp4Remuxer.remux(MediaSource.of(data), sink, options)).ifPresent(result -> {
            var output = sink.toByteArray();
            MediaTags tags;
            try {
                tags = TagEditor.read(MediaSource.of(output), TagReadOptions.builder().limits(Fuzz.LIMITS).build());
            } catch (MediaException exception) {
                throw new AssertionError("the tags of the remuxed file do not read back", exception);
            }
            var expected = result.tracks().size() + (tags.chapters().isEmpty() ? 0 : 1);
            try (var demuxer = Containers.openDemuxer(MediaSource.of(output), DemuxerOptions.builder()
                    .limits(Fuzz.LIMITS.toBuilder().maxFrames(Integer.MAX_VALUE).build())
                    .build())) {
                if (demuxer.tracks().size() != expected) {
                    throw new AssertionError("the remuxed file holds " + demuxer.tracks() + ", reported "
                            + result.tracks() + " and " + tags.chapters().size() + " chapters");
                }
            } catch (MediaException exception) {
                throw new AssertionError("the remuxed file does not demux", exception);
            }
            run.output();
        });
    }
}
