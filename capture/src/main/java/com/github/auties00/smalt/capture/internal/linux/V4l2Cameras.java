package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.CaptureMode;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Finds the Video4Linux2 cameras of the system.
 *
 * <p>Every {@code /dev/videoN} node is opened and kept when {@code VIDIOC_QUERYCAP} reports a
 * single-planar or multi-planar streaming capture node (using {@code device_caps}, so the metadata nodes USB
 * Video Class cameras create next to their capture node are skipped) that offers at least one
 * pixel format this backend converts. Nodes that cannot be opened, typically for lack of
 * permission (membership of the {@code video} group), or that fail while being queried are
 * skipped. Cameras are listed in node order; Linux has no notion of a default camera, so the
 * first one stands for it.
 *
 * <p>A camera's identifier is its persistent {@code /dev/v4l/by-id} link when udev created one
 * (it survives re-plugging and renumbering, and opens the same node), otherwise the node path.
 * {@link #first()} and {@link #find(String)} query only the nodes up to the one they return, and
 * agree with {@link #list()}.
 */
final class V4l2Cameras {
    /**
     * The name of a capture node.
     */
    private static final Pattern NODE = Pattern.compile("video(\\d+)");

    /**
     * The real path of a node a camera identifier may resolve to.
     */
    private static final Pattern NODE_PATH = Pattern.compile("/dev/video\\d+");

    /**
     * The directory of the device nodes.
     */
    private static final Path DEV = Path.of("/dev");

    /**
     * The directory of udev's persistent links.
     */
    private static final Path BY_ID = Path.of("/dev/v4l/by-id");

    /**
     * Prevents instantiation.
     */
    private V4l2Cameras() {
        throw new AssertionError();
    }

    /**
     * Lists the identifiers of the cameras without enumerating their modes.
     *
     * @return the identifiers in node order
     * @throws MediaException.Io if {@code /dev} cannot be listed
     */
    static List<String> ids() {
        var stableIds = stableIds();
        var posix = new Posix();
        var ids = new ArrayList<String>();
        for (var node : nodes()) {
            var id = stableIds.getOrDefault(node.toString(), node.toString());
            probe(posix, node, id, false).ifPresent(camera -> ids.add(camera.id()));
        }
        return List.copyOf(ids);
    }

    /**
     * Lists the cameras.
     *
     * @return the cameras in node order
     * @throws MediaException.Io if {@code /dev} cannot be listed
     */
    static List<CaptureDevice.Camera> list() {
        var stableIds = stableIds();
        var posix = new Posix();
        var cameras = new ArrayList<CaptureDevice.Camera>();
        for (var node : nodes()) {
            probe(posix, node, stableIds.getOrDefault(node.toString(), node.toString()), true).ifPresent(cameras::add);
        }
        return List.copyOf(cameras);
    }

    /**
     * Returns the first camera in node order, querying the nodes before it but none after it.
     *
     * @return the camera, or empty when there is none
     * @throws MediaException.Io if {@code /dev} cannot be listed
     */
    static Optional<CaptureDevice.Camera> first() {
        var stableIds = stableIds();
        var posix = new Posix();
        for (var node : nodes()) {
            var camera = probe(posix, node, stableIds.getOrDefault(node.toString(), node.toString()), true);
            if (camera.isPresent()) {
                return camera;
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the camera {@link #list()} reports with an identifier, querying only its node.
     *
     * @param id the identifier
     * @return the camera, or empty when no camera has that identifier
     */
    static Optional<CaptureDevice.Camera> find(String id) {
        String node;
        try {
            node = Path.of(id).toRealPath().toString();
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
        if (!NODE_PATH.matcher(node).matches() || !id.equals(stableIds().getOrDefault(node, node))) {
            return Optional.empty();
        }
        return probe(new Posix(), Path.of(node), id, true);
    }

    /**
     * Lists the capture node candidates.
     *
     * @return the {@code /dev/videoN} paths in node order; empty when {@code /dev} does not exist
     * @throws MediaException.Io if {@code /dev} cannot be listed
     */
    private static List<Path> nodes() {
        if (!Files.isDirectory(DEV)) {
            return List.of();
        }
        var nodes = new ArrayList<Path>();
        try (var stream = Files.newDirectoryStream(DEV, "video*")) {
            for (var node : stream) {
                if (NODE.matcher(node.getFileName().toString()).matches()) {
                    nodes.add(node);
                }
            }
        } catch (IOException exception) {
            throw new MediaException.Io("cannot list " + DEV, exception);
        } catch (DirectoryIteratorException exception) {
            throw new MediaException.Io("cannot list " + DEV, exception.getCause());
        }
        nodes.sort(Comparator.comparingLong(V4l2Cameras::nodeNumber));
        return nodes;
    }

    /**
     * Opens a node and reads it as a camera.
     *
     * @param posix the C library
     * @param node the node path
     * @param id the identifier the camera gets
     * @param withModes whether to enumerate the modes; without, the camera has none
     * @return the camera, or empty when the node cannot be opened, is not a capture node this
     * backend converts, or fails while being queried
     */
    private static Optional<CaptureDevice.Camera> probe(Posix posix, Path node, String id, boolean withModes) {
        var fd = posix.open(node.toString(), Posix.O_RDWR | Posix.O_NONBLOCK | Posix.O_CLOEXEC);
        if (fd < 0) {
            return Optional.empty();
        }
        try {
            var capability = V4l2Formats.query((request, argument) -> posix.ioctl(fd, request, argument));
            var device = capability.device((request, argument) -> posix.ioctl(fd, request, argument));
            if (!capability.canCapture() || V4l2Formats.pixelFormats(device).isEmpty()) {
                return Optional.empty();
            }
            var modes = withModes ? V4l2Formats.modes(V4l2Formats.offers(device)) : List.<CaptureMode>of();
            return Optional.of(new CaptureDevice.Camera(id, capability.card(), modes));
        } catch (MediaException ignored) {
            // The node vanished or misbehaved while being queried: it is not a usable camera.
            return Optional.empty();
        } finally {
            posix.close(fd);
        }
    }

    /**
     * Resolves a camera identifier to the capture node it designates.
     *
     * @param id the identifier, a node path or a link to one
     * @return the real path of the node
     * @throws MediaException.Io          if the identifier does not exist
     * @throws MediaException.Unsupported if the identifier is not a Video4Linux2 capture node
     */
    static String nodePath(String id) {
        Path real;
        try {
            real = Path.of(id).toRealPath();
        } catch (NoSuchFileException exception) {
            throw new MediaException.Io("the camera " + id + " is not connected", exception);
        } catch (IOException exception) {
            throw new MediaException.Io("cannot resolve the camera " + id, exception);
        } catch (RuntimeException exception) {
            throw new MediaException.Unsupported("v4l2 device", id + " is not a device path");
        }
        var path = real.toString();
        if (!NODE_PATH.matcher(path).matches()) {
            throw new MediaException.Unsupported("v4l2 device", id + " is not a Video4Linux2 node");
        }
        return path;
    }

    /**
     * Maps each node to its persistent link.
     *
     * @return the first {@code /dev/v4l/by-id} link, in name order, of each node's real path
     */
    private static Map<String, String> stableIds() {
        var links = new ArrayList<Path>();
        if (Files.isDirectory(BY_ID)) {
            try (var stream = Files.newDirectoryStream(BY_ID)) {
                for (var link : stream) {
                    links.add(link);
                }
            } catch (IOException | DirectoryIteratorException exception) {
                return Map.of();
            }
        }
        links.sort(Comparator.comparing(Path::toString));
        var ids = new HashMap<String, String>();
        for (var link : links) {
            try {
                ids.putIfAbsent(link.toRealPath().toString(), link.toString());
            } catch (IOException exception) {
                // A dangling link names no node.
            }
        }
        return ids;
    }

    /**
     * Returns the number of a node.
     *
     * @param node the node path
     * @return {@code N} of {@code /dev/videoN}, or {@link Long#MAX_VALUE} when it does not fit
     */
    private static long nodeNumber(Path node) {
        var matcher = NODE.matcher(node.getFileName().toString());
        if (!matcher.matches()) {
            return Long.MAX_VALUE;
        }
        var digits = matcher.group(1);
        return digits.length() > 18 ? Long.MAX_VALUE : Long.parseLong(digits);
    }
}
