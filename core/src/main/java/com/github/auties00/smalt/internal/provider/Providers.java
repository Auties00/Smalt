package com.github.auties00.smalt.internal.provider;

import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.spi.AudioCodecProvider;
import com.github.auties00.smalt.spi.ContainerProvider;
import com.github.auties00.smalt.spi.ImageCodecProvider;
import com.github.auties00.smalt.spi.VideoCodecProvider;

import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.regex.Pattern;

/**
 * Holds the codec and container providers the registries consult, each kind found the first time it is needed.
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
     * Returns the container providers.
     *
     * @return the providers; unmodifiable
     * @throws ServiceConfigurationError if the service loader fails, or a provider's name or format breaks the
     * rules of {@link ContainerProvider#name()} and {@link ContainerProvider#format()}
     */
    public static List<ContainerProvider> containers() {
        if (Container.FAILURE != null) {
            throw new ServiceConfigurationError(Container.FAILURE, Container.CAUSE);
        }
        return Container.ALL;
    }

    /**
     * Checks the identity of a container provider.
     *
     * @param provider the container provider to check
     * @return a description of the broken rule, or {@code null} when the provider is valid
     */
    private static String check(ContainerProvider provider) {
        var type = provider.getClass().getName();
        String name;
        ContainerFormat format;
        try {
            name = provider.name();
            format = provider.format();
        } catch (RuntimeException exception) {
            return type + " failed to report its name or format: " + exception;
        }
        if (name == null || !Container.NAME.matcher(name).matches()) {
            return type + " has an invalid container name " + (name == null ? "null" : "\"" + name + "\"");
        }
        if (format == null) {
            return type + " reports no container format";
        }
        if (format == ContainerFormat.OTHER) {
            for (var constant : ContainerFormat.values()) {
                if (constant.shortName().equals(name)) {
                    return type + " names its container \"" + name + "\", the name of " + constant;
                }
            }
        } else if (!format.shortName().equals(name)) {
            return type + " provides " + format + " under the name \"" + name + "\" instead of \""
                    + format.shortName() + "\"";
        }
        return null;
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
     * Holds the container providers, loaded and checked on first use.
     */
    private static final class Container {
        /**
         * The form of a container name: groups of lowercase letters and digits joined by single hyphens.
         */
        private static final Pattern NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

        /**
         * The providers, empty when one is invalid.
         */
        private static final List<ContainerProvider> ALL;

        /**
         * The rule the first invalid provider breaks, or {@code null} when every provider is valid.
         */
        private static final String FAILURE;

        /**
         * The failure of the service loader, or {@code null}.
         */
        private static final Throwable CAUSE;

        static {
            List<ContainerProvider> all;
            String failure = null;
            Throwable cause = null;
            try {
                all = load(ContainerProvider.class);
                for (var provider : all) {
                    failure = check(provider);
                    if (failure != null) {
                        break;
                    }
                }
            } catch (ServiceConfigurationError error) {
                all = List.of();
                failure = error.getMessage();
                cause = error;
            }
            FAILURE = failure;
            CAUSE = cause;
            ALL = failure == null ? all : List.of();
        }

        /**
         * Prevents instantiation.
         */
        private Container() {
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
