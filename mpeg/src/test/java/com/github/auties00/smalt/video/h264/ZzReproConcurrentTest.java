package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.fail;

// Temporary checkpoint repro of the intermittent H264DecoderTest.concurrentDecoders mismatch; deleted after use.
class ZzReproConcurrentTest {
    private static final VideoTrack ANNEX_B = H264TestStreams.track(Optional.empty(), Optional.empty());

    @Test
    void loop() throws Exception {
        var stream = Fixtures.ffmpeg("h264", List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25"),
                List.of("-vf", "format=yuv420p", "-frames:v", "30", "-c:v", "libx264", "-profile:v", "high", "-bf", "3",
                        "-x264-params", "keyint=12:open-gop=1", "-f", "h264"));
        var payloads = new ArrayList<byte[]>();
        for (var unit : H264TestStreams.accessUnits(H264TestStreams.nalUnits(Fixtures.read(stream)))) {
            payloads.add(H264TestStreams.annexB(unit));
        }
        List<Packet> packets = H264TestStreams.packets(payloads);
        var bytes = Fixtures.read(stream);
        var digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var reports = java.nio.file.Path.of("target", "surefire-reports");
        java.nio.file.Files.createDirectories(reports);
        java.nio.file.Files.write(reports.resolve("repro-stream.h264"), bytes);
        System.out.println("repro stream " + bytes.length + " bytes sha256 " + digest);
        var single = H264TestStreams.decode(ANNEX_B, packets, 1);
        var report = new ArrayList<String>();
        var rounds = Integer.getInteger("repro.rounds", 40);
        var bad = 0;
        for (var round = 0; round < rounds; round++) {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = new ArrayList<Future<List<H264TestStreams.Decoded>>>();
                var threadCounts = new ArrayList<Integer>();
                for (var i = 0; i < 16; i++) {
                    var threads = 2 + i % 7;
                    threadCounts.add(threads);
                    futures.add(executor.submit(() -> H264TestStreams.decode(ANNEX_B, packets, threads)));
                }
                for (var d = 0; d < futures.size(); d++) {
                    List<H264TestStreams.Decoded> frames;
                    try {
                        frames = futures.get(d).get();
                    } catch (java.util.concurrent.ExecutionException exception) {
                        System.out.println("PROBE-FAILURE round " + round + " decoder " + d + " threads " + threadCounts.get(d));
                        exception.getCause().printStackTrace(System.out);
                        System.out.flush();
                        throw exception;
                    }
                    for (var i = 0; i < single.size(); i++) {
                        var want = single.get(i).samples();
                        var got = frames.get(i).samples();
                        var count = 0;
                        var first = -1;
                        for (var p = 0; p < want.length; p++) {
                            if (want[p] != got[p]) {
                                count++;
                                if (first < 0) {
                                    first = p;
                                }
                            }
                        }
                        if (count > 0) {
                            bad++;
                            if (report.size() < 40) {
                                report.add("round " + round + " decoder " + d + " threads " + threadCounts.get(d) + " frame " + i
                                        + ": " + count + " samples differ, first " + first + " (x " + (first % 176) + " y "
                                        + (first / 176) + ") " + (want[first] & 0xFF) + " vs " + (got[first] & 0xFF)
                                        + " keyframe " + single.get(i).keyframe());
                            }
                        }
                    }
                }
            }
        }
        if (bad > 0) {
            fail(bad + " bad frames over " + rounds + " rounds:\n" + String.join("\n", report));
        }
    }
}
