package com.github.auties00.smalt.image;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The channels of a {@link ChannelPicture}, in the order their samples are interleaved.
 *
 * <p>A layout starts with the color channels of its {@link ColorModel}, in canonical order: one
 * {@link ChannelType#GRAY}; {@link ChannelType#RED}, {@link ChannelType#GREEN}, {@link ChannelType#BLUE};
 * {@link ChannelType#CYAN}, {@link ChannelType#MAGENTA}, {@link ChannelType#YELLOW}, {@link ChannelType#BLACK};
 * or one or more {@link ChannelType#INK} separations. Any number of extra channels follow, at most one of them
 * an alpha channel ({@link ChannelType#isAlpha()}). Two layouts are equal when their channels are, names
 * included.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param channels the channels, color channels first; copied, at least one
 */
public record PixelLayout(List<Channel> channels) {
    /**
     * One gray channel.
     */
    public static final PixelLayout GRAY = of(Channel.of(ChannelType.GRAY));

    /**
     * Gray and straight alpha.
     */
    public static final PixelLayout GRAY_ALPHA = GRAY.withChannel(Channel.of(ChannelType.ALPHA));

    /**
     * Red, green and blue.
     */
    public static final PixelLayout RGB = of(Channel.of(ChannelType.RED), Channel.of(ChannelType.GREEN),
            Channel.of(ChannelType.BLUE));

    /**
     * Red, green, blue and straight alpha.
     */
    public static final PixelLayout RGBA = RGB.withChannel(Channel.of(ChannelType.ALPHA));

    /**
     * Cyan, magenta, yellow and black.
     */
    public static final PixelLayout CMYK = of(Channel.of(ChannelType.CYAN), Channel.of(ChannelType.MAGENTA),
            Channel.of(ChannelType.YELLOW), Channel.of(ChannelType.BLACK));

    /**
     * Cyan, magenta, yellow, black and straight alpha.
     */
    public static final PixelLayout CMYKA = CMYK.withChannel(Channel.of(ChannelType.ALPHA));

    /**
     * Validates the layout.
     *
     * @throws IllegalArgumentException if the list is empty, does not start with the color channels of a
     * color model in canonical order, has a color channel after an extra channel, or has more than one alpha
     * channel
     */
    public PixelLayout {
        channels = List.copyOf(channels);
        if (channels.isEmpty()) {
            throw new IllegalArgumentException("a channel layout needs at least one channel");
        }
        var colors = 0;
        while (colors < channels.size() && channels.get(colors).type().isColor()) {
            colors++;
        }
        if (colors == 0) {
            throw new IllegalArgumentException("a channel layout must start with its color channels: " + channels);
        }
        modelOf(channels, colors);
        var alphas = 0;
        for (var i = colors; i < channels.size(); i++) {
            var type = channels.get(i).type();
            if (type.isColor()) {
                throw new IllegalArgumentException("color channel " + type + " follows an extra channel");
            }
            if (type.isAlpha()) {
                alphas++;
            }
        }
        if (alphas > 1) {
            throw new IllegalArgumentException("a channel layout holds at most one alpha channel: " + channels);
        }
    }

    /**
     * Returns the layout of a list of channels.
     *
     * @param channels the channels, color channels first
     * @return the layout
     * @throws IllegalArgumentException if the channels do not form a valid layout, as the canonical
     * constructor describes
     */
    public static PixelLayout of(Channel... channels) {
        return new PixelLayout(List.of(channels));
    }

    /**
     * Returns the layout of named {@link ChannelType#INK} separations, without extra channels.
     *
     * @param names the name of each ink in channel order, an empty string for an unnamed one; at least one
     * @return the layout
     * @throws NullPointerException if a name is {@code null}
     * @throws IllegalArgumentException if {@code names} is empty
     */
    public static PixelLayout ofInks(List<String> names) {
        var channels = new ArrayList<Channel>(names.size());
        for (var name : names) {
            channels.add(Channel.ofInk(name));
        }
        return new PixelLayout(channels);
    }

    /**
     * Returns this layout with one more extra channel at the end.
     *
     * @param channel the extra channel
     * @return the new layout
     * @throws IllegalArgumentException if {@code channel} is a color channel, or is an alpha channel and this
     * layout already has one
     */
    public PixelLayout withChannel(Channel channel) {
        Objects.requireNonNull(channel, "channel");
        var list = new ArrayList<>(channels);
        list.add(channel);
        return new PixelLayout(list);
    }

    /**
     * Returns this layout with its extra channels removed.
     *
     * @return the layout of the color channels alone, or this layout when it has no extra channels
     */
    public PixelLayout colorOnly() {
        var colors = colorChannels();
        return colors == channels.size() ? this : new PixelLayout(channels.subList(0, colors));
    }

    /**
     * {@return the number of channels, the samples per pixel}
     */
    public int count() {
        return channels.size();
    }

    /**
     * Returns one channel.
     *
     * @param index the channel index, from 0 to {@link #count()} minus 1
     * @return the channel
     * @throws IndexOutOfBoundsException if {@code index} is negative or not less than {@link #count()}
     */
    public Channel channel(int index) {
        return channels.get(index);
    }

    /**
     * {@return the color model of the leading color channels}
     */
    public ColorModel colorModel() {
        return modelOf(channels, colorChannels());
    }

    /**
     * {@return the number of leading color channels: 1 for gray, 3 for RGB, 4 for CMYK, the ink count for
     * inks}
     */
    public int colorChannels() {
        var colors = 0;
        while (colors < channels.size() && channels.get(colors).type().isColor()) {
            colors++;
        }
        return colors;
    }

    /**
     * Returns the index of the first channel of a type.
     *
     * @param type the channel type
     * @return the index, or {@code -1} when no channel has the type
     */
    public int indexOf(ChannelType type) {
        Objects.requireNonNull(type, "type");
        for (var i = 0; i < channels.size(); i++) {
            if (channels.get(i).type() == type) {
                return i;
            }
        }
        return -1;
    }

    /**
     * {@return the index of the alpha channel, straight or premultiplied, or {@code -1} when there is none}
     */
    public int alphaIndex() {
        for (var i = 0; i < channels.size(); i++) {
            if (channels.get(i).type().isAlpha()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * {@return whether the layout has an alpha channel}
     */
    public boolean hasAlpha() {
        return alphaIndex() >= 0;
    }

    /**
     * {@return whether the layout's alpha channel is {@link ChannelType#PREMULTIPLIED_ALPHA}}
     */
    public boolean isAlphaPremultiplied() {
        var index = alphaIndex();
        return index >= 0 && channels.get(index).type() == ChannelType.PREMULTIPLIED_ALPHA;
    }

    /**
     * Identifies the color model of the leading color channels.
     *
     * @param channels the channels, color channels first
     * @param colors the number of leading color channels, positive
     * @return the color model
     * @throws IllegalArgumentException if the color channels form no color model
     */
    private static ColorModel modelOf(List<Channel> channels, int colors) {
        var first = channels.getFirst().type();
        var model = switch (first) {
            case GRAY -> colors == 1 ? ColorModel.GRAY : null;
            case RED -> colors == 3 && channels.get(1).type() == ChannelType.GREEN
                    && channels.get(2).type() == ChannelType.BLUE ? ColorModel.RGB : null;
            case CYAN -> colors == 4 && channels.get(1).type() == ChannelType.MAGENTA
                    && channels.get(2).type() == ChannelType.YELLOW
                    && channels.get(3).type() == ChannelType.BLACK ? ColorModel.CMYK : null;
            case INK -> allInks(channels, colors) ? ColorModel.INK : null;
            default -> null;
        };
        if (model == null) {
            throw new IllegalArgumentException("the color channels " + channels.subList(0, colors)
                    + " form no color model");
        }
        return model;
    }

    /**
     * Returns whether the leading color channels are all inks.
     *
     * @param channels the channels, color channels first
     * @param colors the number of leading color channels
     * @return whether every one is {@link ChannelType#INK}
     */
    private static boolean allInks(List<Channel> channels, int colors) {
        for (var i = 0; i < colors; i++) {
            if (channels.get(i).type() != ChannelType.INK) {
                return false;
            }
        }
        return true;
    }
}
