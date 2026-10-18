package com.github.auties00.smalt.internal.provider;

import com.github.auties00.smalt.spi.AudioCodecProvider;
import com.github.auties00.smalt.spi.ImageCodecProvider;
import com.github.auties00.smalt.spi.VideoCodecProvider;

import java.util.List;
import java.util.ServiceLoader;

/**
 * Holds the codec providers the registries consult, each kind found the first time it is needed.
 *
 * <p>Providers are found with {@link ServiceLoader} through the class loader that defined their
 * service-provider interface, and kept in the order it finds them. Each kind is loaded once per
 * JVM and shared by the decoder and encoder registries of that kind.
 */
public final class Providers {
    /**
     * Prevents instantiation.
     */
    private Providers() {
        throw new AssertionError();
    }

    /**
     * Returns the audio codec providers.
     *
     * @return the providers; unmodifiable
     */
    public static List<AudioCodecProvider> audio() {
        return Audio.ALL;
    }

    /**
     * Returns the video codec providers.
     *
     * @return the providers; unmodifiable
     */
    public static List<VideoCodecProvider> video() {
        return Video.ALL;
    }

    /**
     * Returns the image codec providers.
     *
     * @return the providers; unmodifiable
     */
    public static List<ImageCodecProvider> image() {
        return Image.ALL;
    }

    /**
     * Loads every provider of a service-provider interface.
     *
     * @param service the interface
     * @param <T>     the interface type
     * @return the providers, in the order the service loader finds them
     */
    private static <T> List<T> load(Class<T> service) {
        return ServiceLoader.load(service, service.getClassLoader())
                .stream()
                .map(ServiceLoader.Provider::get)
                .toList();
    }

    /**
     * Holds the audio codec providers, loaded on first use.
     */
    private static final class Audio {
        /**
         * The providers.
         */
        private static final List<AudioCodecProvider> ALL = load(AudioCodecProvider.class);

        /**
         * Prevents instantiation.
         */
        private Audio() {
            throw new AssertionError();
        }
    }

    /**
     * Holds the video codec providers, loaded on first use.
     */
    private static final class Video {
        /**
         * The providers.
         */
        private static final List<VideoCodecProvider> ALL = load(VideoCodecProvider.class);

        /**
         * Prevents instantiation.
         */
        private Video() {
            throw new AssertionError();
        }
    }

    /**
     * Holds the image codec providers, loaded on first use.
     */
    private static final class Image {
        /**
         * The providers.
         */
        private static final List<ImageCodecProvider> ALL = load(ImageCodecProvider.class);

        /**
         * Prevents instantiation.
         */
        private Image() {
            throw new AssertionError();
        }
    }
}
