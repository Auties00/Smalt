package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.caption.CcData;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.nal.NalRecords;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;

import java.io.ByteArrayOutputStream;
import java.util.Objects;

/**
 * Reads the closed captions that video bitstreams carry.
 *
 * <p>ATSC A/53 caption data, the {@code cc_data} constructs of CEA-708 with the CEA-608 compatibility bytes, travels
 * inside the video: in the {@code user_data_registered_itu_t_t35} SEI messages of H.264 (ATSC A/72) and HEVC, in the
 * user data of MPEG-1 and MPEG-2 pictures (ATSC A/53 Part 4) and in the ITU-T T.35 metadata OBUs of AV1, each as an
 * ATSC {@code GA94} structure. {@link #ccData(Track, Packet)} reads one packet's; a {@link CaptionExtractor} turns a
 * whole video track into a {@link Codec#CEA_708} track in presentation order, which
 * {@link SubtitleDecoder} decodes.
 *
 * <p>This class is thread-safe.
 */
public final class ClosedCaptions {
    private ClosedCaptions() {
        throw new AssertionError();
    }

    /**
     * Returns whether video of a codec can carry closed captions this class reads.
     *
     * @param codec the video codec
     * @return {@code true} for {@link Codec#H264}, {@link Codec#HEVC}, {@link Codec#MPEG1_VIDEO},
     * {@link Codec#MPEG2_VIDEO} and {@link Codec#AV1}
     */
    public static boolean carries(Codec codec) {
        return switch (Objects.requireNonNull(codec, "codec")) {
            case H264, HEVC, MPEG1_VIDEO, MPEG2_VIDEO, AV1 -> true;
            default -> false;
        };
    }

    /**
     * Returns the caption data of one video packet.
     *
     * <p>H.264 and HEVC packets are read as length-prefixed NAL units with the length size of the track's
     * configuration (four bytes without one), or as an Annex B byte stream when they start with a start code. The
     * constructs of every caption structure of the packet are concatenated in bitstream order; a structure of
     * another kind (bar data, another provider's T.35 payload) adds nothing.
     *
     * @param track the video track
     * @param packet a packet of the track
     * @return the {@code cc_data} constructs, three bytes each as {@link Codec#CEA_708} packets hold them; empty
     * when the packet carries no captions or the codec cannot
     * @throws MediaException.Malformed if the packet's structure is corrupt beyond what the reader skips
     */
    public static byte[] ccData(Track track, Packet packet) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(packet, "packet");
        return Guards.malformed("closed captions", () -> read(track, packet));
    }

    /**
     * Reads the caption data of one video packet, unguarded.
     *
     * @param track the video track
     * @param packet the video packet carrying the captions
     * @return the constructs
     */
    private static byte[] read(Track track, Packet packet) {
        var data = packet.data();
        var out = new ByteArrayOutputStream();
        switch (track.codec()) {
            case H264, HEVC -> {
                var hevc = track.codec() == Codec.HEVC;
                NalUnits.Sink sink = (unit, offset, length) -> {
                    if (length > 0 && isSei(unit[offset], hevc)) {
                        CcData.sei(unit, offset, length, hevc, out);
                    }
                };
                if (NalUnits.looksLikeAnnexB(data)) {
                    NalUnits.forEachAnnexB(data, sink);
                } else {
                    NalUnits.forEachLengthPrefixed(data, lengthSize(track, hevc), sink);
                }
            }
            case MPEG1_VIDEO, MPEG2_VIDEO -> CcData.mpegVideo(data, out);
            case AV1 -> CcData.av1(data, out);
            default -> {
            }
        }
        return out.toByteArray();
    }

    /**
     * Returns the {@link Codec#CEA_708} track the captions of a video track make.
     *
     * @param video the video track
     * @return a data track with the video track's identifier and time base
     * @throws IllegalArgumentException if {@code video} is not a video track
     */
    public static DataTrack track(Track video) {
        Objects.requireNonNull(video, "video");
        if (video.kind() != TrackKind.VIDEO) {
            throw new IllegalArgumentException("track " + video.id() + " is not a video track");
        }
        return DataTrack.builder().id(video.id()).codec(Codec.CEA_708).timeBase(video.timeBase())
                .duration(video.duration()).build();
    }

    private static boolean isSei(byte header, boolean hevc) {
        if (hevc) {
            var type = (header >> 1) & 0x3F;
            return type == 39 || type == 40;
        }
        return (header & 0x1F) == 6;
    }

    private static int lengthSize(Track track, boolean hevc) {
        var config = track.codecConfig().orElse(null);
        if (config == null) {
            return 4;
        }
        try {
            var parsed = hevc ? NalRecords.parseHevc(config) : NalRecords.parseAvc(config);
            return parsed == null ? 4 : parsed.lengthSize();
        } catch (RuntimeException exception) {
            return 4;
        }
    }
}
