package com.github.auties00.smalt.internal.limits;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the failure translation of the decoder and encoder entry points and the unlimited probe
 * limits.
 */
class GuardsTest {
    @Nested
    @DisplayName("decoder policy")
    class Decoder {
        @Test
        @DisplayName("returns the body's result")
        void result() {
            assertEquals(42, Guards.malformed("JPEG stream", () -> 42));
        }

        @Test
        @DisplayName("passes a MediaException through unchanged")
        void passThrough() {
            var failure = new MediaException.Unsupported("jpeg", "arithmetic coding");
            assertSame(failure, assertThrows(MediaException.Unsupported.class,
                    () -> Guards.malformed("JPEG stream", () -> {
                        throw failure;
                    })));
        }

        @Test
        @DisplayName("turns another runtime exception into Malformed with the cause kept")
        void malformed() {
            var cause = new ArrayIndexOutOfBoundsException(3);
            var failure = assertThrows(MediaException.Malformed.class, () -> Guards.malformed("JPEG stream", () -> {
                throw cause;
            }));
            assertEquals("corrupt JPEG stream", failure.getMessage());
            assertSame(cause, failure.getCause());
        }

        @Test
        @DisplayName("turns running out of memory or stack into LimitExceeded")
        void limits() {
            var memory = assertThrows(MediaException.LimitExceeded.class, () -> Guards.malformed("GIF datastream", () -> {
                throw new OutOfMemoryError();
            }));
            assertEquals("GIF datastream needs more memory than is available", memory.getMessage());
            var stack = assertThrows(MediaException.LimitExceeded.class, () -> Guards.malformed("raw file", () -> {
                throw new StackOverflowError();
            }));
            assertEquals("raw file nests too deeply", stack.getMessage());
        }

        @Test
        @DisplayName("lets IllegalArgumentException through only in the argument-checking variant")
        void arguments() {
            assertThrows(MediaException.Malformed.class, () -> Guards.malformed("JPEG stream", () -> {
                throw new IllegalArgumentException("bad crop");
            }));
            assertThrows(IllegalArgumentException.class, () -> Guards.malformedExceptArguments("JPEG stream", () -> {
                throw new IllegalArgumentException("bad crop");
            }));
            assertThrows(MediaException.Malformed.class, () -> Guards.malformedExceptArguments("JPEG stream", () -> {
                throw new IllegalStateException();
            }));
        }

        @Test
        @DisplayName("translates a caught exception as the body form does")
        void translate() {
            var media = new MediaException.Malformed("bad");
            assertSame(media, Guards.translate("BMP file", media));
            var translated = Guards.translate("BMP file", new NegativeArraySizeException());
            assertEquals("corrupt BMP file", translated.getMessage());
        }
    }

    @Nested
    @DisplayName("encoder policy")
    class Encoder {
        @Test
        @DisplayName("lets programming errors propagate and turns only running out of memory into LimitExceeded")
        void policy() {
            assertThrows(IllegalStateException.class, () -> Guards.encoding("WebP image", () -> {
                throw new IllegalStateException();
            }));
            assertThrows(MediaException.LimitExceeded.class, () -> Guards.encoding("WebP image", () -> {
                throw new OutOfMemoryError();
            }));
            assertThrows(StackOverflowError.class, () -> Guards.encoding("WebP image", () -> {
                throw new StackOverflowError();
            }));
        }
    }

    @Test
    @DisplayName("the unlimited probe limits accept the largest sizes and equal the probes' former copies")
    void unlimited() {
        assertEquals(MediaLimits.builder().maxDimension(Integer.MAX_VALUE).maxPixels(Long.MAX_VALUE)
                .maxFrames(Integer.MAX_VALUE).maxChannels(Integer.MAX_VALUE).maxBytes(Long.MAX_VALUE).build(),
                LimitChecks.UNLIMITED);
        LimitChecks.checkPicture(LimitChecks.UNLIMITED, Integer.MAX_VALUE, 1);
        LimitChecks.checkChannels(LimitChecks.UNLIMITED, 255);
        LimitChecks.checkFrames(LimitChecks.UNLIMITED, Integer.MAX_VALUE);
    }
}
