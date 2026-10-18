package com.github.auties00.smalt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the typed {@link MediaException.Unsupported.Kind}: the kind constructors, the kind the
 * older constructors report, and serialization of the kind.
 */
@DisplayName("MediaException.Unsupported")
class MediaExceptionTest {
    @ParameterizedTest(name = "{0}")
    @EnumSource(MediaException.Unsupported.Kind.class)
    @DisplayName("reports the kind it was constructed with")
    void kind(MediaException.Unsupported.Kind kind) {
        var exception = new MediaException.Unsupported(kind, "thing", "the thing is not supported");
        assertEquals(kind, exception.kind());
        assertEquals("thing", exception.format());
        assertEquals("the thing is not supported", exception.getMessage());
        assertEquals(Optional.empty(), exception.artifact());
        var withArtifact = new MediaException.Unsupported(kind, "h264", "missing", "smalt-mpeg");
        assertEquals(kind, withArtifact.kind());
        assertEquals(Optional.of("smalt-mpeg"), withArtifact.artifact());
    }

    @Test
    @DisplayName("constructors without a kind report FEATURE")
    void defaultKind() {
        assertEquals(MediaException.Unsupported.Kind.FEATURE, new MediaException.Unsupported("x", "y").kind());
        assertEquals(MediaException.Unsupported.Kind.FEATURE, new MediaException.Unsupported("x", "y", "smalt-image").kind());
    }

    @Test
    @DisplayName("rejects a null kind")
    void nullKind() {
        assertThrows(NullPointerException.class,
                () -> new MediaException.Unsupported((MediaException.Unsupported.Kind) null, "x", "y"));
    }

    @Test
    @DisplayName("keeps the kind across serialization")
    void serialization() throws IOException, ClassNotFoundException {
        var exception = new MediaException.Unsupported(MediaException.Unsupported.Kind.OPERATION, "seek", "not seekable");
        var bytes = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(bytes)) {
            output.writeObject(exception);
        }
        try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            var copy = (MediaException.Unsupported) input.readObject();
            assertEquals(MediaException.Unsupported.Kind.OPERATION, copy.kind());
            assertEquals("seek", copy.format());
        }
    }
}
