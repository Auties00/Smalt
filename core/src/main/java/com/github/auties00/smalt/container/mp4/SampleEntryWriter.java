package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.internal.codec.Ac3Configs;
import com.github.auties00.smalt.internal.codec.AmrConfigs;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.subtitle.TimedText;
import com.github.auties00.smalt.internal.subtitle.WebVttText;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Serializes the sample description of a track the {@link Mp4Muxer} writes: a visual sample entry
 * ({@code avc1}, {@code hvc1}, {@code vp09}, {@code av01}) with its configuration box and an
 * optional {@code colr}, a QuickTime visual sample entry ({@code apco} to {@code ap4x} for ProRes,
 * {@code AVdn} and {@code AVdh} for DNxHD) with its {@code colr}, {@code fiel} and {@code pasp} boxes, an
 * audio sample entry ({@code mp4a}, {@code Opus}, {@code alac}, {@code fLaC},
 * {@code samr}, {@code sawb}) with its configuration box, or a text sample entry: {@code tx3g} (3GPP TS
 * 26.245) or {@code wvtt} with its {@code vttC} box (ISO/IEC 14496-30).
 */
final class SampleEntryWriter {
    private SampleEntryWriter() {
        throw new AssertionError();
    }

    /**
     * Writes the sample entry of a track.
     *
     * @param writer the writer positioned inside the {@code stsd} box
     * @param plan the track layout
     */
    static void write(BoxWriter writer, Mp4Muxer.TrackPlan plan) {
        var track = plan.track;
        switch (track.codec()) {
            case H264 -> writeVisual(writer, BoxTypes.AVC1, BoxTypes.AVCC, false, (VideoTrack) track.track, track.config);
            case HEVC -> writeVisual(writer, BoxTypes.HVC1, BoxTypes.HVCC, false, (VideoTrack) track.track, track.config);
            case VP9 -> writeVisual(writer, BoxTypes.VP09, BoxTypes.VPCC, true, (VideoTrack) track.track, track.config);
            case AV1 -> writeVisual(writer, BoxTypes.AV01, BoxTypes.AV1C, false, (VideoTrack) track.track, track.config);
            case PRORES, DNXHD -> writeQuickTimeVisual(writer, track, (VideoTrack) track.track);
            case MOV_TEXT, WEBVTT -> writeText(writer, track.track);
            default -> writeAudio(writer, plan, (AudioTrack) track.track);
        }
    }

    /**
     * Writes a visual sample entry, ISO/IEC 14496-12 clause 12.1.3.
     *
     * @param writer the destination
     * @param format the sample entry type
     * @param configType the type of the configuration box
     * @param full whether the configuration box is a version 1 full box ({@code vpcC})
     * @param video the track
     * @param config the configuration box body
     * @throws IllegalStateException if the configuration is unknown
     */
    private static void writeVisual(BoxWriter writer, int format, int configType, boolean full, VideoTrack video, byte[] config) {
        if (config == null) {
            throw new IllegalStateException(video.codec().displayName() + " track " + video.id()
                    + " has no codec configuration and no key frame it can be derived from");
        }
        writer.begin(format);
        writer.zeros(6);
        writer.u16(1);
        writer.zeros(16);
        writer.u16(video.width());
        writer.u16(video.height());
        writer.u32(0x00480000);
        writer.u32(0x00480000);
        writer.u32(0);
        writer.u16(1);
        writer.zeros(32);
        writer.u16(0x0018);
        writer.u16(0xFFFF);
        if (full) {
            writer.beginFull(configType, 1, 0);
        } else {
            writer.begin(configType);
        }
        writer.bytes(config);
        writer.end();
        if (video.color().isPresent()) {
            var color = video.color().get();
            writer.begin(BoxTypes.COLR);
            writer.u32(BoxTypes.NCLX);
            writer.u16(color.primaries().code());
            writer.u16(color.transfer().code());
            writer.u16(color.matrix().code());
            writer.u8(color.range() == ColorRange.FULL ? 0x80 : 0);
            writer.end();
        }
        writer.end();
    }

    /**
     * Writes a QuickTime visual sample entry for a ProRes or DNxHD track (Apple QuickTime File
     * Format, video sample description), followed by an {@code nclc} color box when the track
     * declares its color, a {@code fiel} box describing the scan of the first frame and a square
     * {@code pasp} box. The depth is 32 when the first frame carries alpha (a ProRes 4444 alpha
     * channel, or the alpha flag {@code ALP} of a VC-3 coding unit), 24 otherwise.
     *
     * <p>The sample entry type is the track's four-character configuration; without one it is
     * {@code ap4h} or {@code apcn} for ProRes, by the chroma format of the first frame, and
     * {@code AVdh} or {@code AVdn} for DNxHD, by the header version of the first frame.
     *
     * @param writer the destination
     * @param track the track state, with the first bytes of the first frame
     * @param video the track
     */
    private static void writeQuickTimeVisual(BoxWriter writer, MuxTrack track, VideoTrack video) {
        var first = track.firstBytes;
        var prores = video.codec() == Codec.PRORES;
        int format;
        if (track.config != null && track.config.length == 4) {
            format = (track.config[0] & 0xFF) << 24 | (track.config[1] & 0xFF) << 16 | (track.config[2] & 0xFF) << 8
                    | track.config[3] & 0xFF;
        } else if (prores) {
            format = first != null && first.length > 20 && (first[20] & 0xFF) >>> 6 == 3 ? BoxTypes.AP4H : BoxTypes.APCN;
        } else {
            format = first != null && first.length > 4 && first[4] >= 3 ? BoxTypes.AVDH : BoxTypes.AVDN;
        }
        var alpha = prores ? first != null && first.length > 25 && (first[25] & 0x0F) != 0
                : first != null && first.length > 7 && (first[7] & 1) != 0;
        var fieldOrder = 0;
        if (prores && first != null && first.length > 20) {
            fieldOrder = (first[20] >>> 2) & 3;
        } else if (!prores && first != null && first.length > 0x22) {
            fieldOrder = (first[0x22] & 0x04) != 0 ? 1 : 0;
        }
        writer.begin(format);
        writer.zeros(6);
        writer.u16(1);
        writer.u16(0);
        writer.u16(0);
        writer.u32(0);
        writer.u32(0x200);
        writer.u32(0x200);
        writer.u16(video.width());
        writer.u16(video.height());
        writer.u32(0x00480000);
        writer.u32(0x00480000);
        writer.u32(0);
        writer.u16(1);
        var name = compressorName(format).getBytes(StandardCharsets.US_ASCII);
        writer.u8(name.length);
        writer.bytes(name);
        writer.zeros(31 - name.length);
        writer.u16(alpha ? 32 : 24);
        writer.u16(0xFFFF);
        if (video.color().isPresent()) {
            var color = video.color().get();
            writer.begin(BoxTypes.COLR);
            writer.u32(BoxTypes.NCLC);
            writer.u16(color.primaries().code());
            writer.u16(color.transfer().code());
            writer.u16(color.matrix().code());
            writer.end();
        }
        writer.begin(BoxTypes.FIEL);
        writer.u8(fieldOrder == 0 ? 1 : 2);
        writer.u8(fieldOrder == 0 ? 0 : fieldOrder == 1 ? 9 : 14);
        writer.end();
        writer.begin(BoxTypes.PASP);
        writer.u32(1);
        writer.u32(1);
        writer.end();
        writer.end();
    }

    /**
     * Returns the compressor name a QuickTime sample entry carries for a ProRes or DNxHD sample
     * entry type.
     *
     * @param format the sample entry type
     * @return the name, at most 31 ASCII characters
     */
    private static String compressorName(int format) {
        return switch (format) {
            case BoxTypes.APCO -> "ProRes 422 Proxy";
            case BoxTypes.APCS -> "ProRes 422 LT";
            case BoxTypes.APCN -> "ProRes 422";
            case BoxTypes.APCH -> "ProRes 422 HQ";
            case BoxTypes.AP4H -> "ProRes 4444";
            case BoxTypes.AP4X -> "ProRes 4444 XQ";
            case BoxTypes.AVDH -> "DNxHR";
            default -> "DNxHD";
        };
    }

    /**
     * Writes a text sample entry: {@code tx3g} with the track configuration (the default 3GPP
     * description when there is none), or {@code wvtt} with a {@code vttC} box holding the
     * WebVTT header (a bare {@code WEBVTT} when there is none).
     *
     * @param writer the destination
     * @param track the text track
     */
    private static void writeText(BoxWriter writer, Track track) {
        var config = track.codecConfig().filter(bytes -> bytes.length > 0).orElse(null);
        if (track.codec() == Codec.MOV_TEXT) {
            writer.begin(SampleEntry.TX3G);
            writer.zeros(6);
            writer.u16(1);
            writer.bytes(config != null ? config : TimedText.defaultConfig());
            writer.end();
            return;
        }
        var header = WebVttText.header(config == null ? null : new String(config, StandardCharsets.UTF_8));
        writer.begin(SampleEntry.WVTT);
        writer.zeros(6);
        writer.u16(1);
        writer.begin(SampleEntry.VTT_CONFIG);
        writer.bytes(header.getBytes(StandardCharsets.UTF_8));
        writer.end();
        writer.end();
    }

    /**
     * Writes an audio sample entry, ISO/IEC 14496-12 clause 12.2.3, version 0.
     *
     * @param writer the destination
     * @param plan the track layout
     * @param audio the track
     */
    private static void writeAudio(BoxWriter writer, Mp4Muxer.TrackPlan plan, AudioTrack audio) {
        var config = plan.track.config;
        var channels = audio.layout().map(layout -> layout.channels()).orElse(0);
        var sampleRate = audio.sampleRate();
        var format = BoxTypes.MP4A;
        switch (audio.codec()) {
            case AAC -> {
                var aac = AudioConfigs.parseAac(config);
                if (channels == 0) {
                    channels = aac.channels();
                }
                if (sampleRate == 0) {
                    sampleRate = aac.sampleRate();
                }
            }
            case MP3 -> {
                var header = plan.track.firstBytes == null ? null
                        : AudioConfigs.parseMpegHeader(plan.track.firstBytes, 0, plan.track.firstBytes.length);
                if (sampleRate == 0 && header != null) {
                    sampleRate = header.sampleRate();
                }
                if (channels == 0 && header != null) {
                    channels = ((plan.track.firstBytes[3] >> 6) & 3) == 3 ? 1 : 2;
                }
            }
            case OPUS -> {
                format = BoxTypes.OPUS;
                channels = AudioConfigs.parseOpusHead(config).channels();
                sampleRate = 48000;
            }
            case ALAC -> {
                format = BoxTypes.ALAC;
                config = AudioConfigs.alacConfig(config);
                var alac = AudioConfigs.parseAlac(config);
                channels = alac.channels() > 0 ? alac.channels() : channels;
                sampleRate = alac.sampleRate() > 0 ? alac.sampleRate() : sampleRate;
            }
            case FLAC -> {
                format = BoxTypes.FLAC;
                var flac = AudioConfigs.parseFlac(config);
                channels = flac.channels();
                sampleRate = flac.sampleRate();
            }
            case AC3, EAC3 -> {
                var enhanced = audio.codec() == Codec.EAC3;
                format = enhanced ? BoxTypes.EC3 : BoxTypes.AC3;
                var first = plan.track.firstBytes;
                var frame = first == null ? null : Ac3Configs.parse(first, 0, first.length);
                var stream = frame == null ? null : Ac3Configs.describe(first, 0, first.length);
                config = stream == null ? null : enhanced ? Ac3Configs.dec3(first, 0, first.length) : Ac3Configs.dac3(frame);
                if (config == null) {
                    config = Ac3Configs.declared(enhanced, sampleRate, audio.layout().orElse(null));
                } else {
                    channels = stream.layout().channels();
                    sampleRate = frame.sampleRate();
                }
            }
            case AMR_NB, AMR_WB -> {
                var wideband = audio.codec() == Codec.AMR_WB;
                format = wideband ? BoxTypes.SAWB : BoxTypes.SAMR;
                channels = 1;
                sampleRate = wideband ? 16000 : 8000;
                config = AmrConfigs.damr(wideband, plan.track.amrModes);
            }
            default -> throw new IllegalStateException("unsupported codec " + audio.codec());
        }
        writer.begin(format);
        writer.zeros(6);
        writer.u16(1);
        writer.zeros(8);
        writer.u16(Math.max(channels, 1));
        writer.u16(audio.codec() == Codec.ALAC && audio.bitsPerSample() > 0 ? audio.bitsPerSample() : 16);
        writer.u32(0);
        writer.u32(sampleRate > 0 && sampleRate <= 0xFFFF ? (long) sampleRate << 16 : 0);
        switch (audio.codec()) {
            case AAC, MP3 -> {
                var objectType = audio.codec() == Codec.AAC ? EsDescriptor.MPEG4_AUDIO
                        : sampleRate >= 32000 || sampleRate == 0 ? EsDescriptor.MPEG1_AUDIO : EsDescriptor.MPEG2_AUDIO;
                var descriptor = new EsDescriptor(objectType, EsDescriptor.AUDIO_STREAM, maxSampleSize(plan),
                        maxBitrate(plan), averageBitrate(plan), audio.codec() == Codec.AAC ? config : null);
                writer.beginFull(BoxTypes.ESDS, 0, 0);
                descriptor.write(writer, plan.track.number);
                writer.end();
            }
            case OPUS -> {
                writer.begin(BoxTypes.DOPS);
                writer.bytes(AudioConfigs.dopsFromOpusHead(config));
                writer.end();
            }
            case ALAC -> {
                writer.beginFull(BoxTypes.ALAC, 0, 0);
                writer.bytes(config);
                writer.end();
            }
            case FLAC -> {
                writer.beginFull(BoxTypes.DFLA, 0, 0);
                var blocks = AudioConfigs.flacContainerConfig(config);
                writer.bytes(Arrays.copyOfRange(blocks, 4, blocks.length));
                writer.end();
            }
            case AMR_NB, AMR_WB -> {
                writer.begin(BoxTypes.DAMR);
                writer.bytes(config);
                writer.end();
            }
            case AC3, EAC3 -> {
                writer.begin(audio.codec() == Codec.EAC3 ? BoxTypes.DEC3 : BoxTypes.DAC3);
                writer.bytes(config);
                writer.end();
            }
            default -> {
            }
        }
        writer.end();
    }

    /**
     * Returns the largest sample of a track.
     *
     * @param plan the track layout
     * @return the size in bytes, capped to 24 bits
     */
    private static int maxSampleSize(Mp4Muxer.TrackPlan plan) {
        var max = 0;
        for (var index = 0; index < plan.track.count; index++) {
            max = Math.max(max, plan.track.sizes[index]);
        }
        return Math.min(max, 0xFFFFFF);
    }

    /**
     * Returns the average bit rate of a track.
     *
     * @param plan the track layout
     * @return the rate in bits per second, or {@code 0} for an empty or instantaneous track
     */
    private static long averageBitrate(Mp4Muxer.TrackPlan plan) {
        if (plan.mediaDuration <= 0) {
            return 0;
        }
        var bytes = 0L;
        for (var index = 0; index < plan.track.count; index++) {
            bytes += plan.track.sizes[index];
        }
        return Math.min(0xFFFFFFFFL, bytes * 8 * plan.track.timescale / plan.mediaDuration);
    }

    /**
     * Returns the peak bit rate of a track over any one-second window of decoding time.
     *
     * @param plan the track layout
     * @return the rate in bits per second
     */
    private static long maxBitrate(Mp4Muxer.TrackPlan plan) {
        var track = plan.track;
        var window = track.timescale;
        var best = 0L;
        var sum = 0L;
        var start = 0;
        for (var end = 0; end < track.count; end++) {
            sum += track.sizes[end];
            while (track.dts[end] - track.dts[start] >= window) {
                sum -= track.sizes[start++];
            }
            best = Math.max(best, sum);
        }
        return Math.min(0xFFFFFFFFL, best * 8);
    }
}
