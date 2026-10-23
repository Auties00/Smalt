package com.github.auties00.smalt.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.testkit.fuzz.Fuzz;
import com.github.auties00.smalt.testkit.fuzz.FuzzMedia;

/**
 * Fuzz targets of the tag readers and writers of every container ({@code TagEditor}) and of the XMP parser.
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
}
