package com.github.auties00.smalt.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.mp4.Mp4Remuxer;
import com.github.auties00.smalt.container.mp4.RemuxOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.fuzz.Fuzz;

/**
 * Fuzz target of the MP4 remuxer ({@link Mp4Remuxer}), which reads any container Smalt demuxes and writes its
 * tracks to a fast-start MP4 file. A remuxed file must be read back by Smalt's demuxer with the tracks and the
 * packet count the remuxer reports.
 */
class RemuxFuzzTest {
    @FuzzTest
    void mp4(byte[] data) {
        var run = Fuzz.start("remux");
        var options = RemuxOptions.builder()
                .limits(Fuzz.LIMITS)
                .allAudioTracks()
                .allSubtitleTracks()
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
                if (packets != result.packets()) {
                    throw new AssertionError("the remuxed file holds " + packets + " packets, reported "
                            + result.packets());
                }
            } catch (MediaException exception) {
                throw new AssertionError("the remuxed file does not demux", exception);
            }
            run.output();
        });
    }
}
