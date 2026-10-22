package com.github.auties00.smalt.mpeg.fuzz;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.HeifBuilder;
import com.github.auties00.smalt.testkit.fuzz.FuzzPackets;
import com.github.auties00.smalt.testkit.fuzz.FuzzSeeds;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayOutputStream;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.copy;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.ffmpeg;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.tone;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.video;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.write;

/**
 * Writes the committed seeds of the mpeg fuzz targets (tools/fuzz/README.md): short streams encoded by ffmpeg and
 * converted to the packet format through Smalt's demuxers, raw elementary streams and H.264 parameter sets made by
 * ffmpeg (and from a separate-color-plane test stream), HEIC stills built with the test kit's
 * {@link HeifBuilder} around x265 pictures, and AAC LTP, ER AAC LTP and SSR streams written bit by bit. Runs only with {@code -Dsmalt.fuzz.writeSeeds=true}.
 */
@EnabledIfSystemProperty(named = "smalt.fuzz.writeSeeds", matches = "true",
        disabledReason = "[opt-in:fuzz-seeds] runs only with -Dsmalt.fuzz.writeSeeds=true")
class FuzzSeedWriter {
    @Test
    @DisplayName("writes the seeds of the video decoder targets")
    void videos() {
        seed("h264", "valid-baseline-mp4.bin", Codec.H264, ffmpeg("mp4", video(32, 32, 3), "-c:v", "libx264",
                "-preset", "ultrafast", "-profile:v", "baseline"));
        seed("h264", "valid-high-bframes-cabac.bin", Codec.H264, ffmpeg("mp4", video(48, 32, 4), "-c:v", "libx264",
                "-preset", "medium", "-bf", "2", "-refs", "2", "-x264-params", "weightb=1:b-pyramid=normal"));
        seed("h264", "valid-annexb-interlaced.bin", Codec.H264, ffmpeg("ts", video(32, 64, 2), "-c:v", "libx264",
                "-preset", "ultrafast", "-flags", "+ildct+ilme", "-x264-params", "tff=1"));
        seed("h264", "valid-high10-8x8.bin", Codec.H264, ffmpeg("mp4", video(32, 32, 2), "-c:v", "libx264",
                "-preset", "fast", "-pix_fmt", "yuv420p10le", "-x264-params", "8x8dct=1"));
        seed("h264", "valid-high422-cabac.bin", Codec.H264, ffmpeg("mp4", video(32, 32, 3), "-c:v", "libx264",
                "-preset", "fast", "-pix_fmt", "yuv422p", "-bf", "1", "-x264-params", "8x8dct=1:weightb=1"));
        seed("h264", "valid-high422-10-mbaff-cavlc.bin", Codec.H264, ffmpeg("ts", video(32, 64, 2), "-c:v",
                "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv422p10le", "-flags", "+ildct+ilme",
                "-x264-params", "tff=1:cabac=0"));
        seed("h264", "valid-high444-cabac-8x8-wp.bin", Codec.H264, ffmpeg("mp4", video(32, 32, 4), "-c:v",
                "libx264", "-preset", "fast", "-pix_fmt", "yuv444p", "-bf", "2", "-x264-params",
                "8x8dct=1:weightp=2:weightb=1"));
        seed("h264", "valid-high444-10-mbaff-cavlc.bin", Codec.H264, ffmpeg("ts", video(32, 64, 2), "-c:v",
                "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv444p10le", "-flags", "+ildct+ilme",
                "-x264-params", "tff=1:cabac=0"));
        seed("h264", "valid-lossless-rgb.bin", Codec.H264, ffmpeg("mp4", video(32, 32, 3), "-c:v", "libx264rgb",
                "-preset", "fast", "-bf", "1", "-qp", "0"));
        seed("h264", "valid-lossless-420-cavlc.bin", Codec.H264, ffmpeg("mp4", video(32, 32, 3), "-c:v",
                "libx264", "-preset", "fast", "-pix_fmt", "yuv420p", "-qp", "0", "-x264-params", "cabac=0"));
        write(MpegFuzzTest.class, "h264", "valid-separate-planes.bin",
                FuzzPackets.video(0, 96, 64, null, separatePlaneUnits(3)));
        seed("hevc", "valid-main-mp4.bin", Codec.HEVC, ffmpeg("mp4", video(64, 64, 3), "-c:v", "libx265",
                "-preset", "ultrafast", "-x265-params", "log-level=0:bframes=1"));
        seed("hevc", "valid-main10-annexb.bin", Codec.HEVC, ffmpeg("ts", video(64, 32, 2), "-c:v", "libx265",
                "-preset", "fast", "-pix_fmt", "yuv420p10le", "-x265-params", "log-level=0:sao=1:amp=1"));
        seed("mpeg4", "valid-asp-avi.bin", Codec.MPEG4_PART2, ffmpeg("avi", video(32, 32, 3), "-c:v", "mpeg4",
                "-q:v", "10", "-bf", "1"));
        seed("mpeg4", "valid-qpel-mp4.bin", Codec.MPEG4_PART2, ffmpeg("mp4", video(32, 32, 3), "-c:v", "mpeg4",
                "-q:v", "10", "-flags", "+qpel+mv4"));
        seed("h263", "valid-sqcif.bin", Codec.H263, ffmpeg("3gp", video(128, 96, 3), "-c:v", "h263", "-q:v", "20"));
        seed("h263", "valid-plus-annexes.bin", Codec.H263, ffmpeg("avi", video(176, 144, 2), "-c:v", "h263p",
                "-q:v", "20", "-flags", "+aic+loop", "-umv", "1", "-aiv", "1"));
    }

    @Test
    @DisplayName("writes the seeds of the audio decoder targets")
    void audios() {
        seed("aac", "valid-lc-m4a.bin", Codec.AAC, ffmpeg("m4a", tone(8000, 0.3), "-c:a", "aac", "-b:a", "16k"));
        seed("aac", "valid-lc-stereo-adts.bin", Codec.AAC, ffmpeg("aac", tone(44100, 0.1), "-ac", "2",
                "-c:a", "aac", "-b:a", "64k"));
        seed("aac", "valid-stereo-tools.bin", Codec.AAC, ffmpeg("m4a", tone(16000, 0.2), "-ac", "2", "-c:a",
                "aac", "-b:a", "24k", "-aac_pns", "1", "-aac_is", "1", "-aac_ms", "1", "-aac_tns", "1"));
        seed("amrNb", "valid-nb.bin", Codec.AMR_NB, ffmpeg("amr", tone(8000, 0.2), "-c:a", "libopencore_amrnb",
                "-ar", "8000", "-b:a", "12.2k"));
        seed("amrWb", "valid-wb.bin", Codec.AMR_WB, ffmpeg("amr", tone(16000, 0.2), "-c:a", "libvo_amrwbenc",
                "-ar", "16000"));
        seed("ac3", "valid-stereo.bin", Codec.AC3, ffmpeg("ac3", tone(48000, 0.1), "-ac", "2", "-c:a", "ac3",
                "-b:a", "96k"));
        seed("ac3", "valid-surround.bin", Codec.AC3, ffmpeg("ac3", tone(48000, 0.06), "-ac", "6", "-c:a", "ac3",
                "-b:a", "192k"));
        seed("eac3", "valid-stereo.bin", Codec.EAC3, ffmpeg("eac3", tone(48000, 0.1), "-ac", "2", "-c:a", "eac3",
                "-b:a", "96k"));
    }

    @Test
    @DisplayName("writes the AAC Main, LTP, ER AAC LTP and SSR seeds of the AAC target")
    void aacObjectTypes() {
        // ffmpeg's encoder sets prediction_used from the fourth frame of this mono tone on
        seed("aac", "valid-main-prediction.bin", Codec.AAC, ffmpeg("m4a", tone(44100, 0.15), "-c:a", "aac",
                "-strict", "-2", "-profile:a", "aac_main", "-aac_pred", "1", "-b:a", "24k"));
        // no available encoder writes long term prediction data or SSR gain control data, so these
        // streams are built bit by bit: 44.1 kHz, 39 zero bands and a noise-substituted band 39
        var ltp = new ArrayList<FuzzPackets.Part>();
        var errorResilientLtp = new ArrayList<FuzzPackets.Part>();
        var ssr = new ArrayList<FuzzPackets.Part>();
        for (var frame = 0; frame < 8; frame++) {
            ltp.add(new FuzzPackets.Part(ltpPair(frame, false), true));
            errorResilientLtp.add(new FuzzPackets.Part(ltpPair(frame, true), true));
            ssr.add(new FuzzPackets.Part(ssrSingle(frame), true));
        }
        // AudioSpecificConfigs: object type 4 (LTP), 19 (ER LTP with the extension flag, no resilience
        // tools and epConfig 0) or 3 (SSR), sampling frequency index 4, channel configuration 2 or 1
        write(MpegFuzzTest.class, "aac", "valid-ltp-pair.bin",
                FuzzPackets.audio(0, 44100, 2, 0, new byte[]{0x22, 0x10}, ltp));
        write(MpegFuzzTest.class, "aac", "valid-er-ltp-pair.bin",
                FuzzPackets.audio(0, 44100, 2, 0, new byte[]{(byte) 0x9A, 0x11, 0x00}, errorResilientLtp));
        write(MpegFuzzTest.class, "aac", "valid-ssr-gain-control.bin",
                FuzzPackets.audio(0, 44100, 1, 0, new byte[]{0x1A, 0x08}, ssr));
    }

    /**
     * Builds a {@code raw_data_block} (or an {@code er_raw_data_block}) holding one channel pair with a common
     * long window, long term prediction in all 40 bands of both channels and an order 4 TNS filter.
     */
    private static byte[] ltpPair(int frame, boolean errorResilient) {
        var writer = new MsbBitWriter();
        if (!errorResilient) {
            writer.write(1, 3);
        }
        writer.write(0, 4);
        writer.write(1, 1);
        writer.write(0, 1);
        writer.write(0, 2);
        writer.write(frame & 1, 1);
        writer.write(40, 6);
        writer.write(1, 1);
        if (!errorResilient) {
            ltpData(writer, frame, 0);
            ltpData(writer, frame, 1);
        }
        writer.write(0, 2);
        for (var channel = 0; channel < 2; channel++) {
            if (errorResilient) {
                ltpData(writer, frame, channel);
            }
            writer.write(100, 8);
            noiseBand(writer, 256 + 40 + channel);
            writer.write(0, 1);
            writer.write(1, 1);
            if (!errorResilient) {
                tnsData(writer);
            }
            writer.write(0, 1);
            if (errorResilient) {
                tnsData(writer);
            }
        }
        if (!errorResilient) {
            writer.write(7, 3);
        }
        writer.alignWithZeros();
        return writer.toByteArray();
    }

    /**
     * Writes {@code ltp_data_present} set and an {@code ltp_data} covering 40 bands, sweeping the lag and keeping the
     * coefficient below 1.
     */
    private static void ltpData(MsbBitWriter writer, int frame, int channel) {
        writer.write(1, 1);
        writer.write((256 + frame * 37 + channel * 911) % 2048, 11);
        writer.write((frame + channel) & 3, 3);
        for (var band = 0; band < 40; band++) {
            writer.write(1, 1);
        }
    }

    /** Writes the sections and scalefactors of 39 zero long bands and one noise band. */
    private static void noiseBand(MsbBitWriter writer, int energy) {
        writer.write(0, 4);
        writer.write(31, 5);
        writer.write(8, 5);
        writer.write(13, 4);
        writer.write(1, 5);
        writer.write(energy, 9);
    }

    /** Writes the TNS data of a long window: one order 4 filter over 40 bands. */
    private static void tnsData(MsbBitWriter writer) {
        writer.write(1, 2);
        writer.write(1, 1);
        writer.write(40, 6);
        writer.write(4, 5);
        writer.write(0, 2);
        writer.write(0x5C39, 16);
    }

    /**
     * Builds a {@code raw_data_block} holding one SSR single channel element with gain control data in the three
     * gain-controlled PQF bands, cycling through every window sequence in an order ISO/IEC 14496-3 allows.
     */
    private static byte[] ssrSingle(int frame) {
        int[] sequences = {0, 1, 2, 2, 3, 0, 1, 3};
        var sequence = sequences[frame];
        var writer = new MsbBitWriter();
        writer.write(0, 3);
        writer.write(0, 4);
        writer.write(100, 8);
        writer.write(0, 1);
        writer.write(sequence, 2);
        writer.write(frame >> 1 & 1, 1);
        if (sequence == 2) {
            // 14 short bands in one window group: 13 zero bands, then one noise band
            writer.write(14, 4);
            writer.write(0x7F, 7);
            writer.write(0, 4);
            writer.write(7, 3);
            writer.write(6, 3);
            writer.write(13, 4);
            writer.write(1, 3);
            writer.write(256 + 30, 9);
        } else {
            writer.write(40, 6);
            writer.write(0, 1);
            noiseBand(writer, 256 + 40);
        }
        writer.write(0, 1);
        writer.write(0, 1);
        writer.write(1, 1);
        writer.write(3, 2);
        var windows = sequence == 0 ? 1 : sequence == 2 ? 8 : 2;
        for (var band = 1; band <= 3; band++) {
            for (var window = 0; window < windows; window++) {
                var bits = switch (sequence) {
                    case 0 -> 5;
                    case 2 -> 2;
                    case 1 -> window == 0 ? 4 : 2;
                    default -> window == 0 ? 4 : 5;
                };
                // two increasing locations of each width, the 4-bit ones below the reserved codes 14 and 15
                int[] locations = bits == 2 ? new int[]{1, 3} : bits == 4 ? new int[]{3, 12} : new int[]{4, 20};
                var changes = (frame + band + window) % 3;
                writer.write(changes, 3);
                for (var change = 0; change < changes; change++) {
                    writer.write((frame * 3 + band + window + change) & 15, 4);
                    writer.write(locations[change], bits);
                }
            }
        }
        writer.write(7, 3);
        writer.alignWithZeros();
        return writer.toByteArray();
    }

    @Test
    @DisplayName("writes the seeds of the HEIC target")
    void heic() {
        write(MpegFuzzTest.class, "heic", "valid-still.heic", still(ffmpeg("mp4", video(64, 64, 1), "-frames:v",
                "1", "-c:v", "libx265", "-preset", "ultrafast", "-x265-params", "log-level=0")));
        write(MpegFuzzTest.class, "heic", "valid-10bit.heic", still(ffmpeg("mp4", video(32, 32, 1), "-frames:v",
                "1", "-c:v", "libx265", "-preset", "ultrafast", "-pix_fmt", "yuv420p10le", "-x265-params",
                "log-level=0")));
    }

    @Test
    @DisplayName("writes the seeds of the elementary stream and H.264 parameter set targets")
    void streams() {
        // the m4v extension selects ffmpeg's ipod muxer, so the raw stream muxer is named
        copy(MpegFuzzTest.class, "elementaryStream", "valid-mpeg4.m4v", ffmpeg("m4v", video(32, 32, 3),
                "-c:v", "mpeg4", "-q:v", "10", "-bf", "1", "-f", "m4v"));
        copy(MpegFuzzTest.class, "elementaryStream", "valid-h263.h263", ffmpeg("h263", video(128, 96, 3),
                "-c:v", "h263", "-q:v", "20"));
        var mp4 = ffmpeg("mp4", video(48, 32, 1), "-c:v", "libx264", "-preset", "ultrafast", "-vf", "crop=46:30");
        write(MpegFuzzTest.class, "h264Info", "valid-avcc-cropped.bin", firstTrack(mp4).codecConfig().orElseThrow());
        var interlaced = ffmpeg("mp4", video(32, 64, 2), "-c:v", "libx264", "-preset", "ultrafast",
                "-pix_fmt", "yuv422p10le", "-flags", "+ildct+ilme", "-x264-params", "tff=1");
        write(MpegFuzzTest.class, "h264Info", "valid-avcc-high422-interlaced.bin",
                firstTrack(interlaced).codecConfig().orElseThrow());
        var annexB = ffmpeg("h264", video(32, 32, 1), "-c:v", "libx264", "-preset", "ultrafast", "-r", "25");
        write(MpegFuzzTest.class, "h264Info", "valid-annexb.bin", Fixtures.read(annexB));
        var config = firstTrack(mp4).codecConfig().orElseThrow();
        // avcC: 5 header bytes, the SPS count byte, a 16-bit length, then the first SPS NAL unit
        var spsLength = (config[6] & 0xFF) << 8 | config[7] & 0xFF;
        write(MpegFuzzTest.class, "h264Info", "valid-sps.bin", Arrays.copyOfRange(config, 8, 8 + spsLength));
        var lossless = ffmpeg("mp4", video(32, 32, 1), "-c:v", "libx264", "-preset", "ultrafast",
                "-pix_fmt", "yuv444p", "-qp", "0");
        write(MpegFuzzTest.class, "h264Info", "valid-avcc-high444-lossless.bin",
                firstTrack(lossless).codecConfig().orElseThrow());
        var planes = Fixtures.read(separatePlanes());
        // the stream starts with a 4-byte start code and the SPS, which the zero bytes of the next start code end
        var end = 4;
        while (planes[end] != 0 || planes[end + 1] != 0 || planes[end + 2] != 1) {
            end++;
        }
        while (planes[end - 1] == 0) {
            end--;
        }
        write(MpegFuzzTest.class, "h264Info", "valid-sps-separate-planes.bin", Arrays.copyOfRange(planes, 4, end));
    }

    /**
     * Splits the first access units of the separate-color-plane test stream, whose pictures are written as the
     * slices of the Y, Cb and Cr planes in turn, into Annex B packets.
     *
     * @param count the number of access units
     * @return the packets, the first holding the parameter sets
     */
    private static List<FuzzPackets.Part> separatePlaneUnits(int count) {
        var data = Fixtures.read(separatePlanes());
        var starts = new ArrayList<Integer>();
        for (var i = 0; i + 3 <= data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                starts.add(i + 3);
            }
        }
        var parts = new ArrayList<FuzzPackets.Part>();
        var unit = new ByteArrayOutputStream();
        var started = false;
        var idr = false;
        var planeStarts = 0;
        for (var n = 0; n < starts.size() && parts.size() < count; n++) {
            var from = starts.get(n);
            var to = n + 1 < starts.size() ? starts.get(n + 1) - 3 : data.length;
            while (to > from && data[to - 1] == 0) {
                to--;
            }
            var type = data[from] & 0x1F;
            // a slice whose first_mb_in_slice is 0 starts a plane, and every third plane starts a picture
            if ((type == 1 || type == 5) && (data[from + 1] & 0x80) != 0 && planeStarts++ % 3 == 0 && started) {
                parts.add(new FuzzPackets.Part(unit.toByteArray(), idr));
                unit.reset();
                idr = false;
            }
            started |= type == 1 || type == 5;
            idr |= type == 5;
            unit.writeBytes(new byte[]{0, 0, 0, 1});
            unit.write(data, from, to - from);
        }
        return parts;
    }

    private static Path separatePlanes() {
        try {
            return Path.of(FuzzSeedWriter.class.getResource(
                    "/com/github/auties00/smalt/video/h264/chroma444/m8-cabac-slices-sep.264").toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static VideoTrack firstTrack(Path file) {
        try (var source = MediaSource.of(file); var demuxer = Containers.openDemuxer(source)) {
            return (VideoTrack) demuxer.tracks().getFirst();
        }
    }

    private static byte[] still(Path mp4) {
        try (var source = MediaSource.of(mp4); var demuxer = Containers.openDemuxer(source)) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            var packet = demuxer.read();
            var builder = new HeifBuilder().brands("heic", "heic", "mif1");
            var id = builder.item("hvc1", packet.data(),
                    HeifBuilder.plain(HeifBuilder.ispe(track.width(), track.height())),
                    HeifBuilder.essential(HeifBuilder.box("hvcC", track.codecConfig().orElseThrow())));
            return builder.primary(id).build();
        }
    }

    private static void seed(String method, String name, Codec codec, Path file) {
        write(MpegFuzzTest.class, method, name, FuzzSeeds.packets(file, codec, 0, 6));
    }
}
