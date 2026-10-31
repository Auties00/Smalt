package com.github.auties00.smalt.io;

import com.github.auties00.smalt.internal.check.Checks;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Holds the settings of a {@link MediaSource} over an HTTP or HTTPS resource: the client and request headers, the
 * size of the blocks it fetches and keeps, how far it reads ahead, and how it retries. Instances are immutable and
 * thread-safe; create them with {@link #builder()} or a preset such as {@link #defaults()}.
 *
 * @see MediaSource#of(URI, HttpSourceOptions)
 * @see DataReferenceResolver#http(URI, HttpSourceOptions)
 */
public final class HttpSourceOptions {
    /**
     * The default block size: 64 KiB.
     */
    public static final int DEFAULT_BLOCK_SIZE = 64 << 10;

    /**
     * The smallest block size: 4 KiB.
     */
    public static final int MIN_BLOCK_SIZE = 4 << 10;

    /**
     * The largest block size: 16 MiB.
     */
    public static final int MAX_BLOCK_SIZE = 16 << 20;

    /**
     * The default read-ahead: 2 MiB.
     */
    public static final long DEFAULT_READ_AHEAD = 2L << 20;

    /**
     * The default cache size: 16 MiB.
     */
    public static final long DEFAULT_CACHE_SIZE = 16L << 20;

    /**
     * The default number of retries of a failed request: 3.
     */
    public static final int DEFAULT_RETRIES = 3;

    /**
     * The default wait before the first retry: 500 milliseconds.
     */
    public static final Duration DEFAULT_RETRY_DELAY = Duration.ofMillis(500);

    /**
     * The default time a request may wait for its response headers, and for each further part of its body: 30
     * seconds.
     */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /**
     * The request headers the source sets itself, in lowercase.
     */
    private static final Set<String> RESERVED_HEADERS = Set.of("range", "if-range", "accept-encoding");

    /**
     * The options of {@link #defaults()}.
     */
    private static final HttpSourceOptions DEFAULTS = new HttpSourceOptions(null, Map.of(), DEFAULT_BLOCK_SIZE,
            DEFAULT_READ_AHEAD, DEFAULT_CACHE_SIZE, DEFAULT_RETRIES, DEFAULT_RETRY_DELAY, DEFAULT_REQUEST_TIMEOUT,
            StreamSourceOptions.defaults());

    /**
     * The client, or {@code null} for the shared client.
     */
    private final HttpClient httpClient;

    private final Map<String, List<String>> headers;
    private final int blockSize;
    private final long readAhead;
    private final long cacheSize;
    private final int retries;
    private final Duration retryDelay;
    private final Duration requestTimeout;
    private final StreamSourceOptions streamOptions;

    private HttpSourceOptions(HttpClient httpClient, Map<String, List<String>> headers, int blockSize, long readAhead,
                              long cacheSize, int retries, Duration retryDelay, Duration requestTimeout,
                              StreamSourceOptions streamOptions) {
        this.httpClient = httpClient;
        this.headers = headers;
        this.blockSize = blockSize;
        this.readAhead = readAhead;
        this.cacheSize = cacheSize;
        this.retries = retries;
        this.retryDelay = retryDelay;
        this.requestTimeout = requestTimeout;
        this.streamOptions = streamOptions;
    }

    /**
     * Returns the default options: the shared client, no extra headers, blocks of {@link #DEFAULT_BLOCK_SIZE}
     * bytes, a read-ahead of {@link #DEFAULT_READ_AHEAD} bytes, a cache of {@link #DEFAULT_CACHE_SIZE} bytes and
     * {@link #DEFAULT_RETRIES} retries.
     *
     * @return the default options
     */
    public static HttpSourceOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder(DEFAULTS);
    }

    /**
     * Returns a builder initialized with these options.
     *
     * @return the builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return the client the requests are sent with, or an empty {@code Optional} for the shared client of this
     * library, which follows redirects except from HTTPS to HTTP, connects within 10 seconds and uses the system's
     * default proxy selector; empty by default}
     *
     * <p>A client given here is not closed by the sources that use it.
     */
    public Optional<HttpClient> httpClient() {
        return Optional.ofNullable(httpClient);
    }

    /**
     * {@return the headers added to every request, by name in the order they were added, each with its values in
     * order; empty by default}
     *
     * <p>The source sets {@code Range}, {@code If-Range} and {@code Accept-Encoding} itself, so they cannot be
     * given here.
     */
    public Map<String, List<String>> headers() {
        return headers;
    }

    /**
     * {@return the size in bytes of the blocks the source fetches and caches, from {@link #MIN_BLOCK_SIZE} to
     * {@link #MAX_BLOCK_SIZE}; {@link #DEFAULT_BLOCK_SIZE} by default}
     *
     * <p>Every request asks for whole blocks, so a read of a few bytes transfers at least one block; the first
     * request, which also learns the size of the resource and whether the server honors range requests, asks
     * for the first block.
     */
    public int blockSize() {
        return blockSize;
    }

    /**
     * {@return the most bytes a request fetches past the end of a read that continues the previous one, at least
     * zero; {@link #DEFAULT_READ_AHEAD} by default}
     *
     * <p>Reads that follow one another fetch a growing window in one request, up to this many bytes past what
     * the read needs, so a front-to-back read takes one request per window instead of one per block; a read
     * elsewhere fetches only the blocks it needs. Zero fetches only the blocks each read needs.
     */
    public long readAhead() {
        return readAhead;
    }

    /**
     * {@return the most bytes of fetched blocks the source keeps in memory, the least recently read dropped first,
     * at least {@link #blockSize()}; {@link #DEFAULT_CACHE_SIZE} by default}
     *
     * <p>One request never fetches more than half the cache, read-ahead included. Besides the cache, the source
     * holds the blocks of the requests in flight and keeps up to half the cache of dropped blocks for the next
     * request to fill, so a source read from one thread holds at most one and a half times this size.
     */
    public long cacheSize() {
        return cacheSize;
    }

    /**
     * {@return the number of times a failed request is sent again before the read fails, at least zero;
     * {@link #DEFAULT_RETRIES} by default}
     *
     * <p>A request is retried when it fails with an I/O error or a timeout, when the response ends before its
     * declared length, and on the statuses 408, 425, 429, 500, 502, 503 and 504. Any other status fails at once.
     */
    public int retries() {
        return retries;
    }

    /**
     * {@return the wait before the first retry, zero or longer; each further retry waits twice as long as the one
     * before; {@link #DEFAULT_RETRY_DELAY} by default}
     *
     * <p>A response carrying a {@code Retry-After} header of at most 60 seconds is retried after that delay if it
     * is longer; a longer {@code Retry-After} fails the request at once.
     */
    public Duration retryDelay() {
        return retryDelay;
    }

    /**
     * {@return the time each request may wait for its response headers, and for each further part of its body,
     * positive; {@link #DEFAULT_REQUEST_TIMEOUT} by default}
     *
     * <p>A request that waits longer fails as an I/O error does: it is retried, and a response whose body stalls
     * is resumed from where it stopped.
     */
    public Duration requestTimeout() {
        return requestTimeout;
    }

    /**
     * {@return the options of the source used when the resource can only be read front to back;
     * {@link StreamSourceOptions#defaults()} by default}
     *
     * <p>A server that ignores range requests, or that does not tell the length of the resource, sends it whole;
     * the source then keeps what it has read as a {@linkplain MediaSource#of(java.io.InputStream, long,
     * StreamSourceOptions) stream source} does, under these options.
     */
    public StreamSourceOptions streamOptions() {
        return streamOptions;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof HttpSourceOptions that && Objects.equals(httpClient, that.httpClient)
                && headers.equals(that.headers) && blockSize == that.blockSize && readAhead == that.readAhead
                && cacheSize == that.cacheSize && retries == that.retries && retryDelay.equals(that.retryDelay)
                && requestTimeout.equals(that.requestTimeout) && streamOptions.equals(that.streamOptions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(httpClient, headers, blockSize, readAhead, cacheSize, retries, retryDelay, requestTimeout,
                streamOptions);
    }

    @Override
    public String toString() {
        return "HttpSourceOptions[httpClient=" + (httpClient == null ? "shared" : httpClient) + ", headers="
                + headers.keySet() + ", blockSize=" + blockSize + ", readAhead=" + readAhead + ", cacheSize="
                + cacheSize + ", retries=" + retries + ", retryDelay=" + retryDelay + ", requestTimeout="
                + requestTimeout + ", streamOptions=" + streamOptions + "]";
    }

    /**
     * Assembles {@link HttpSourceOptions} setting by setting.
     *
     * <p>Setters reject {@code null} and a header the source sets itself or that is not a valid HTTP header, and
     * store numbers unchecked; {@link #build()} checks the ranges. Instances are not thread-safe.
     */
    public static final class Builder {
        private HttpClient httpClient;
        private final LinkedHashMap<String, List<String>> headers;
        private int blockSize;
        private long readAhead;
        private long cacheSize;
        private int retries;
        private Duration retryDelay;
        private Duration requestTimeout;
        private StreamSourceOptions streamOptions;

        private Builder(HttpSourceOptions options) {
            this.httpClient = options.httpClient;
            this.headers = new LinkedHashMap<>();
            options.headers.forEach((name, values) -> headers.put(name, new ArrayList<>(values)));
            this.blockSize = options.blockSize;
            this.readAhead = options.readAhead;
            this.cacheSize = options.cacheSize;
            this.retries = options.retries;
            this.retryDelay = options.retryDelay;
            this.requestTimeout = options.requestTimeout;
            this.streamOptions = options.streamOptions;
        }

        /**
         * Sets the client the requests are sent with; see {@link HttpSourceOptions#httpClient()}.
         *
         * @param httpClient the value
         * @return this builder
         */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
            return this;
        }

        /**
         * Sends the requests with the shared client of this library; see {@link HttpSourceOptions#httpClient()}.
         *
         * @return this builder
         */
        public Builder automaticHttpClient() {
            this.httpClient = null;
            return this;
        }

        /**
         * Adds a value of a header sent with every request, after the values the header already has; see
         * {@link HttpSourceOptions#headers()}.
         *
         * <p>Header names are compared without regard to case; the first spelling added is kept.
         *
         * @param name the header name
         * @param value the header value
         * @return this builder
         * @throws IllegalArgumentException if {@code name} is {@code Range}, {@code If-Range} or
         * {@code Accept-Encoding}, or the header is not one a request may carry
         */
        public Builder header(String name, String value) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            if (RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("the source sets the " + name + " header itself");
            }
            // throws IllegalArgumentException for a malformed or restricted header
            HttpRequest.newBuilder(URI.create("http://localhost/")).header(name, value);
            for (var entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    entry.getValue().add(value);
                    return this;
                }
            }
            var values = new ArrayList<String>();
            values.add(value);
            headers.put(name, values);
            return this;
        }

        /**
         * Removes every header added; see {@link HttpSourceOptions#headers()}.
         *
         * @return this builder
         */
        public Builder noHeaders() {
            headers.clear();
            return this;
        }

        /**
         * Sets the size of the blocks fetched and cached; see {@link HttpSourceOptions#blockSize()}.
         *
         * @param blockSize the value
         * @return this builder
         */
        public Builder blockSize(int blockSize) {
            this.blockSize = blockSize;
            return this;
        }

        /**
         * Sets the most bytes fetched past a read that continues the previous one; see
         * {@link HttpSourceOptions#readAhead()}.
         *
         * @param readAhead the value
         * @return this builder
         */
        public Builder readAhead(long readAhead) {
            this.readAhead = readAhead;
            return this;
        }

        /**
         * Sets the most bytes of blocks kept in memory; see {@link HttpSourceOptions#cacheSize()}.
         *
         * @param cacheSize the value
         * @return this builder
         */
        public Builder cacheSize(long cacheSize) {
            this.cacheSize = cacheSize;
            return this;
        }

        /**
         * Sets the number of times a failed request is sent again; see {@link HttpSourceOptions#retries()}.
         *
         * @param retries the value
         * @return this builder
         */
        public Builder retries(int retries) {
            this.retries = retries;
            return this;
        }

        /**
         * Sets the wait before the first retry; see {@link HttpSourceOptions#retryDelay()}.
         *
         * @param retryDelay the value
         * @return this builder
         */
        public Builder retryDelay(Duration retryDelay) {
            this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay");
            return this;
        }

        /**
         * Sets the time each request may wait for its response headers and each part of its body; see
         * {@link HttpSourceOptions#requestTimeout()}.
         *
         * @param requestTimeout the value
         * @return this builder
         */
        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
            return this;
        }

        /**
         * Sets the options of the source used when the resource can only be read front to back; see
         * {@link HttpSourceOptions#streamOptions()}.
         *
         * @param streamOptions the value
         * @return this builder
         */
        public Builder streamOptions(StreamSourceOptions streamOptions) {
            this.streamOptions = Objects.requireNonNull(streamOptions, "streamOptions");
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if the block size is outside {@link #MIN_BLOCK_SIZE} to
         * {@link #MAX_BLOCK_SIZE}, the read-ahead or the retries are negative, the cache is smaller than one block,
         * the retry delay is negative or the request timeout is not positive
         */
        public HttpSourceOptions build() {
            Checks.inRange("block size", blockSize, MIN_BLOCK_SIZE, MAX_BLOCK_SIZE);
            Checks.nonNegative("read-ahead", readAhead);
            Checks.inRange("cache size", cacheSize, blockSize, Long.MAX_VALUE);
            Checks.nonNegative("retries", retries);
            Checks.nonNegative("retry delay", retryDelay);
            Checks.positive("request timeout", requestTimeout);
            var copy = new LinkedHashMap<String, List<String>>();
            headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
            return new HttpSourceOptions(httpClient, Collections.unmodifiableMap(copy), blockSize, readAhead,
                    cacheSize, retries, retryDelay, requestTimeout, streamOptions);
        }
    }
}
