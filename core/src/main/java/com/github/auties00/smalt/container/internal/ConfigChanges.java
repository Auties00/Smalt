package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.track.Packet;

import java.util.Arrays;

/**
 * Reads the codec configuration changes that packets signal through
 * {@link com.github.auties00.smalt.track.PacketSideData#newCodecConfig()}.
 */
public final class ConfigChanges {
    /**
     * Prevents instantiation.
     */
    private ConfigChanges() {
        throw new AssertionError();
    }

    /**
     * Returns the codec configuration a packet establishes when it differs from the one in use.
     *
     * @param packet the packet whose side data may carry a new configuration
     * @param current the configuration in use, or {@code null} when there is none
     * @return the new configuration, or {@code null} when the packet keeps {@code current}
     */
    public static byte[] changed(Packet packet, byte[] current) {
        var next = packet.sideData().newCodecConfig().orElse(null);
        return next == null || Arrays.equals(next, current) ? null : next;
    }

    /**
     * Rejects a packet that changes the codec configuration of a container that stores one
     * configuration per track.
     *
     * @param packet the packet about to be written
     * @param current the configuration in use, or {@code null} when there is none
     * @param container the name of the container, for the message
     * @throws MediaException.Unsupported if the packet changes the configuration
     */
    public static void reject(Packet packet, byte[] current, String container) {
        if (changed(packet, current) != null) {
            throw unsupported(container, packet.trackId());
        }
    }

    /**
     * Returns the failure of a container that cannot store a codec configuration change.
     *
     * @param container the name of the container
     * @param trackId the identifier of the track that changes
     * @return the exception to throw
     */
    public static MediaException.Unsupported unsupported(String container, int trackId) {
        return new MediaException.Unsupported(container + " codec configuration change",
                "track " + trackId + " changes its codec configuration, which " + container
                        + " cannot store; transcode the track or split the output where it changes");
    }
}
