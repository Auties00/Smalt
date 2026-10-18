package com.github.auties00.smalt.image;

import com.github.auties00.smalt.MediaException;

/**
 * Reads the pages of a multi-image file, such as the directories of a multi-page TIFF or the
 * entries of an icon, in any order.
 *
 * <p>Pages are independent images: unlike the frames of an {@link AnimationReader}, they have no
 * timing, are not composited onto a shared canvas, and may differ in size, depth and color type.
 * {@link #pageCount()} is known when the reader opens, and {@link #read(int)} decodes any page,
 * any number of times, in any order. An image whose format holds a single image has one page.
 *
 * <p>A reader is not thread-safe.
 *
 * @implSpec
 * Implementations must check every page against their limits before allocating it, must count
 * pages against the frame limit when opening, and must raise {@link MediaException.Malformed} for
 * a corrupt page.
 */
public interface PageReader extends AutoCloseable {
    /**
     * Returns the number of pages.
     *
     * @return the page count, at least one
     */
    int pageCount();

    /**
     * Summarizes a page from its headers without decoding its pixels.
     *
     * @param index the page index, from zero to {@link #pageCount()} minus one
     * @return the summary; its {@link ImageInfo#frameCount()} is the page count of the file
     * @throws IndexOutOfBoundsException  if {@code index} is out of range
     * @throws MediaException.Unsupported if the page uses a feature the decoder lacks
     * @throws MediaException.Malformed   if the page headers are corrupt
     * @throws MediaException.Io          if reading the source fails
     */
    ImageInfo probe(int index);

    /**
     * Decodes a page.
     *
     * @param index the page index, from zero to {@link #pageCount()} minus one
     * @return the decoded page, in its format's native picture representation
     * @throws IndexOutOfBoundsException    if {@code index} is out of range
     * @throws MediaException.Unsupported   if the page uses a feature the decoder lacks
     * @throws MediaException.Malformed     if the page is corrupt
     * @throws MediaException.LimitExceeded if the page exceeds the limits
     * @throws MediaException.Io            if reading the source fails
     */
    DecodedImage read(int index);

    /**
     * Releases the resources of the reader; the source it reads is not closed.
     *
     * @implSpec The default implementation does nothing.
     */
    @Override
    default void close() {
    }
}
