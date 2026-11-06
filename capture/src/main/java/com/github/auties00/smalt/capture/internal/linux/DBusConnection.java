package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * A connection to the D-Bus session bus that can receive Unix file descriptors.
 *
 * <p>The connection speaks the D-Bus wire protocol itself over a Unix domain socket of the bus
 * address in {@code DBUS_SESSION_BUS_ADDRESS} ({@code unix:path=...} or {@code unix:abstract=...}),
 * through the C library's {@code socket}, {@code send} and {@code recvmsg}, so that the descriptors a
 * service passes ({@code SCM_RIGHTS}) are received. It authenticates with {@code EXTERNAL},
 * negotiates descriptor passing, and says {@code Hello}. {@link #call} sends a method call and waits
 * for its reply; the signals that arrive meanwhile are kept for {@link #awaitSignal}. A received
 * descriptor that no returned message claims is closed. Instances are used by one thread at a time.
 */
final class DBusConnection implements AutoCloseable {
    /**
     * The bus daemon's name, path and interface.
     */
    static final String BUS_NAME = "org.freedesktop.DBus", BUS_PATH = "/org/freedesktop/DBus";

    /**
     * The size of the native receive buffer.
     */
    private static final int RECEIVE_BYTES = 64 * 1024;

    /**
     * The largest number of descriptors one {@code recvmsg} accepts.
     */
    private static final int MAX_FDS = 32;

    /**
     * The C library bindings.
     */
    private final Posix posix = new Posix();

    /**
     * The native buffers of the socket calls.
     */
    private final Arena arena = Arena.ofShared();

    /**
     * The socket.
     */
    private final int fd;

    /**
     * The native receive buffer.
     */
    private final MemorySegment receive;

    /**
     * The native control message buffer.
     */
    private final MemorySegment control;

    /**
     * The {@code struct msghdr} and {@code struct iovec} of {@code recvmsg}.
     */
    private final MemorySegment header;

    /**
     * The bytes received and not yet decoded.
     */
    private byte[] pending = new byte[RECEIVE_BYTES];

    /**
     * The number of valid bytes of {@link #pending}.
     */
    private int pendingLength;

    /**
     * The descriptors received and not yet claimed by a message.
     */
    private final ArrayDeque<Integer> pendingFds = new ArrayDeque<>();

    /**
     * The signals received while waiting for replies.
     */
    private final ArrayDeque<DBusMessage> signals = new ArrayDeque<>();

    /**
     * The serial of the next message.
     */
    private long serial = 1;

    /**
     * The unique name the bus gave the connection.
     */
    private String uniqueName;

    /**
     * Whether the connection is closed.
     */
    private boolean closed;

    /**
     * Wraps a connected socket.
     */
    private DBusConnection(int fd) {
        this.fd = fd;
        this.receive = arena.allocate(RECEIVE_BYTES, 8);
        this.control = arena.allocate(Posix.CMSG_DATA + 4L * MAX_FDS, 8);
        this.header = arena.allocate(Posix.MSGHDR.byteSize() + Posix.IOVEC.byteSize(), 8);
    }

    /**
     * Connects to the session bus.
     *
     * @param timeout how long the authentication and {@code Hello} may take
     * @return the connection
     * @throws MediaException.Unsupported if no session bus address is set or none of its addresses
     * is a Unix socket
     * @throws MediaException.Io if the bus refuses the connection or descriptor passing
     */
    static DBusConnection session(Duration timeout) {
        var address = System.getenv("DBUS_SESSION_BUS_ADDRESS");
        if (address == null || address.isBlank()) {
            var runtime = System.getenv("XDG_RUNTIME_DIR");
            if (runtime == null || runtime.isBlank()) {
                throw new MediaException.Unsupported("d-bus", "DBUS_SESSION_BUS_ADDRESS and XDG_RUNTIME_DIR are not set");
            }
            address = "unix:path=" + runtime + "/bus";
        }
        var paths = socketPaths(address);
        if (paths.isEmpty()) {
            throw new MediaException.Unsupported("d-bus", "the session bus address " + address + " names no Unix socket");
        }
        var posix = new Posix();
        var error = 0;
        for (var path : paths) {
            var fd = posix.connectUnix(path);
            if (fd >= 0) {
                var connection = new DBusConnection(fd);
                try {
                    connection.authenticate(timeout);
                    var reply = connection.call(DBusMessage.call(BUS_NAME, BUS_PATH, BUS_NAME, "Hello", ""), timeout);
                    connection.uniqueName = (String) reply.body().getFirst();
                    return connection;
                } catch (RuntimeException failure) {
                    connection.close();
                    throw failure;
                }
            }
            error = posix.errno();
        }
        throw new MediaException.Io("cannot connect to the session bus", Posix.failure("connect " + address, error));
    }

    /**
     * Returns the socket paths of a bus address, in order.
     *
     * @param address the address, {@code ;}-separated transports of {@code key=value} pairs
     * @return the paths, an abstract socket's starting with a NUL byte
     */
    static List<byte[]> socketPaths(String address) {
        var paths = new ArrayList<byte[]>();
        for (var transport : address.split(";")) {
            var colon = transport.indexOf(':');
            if (colon < 0 || !transport.substring(0, colon).equals("unix")) {
                continue;
            }
            for (var pair : transport.substring(colon + 1).split(",")) {
                var equals = pair.indexOf('=');
                if (equals < 0) {
                    continue;
                }
                var key = pair.substring(0, equals);
                var value = unescape(pair.substring(equals + 1));
                if (key.equals("path")) {
                    paths.add(value);
                } else if (key.equals("abstract")) {
                    var path = new byte[value.length + 1];
                    System.arraycopy(value, 0, path, 1, value.length);
                    paths.add(path);
                }
            }
        }
        return paths;
    }

    /**
     * Undoes the {@code %XX} escapes of an address value; a {@code %} not followed by two hex
     * digits is kept as it is.
     */
    private static byte[] unescape(String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        var out = new byte[bytes.length];
        var length = 0;
        for (var index = 0; index < bytes.length; index++) {
            var high = index + 2 < bytes.length ? Character.digit(bytes[index + 1], 16) : -1;
            var low = index + 2 < bytes.length ? Character.digit(bytes[index + 2], 16) : -1;
            if (bytes[index] == '%' && high >= 0 && low >= 0) {
                out[length++] = (byte) (high << 4 | low);
                index += 2;
            } else {
                out[length++] = bytes[index];
            }
        }
        return Arrays.copyOf(out, length);
    }

    /**
     * Returns the unique name the bus gave the connection.
     *
     * @return the name, such as {@code :1.42}
     */
    String uniqueName() {
        return uniqueName;
    }

    /**
     * Authenticates with {@code EXTERNAL} and negotiates descriptor passing.
     */
    private void authenticate(Duration timeout) {
        var uid = Long.toString(posix.uid());
        var hex = HexFormat.of().formatHex(uid.getBytes(StandardCharsets.US_ASCII));
        sendRaw(("\0AUTH EXTERNAL " + hex + "\r\n").getBytes(StandardCharsets.US_ASCII));
        var deadline = System.nanoTime() + timeout.toNanos();
        var answer = readLine(deadline);
        if (!answer.startsWith("OK ")) {
            throw failure("the session bus refused the authentication: " + answer);
        }
        sendRaw("NEGOTIATE_UNIX_FD\r\n".getBytes(StandardCharsets.US_ASCII));
        answer = readLine(deadline);
        if (!answer.equals("AGREE_UNIX_FD")) {
            throw failure("the session bus does not pass file descriptors: " + answer);
        }
        sendRaw("BEGIN\r\n".getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Reads one line of the authentication exchange.
     */
    private String readLine(long deadline) {
        while (true) {
            for (var index = 0; index + 1 < pendingLength; index++) {
                if (pending[index] == '\r' && pending[index + 1] == '\n') {
                    var line = new String(pending, 0, index, StandardCharsets.US_ASCII);
                    consume(index + 2);
                    return line;
                }
            }
            if (pendingLength > 16 * 1024) {
                throw failure("the session bus sent an overlong authentication line");
            }
            if (!fill(deadline)) {
                throw failure("the session bus did not answer the authentication in time");
            }
        }
    }

    /**
     * Sends a method call and waits for its reply.
     *
     * @param call the method call, without a serial
     * @param timeout how long to wait
     * @return the method return
     * @throws MediaException.Io if the reply is an error, the bus closes the connection or the reply
     * does not arrive in time
     */
    DBusMessage call(DBusMessage call, Duration timeout) {
        var sent = send(call);
        var deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            var message = next(deadline);
            if (message == null) {
                throw failure(call.iface() + "." + call.member() + " got no reply within " + timeout);
            }
            if (message.replySerial() == sent && (message.type() == DBusMessage.METHOD_RETURN || message.type() == DBusMessage.ERROR)) {
                if (message.type() == DBusMessage.ERROR) {
                    var detail = message.body().isEmpty() ? "" : ": " + message.body().getFirst();
                    throw failure(call.iface() + "." + call.member() + " failed with " + message.errorName() + detail);
                }
                return message;
            }
            if (message.type() == DBusMessage.SIGNAL) {
                signals.add(message);
            } else {
                closeDescriptors(message);
            }
        }
    }

    /**
     * Sends a message without waiting.
     *
     * @param message the message to send, without a serial
     * @return the serial it was sent with
     */
    long send(DBusMessage message) {
        var current = serial++;
        sendRaw(message.encode(current));
        return current;
    }

    /**
     * Subscribes to the signals a match rule selects.
     *
     * @param rule the rule, such as {@code type='signal',path='/a/b'}
     * @param timeout how long the bus may take to accept it
     */
    void addMatch(String rule, Duration timeout) {
        call(DBusMessage.call(BUS_NAME, BUS_PATH, BUS_NAME, "AddMatch", "s", rule), timeout);
    }

    /**
     * Waits for a signal.
     *
     * @param wanted selects the signal; the others received meanwhile are dropped
     * @param timeout how long to wait
     * @return the signal, or empty when none arrived in time
     * @throws MediaException.Io if the bus closes the connection
     */
    Optional<DBusMessage> awaitSignal(Predicate<DBusMessage> wanted, Duration timeout) {
        while (!signals.isEmpty()) {
            var queued = signals.poll();
            if (wanted.test(queued)) {
                return Optional.of(queued);
            }
            closeDescriptors(queued);
        }
        var deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            var message = next(deadline);
            if (message == null) {
                return Optional.empty();
            }
            if (message.type() == DBusMessage.SIGNAL && wanted.test(message)) {
                return Optional.of(message);
            }
            closeDescriptors(message);
        }
    }

    /**
     * Closes the descriptors a message carries, which nobody will use.
     */
    private void closeDescriptors(DBusMessage message) {
        closeDescriptors(message.signature(), message.body());
    }

    /**
     * Closes the descriptors among values.
     */
    private void closeDescriptors(String signature, List<Object> values) {
        if (signature.indexOf('h') < 0 && signature.indexOf('v') < 0) {
            return;
        }
        var position = 0;
        for (var value : values) {
            var end = DBusMessage.typeEnd(signature, position);
            closeDescriptor(signature.substring(position, end), value);
            position = end;
        }
    }

    /**
     * Closes the descriptors of one value.
     */
    private void closeDescriptor(String type, Object value) {
        switch (type.charAt(0)) {
            case 'h' -> posix.close((Integer) value);
            case 'v' -> closeDescriptor(((DBusMessage.Variant) value).signature(), ((DBusMessage.Variant) value).value());
            case 'a' -> {
                var element = type.substring(1);
                if (element.charAt(0) == '{') {
                    var keyEnd = DBusMessage.typeEnd(element, 1);
                    var valueType = element.substring(keyEnd, element.length() - 1);
                    for (var entry : ((java.util.Map<?, ?>) value).values()) {
                        closeDescriptor(valueType, entry);
                    }
                } else {
                    for (var item : (List<?>) value) {
                        closeDescriptor(element, item);
                    }
                }
            }
            case '(' -> {
                @SuppressWarnings("unchecked")
                var fields = (List<Object>) value;
                closeDescriptors(type.substring(1, type.length() - 1), fields);
            }
            default -> {
            }
        }
    }

    /**
     * Receives the next complete message.
     *
     * @param deadline the {@link System#nanoTime()} to give up at
     * @return the message, or {@code null} when none arrived before the deadline
     */
    private DBusMessage next(long deadline) {
        while (true) {
            var length = DBusMessage.length(pending, 0, pendingLength);
            if (length >= 0 && pendingLength >= length) {
                var message = DBusMessage.decode(pending, 0, length, this::takeDescriptors);
                consume(length);
                return message;
            }
            if (!fill(deadline)) {
                return null;
            }
        }
    }

    /**
     * Takes the descriptors a message claims from the received ones.
     *
     * @param count the number of descriptors the header of the message names
     * @return the descriptors
     * @throws MediaException.Io if fewer arrived
     */
    private int[] takeDescriptors(int count) {
        if (count > pendingFds.size()) {
            throw failure("a D-Bus message names " + count + " descriptors, " + pendingFds.size() + " arrived");
        }
        var fds = new int[count];
        for (var index = 0; index < count; index++) {
            fds[index] = pendingFds.poll();
        }
        return fds;
    }

    /**
     * Removes bytes from the start of {@link #pending}.
     */
    private void consume(int count) {
        System.arraycopy(pending, count, pending, 0, pendingLength - count);
        pendingLength -= count;
    }

    /**
     * Receives more bytes and descriptors.
     *
     * @param deadline the {@link System#nanoTime()} to give up at
     * @return whether bytes arrived before the deadline
     */
    private boolean fill(long deadline) {
        try (var scratch = Arena.ofConfined()) {
            var poll = scratch.allocate(Posix.POLLFD);
            while (true) {
                var left = (deadline - System.nanoTime()) / 1_000_000;
                if (left <= 0) {
                    return false;
                }
                poll.set(JAVA_INT, 0, fd);
                poll.set(JAVA_SHORT, Posix.POLLFD_EVENTS, Posix.POLLIN);
                poll.set(JAVA_SHORT, Posix.POLLFD_REVENTS, (short) 0);
                var ready = posix.poll(poll, 1, (int) Math.min(left, Integer.MAX_VALUE));
                if (ready < 0 && posix.errno() != Posix.EINTR) {
                    throw new MediaException.Io("the session bus connection failed", Posix.failure("poll", posix.errno()));
                }
                if (ready > 0) {
                    break;
                }
            }
        }
        var iov = header.asSlice(Posix.MSGHDR.byteSize());
        iov.set(ADDRESS, 0, receive);
        iov.set(JAVA_LONG, 8, RECEIVE_BYTES);
        header.asSlice(0, Posix.MSGHDR.byteSize()).fill((byte) 0);
        header.set(ADDRESS, 16, iov);
        header.set(JAVA_LONG, 24, 1);
        header.set(ADDRESS, 32, control);
        header.set(JAVA_LONG, 40, control.byteSize());
        var received = posix.recvmsg(fd, header);
        if (received < 0) {
            if (posix.errno() == Posix.EAGAIN) {
                return true;
            }
            throw new MediaException.Io("the session bus connection failed", Posix.failure("recvmsg", posix.errno()));
        }
        if (received == 0) {
            throw failure("the session bus closed the connection");
        }
        collectDescriptors(header.get(JAVA_LONG, 40));
        if (pendingLength + received > pending.length) {
            if (pendingLength + received > DBusMessage.MAX_MESSAGE) {
                throw failure("the session bus sent more than " + DBusMessage.MAX_MESSAGE + " bytes without a message");
            }
            pending = Arrays.copyOf(pending, (int) Math.max(pending.length * 2L, pendingLength + received));
        }
        MemorySegment.copy(receive, JAVA_BYTE, 0, pending, pendingLength, (int) received);
        pendingLength += (int) received;
        return true;
    }

    /**
     * Takes the descriptors of the {@code SCM_RIGHTS} control messages of a {@code recvmsg}.
     *
     * @param length the control length the call returned
     */
    private void collectDescriptors(long length) {
        var offset = 0L;
        while (offset + Posix.CMSG_DATA <= length) {
            var size = control.get(JAVA_LONG, offset);
            if (size < Posix.CMSG_DATA || offset + size > length) {
                break;
            }
            if (control.get(JAVA_INT, offset + 8) == Posix.SOL_SOCKET && control.get(JAVA_INT, offset + 12) == Posix.SCM_RIGHTS) {
                for (var at = Posix.CMSG_DATA; at + 4 <= size; at += 4) {
                    pendingFds.add(control.get(JAVA_INT, offset + at));
                }
            }
            offset += (size + 7) & ~7L;
        }
    }

    /**
     * Sends bytes, all of them.
     */
    private void sendRaw(byte[] bytes) {
        try (var scratch = Arena.ofConfined()) {
            var native_ = scratch.allocate(bytes.length);
            MemorySegment.copy(bytes, 0, native_, JAVA_BYTE, 0, bytes.length);
            var sent = 0L;
            while (sent < bytes.length) {
                var count = posix.send(fd, native_.asSlice(sent), bytes.length - sent);
                if (count < 0) {
                    throw new MediaException.Io("the session bus connection failed", Posix.failure("send", posix.errno()));
                }
                sent += count;
            }
        }
    }

    /**
     * Builds a failure of the connection.
     */
    private static MediaException.Io failure(String message) {
        var cause = new IOException(message);
        return new MediaException.Io(message, cause);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        while (!pendingFds.isEmpty()) {
            posix.close(pendingFds.poll());
        }
        for (var signal : signals) {
            closeDescriptors(signal);
        }
        signals.clear();
        posix.close(fd);
        arena.close();
    }
}
