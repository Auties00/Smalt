package com.github.auties00.smalt.capture.audio;

import java.util.List;
import java.util.Objects;

/**
 * An audio endpoint: a microphone or other {@link Input}, or a speaker, headset or other
 * {@link Output}.
 *
 * <p>Devices are listed by {@link AudioDevices#inputs()} and {@link AudioDevices#outputs()} and
 * opened by {@link AudioDevices#openCapture(Input, AudioStreamOptions)},
 * {@link AudioDevices#openPlayback(Output, AudioStreamOptions)} and
 * {@link AudioDevices#openLoopback(Output, AudioStreamOptions)}. The {@link #id()} is the
 * operating system's stable identifier of the endpoint, suitable for remembering a user's choice
 * and for {@link AudioDevices#input(String)} and {@link AudioDevices#output(String)}; the
 * {@link #name()} is its human-readable label, which several devices may share. A device is a
 * snapshot taken when it was listed: {@link #defaultDevice()} does not follow later changes,
 * which {@link AudioDeviceListener}s receive. Devices are immutable and thread-safe.
 */
public sealed interface AudioDevice permits AudioDevice.Input, AudioDevice.Output {
    /**
     * Returns the operating system's identifier of the device.
     *
     * @return the identifier, unique among the devices of its direction
     */
    String id();

    /**
     * Returns the human-readable label of the device.
     *
     * @return the label
     */
    String name();

    /**
     * Returns whether the device was the system's default of its direction when it was listed.
     *
     * @return whether it is the default device
     */
    boolean defaultDevice();

    /**
     * Returns the formats the device runs at without conversion.
     *
     * @return the formats, the one the operating system currently runs the device at first; empty
     * when the platform does not report them
     */
    List<AudioDeviceFormat> formats();

    /**
     * Returns the direction of the device.
     *
     * @return {@link Direction#INPUT} for an {@link Input}, {@link Direction#OUTPUT} for an
     * {@link Output}
     */
    Direction direction();

    /**
     * The direction of an audio endpoint.
     */
    enum Direction {
        /**
         * An endpoint that records: a microphone, a line input, a virtual cable's output.
         */
        INPUT,

        /**
         * An endpoint that plays: speakers, headphones, a virtual cable's input.
         */
        OUTPUT
    }

    /**
     * An input device.
     *
     * @param id the operating system's identifier (an endpoint id on Windows, a device
     * UID on macOS, an ALSA device name on Linux)
     * @param name the device's label
     * @param defaultDevice whether it was the default input when listed
     * @param formats the formats the device runs at without conversion, the current one
     * first; may be empty
     */
    record Input(String id, String name, boolean defaultDevice, List<AudioDeviceFormat> formats) implements AudioDevice {
        /**
         * Validates the description and copies the formats.
         *
         * @throws NullPointerException if a format is {@code null}
         */
        public Input {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            formats = List.copyOf(formats);
        }

        @Override
        public Direction direction() {
            return Direction.INPUT;
        }
    }

    /**
     * An output device.
     *
     * @param id the operating system's identifier (an endpoint id on Windows, a device
     * UID on macOS, an ALSA device name on Linux)
     * @param name the device's label
     * @param defaultDevice whether it was the default output when listed
     * @param formats the formats the device runs at without conversion, the current one
     * first; may be empty
     */
    record Output(String id, String name, boolean defaultDevice, List<AudioDeviceFormat> formats) implements AudioDevice {
        /**
         * Validates the description and copies the formats.
         *
         * @throws NullPointerException if a format is {@code null}
         */
        public Output {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            formats = List.copyOf(formats);
        }

        @Override
        public Direction direction() {
            return Direction.OUTPUT;
        }
    }
}
