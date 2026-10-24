package com.github.auties00.smalt.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.audio.loudness.ReplayGain;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.testkit.fuzz.Fuzz;
import com.github.auties00.smalt.testkit.fuzz.FuzzMedia;

import java.nio.charset.StandardCharsets;

/**
 * Fuzz targets of the tag readers and writers of every container ({@code TagEditor}), of the XMP parser and of
 * the ReplayGain tag fields.
 */
class TagFuzzTest {
    @FuzzTest
    void tags(byte[] data) {
        FuzzMedia.tags(Fuzz.start("tags"), data);
    }

    @FuzzTest
    void xmp(byte[] data) {
        var run = Fuzz.start("xmp");
        var packet = run.value("parse", () -> XmpPacket.parse(data));
        if (packet.isEmpty()) {
            return;
        }
        var parsed = packet.get();
        parsed.properties();
        parsed.prefixes();
        var edited = parsed.with(XmpPacket.DC, "title", "fuzz").without(XmpPacket.XMP, "CreatorTool");
        var reparsed = XmpPacket.parse(edited.toBytes());
        if (reparsed.text(XmpPacket.DC, "title").isEmpty()) {
            throw new AssertionError("the written dc:title is not read back from " + edited.toXml());
        }
        XmpPacket.parse(parsed.toXml(64));
        run.output();
    }

    /**
     * Reads ReplayGain fields from {@code key=value} lines, then checks that the gains are in range and survive a
     * write and a read in both tag forms.
     */
    @FuzzTest
    void replayGain(byte[] data) {
        var run = Fuzz.start("replaygain");
        var builder = MediaTags.builder();
        for (var line : new String(data, StandardCharsets.UTF_8).split("\n", 64)) {
            var separator = line.indexOf('=');
            if (separator <= 0 || !validKey(line.substring(0, separator))) {
                continue;
            }
            builder.add(line.substring(0, separator), line.substring(separator + 1).replace("\0", ""));
        }
        var gains = run.value("parse", () -> ReplayGain.fromTags(builder.build())).orElseThrow();
        for (var gain : new double[]{gains.trackGainDb(), gains.albumGainDb()}) {
            if (!Double.isNaN(gain) && !(Math.abs(gain) <= 200)) {
                throw new AssertionError("gain out of range: " + gains);
            }
        }
        for (var peak : new double[]{gains.trackPeak(), gains.albumPeak()}) {
            if (!Double.isNaN(peak) && !(peak >= 0 && Double.isFinite(peak))) {
                throw new AssertionError("peak out of range: " + gains);
            }
        }
        var written = ReplayGain.fromTags(gains.toTags(MediaTags.empty(), ReplayGain.TagFormat.REPLAYGAIN));
        if (!close(gains.trackGainDb(), written.trackGainDb(), 0.005)
                || !close(gains.albumGainDb(), written.albumGainDb(), 0.005)
                || !close(gains.trackPeak(), written.trackPeak(), 5e-7 + Math.abs(gains.trackPeak()) * 1e-12)) {
            throw new AssertionError(gains + " read back as " + written);
        }
        var r128 = ReplayGain.fromTags(gains.toTags(MediaTags.empty(), ReplayGain.TagFormat.R128));
        if (Math.abs(gains.trackGainDb() - 5) < 127 && !close(gains.trackGainDb(), r128.trackGainDb(), 1 / 512.0 + 1e-9)) {
            throw new AssertionError(gains + " read back from R128 as " + r128);
        }
        for (var mode : ReplayGain.Mode.values()) {
            gains.playbackGainDb(mode, -3, true).ifPresent(gain -> {
                if (!Double.isFinite(gain)) {
                    throw new AssertionError("playback gain " + gain);
                }
            });
        }
        if (!Double.isNaN(gains.trackGainDb())) {
            run.output();
        }
    }

    private static boolean validKey(String key) {
        return !key.isBlank() && key.chars().noneMatch(c -> c == '=' || c < 0x20 || c == 0x7F);
    }

    private static boolean close(double expected, double actual, double tolerance) {
        return Double.isNaN(expected) ? Double.isNaN(actual) : Math.abs(expected - actual) <= tolerance;
    }
}
