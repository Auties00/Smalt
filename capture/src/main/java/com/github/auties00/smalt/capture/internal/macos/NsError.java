package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.MediaException;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.Objects;

/**
 * Captures the parts of an {@code NSError} a failure report needs.
 *
 * @param domain      the error domain, for example {@code "AVFoundationErrorDomain"}
 * @param code        the error code within the domain
 * @param description the localized description
 */
record NsError(String domain, long code, String description) {
    /**
     * Validates the error.
     *
     * @throws NullPointerException if {@code domain} or {@code description} is {@code null}
     */
    NsError {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(description, "description");
    }

    /**
     * Reads an {@code NSError}.
     *
     * <p>Must run inside an autorelease pool: the domain and description strings are autoreleased.
     *
     * @param error the error, or {@code NULL}
     * @return the error, or {@code null} for {@code NULL}
     */
    static NsError read(MemorySegment error) {
        if (ObjC.isNull(error)) {
            return null;
        }
        var domain = CoreFoundation.string(ObjC.id(error, ObjC.selector("domain")));
        var code = ObjC.integer(error, ObjC.selector("code"));
        var description = CoreFoundation.string(ObjC.id(error, ObjC.selector("localizedDescription")));
        return new NsError(domain == null ? "" : domain, code, description == null ? "" : description);
    }

    /**
     * Returns whether this error is in a domain and has a code.
     *
     * @param expectedDomain the domain
     * @param expectedCode   the code
     * @return whether both match
     */
    boolean is(String expectedDomain, long expectedCode) {
        return domain.equals(expectedDomain) && code == expectedCode;
    }

    /**
     * Wraps this error into a capture failure.
     *
     * @param message what failed, in words
     * @return the failure, whose message ends with the description and whose cause names the
     *         domain and code
     */
    MediaException.Io toException(String message) {
        var text = description.isBlank() ? message : message + ": " + description;
        return new MediaException.Io(text, new IOException(domain + " error " + code + (description.isBlank() ? "" : " (" + description + ")")));
    }
}
