package com.github.auties00.smalt.io;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The resolver of {@link DataReferenceResolver#http(URI, HttpSourceOptions)} and its overload, which fetches the
 * referenced resources over HTTP; two resolvers with the same base, options and filter are equal.
 *
 * @param base the absolute {@code http} or {@code https} URI relative locations resolve against
 * @param options the options of the sources
 * @param filter accepts the resources that may be fetched, or {@code null} for those of the origin of {@code base}
 */
record HttpDataReferences(URI base, HttpSourceOptions options, Predicate<? super URI> filter)
        implements DataReferenceResolver {
    /**
     * Constructs a resolver.
     *
     * @param base the base URI
     * @param options the options of the sources
     * @param filter the filter, or {@code null} for the origin of {@code base}
     * @throws IllegalArgumentException if {@code base} is not an absolute {@code http} or {@code https} URI with a
     * host
     */
    HttpDataReferences {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(options, "options");
        if (!isHttp(base)) {
            throw new IllegalArgumentException("not an absolute http or https URI: " + base);
        }
        if (base.getRawPath() == null || base.getRawPath().isEmpty()) {
            // URI.resolve appends a relative path to the authority of a base with an empty path
            base = base.resolve("/");
        }
    }

    @Override
    public Optional<MediaSource> resolve(DataReference reference) {
        if (reference.kind() != DataReference.Kind.URL && reference.kind() != DataReference.Kind.URN
                || reference.location().isEmpty()) {
            return Optional.empty();
        }
        URI target;
        try {
            var resolved = base.resolve(new URI(reference.location())).toString();
            var hash = resolved.indexOf('#');
            target = new URI(hash < 0 ? resolved : resolved.substring(0, hash));
        } catch (URISyntaxException | IllegalArgumentException exception) {
            return Optional.empty();
        }
        if (!isHttp(target) || !(filter == null ? sameOrigin(target) : filter.test(target))) {
            return Optional.empty();
        }
        return Optional.of(HttpMediaSource.open(target, options));
    }

    /**
     * Returns whether a URI is an absolute {@code http} or {@code https} URI with a host.
     *
     * @param uri the URI
     * @return {@code true} when the source can fetch it
     */
    private static boolean isHttp(URI uri) {
        var scheme = uri.getScheme();
        return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                && uri.getHost() != null;
    }

    /**
     * Returns whether a URI has the scheme, host and port of the base (RFC 6454 section 4).
     *
     * @param uri an absolute {@code http} or {@code https} URI
     * @return {@code true} for a resource of the origin of the base
     */
    private boolean sameOrigin(URI uri) {
        return uri.getScheme().equalsIgnoreCase(base.getScheme()) && uri.getHost().equalsIgnoreCase(base.getHost())
                && port(uri) == port(base);
    }

    /**
     * Returns the port of a URI, the default of its scheme when it states none.
     *
     * @param uri an absolute {@code http} or {@code https} URI
     * @return the port
     */
    private static int port(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return uri.getScheme().toLowerCase(Locale.ROOT).equals("https") ? 443 : 80;
    }

    @Override
    public String toString() {
        return "DataReferenceResolver.http(" + base + (filter == null ? ", same origin" : ", filtered") + ")";
    }
}
