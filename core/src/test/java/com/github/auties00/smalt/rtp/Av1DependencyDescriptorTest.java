package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AV1 dependency descriptor (AV1 RTP payload specification appendix A)")
class Av1DependencyDescriptorTest {
    /** The L3T3 full SVC structure of appendix A.10.2.2: three spatial and three temporal layers, nine targets. */
    static Av1TemplateStructure l3t3() {
        var templates = new java.util.ArrayList<Av1TemplateStructure.Template>();
        var random = new Random(3);
        for (var spatial = 0; spatial < 3; spatial++) {
            for (var temporal = 0; temporal < 3; temporal++) {
                var dtis = new java.util.ArrayList<Integer>();
                for (var target = 0; target < 9; target++) {
                    dtis.add(random.nextInt(4));
                }
                var diffs = temporal == 0 ? List.<Integer>of() : List.of(1 + random.nextInt(16), 1 + spatial);
                templates.add(new Av1TemplateStructure.Template(spatial, temporal, dtis, diffs,
                        List.of(random.nextInt(16), random.nextInt(16), random.nextInt(16))));
            }
        }
        return new Av1TemplateStructure(17, 9, templates, 3, List.of(0, 0, 0, 1, 1, 1, 2, 2, 2),
                List.of(new Av1TemplateStructure.Resolution(320, 180), new Av1TemplateStructure.Resolution(640, 360),
                        new Av1TemplateStructure.Resolution(1280, 720)));
    }

    @Test
    @DisplayName("writes the mandatory fields alone as 3 bytes, bit for bit")
    void mandatory() {
        var descriptor = new Av1DependencyDescriptor(true, false, 5, 0x1234);
        var structure = Av1TemplateStructure.singleLayer(4);
        var bytes = descriptor.toByteArray(structure);
        assertArrayEquals(new byte[]{(byte) 0x85, 0x12, 0x34}, bytes);
        var back = Av1DependencyDescriptor.parse(bytes, structure);
        assertEquals(descriptor, back);
        assertEquals(structure.templates().get(1), back.template(structure));
    }

    @Test
    @DisplayName("round trips an attached structure with chains, resolutions and every custom field")
    void structure() {
        var structure = l3t3();
        var descriptor = new Av1DependencyDescriptor(true, true, 20, 65_535, structure, 0x1F5, List.of(3, 2, 1, 0,
                3, 2, 1, 0, 3), List.of(1, 16, 17, 256, 257, 4096), List.of(0, 7, 255));
        var bytes = descriptor.toByteArray(null);
        var back = Av1DependencyDescriptor.parse(bytes, null);
        assertEquals(descriptor, back);
        assertEquals(structure, back.attachedStructure());
        assertEquals(1, back.template(structure).spatialId());
        assertEquals(0, back.template(structure).temporalId());
        var plain = new Av1DependencyDescriptor(false, true, 18, 9, structure, -1, null, null, null);
        var parsed = Av1DependencyDescriptor.parse(plain.toByteArray(null), null);
        assertEquals((1L << 9) - 1, parsed.activeDecodeTargets(), "an attached structure activates every target");
        assertArrayEquals(plain.toByteArray(null), parsed.toByteArray(null));
    }

    @Test
    @DisplayName("codes chain counts with the non-symmetric code of A.8.2")
    void nonSymmetric() {
        // Five decode targets give six chain-count values: 0 and 1 in two bits, then 3-bit codes.
        for (var chains = 0; chains <= 4; chains++) {
            var protectedBy = chains == 0 ? List.<Integer>of() : List.of(0, chains - 1, 0, chains - 1, 0);
            var chainDiffs = java.util.Collections.nCopies(chains, 3);
            var structure = new Av1TemplateStructure(0, 5, List.of(new Av1TemplateStructure.Template(0, 0,
                    List.of(3, 3, 3, 3, 3), List.of(), chainDiffs)), chains, protectedBy, List.of());
            var descriptor = new Av1DependencyDescriptor(true, true, 0, 1, structure, -1, null, null, null);
            assertEquals(structure, Av1DependencyDescriptor.parse(descriptor.toByteArray(null), null)
                    .attachedStructure(), chains + " chains");
        }
    }

    @Test
    @DisplayName("rejects descriptors that are short, truncated, or refer to an unknown structure or template")
    void rejects() {
        var structure = Av1TemplateStructure.singleLayer(0);
        assertThrows(MediaException.Malformed.class, () -> Av1DependencyDescriptor.parse(new byte[2], structure));
        assertThrows(MediaException.Malformed.class, () -> Av1DependencyDescriptor.parse(new byte[3], null));
        assertThrows(MediaException.Malformed.class,
                () -> Av1DependencyDescriptor.parse(new byte[]{0x05, 0, 0}, structure));
        var full = new Av1DependencyDescriptor(true, true, 17, 1, l3t3(), -1, null, null, null).toByteArray(null);
        assertThrows(MediaException.Malformed.class,
                () -> Av1DependencyDescriptor.parse(java.util.Arrays.copyOf(full, full.length / 2), null));
        assertThrows(IllegalArgumentException.class,
                () -> new Av1TemplateStructure(0, 1, List.of(new Av1TemplateStructure.Template(0, 2, List.of(1),
                        List.of(), List.of())), 0, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Av1DependencyDescriptor(true, true, 64, 0));
        assertThrows(IllegalArgumentException.class, () -> new Av1DependencyDescriptor(true, true, 0, 0, null, -1,
                null, List.of(4097), null));
    }

    @Test
    @DisplayName("the AV1 packetizer marks frames and attaches the structure on key temporal units")
    void packetizer() {
        var stream = Av1RtpTest.stream(9);
        var packetizer = RtpPacketizer.create(RtpPayloadFormat.av1(), RtpChannel.fixed().dependencyDescriptorId(3)
                .maxPacketSize(300).initialSequenceNumber(65_535).build());
        var sent = RtpChannel.packetize(packetizer, stream.units());
        Av1TemplateStructure structure = null;
        for (var index = 0; index < sent.packets().size(); index++) {
            var packet = sent.packets().get(index);
            assertTrue(packet.size() <= 300, "packet of " + packet.size() + " bytes");
            var owner = sent.owners().get(index);
            var first = index == 0 || !sent.owners().get(index - 1).equals(owner);
            var last = index == sent.packets().size() - 1 || !sent.owners().get(index + 1).equals(owner);
            var descriptor = Av1DependencyDescriptor.parse(packet.extension().element(3).orElseThrow(), structure);
            if (descriptor.attachedStructure() != null) {
                structure = descriptor.attachedStructure();
                assertTrue(first);
            }
            assertEquals(first, descriptor.startOfFrame());
            assertEquals(last, descriptor.endOfFrame());
            assertEquals((65_535 + owner) & 0xFFFF, descriptor.frameNumber());
            var key = stream.units().get(owner).key();
            assertEquals(key ? 0 : 1, descriptor.templateId());
            if (first && key) {
                assertEquals(Av1TemplateStructure.singleLayer(0), descriptor.attachedStructure());
            } else {
                assertNull(descriptor.attachedStructure());
            }
        }
        var frames = RtpChannel.depacketize(RtpDepacketizer.create(RtpPayloadFormat.av1(),
                RtpDepacketizerOptions.defaults()), RtpChannel.overTheWire(sent.packets()));
        for (var index = 0; index < frames.size(); index++) {
            assertArrayEquals(stream.expected().get(index), frames.get(index).packet().data());
        }
    }
}
