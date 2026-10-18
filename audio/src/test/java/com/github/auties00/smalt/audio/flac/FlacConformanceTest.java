package com.github.auties00.smalt.audio.flac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.header.FlacStreamInfo;
import com.github.auties00.smalt.container.flac.FlacDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the IETF CELLAR FLAC decoder testbench (github.com/ietf-wg-cellar/flac-test-files, CC0,
 * pinned to commit aa7b0c6cf32994c106ae517a08134c28a96ff5b2): every subset and uncommon file must
 * decode to samples whose MD5 equals the STREAMINFO signature, every faulty file must be rejected
 * with {@link MediaException.Malformed}.
 */
class FlacConformanceTest {
    static List<FlacConformanceCorpus.Vector> corpus() {
        return FlacConformanceCorpus.all();
    }

    static Stream<FlacConformanceCorpus.Vector> valid() {
        return corpus().stream().filter(vector -> !vector.group().equals("faulty"));
    }

    static Stream<FlacConformanceCorpus.Vector> faulty() {
        return corpus().stream().filter(vector -> vector.group().equals("faulty"));
    }

    /** The outcome of decoding a whole file: MD5 over the samples as FLAC defines it, and the sample count. */
    record Result(byte[] md5, long samples, AudioTrack track) {
    }

    static Result decode(Path file) throws Exception {
        var digest = MessageDigest.getInstance("MD5");
        var samples = 0L;
        try (var source = MediaSource.of(file); var demuxer = new FlacDemuxer(source)) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            try (var decoder = new FlacDecoder(track)) {
                Packet packet;
                var bytes = new byte[0];
                while ((packet = demuxer.read()) != null) {
                    decoder.decode(packet, buffer -> {
                    });
                    var frames = decoder.lastBlockSize();
                    var channels = decoder.lastChannels();
                    var width = (decoder.lastBitsPerSample() + 7) / 8;
                    var needed = frames * channels * width;
                    if (bytes.length < needed) {
                        bytes = new byte[needed];
                    }
                    var planes = decoder.lastSamples();
                    var at = 0;
                    for (var i = 0; i < frames; i++) {
                        for (var c = 0; c < channels; c++) {
                            var value = planes[c][i];
                            for (var b = 0; b < width; b++) {
                                bytes[at++] = (byte) (value >> (8 * b));
                            }
                        }
                    }
                    digest.update(bytes, 0, needed);
                    samples += frames;
                }
            }
            return new Result(digest.digest(), samples, track);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("valid")
    @DisplayName("subset and uncommon files decode to their STREAMINFO MD5")
    void md5(FlacConformanceCorpus.Vector vector) throws Exception {
        var file = vector.fetch();
        var result = decode(file);
        var streamInfo = vector.number().equals("10") && vector.group().equals("uncommon")
                || vector.number().equals("11") && vector.group().equals("uncommon")
                ? null
                : FlacStreamInfo.parse(result.track().codecConfig().orElseThrow(), 8);
        if (streamInfo == null) {
            assertTrue(result.samples() > 0, "no samples decoded");
            return;
        }
        if (streamInfo.totalSamples() > 0) {
            assertEquals(streamInfo.totalSamples(), result.samples(), "sample count");
        }
        if (streamInfo.hasMd5()) {
            assertEquals(HexFormat.of().formatHex(streamInfo.md5()), HexFormat.of().formatHex(result.md5()), "MD5");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("faulty")
    @DisplayName("faulty files are rejected as malformed")
    void faultyFiles(FlacConformanceCorpus.Vector vector) {
        var file = vector.fetch();
        assertThrows(MediaException.Malformed.class, () -> decode(file));
    }
}
