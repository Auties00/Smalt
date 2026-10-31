package com.github.auties00.smalt.io;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.LocalSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Implements {@link MediaSource} over an HTTP or HTTPS resource fetched with range requests (RFC 9110 section 14).
 *
 * <p>The source fetches whole blocks and keeps them in a cache, the least recently read dropped first. A read that
 * misses the cache fetches, in one request, the blocks it needs that are neither cached nor being fetched by
 * another read; a read that continues the previous one also fetches a window past its end that doubles with every
 * such read up to the read-ahead. Concurrent reads of a block being fetched wait for that fetch. Every request after
 * the first goes to the URI the first one was redirected to and carries the {@code If-Range} validator RFC 9110
 * section 13.1.5 allows (the strong entity tag of the first response, or its modification date when it has no entity
 * tag and the date is strong), and every response must keep the length, entity tag and modification date of the
 * first, so a resource that changes while it is read fails the read instead of mixing two versions.
 *
 * <p>The request timeout bounds the wait for the headers of a response only: a server that stops sending a body
 * without closing the connection blocks the read until the connection fails.
 *
 * <p>The cached ranges are {@linkplain LocalSource#isLocal(long, long) local}; the others are not.
 */
final class HttpMediaSource implements MediaSource, LocalSource {
    /**
     * The longest {@code Retry-After} the source waits for, in seconds.
     */
    private static final long MAX_RETRY_AFTER_SECONDS = 60;

    /**
     * The client of the sources whose options name none.
     */
    private static final class SharedClient {
        /**
         * The client, created on first use and never closed.
         */
        private static final HttpClient CLIENT = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * The parts of a {@code Content-Range} header of a byte range (RFC 9110 section 14.4).
     *
     * @param first the offset of the first byte sent, or {@code -1} for an unsatisfied range
     * @param last the offset of the last byte sent, or {@code -1} for an unsatisfied range
     * @param total the length of the resource, or {@code -1} when the server states none
     */
    record ContentRange(long first, long last, long total) {
        /**
         * Parses a {@code Content-Range} value: {@code bytes first-last/total}, {@code bytes first-last/*} or
         * {@code bytes *}{@code /total}.
         *
         * @param value the header value
         * @return the range, or {@code null} when the value is not a valid byte range
         */
        static ContentRange parse(String value) {
            if (value == null) {
                return null;
            }
            var text = value.strip();
            if (text.length() < 6 || !text.regionMatches(true, 0, "bytes", 0, 5) || text.charAt(5) != ' ') {
                return null;
            }
            text = text.substring(6).strip();
            var slash = text.indexOf('/');
            if (slash < 0) {
                return null;
            }
            var range = text.substring(0, slash);
            var length = text.substring(slash + 1);
            var total = length.equals("*") ? -1 : digits(length);
            if (total == -2 || (total == -1 && !length.equals("*"))) {
                return null;
            }
            if (range.equals("*")) {
                return total < 0 ? null : new ContentRange(-1, -1, total);
            }
            var dash = range.indexOf('-');
            if (dash < 0) {
                return null;
            }
            var first = digits(range.substring(0, dash));
            var last = digits(range.substring(dash + 1));
            if (first < 0 || last < first || (total >= 0 && last >= total)) {
                return null;
            }
            return new ContentRange(first, last, total);
        }

        /**
         * Parses a run of decimal digits.
         *
         * @param text the characters to parse
         * @return the value, or {@code -2} when the text is empty, holds anything but digits or overflows
         */
        private static long digits(String text) {
            if (text.isEmpty() || text.length() > 18) {
                return -2;
            }
            var value = 0L;
            for (var index = 0; index < text.length(); index++) {
                var c = text.charAt(index);
                if (c < '0' || c > '9') {
                    return -2;
                }
                value = value * 10 + (c - '0');
            }
            return value;
        }
    }

    /**
     * Signals a response that no retry can fix: the resource changed or the server answered with a final status.
     */
    private static final class FinalFailure extends Exception {
        /**
         * Constructs the failure.
         *
         * @param message the detail message
         */
        FinalFailure(String message) {
            super(message, null, false, false);
        }
    }

    private final HttpClient client;
    private final HttpSourceOptions options;

    /**
     * The URI the requests go to: the one the first request ended at, after its redirects.
     */
    private final URI uri;

    /**
     * The length of the resource.
     */
    private final long size;

    /**
     * The value of the {@code If-Range} header, or {@code null} when the first response carried no validator.
     */
    private final String ifRange;

    /**
     * The entity tag of the first response, weak or strong, or {@code null}.
     */
    private final String entityTag;

    /**
     * The {@code Last-Modified} value of the first response, or {@code null}.
     */
    private final String lastModified;

    private final int blockSize;

    /**
     * The number of blocks of the resource.
     */
    private final long blockCount;

    /**
     * The most blocks the cache keeps.
     */
    private final int cacheBlocks;

    /**
     * The most blocks one request fetches.
     */
    private final int fetchBlocks;

    /**
     * The most blocks fetched past the end of a read, rounded up.
     */
    private final int readAheadBlocks;

    /**
     * Guards every field below and is waited on by reads of a block being fetched.
     */
    private final Object lock;

    /**
     * The cached blocks by index, in access order; each array is {@link #blockSize} bytes long.
     */
    private final LinkedHashMap<Long, byte[]> cache;

    /**
     * The blocks being fetched.
     */
    private final HashSet<Long> fetching;

    /**
     * Arrays of dropped blocks, reused by later fetches.
     */
    private final ArrayDeque<byte[]> spare;

    /**
     * The offset just past the last read served, or {@code -1}.
     */
    private long lastEnd;

    /**
     * The number of blocks fetched past the end of the last read that continued its predecessor.
     */
    private int window;

    private boolean closed;

    /**
     * Constructs a source over a resource whose server honors range requests.
     *
     * @param client sends the requests
     * @param options the block, cache and retry settings
     * @param uri the URI the requests go to
     * @param size the length of the resource
     * @param ifRange the {@code If-Range} value, or {@code null}
     * @param entityTag the entity tag of the first response, or {@code null}
     * @param lastModified the {@code Last-Modified} value of the first response, or {@code null}
     */
    private HttpMediaSource(HttpClient client, HttpSourceOptions options, URI uri, long size, String ifRange,
                            String entityTag, String lastModified) {
        this.client = client;
        this.options = options;
        this.uri = uri;
        this.size = size;
        this.ifRange = ifRange;
        this.entityTag = entityTag;
        this.lastModified = lastModified;
        this.blockSize = options.blockSize();
        this.blockCount = (size + blockSize - 1) / blockSize;
        this.cacheBlocks = (int) Math.min(Integer.MAX_VALUE, options.cacheSize() / blockSize);
        this.fetchBlocks = Math.max(1, cacheBlocks / 2);
        this.readAheadBlocks = (int) Math.min(fetchBlocks, (options.readAhead() + blockSize - 1) / blockSize);
        this.lock = new Object();
        this.cache = new LinkedHashMap<>(16, 0.75f, true);
        this.fetching = new HashSet<>();
        this.spare = new ArrayDeque<>();
        this.lastEnd = -1;
    }

    /**
     * Opens a source over a resource: sends the first request, which asks for the first block, and returns a
     * range source when the server honors it with the length of the resource, or else a forward-only source over
     * the body of the response.
     *
     * @param uri the resource
     * @param options the block, cache and retry settings
     * @return the source
     * @throws IllegalArgumentException if {@code uri} is not an absolute {@code http} or {@code https} URI with a
     * host
     * @throws MediaException.Io if the resource cannot be fetched
     */
    static MediaSource open(URI uri, HttpSourceOptions options) {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(options, "options");
        var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
            throw new IllegalArgumentException("not an absolute http or https URI: " + uri);
        }
        var client = options.httpClient().orElseGet(() -> SharedClient.CLIENT);
        var blockSize = options.blockSize();
        HttpResponse<InputStream> response;
        try {
            response = sendWithRetries(client, options, uri, "bytes=0-" + (blockSize - 1), null);
        } catch (FinalFailure failure) {
            throw io(failure.getMessage());
        }
        var status = response.statusCode();
        var headers = response.headers();
        var encoding = encoding(headers);
        if (status == 206 && !encoding.equals("identity")) {
            close(response);
            throw io("the server sent a part of " + uri + " with the content coding " + encoding);
        }
        if (status == 206) {
            var range = ContentRange.parse(headers.firstValue("content-range").orElse(null));
            if (range == null || range.first() != 0) {
                close(response);
                throw io("the response to the first request for " + uri + " has an invalid Content-Range "
                        + headers.firstValue("content-range").orElse("(none)"));
            }
            if (range.total() < 0) {
                close(response);
                return openWhole(client, options, uri);
            }
            var entityTag = headers.firstValue("etag").orElse(null);
            var lastModified = headers.firstValue("last-modified").orElse(null);
            var ifRange = ifRange(entityTag, lastModified, headers.firstValue("date").orElse(null));
            var source = new HttpMediaSource(client, options, response.uri(), range.total(), ifRange, entityTag,
                    lastModified);
            try {
                source.storeFirst(response.body(), range);
            } catch (RuntimeException exception) {
                close(response);
                throw exception;
            }
            close(response);
            return source;
        }
        if (status == 200) {
            return forwardOnly(response, options);
        }
        if (status == 416) {
            var range = ContentRange.parse(headers.firstValue("content-range").orElse(null));
            close(response);
            if (range != null && range.total() == 0) {
                return MediaSource.of(new byte[0]);
            }
            throw io("the server rejected the first range of " + uri + " (status 416)");
        }
        close(response);
        throw io("HTTP status " + status + " for " + uri);
    }

    /**
     * Chooses the {@code If-Range} value of the later requests (RFC 9110 section 13.1.5): the entity tag when it is
     * strong; nothing when it is weak, since a client holding an entity tag must not send a date; and otherwise the
     * modification date when it is strong, that is at least one second before the {@code Date} of the response
     * (RFC 9110 section 8.8.2.2).
     *
     * @param entityTag the {@code ETag} value of the first response, or {@code null}
     * @param lastModified the {@code Last-Modified} value of the first response, or {@code null}
     * @param date the {@code Date} value of the first response, or {@code null}
     * @return the validator, or {@code null} when the requests carry none
     */
    static String ifRange(String entityTag, String lastModified, String date) {
        if (entityTag != null) {
            return entityTag.startsWith("W/") ? null : entityTag;
        }
        if (lastModified == null || date == null) {
            return null;
        }
        try {
            var modified = ZonedDateTime.parse(lastModified.strip(), DateTimeFormatter.RFC_1123_DATE_TIME);
            var sent = ZonedDateTime.parse(date.strip(), DateTimeFormatter.RFC_1123_DATE_TIME);
            return modified.plusSeconds(1).isAfter(sent) ? null : lastModified;
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    /**
     * Opens a source over the whole resource, read front to back, for a server that states no length.
     *
     * @param client sends the requests
     * @param options the block, cache and retry settings
     * @param uri the resource
     * @return the source
     */
    private static MediaSource openWhole(HttpClient client, HttpSourceOptions options, URI uri) {
        HttpResponse<InputStream> response;
        try {
            response = sendWithRetries(client, options, uri, null, null);
        } catch (FinalFailure failure) {
            throw io(failure.getMessage());
        }
        if (response.statusCode() != 200) {
            close(response);
            throw io("HTTP status " + response.statusCode() + " for " + uri);
        }
        return forwardOnly(response, options);
    }

    /**
     * Returns a forward-only source over the body of a whole response, which the source closes.
     *
     * @param response the response, of status 200
     * @param options the block, cache and retry settings
     * @return the source
     */
    private static MediaSource forwardOnly(HttpResponse<InputStream> response, HttpSourceOptions options) {
        var encoding = encoding(response.headers());
        if (encoding.equals("identity")) {
            var length = response.headers().firstValue("content-length").map(HttpMediaSource::length).orElse(-1L);
            return new StreamMediaSource(response.body(), null, true, length, options.streamOptions(), false);
        }
        if (!encoding.equals("gzip") && !encoding.equals("x-gzip") && !encoding.equals("deflate")) {
            close(response);
            throw io("the server sent " + response.uri() + " with the content coding " + encoding
                    + ", which cannot be decoded");
        }
        return new StreamMediaSource(new DecodedBody(response.body(), encoding), null, true, -1,
                options.streamOptions(), false);
    }

    /**
     * Returns the content coding of a response (RFC 9110 section 8.4).
     *
     * @param headers the response headers
     * @return the lowercase coding, {@code identity} when the response states none
     */
    private static String encoding(HttpHeaders headers) {
        var value = headers.firstValue("content-encoding").map(String::strip).orElse("");
        return value.isEmpty() ? "identity" : value.toLowerCase(Locale.ROOT);
    }

    /**
     * Decodes a body compressed with {@code gzip} or {@code deflate}; the decoder is created by the first read, so
     * that creating the source reads nothing.
     */
    private static final class DecodedBody extends InputStream {
        private final InputStream body;
        private final String encoding;
        private InputStream decoded;

        /**
         * Constructs the decoder.
         *
         * @param body the compressed response body, closed by {@link #close()}
         * @param encoding {@code gzip}, {@code x-gzip} or {@code deflate}
         */
        DecodedBody(InputStream body, String encoding) {
            this.body = body;
            this.encoding = encoding;
        }

        /**
         * {@return the decoding stream, created on first use}
         *
         * @throws IOException if the gzip header cannot be read
         */
        private InputStream decoded() throws IOException {
            if (decoded == null) {
                decoded = encoding.equals("deflate") ? new InflaterInputStream(body) : new GZIPInputStream(body);
            }
            return decoded;
        }

        @Override
        public int read() throws IOException {
            return decoded().read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return decoded().read(buffer, offset, length);
        }

        @Override
        public void close() throws IOException {
            if (decoded != null) {
                decoded.close();
            }
            body.close();
        }
    }

    /**
     * Parses a {@code Content-Length} value.
     *
     * @param value the header value
     * @return the length, or {@code -1} when the value is not a non-negative decimal number
     */
    private static long length(String value) {
        var parsed = ContentRange.digits(value.strip());
        return parsed < 0 ? -1 : parsed;
    }

    /**
     * Stores the blocks of the body of the first response.
     *
     * @param body the response body
     * @param range the range the body holds, starting at zero
     */
    private void storeFirst(InputStream body, ContentRange range) {
        var blocks = new byte[(int) Math.min(fetchBlocks, blockCount)][];
        // a truncated first body leaves the blocks it holds; reads fetch the rest
        var filled = readBlocks(body, 0, range.last() + 1, blocks, new IOException[1]);
        synchronized (lock) {
            for (var index = 0; index < filled; index++) {
                cache.put((long) index, blocks[index]);
            }
            evict();
        }
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public int read(long position, byte[] buffer, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (position < 0) {
            throw new IndexOutOfBoundsException("negative position " + position);
        }
        if (length == 0) {
            return 0;
        }
        if (position >= size) {
            return -1;
        }
        var end = Math.min(size, position + length);
        var first = position / blockSize;
        var last = (end - 1) / blockSize;
        while (true) {
            int count;
            byte[][] blocks;
            synchronized (lock) {
                requireOpen();
                var copied = copyCached(position, end, buffer, offset);
                if (copied > 0) {
                    lastEnd = position + copied;
                    return copied;
                }
                if (fetching.contains(first)) {
                    try {
                        lock.wait();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw interrupted(exception);
                    }
                    continue;
                }
                count = plan(position, first, last);
                blocks = new byte[count][];
                for (var index = 0; index < count; index++) {
                    fetching.add(first + index);
                }
            }
            var filled = 0;
            var copied = 0;
            try {
                filled = fetch(first, blocks);
            } finally {
                synchronized (lock) {
                    for (var index = 0; index < count; index++) {
                        fetching.remove(first + index);
                    }
                    if (!closed) {
                        for (var index = 0; index < filled; index++) {
                            cache.put(first + index, blocks[index]);
                        }
                        copied = copyCached(position, end, buffer, offset);
                        if (copied > 0) {
                            lastEnd = position + copied;
                        }
                        evict();
                    }
                    for (var index = filled; index < count; index++) {
                        if (blocks[index] != null) {
                            recycle(blocks[index]);
                        }
                    }
                    lock.notifyAll();
                }
            }
            if (copied > 0) {
                return copied;
            }
        }
    }

    /**
     * Chooses how many blocks to fetch from {@code first}: those the read needs up to the first one cached or being
     * fetched, and, for a read that continues the previous one, the read-ahead window past them.
     *
     * @param position the offset of the read
     * @param first the first block of the read, neither cached nor being fetched
     * @param last the last block of the read
     * @return the number of blocks, at least 1
     */
    private int plan(long position, long first, long last) {
        var sequential = lastEnd >= 0 && position >= lastEnd - blockSize && position <= lastEnd + blockSize;
        if (sequential && readAheadBlocks > 0) {
            window = window == 0 ? 1 : Math.min(readAheadBlocks, window * 2);
        } else {
            window = 0;
        }
        var limit = Math.min(blockCount, Math.min(last + 1 + window, first + fetchBlocks));
        var count = 1;
        while (first + count < limit && !cache.containsKey(first + count) && !fetching.contains(first + count)) {
            count++;
        }
        return count;
    }

    /**
     * Copies the bytes of a read from the cached blocks, from {@code position} up to the first block not cached.
     *
     * @param position the offset of the read
     * @param end the offset just past the read, at most the size
     * @param buffer the destination
     * @param offset the index in {@code buffer} of {@code position}
     * @return the number of bytes copied
     */
    private int copyCached(long position, long end, byte[] buffer, int offset) {
        var at = position;
        while (at < end) {
            var index = at / blockSize;
            var block = cache.get(index);
            if (block == null) {
                break;
            }
            var start = index * blockSize;
            var count = (int) (Math.min(end, Math.min(size, start + blockSize)) - at);
            System.arraycopy(block, (int) (at - start), buffer, offset + (int) (at - position), count);
            at += count;
        }
        return (int) (at - position);
    }

    /**
     * Drops the least recently read blocks beyond the cache size.
     */
    private void evict() {
        var iterator = cache.values().iterator();
        while (cache.size() > cacheBlocks) {
            recycle(iterator.next());
            iterator.remove();
        }
    }

    /**
     * Keeps an array for a later fetch, up to the most one fetch needs.
     *
     * @param block the array, of {@link #blockSize} bytes
     */
    private void recycle(byte[] block) {
        if (spare.size() < fetchBlocks) {
            spare.push(block);
        }
    }

    /**
     * Fetches consecutive blocks, retrying failed requests.
     *
     * @param first the index of the first block
     * @param blocks receives the blocks; its length is the number of blocks wanted
     * @return the number of leading blocks fetched, at least 1
     * @throws MediaException.Io if no block can be fetched
     */
    private int fetch(long first, byte[][] blocks) {
        var from = first * blockSize;
        var to = Math.min(size, (first + blocks.length) * blockSize);
        var failure = new IOException[1];
        for (var attempt = 0; ; attempt++) {
            HttpResponse<InputStream> response;
            try {
                response = sendWithRetries(client, options, uri, "bytes=" + from + "-" + (to - 1), ifRange);
            } catch (FinalFailure exception) {
                throw io(exception.getMessage());
            }
            int filled;
            try {
                var status = response.statusCode();
                if (status == 200) {
                    throw io("the resource " + uri + " changed or no longer honors range requests");
                }
                if (status == 416) {
                    throw io("the resource " + uri + " changed: the server rejected bytes " + from + " to " + (to - 1));
                }
                if (status != 206) {
                    throw io("HTTP status " + status + " for bytes " + from + " to " + (to - 1) + " of " + uri);
                }
                var headers = response.headers();
                if (!encoding(headers).equals("identity")) {
                    throw io("the server sent a part of " + uri + " with the content coding " + encoding(headers));
                }
                var range = ContentRange.parse(headers.firstValue("content-range").orElse(null));
                if (range == null || range.first() != from || range.total() != size) {
                    throw io("the resource " + uri + " changed or answered bytes " + from + " to " + (to - 1)
                            + " with Content-Range " + headers.firstValue("content-range").orElse("(none)"));
                }
                var tag = headers.firstValue("etag").orElse(null);
                if (entityTag != null && tag != null && !tag.equals(entityTag)) {
                    throw io("the resource " + uri + " changed: its entity tag is " + tag + ", not " + entityTag);
                }
                var modified = headers.firstValue("last-modified").orElse(null);
                if (lastModified != null && modified != null && !modified.equals(lastModified)) {
                    throw io("the resource " + uri + " changed: it was modified " + modified + ", not "
                            + lastModified);
                }
                synchronized (lock) {
                    for (var index = 0; index < blocks.length; index++) {
                        if (blocks[index] == null) {
                            var reused = spare.poll();
                            blocks[index] = reused != null ? reused : new byte[blockSize];
                        }
                    }
                }
                failure[0] = null;
                // a server sending more than asked for is read up to the blocks wanted
                filled = readBlocks(response.body(), from, Math.min(to, range.last() + 1), blocks, failure);
            } finally {
                close(response);
            }
            if (filled > 0) {
                return filled;
            }
            if (attempt >= options.retries()) {
                var message = "the response for bytes " + from + " to " + (to - 1) + " of " + uri
                        + " held no whole block";
                throw new MediaException.Io(message, failure[0] != null ? failure[0] : new IOException(message));
            }
            pause(options, attempt, null);
        }
    }

    /**
     * Reads a response body into consecutive blocks.
     *
     * <p>The bytes of a block the body ends inside are dropped, unless that block is the last of the resource.
     *
     * @param body the response body
     * @param start the offset of the first byte of the body, the start of block zero of {@code blocks}
     * @param end the offset just past the last byte of the body
     * @param blocks the blocks, each allocated on demand when {@code null}
     * @param failure receives the failure that stopped the read before {@code end}, if any
     * @return the number of leading blocks complete
     */
    private int readBlocks(InputStream body, long start, long end, byte[][] blocks, IOException[] failure) {
        var at = start;
        var index = 0;
        try {
            while (at < end && index < blocks.length) {
                var blockEnd = Math.min(size, at + blockSize);
                if (end < blockEnd) {
                    break;
                }
                if (blocks[index] == null) {
                    blocks[index] = new byte[blockSize];
                }
                var block = blocks[index];
                var want = (int) (blockEnd - at);
                var done = 0;
                while (done < want) {
                    // TODO: bound a stalled body by the request timeout, which HttpClient applies to the headers only
                    var read = body.read(block, done, want - done);
                    if (read < 0) {
                        throw new IOException("the response body ended " + (end - at - done) + " bytes early");
                    }
                    done += read;
                }
                at = blockEnd;
                index++;
            }
        } catch (IOException exception) {
            failure[0] = exception;
        }
        return index;
    }

    /**
     * Sends one request, retrying failures and transient statuses.
     *
     * @param client sends the requests
     * @param options the block, cache and retry settings
     * @param uri the resource
     * @param range the {@code Range} value, or {@code null} for the whole resource
     * @param ifRange the {@code If-Range} value, or {@code null}
     * @return the response, whose body the caller closes, of a status other than a transient one
     * @throws FinalFailure if a retry waits longer than the source accepts
     * @throws MediaException.Io if the request fails on every attempt
     */
    private static HttpResponse<InputStream> sendWithRetries(HttpClient client, HttpSourceOptions options, URI uri,
                                                             String range, String ifRange) throws FinalFailure {
        var builder = HttpRequest.newBuilder(uri).GET().timeout(options.requestTimeout())
                .header("Accept-Encoding", "identity");
        options.headers().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        if (range != null) {
            builder.header("Range", range);
            if (ifRange != null) {
                builder.header("If-Range", ifRange);
            }
        }
        var request = builder.build();
        for (var attempt = 0; ; attempt++) {
            HttpResponse<InputStream> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw interrupted(exception);
            } catch (IOException exception) {
                if (attempt >= options.retries()) {
                    throw new MediaException.Io("cannot fetch " + uri, exception);
                }
                pause(options, attempt, null);
                continue;
            }
            var status = response.statusCode();
            if (!isTransient(status)) {
                return response;
            }
            var retryAfter = retryAfter(response.headers());
            close(response);
            if (attempt >= options.retries()) {
                throw io("HTTP status " + status + " for " + uri + " after " + (attempt + 1) + " attempts");
            }
            if (retryAfter != null && retryAfter.toSeconds() > MAX_RETRY_AFTER_SECONDS) {
                throw new FinalFailure("HTTP status " + status + " for " + uri + " with Retry-After " + retryAfter);
            }
            pause(options, attempt, retryAfter);
        }
    }

    /**
     * Returns whether a status may succeed when the request is sent again.
     *
     * @param status the HTTP status code
     * @return {@code true} for 408, 425, 429, 500, 502, 503 and 504
     */
    static boolean isTransient(int status) {
        return switch (status) {
            case 408, 425, 429, 500, 502, 503, 504 -> true;
            default -> false;
        };
    }

    /**
     * Parses the {@code Retry-After} header: a number of seconds or an HTTP date (RFC 9110 section 10.2.3).
     *
     * @param headers the response headers
     * @return the delay, zero for a date in the past, or {@code null} when the header is absent or invalid
     */
    static Duration retryAfter(HttpHeaders headers) {
        var value = headers.firstValue("retry-after").map(String::strip).orElse(null);
        if (value == null || value.isEmpty()) {
            return null;
        }
        var seconds = ContentRange.digits(value);
        if (seconds >= 0) {
            return Duration.ofSeconds(seconds);
        }
        try {
            var date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
            var delay = Duration.between(ZonedDateTime.now(date.getZone()), date);
            return delay.isNegative() ? Duration.ZERO : delay;
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    /**
     * Waits before a retry: the retry delay doubled once per earlier attempt, or the server's
     * {@code Retry-After} when that is longer.
     *
     * @param options the block, cache and retry settings
     * @param attempt the number of attempts before this wait, from zero
     * @param retryAfter the server's delay, or {@code null}
     */
    private static void pause(HttpSourceOptions options, int attempt, Duration retryAfter) {
        var delay = options.retryDelay().multipliedBy(1L << Math.min(attempt, 20));
        if (retryAfter != null && retryAfter.compareTo(delay) > 0) {
            delay = retryAfter;
        }
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw interrupted(exception);
        }
    }

    /**
     * Closes a response body, ignoring a failure.
     *
     * @param response the response whose body is closed
     */
    private static void close(HttpResponse<InputStream> response) {
        try {
            response.body().close();
        } catch (IOException ignored) {
            // the connection is dropped either way
        }
    }

    /**
     * Returns the failure of a request that cannot be fixed by retrying.
     *
     * @param message the detail message
     * @return the exception
     */
    private static MediaException.Io io(String message) {
        return new MediaException.Io(message, new IOException(message));
    }

    /**
     * Returns the failure of a request interrupted while waiting.
     *
     * @param cause the interruption
     * @return the exception
     */
    private static MediaException.Io interrupted(InterruptedException cause) {
        var failure = new InterruptedIOException("interrupted while fetching");
        failure.initCause(cause);
        return new MediaException.Io("interrupted while fetching", failure);
    }

    /**
     * Fails a read of a closed source.
     *
     * @throws MediaException.Io if the source is closed
     */
    private void requireOpen() {
        if (closed) {
            throw new MediaException.Io("the source over " + uri + " is closed", new ClosedChannelException());
        }
    }

    @Override
    public boolean isLocal() {
        return false;
    }

    @Override
    public boolean isLocal(long from, long to) {
        if (from >= to) {
            return true;
        }
        if (to > size) {
            return false;
        }
        synchronized (lock) {
            for (var index = from / blockSize; index <= (to - 1) / blockSize; index++) {
                if (!cache.containsKey(index)) {
                    return false;
                }
            }
            return true;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            cache.clear();
            spare.clear();
            lock.notifyAll();
        }
    }
}
