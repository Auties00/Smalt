package com.github.auties00.smalt.image;

import com.github.auties00.smalt.MediaException;

/**
 * Encodes the pages of a multi-image file one by one into a sink, the writing counterpart of
 * {@link PageReader}: the directories of a multi-page TIFF, or the entries of an icon.
 *
 * <p>Pages are independent images, written in order through {@link #write(Picture)} or
 * {@link #write(DecodedImage)}; they may differ in size, depth and color type. {@link #finish()}
 * completes the file and writes whatever the format holds back to the sink; an instance that is
 * closed without finishing leaves the output incomplete, so it is typically used as:
 * {@snippet :
 * try (var writer = Images.openPageWriter(ImageFormat.TIFF, sink)) {
 *     for (var index = 0; index < reader.pageCount(); index++) {
 *         writer.write(reader.read(index));
 *     }
 *     writer.finish();
 * }
 * }
 *
 * <p>Implementations must reject a write or a finish after {@link #finish()} with
 * {@link IllegalStateException}, a page the format cannot hold with {@link IllegalArgumentException}
 * before writing any of it, and must translate output failures into {@link MediaException.Io}.
 *
 * <p>An instance is single-use and not thread-safe.
 */
public interface PageWriter extends AutoCloseable {
    /**
     * Adds a page.
     *
     * @param picture the page
     * @throws IllegalArgumentException if the format cannot hold the page
     * @throws IllegalStateException if the writer is finished
     * @throws MediaException.Unsupported if the page cannot be written under the writer's options (a
     * lossless writer given more bits per sample than the format holds)
     * @throws MediaException.LimitExceeded if the file would exceed what the format addresses
     * @throws MediaException.Io if writing the sink fails
     */
    void write(Picture picture);

    /**
     * Adds a decoded page, with the metadata the format can carry: its orientation, color profile,
     * EXIF metadata and XMP packet.
     *
     * @implSpec The default implementation calls {@link #write(Picture)} with
     * {@link DecodedImage#picture()}, dropping the metadata.
     *
     * @param page the decoded page, its picture and metadata
     * @throws IllegalArgumentException if the format cannot hold the page
     * @throws IllegalStateException if the writer is finished
     * @throws MediaException.Unsupported if the page cannot be written under the writer's options
     * @throws MediaException.LimitExceeded if the file would exceed what the format addresses
     * @throws MediaException.Io if writing the sink fails
     */
    default void write(DecodedImage page) {
        write(page.picture());
    }

    /**
     * {@return the number of pages written so far}
     */
    int pageCount();

    /**
     * Completes the file and writes the rest of it to the sink.
     *
     * @throws IllegalStateException if no page was written or the writer is already finished
     * @throws MediaException.Io if writing the sink fails
     */
    void finish();

    /**
     * Releases the writer's resources; the sink is not closed, and an unfinished file is left
     * incomplete.
     */
    @Override
    void close();
}
