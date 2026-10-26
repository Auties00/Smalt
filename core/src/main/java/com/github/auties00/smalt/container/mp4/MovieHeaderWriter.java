package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.internal.subtitle.bitmap.VobSubHeader;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.codec.CodecConfigs;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.function.Consumer;

/**
 * Writes the movie and track header boxes that {@link Mp4Muxer} and {@link FragmentedMp4Muxer}
 * share ({@code mvhd}, {@code tkhd}, {@code mdhd}, {@code hdlr}, the media information header and
 * {@code dinf}, ISO/IEC 14496-12 clause 8), and checks the codec configuration a track needs.
 *
 * <p>Creation and modification times are zero, and a duration field is 64 bits wide only when its
 * value does not fit 32 bits.
 */
final class MovieHeaderWriter {
    private static final long U32_MAX = 0xFFFFFFFFL;

    /**
     * The packed ISO 639-2 code {@code und}.
     */
    private static final int LANGUAGE_UNDETERMINED = 0x55C4;

    private MovieHeaderWriter() {
        throw new AssertionError();
    }

    /**
     * Checks that a track carries the configuration its codec needs and that the configuration
     * parses.
     *
     * <p>An AMR track must have one channel, since the {@code samr} and {@code sawb} sample
     * entries of 3GPP TS 26.244 carry a single channel and a multichannel track holds frame-blocks.
     *
     * @param track the track to check
     * @throws IllegalArgumentException if an H.264, HEVC, AAC, Opus, ALAC or FLAC track has no
     * configuration, a TTML configuration is malformed, a DNxHD configuration carries bytes
     * after its sample entry type that are not Avid sample description extensions, or an AMR track
     * has more than one channel
     * @throws com.github.auties00.smalt.MediaException.Malformed if an AAC, Opus or FLAC
     * configuration is invalid
     */
    static void validateConfig(Track track) {
        var codec = track.codec();
        switch (codec) {
            case H264, HEVC, AAC, OPUS, ALAC, FLAC -> {
                if (track.codecConfig().isEmpty() || track.codecConfig().get().length == 0) {
                    throw new IllegalArgumentException(codec.displayName() + " track " + track.id()
                            + " needs its codec configuration");
                }
            }
            default -> {
            }
        }
        if ((codec == Codec.AMR_NB || codec == Codec.AMR_WB) && track instanceof AudioTrack audio) {
            var channels = audio.layout().map(ChannelLayout::channels).orElse(1);
            if (channels > 1) {
                throw new IllegalArgumentException("an AMR sample entry carries one channel, not " + channels);
            }
        }
        if (codec == Codec.TTML) {
            track.codecConfig().filter(config -> config.length > 0).ifPresent(TtmlEntries::validate);
        } else if (codec == Codec.OPUS) {
            AudioConfigs.parseOpusHead(track.codecConfig().orElseThrow());
        } else if (codec == Codec.AAC) {
            AudioConfigs.parseAac(track.codecConfig().orElseThrow());
        } else if (codec == Codec.FLAC) {
            AudioConfigs.parseFlac(track.codecConfig().orElseThrow());
        } else if (codec == Codec.DNXHD) {
            track.codecConfig().filter(config -> config.length > 4).ifPresent(CodecConfigs::dnxhdExtensions);
        }
    }

    /**
     * Writes a {@code mvhd} box with the identity matrix, a rate of 1.0 and full volume.
     *
     * @param writer the destination
     * @param timescale the movie timescale, in units per second
     * @param duration the movie duration, in movie timescale units
     * @param nextTrackId the {@code next_track_ID}, one more than the largest track number
     */
    static void writeMovieHeader(BoxWriter writer, long timescale, long duration, int nextTrackId) {
        var wide = duration > U32_MAX;
        writer.beginFull(BoxTypes.MVHD, wide ? 1 : 0, 0);
        writer.zeros(wide ? 16 : 8);
        writer.u32(timescale);
        writeLength(writer, duration, wide);
        writer.u32(0x00010000);
        writer.u16(0x0100);
        writer.zeros(10);
        writeMatrix(writer, DisplayMatrix.of(Orientation.NORMAL, 0, 0));
        writer.zeros(24);
        writer.u32(nextTrackId);
        writer.end();
    }

    /**
     * Writes the {@code tkhd} box of an enabled track that is in the movie and in its preview.
     *
     * <p>An audio track gets full volume; a video track gets its dimensions and the matrix of its
     * orientation.
     *
     * @param writer the destination
     * @param track the track the box describes
     * @param duration the track duration, in movie timescale units
     */
    static void writeTrackHeader(BoxWriter writer, MuxTrack track, long duration) {
        writeTrackHeader(writer, track, duration, true);
    }

    /**
     * Writes the {@code tkhd} box of a track that is in the movie and in its preview, enabled or not.
     *
     * <p>An audio track gets full volume; a video track gets its dimensions and the matrix of its
     * orientation.
     *
     * @param writer the destination
     * @param track the track the box describes
     * @param duration the track duration, in movie timescale units
     * @param enabled whether the track is enabled, the track a player presents when the user expressed no preference
     */
    static void writeTrackHeader(BoxWriter writer, MuxTrack track, long duration, boolean enabled) {
        var audio = track.kind() == TrackKind.AUDIO;
        var wide = duration > U32_MAX;
        writer.beginFull(BoxTypes.TKHD, wide ? 1 : 0, enabled ? 3 : 2);
        writer.zeros(wide ? 16 : 8);
        writer.u32(track.number);
        writer.u32(0);
        writeLength(writer, duration, wide);
        writer.zeros(8);
        writer.u16(0);
        writer.u16(audio ? 1 : 0);
        writer.u16(audio ? 0x0100 : 0);
        writer.u16(0);
        var width = 0;
        var height = 0;
        var matrix = DisplayMatrix.of(Orientation.NORMAL, 0, 0);
        if (track.track instanceof VideoTrack video) {
            width = video.width();
            height = video.height();
            matrix = DisplayMatrix.of(video.orientation(), width, height);
        } else if (track.codec() == Codec.DVD_SUBTITLE) {
            var header = VobSubHeader.parse(track.track.codecConfig().orElse(null));
            width = header.width() > 0 ? header.width() : 720;
            height = header.height() > 0 ? header.height() : 480;
        }
        writeMatrix(writer, matrix);
        writer.u32((long) width << 16);
        writer.u32((long) height << 16);
        writer.end();
    }

    /**
     * {@return the handler type of a text track}
     *
     * @param codec the codec of the track
     */
    private static int textHandler(Codec codec) {
        return switch (codec) {
            case MOV_TEXT -> BoxTypes.SBTL;
            case TTML -> BoxTypes.SUBT;
            case DVD_SUBTITLE -> BoxTypes.SUBP;
            case CEA_608, CEA_708 -> BoxTypes.CLCP;
            default -> BoxTypes.TEXT;
        };
    }

    /**
     * Writes the {@code mdia} box of a track: {@code mdhd}, {@code hdlr} and a {@code minf} box
     * holding the media information header, a self-contained {@code dinf} and the sample table.
     *
     * <p>The handler is {@code soun} for audio, {@code sbtl} for {@link Codec#MOV_TEXT}, {@code subt}
     * (with a {@code sthd} media header) for {@link Codec#TTML}, {@code subp} for {@link Codec#DVD_SUBTITLE},
     * {@code clcp} for the closed caption codecs, {@code text} for other text tracks and {@code vide} for video.
     *
     * @param writer the destination
     * @param track the track the box describes
     * @param duration the media duration, in media timescale units
     * @param sampleTable writes the {@code stbl} box into the writer it receives
     */
    static void writeMedia(BoxWriter writer, MuxTrack track, long duration, Consumer<BoxWriter> sampleTable) {
        var audio = track.kind() == TrackKind.AUDIO;
        var text = track.kind() == TrackKind.DATA;
        writer.begin(BoxTypes.MDIA);
        var wide = duration > U32_MAX;
        writer.beginFull(BoxTypes.MDHD, wide ? 1 : 0, 0);
        writer.zeros(wide ? 16 : 8);
        writer.u32(track.timescale);
        writeLength(writer, duration, wide);
        writer.u16(packLanguage(track.track.language().orElse(null)));
        writer.u16(0);
        writer.end();
        writer.beginFull(BoxTypes.HDLR, 0, 0);
        writer.u32(0);
        writer.u32(audio ? BoxTypes.SOUN : text ? textHandler(track.codec()) : BoxTypes.VIDE);
        writer.zeros(12);
        writer.cString(audio ? "SoundHandler" : text ? "SubtitleHandler" : "VideoHandler");
        writer.end();
        writer.begin(BoxTypes.MINF);
        if (audio) {
            writer.beginFull(BoxTypes.SMHD, 0, 0);
            writer.u32(0);
        } else if (text) {
            writer.beginFull(track.codec() == Codec.TTML ? BoxTypes.STHD : BoxTypes.NMHD, 0, 0);
        } else {
            writer.beginFull(BoxTypes.VMHD, 0, 1);
            writer.zeros(8);
        }
        writer.end();
        writer.begin(BoxTypes.DINF);
        writer.beginFull(BoxTypes.DREF, 0, 0);
        writer.u32(1);
        writer.beginFull(BoxTypes.URL, 0, 1);
        writer.end();
        writer.end();
        writer.end();
        sampleTable.accept(writer);
        writer.end();
        writer.end();
    }

    /**
     * Writes a duration field.
     *
     * @param writer the destination
     * @param value the duration, or -1 for the empty edit's media time
     * @param wide whether the field is 64 bits wide
     */
    static void writeLength(BoxWriter writer, long value, boolean wide) {
        if (wide) {
            writer.u64(value);
        } else {
            writer.u32(value);
        }
    }

    /**
     * Writes a transformation matrix.
     *
     * @param writer the destination
     * @param matrix the nine elements, in 16.16 and 2.30 fixed point
     */
    private static void writeMatrix(BoxWriter writer, int[] matrix) {
        for (var value : matrix) {
            writer.u32(value & U32_MAX);
        }
    }

    /**
     * Packs an ISO 639-2 language code into the 15 bits of {@code mdhd}.
     *
     * @param language the three-letter lowercase code, or {@code null}
     * @return the packed code, {@code und} when the language is absent or not three lowercase
     * letters
     */
    static int packLanguage(String language) {
        if (language == null || language.length() != 3) {
            return LANGUAGE_UNDETERMINED;
        }
        var packed = 0;
        for (var index = 0; index < 3; index++) {
            var value = language.charAt(index) - 0x60;
            if (value < 1 || value > 26) {
                return LANGUAGE_UNDETERMINED;
            }
            packed = (packed << 5) | value;
        }
        return packed;
    }
}
