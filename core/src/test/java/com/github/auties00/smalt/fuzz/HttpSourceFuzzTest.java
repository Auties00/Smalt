package com.github.auties00.smalt.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.io.HttpSourceOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.io.StreamSourceOptions;
import com.github.auties00.smalt.testkit.fuzz.Fuzz;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Fuzz target of the HTTP source, whose input is a script of responses that a client standing in for the network
 * returns in order: the parts are separated by the marker {@code FUZZ-RSP}, and each part is a status line (a
 * number, or {@code 000} for a request failing with an I/O error), header lines {@code Name: value}, an empty line
 * and the body. Once the script is spent every request gets status 404. Whatever the responses, opening and
 * reading the source must fail only with a {@code MediaException}, and a read must keep the source contract. The
 * seeds are written by {@link FuzzSeedWriter#httpSource()}.
 */
class HttpSourceFuzzTest {
    static final byte[] MARKER = "FUZZ-RSP".getBytes(StandardCharsets.US_ASCII);

    /** One scripted response. */
    record Scripted(int status, Map<String, List<String>> headers, byte[] body) {
    }

    @FuzzTest
    void responses(byte[] data) {
        var run = Fuzz.start("HTTP source");
        var client = new ScriptedClient(parse(data));
        var options = HttpSourceOptions.builder().httpClient(client).blockSize(4096).cacheSize(4 * 4096)
                .readAhead(8192).retries(2).retryDelay(Duration.ZERO)
                .streamOptions(StreamSourceOptions.memoryOnly().toBuilder().memoryBudget(1 << 20).build()).build();
        run.value("open", () -> MediaSource.of(URI.create("http://fuzz.invalid/media"), options)).ifPresent(source -> {
            var size = source.size();
            if (size < -1) {
                throw new AssertionError("size " + size);
            }
            var buffer = new byte[3000];
            var forward = run.step("read", () -> {
                var at = 0L;
                for (var count = 0; count < 64; count++) {
                    var read = source.read(at, buffer, 0, buffer.length);
                    if (read < 0) {
                        if (size >= 0 && at < size) {
                            throw new AssertionError("end of input at " + at + " before the size " + size);
                        }
                        break;
                    }
                    if (read == 0 || read > buffer.length || size >= 0 && at + read > size) {
                        throw new AssertionError("read of " + read + " bytes at " + at + " of " + size);
                    }
                    at += read;
                    run.output();
                }
            });
            if (forward && size > 0) {
                run.step("read again", () -> {
                    var position = Integer.remainderUnsigned(Arrays.hashCode(data), (int) Math.min(size, 1 << 20));
                    if (source.read(position, buffer, 0, 1) != 1) {
                        throw new AssertionError("no byte at " + position + " of " + size);
                    }
                });
            }
            run.step("close", source::close);
        });
    }

    /** Parses the script of responses. */
    static Deque<Scripted> parse(byte[] data) {
        var parts = new ArrayDeque<Scripted>();
        var start = 0;
        while (start <= data.length && parts.size() < 64) {
            var end = indexOf(data, MARKER, start);
            if (end < 0) {
                end = data.length;
            }
            parts.add(part(Arrays.copyOfRange(data, start, end)));
            start = end + MARKER.length;
        }
        return parts;
    }

    private static Scripted part(byte[] part) {
        var text = new String(part, StandardCharsets.ISO_8859_1);
        var headerEnd = text.indexOf("\n\n");
        var head = headerEnd < 0 ? text : text.substring(0, headerEnd);
        var body = headerEnd < 0 ? new byte[0] : Arrays.copyOfRange(part, headerEnd + 2, part.length);
        var lines = head.split("\n", -1);
        var status = 200;
        try {
            status = Integer.parseInt(lines[0].strip());
        } catch (NumberFormatException ignored) {
            // a part without a status line is a 200
        }
        var headers = new LinkedHashMap<String, List<String>>();
        for (var index = 1; index < lines.length; index++) {
            var colon = lines[index].indexOf(':');
            if (colon > 0) {
                var name = lines[index].substring(0, colon).strip();
                var value = lines[index].substring(colon + 1).strip();
                // a Retry-After the source waits for would stall the fuzzer, so the short delays become zero
                if (name.equalsIgnoreCase("Retry-After") && value.matches("[0-9]{1,18}")
                        && Long.parseLong(value) <= 60) {
                    value = "0";
                }
                headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
            }
        }
        return new Scripted(status, headers, body);
    }

    private static int indexOf(byte[] data, byte[] marker, int from) {
        outer:
        for (var index = from; index <= data.length - marker.length; index++) {
            for (var offset = 0; offset < marker.length; offset++) {
                if (data[index + offset] != marker[offset]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }

    /** Encodes one scripted response, for the seeds. */
    static byte[] encode(int status, String headers, byte[] body) {
        var head = (status + "\n" + headers + "\n").getBytes(StandardCharsets.ISO_8859_1);
        var all = Arrays.copyOf(head, head.length + body.length);
        System.arraycopy(body, 0, all, head.length, body.length);
        return all;
    }

    /** Joins scripted responses with the marker, for the seeds. */
    static byte[] join(byte[]... parts) {
        var out = new java.io.ByteArrayOutputStream();
        for (var index = 0; index < parts.length; index++) {
            if (index > 0) {
                out.writeBytes(MARKER);
            }
            out.writeBytes(parts[index]);
        }
        return out.toByteArray();
    }

    /** A client that answers every request with the next scripted response. */
    static final class ScriptedClient extends HttpClient {
        private final Deque<Scripted> script;

        ScriptedClient(Deque<Scripted> script) {
            this.script = script;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
            Scripted next;
            synchronized (script) {
                next = script.poll();
            }
            if (next == null) {
                next = new Scripted(404, Map.of(), new byte[0]);
            }
            if (next.status() == 0) {
                throw new IOException("scripted failure");
            }
            var response = next;
            var headers = HttpHeaders.of(response.headers(), (name, value) -> true);
            InputStream body = new ByteArrayInputStream(response.body());
            return (HttpResponse<T>) new HttpResponse<InputStream>() {
                @Override
                public int statusCode() {
                    return response.status();
                }

                @Override
                public HttpRequest request() {
                    return request;
                }

                @Override
                public Optional<HttpResponse<InputStream>> previousResponse() {
                    return Optional.empty();
                }

                @Override
                public HttpHeaders headers() {
                    return headers;
                }

                @Override
                public InputStream body() {
                    return body;
                }

                @Override
                public Optional<SSLSession> sslSession() {
                    return Optional.empty();
                }

                @Override
                public URI uri() {
                    return request.uri();
                }

                @Override
                public Version version() {
                    return Version.HTTP_1_1;
                }
            };
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> handler,
                                                                HttpResponse.PushPromiseHandler<T> promises) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return null;
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }
    }
}
