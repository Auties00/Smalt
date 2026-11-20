package com.github.auties00.smalt.video.h263.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Holds the multi-picture buffer of the Enhanced Reference Picture Selection mode for one layer (ITU-T H.263 U.4).
 *
 * <p>Short-term pictures are kept from the most recent to the oldest, long-term pictures by their long-term index;
 * the default relative index order lists the short-term pictures first, then the long-term ones. The buffer counts its
 * capacity in sub-pictures as the last buffer structure command set it, and the sliding window drops the oldest
 * short-term pictures when a new picture does not fit.
 */
final class ReferenceBuffer {
    /**
     * The capacity in pictures assumed before the stream sets a buffer structure.
     */
    private static final int DEFAULT_CAPACITY = 16;

    /**
     * One buffered picture.
     *
     * @param picture the decoded picture, held by the buffer
     * @param pictureNumber its picture number
     * @param longTermIndex its long-term index, or -1 for a short-term picture
     * @param removedAreas the sub-pictures marked unused, or {@code null}
     */
    private record Entry(PlusPicture picture, int pictureNumber, int longTermIndex, boolean[] removedAreas) {
    }

    /**
     * The short-term pictures, the most recent first.
     */
    private final List<Entry> shortTerm = new ArrayList<>();

    /**
     * The long-term pictures by long-term index.
     */
    private final TreeMap<Integer, Entry> longTerm = new TreeMap<>();

    /**
     * The pictures not re-mapped yet while an order is built.
     */
    private final List<Entry> remaining = new ArrayList<>();

    /**
     * The receiver of the pictures that leave the buffer.
     */
    private final Consumer<PlusPicture> release;

    /**
     * The sub-picture width indication in effect, or -1 before any buffer structure.
     */
    int spwi = -1;

    /**
     * The sub-picture height indication in effect.
     */
    int sphi = 1;

    /**
     * The capacity in sub-pictures, or -1 before any buffer structure.
     */
    private int capacity = -1;

    /**
     * The largest long-term index plus one.
     */
    private int maxLongTerm;

    /**
     * Creates an empty buffer.
     *
     * @param release the receiver of the pictures that leave the buffer
     */
    ReferenceBuffer(Consumer<PlusPicture> release) {
        this.release = release;
    }

    /**
     * {@return the pictures the buffer holds, short-term and long-term}
     */
    int size() {
        return shortTerm.size() + longTerm.size();
    }

    /**
     * Empties the buffer.
     */
    void clear() {
        for (var entry : shortTerm) {
            release.accept(entry.picture());
        }
        for (var entry : longTerm.values()) {
            release.accept(entry.picture());
        }
        shortTerm.clear();
        longTerm.clear();
    }

    /**
     * Returns whether the buffer holds no picture.
     *
     * @return whether it is empty
     */
    boolean isEmpty() {
        return shortTerm.isEmpty() && longTerm.isEmpty();
    }

    /**
     * Lists the pictures in the relative index order a layer re-maps (U.3.1.5.2).
     *
     * @param layer the ERPS layer holding the re-mappings, or {@code null} for the default order
     * @param currentNumber the picture number of the current picture
     * @param out receives the pictures
     */
    void order(ErpsLayer layer, int currentNumber, List<PlusPicture> out) {
        out.clear();
        var remaining = this.remaining;
        remaining.clear();
        remaining.addAll(shortTerm);
        remaining.addAll(longTerm.values());
        if (layer != null) {
            var prediction = currentNumber;
            for (var i = 0; i < layer.remaps; i++) {
                Entry found = null;
                if (layer.remapKinds[i] == ErpsLayer.REMAP_LONG_TERM) {
                    for (var entry : remaining) {
                        if (entry.longTermIndex() == layer.remapValues[i]) {
                            found = entry;
                            break;
                        }
                    }
                } else {
                    var difference = layer.remapKinds[i] == ErpsLayer.REMAP_NEGATIVE ? -layer.remapValues[i]
                            : layer.remapValues[i];
                    prediction = (prediction + difference) & 1023;
                    for (var entry : remaining) {
                        if (entry.longTermIndex() < 0 && entry.pictureNumber() == prediction) {
                            found = entry;
                            break;
                        }
                    }
                }
                if (found != null) {
                    remaining.remove(found);
                    out.add(found.picture());
                }
            }
        }
        for (var entry : remaining) {
            out.add(entry.picture());
        }
    }

    /**
     * Stores a decoded picture as the buffering type and the memory management operations of its picture layer
     * specify (U.4.5); B-pictures are not stored.
     *
     * @param picture the decoded picture to store
     * @param pictureNumber its picture number
     * @param layer the ERPS layer of its picture header
     * @param retain called once for the picture when the buffer keeps it
     */
    void store(PlusPicture picture, int pictureNumber, ErpsLayer layer, Consumer<PlusPicture> retain) {
        var subPictures = spwi < 0 ? 1 : ErpsLayer.subPictures(picture.width, picture.height, spwi, sphi);
        if (layer.slidingWindow) {
            var limit = capacity < 0 ? DEFAULT_CAPACITY * subPictures : capacity;
            while (used(picture) + subPictures > limit && !shortTerm.isEmpty()) {
                release.accept(shortTerm.removeLast().picture());
            }
            shortTerm.addFirst(new Entry(picture, pictureNumber, -1, null));
            retain.accept(picture);
            return;
        }
        shortTerm.addFirst(new Entry(picture, pictureNumber, -1, null));
        retain.accept(picture);
        for (var i = 0; i < layer.operations; i++) {
            switch (layer.operationKinds[i]) {
                case ErpsLayer.MMCO_BUFFER -> {
                    spwi = layer.operationFirst[i];
                    sphi = Math.max(1, layer.operationSecond[i]);
                    capacity = layer.operationThird[i];
                    if (layer.operationFourth[i] == 1) {
                        while (shortTerm.size() > 1) {
                            release.accept(shortTerm.removeLast().picture());
                        }
                        if (!shortTerm.isEmpty() && shortTerm.getFirst().picture() != picture) {
                            release.accept(shortTerm.removeFirst().picture());
                        }
                        for (var entry : longTerm.values()) {
                            release.accept(entry.picture());
                        }
                        longTerm.clear();
                    }
                }
                case ErpsLayer.MMCO_MAX_LONG -> {
                    maxLongTerm = layer.operationFirst[i];
                    var dropped = longTerm.tailMap(maxLongTerm, true);
                    for (var entry : dropped.values()) {
                        release.accept(entry.picture());
                    }
                    dropped.clear();
                }
                case ErpsLayer.MMCO_SHORT_UNUSED -> {
                    var number = (pictureNumber - layer.operationFirst[i]) & 1023;
                    for (var iterator = shortTerm.iterator(); iterator.hasNext(); ) {
                        var entry = iterator.next();
                        if (entry.pictureNumber() == number) {
                            iterator.remove();
                            release.accept(entry.picture());
                            break;
                        }
                    }
                }
                case ErpsLayer.MMCO_LONG_UNUSED -> {
                    var entry = longTerm.remove(layer.operationFirst[i]);
                    if (entry != null) {
                        release.accept(entry.picture());
                    }
                }
                case ErpsLayer.MMCO_ASSIGN_LONG -> {
                    var number = (pictureNumber - layer.operationFirst[i]) & 1023;
                    var index = layer.operationSecond[i];
                    for (var iterator = shortTerm.iterator(); iterator.hasNext(); ) {
                        var entry = iterator.next();
                        if (entry.pictureNumber() == number) {
                            iterator.remove();
                            var previous = longTerm.put(index, new Entry(entry.picture(), number, index,
                                    entry.removedAreas()));
                            if (previous != null) {
                                release.accept(previous.picture());
                            }
                            break;
                        }
                    }
                }
                case ErpsLayer.MMCO_SHORT_AREAS, ErpsLayer.MMCO_LONG_AREAS -> {
                    var areas = layer.operationAreas[i];
                    var longTermArea = layer.operationKinds[i] == ErpsLayer.MMCO_LONG_AREAS;
                    var number = (pictureNumber - layer.operationFirst[i]) & 1023;
                    if (longTermArea) {
                        var entry = longTerm.get(layer.operationFirst[i]);
                        if (entry != null) {
                            longTerm.put(entry.longTermIndex(), new Entry(entry.picture(), entry.pictureNumber(),
                                    entry.longTermIndex(), areas));
                        }
                    } else {
                        for (var j = 0; j < shortTerm.size(); j++) {
                            var entry = shortTerm.get(j);
                            if (entry.pictureNumber() == number) {
                                shortTerm.set(j, new Entry(entry.picture(), number, -1, areas));
                                break;
                            }
                        }
                    }
                }
                default -> {
                }
            }
        }
    }

    /**
     * Returns the sub-pictures the buffered pictures occupy.
     *
     * @param current a picture of the current size, to count the sub-pictures of a whole picture
     * @return the count
     */
    private int used(PlusPicture current) {
        var whole = spwi < 0 ? 1 : ErpsLayer.subPictures(current.width, current.height, spwi, sphi);
        var total = 0;
        for (var entry : shortTerm) {
            total += occupied(entry, whole);
        }
        for (var entry : longTerm.values()) {
            total += occupied(entry, whole);
        }
        return total;
    }

    /**
     * Returns the sub-pictures one entry occupies.
     *
     * @param entry the buffered picture
     * @param whole the sub-pictures of a whole picture
     * @return the count
     */
    private static int occupied(Entry entry, int whole) {
        if (entry.removedAreas() == null) {
            return whole;
        }
        var count = 0;
        for (var removed : entry.removedAreas()) {
            if (!removed) {
                count++;
            }
        }
        return count;
    }
}
