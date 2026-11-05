package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Starts a screen cast through the {@code org.freedesktop.portal.ScreenCast} interface of
 * {@code xdg-desktop-portal}, the only way a Wayland client may record the screen.
 *
 * <p>The cast is the portal's sequence of requests: {@code CreateSession}, {@code SelectSources}
 * (one monitor or window, the pointer embedded or hidden), {@code Start}, which may show the
 * desktop's dialog where the user picks what to share, and {@code OpenPipeWireRemote}, which returns
 * a descriptor of a PipeWire connection that can see only the shared streams. Each request answers
 * through the {@code Response} signal of a request object whose path is derived from the
 * connection's unique name and a token, so the signal is subscribed to before the request is sent. A
 * response other than success ({@code 1} cancelled by the user, {@code 2} failed) ends the cast.
 */
final class ScreenCastPortal implements AutoCloseable {
    /**
     * The portal's bus name and object path.
     */
    static final String PORTAL = "org.freedesktop.portal.Desktop", PORTAL_PATH = "/org/freedesktop/portal/desktop";

    /**
     * The screen cast interface.
     */
    static final String SCREEN_CAST = "org.freedesktop.portal.ScreenCast";

    /**
     * The source type of a monitor.
     */
    static final long SOURCE_MONITOR = 1;

    /**
     * The source type of a window.
     */
    static final long SOURCE_WINDOW = 2;

    /**
     * The cursor mode that leaves the pointer out.
     */
    static final long CURSOR_HIDDEN = 1;

    /**
     * The cursor mode that draws the pointer into the frames.
     */
    static final long CURSOR_EMBEDDED = 2;

    /**
     * How long a request may take, the user's choice in the dialog included.
     */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofMinutes(5);

    /**
     * How long a method call may take.
     */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);

    /**
     * The counter that makes request tokens unique in the process.
     */
    private static final AtomicLong TOKENS = new AtomicLong();

    /**
     * The bus connection.
     */
    private final DBusConnection bus;

    /**
     * The session's object path, or {@code null} before {@code CreateSession} answered.
     */
    private String session;

    /**
     * Wraps a bus connection.
     */
    private ScreenCastPortal(DBusConnection bus) {
        this.bus = bus;
    }

    /**
     * Shares one source through the portal and opens the PipeWire connection that streams it.
     *
     * @param sourceType {@link #SOURCE_MONITOR} or {@link #SOURCE_WINDOW}
     * @param cursor whether the pointer should be drawn into the frames, when the portal can
     * @return the cast, which the caller closes
     * @throws MediaException.Unsupported if no session bus or screen cast portal is reachable, or
     * the portal cannot share the source type
     * @throws MediaException.Io if the user cancels or the portal fails
     */
    static Cast start(long sourceType, boolean cursor) {
        var bus = DBusConnection.session(CALL_TIMEOUT);
        var portal = new ScreenCastPortal(bus);
        try {
            return portal.run(sourceType, cursor);
        } catch (RuntimeException | Error failure) {
            portal.close();
            throw failure;
        }
    }

    /**
     * Runs the requests of a cast.
     */
    private Cast run(long sourceType, boolean cursor) {
        var sourceTypes = property("AvailableSourceTypes");
        if ((sourceTypes & sourceType) == 0) {
            throw new MediaException.Unsupported("screen cast", "the screen cast portal cannot share "
                    + (sourceType == SOURCE_MONITOR ? "monitors" : "windows") + " (source types " + sourceTypes + ")");
        }
        var cursorModes = property("AvailableCursorModes");
        var created = request("CreateSession", "a{sv}", options -> options.put("session_handle_token", string(token())));
        session = (String) value(created, "session_handle");
        // TODO: draw the pointer from the stream's SPA_META_Cursor when the portal offers only the metadata mode
        var cursorMode = cursor && (cursorModes & CURSOR_EMBEDDED) != 0 ? CURSOR_EMBEDDED : CURSOR_HIDDEN;
        request("SelectSources", "oa{sv}", options -> {
            options.put("types", new DBusMessage.Variant("u", sourceType));
            options.put("multiple", new DBusMessage.Variant("b", false));
            if ((cursorModes & cursorMode) != 0) {
                options.put("cursor_mode", new DBusMessage.Variant("u", cursorMode));
            }
        });
        var started = request("Start", "osa{sv}", options -> {
        });
        var streams = streams(started);
        if (streams.isEmpty()) {
            throw failure("the screen cast portal shared no stream");
        }
        var reply = bus.call(DBusMessage.call(PORTAL, PORTAL_PATH, SCREEN_CAST, "OpenPipeWireRemote", "oa{sv}", session,
                new LinkedHashMap<String, DBusMessage.Variant>()), CALL_TIMEOUT);
        if (!reply.signature().equals("h")) {
            throw failure("OpenPipeWireRemote returned " + reply.signature());
        }
        return new Cast(this, streams.getFirst(), (Integer) reply.body().getFirst(), cursorMode == CURSOR_EMBEDDED);
    }

    /**
     * Reads an unsigned property of the screen cast interface.
     *
     * @param name the property
     * @return its value, or {@code 0} when the portal does not have it
     * @throws MediaException.Unsupported if no screen cast portal answers
     */
    private long property(String name) {
        DBusMessage reply;
        try {
            reply = bus.call(DBusMessage.call(PORTAL, PORTAL_PATH, "org.freedesktop.DBus.Properties", "Get", "ss",
                    SCREEN_CAST, name), CALL_TIMEOUT);
        } catch (MediaException.Io failure) {
            throw new MediaException.Unsupported("screen cast", "no xdg-desktop-portal screen cast portal answers on the"
                    + " session bus: " + failure.getMessage());
        }
        return reply.body().getFirst() instanceof DBusMessage.Variant variant && variant.value() instanceof Long number ? number : 0;
    }

    /**
     * Fills the options of a request.
     */
    @FunctionalInterface
    private interface Options {
        /**
         * Adds request-specific options.
         *
         * @param options the options, which already hold the {@code handle_token}
         */
        void fill(Map<String, DBusMessage.Variant> options);
    }

    /**
     * Sends a portal request and waits for its response.
     *
     * @param method the method of the screen cast interface
     * @param signature the method's signature: the session path and parent window, if any, then the options
     * @param options fills the options
     * @return the response's results
     * @throws MediaException.Io if the response is not a success
     */
    private Map<?, ?> request(String method, String signature, Options options) {
        var token = token();
        var expected = PORTAL_PATH + "/request/" + bus.uniqueName().substring(1).replace('.', '_') + "/" + token;
        bus.addMatch("type='signal',interface='org.freedesktop.portal.Request',member='Response',path='" + expected + "'",
                CALL_TIMEOUT);
        var values = new LinkedHashMap<String, DBusMessage.Variant>();
        values.put("handle_token", string(token));
        options.fill(values);
        var arguments = new ArrayList<>();
        if (signature.startsWith("o")) {
            arguments.add(session);
        }
        if (signature.startsWith("os")) {
            arguments.add("");
        }
        arguments.add(values);
        var reply = bus.call(DBusMessage.call(PORTAL, PORTAL_PATH, SCREEN_CAST, method, signature, arguments.toArray()),
                CALL_TIMEOUT);
        var handle = reply.body().isEmpty() ? expected : (String) reply.body().getFirst();
        if (!handle.equals(expected)) {
            bus.addMatch("type='signal',interface='org.freedesktop.portal.Request',member='Response',path='" + handle + "'",
                    CALL_TIMEOUT);
        }
        var response = bus.awaitSignal(signal -> handle.equals(signal.path()) && "Response".equals(signal.member())
                && signal.signature().equals("ua{sv}"), RESPONSE_TIMEOUT)
                .orElseThrow(() -> failure("the screen cast portal did not answer " + method + " within " + RESPONSE_TIMEOUT));
        var code = (Long) response.body().get(0);
        if (code != 0) {
            throw failure(method + (code == 1 ? " was cancelled by the user" : " failed in the screen cast portal (response " + code + ")"));
        }
        return (Map<?, ?>) response.body().get(1);
    }

    /**
     * Returns the value of a result.
     *
     * @throws MediaException.Io if the result is missing
     */
    private static Object value(Map<?, ?> results, String key) {
        if (results.get(key) instanceof DBusMessage.Variant variant) {
            return variant.value();
        }
        throw failure("the screen cast portal did not return " + key);
    }

    /**
     * Reads the {@code streams} result of {@code Start}: {@code a(ua{sv})}, each stream a PipeWire
     * node and its properties.
     *
     * @param results the results of the response
     * @return the streams
     */
    static List<Stream> streams(Map<?, ?> results) {
        if (!(results.get("streams") instanceof DBusMessage.Variant variant) || !variant.signature().equals("a(ua{sv})")) {
            return List.of();
        }
        var streams = new ArrayList<Stream>();
        for (var entry : (List<?>) variant.value()) {
            var fields = (List<?>) entry;
            var node = (Long) fields.get(0);
            var properties = (Map<?, ?>) fields.get(1);
            int[] position = pair(properties.get("position"));
            int[] size = pair(properties.get("size"));
            var type = properties.get("source_type") instanceof DBusMessage.Variant source && source.value() instanceof Long number
                    ? number : 0L;
            streams.add(new Stream(node, position == null ? 0 : position[0], position == null ? 0 : position[1],
                    size == null ? 0 : size[0], size == null ? 0 : size[1], type));
        }
        return streams;
    }

    /**
     * Reads an {@code (ii)} property.
     *
     * @return the two values, or {@code null} when the property is missing or of another type
     */
    private static int[] pair(Object property) {
        if (property instanceof DBusMessage.Variant variant && variant.signature().equals("(ii)")) {
            var fields = (List<?>) variant.value();
            return new int[]{(Integer) fields.get(0), (Integer) fields.get(1)};
        }
        return null;
    }

    /**
     * Returns a new request token.
     */
    private static String token() {
        return "smalt" + ProcessHandle.current().pid() + "_" + TOKENS.incrementAndGet();
    }

    /**
     * Wraps a string option.
     */
    private static DBusMessage.Variant string(String value) {
        return new DBusMessage.Variant("s", value);
    }

    /**
     * Builds a failure of the portal.
     */
    private static MediaException.Io failure(String message) {
        var cause = new IOException(message);
        return new MediaException.Io(message, cause);
    }

    /**
     * Closes the session, which ends every stream of the cast, and the bus connection.
     */
    @Override
    public void close() {
        try {
            if (session != null) {
                bus.call(DBusMessage.call(PORTAL, session, "org.freedesktop.portal.Session", "Close", ""), CALL_TIMEOUT);
            }
        } catch (MediaException ignored) {
            // the session ends with the connection anyway
        } finally {
            session = null;
            bus.close();
        }
    }

    /**
     * Describes a shared stream.
     *
     * @param node the PipeWire node
     * @param x the left edge of a monitor in the desktop, or {@code 0}
     * @param y the top edge of a monitor in the desktop, or {@code 0}
     * @param width the logical width, or {@code 0} when the portal did not say
     * @param height the logical height, or {@code 0} when the portal did not say
     * @param sourceType the source type, or {@code 0} when the portal did not say
     */
    record Stream(long node, int x, int y, int width, int height, long sourceType) {
    }

    /**
     * A started cast: its stream and the PipeWire connection, which the caller owns.
     *
     * @param portal the portal session, closed with the cast
     * @param stream the shared stream
     * @param pipewire the descriptor of the PipeWire connection
     * @param cursorEmbedded whether the frames carry the pointer
     */
    record Cast(ScreenCastPortal portal, Stream stream, int pipewire, boolean cursorEmbedded) implements AutoCloseable {
        /**
         * Ends the cast; the PipeWire descriptor belongs to whoever connected with it.
         */
        @Override
        public void close() {
            portal.close();
        }
    }
}
