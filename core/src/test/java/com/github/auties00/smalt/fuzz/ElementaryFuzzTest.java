package com.github.auties00.smalt.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.raw.PcmDemuxer;
import com.github.auties00.smalt.container.raw.PcmDemuxerOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.fuzz.Fuzz;
import com.github.auties00.smalt.testkit.fuzz.FuzzMedia;
import com.github.auties00.smalt.track.Codec;

import java.util.Arrays;

/**
 * Fuzz targets of the raw PCM demuxer, whose first three input bytes choose the sample format, the channel count
 * and the sample rate, and of the uncompressed video decoder, whose input is in the
 * {@link com.github.auties00.smalt.testkit.fuzz.FuzzPackets} format (the configuration a raw video format).
 */
class ElementaryFuzzTest {
    /** The PCM codecs, chosen by the first byte modulo their count. */
    private static final Codec[] CODECS = Arrays.stream(Codec.values()).filter(Codec::isPcm).toArray(Codec[]::new);

    @FuzzTest
    void pcmDemuxer(byte[] data) {
        var run = Fuzz.start("PCM demuxer");
        if (data.length < 3) {
            run.skip("no format bytes");
            return;
        }
        var options = PcmDemuxerOptions.builder()
                .codec(CODECS[(data[0] & 0xFF) % CODECS.length])
                .layout(ChannelLayout.defaultFor(1 + (data[1] & 7)))
                .sampleRate(8000 + 100 * (data[2] & 0xFF))
                .packetFrames(1 + (data[2] & 0x3F) * 16)
                .limits(Fuzz.LIMITS)
                .build();
        var body = Arrays.copyOfRange(data, 3, data.length);
        run.step("demux", () -> {
            try (var demuxer = new PcmDemuxer(MediaSource.of(body), options)) {
                var count = 0;
                for (var packet = demuxer.read(); packet != null && count < Fuzz.MAX_PACKETS; packet = demuxer.read()) {
                    packet.size();
                    count++;
                    run.output();
                }
                demuxer.seek(demuxer.durationMicros() / 2);
                demuxer.read();
            }
        });
    }

    @FuzzTest
    void rawVideo(byte[] data) {
        FuzzMedia.video(Codec.RAW_VIDEO, data);
    }
}
