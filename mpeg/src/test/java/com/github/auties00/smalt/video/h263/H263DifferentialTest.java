package com.github.auties00.smalt.video.h263;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Quality;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Encodes synthetic scenes with ffmpeg's H.263 and H.263+ encoders across the optional modes and
 * compares the decoder's output with ffmpeg's decode, within the noise floor that the freedom of
 * the inverse DCT leaves (see the MPEG-4 differential test).
 *
 * <p>ffmpeg's decoder departs from ITU-T H.263 F.3 in the Advanced Prediction mode: it predicts
 * some overlapped blocks with a remote vector of the macroblock to the right other than the one
 * that macroblock carries, so its pictures drift from the encoder's by about a decibel within a
 * group of pictures. Streams with overlapped motion compensation are therefore checked against
 * the encoder's reconstruction instead: ffmpeg reports the luma PSNR of every reconstructed
 * picture against the source ({@code -vstats_file}, to 0.01 dB), and the decoder's pictures must
 * reach the same PSNR against the same source. Those streams zoom into a still picture, because
 * ffmpeg's encoder also departs from F.3 next to intra coded macroblocks (it overlaps their
 * motion estimation vectors) and in slices (it takes remote vectors from other slices), and a
 * smooth zoom keeps it from coding macroblocks intra where that shows.
 */
@DisplayName("H.263 decoder against ffmpeg")
class H263DifferentialTest {
    private static final Pattern VSTATS_PSNR = Pattern.compile("PSNR=\\s*([0-9.]+)");

    private static final int ZOOM_PICTURES = 30;

    enum Reference {
        DECODER,
        ENCODER
    }

    record Case(String name, String size, String codec, String options, Reference reference) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Case> cases() {
        return Stream.of(
                new Case("baseline QCIF", "176x144", "h263", "-g 12", Reference.DECODER),
                new Case("baseline CIF", "352x288", "h263", "-g 12", Reference.DECODER),
                new Case("sub-QCIF intra only", "128x96", "h263", "-g 1", Reference.DECODER),
                new Case("H.263+ custom size", "320x240", "h263p", "-g 12", Reference.DECODER),
                new Case("unrestricted motion vectors", "176x144", "h263p", "-umv 1", Reference.DECODER),
                new Case("advanced intra coding", "176x144", "h263p", "-flags +aic -g 6", Reference.DECODER),
                new Case("deblocking filter", "176x144", "h263p", "-flags +loop+mv4", Reference.DECODER),
                new Case("alternative inter VLC", "176x144", "h263p", "-aiv 1", Reference.DECODER),
                new Case("slice structured", "176x144", "h263p", "-structured_slices 1 -ps 300", Reference.DECODER),
                new Case("advanced prediction", "176x144", "h263", "-obmc 1 -flags +mv4", Reference.ENCODER),
                new Case("advanced prediction CIF", "352x288", "h263", "-obmc 1 -flags +mv4", Reference.ENCODER),
                new Case("H.263+ modes with advanced prediction", "352x288", "h263p",
                        "-flags +aic+mv4 -umv 1 -aiv 1 -obmc 1", Reference.ENCODER)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("decodes within the IDCT noise floor of ffmpeg's decode, or of ffmpeg's encoder with OBMC")
    void matchesFfmpeg(Case c, @TempDir Path directory) throws IOException {
        var extension = c.codec().equals("h263") ? "3gp" : "mkv";
        if (c.reference() == Reference.DECODER) {
            var output = new ArrayList<>(List.of("-t", "1", "-c:v", c.codec(), "-q:v", "5"));
            output.addAll(List.of(c.options().split(" ")));
            var file = Fixtures.ffmpeg(extension, List.of("-f", "lavfi", "-i", "testsrc2=size=" + c.size() + ":rate=15"),
                    output);
            var expected = ffmpeg(file, "int");
            var pictures = decode(file);
            var raw = concat(pictures);
            assertEquals(expected.length, raw.length, c.name() + " after " + pictures.size() + " frames");
            var ours = Quality.psnr(expected, raw);
            var floor = Quality.psnr(expected, ffmpeg(file, "simple"));
            assertTrue(ours >= Math.min(floor, 60) - 1, c.name() + ": PSNR " + ours + " dB, ffmpeg -idct simple "
                    + floor + " dB");
            return;
        }
        var source = List.of("-f", "lavfi", "-i", zoom(c.size()));
        var file = directory.resolve("stream." + extension);
        var stats = directory.resolve("vstats.txt");
        var options = new ArrayList<>(List.of(c.options().split(" ")));
        // A repeated -flags would replace the first, so the PSNR flag joins the case's flags.
        var flags = options.indexOf("-flags");
        options.set(flags + 1, options.get(flags + 1) + "+psnr");
        // -cpuflags 0: the encoder reconstructs with ffmpeg's C inverse DCT, which its arm64 NEON kernels do not
        // reproduce; the source pictures below are scaled by the same C code.
        var arguments = new ArrayList<>(List.of("-v", "error", "-y", "-cpuflags", "0"));
        arguments.addAll(source);
        arguments.addAll(List.of("-frames:v", Integer.toString(ZOOM_PICTURES), "-c:v", c.codec(), "-q:v", "5"));
        arguments.addAll(options);
        // Slice threads would make the encoder's choices depend on the thread count.
        arguments.addAll(List.of("-idct", "int", "-threads", "1", "-vstats_file", stats.toString(), file.toString()));
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), arguments);
        var encoderPsnr = VSTATS_PSNR.matcher(Files.readString(stats)).results()
                .map(result -> Double.parseDouble(result.group(1))).toList();
        var pictures = decode(file);
        assertEquals(ZOOM_PICTURES, pictures.size());
        assertEquals(ZOOM_PICTURES, encoderPsnr.size(), "pictures reported by the encoder");
        var sourceArguments = new ArrayList<>(List.of("-v", "error", "-cpuflags", "0"));
        sourceArguments.addAll(source);
        sourceArguments.addAll(List.of("-frames:v", Integer.toString(ZOOM_PICTURES), "-f", "rawvideo", "-pix_fmt",
                "yuv420p", "-"));
        var sources = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), sourceArguments);
        var dimensions = c.size().split("x");
        var lumaSize = Integer.parseInt(dimensions[0]) * Integer.parseInt(dimensions[1]);
        var frameSize = lumaSize * 3 / 2;
        assertEquals(frameSize * ZOOM_PICTURES, sources.length, "source pictures");
        for (var i = 0; i < ZOOM_PICTURES; i++) {
            var sourceLuma = Arrays.copyOfRange(sources, i * frameSize, i * frameSize + lumaSize);
            var ours = Quality.psnr(sourceLuma, Arrays.copyOf(pictures.get(i), lumaSize));
            // 0.005 dB of rounding in the report and up to 0.01 dB of IDCT noise.
            assertEquals(encoderPsnr.get(i), ours, 0.015, c.name() + ", picture " + i
                    + ": luma PSNR against the source differs from the encoder's reconstruction");
        }
    }

    /**
     * Returns a lavfi graph that zooms slowly into the first picture of {@code testsrc2}, so that
     * the vectors of neighboring macroblocks differ while the content stays predictable.
     */
    static String zoom(String size) {
        var dimensions = size.split("x");
        var width = Integer.parseInt(dimensions[0]);
        var height = Integer.parseInt(dimensions[1]);
        return "testsrc2=size=" + 2 * width + "x" + 2 * height + ":rate=15,trim=end_frame=1,"
                + "loop=loop=" + (ZOOM_PICTURES + 10) + ":size=1:start=0,setpts=N/15/TB,"
                + "zoompan=z='1+0.004*on':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=1:s=" + size + ":fps=15";
    }

    static List<byte[]> decode(Path file) {
        var pictures = new ArrayList<byte[]>();
        var timestamps = new ArrayList<Long>();
        try (var media = MediaSource.of(file); var demuxer = Containers.openDemuxer(media)) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            try (var decoder = new H263Decoder(track)) {
                Consumer<VideoFrame> sink = frame -> {
                    timestamps.add(frame.pts());
                    pictures.add(raw((YuvPicture) frame.picture()));
                };
                Packet packet;
                while ((packet = demuxer.read()) != null) {
                    decoder.decode(packet, sink);
                }
                decoder.flush(sink);
            }
        }
        for (var i = 1; i < timestamps.size(); i++) {
            assertTrue(timestamps.get(i) > timestamps.get(i - 1), "timestamps increase: " + timestamps);
        }
        return pictures;
    }

    static byte[] concat(List<byte[]> pictures) {
        var out = new ByteArrayOutputStream();
        pictures.forEach(out::writeBytes);
        return out.toByteArray();
    }

    static byte[] ffmpeg(Path file, String idct) {
        // -cpuflags 0: the reference is ffmpeg's C inverse DCT, which its arm64 NEON kernels do not reproduce.
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), List.of("-v", "error", "-cpuflags", "0", "-idct",
                idct, "-i", file.toString(), "-map", "0:v:0", "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt",
                "yuv420p", "-"));
    }

    static byte[] raw(YuvPicture picture) {
        var out = new ByteArrayOutputStream();
        for (var y = 0; y < picture.height(); y++) {
            out.write(picture.y(), y * picture.yStride(), picture.width());
        }
        for (var y = 0; y < picture.chromaHeight(); y++) {
            out.write(picture.u(), y * picture.uvStride(), picture.chromaWidth());
        }
        for (var y = 0; y < picture.chromaHeight(); y++) {
            out.write(picture.v(), y * picture.uvStride(), picture.chromaWidth());
        }
        return out.toByteArray();
    }
}
