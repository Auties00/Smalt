package com.github.auties00.smalt.capture;

/**
 * Delivers the frames of an open camera and adjusts its settings.
 *
 * <p>The frames follow the contract of {@link VideoCaptureSession}; {@link #controls()} reads and adjusts
 * the camera's exposure, focus, zoom, white balance and the other {@link CameraControl}s while the session
 * is open.
 */
public interface CameraCaptureSession extends VideoCaptureSession {
    /**
     * {@inheritDoc}
     *
     * @return the camera
     */
    @Override
    CaptureDevice.Camera device();

    /**
     * Returns the settings of the camera.
     *
     * @return the controls, the same instance on every call; their capabilities are empty when the camera
     * or the platform offers no control
     */
    CameraControls controls();
}
