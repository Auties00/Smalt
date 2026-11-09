package com.github.auties00.smalt.io;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A local HTTP/1.1 server of in-memory resources for the HTTP source tests, which counts every request and the body
 * bytes it sends, honors single byte ranges and {@code If-Range} unless told not to, and injects transient statuses,
 * truncated and stalled bodies, capped parts, redirects and header checks on demand.
 */
final class FixtureServer implements AutoCloseable {
    /** One resource and how the server misbehaves when serving it. */
    static final class Resource {
        volatile byte[] data;
        volatile String etag;
        volatile String lastModified;
        volatile boolean ranges = true;
        volatile boolean chunked;
        volatile String encoding;
        volatile String redirect;
        volatile String requiredHeader;
        volatile String requiredValue;
        volatile String retryAfter = "0";
        volatile boolean unknownTotal;
        volatile boolean encodeRanges;
        volatile boolean overDeliver;
        volatile long rangeShift;
        volatile int maxPart = Integer.MAX_VALUE;
        final Deque<Integer> failures = new ArrayDeque<>();
        final AtomicInteger truncations = new AtomicInteger();
        final AtomicInteger stalls = new AtomicInteger();

        Resource(byte[] data) {
            this.data = data;
        }

        synchronized Resource fail(int... statuses) {
            for (var status : statuses) {
                failures.add(status);
            }
            return this;
        }

        synchronized Integer nextFailure() {
            return failures.poll();
        }
    }

    private final HttpServer server;
    private final ExecutorService executor;
    private final Map<String, Resource> resources = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicLong bodyBytes = new AtomicLong();
    private final List<String> ranges = new ArrayList<>();
    private final List<String> ifRanges = new ArrayList<>();

    FixtureServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 64);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    Resource put(String path, byte[] data) {
        var resource = new Resource(data);
        resources.put(path, resource);
        return resource;
    }

    URI uri(String path) {
        return URI.create("http://localhost:" + server.getAddress().getPort() + path);
    }

    URI loopbackUri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    int requests() {
        return requests.get();
    }

    int hits(String path) {
        var count = hits.get(path);
        return count == null ? 0 : count.get();
    }

    long bodyBytes() {
        return bodyBytes.get();
    }

    List<String> ranges() {
        synchronized (ranges) {
            return List.copyOf(ranges);
        }
    }

    List<String> ifRanges() {
        synchronized (ranges) {
            return List.copyOf(ifRanges);
        }
    }

    void reset() {
        requests.set(0);
        bodyBytes.set(0);
        hits.clear();
        synchronized (ranges) {
            ranges.clear();
            ifRanges.clear();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            requests.incrementAndGet();
            var path = exchange.getRequestURI().getPath();
            hits.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
            var requestHeaders = exchange.getRequestHeaders();
            var range = requestHeaders.getFirst("Range");
            synchronized (ranges) {
                ranges.add(path + " " + range);
                if (range != null) {
                    ifRanges.add(path + " " + requestHeaders.getFirst("If-Range"));
                }
            }
            var resource = resources.get(path);
            var headers = exchange.getResponseHeaders();
            if (resource == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if (resource.redirect != null) {
                headers.set("Location", resource.redirect);
                exchange.sendResponseHeaders(302, -1);
                return;
            }
            if (resource.requiredHeader != null
                    && !resource.requiredValue.equals(requestHeaders.getFirst(resource.requiredHeader))) {
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            var failure = resource.nextFailure();
            if (failure != null) {
                if (resource.retryAfter != null) {
                    headers.set("Retry-After", resource.retryAfter);
                }
                exchange.sendResponseHeaders(failure, -1);
                return;
            }
            var data = resource.data;
            var etag = resource.etag;
            if (etag != null) {
                headers.set("ETag", etag);
            }
            if (resource.lastModified != null) {
                headers.set("Last-Modified", resource.lastModified);
            }
            var ifRange = requestHeaders.getFirst("If-Range");
            var validatorMatches = ifRange == null || ifRange.equals(etag) || ifRange.equals(resource.lastModified);
            if (resource.ranges && range != null && range.startsWith("bytes=") && validatorMatches) {
                headers.set("Accept-Ranges", "bytes");
                var spec = range.substring(6);
                var dash = spec.indexOf('-');
                var first = Long.parseLong(spec.substring(0, dash));
                var last = dash == spec.length() - 1 ? data.length - 1 : Long.parseLong(spec.substring(dash + 1));
                if (first >= data.length) {
                    headers.set("Content-Range", "bytes */" + data.length);
                    exchange.sendResponseHeaders(416, -1);
                    return;
                }
                last = resource.overDeliver ? data.length - 1 : Math.min(last, data.length - 1);
                last = Math.min(last, first + resource.maxPart - 1);
                first += resource.rangeShift;
                var length = (int) (last - first + 1);
                headers.set("Content-Range", "bytes " + first + "-" + last + "/"
                        + (resource.unknownTotal ? "*" : data.length));
                if (resource.encodeRanges) {
                    headers.set("Content-Encoding", "gzip");
                }
                exchange.sendResponseHeaders(206, length);
                if (stall(resource, exchange, data, (int) first, length)) {
                    return;
                }
                var sent = length;
                if (resource.truncations.getAndUpdate(count -> Math.max(0, count - 1)) > 0) {
                    sent = length / 2;
                }
                exchange.getResponseBody().write(data, (int) first, sent);
                // a cut body is flushed, since closing a short fixed-length body drops what is buffered
                exchange.getResponseBody().flush();
                bodyBytes.addAndGet(sent);
                return;
            }
            if (resource.encoding != null) {
                headers.set("Content-Encoding", resource.encoding);
                if (resource.encoding.equals("gzip")) {
                    var compressed = new java.io.ByteArrayOutputStream();
                    try (var gzip = new java.util.zip.GZIPOutputStream(compressed)) {
                        gzip.write(data);
                    }
                    data = compressed.toByteArray();
                } else if (resource.encoding.equals("deflate")) {
                    var compressed = new java.io.ByteArrayOutputStream();
                    try (var deflate = new java.util.zip.DeflaterOutputStream(compressed)) {
                        deflate.write(data);
                    }
                    data = compressed.toByteArray();
                }
            }
            exchange.sendResponseHeaders(200, resource.chunked ? 0 : data.length == 0 ? -1 : data.length);
            if (stall(resource, exchange, data, 0, data.length)) {
                return;
            }
            exchange.getResponseBody().write(data);
            bodyBytes.addAndGet(data.length);
        } catch (IOException exception) {
            // the client closed the connection before the body was sent: expected for dropped responses
        }
    }

    /**
     * Sends the first half of a body and then nothing for three seconds, while the resource has stalls left.
     */
    private boolean stall(Resource resource, HttpExchange exchange, byte[] data, int offset, int length)
            throws IOException {
        if (resource.stalls.getAndUpdate(count -> Math.max(0, count - 1)) <= 0) {
            return false;
        }
        exchange.getResponseBody().write(data, offset, length / 2);
        exchange.getResponseBody().flush();
        bodyBytes.addAndGet(length / 2);
        try {
            Thread.sleep(3000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }
}
