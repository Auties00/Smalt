package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.image.PageWriter;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.ico.IcoEncoder;
import com.github.auties00.smalt.image.ico.IcoEncoderOptions;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.internal.check.LosslessDepth;
import com.github.auties00.smalt.io.MediaSink;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Writes the entries of an icon through {@link IcoEncoder}, one entry per page, with
 * {@link IcoEncoderOptions#defaults()}.
 *
 * <p>The icon directory precedes the entries and records their sizes, so the entries are kept until
 * {@link #finish()} writes the whole file. A page larger than 256 pixels on a side is scaled into a
 * 256-pixel square entry, as an icon encoded from one picture is.
 */
final class IcoPageWriter implements PageWriter {
    /**
     * The most entries an icon directory holds.
     */
    private static final int MAX_ENTRIES = 0xFFFF;

    /**
     * The output.
     */
    private final MediaSink sink;

    /**
     * Whether pages are checked to fit 8 bits per sample.
     */
    private final boolean lossless;

    /**
     * The entries written so far.
     */
    private final List<RgbaPicture> entries = new ArrayList<>();

    /**
     * Whether {@link #finish()} ran.
     */
    private boolean finished;

    /**
     * Constructs a writer.
     *
     * @param sink the output, written from its position; not closed
     * @param lossless whether a page with more bits per sample than an icon holds is rejected
     */
    IcoPageWriter(MediaSink sink, boolean lossless) {
        this.sink = sink;
        this.lossless = lossless;
    }

    @Override
    public void write(Picture picture) {
        Objects.requireNonNull(picture, "picture");
        if (finished) {
            throw new IllegalStateException("the icon file is finished");
        }
        if (entries.size() == MAX_ENTRIES) {
            throw new IllegalArgumentException("an icon file holds at most " + MAX_ENTRIES + " entries");
        }
        if (lossless) {
            picture = LosslessDepth.fit(picture, 8, "ico");
        }
        var rgba = ImageOps.toRgba(picture);
        if (rgba.width() > 256 || rgba.height() > 256) {
            rgba = (RgbaPicture) ImageOps.fitInto(rgba, 256, 256, ResizeFilter.AREA, 0);
        }
        entries.add(rgba);
    }

    @Override
    public int pageCount() {
        return entries.size();
    }

    @Override
    public void finish() {
        if (finished) {
            throw new IllegalStateException("the icon file is already finished");
        }
        if (entries.isEmpty()) {
            throw new IllegalStateException("an icon file needs at least one entry");
        }
        finished = true;
        IcoEncoder.encode(entries, IcoEncoderOptions.defaults(), sink);
        entries.clear();
    }

    @Override
    public void close() {
        entries.clear();
    }
}
