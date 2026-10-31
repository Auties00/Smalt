package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.CaptureDevice;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists the outputs of a Wayland compositor as screens, speaking the Wayland wire protocol over the
 * compositor's socket.
 *
 * <p>The client binds every {@code wl_output} global (up to version 4, which names the output) and,
 * when the compositor offers it, the {@code zxdg_output_manager_v1} global, whose
 * {@code zxdg_output_v1} objects give each output's position and size in the logical coordinates
 * of the desktop; without it the size is the current mode divided by the output's scale. Two
 * {@code wl_display.sync} round trips collect the events the binds produce. A screen's identifier is
 * {@value #PREFIX} followed by the output's name; the first output stands for the primary one,
 * since Wayland has none. The compositor's socket is {@code WAYLAND_DISPLAY}, an absolute path or a
 * name in {@code XDG_RUNTIME_DIR}.
 */
final class WaylandOutputs {
    /**
     * The prefix of the identifiers of Wayland screens.
     */
    static final String PREFIX = "wayland:";

    /**
     * How long the compositor may take to answer.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /**
     * The largest message the protocol allows.
     */
    private static final int MAX_MESSAGE = 4096;

    private WaylandOutputs() {
        throw new AssertionError();
    }

    /**
     * Returns the compositor's socket of the session.
     *
     * @return the path, or {@code null} outside a Wayland session
     */
    static Path socket() {
        var display = System.getenv("WAYLAND_DISPLAY");
        if (display == null || display.isBlank()) {
            return null;
        }
        if (display.startsWith("/")) {
            return Path.of(display);
        }
        var runtime = System.getenv("XDG_RUNTIME_DIR");
        return runtime == null || runtime.isBlank() ? null : Path.of(runtime, display);
    }

    /**
     * Lists the outputs of the session's compositor.
     *
     * @return the screens, or an empty list outside a Wayland session or when the compositor does
     * not answer
     */
    static List<CaptureDevice.Screen> list() {
        var socket = socket();
        return socket == null ? List.of() : list(socket);
    }

    /**
     * Lists the outputs of a compositor.
     *
     * @param socket the compositor's socket
     * @return the screens, or an empty list when the compositor does not answer
     */
    static List<CaptureDevice.Screen> list(Path socket) {
        try {
            return list(socket, TIMEOUT);
        } catch (IOException | MediaException exception) {
            return List.of();
        }
    }

    /**
     * Lists the outputs of a compositor.
     *
     * @param socket the compositor's socket
     * @param timeout how long each round trip may take
     * @return the screens
     * @throws IOException if the connection fails or times out
     * @throws MediaException.Malformed if the compositor sends a malformed message or an error
     */
    static List<CaptureDevice.Screen> list(Path socket, Duration timeout) throws IOException {
        try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX);
             var selector = Selector.open()) {
            channel.connect(UnixDomainSocketAddress.of(socket));
            channel.configureBlocking(false);
            channel.register(selector, SelectionKey.OP_READ);
            var client = new Client(channel, selector, timeout);
            return client.run();
        }
    }

    /**
     * Lists the outputs a recorded stream of compositor events describes, as {@link #list(Path, Duration)}
     * would if the compositor sent exactly those events.
     *
     * <p>The client's requests are discarded, and the object ids it assigns are those of a live
     * listing: the registry {@code 2}, the first round trip's callback {@code 3}, then the output manager,
     * if advertised, and each output followed by its {@code zxdg_output_v1}, then the two other callbacks.
     *
     * @param events the events, as the compositor would write them to the socket
     * @return the screens
     * @throws IOException if the events end before the listing completes
     * @throws MediaException.Malformed if an event is malformed or is an error
     */
    static List<CaptureDevice.Screen> replay(byte[] events) throws IOException {
        return new Client(ByteBuffer.wrap(events), Duration.ZERO).run();
    }

    /**
     * The state of one listing.
     */
    private static final class Client {
        private final SocketChannel channel;
        private final Selector selector;
        private final Duration timeout;
        private final ByteBuffer input;
        private final Map<Integer, Output> outputs = new LinkedHashMap<>();
        private final Map<Integer, Output> xdgOutputs = new LinkedHashMap<>();
        private final List<int[]> outputGlobals = new ArrayList<>();
        private int managerGlobal = -1;
        private int managerVersion;
        private int nextId = 2;
        private int doneCallback;
        private boolean done;

        Client(SocketChannel channel, Selector selector, Duration timeout) {
            this.channel = channel;
            this.selector = selector;
            this.timeout = timeout;
            this.input = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);
            input.flip();
        }

        Client(ByteBuffer events, Duration timeout) {
            this.channel = null;
            this.selector = null;
            this.timeout = timeout;
            this.input = events.order(ByteOrder.LITTLE_ENDIAN);
        }

        List<CaptureDevice.Screen> run() throws IOException {
            var registry = nextId++;
            send(1, 1, ints(registry));
            roundTrip();
            var manager = 0;
            if (managerGlobal >= 0) {
                manager = nextId++;
                bind(registry, managerGlobal, "zxdg_output_manager_v1", Math.min(managerVersion, 3), manager);
            }
            for (var global : outputGlobals) {
                var id = nextId++;
                bind(registry, global[0], "wl_output", Math.min(global[1], 4), id);
                var output = new Output();
                outputs.put(id, output);
                if (manager != 0) {
                    var xdg = nextId++;
                    send(manager, 1, ints(xdg, id));
                    xdgOutputs.put(xdg, output);
                }
            }
            roundTrip();
            roundTrip();
            var screens = new ArrayList<CaptureDevice.Screen>();
            var index = 0;
            for (var output : outputs.values()) {
                var name = output.name != null ? output.name : output.xdgName != null ? output.xdgName : "output-" + index;
                var width = output.logicalWidth > 0 ? output.logicalWidth : output.modeWidth / Math.max(1, output.scale);
                var height = output.logicalHeight > 0 ? output.logicalHeight : output.modeHeight / Math.max(1, output.scale);
                var x = output.logicalWidth > 0 ? output.logicalX : output.x;
                var y = output.logicalWidth > 0 ? output.logicalY : output.y;
                if (width > 0 && height > 0) {
                    var label = output.description != null ? output.description
                            : output.make != null || output.model != null ? (nonNull(output.make) + " " + nonNull(output.model)).trim()
                            : name;
                    screens.add(new CaptureDevice.Screen(PREFIX + name, label.isEmpty() ? name : label, x, y, width, height,
                            screens.isEmpty()));
                }
                index++;
            }
            return List.copyOf(screens);
        }

        private static String nonNull(String value) {
            return value == null ? "" : value;
        }

        /**
         * Sends {@code wl_registry.bind} with an untyped new id.
         */
        private void bind(int registry, int name, String iface, int version, int id) throws IOException {
            var body = ByteBuffer.allocate(4 + stringSize(iface) + 8).order(ByteOrder.LITTLE_ENDIAN);
            body.putInt(name);
            putString(body, iface);
            body.putInt(version).putInt(id);
            send(registry, 0, body.array());
        }

        /**
         * Sends {@code wl_display.sync} and handles events until its callback is done.
         */
        private void roundTrip() throws IOException {
            doneCallback = nextId++;
            done = false;
            send(1, 0, ints(doneCallback));
            var deadline = System.nanoTime() + timeout.toNanos();
            while (!done) {
                if (!dispatch()) {
                    receive(deadline);
                }
            }
        }

        /**
         * Handles one buffered event.
         *
         * @return whether a complete event was buffered
         */
        private boolean dispatch() {
            if (input.remaining() < 8) {
                return false;
            }
            var start = input.position();
            var object = input.getInt(start);
            var word = input.getInt(start + 4);
            var size = word >>> 16;
            var opcode = word & 0xFFFF;
            if (size < 8 || size > MAX_MESSAGE || (size & 3) != 0) {
                throw new MediaException.Malformed("a Wayland message of " + size + " bytes");
            }
            if (input.remaining() < size) {
                return false;
            }
            var message = input.slice(start + 8, size - 8).order(ByteOrder.LITTLE_ENDIAN);
            input.position(start + size);
            handle(object, opcode, message);
            return true;
        }

        /**
         * Handles an event.
         */
        private void handle(int object, int opcode, ByteBuffer message) {
            if (object == 1) {
                if (opcode == 0) {
                    integer(message);
                    var code = integer(message);
                    throw new MediaException.Malformed("the Wayland compositor reported error " + code + ": " + string(message));
                }
                return;
            }
            if (object == doneCallback && opcode == 0) {
                done = true;
                return;
            }
            if (object == 2 && opcode == 0) {
                var name = integer(message);
                var iface = string(message);
                var version = integer(message);
                if (iface.equals("wl_output")) {
                    outputGlobals.add(new int[]{name, version});
                } else if (iface.equals("zxdg_output_manager_v1")) {
                    managerGlobal = name;
                    managerVersion = version;
                }
                return;
            }
            var output = outputs.get(object);
            if (output != null) {
                switch (opcode) {
                    case 0 -> {
                        output.x = integer(message);
                        output.y = integer(message);
                        integer(message);
                        integer(message);
                        integer(message);
                        output.make = string(message);
                        output.model = string(message);
                    }
                    case 1 -> {
                        var flags = integer(message);
                        var width = integer(message);
                        var height = integer(message);
                        if ((flags & 1) != 0 || output.modeWidth == 0) {
                            output.modeWidth = width;
                            output.modeHeight = height;
                        }
                    }
                    case 3 -> output.scale = integer(message);
                    case 4 -> output.name = string(message);
                    case 5 -> output.description = string(message);
                    default -> {
                    }
                }
                return;
            }
            var xdg = xdgOutputs.get(object);
            if (xdg != null) {
                switch (opcode) {
                    case 0 -> {
                        xdg.logicalX = integer(message);
                        xdg.logicalY = integer(message);
                    }
                    case 1 -> {
                        xdg.logicalWidth = integer(message);
                        xdg.logicalHeight = integer(message);
                    }
                    case 3 -> xdg.xdgName = string(message);
                    case 4 -> {
                        if (xdg.description == null) {
                            xdg.description = string(message);
                        }
                    }
                    default -> {
                    }
                }
            }
        }

        /**
         * Reads more bytes.
         */
        private void receive(long deadline) throws IOException {
            if (channel == null) {
                throw new IOException("the recorded events end before the listing completes");
            }
            input.compact();
            try {
                while (true) {
                    var left = (deadline - System.nanoTime()) / 1_000_000;
                    if (left <= 0) {
                        throw new IOException("the Wayland compositor did not answer within " + timeout);
                    }
                    selector.select(left);
                    selector.selectedKeys().clear();
                    var read = channel.read(input);
                    if (read < 0) {
                        throw new IOException("the Wayland compositor closed the connection");
                    }
                    if (read > 0) {
                        return;
                    }
                    if (!input.hasRemaining()) {
                        throw new MediaException.Malformed("a Wayland message exceeds the buffer");
                    }
                }
            } finally {
                input.flip();
            }
        }

        /**
         * Sends a request.
         */
        private void send(int object, int opcode, byte[] body) throws IOException {
            if (channel == null) {
                return;
            }
            var message = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
            message.putInt(object).putInt((8 + body.length) << 16 | opcode).put(body).flip();
            while (message.hasRemaining()) {
                if (channel.write(message) == 0) {
                    Thread.onSpinWait();
                }
            }
        }

        private static byte[] ints(int... values) {
            var buffer = ByteBuffer.allocate(4 * values.length).order(ByteOrder.LITTLE_ENDIAN);
            for (var value : values) {
                buffer.putInt(value);
            }
            return buffer.array();
        }

        private static int stringSize(String value) {
            return 4 + ((value.getBytes(StandardCharsets.UTF_8).length + 1 + 3) & ~3);
        }

        private static void putString(ByteBuffer buffer, String value) {
            var bytes = value.getBytes(StandardCharsets.UTF_8);
            buffer.putInt(bytes.length + 1).put(bytes).put((byte) 0);
            while ((buffer.position() & 3) != 0) {
                buffer.put((byte) 0);
            }
        }

        /**
         * Reads an integer argument.
         *
         * @throws MediaException.Malformed if it leaves the message
         */
        private static int integer(ByteBuffer message) {
            if (message.remaining() < 4) {
                throw new MediaException.Malformed("a truncated Wayland event");
            }
            return message.getInt();
        }

        /**
         * Reads a string argument.
         *
         * @throws MediaException.Malformed if it leaves the message or is not terminated
         */
        private static String string(ByteBuffer message) {
            if (message.remaining() < 4) {
                throw new MediaException.Malformed("a truncated Wayland string");
            }
            var length = message.getInt();
            if (length == 0) {
                return null;
            }
            if (length < 0 || length > message.remaining()) {
                throw new MediaException.Malformed("a Wayland string of " + length + " bytes leaves its message");
            }
            var padded = (length + 3) & ~3;
            if (padded > message.remaining()) {
                throw new MediaException.Malformed("a Wayland string of " + length + " bytes leaves its message");
            }
            var bytes = new byte[length];
            message.get(bytes);
            message.position(message.position() + padded - length);
            if (bytes[length - 1] != 0) {
                throw new MediaException.Malformed("a Wayland string is not terminated");
            }
            return new String(bytes, 0, length - 1, StandardCharsets.UTF_8);
        }
    }

    /**
     * What the events said about one output.
     */
    private static final class Output {
        int x;
        int y;
        int modeWidth;
        int modeHeight;
        int scale = 1;
        int logicalX;
        int logicalY;
        int logicalWidth;
        int logicalHeight;
        String name;
        String xdgName;
        String description;
        String make;
        String model;
    }
}
