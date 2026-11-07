package com.github.auties00.smalt.image.exr.internal;

import java.util.List;

/**
 * Describes the pixels of one chunk: the rectangle it covers and, for each channel, the samples it holds.
 *
 * <p>An uncompressed chunk stores its lines from the top; each line holds, for each channel in name order that
 * samples the line, the channel's samples of the line, little-endian.
 */
public final class ExrBlock {
    private final List<ExrHeader.Channel> channels;

    private final int x;

    private final int y;

    private final int width;

    private final int height;

    private final int[] widths;

    private final int[] heights;

    /**
     * Describes a chunk.
     *
     * @param channels the channels of the part, sorted by name
     * @param x the left column of the chunk
     * @param y the top row of the chunk
     * @param width the columns of the chunk
     * @param height the rows of the chunk
     */
    public ExrBlock(List<ExrHeader.Channel> channels, int x, int y, int width, int height) {
        this.channels = channels;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.widths = new int[channels.size()];
        this.heights = new int[channels.size()];
        for (var index = 0; index < channels.size(); index++) {
            var channel = channels.get(index);
            widths[index] = ExrHeader.samples(channel.xSampling(), x, width);
            heights[index] = ExrHeader.samples(channel.ySampling(), y, height);
        }
    }

    /**
     * {@return the channels, sorted by name}
     */
    public List<ExrHeader.Channel> channels() {
        return channels;
    }

    /**
     * {@return the left column}
     */
    public int x() {
        return x;
    }

    /**
     * {@return the top row}
     */
    public int y() {
        return y;
    }

    /**
     * {@return the columns}
     */
    public int width() {
        return width;
    }

    /**
     * {@return the rows}
     */
    public int height() {
        return height;
    }

    /**
     * {@return the samples of a channel in each line it samples}
     *
     * @param channel the channel index
     */
    public int channelWidth(int channel) {
        return widths[channel];
    }

    /**
     * {@return the lines of the chunk a channel samples}
     *
     * @param channel the channel index
     */
    public int channelHeight(int channel) {
        return heights[channel];
    }

    /**
     * {@return whether a channel samples a row}
     *
     * @param channel the channel index
     * @param row the row, in data window coordinates
     */
    public boolean samples(int channel, int row) {
        return Math.floorMod(row, channels.get(channel).ySampling()) == 0;
    }

    /**
     * {@return the bytes of the chunk once uncompressed}
     */
    public long bytes() {
        var total = 0L;
        for (var index = 0; index < channels.size(); index++) {
            total += (long) widths[index] * heights[index] * channels.get(index).bytes();
        }
        return total;
    }
}
