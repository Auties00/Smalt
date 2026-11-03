package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.testkit.validate.Findings;
import com.github.auties00.smalt.testkit.validate.PsValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the test kit's program stream validator on hand-written streams: a valid one passes and each defect it
 * looks for is reported.
 */
@DisplayName("program stream validator")
class PsValidatorTest {
    static List<String> problems(byte[] data, boolean strict) {
        var findings = new Findings("test");
        PsValidator.validate(data, findings, strict);
        return findings.problems();
    }

    static PsWriter stream() {
        return new PsWriter().pack(0).systemHeader(0xE0).map(List.of(new PsWriter.MapEntry(0x02, 0xE0, "eng")), false,
                false).pes(0xE0, 90_000, 86_400, new byte[100]).pack(100).pes(0xE0, 93_600, 90_000, new byte[100]);
    }

    @Test
    @DisplayName("accepts a valid stream and summarizes it")
    void valid() {
        var data = stream().end().toByteArray();
        assertEquals(List.of(), problems(data, true));
        var summary = PsValidator.validate(data, new Findings("test"), true);
        assertEquals(2, summary.packs());
        assertTrue(summary.endCode());
        assertEquals(java.util.Map.of(0xE0, 0x02), summary.mapTypes());
        assertFalse(summary.mpeg1());
    }

    @Test
    @DisplayName("reports a corrupt pack header, map CRC, timestamps, clock and trailing bytes")
    void defects() {
        var data = stream().end().toByteArray();
        var marker = data.clone();
        marker[4] &= (byte) ~0x04;
        assertFalse(problems(marker, false).isEmpty(), "pack marker bits");
        var crc = new PsWriter().pack(0).map(List.of(new PsWriter.MapEntry(0x02, 0xE0, null)), false, true).end()
                .toByteArray();
        assertTrue(problems(crc, false).stream().anyMatch(problem -> problem.contains("CRC_32")), "map CRC");
        var order = new PsWriter().pack(0).pes(0xE0, 90_000, 93_600, new byte[10]).end().toByteArray();
        assertTrue(problems(order, false).stream().anyMatch(problem -> problem.contains("DTS after PTS")));
        var back = new PsWriter().pack(500).pes(0xE0, 90_000, -1, new byte[10]).pack(100).end().toByteArray();
        assertTrue(problems(back, false).stream().anyMatch(problem -> problem.contains("goes back")));
        var late = new PsWriter().pack(95_000).pes(0xE0, 90_000, -1, new byte[10]).end().toByteArray();
        assertTrue(problems(late, false).isEmpty(), "pacing is checked only when strict");
        assertTrue(problems(late, true).stream().anyMatch(problem -> problem.contains("before the system clock")));
        var trailing = stream().end().bytes(new byte[]{1, 2, 3}).toByteArray();
        assertTrue(problems(trailing, false).stream().anyMatch(problem -> problem.contains("after the program end")));
        var truncated = java.util.Arrays.copyOf(data, data.length - 60);
        assertFalse(problems(truncated, false).isEmpty(), "a packet running past the end");
    }
}
