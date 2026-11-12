package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.MediaException;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Takes and gives back the exclusive ownership of CoreAudio devices.
 *
 * <p>{@code kAudioDevicePropertyHogMode} holds the process that owns a device, or {@code -1}. Writing
 * it toggles: the written value is ignored, a free device becomes this process's, a device this
 * process owns becomes free, and a device another process owns stays that process's. A blind write
 * could therefore give away a device the process already owns, so this class reads the owner first
 * and remembers what it holds. The operating system frees the device when the process exits.
 *
 * <p>The ownership belongs to the process and to the device, while a stream has a direction, so an
 * input stream and an output stream of one device may run exclusively side by side, at one rate; a
 * second stream of the same direction is refused. The last stream to leave gives the device back,
 * with the nominal rate it had before. This class is thread-safe.
 */
final class CoreAudioHog {
    /**
     * The owner value of a device nobody owns.
     */
    static final int NO_OWNER = -1;

    /**
     * The status of a toggle refused because another process owns the device.
     */
    private static final int PERMISSIONS_ERROR = FourCc.of("!hog");

    /**
     * The hog mode of this process's real devices.
     */
    private static final class ProcessHog {
        /**
         * The instance over the hardware abstraction layer.
         */
        static final CoreAudioHog INSTANCE = new CoreAudioHog(new HalHardware(), ProcessHandle.current().pid());
    }

    /**
     * The device properties this class depends on.
     */
    interface Hardware {
        /**
         * Reads a device's owner.
         *
         * @param object the device
         * @return the owning process, or {@link #NO_OWNER} when there is none or the device is gone
         */
        int owner(int object);

        /**
         * Writes a device's hog mode property.
         *
         * @param object the device
         * @return the status, {@code 0} on success
         */
        int toggle(int object);

        /**
         * Reads a device's nominal rate.
         *
         * @param object the device
         * @return the rate in hertz, or {@code 0} when it cannot be read
         */
        int nominalRate(int object);

        /**
         * Sets a device's nominal rate, waiting a bounded time for the device to apply it.
         *
         * @param object the device
         * @param rate the rate in hertz
         * @return whether the device runs at the rate
         */
        boolean setNominalRate(int object, int rate);
    }

    /**
     * One stream's share of a device's ownership, which {@link #release(Hold)} gives back.
     */
    static final class Hold {
        /**
         * The device.
         */
        private final int object;

        /**
         * Whether the stream plays rather than captures.
         */
        private final boolean playback;

        /**
         * Creates a share.
         *
         * @param object the device
         * @param playback whether the stream plays
         */
        private Hold(int object, boolean playback) {
            this.object = object;
            this.playback = playback;
        }
    }

    /**
     * What this process holds of one device.
     */
    private static final class Ownership {
        /**
         * The device's nominal rate before it was taken, in hertz.
         */
        private final int previousRate;

        /**
         * The rate the device runs at while it is taken, in hertz.
         */
        private final int rate;

        /**
         * Whether an input stream holds the device.
         */
        private boolean input;

        /**
         * Whether an output stream holds the device.
         */
        private boolean output;

        /**
         * Creates the record of a device just taken.
         *
         * @param previousRate the nominal rate before
         * @param rate the rate now
         */
        private Ownership(int previousRate, int rate) {
            this.previousRate = previousRate;
            this.rate = rate;
        }
    }

    /**
     * The device properties.
     */
    private final Hardware hardware;

    /**
     * This process's identifier.
     */
    private final long pid;

    /**
     * Guards {@link #owned} and the sequences of property reads and writes.
     */
    private final Object lock = new Object();

    /**
     * The devices this process holds, by audio object.
     */
    private final Map<Integer, Ownership> owned = new HashMap<>();

    /**
     * Creates an instance.
     *
     * @param hardware the device properties
     * @param pid this process's identifier
     */
    CoreAudioHog(Hardware hardware, long pid) {
        this.hardware = hardware;
        this.pid = pid;
    }

    /**
     * Returns the instance over this process's real devices.
     *
     * @return the instance
     */
    static CoreAudioHog system() {
        return ProcessHog.INSTANCE;
    }

    /**
     * Takes a device for one stream and runs it at a rate.
     *
     * @param object the device
     * @param name the device's name, for messages
     * @param playback whether the stream plays
     * @param rate the rate of the stream in hertz, one the device offers
     * @return the stream's share, which {@link #release(Hold)} gives back
     * @throws MediaException.Io if another process owns the device, or another stream of this
     * process owns it in the same direction
     * @throws MediaException.Unsupported if the device refuses the hog mode or the rate
     */
    Hold acquire(int object, String name, boolean playback, int rate) {
        synchronized (lock) {
            var ownership = owned.get(object);
            if (ownership == null) {
                ownership = take(object, name, rate);
                owned.put(object, ownership);
            } else if (playback ? ownership.output : ownership.input) {
                throw busy("the audio device " + name + " already has an exclusive "
                           + (playback ? "output" : "input") + " stream in this process");
            } else if (ownership.rate != rate) {
                throw new MediaException.Unsupported("exclusive mode", "the audio device " + name + " runs exclusively at "
                        + ownership.rate + " Hz and cannot also run at " + rate + " Hz");
            }
            if (playback) {
                ownership.output = true;
            } else {
                ownership.input = true;
            }
            return new Hold(object, playback);
        }
    }

    /**
     * Gives back one stream's share; the last one frees the device and restores its nominal rate. A
     * device no longer owned by this process, because it was removed, is left alone.
     *
     * @param hold the share
     */
    void release(Hold hold) {
        synchronized (lock) {
            var ownership = owned.get(hold.object);
            if (ownership == null) {
                return;
            }
            if (hold.playback) {
                ownership.output = false;
            } else {
                ownership.input = false;
            }
            if (ownership.input || ownership.output) {
                return;
            }
            owned.remove(hold.object);
            if (ownership.previousRate > 0 && ownership.rate != ownership.previousRate) {
                hardware.setNominalRate(hold.object, ownership.previousRate);
            }
            if (hardware.owner(hold.object) == pid) {
                hardware.toggle(hold.object);
            }
        }
    }

    /**
     * Takes a device this class does not hold yet.
     *
     * @param object the device
     * @param name the device's name, for messages
     * @param rate the rate to run it at, in hertz
     * @return the record of the device
     */
    private Ownership take(int object, String name, int rate) {
        var owner = hardware.owner(object);
        if (owner == pid) {
            throw busy("the audio device " + name + " is already owned exclusively by code of this process outside this session");
        }
        if (owner != NO_OWNER) {
            throw inUse(name, owner);
        }
        var status = hardware.toggle(object);
        if (status == PERMISSIONS_ERROR) {
            throw inUse(name, hardware.owner(object));
        }
        if (status != 0) {
            throw new MediaException.Unsupported("exclusive mode", "the audio device " + name
                    + " does not allow exclusive mode (OSStatus " + status + ")");
        }
        owner = hardware.owner(object);
        if (owner != pid) {
            if (owner != NO_OWNER) {
                throw inUse(name, owner);
            }
            throw new MediaException.Unsupported("exclusive mode", "the audio device " + name + " does not allow exclusive mode");
        }
        var previous = hardware.nominalRate(object);
        if (rate != previous && !hardware.setNominalRate(object, rate)) {
            // the device may still apply a write it did not confirm in time
            if (previous > 0) {
                hardware.setNominalRate(object, previous);
            }
            hardware.toggle(object);
            throw new MediaException.Unsupported("exclusive mode", "the audio device " + name + " cannot run exclusively at " + rate + " Hz");
        }
        return new Ownership(previous, rate);
    }

    /**
     * Builds the failure of a device another process owns.
     *
     * @param name the device's name
     * @param owner the owning process, or {@link #NO_OWNER} when unknown
     * @return the exception
     */
    private static MediaException.Io inUse(String name, int owner) {
        return busy("the audio device " + name + ": another application"
                    + (owner == NO_OWNER ? "" : " (process " + owner + ")") + " uses the device exclusively");
    }

    /**
     * Builds the failure of a device that is taken.
     *
     * @param message the description
     * @return the exception
     */
    private static MediaException.Io busy(String message) {
        return new MediaException.Io(message, new IOException(message));
    }

    /**
     * Reads and writes the properties through the hardware abstraction layer.
     */
    private static final class HalHardware implements Hardware {
        /**
         * The longest wait for a device to apply a nominal rate, in milliseconds; the property is
         * applied asynchronously.
         */
        private static final int RATE_TIMEOUT_MILLIS = 2_000;

        /**
         * The interval between two reads of the nominal rate, in milliseconds.
         */
        private static final int RATE_POLL_MILLIS = 10;

        @Override
        public int owner(int object) {
            return CoreAudio.intProperty(object, CoreAudio.HOG_MODE, CoreAudio.SCOPE_GLOBAL, NO_OWNER);
        }

        @Override
        public int toggle(int object) {
            return CoreAudio.setIntProperty(object, CoreAudio.HOG_MODE, CoreAudio.SCOPE_GLOBAL, (int) ProcessHandle.current().pid());
        }

        @Override
        public int nominalRate(int object) {
            return (int) Math.round(CoreAudio.doubleProperty(object, CoreAudio.NOMINAL_SAMPLE_RATE, CoreAudio.SCOPE_GLOBAL, 0));
        }

        @Override
        public boolean setNominalRate(int object, int rate) {
            if (CoreAudio.setDoubleProperty(object, CoreAudio.NOMINAL_SAMPLE_RATE, CoreAudio.SCOPE_GLOBAL, rate) != 0) {
                return false;
            }
            for (var waited = 0; nominalRate(object) != rate; waited += RATE_POLL_MILLIS) {
                if (waited >= RATE_TIMEOUT_MILLIS) {
                    return false;
                }
                try {
                    Thread.sleep(RATE_POLL_MILLIS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }
}
