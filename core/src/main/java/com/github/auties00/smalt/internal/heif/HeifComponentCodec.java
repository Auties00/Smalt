package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;

import java.util.List;
import java.util.ServiceLoader;

/**
 * Decodes the data of HEIF image items coded with a codec that another Smalt artifact implements
 * into component planes, for the HEIF decoders of the artifacts that do not depend on it.
 *
 * <p>Implementations are found with {@link ServiceLoader}: a module provides one with
 * {@code provides}, a jar on the class path with a {@code META-INF/services} entry. They must have
 * a public no-argument constructor that does no work and must be thread-safe.
 */
public interface HeifComponentCodec {
    /**
     * {@return the item type this codec decodes, such as {@code "j2k1"}}
     */
    String itemType();

    /**
     * Decodes the data of an item.
     *
     * <p>Implementations must report a component of each color and alpha channel with the types
     * of {@link HeifComponents}, deciding the color model from the item's properties.
     *
     * @param name the format name, for messages, such as {@code "heic"}
     * @param payload the item data
     * @param item the item, with its properties
     * @param limits the bounds of the decoded planes
     * @return the components
     * @throws MediaException.Malformed if the data is corrupt
     * @throws MediaException.Unsupported if the item uses a feature the codec lacks
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     */
    HeifComponents decode(String name, byte[] payload, HeifItem item, MediaLimits limits);

    /**
     * Returns the codec of an item type.
     *
     * @param itemType the item type
     * @return the first codec found for it, or {@code null} when no artifact on the module or class
     * path provides one
     */
    static HeifComponentCodec find(String itemType) {
        for (var codec : Holder.CODECS) {
            if (codec.itemType().equals(itemType)) {
                return codec;
            }
        }
        return null;
    }

    /**
     * Holds the codecs, looked up once.
     */
    final class Holder {
        /**
         * The codecs found.
         */
        private static final List<HeifComponentCodec> CODECS = ServiceLoader.load(HeifComponentCodec.class,
                HeifComponentCodec.class.getClassLoader()).stream().map(ServiceLoader.Provider::get).toList();

        /**
         * Prevents instantiation.
         */
        private Holder() {
            throw new AssertionError();
        }
    }
}
