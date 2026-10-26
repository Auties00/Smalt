package com.github.auties00.smalt.image;

import java.util.Objects;

/**
 * One channel of a {@link PixelLayout}: its meaning and, for an ink, spot or other extra channel, its name.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param type the meaning of the channel
 * @param name the name of the ink, spot color or extra channel as the file stores it, or the empty string when
 * it has none
 */
public record Channel(ChannelType type, String name) {
    /**
     * Validates the channel.
     */
    public Channel {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(name, "name");
    }

    /**
     * Returns an unnamed channel of a type.
     *
     * @param type the meaning of the channel
     * @return the channel, with an empty name
     */
    public static Channel of(ChannelType type) {
        return new Channel(type, "");
    }

    /**
     * Returns a named {@link ChannelType#INK} separation.
     *
     * @param name the name of the ink, for example {@code "PANTONE 185 C"}, or the empty string
     * @return the channel
     */
    public static Channel ofInk(String name) {
        return new Channel(ChannelType.INK, name);
    }

    /**
     * Returns a named {@link ChannelType#SPOT} color.
     *
     * @param name the name of the spot color, or the empty string
     * @return the channel
     */
    public static Channel ofSpot(String name) {
        return new Channel(ChannelType.SPOT, name);
    }
}
