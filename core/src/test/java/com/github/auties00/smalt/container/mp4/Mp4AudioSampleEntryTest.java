package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Optional;
import java.util.stream.Stream;

import static com.github.auties00.smalt.container.mp4.Mp4Builder.box;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.concat;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.file;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.full;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.mvhd;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.stsz;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.table;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.trak;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.u16;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.u32;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.u64;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.u8;
import static com.github.auties00.smalt.container.mp4.Mp4Builder.zeros;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Maps the audio sample entries of ISO base media and QuickTime files to tracks: every object type an MPEG-4
 * elementary stream descriptor names, entries without their configuration box, the QuickTime sound description
 * versions 1 and 2 with their linear PCM flags, the byte order a {@code wave} or {@code enda} box declares, and the
 * entries whose codec fixes the rate and channel count.
 */
@DisplayName("MP4 audio sample entries")
class Mp4AudioSampleEntryTest {
    private static final byte[] ASC_STEREO_44100 = {0x12, 0x10};

    record Case(String name, byte[] entry, Codec codec, int channels, int sampleRate, int bits, byte[] config) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** Builds a sample entry of the given sound description version; {@code extension} follows the 20-byte header. */
    static byte[] entry(String format, int version, int channels, int sampleSize, int rate, byte[] extension,
                        byte[]... children) {
        return box(format, zeros(6), u16(1), u16(version), u16(0), u32(0), u16(channels), u16(sampleSize), u16(0),
                u16(0), u32((long) rate << 16), extension, concat(children));
    }

    static byte[] entry(String format, int channels, int sampleSize, int rate, byte[]... children) {
        return entry(format, 0, channels, sampleSize, rate, new byte[0], children);
    }

    /** Builds an {@code esds} box with a decoder configuration of an object type and an optional specific info. */
    static byte[] esds(int objectType, byte[] specificInfo) {
        var info = specificInfo == null ? new byte[0] : concat(u8(5), u8(specificInfo.length), specificInfo);
        var decoderConfig = concat(u8(4), u8(13 + info.length), u8(objectType), u8(0x15), zeros(3), u32(0), u32(0),
                info);
        var es = concat(u8(3), u8(3 + decoderConfig.length + 3), u16(1), u8(0), decoderConfig, u8(6), u8(1), u8(2));
        return full("esds", 0, 0, es);
    }

    /** The QuickTime version 1 extension: samples per packet, bytes per packet, bytes per frame, bytes per sample. */
    static byte[] version1(int bytesPerFrame) {
        return concat(u32(1), u32(bytesPerFrame / 2), u32(bytesPerFrame), u32(2));
    }

    /** The QuickTime version 2 extension, from the size field to the frames per packet. */
    static byte[] version2(double rate, int channels, int bits, int flags, int bytesPerFrame) {
        return concat(u32(72), u64(Double.doubleToLongBits(rate)), u32(channels), u32(0x7F000000), u32(bits),
                u32(flags), u32(bytesPerFrame), u32(1));
    }

    static Stream<Case> cases() {
        var vorbisHeaders = new byte[]{2, 1, 1, 9, 8, 7};
        var dfla = concat(u32(0x80000022), u16(4096), u16(4096), zeros(6),
                // 48000 Hz, 2 channels, 24 bits, then the total samples and the MD5
                new byte[]{0x0B, (byte) 0xB8, 0x03, 0x70, 0, 0, 0, 0}, zeros(16));
        var alac = full("alac", 0, 0, u32(4096), u8(0), u8(20), u8(40), u8(10), u8(14), u8(3), u16(255), u32(0),
                u32(0), u32(88200));
        return Stream.of(
                new Case("mp4a MPEG-4 AAC", entry("mp4a", 1, 16, 44100, esds(0x40, ASC_STEREO_44100)), Codec.AAC, 2,
                        44100, 0, ASC_STEREO_44100),
                new Case("mp4a MPEG-4 AAC without a rate", entry("mp4a", 1, 16, 0, esds(0x40, ASC_STEREO_44100)),
                        Codec.AAC, 2, 44100, 0, ASC_STEREO_44100),
                new Case("mp4a MPEG-2 AAC Main", entry("mp4a", 2, 16, 44100, esds(0x66, ASC_STEREO_44100)), Codec.AAC, 2,
                        44100, 0, ASC_STEREO_44100),
                new Case("mp4a MPEG-2 AAC LC", entry("mp4a", 2, 16, 44100, esds(0x67, ASC_STEREO_44100)), Codec.AAC, 2,
                        44100, 0, ASC_STEREO_44100),
                new Case("mp4a MPEG-2 AAC SSR", entry("mp4a", 2, 16, 44100, esds(0x68, ASC_STEREO_44100)), Codec.AAC, 2,
                        44100, 0, ASC_STEREO_44100),
                new Case("mp4a with an unparsable AAC configuration", entry("mp4a", 6, 16, 32000,
                        esds(0x40, new byte[]{(byte) 0xF8})), Codec.AAC, 6, 32000, 0, new byte[]{(byte) 0xF8}),
                new Case("mp4a without esds", entry("mp4a", 2, 16, 48000), Codec.AAC, 2, 48000, 0, null),
                new Case("mp4a MPEG-2 audio", entry("mp4a", 2, 16, 44100, esds(0x69, null)), Codec.MP3, 2, 44100, 0, null),
                new Case("mp4a MPEG-1 audio", entry("mp4a", 2, 16, 44100, esds(0x6B, null)), Codec.MP3, 2, 44100, 0, null),
                new Case("mp4a AC-3", entry("mp4a", 2, 16, 48000, esds(0xA5, null)), Codec.AC3, 2, 48000, 0, null),
                new Case("mp4a E-AC-3", entry("mp4a", 2, 16, 48000, esds(0xA6, null)), Codec.EAC3, 2, 48000, 0, null),
                new Case("mp4a Opus", entry("mp4a", 2, 16, 48000, esds(0xAD, null)), Codec.OPUS, 2, 48000, 0, null),
                new Case("mp4a Vorbis", entry("mp4a", 2, 16, 44100, esds(0xDD, vorbisHeaders)), Codec.VORBIS, 2, 44100, 0,
                        null),
                new Case("mp4a of a video object type", entry("mp4a", 2, 16, 44100, esds(0x20, null)),
                        Codec.UNKNOWN_AUDIO, 2, 44100, 0, null),
                new Case(".mp3", entry(".mp3", 2, 16, 44100), Codec.MP3, 2, 44100, 0, null),
                new Case("ms U (Windows MP3)", entry("ms\u0000U", 1, 16, 22050), Codec.MP3, 1, 22050, 0, null),
                new Case("Opus without dOps", entry("Opus", 2, 16, 44100), Codec.OPUS, 2, 48000, 0, null),
                new Case("fLaC with dfLa", entry("fLaC", 1, 16, 8000, full("dfLa", 0, 0, dfla)), Codec.FLAC, 2, 48000, 24,
                        null),
                new Case("fLaC without dfLa", entry("fLaC", 2, 16, 44100), Codec.FLAC, 2, 44100, 0, null),
                new Case("alac with its configuration", entry("alac", 1, 16, 8000, alac), Codec.ALAC, 3, 88200, 20, null),
                new Case("alac without its configuration", entry("alac", 2, 16, 44100), Codec.ALAC, 2, 44100, 0, null),
                new Case("samr", entry("samr", 2, 16, 44100), Codec.AMR_NB, 1, 8000, 0, null),
                new Case("sawb", entry("sawb", 2, 16, 44100), Codec.AMR_WB, 1, 16000, 0, null),
                new Case("twos version 1", entry("twos", 1, 2, 16, 44100, version1(4)), Codec.PCM_S16BE, 2, 44100, 16,
                        null),
                new Case("twos little-endian in a wave box", entry("twos", 2, 16, 44100,
                        box("wave", box("enda", u16(1)))), Codec.PCM_S16LE, 2, 44100, 16, null),
                new Case("sowt", entry("sowt", 1, 16, 8000), Codec.PCM_S16LE, 1, 8000, 16, null),
                new Case("raw 8-bit", entry("raw ", 1, 8, 8000), Codec.PCM_U8, 1, 8000, 8, null),
                new Case("NONE 16-bit", entry("NONE", 2, 16, 8000), Codec.PCM_S16BE, 2, 8000, 16, null),
                new Case("in24", entry("in24", 2, 24, 48000), Codec.PCM_S24BE, 2, 48000, 24, null),
                new Case("in24 little-endian", entry("in24", 2, 24, 48000, box("enda", u16(1))), Codec.PCM_S24LE, 2, 48000,
                        24, null),
                new Case("in32", entry("in32", 2, 32, 48000), Codec.PCM_S32BE, 2, 48000, 32, null),
                new Case("fl32", entry("fl32", 2, 32, 48000), Codec.PCM_F32BE, 2, 48000, 32, null),
                new Case("fl64 little-endian", entry("fl64", 1, 32, 48000, box("enda", u16(1))), Codec.PCM_F64LE, 1, 48000,
                        64, null),
                new Case("ulaw", entry("ulaw", 1, 16, 8000), Codec.PCM_MULAW, 1, 8000, 8, null),
                new Case("alaw", entry("alaw", 1, 16, 8000), Codec.PCM_ALAW, 1, 8000, 8, null),
                // linear PCM flags: 1 float, 2 big-endian, 4 signed integer
                new Case("lpcm version 2 float little-endian", entry("lpcm", 2, 3, 16, 65536,
                        version2(96000, 2, 32, 1 | 8, 8)), Codec.PCM_F32LE, 2, 96000, 32, null),
                new Case("lpcm version 2 float64 big-endian", entry("lpcm", 2, 3, 16, 65536,
                        version2(44100, 1, 64, 1 | 2, 8)), Codec.PCM_F64BE, 1, 44100, 64, null),
                new Case("lpcm version 2 signed 24-bit big-endian", entry("lpcm", 2, 3, 16, 65536,
                        version2(48000, 6, 24, 2 | 4, 18)), Codec.PCM_S24BE, 6, 48000, 24, null),
                new Case("lpcm version 2 unsigned 8-bit", entry("lpcm", 2, 3, 16, 65536,
                        version2(22050, 1, 8, 0, 1)), Codec.PCM_U8, 1, 22050, 8, null),
                // a rate that is not a number leaves the rate of the media timescale
                new Case("lpcm version 2 with a rate that is not a number", entry("lpcm", 2, 3, 16, 65536,
                        version2(Double.NaN, 2, 16, 4, 4)), Codec.PCM_S16LE, 2, 44100, 16, null),
                new Case("lpcm version 0", entry("lpcm", 2, 16, 44100), Codec.UNKNOWN_AUDIO, 2, 44100, 0, null),
                new Case("ipcm without pcmC", entry("ipcm", 2, 16, 44100), Codec.UNKNOWN_AUDIO, 2, 44100, 0, null),
                new Case("fpcm 64-bit little-endian", entry("fpcm", 2, 16, 44100, full("pcmC", 0, 0, u8(1), u8(64))),
                        Codec.PCM_F64LE, 2, 44100, 64, null),
                new Case("fpcm 32-bit big-endian", entry("fpcm", 1, 16, 44100, full("pcmC", 0, 0, u8(0), u8(32))),
                        Codec.PCM_F32BE, 1, 44100, 32, null),
                new Case("an unknown entry", entry("xyzw", 2, 16, 44100), Codec.UNKNOWN_AUDIO, 2, 44100, 0, null));
    }

    private static byte[] file(byte[] entry, int sampleRate) {
        return Mp4Builder.file(zeros(64), mvhd(1000, 0), trak(1, "soun", Math.max(1, sampleRate), 0, entry,
                table("stts", 0, 2, 1, 1024), table("stsc", 0, 3, 1, 1, 1), stsz(64, new int[1]),
                table("stco", 0, 1, Mp4Builder.FTYP_SIZE + 8)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("describes the codec, layout, rate, sample size and configuration of the entry")
    void entry(Case c) {
        try (var demuxer = new Mp4Demuxer(MediaSource.of(file(c.entry(), 44100)))) {
            var track = assertInstanceOf(AudioTrack.class, demuxer.tracks().getFirst());
            assertEquals(c.codec(), track.codec(), "codec");
            assertEquals(c.channels(), track.layout().map(layout -> layout.channels()).orElse(0), "channels");
            assertEquals(c.sampleRate(), track.sampleRate(), "sample rate");
            assertEquals(c.bits(), track.bitsPerSample(), "bits per sample");
            if (c.config() != null) {
                assertArrayEquals(c.config(), track.codecConfig().orElseThrow(), "configuration");
            }
        }
    }

    @Test
    @DisplayName("rejects a channel count over the limits")
    void channelLimit() {
        var data = file(entry("lpcm", 2, 3, 16, 65536, version2(48000, 300, 16, 4, 600)), 48000);
        var options = DemuxerOptions.builder().limits(MediaLimits.builder().maxChannels(64).build()).build();
        assertThrows(MediaException.LimitExceeded.class, () -> new Mp4Demuxer(MediaSource.of(data), options));
    }

    @Test
    @DisplayName("rejects an encrypted entry without keys as unsupported")
    void encrypted() {
        var protection = Mp4Builder.sinf("sowt", "cbcs", Mp4Builder.tenc(1, 0, 0, true, 0, new byte[16], new byte[16]));
        assertThrows(MediaException.Unsupported.class,
                () -> new Mp4Demuxer(MediaSource.of(file(entry("enca", 2, 16, 44100, protection), 44100))).close());
        assertThrows(MediaException.Malformed.class,
                () -> new Mp4Demuxer(MediaSource.of(file(entry("enca", 2, 16, 44100), 44100))).close());
    }

    @Test
    @DisplayName("reports no layout for an entry without channels")
    void noChannels() {
        try (var demuxer = new Mp4Demuxer(MediaSource.of(file(entry("sowt", 0, 16, 8000), 8000)))) {
            var track = assertInstanceOf(AudioTrack.class, demuxer.tracks().getFirst());
            assertEquals(Optional.empty(), track.layout());
        }
    }
}
