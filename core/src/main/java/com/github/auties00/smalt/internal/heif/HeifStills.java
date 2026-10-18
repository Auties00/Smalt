package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.io.MediaSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Shares the steps of still image reconstruction that do not depend on the codec of the coded
 * items: size conversion, grid payloads and alpha discovery.
 *
 * <p>Messages start with the format name the caller passes, such as {@code "avif"} or
 * {@code "heic"}. This class is thread-safe.
 */
public final class HeifStills {
    /**
     * The largest {@code grid} item payload: the 12 bytes of an image grid with 32-bit sizes.
     */
    public static final int MAX_GRID_PAYLOAD = 12;

    /**
     * Prevents instantiation.
     */
    private HeifStills() {
        throw new AssertionError();
    }

    /**
     * Converts a declared size to an {@code int}.
     *
     * @param format the format name, for messages
     * @param size the size, an unsigned 32-bit value
     * @return the size
     * @throws MediaException.Malformed if the size is not positive
     * @throws MediaException.LimitExceeded if the size exceeds the largest {@code int}
     */
    public static int checkedSize(String format, long size) {
        if (size <= 0) {
            throw new MediaException.Malformed(format + ": invalid size " + size);
        }
        if (size > Integer.MAX_VALUE) {
            throw new MediaException.LimitExceeded(format + ": size " + size + " exceeds the largest picture");
        }
        return (int) size;
    }

    /**
     * Reads and parses the payload of a {@code grid} item.
     *
     * @param format the format name, for messages
     * @param source the file
     * @param file the parsed file
     * @param item the grid item
     * @param limits the bounds of the item read
     * @return the grid
     * @throws MediaException.Malformed if the payload holds more than {@value #MAX_GRID_PAYLOAD}
     * bytes or is not a valid image grid
     */
    public static HeifGrid readGrid(String format, MediaSource source, HeifFile file, HeifItem item,
                                    MediaLimits limits) {
        if (item.size() > MAX_GRID_PAYLOAD) {
            throw new MediaException.Malformed(format + ": grid item " + item.id() + " holds " + item.size() + " bytes");
        }
        return HeifGrid.parse(file.readItem(source, item, limits));
    }

    /**
     * Returns the alpha auxiliary images of an item a decoder may use, in file order.
     *
     * <p>A candidate is an auxiliary item of {@code item} ({@code auxl} reference) whose
     * {@code auxC} property names an alpha URN and whose essential properties are all in
     * {@code supported}. The caller filters the candidates further by item type and data.
     *
     * @param file the parsed file
     * @param item the item whose alpha is wanted
     * @param supported the property types the decoder implements
     * @return the candidates, possibly empty
     */
    public static List<HeifItem> alphaCandidates(HeifFile file, HeifItem item, Set<String> supported) {
        var candidates = new ArrayList<HeifItem>();
        for (var candidate : file.auxiliariesOf(item.id())) {
            var type = candidate.property(HeifProperty.AuxiliaryType.class);
            if (type != null && type.isAlpha() && candidate.unsupportedEssential(supported) == null) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }
}
