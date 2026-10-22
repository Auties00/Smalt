package com.github.auties00.smalt.video.h263;

import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.mpeg4.Mpeg4ElementaryStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes H.263+ bitstreams contributed to ITU-T Q.15/SG16 and checks them against what their
 * encoders reconstructed.
 *
 * <p>Intel's Draft 20 set (Foreman QCIF, 68 pictures) comes with the decoded sequence of each
 * stream; every picture must match it within the noise floor of the inverse DCT (both decoders
 * meet IEEE 1180, and the Deblocking Filter mode carries their differences on from picture to
 * picture), and match ffmpeg's decode with the same inverse DCT more closely still. The set's
 * {@code dfijst_fmnq} stream is not used: its encoder codes some INTER blocks with the INTRA VLC
 * where decoding them with the INTER VLC stays inside the block, which the Alternative INTER VLC
 * mode does not allow (ITU-T H.263 S.2.1).
 *
 * <p>The University of British Columbia's anchors of July 1998 were coded by the TMN10 reference
 * encoder with Unrestricted Motion Vectors, Advanced Prediction (overlapped motion compensation),
 * Advanced INTRA Coding, the Deblocking Filter and Modified Quantization (Foreman QCIF, 100
 * pictures at 10 pictures per second, one stream per quantizer). They come with the encoder's log,
 * which reports the luma PSNR of every reconstructed picture against the source to 0.01 dB; the
 * decoded pictures must reach the same PSNR against the same source. The encoder's inverse DCT
 * differs from the decoder's by the IEEE 1180 tolerance, whose mismatches accumulate where many
 * coefficients are coded, so the tight bound applies to the coarser quantizers and to the start
 * of the finer ones, and a looser bound to every picture.
 */
@DisplayName("H.263+ conformance streams")
class H263ConformanceTest {
    private static final String ARCHIVE = "h263plus-Intel_Draft20.zip";
    private static final String URL =
            "https://www.itu.int/wftp3/av-arch/video-site/h263plus/bitstreams/Intel_Draft20.zip";
    private static final String SHA256 = "d8c37e8b97f49a0fc4cecb4c81f5f178dfd3e4f7d9d7c57d20c738d9708d875b";

    private static final String ANCHORS = "https://www.itu.int/wftp3/av-arch/video-site/h263plus/bitstreams/9807_anchors/";
    private static final String STREAMS = "h263plus-9807-foreman.streams.tar";
    private static final String STREAMS_SHA256 = "8f6f98eb3b24ed505753a846ac8a93369707b44a3478bcbe75d8228e43fae31a";
    private static final String LOGS = "h263plus-9807-foreman.logs.tar.gz";
    private static final String LOGS_SHA256 = "7cb3d93d10f47665731dc8d688820f2c03045733ce88a3a810e76ff577eb049b";
    private static final String SOURCE = "foreman_qcif.y4m";
    private static final String SOURCE_URL = "https://media.xiph.org/video/derf/y4m/foreman_qcif.y4m";
    private static final String SOURCE_SHA256 = "9b37e95ae2d06b3b173d6130965f450009216084bae12b2025248814baf057af";
    private static final Pattern PICTURE_PSNR = Pattern.compile("Reading image no: (\\d+).*?SNR_Y\\s*:\\s*([0-9.]+)",
            Pattern.DOTALL);

    private static final int LUMA = 176 * 144;
    private static final int FRAME = LUMA * 3 / 2;

    /**
     * With ffmpeg's C inverse DCT, the only differences left are its rounding ties, which the Deblocking Filter mode
     * carries on from picture to picture: at least 65 dB per picture.
     */
    private static final Tolerance SAME_IDCT = Tolerance.psnr(65);

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // No optional mode.
            "base_fmnq, 56",
            // Unrestricted Motion Vectors, four vectors per macroblock with the Deblocking Filter,
            // Slice Structured and Modified Quantization modes.
            "dfkjst_fmnq, 51"
    })
    @DisplayName("decodes every picture within the IDCT noise floor of the reference decode")
    void matchesReference(String stream, double minimumPsnr) throws IOException {
        var archive = Conformance.fetch(ARCHIVE, URL, SHA256);
        byte[] bitstream;
        byte[] reference;
        try (var zip = new ZipFile(archive.toFile())) {
            bitstream = zip.getInputStream(zip.getEntry(stream + ".263")).readAllBytes();
            reference = zip.getInputStream(zip.getEntry(stream + ".dec")).readAllBytes();
        }
        var file = Files.createTempFile("h263-", ".263");
        try {
            Files.write(file, bitstream);
            var decoded = decode(file);
            assertEquals(reference.length, decoded.length, "decoded size");
            // the IDCT noise floor of the stream: both decoders meet IEEE 1180
            Tolerance.psnr(minimumPsnr).assertFrames(stream + " against the reference decode", reference, decoded,
                    FRAME);
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                // With the same inverse DCT the only differences left are its rounding ties; -cpuflags 0 makes the
                // reference ffmpeg's C inverse DCT, which its arm64 NEON kernels do not reproduce.
                var ffmpeg = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-cpuflags",
                        "0", "-idct", "int", "-f", "h263", "-i", file.toString(), "-f", "rawvideo", "-pix_fmt", "yuv420p",
                        "-"));
                assertEquals(ffmpeg.length, decoded.length, "pictures decoded by ffmpeg");
                SAME_IDCT.assertFrames(stream + " against ffmpeg -idct int", ffmpeg, decoded, FRAME);
            }
        } finally {
            Files.delete(file);
        }
    }

    @ParameterizedTest(name = "quantizer {0}")
    @CsvSource({
            "25, 100, 0.015",
            "15, 30, 0.015",
            "10, 0, 0",
            "7, 0, 0",
            "5, 0, 0",
            "4, 0, 0"
    })
    @DisplayName("reaches the PSNR of the TMN10 encoder's reconstruction with overlapped motion compensation")
    void matchesReferenceEncoder(int quantizer, int tightPictures, double tightBound) throws IOException {
        var streams = Conformance.fetch(STREAMS, ANCHORS + "foreman.streams.tar", STREAMS_SHA256);
        var logs = Conformance.fetch(LOGS, ANCHORS + "foreman.logs.tar.gz", LOGS_SHA256);
        var source = Conformance.fetch(SOURCE, SOURCE_URL, SOURCE_SHA256);
        var name = "foreman_qcif_dfjit_" + quantizer;
        byte[] bitstream;
        try (var in = Files.newInputStream(streams)) {
            bitstream = tarEntry(in, name + ".stream");
        }
        String log;
        try (var in = new GZIPInputStream(Files.newInputStream(logs))) {
            log = new String(tarEntry(in, name + ".log"), StandardCharsets.ISO_8859_1);
        }
        var images = new ArrayList<Integer>();
        var encoderPsnr = new ArrayList<Double>();
        var matcher = PICTURE_PSNR.matcher(log);
        while (matcher.find()) {
            images.add(Integer.parseInt(matcher.group(1)));
            encoderPsnr.add(Double.parseDouble(matcher.group(2)));
        }
        assertEquals(100, images.size(), "pictures in the encoder's log");
        var sources = y4mLuma(Files.readAllBytes(source));
        var file = Files.createTempFile("h263-", ".263");
        try {
            Files.write(file, bitstream);
            var decoded = decode(file);
            assertEquals(images.size() * FRAME, decoded.length, "decoded size");
            for (var i = 0; i < images.size(); i++) {
                // The log numbers the source pictures from 1.
                var ours = Tolerance.Difference.of(sources.get(images.get(i) - 1), Arrays.copyOfRange(decoded,
                        i * FRAME, i * FRAME + LUMA)).psnr();
                // 0.005 dB of rounding in the log, the rest IEEE 1180 mismatches.
                var bound = i < tightPictures ? tightBound : 0.2;
                assertEquals(encoderPsnr.get(i), ours, bound, name + ", picture " + i
                        + ": luma PSNR against the source differs from the encoder's reconstruction");
            }
        } finally {
            Files.delete(file);
        }
    }

    /**
     * Returns the content of the first entry of a tar archive with the given name.
     */
    private static byte[] tarEntry(InputStream in, String name) throws IOException {
        var header = new byte[512];
        while (in.readNBytes(header, 0, 512) == 512 && header[0] != 0) {
            var nameEnd = 0;
            while (nameEnd < 100 && header[nameEnd] != 0) {
                nameEnd++;
            }
            var entry = new String(header, 0, nameEnd, StandardCharsets.US_ASCII);
            var size = Long.parseLong(new String(header, 124, 12, StandardCharsets.US_ASCII).trim().replace("\0", ""),
                    8);
            var data = in.readNBytes((int) size);
            in.skipNBytes((512 - size % 512) % 512);
            if (entry.equals(name) || entry.endsWith("/" + name)) {
                return data;
            }
        }
        throw new AssertionError(name + " is not in the archive");
    }

    /**
     * Returns the luma plane of every picture of a 4:2:0 QCIF YUV4MPEG2 stream.
     */
    private static List<byte[]> y4mLuma(byte[] data) {
        var pictures = new ArrayList<byte[]>();
        var at = indexOf(data, 0, (byte) '\n') + 1;
        assertTrue(new String(data, 0, at, StandardCharsets.US_ASCII).contains(" W176 H144 "), "QCIF source");
        while (at < data.length) {
            var end = indexOf(data, at, (byte) '\n');
            assertTrue(end > at && new String(data, at, 5, StandardCharsets.US_ASCII).equals("FRAME"),
                    "frame header at " + at);
            at = end + 1;
            pictures.add(Arrays.copyOfRange(data, at, at + LUMA));
            at += FRAME;
        }
        assertNotNull(pictures.getFirst());
        return pictures;
    }

    private static int indexOf(byte[] data, int from, byte value) {
        for (var i = from; i < data.length; i++) {
            if (data[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private static byte[] decode(Path file) {
        var raw = new ByteArrayOutputStream();
        try (var source = MediaSource.of(file); var stream = Mpeg4ElementaryStream.open(source)) {
            try (var decoder = new H263Decoder(stream.track())) {
                Consumer<VideoFrame> sink = frame -> raw.writeBytes(H263DifferentialTest.raw((YuvPicture) frame.picture()));
                Packet packet;
                while ((packet = stream.read()) != null) {
                    decoder.decode(packet, sink);
                }
                decoder.flush(sink);
            }
        }
        return raw.toByteArray();
    }
}
