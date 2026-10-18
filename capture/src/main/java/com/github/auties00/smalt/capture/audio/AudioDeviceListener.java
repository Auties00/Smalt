package com.github.auties00.smalt.capture.audio;

/**
 * Receives the changes of the audio devices: a new default device, a device plugged in or
 * removed.
 *
 * <p>Listeners are registered with {@link AudioDevices#addListener(AudioDeviceListener)}. Events
 * are delivered in the order the operating system reports them, one at a time, on a dedicated
 * thread of this module, never on a thread of the operating system's audio service; a listener
 * may therefore block, list devices and open or close sessions, at the cost of delaying the
 * events that follow.
 */
@FunctionalInterface
public interface AudioDeviceListener {
    /**
     * Handles one change.
     *
     * <p>An exception thrown by this method is ignored, and later events are still delivered.
     *
     * @param event the change
     */
    void onEvent(AudioDeviceEvent event);
}
