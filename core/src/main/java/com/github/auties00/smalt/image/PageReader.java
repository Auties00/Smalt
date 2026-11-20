package com.github.auties00.smalt.image;

import com.github.auties00.smalt.MediaException;

import java.util.List;

/**
 * Reads the pages of a multi-image file, such as the directories of a multi-page TIFF, the
 * entries of an icon or the master images of a HEIF file, in any order.
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
     * Returns the groups of related pages the file declares, such as the left and right views of a stereo pair.
     *
     * <p>A page may belong to several groups or to none. The groups are known when the reader opens and do not
     * change.
     *
     * @implSpec The default implementation returns an empty list.
     *
     * @return the groups in the order the file declares them, unmodifiable, empty when the file declares none
     */
    default List<PageGroup> groups() {
        return List.of();
    }

    /**
     * Releases the resources of the reader; the source it reads is not closed.
     *
     * @implSpec The default implementation does nothing.
     */
    @Override
    default void close() {
    }
}
