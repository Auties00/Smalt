package com.github.auties00.smalt.image;

import java.util.List;
import java.util.Objects;

/**
 * A group of pages that a multi-image file declares related, such as the two views of a stereo pair or the shots
 * of a burst.
 *
 * <p>The type is the grouping type the file stores. HEIF files (ISO/IEC 23008-12) declare their groups as entity
 * groups, for example:
 * <ul>
 *   <li>{@code "ster"}, a stereo pair: exactly two pages, the left view first and the right view second;</li>
 *   <li>{@code "brst"}, a burst of images captured one after the other, in capture order;</li>
 *   <li>{@code "tsyn"}, images captured at the same time;</li>
 *   <li>{@code "albc"} and {@code "favc"}, an album and a favorites collection;</li>
 *   <li>{@code "aebr"}, {@code "wbbr"}, {@code "fobr"}, {@code "afbr"} and {@code "dobr"}, exposure, white
 *       balance, focus, flash exposure and depth of field bracketing;</li>
 *   <li>{@code "pano"} and {@code "slid"}, the images of a panorama and of a slideshow.</li>
 * </ul>
 * Groups of other types are reported with their own code.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param type the grouping type, a four-character code such as {@code "ster"}
 * @param pages the indices of the pages in the group, in the order the file lists them, each at most once
 */
public record PageGroup(String type, List<Integer> pages) {
    /**
     * Validates the group and copies its page list.
     *
     * @throws NullPointerException if {@code type}, {@code pages} or one of its elements is {@code null}
     * @throws IllegalArgumentException if {@code pages} is empty, holds a negative index or holds an index twice
     */
    public PageGroup {
        Objects.requireNonNull(type, "type");
        pages = List.copyOf(pages);
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("a page group holds at least one page");
        }
        if (pages.stream().anyMatch(page -> page < 0)) {
            throw new IllegalArgumentException("negative page index in " + pages);
        }
        if (pages.stream().distinct().count() != pages.size()) {
            throw new IllegalArgumentException("repeated page index in " + pages);
        }
    }
}
