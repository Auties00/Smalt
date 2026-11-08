package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Track;

import java.util.Locale;
import java.util.Objects;

/**
 * Names the text stream a caption decoder reads out of a caption track: a CEA-608 channel, a CEA-708 caption service
 * or a teletext page.
 *
 * <p>A CEA-608 channel ({@link #cea608(int)}) is one of the four caption channels CC1 to CC4 of line 21: CC1 and CC2
 * in field 1, CC3 and CC4 in field 2; CC1 carries the primary captions. A CEA-708 service ({@link #cea708(int)}) is
 * one of the 63 caption services of the digital caption channel; service 1 carries the primary captions. A teletext
 * page ({@link #teletext(int)}) is written as on screen, its three digits read as hexadecimal: page 888 is
 * {@code 0x888}, the first digit the magazine.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param kind the kind of stream
 * @param number the channel (1 to 4), the service (1 to 63) or the page ({@code 0x100} to {@code 0x8FF}, each later
 * digit {@code 0x0} to {@code 0xF})
 */
public record CaptionService(Kind kind, int number) {
    /**
     * Enumerates the kinds of caption streams.
     */
    public enum Kind {
        /**
         * A CEA-608 caption channel, read from {@link Codec#CEA_608} and {@link Codec#CEA_708} tracks.
         */
        CEA_608,

        /**
         * A CEA-708 caption service, read from {@link Codec#CEA_708} tracks.
         */
        CEA_708,

        /**
         * A teletext page, read from {@link Codec#DVB_TELETEXT} tracks.
         */
        TELETEXT
    }

    /**
     * Validates the service.
     *
     * @throws IllegalArgumentException if {@code number} is out of the range of its kind
     */
    public CaptionService {
        Objects.requireNonNull(kind, "kind");
        var valid = switch (kind) {
            case CEA_608 -> number >= 1 && number <= 4;
            case CEA_708 -> number >= 1 && number <= 63;
            case TELETEXT -> number >= 0x100 && number <= 0x8FF;
        };
        if (!valid) {
            throw new IllegalArgumentException("invalid " + kind.name().toLowerCase(Locale.ROOT) + " number "
                    + (kind == Kind.TELETEXT ? Integer.toHexString(number) : Integer.toString(number)));
        }
    }

    /**
     * Returns a CEA-608 caption channel.
     *
     * @param channel the channel, 1 to 4 for CC1 to CC4
     * @return the service
     * @throws IllegalArgumentException if {@code channel} is not from 1 to 4
     */
    public static CaptionService cea608(int channel) {
        return new CaptionService(Kind.CEA_608, channel);
    }

    /**
     * Returns a CEA-708 caption service.
     *
     * @param service the service number, 1 to 63
     * @return the service
     * @throws IllegalArgumentException if {@code service} is not from 1 to 63
     */
    public static CaptionService cea708(int service) {
        return new CaptionService(Kind.CEA_708, service);
    }

    /**
     * Returns a teletext page.
     *
     * @param page the page number with its digits as hexadecimal digits, {@code 0x100} to {@code 0x8FF}, for example
     * {@code 0x888}
     * @return the service
     * @throws IllegalArgumentException if {@code page} is out of range
     */
    public static CaptionService teletext(int page) {
        return new CaptionService(Kind.TELETEXT, page);
    }

    /**
     * Returns the service decoded from a track when the caller names none.
     *
     * <p>That is CC1 for {@link Codec#CEA_608} and {@link Codec#CEA_708} tracks, which carries the primary captions
     * and is present in every ATSC caption stream, and for a {@link Codec#DVB_TELETEXT} track the first subtitle
     * page (teletext type 2 or 5) its teletext descriptor lists, else its first page, else page 888.
     *
     * @param track the caption track
     * @return the service
     * @throws IllegalArgumentException if the track's codec is not a caption codec
     */
    public static CaptionService defaultFor(Track track) {
        Objects.requireNonNull(track, "track");
        return switch (track.codec()) {
            case CEA_608, CEA_708 -> cea608(1);
            case DVB_TELETEXT -> teletext(teletextPage(track.codecConfig().orElse(null)));
            default -> throw new IllegalArgumentException(track.codec().displayName() + " is not a caption codec");
        };
    }

    /**
     * Returns whether a track of a codec can carry this service.
     *
     * @param codec the codec of the track
     * @return {@code true} when the service can be read from the codec's packets
     */
    public boolean readableFrom(Codec codec) {
        return switch (kind) {
            case CEA_608 -> codec == Codec.CEA_608 || codec == Codec.CEA_708;
            case CEA_708 -> codec == Codec.CEA_708;
            case TELETEXT -> codec == Codec.DVB_TELETEXT;
        };
    }

    /**
     * Returns the default page of a teletext descriptor body.
     *
     * @param config the descriptor body, five bytes per page, or {@code null}
     * @return the page number
     */
    static int teletextPage(byte[] config) {
        if (config == null || config.length < 5) {
            return 0x888;
        }
        for (var offset = 0; offset + 5 <= config.length; offset += 5) {
            var type = (config[offset + 3] & 0xFF) >> 3;
            if (type == 2 || type == 5) {
                return pageOf(config, offset);
            }
        }
        return pageOf(config, 0);
    }

    /**
     * Returns the page of one teletext descriptor entry.
     */
    private static int pageOf(byte[] config, int offset) {
        var magazine = config[offset + 3] & 7;
        return (magazine == 0 ? 8 : magazine) << 8 | (config[offset + 4] & 0xFF);
    }

    @Override
    public String toString() {
        return switch (kind) {
            case CEA_608 -> "CC" + number;
            case CEA_708 -> "service " + number;
            case TELETEXT -> "teletext page " + Integer.toHexString(number).toUpperCase(Locale.ROOT);
        };
    }
}
