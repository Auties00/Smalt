package com.github.auties00.smalt.container.ogg;

import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.AudioStreamDecoder;
import com.github.auties00.smalt.audio.AudioStreamDecoderOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the links of a chained Opus and Vorbis file after a seek into each of them, pre-rolled as
 * the stream decoder measures, and compares the samples from the target on with a decode of the
 * same track from its start: the two must be identical sample for sample.
 */
@DisplayName("chained Ogg decoding after a seek")
class OggChainedDecodeTest {
    static final String SOURCE = "aevalsrc=0.3*sin(2*PI*(200+150*t)*t):s=48000:d=";

    static byte[] chain() {
        var parts = List.of(
                Fixtures.ffmpeg("opus", List.of("-f", "lavfi", "-i", SOURCE + "1.5"),
                        List.of("-c:a", "libopus", "-b:a", "48k", "-serial_offset", "10")),
                Fixtures.ffmpeg("ogg", List.of("-f", "lavfi", "-i", SOURCE + "1.25"),
                        List.of("-c:a", "libvorbis", "-serial_offset", "20")),
                Fixtures.ffmpeg("opus", List.of("-f", "lavfi", "-i", SOURCE + "1.0"),
                        List.of("-c:a", "libopus", "-b:a", "32k", "-serial_offset", "30")));
        var output = new ByteArrayOutputStream();
        for (var part : parts) {
            output.writeBytes(Fixtures.read(part));
        }
        return output.toByteArray();
    }

    /** Returns the packets of one track read after a seek to {@code micros}, or from the start when it is {@code null}. */
    static List<Packet> packets(byte[] file, int trackId, Long micros) {
        try (var demuxer = new OggDemuxer(MediaSource.of(file))) {
            if (micros != null) {
                demuxer.seek(micros);
            }
            var packets = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                if (packet.trackId() == trackId) {
                    packets.add(packet);
                }
            }
            return packets;
        }
    }

    /** Returns the time, in samples of the track, of the first sample a fresh decoder outputs for the packets. */
    static long firstOutput(AudioTrack track, List<Packet> packets) {
        if (track.codec() == Codec.VORBIS) {
            // the first Vorbis packet only primes the decoder, and its own timestamp is not exact after a seek
            return packets.get(1).pts();
        }
        var head = track.codecConfig().orElseThrow();
        return packets.getFirst().pts() + ((head[10] & 0xFF) | (head[11] & 0xFF) << 8);
    }

    @Test
    @DisplayName("returns after a seek into every link the samples a decode from the start returns from the target on")
    void seekDecodesExactly() {
        var file = chain();
        try (var demuxer = new OggDemuxer(MediaSource.of(file))) {
            assertEquals(3, demuxer.links().size());
            for (var link : demuxer.links()) {
                var track = (AudioTrack) demuxer.tracks().get(link.trackIds().getFirst());
                long preroll;
                try (var measuring = new OggDemuxer(MediaSource.of(file))) {
                    preroll = AudioStreamDecoder.measure(measuring, track, AudioStreamDecoderOptions.defaults()).prerollMicros();
                }
                var linear = packets(file, track.id(), null);
                float[] reference;
                try (var decoder = AudioDecoders.create(track)) {
                    reference = AudioPipeline.decode(track, linear, decoder).interleaved();
                }
                var referenceStart = firstOutput(track, linear);
                for (var offset : new long[]{0, 21, 250_000, 600_000, link.durationMicros() - 30_000}) {
                    var micros = link.startMicros() + offset;
                    var sought = packets(file, track.id(), micros - preroll);
                    float[] samples;
                    try (var decoder = AudioDecoders.create(track)) {
                        samples = AudioPipeline.decode(track, sought, decoder).interleaved();
                    }
                    var target = Rational.MICROSECONDS.rescale(micros, track.timeBase());
                    var from = (int) (target - firstOutput(track, sought));
                    var referenceFrom = (int) (target - referenceStart);
                    assertTrue(from >= 0 && referenceFrom >= 0, "the seek lands before " + micros);
                    var compared = Arrays.copyOfRange(samples, from, samples.length);
                    var expected = Arrays.copyOfRange(reference, referenceFrom, referenceFrom + compared.length);
                    assertEquals(reference.length - referenceFrom, compared.length, "samples after " + micros);
                    assertArrayEquals(expected, compared, track.codec() + " link at " + link.startMicros() + ", target "
                            + micros);
                }
            }
        }
    }
}
