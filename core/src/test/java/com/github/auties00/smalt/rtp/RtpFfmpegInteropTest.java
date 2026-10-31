package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Interoperates with ffmpeg's RTP muxer and demuxer as a black-box peer over loopback UDP: ffmpeg sends a file it
 * made and the depacketizer must give back the file's packets byte for byte, and the packetizer sends the file's
 * packets to an ffmpeg reading the session description, whose per-packet MD5 list ({@code framemd5}) must match.
 * ffmpeg's demuxer is told that the stream ended with an RTCP BYE.
 */
@DisplayName("Interoperability with ffmpeg's RTP muxer and demuxer")
class RtpFfmpegInteropTest {
    /** One stream: how ffmpeg makes it, and the extra ffmpeg arguments its RTP muxer needs. */
    record Source(String name, String extension, List<String> input, List<String> output, List<String> rtpOptions,
                  int framesPerPacket) {
        @Override
        public String toString() {
            return name;
        }

        Path file() {
            return Fixtures.ffmpeg(extension, input, output);
        }
    }

    static Stream<Arguments> sources() {
        var video = List.of("-f", "lavfi", "-i", "testsrc2=size=320x240:rate=25", "-t", "2");
        var tone = List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "2");
        return Stream.of(
                new Source("H.264", "mp4", video, List.of("-c:v", "libx264", "-g", "25", "-bf", "2", "-pix_fmt",
                        "yuv420p", "-b:v", "600k"), List.of(), 1),
                new Source("VP8", "ivf", video, List.of("-c:v", "libvpx", "-b:v", "600k", "-g", "25",
                        "-auto-alt-ref", "0"), List.of(), 1),
                new Source("VP9", "ivf", video, List.of("-c:v", "libvpx-vp9", "-b:v", "600k", "-g", "25",
                        "-deadline", "realtime", "-cpu-used", "8", "-lag-in-frames", "0", "-auto-alt-ref", "0"),
                        List.of("-strict", "experimental"), 1),
                new Source("Opus", "ogg", tone, List.of("-c:a", "libopus", "-b:a", "64k"), List.of(), 1),
                new Source("AMR-NB", "amr", List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=8000",
                        "-t", "2"), List.of("-ac", "1", "-c:a", "libopencore_amrnb", "-b:a", "12.2k"), List.of(), 1),
                new Source("AMR-WB", "amr", List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=16000",
                        "-t", "2"), List.of("-ac", "1", "-c:a", "libvo_amrwbenc", "-b:a", "23.85k"), List.of(), 1),
                new Source("AAC", "m4a", tone, List.of("-c:a", "aac", "-b:a", "96k", "-ac", "2"), List.of(), 3))
                .map(Arguments::of);
    }

    record Demuxed(Track track, List<Packet> packets) {
    }

    static Demuxed demux(Path file) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            var track = demuxer.tracks().getFirst();
            var packets = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
            return new Demuxed(track, packets);
        }
    }

    static byte[] concat(List<byte[]> parts) {
        var out = new ByteArrayOutputStream();
        parts.forEach(out::writeBytes);
        return out.toByteArray();
    }

    /** Binds an even RTP port and the RTCP port after it. */
    static DatagramSocket[] bindPair() throws SocketException {
        for (var attempt = 0; attempt < 50; attempt++) {
            var probe = new DatagramSocket(0, InetAddress.getLoopbackAddress());
            var port = probe.getLocalPort() & ~1;
            probe.close();
            try {
                var rtp = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
                try {
                    var rtcp = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), port + 1));
                    return new DatagramSocket[]{rtp, rtcp};
                } catch (SocketException busy) {
                    rtp.close();
                }
            } catch (SocketException busy) {
                // try another pair
            }
        }
        throw new SocketException("no free port pair");
    }

    /** Finds a free even port whose successor is free too, for ffmpeg to bind. */
    static int freePair() throws SocketException {
        var sockets = bindPair();
        var port = sockets[0].getLocalPort();
        sockets[0].close();
        sockets[1].close();
        return port;
    }

    static String md5(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    static Process start(List<String> arguments, Path log) throws IOException {
        var command = new ArrayList<String>();
        command.add(Oracle.find(Oracle.Tool.FFMPEG).orElseThrow().toString());
        command.addAll(arguments);
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    @Nested
    @DisplayName("ffmpeg sends, Smalt receives")
    class FfmpegSends {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.rtp.RtpFfmpegInteropTest#sources")
        @DisplayName("reassembles the packets of the file ffmpeg streams")
        void receive(Source source) throws Exception {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var file = source.file();
            var expected = demux(file);
            var scratch = Scratch.directory("rtp-interop");
            var sdp = scratch.resolve(source.name() + "-send.sdp");
            var log = scratch.resolve(source.name() + "-send.log");
            var sockets = bindPair();
            var received = new ArrayList<RtpPacket>();
            try (var rtp = sockets[0]; var rtcp = sockets[1]) {
                rtp.setReceiveBufferSize(4 << 20);
                rtp.setSoTimeout(300);
                var arguments = new ArrayList<>(List.of("-v", "error", "-y", "-readrate", "4", "-i", file.toString(),
                        "-map", "0:0", "-c", "copy"));
                arguments.addAll(source.rtpOptions());
                arguments.addAll(List.of("-sdp_file", sdp.toString(), "-f", "rtp",
                        "rtp://127.0.0.1:" + rtp.getLocalPort() + "?pkt_size=1200"));
                var process = start(arguments, log);
                var buffer = new byte[65536];
                var quiet = 0;
                while (quiet < 5 || process.isAlive()) {
                    var datagram = new DatagramPacket(buffer, buffer.length);
                    try {
                        rtp.receive(datagram);
                        received.add(RtpPacket.parse(datagram.getData(), 0, datagram.getLength()));
                        quiet = 0;
                    } catch (SocketTimeoutException timeout) {
                        if (!process.isAlive()) {
                            quiet++;
                        }
                    }
                }
                assertEquals(0, process.waitFor(), () -> "ffmpeg failed: " + read(log));
            }
            var format = sdpFormat(read(sdp));
            var depacketizer = RtpDepacketizer.create(format, RtpDepacketizerOptions.defaults());
            var frames = RtpChannel.depacketize(depacketizer, received);
            assertEquals(0, depacketizer.lostPackets(), "packets lost on loopback");
            assertEquals(0, depacketizer.discardedPackets());
            List<byte[]> got = frames.stream().map(frame -> frame.packet().data()).toList();
            List<byte[]> want = expected.packets().stream().map(Packet::data).toList();
            if (source.name().startsWith("AMR")) {
                var wideband = source.name().equals("AMR-WB");
                got = amrFrames(concat(got), wideband);
                want = amrFrames(concat(want), wideband);
            }
            // ffmpeg's muxer never sends the AMR frames and AAC access units it still holds for aggregation when
            // the input ends, so an audio stream may come up short by less than one packet's worth.
            var tail = source.name().startsWith("AMR") || source.name().equals("AAC") ? 64 : 0;
            assertTrue(got.size() <= want.size() && want.size() - got.size() <= tail,
                    want.size() + " units sent, " + got.size() + " received");
            assertTrue(got.size() > want.size() / 2, "too few units: " + got.size());
            for (var index = 0; index < got.size(); index++) {
                assertEquals(md5(want.get(index)), md5(got.get(index)), "unit " + index);
            }
            assertTrue(frames.stream().allMatch(RtpFrame::complete));
        }
    }

    @Nested
    @DisplayName("Smalt sends, ffmpeg receives")
    class SmaltSends {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.rtp.RtpFfmpegInteropTest#sources")
        @DisplayName("streams the file's packets to ffmpeg, which reads them back unchanged")
        void send(Source source) throws Exception {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var file = source.file();
            var input = demux(file);
            var format = RtpPayloadFormat.forTrack(input.track());
            var packetizer = RtpPacketizer.create(input.track(), RtpChannel.fixed()
                    .framesPerPacket(source.framesPerPacket()).parameterSetsBeforeKeyFrames(false).build());
            var packets = new ArrayList<RtpPacket>();
            for (var packet : input.packets()) {
                packets.addAll(packetizer.packetize(packet));
            }
            packets.addAll(packetizer.flush());
            var scratch = Scratch.directory("rtp-interop");
            var port = freePair();
            var sdp = scratch.resolve(source.name() + "-receive.sdp");
            var media = format.codec().kind() == com.github.auties00.smalt.track.TrackKind.VIDEO ? "video" : "audio";
            var description = "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=Smalt\r\nc=IN IP4 127.0.0.1\r\nt=0 0\r\n"
                    + "m=" + media + " " + port + " RTP/AVP 96\r\na=rtpmap:96 " + format.rtpmap() + "\r\n"
                    + (format.fmtp().isEmpty() ? "" : "a=fmtp:96 " + format.fmtp() + "\r\n");
            Files.writeString(sdp, description, StandardCharsets.US_ASCII);
            var output = scratch.resolve(source.name() + ".framemd5");
            var log = scratch.resolve(source.name() + "-receive.log");
            // ffmpeg's VP9 and AAC RTP demuxers flag no packet as a key frame, which a stream copy would wait for.
            var arguments = List.of("-loglevel", "debug", "-y", "-protocol_whitelist", "file,udp,rtp",
                    "-reorder_queue_size", "0", "-i", sdp.toString(), "-map", "0:0", "-c", "copy", "-copyinkf",
                    "-f", "framemd5", output.toString());
            var process = start(arguments, log);
            try (var socket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
                waitForLine(log, "Before avformat_find_stream_info", process);
                var target = new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
                for (var index = 0; index < packets.size(); index++) {
                    var bytes = packets.get(index).toByteArray();
                    socket.send(new DatagramPacket(bytes, bytes.length, target));
                    if (index % 4 == 3) {
                        Thread.sleep(1);
                    }
                }
                Thread.sleep(200);
                var bye = new byte[]{(byte) 0x81, (byte) 203, 0, 1, 0x12, 0x34, 0x56, 0x78};
                for (var attempt = 0; attempt < 5 && process.isAlive(); attempt++) {
                    socket.send(new DatagramPacket(bye, bye.length, new InetSocketAddress(
                            InetAddress.getLoopbackAddress(), port + 1)));
                    process.waitFor(1, TimeUnit.SECONDS);
                }
            } finally {
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    fail("ffmpeg did not finish: " + tail(read(log)));
                }
            }
            assertEquals(0, process.exitValue(), () -> "ffmpeg failed: " + tail(read(log)));
            var lines = Files.readAllLines(output).stream().filter(line -> !line.startsWith("#")).toList();
            var got = lines.stream().map(line -> {
                var fields = line.split(",");
                return fields[4].strip() + ":" + fields[5].strip();
            }).toList();
            List<byte[]> units;
            if (source.name().equals("H.264")) {
                // ffmpeg's depacketizer writes every NAL unit behind a 4-byte start code.
                units = input.packets().stream().map(packet -> annexB(packet.data())).toList();
            } else if (source.name().startsWith("AMR")) {
                units = amrFrames(concat(input.packets().stream().map(Packet::data).toList()),
                        source.name().equals("AMR-WB"));
            } else {
                units = input.packets().stream().map(Packet::data).toList();
            }
            var want = units.stream().map(unit -> unit.length + ":" + md5(unit)).toList();
            if (!want.equals(got)) {
                fail("ffmpeg read back " + got.size() + " of " + want.size() + " units, first difference at "
                        + firstDifference(want, got) + "; ffmpeg log:\n" + tail(read(log)));
            }
        }
    }

    static int firstDifference(List<String> want, List<String> got) {
        var index = 0;
        while (index < want.size() && index < got.size() && want.get(index).equals(got.get(index))) {
            index++;
        }
        return index;
    }

    static byte[] annexB(byte[] lengthPrefixed) {
        var out = new ByteArrayOutputStream();
        var position = 0;
        while (position + 4 <= lengthPrefixed.length) {
            var length = ((lengthPrefixed[position] & 0xFF) << 24) | ((lengthPrefixed[position + 1] & 0xFF) << 16)
                    | ((lengthPrefixed[position + 2] & 0xFF) << 8) | (lengthPrefixed[position + 3] & 0xFF);
            out.writeBytes(new byte[]{0, 0, 0, 1});
            out.write(lengthPrefixed, position + 4, length);
            position += 4 + length;
        }
        return out.toByteArray();
    }

    static List<byte[]> amrFrames(byte[] data, boolean wideband) {
        var frames = new ArrayList<byte[]>();
        var position = 0;
        while (position < data.length) {
            var size = 1 + com.github.auties00.smalt.internal.codec.AmrConfigs.payloadSize(wideband,
                    (data[position] >>> 3) & 0x0F);
            frames.add(java.util.Arrays.copyOfRange(data, position, position + size));
            position += size;
        }
        return frames;
    }

    static void waitForLine(Path log, String text, Process process) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (Files.exists(log) && read(log).contains(text)) {
                Thread.sleep(100);
                return;
            }
            if (!process.isAlive()) {
                fail("ffmpeg exited early: " + tail(read(log)));
            }
            Thread.sleep(20);
        }
        fail("ffmpeg never opened its sockets: " + tail(read(log)));
    }

    static RtpPayloadFormat sdpFormat(String sdp) {
        String rtpmap = null;
        String fmtp = null;
        for (var line : sdp.split("\\R")) {
            if (line.startsWith("a=rtpmap:")) {
                rtpmap = line;
            } else if (line.startsWith("a=fmtp:")) {
                fmtp = line;
            }
        }
        if (rtpmap == null) {
            fail("no rtpmap in " + sdp);
        }
        return RtpPayloadFormat.fromSdp(rtpmap, fmtp);
    }

    static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.ISO_8859_1);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static String tail(String text) {
        return text.length() > 4000 ? text.substring(text.length() - 4000) : text;
    }
}
