package com.github.auties00.smalt.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.codec.DolbyVisionConfigs;
import com.github.auties00.smalt.internal.nal.DolbyVisionRpu;
import com.github.auties00.smalt.testkit.DolbyVisionRpus;
import com.github.auties00.smalt.testkit.fuzz.Fuzz;

/**
 * Fuzz targets of the Dolby Vision parsers: an unescaped reference processing unit, parsed as it is and as the
 * HEVC NAL unit of type 62 that carries it, and its first bytes read as a configuration record and as a transport
 * stream descriptor.
 */
class DolbyVisionFuzzTest {
    @FuzzTest
    void rpu(byte[] data) {
        var run = Fuzz.start("Dolby Vision RPU");
        run.step("parse", () -> {
            var rpu = DolbyVisionRpu.parse(data, 0, data.length);
            if (rpu.profile() < 0 || rpu.baseLayerBitDepth() != 10) {
                throw new AssertionError("profile " + rpu.profile() + ", base layer depth " + rpu.baseLayerBitDepth());
            }
            run.output();
        });
        run.step("hevc nal", () -> {
            var escaped = DolbyVisionRpus.escape(data);
            var nal = new byte[escaped.length + 2];
            nal[0] = 0x7C;
            nal[1] = 0x01;
            System.arraycopy(escaped, 0, nal, 2, escaped.length);
            var rpu = DolbyVisionRpu.tryParseHevcNal(nal, 0, nal.length);
            if (rpu == null) {
                throw new MediaException.Malformed("not an RPU NAL unit");
            }
            run.output();
        });
        run.step("configurations", () -> {
            var record = DolbyVisionConfigs.parseRecord(data, 0, Math.min(data.length, DolbyVisionConfigs.RECORD_LENGTH));
            var descriptor = DolbyVisionConfigs.parseDescriptor(data, 0, Math.min(data.length, 7));
            if (record != null && !record.equals(DolbyVisionConfigs.parseRecord(DolbyVisionConfigs.record(record)))) {
                throw new AssertionError("record round trip of " + record);
            }
            if (descriptor != null) {
                var written = DolbyVisionConfigs.descriptor(descriptor, 0x100);
                if (!descriptor.equals(DolbyVisionConfigs.parseDescriptor(written, 0, written.length))) {
                    throw new AssertionError("descriptor round trip of " + descriptor);
                }
            }
            run.output();
        });
    }
}
