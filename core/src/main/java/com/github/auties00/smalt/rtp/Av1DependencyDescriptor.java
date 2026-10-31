package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Holds the AV1 dependency descriptor of one RTP packet, the header extension of appendix A.8 of the AV1 RTP payload
 * specification that lets a selective forwarding middlebox route the layers of a scalable stream without reading
 * the payload.
 *
 * <p>The descriptor names a frame dependency template of an {@link Av1TemplateStructure}, which the first packet of
 * a coded video sequence attaches and later packets refer to, and may override the template's decode target
 * indications, frame diffs and chain diffs for its frame. {@link #parse(byte[], Av1TemplateStructure)} reads the
 * extension element and {@link #toByteArray(Av1TemplateStructure)} writes it; the element is carried in an RFC 8285
 * header extension under the identifier the session maps to the URI {@value #URI}.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param startOfFrame whether the packet's payload starts a frame
 * @param endOfFrame whether the packet's payload ends a frame
 * @param templateId the frame dependency template identifier, from 0 to 63
 * @param frameNumber the frame number, from 0 to 65535, increasing in decoding order and wrapping
 * @param attachedStructure the template dependency structure the packet carries, or {@code null} for none
 * @param activeDecodeTargets the bit mask of the decode targets available for decoding, bit 0 for decode target
 * 0, or {@code -1} when the packet does not signal it
 * @param frameDecodeTargetIndications the frame's own decode target indications, or {@code null} to use the
 * template's
 * @param frameDiffs the frame's own distances back to the referenced frames, each from 1 to 4096, or {@code null} to
 * use the template's
 * @param frameChainDiffs the frame's own distances back to the previous frame of each chain, each from 0 to 255, or
 * {@code null} to use the template's
 */
public record Av1DependencyDescriptor(boolean startOfFrame, boolean endOfFrame, int templateId, int frameNumber,
                                      Av1TemplateStructure attachedStructure, long activeDecodeTargets,
                                      List<Integer> frameDecodeTargetIndications, List<Integer> frameDiffs,
                                      List<Integer> frameChainDiffs) {
    /**
     * The URI of the header extension in an SDP {@code a=extmap} attribute.
     */
    public static final String URI =
            "https://aomediacodec.github.io/av1-rtp-spec/#dependency-descriptor-rtp-header-extension";

    /**
     * Validates the fields and copies the lists.
     *
     * @throws NullPointerException if an element of a list is {@code null}
     * @throws IllegalArgumentException if a value is outside its range
     */
    public Av1DependencyDescriptor {
        if (templateId < 0 || templateId > 63) {
            throw new IllegalArgumentException("template id " + templateId + " is not from 0 to 63");
        }
        if (frameNumber < 0 || frameNumber > 0xFFFF) {
            throw new IllegalArgumentException("frame number " + frameNumber + " is not from 0 to 65535");
        }
        if (activeDecodeTargets < -1 || activeDecodeTargets > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("active decode target mask " + activeDecodeTargets);
        }
        frameDecodeTargetIndications = copy(frameDecodeTargetIndications, 0, 3);
        frameDiffs = copy(frameDiffs, 1, 4096);
        frameChainDiffs = copy(frameChainDiffs, 0, 255);
    }

    private static List<Integer> copy(List<Integer> values, int min, int max) {
        if (values == null) {
            return null;
        }
        var copy = List.copyOf(values);
        for (var value : copy) {
            if (value < min || value > max) {
                throw new IllegalArgumentException("value " + value + " is not from " + min + " to " + max);
            }
        }
        return copy;
    }

    /**
     * Constructs a descriptor that only names a template.
     *
     * @param startOfFrame whether the packet's payload starts a frame
     * @param endOfFrame whether the packet's payload ends a frame
     * @param templateId the frame dependency template identifier, from 0 to 63
     * @param frameNumber the frame number, from 0 to 65535
     * @throws IllegalArgumentException if a value is outside its range
     */
    public Av1DependencyDescriptor(boolean startOfFrame, boolean endOfFrame, int templateId, int frameNumber) {
        this(startOfFrame, endOfFrame, templateId, frameNumber, null, -1, null, null, null);
    }

    /**
     * Parses a dependency descriptor.
     *
     * @param data the header extension element
     * @param structure the template dependency structure in effect, or {@code null} when none has been received;
     * a structure the element attaches takes its place
     * @return the descriptor
     * @throws MediaException.Malformed if the element is shorter than 3 bytes or truncated, it needs a structure and
     * none is known, or its template identifier is outside the structure's range
     */
    public static Av1DependencyDescriptor parse(byte[] data, Av1TemplateStructure structure) {
        if (data.length < 3) {
            throw new MediaException.Malformed("dependency descriptor of " + data.length + " bytes");
        }
        var reader = new MsbBitReader(data);
        var start = reader.readFlag();
        var end = reader.readFlag();
        var templateId = reader.read(6);
        var frameNumber = reader.read(16);
        Av1TemplateStructure attached = null;
        long active = -1;
        var customDtis = false;
        var customDiffs = false;
        var customChains = false;
        if (data.length > 3) {
            var structurePresent = reader.readFlag();
            var activePresent = reader.readFlag();
            customDtis = reader.readFlag();
            customDiffs = reader.readFlag();
            customChains = reader.readFlag();
            if (structurePresent) {
                attached = readStructure(reader);
                structure = attached;
                active = activePresent ? -1 : (1L << structure.decodeTargets()) - 1;
            }
            if (activePresent) {
                if (structure == null) {
                    throw new MediaException.Malformed("dependency descriptor needs an unknown structure");
                }
                active = reader.readLong(structure.decodeTargets());
            }
        }
        if (structure == null) {
            throw new MediaException.Malformed("dependency descriptor refers to a template structure not received");
        }
        var index = (templateId + 64 - structure.templateIdOffset()) % 64;
        if (index >= structure.templates().size()) {
            throw new MediaException.Malformed("template id " + templateId + " is outside the structure");
        }
        List<Integer> dtis = null;
        if (customDtis) {
            dtis = new ArrayList<>();
            for (var target = 0; target < structure.decodeTargets(); target++) {
                dtis.add(reader.read(2));
            }
        }
        List<Integer> diffs = null;
        if (customDiffs) {
            diffs = new ArrayList<>();
            for (var size = reader.read(2); size != 0; size = reader.read(2)) {
                diffs.add(reader.read(4 * size) + 1);
                if (reader.overrun()) {
                    break;
                }
            }
        }
        List<Integer> chains = null;
        if (customChains) {
            chains = new ArrayList<>();
            for (var chain = 0; chain < structure.chains(); chain++) {
                chains.add(reader.read(8));
            }
        }
        if (reader.overrun()) {
            throw new MediaException.Malformed("dependency descriptor is truncated");
        }
        return new Av1DependencyDescriptor(start, end, templateId, frameNumber, attached, active, dtis, diffs,
                chains);
    }

    private static Av1TemplateStructure readStructure(MsbBitReader reader) {
        var offset = reader.read(6);
        var targets = reader.read(5) + 1;
        var spatial = new ArrayList<Integer>();
        var temporal = new ArrayList<Integer>();
        var spatialId = 0;
        var temporalId = 0;
        while (true) {
            spatial.add(spatialId);
            temporal.add(temporalId);
            var next = reader.read(2);
            if (next == 3 || reader.overrun()) {
                break;
            }
            if (spatial.size() == 64) {
                throw new MediaException.Malformed("more than 64 frame dependency templates");
            }
            if (next == 1) {
                temporalId++;
            } else if (next == 2) {
                temporalId = 0;
                spatialId++;
            }
        }
        var count = spatial.size();
        var dtis = new ArrayList<List<Integer>>();
        for (var template = 0; template < count; template++) {
            var row = new ArrayList<Integer>();
            for (var target = 0; target < targets; target++) {
                row.add(reader.read(2));
            }
            dtis.add(row);
        }
        var diffs = new ArrayList<List<Integer>>();
        for (var template = 0; template < count; template++) {
            var row = new ArrayList<Integer>();
            while (reader.readFlag() && !reader.overrun()) {
                row.add(reader.read(4) + 1);
            }
            diffs.add(row);
        }
        var chainCount = readNs(reader, targets + 1);
        var protectedBy = new ArrayList<Integer>();
        var chainDiffs = new ArrayList<List<Integer>>();
        if (chainCount > 0) {
            for (var target = 0; target < targets; target++) {
                protectedBy.add(readNs(reader, chainCount));
            }
        }
        for (var template = 0; template < count; template++) {
            var row = new ArrayList<Integer>();
            for (var chain = 0; chain < chainCount; chain++) {
                row.add(reader.read(4));
            }
            chainDiffs.add(row);
        }
        var resolutions = new ArrayList<Av1TemplateStructure.Resolution>();
        if (reader.readFlag()) {
            for (var layer = 0; layer <= spatialId; layer++) {
                resolutions.add(new Av1TemplateStructure.Resolution(reader.read(16) + 1, reader.read(16) + 1));
            }
        }
        if (reader.overrun()) {
            throw new MediaException.Malformed("template dependency structure is truncated");
        }
        var templates = new ArrayList<Av1TemplateStructure.Template>();
        for (var template = 0; template < count; template++) {
            templates.add(new Av1TemplateStructure.Template(spatial.get(template), temporal.get(template),
                    dtis.get(template), diffs.get(template), chainDiffs.get(template)));
        }
        try {
            return new Av1TemplateStructure(offset, targets, templates, chainCount, protectedBy, resolutions);
        } catch (IllegalArgumentException exception) {
            throw new MediaException.Malformed("invalid template dependency structure", exception);
        }
    }

    /** Reads a non-symmetric unsigned value below {@code n} (appendix A.8.2). */
    private static int readNs(MsbBitReader reader, int n) {
        var bits = 32 - Integer.numberOfLeadingZeros(n);
        var threshold = (1 << bits) - n;
        var value = reader.read(bits - 1);
        if (value < threshold) {
            return value;
        }
        return (value << 1) - threshold + reader.read(1);
    }

    private static void writeNs(MsbBitWriter writer, int n, int value) {
        if (n == 1) {
            return;
        }
        var bits = 32 - Integer.numberOfLeadingZeros(n);
        var threshold = (1 << bits) - n;
        if (value < threshold) {
            writer.write(value, bits - 1);
        } else {
            writer.write(value + threshold, bits);
        }
    }

    /**
     * {@return the template this descriptor names in a structure}
     *
     * @param structure the template dependency structure in effect, the attached one when there is one
     * @throws IllegalArgumentException if the template identifier is outside the structure's range
     */
    public Av1TemplateStructure.Template template(Av1TemplateStructure structure) {
        var index = (templateId + 64 - structure.templateIdOffset()) % 64;
        if (index >= structure.templates().size()) {
            throw new IllegalArgumentException("template id " + templateId + " is outside the structure");
        }
        return structure.templates().get(index);
    }

    /**
     * Serializes the descriptor as the header extension element.
     *
     * <p>The extended fields are written when the descriptor attaches a structure, signals active decode targets or
     * overrides a template field, and are otherwise left out, which gives the 3-byte form.
     *
     * @param structure the template dependency structure in effect, which the attached one replaces; it gives the
     * field lengths of the active decode targets and the custom fields
     * @return the element
     * @throws IllegalArgumentException if the template identifier is outside the structure, or a custom field does
     * not have one value per decode target or chain
     */
    public byte[] toByteArray(Av1TemplateStructure structure) {
        var current = attachedStructure != null ? attachedStructure : Objects.requireNonNull(structure, "structure");
        template(current);
        if (frameDecodeTargetIndications != null && frameDecodeTargetIndications.size() != current.decodeTargets()
                || frameChainDiffs != null && frameChainDiffs.size() != current.chains()) {
            throw new IllegalArgumentException("custom fields do not match the structure");
        }
        var writer = new MsbBitWriter(32);
        writer.write(startOfFrame ? 1 : 0, 1);
        writer.write(endOfFrame ? 1 : 0, 1);
        writer.write(templateId, 6);
        writer.write(frameNumber, 16);
        var implied = attachedStructure != null ? (1L << current.decodeTargets()) - 1 : -1;
        var activePresent = activeDecodeTargets >= 0 && activeDecodeTargets != implied;
        var extended = attachedStructure != null || activePresent || frameDecodeTargetIndications != null
                || frameDiffs != null || frameChainDiffs != null;
        if (extended) {
            writer.write(attachedStructure != null ? 1 : 0, 1);
            writer.write(activePresent ? 1 : 0, 1);
            writer.write(frameDecodeTargetIndications != null ? 1 : 0, 1);
            writer.write(frameDiffs != null ? 1 : 0, 1);
            writer.write(frameChainDiffs != null ? 1 : 0, 1);
            if (attachedStructure != null) {
                writeStructure(writer, attachedStructure);
            }
            if (activePresent) {
                writer.writeLong(activeDecodeTargets, current.decodeTargets());
            }
        }
        if (frameDecodeTargetIndications != null) {
            for (var indication : frameDecodeTargetIndications) {
                writer.write(indication, 2);
            }
        }
        if (frameDiffs != null) {
            for (var diff : frameDiffs) {
                var value = diff - 1;
                var size = value < 16 ? 1 : value < 256 ? 2 : 3;
                writer.write(size, 2);
                writer.write(value, 4 * size);
            }
            writer.write(0, 2);
        }
        if (frameChainDiffs != null) {
            for (var diff : frameChainDiffs) {
                writer.write(diff, 8);
            }
        }
        writer.alignWithZeros();
        return writer.toByteArray();
    }

    private static void writeStructure(MsbBitWriter writer, Av1TemplateStructure structure) {
        writer.write(structure.templateIdOffset(), 6);
        writer.write(structure.decodeTargets() - 1, 5);
        var templates = structure.templates();
        for (var index = 0; index < templates.size(); index++) {
            if (index == templates.size() - 1) {
                writer.write(3, 2);
            } else {
                var current = templates.get(index);
                var next = templates.get(index + 1);
                writer.write(next.spatialId() > current.spatialId() ? 2
                        : next.temporalId() > current.temporalId() ? 1 : 0, 2);
            }
        }
        for (var template : templates) {
            for (var indication : template.decodeTargetIndications()) {
                writer.write(indication, 2);
            }
        }
        for (var template : templates) {
            for (var diff : template.frameDiffs()) {
                writer.write(1, 1);
                writer.write(diff - 1, 4);
            }
            writer.write(0, 1);
        }
        writeNs(writer, structure.decodeTargets() + 1, structure.chains());
        if (structure.chains() > 0) {
            for (var chain : structure.protectedBy()) {
                writeNs(writer, structure.chains(), chain);
            }
        }
        for (var template : templates) {
            for (var diff : template.chainDiffs()) {
                writer.write(diff, 4);
            }
        }
        writer.write(structure.resolutions().isEmpty() ? 0 : 1, 1);
        for (var resolution : structure.resolutions()) {
            writer.write(resolution.width() - 1, 16);
            writer.write(resolution.height() - 1, 16);
        }
    }
}
