package com.github.auties00.smalt.rtp;

import java.util.List;
import java.util.Objects;

/**
 * Holds the template dependency structure of the AV1 dependency descriptor (appendix A.8 of the AV1 RTP payload
 * specification): the frame dependency templates a coded video sequence refers to, its decode targets and chains.
 *
 * <p>Decode target indications take the values of table A.1: 0 not present, 1 discardable, 2 switch, 3 required.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param templateIdOffset the template identifier of the first template, from 0 to 63
 * @param decodeTargets the number of decode targets, from 1 to 32
 * @param templates the frame dependency templates, at least one and at most 64, unmodifiable
 * @param chains the number of chains, from 0 to {@code decodeTargets}
 * @param protectedBy the chain protecting each decode target, one per decode target when there are chains and
 * none otherwise, unmodifiable
 * @param resolutions the largest render size of each spatial layer, empty when not signaled, unmodifiable
 */
public record Av1TemplateStructure(int templateIdOffset, int decodeTargets, List<Template> templates, int chains,
                                   List<Integer> protectedBy, List<Resolution> resolutions) {
    /**
     * Holds one frame dependency template.
     *
     * @param spatialId the spatial layer, from 0
     * @param temporalId the temporal layer, from 0
     * @param decodeTargetIndications the indication for each decode target, from 0 to 3, unmodifiable
     * @param frameDiffs the distances back to the referenced frames, each from 1 to 16, unmodifiable
     * @param chainDiffs the distance back to the previous frame of each chain, each from 0 to 15, unmodifiable
     */
    public record Template(int spatialId, int temporalId, List<Integer> decodeTargetIndications,
                           List<Integer> frameDiffs, List<Integer> chainDiffs) {
        /**
         * Validates the fields and copies the lists.
         *
         * @throws NullPointerException if an element of a list is {@code null}
         * @throws IllegalArgumentException if a value is outside its range
         */
        public Template {
            decodeTargetIndications = List.copyOf(decodeTargetIndications);
            frameDiffs = List.copyOf(frameDiffs);
            chainDiffs = List.copyOf(chainDiffs);
            if (spatialId < 0 || temporalId < 0) {
                throw new IllegalArgumentException("negative layer");
            }
            for (var indication : decodeTargetIndications) {
                check("decode target indication", indication, 0, 3);
            }
            for (var diff : frameDiffs) {
                check("frame diff", diff, 1, 16);
            }
            for (var diff : chainDiffs) {
                check("chain diff", diff, 0, 15);
            }
        }
    }

    /**
     * Holds the largest render size of one spatial layer.
     *
     * @param width the width in samples, from 1 to 65536
     * @param height the height in samples, from 1 to 65536
     */
    public record Resolution(int width, int height) {
        /**
         * Validates the fields.
         *
         * @throws IllegalArgumentException if a dimension is outside its range
         */
        public Resolution {
            check("width", width, 1, 65_536);
            check("height", height, 1, 65_536);
        }
    }

    /**
     * Validates the structure and copies the lists.
     *
     * @throws NullPointerException if an element of a list is {@code null}
     * @throws IllegalArgumentException if a count or value is outside its range, a template does not have one
     * indication per decode target and one diff per chain, the templates' layers are not in the order the syntax
     * codes them (each template with the layers of the previous one, the next temporal layer, or temporal layer 0 of
     * the next spatial layer), or the resolutions do not match the spatial layers
     */
    public Av1TemplateStructure {
        templates = List.copyOf(templates);
        protectedBy = List.copyOf(protectedBy);
        resolutions = List.copyOf(resolutions);
        check("template id offset", templateIdOffset, 0, 63);
        check("decode target count", decodeTargets, 1, 32);
        check("template count", templates.size(), 1, 64);
        check("chain count", chains, 0, decodeTargets);
        if (protectedBy.size() != (chains == 0 ? 0 : decodeTargets)) {
            throw new IllegalArgumentException(protectedBy.size() + " protecting chains for " + decodeTargets
                    + " decode targets");
        }
        for (var chain : protectedBy) {
            check("protecting chain", chain, 0, chains - 1);
        }
        var spatial = 0;
        var temporal = 0;
        for (var index = 0; index < templates.size(); index++) {
            var template = templates.get(index);
            if (index == 0 ? template.spatialId() != 0 || template.temporalId() != 0
                    : !(template.spatialId() == spatial && (template.temporalId() == temporal
                    || template.temporalId() == temporal + 1)) && !(template.spatialId() == spatial + 1
                    && template.temporalId() == 0)) {
                throw new IllegalArgumentException("template " + index + " breaks the layer order");
            }
            spatial = template.spatialId();
            temporal = template.temporalId();
            if (template.decodeTargetIndications().size() != decodeTargets
                    || template.chainDiffs().size() != chains) {
                throw new IllegalArgumentException("template " + index + " does not match the decode target and "
                        + "chain counts");
            }
        }
        if (!resolutions.isEmpty() && resolutions.size() != spatial + 1) {
            throw new IllegalArgumentException(resolutions.size() + " resolutions for " + (spatial + 1)
                    + " spatial layers");
        }
    }

    private static void check(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " " + value + " is not from " + min + " to " + max);
        }
    }

    /**
     * Returns the structure of a stream with one spatial and one temporal layer: a key frame template and a delta
     * frame template that refers to the previous frame, one decode target protected by one chain.
     *
     * @param templateIdOffset the template identifier of the key frame template, from 0 to 63
     * @return the structure
     * @throws IllegalArgumentException if {@code templateIdOffset} is outside its range
     */
    public static Av1TemplateStructure singleLayer(int templateIdOffset) {
        return new Av1TemplateStructure(templateIdOffset, 1, List.of(
                new Template(0, 0, List.of(2), List.of(), List.of(0)),
                new Template(0, 0, List.of(2), List.of(1), List.of(1))), 1, List.of(0), List.of());
    }
}
