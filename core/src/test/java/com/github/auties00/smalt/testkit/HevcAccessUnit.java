package com.github.auties00.smalt.testkit;

import com.github.auties00.smalt.internal.nal.NalRecords;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * The NAL units of the first access unit of an Annex B HEVC stream, split into its parameter sets and its other units,
 * with the configuration records and payloads a HEIF image item made of it carries: an {@code hvcC} record of the base
 * layer's parameter sets and an {@code lhvC} record of every layer's, as in Nokia's multilayer files, whose base and
 * layered items share one payload holding the units of both layers.
 *
 * @param parameterSets the video, sequence and picture parameter sets of the stream's first access unit, of every
 *                      layer, without start codes
 * @param units         the other NAL units of the access unit, of every layer, without start codes
 */
public record HevcAccessUnit(List<byte[]> parameterSets, List<byte[]> units) {
    /**
     * Takes the first access unit of a stream: the units up to the second first slice segment of the layer the first
     * picture belongs to.
     *
     * @param stream the Annex B stream
     * @return the access unit
     */
    public static HevcAccessUnit first(byte[] stream) {
        var parameterSets = new ArrayList<byte[]>();
        var units = new ArrayList<byte[]>();
        var firstLayer = -1;
        for (var unit : nalUnits(stream)) {
            if (unit.length < 3) {
                continue;
            }
            var type = (unit[0] >> 1) & 0x3F;
            var layer = layerOf(unit);
            if (type >= 32 && type <= 34) {
                parameterSets.add(unit);
                continue;
            }
            if (type < 32 && (unit[2] & 0x80) != 0) {
                if (firstLayer < 0) {
                    firstLayer = layer;
                } else if (layer == firstLayer) {
                    break;
                }
            }
            units.add(unit);
        }
        return new HevcAccessUnit(List.copyOf(parameterSets), List.copyOf(units));
    }

    /**
     * {@return the non-parameter-set units of some layers}
     *
     * @param layers selects the layers by {@code nuh_layer_id}
     */
    public List<byte[]> slices(IntPredicate layers) {
        return units.stream().filter(unit -> layers.test(layerOf(unit))).toList();
    }

    /**
     * {@return an {@code hvcC} record of the base layer's parameter sets, with 4-byte length prefixes}
     */
    public byte[] hvcC() {
        return record(parameterSets.stream().filter(unit -> layerOf(unit) == 0).toList());
    }

    /**
     * {@return an {@code lhvC} record of the parameter sets of every layer, with 4-byte length prefixes}
     *
     * @throws IllegalStateException if the stream has no parameter sets above the base layer
     */
    public byte[] lhvC() {
        var layered = NalRecords.splitLayeredHevc(record(parameterSets))[1];
        if (layered == null) {
            throw new IllegalStateException("the stream has no parameter sets above the base layer");
        }
        var base = hvcC();
        var out = new ByteArrayOutputStream();
        out.write(layered, 0, 5);
        out.write((layered[5] & 0xFF) + (base[22] & 0xFF));
        out.write(base, 23, base.length - 23);
        out.write(layered, 6, layered.length - 6);
        return out.toByteArray();
    }

    /**
     * {@return the {@code nuh_layer_id} of a NAL unit}
     *
     * @param unit the NAL unit, without its start code
     */
    public static int layerOf(byte[] unit) {
        return ((unit[0] & 1) << 5) | ((unit[1] & 0xFF) >> 3);
    }

    /**
     * Joins NAL units into a payload of units with 4-byte length prefixes.
     *
     * @param units the units
     * @return the payload
     */
    public static byte[] lengthPrefixed(List<byte[]> units) {
        var out = new ByteArrayOutputStream();
        for (var unit : units) {
            out.write(unit.length >>> 24);
            out.write(unit.length >>> 16);
            out.write(unit.length >>> 8);
            out.write(unit.length);
            out.writeBytes(unit);
        }
        return out.toByteArray();
    }

    /**
     * Splits an Annex B stream into its NAL units, without start codes and trailing zero bytes.
     *
     * @param data the stream
     * @return the units
     */
    public static List<byte[]> nalUnits(byte[] data) {
        var starts = new ArrayList<Integer>();
        for (var i = 0; i + 2 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                starts.add(i + 3);
                i += 2;
            }
        }
        var units = new ArrayList<byte[]>();
        for (var k = 0; k < starts.size(); k++) {
            var end = k + 1 < starts.size() ? starts.get(k + 1) - 3 : data.length;
            while (end > starts.get(k) && data[end - 1] == 0) {
                end--;
            }
            units.add(Arrays.copyOfRange(data, starts.get(k), end));
        }
        return units;
    }

    private static byte[] record(List<byte[]> sets) {
        var out = new ByteArrayOutputStream();
        out.write(1);
        out.writeBytes(new byte[20]);
        out.write(0xFC | 3);
        out.write(sets.size());
        for (var set : sets) {
            out.write(0x80 | ((set[0] >> 1) & 0x3F));
            out.write(0);
            out.write(1);
            out.write(set.length >> 8);
            out.write(set.length);
            out.writeBytes(set);
        }
        return out.toByteArray();
    }
}
