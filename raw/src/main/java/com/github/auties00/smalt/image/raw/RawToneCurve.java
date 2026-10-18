package com.github.auties00.smalt.image.raw;

/**
 * A tone curve applied to the scene-referred colors of a raw image before they are encoded for
 * display.
 */
public enum RawToneCurve {
    /**
     * No curve: the output is colorimetric, linear scene light scaled so that the sensor's
     * saturation of a neutral maps to white, then encoded with the output color space's transfer
     * function. Suited to further processing; looks darker and flatter than a camera's own JPEG.
     */
    LINEAR,

    /**
     * The file's baseline exposure, then the camera profile's tone curve when the file holds one
     * (a DNG {@code ProfileToneCurve}) or otherwise a filmic default curve with a gentle toe and a
     * highlight shoulder: a rendering comparable to a camera's own JPEG.
     */
    STANDARD
}
