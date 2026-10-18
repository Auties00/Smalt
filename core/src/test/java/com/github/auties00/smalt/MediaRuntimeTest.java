package com.github.auties00.smalt;

import com.github.auties00.smalt.internal.simd.Simd;
import com.github.auties00.smalt.internal.task.ParallelBands;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MediaRuntime")
class MediaRuntimeTest {
    @Test
    @DisplayName("reports the kernel selection the module made")
    void vectorized() {
        assertEquals(Simd.ENABLED, MediaRuntime.isVectorized());
    }

    @Test
    @DisplayName("reports a vector width of at least 128 bits when vectorized and 0 otherwise")
    void vectorBits() {
        if (MediaRuntime.isVectorized()) {
            assertTrue(MediaRuntime.vectorBits() >= 128, "vector bits " + MediaRuntime.vectorBits());
        } else {
            assertEquals(0, MediaRuntime.vectorBits());
        }
    }

    @Test
    @DisplayName("reports the parallelism of the shared pool, at least the calling thread")
    void parallelism() {
        assertEquals(ParallelBands.THREADS, MediaRuntime.parallelism());
        assertTrue(MediaRuntime.parallelism() >= 1);
    }
}
