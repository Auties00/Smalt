package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.caf.CafMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads ISO/IEC 14496-26 HE-AAC conformance streams, as published (MP4 whose sample entries
 * declare the core rate) and remuxed by ffmpeg into ADTS and Matroska, through
 * {@link Containers} and the registered AAC decoder, and as Smalt's {@link CafMuxer} writes them
 * (with its {@code ldsc} chunk of layers, and without it, when the first packet decides), and checks that the track
 * declares the
 * output rate and layout of spectral band replication and parametric stereo before any packet is
 * decoded, that every buffer has that rate, and that the decoded audio meets the 16-bit
 * conformance criterion against ffmpeg's decode of the original stream. The streams cover
 * implicit signaling, backward-compatible explicit signaling, a mono core with parametric
 * stereo, and five channels.
 */
class HeAacContainersTest {
    private record Decoded(AudioTrack track, float[][] planes) {
    }

    private static Decoded read(byte[] file) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            var chunks = new ArrayList<float[][]>();
            try (var decoder = AudioDecoders.create(track)) {
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    decoder.decode(packet, buffer -> {
                        var copy = new float[buffer.channels()][];
                        for (var c = 0; c < copy.length; c++) {
                            copy[c] = Arrays.copyOf(buffer.plane(c), buffer.frames());
                        }
                        assertEquals(track.sampleRate(), buffer.sampleRate(), "buffer rate");
                        chunks.add(copy);
                    });
                }
            }
            return new Decoded(track, concatenate(chunks));
        }
    }

    /**
     * Writes the AAC track of a file as CAF, with its {@code ldsc} chunk or with that chunk renamed {@code free}.
     */
    private static byte[] caf(byte[] file, boolean layers) {
        var sink = MediaSink.memory();
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file)); var muxer = new CafMuxer(sink)) {
            var track = demuxer.tracks().getFirst();
            muxer.addTrack(track);
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                if (packet.trackId() == track.id()) {
                    muxer.writePacket(packet);
                }
            }
            muxer.finish();
        }
        var caf = sink.toByteArray();
        var ldsc = new String(caf, StandardCharsets.ISO_8859_1).indexOf("ldsc");
        assertTrue(ldsc > 0, "the CAF file lists the HE-AAC layers");
        if (!layers) {
            System.arraycopy("free".getBytes(StandardCharsets.US_ASCII), 0, caf, ldsc, 4);
        }
        return caf;
    }

    private static float[][] concatenate(List<float[][]> chunks) {
        var channels = chunks.getFirst().length;
        var length = 0;
        for (var chunk : chunks) {
            length += chunk[0].length;
        }
        var planes = new float[channels][length];
        var position = 0;
        for (var chunk : chunks) {
            for (var c = 0; c < channels; c++) {
                System.arraycopy(chunk[c], 0, planes[c], position, chunk[c].length);
            }
            position += chunk[0].length;
        }
        return planes;
    }

    @ParameterizedTest(name = "{0} as {1}")
    @CsvSource({
            "al_sbr_sig_48_2_sig0, mp4, 48000, 2", "al_sbr_sig_48_2_sig0, adts, 48000, 2",
            "al_sbr_sig_48_2_sig0, mkv, 48000, 2", "al_sbr_sr_48_2_fsaac24, mp4, 48000, 2",
            "al_sbr_sr_48_2_fsaac24, adts, 48000, 2", "al_sbr_sr_48_2_fsaac24, mkv, 48000, 2",
            "al_sbr_ps_01, mp4, 32000, 2", "al_sbr_ps_01, adts, 32000, 2", "al_sbr_ps_01, mkv, 32000, 2",
            "al_sbr_cm_48_5, mp4, 48000, 5", "al_sbr_cm_48_5, adts, 48000, 5", "al_sbr_cm_48_5, mkv, 48000, 5",
            "al_sbr_sig_48_2_sig0, caf, 48000, 2", "al_sbr_sig_48_2_sig0, caf-core, 48000, 2",
            "al_sbr_sr_48_2_fsaac24, caf, 48000, 2", "al_sbr_sr_48_2_fsaac24, caf-core, 48000, 2",
            "al_sbr_ps_01, caf, 32000, 2", "al_sbr_ps_01, caf-core, 32000, 2",
            "al_sbr_cm_48_5, caf, 48000, 5", "al_sbr_cm_48_5, caf-core, 48000, 5"
    })
    @DisplayName("declares the extended output and decodes it within the conformance criterion")
    void remuxed(String name, String extension, int rate, int channels) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var original = HeAacConformanceTest.fetch(name);
        // ffmpeg writes no AAC in CAF, so Smalt's muxer writes those files
        var remuxed = extension.equals("mp4") ? Fixtures.read(original)
                : extension.startsWith("caf") ? caf(Fixtures.read(original), extension.equals("caf"))
                : Fixtures.read(Fixtures.ffmpeg(extension, List.of("-i", original.toString()),
                        List.of("-map", "0:a", "-c", "copy")));
        var decoded = read(remuxed);
        assertEquals(rate, decoded.track().sampleRate(), "declared rate");
        assertNotNull(decoded.track().layout().orElse(null), "declared layout");
        assertEquals(channels, decoded.track().layout().orElseThrow().channels(), "declared channels");
        var reference = Oracle.decodeAudio(original);
        var frames = reference.length / channels;
        assertEquals(channels, decoded.planes().length);
        assertEquals(frames, decoded.planes()[0].length, "decoded length");
        for (var c = 0; c < channels; c++) {
            double squared = 0;
            double peak = 0;
            for (var i = 0; i < frames; i++) {
                var d = Math.abs((double) reference[i * channels + c] - decoded.planes()[c][i]);
                squared += d * d;
                peak = Math.max(peak, d);
            }
            var rms = Math.sqrt(squared / frames);
            assertTrue(rms <= AacConformanceTest.RMS_LIMIT, name + " channel " + c + ": RMS " + rms);
            assertTrue(peak <= AacConformanceTest.PEAK_LIMIT, name + " channel " + c + ": peak " + peak);
        }
    }
}
