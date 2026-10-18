package com.github.auties00.smalt.image.metadata;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Holds IPTC Information Interchange Model (IIM) metadata, the caption, keywords, credit and
 * location fields news agencies and photo libraries embed in JPEG files through a Photoshop
 * {@code APP13} segment.
 *
 * <p>The data is a list of data sets, each a record number, a data set number and a value. Text
 * is decoded as UTF-8 when the {@link Field#CODED_CHARACTER_SET coded character set} declares it
 * ({@code ESC % G}) or when the bytes are valid UTF-8, and as ISO-8859-1 otherwise.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class IptcData {
    /**
     * The tag marker that starts every data set.
     */
    private static final int MARKER = 0x1C;

    /**
     * The escape sequence that declares UTF-8 in the coded character set data set.
     */
    private static final byte[] UTF8_DECLARATION = {0x1B, 0x25, 0x47};

    /**
     * The largest number of data sets read.
     */
    private static final int MAX_DATA_SETS = 1 << 16;

    /**
     * The data sets in order.
     */
    private final List<DataSet> dataSets;

    /**
     * Creates an instance.
     *
     * @param dataSets the data sets
     */
    private IptcData(List<DataSet> dataSets) {
        this.dataSets = List.copyOf(dataSets);
    }

    /**
     * Parses IIM data, the payload of the Photoshop {@code IPTC-NAA} resource.
     *
     * <p>Parsing stops at the first byte that is not a tag marker, or at a data set whose length
     * runs past the data; the data sets before it are kept.
     *
     * @param iim the data
     * @return the data sets
     * @throws NullPointerException if {@code iim} is {@code null}
     */
    public static IptcData parse(byte[] iim) {
        Objects.requireNonNull(iim, "iim");
        var result = new ArrayList<DataSet>();
        var position = 0;
        while (iim.length - position >= 5 && (iim[position] & 0xFF) == MARKER && result.size() < MAX_DATA_SETS) {
            var record = iim[position + 1] & 0xFF;
            var number = iim[position + 2] & 0xFF;
            long length = ((iim[position + 3] & 0xFF) << 8) | (iim[position + 4] & 0xFF);
            position += 5;
            if ((length & 0x8000) != 0) {
                var bytes = (int) (length & 0x7FFF);
                if (bytes > 4 || iim.length - position < bytes) {
                    break;
                }
                length = 0;
                for (var i = 0; i < bytes; i++) {
                    length = (length << 8) | (iim[position + i] & 0xFF);
                }
                position += bytes;
            }
            if (length > iim.length - position) {
                break;
            }
            result.add(new DataSet(record, number, Arrays.copyOfRange(iim, position, position + (int) length)));
            position += (int) length;
        }
        return new IptcData(result);
    }

    /**
     * Returns an instance holding data sets.
     *
     * @param dataSets the data sets, in order
     * @return the instance
     * @throws NullPointerException if the list or a data set is {@code null}
     */
    public static IptcData of(List<DataSet> dataSets) {
        return new IptcData(dataSets);
    }

    /**
     * Returns the data sets in order.
     *
     * @return the data sets; unmodifiable
     */
    public List<DataSet> dataSets() {
        return dataSets;
    }

    /**
     * Returns whether there is no data set.
     *
     * @return {@code true} when empty
     */
    public boolean isEmpty() {
        return dataSets.isEmpty();
    }

    /**
     * Returns every text value of a field.
     *
     * @param field the field
     * @return the values in order; unmodifiable
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public List<String> strings(Field field) {
        return strings(field.record(), field.number());
    }

    /**
     * Returns every text value of a data set.
     *
     * @param record the record number
     * @param number the data set number
     * @return the values in order; unmodifiable
     */
    public List<String> strings(int record, int number) {
        var utf8 = declaresUtf8();
        var result = new ArrayList<String>();
        for (var dataSet : dataSets) {
            if (dataSet.record() == record && dataSet.number() == number) {
                result.add(decode(dataSet.value(), utf8));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Returns the first text value of a field.
     *
     * @param field the field
     * @return the value, or empty when absent
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public Optional<String> string(Field field) {
        var values = strings(field);
        return values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
    }

    /**
     * Returns whether a field names a place: the city, sub-location, province or state, country
     * code or country of the content.
     *
     * @return {@code true} when one of these fields is present
     */
    public boolean hasLocation() {
        for (var dataSet : dataSets) {
            if (isLocation(dataSet)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a copy without the fields that name a place.
     *
     * @return the new instance
     */
    public IptcData withoutLocation() {
        return withoutIf(IptcData::isLocation);
    }

    /**
     * Returns a copy without the data sets of a field.
     *
     * @param field the field
     * @return the new instance
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public IptcData without(Field field) {
        Objects.requireNonNull(field, "field");
        return withoutIf(dataSet -> dataSet.record() == field.record() && dataSet.number() == field.number());
    }

    /**
     * Returns a copy without the data sets a predicate selects.
     *
     * @param filter selects the data sets to remove
     * @return the new instance, or this instance when nothing is removed
     * @throws NullPointerException if {@code filter} is {@code null}
     */
    public IptcData withoutIf(Predicate<DataSet> filter) {
        Objects.requireNonNull(filter, "filter");
        var result = new ArrayList<DataSet>();
        for (var dataSet : dataSets) {
            if (!filter.test(dataSet)) {
                result.add(dataSet);
            }
        }
        return result.size() == dataSets.size() ? this : new IptcData(result);
    }

    /**
     * Encodes the data sets in IIM form.
     *
     * @return the bytes, using the extended length form for values of 32768 bytes or more
     */
    public byte[] toBytes() {
        var out = new ByteArrayOutputStream();
        for (var dataSet : dataSets) {
            var value = dataSet.value();
            out.write(MARKER);
            out.write(dataSet.record());
            out.write(dataSet.number());
            if (value.length < 0x8000) {
                out.write(value.length >>> 8);
                out.write(value.length);
            } else {
                out.write(0x80);
                out.write(4);
                out.write(value.length >>> 24);
                out.write(value.length >>> 16);
                out.write(value.length >>> 8);
                out.write(value.length);
            }
            out.writeBytes(value);
        }
        return out.toByteArray();
    }

    /**
     * Returns whether a data set names a place.
     *
     * @param dataSet the data set
     * @return {@code true} for the location fields of record 2
     */
    private static boolean isLocation(DataSet dataSet) {
        return dataSet.record() == 2 && (dataSet.number() == Field.CITY.number() || dataSet.number() == Field.SUBLOCATION.number()
                || dataSet.number() == Field.PROVINCE_STATE.number() || dataSet.number() == Field.COUNTRY_CODE.number()
                || dataSet.number() == Field.COUNTRY.number());
    }

    /**
     * Returns whether the coded character set declares UTF-8.
     *
     * @return {@code true} when record 1 data set 90 holds {@code ESC % G}
     */
    private boolean declaresUtf8() {
        for (var dataSet : dataSets) {
            if (dataSet.record() == Field.CODED_CHARACTER_SET.record() && dataSet.number() == Field.CODED_CHARACTER_SET.number()
                    && Arrays.equals(dataSet.value(), UTF8_DECLARATION)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Decodes a text value.
     *
     * @param value the bytes
     * @param utf8  whether UTF-8 is declared
     * @return the text
     */
    private static String decode(byte[] value, boolean utf8) {
        if (utf8) {
            return new String(value, StandardCharsets.UTF_8);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value)).toString();
        } catch (CharacterCodingException exception) {
            return new String(value, StandardCharsets.ISO_8859_1);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof IptcData that && dataSets.equals(that.dataSets);
    }

    @Override
    public int hashCode() {
        return dataSets.hashCode();
    }

    @Override
    public String toString() {
        return "IptcData" + dataSets;
    }

    /**
     * Holds one data set.
     *
     * @param record the record number, 1 (envelope) or 2 (application) for the common fields
     * @param number the data set number within the record
     * @param value  the value bytes
     */
    public record DataSet(int record, int number, byte[] value) {
        /**
         * Validates the data set and copies the value.
         *
         * @throws NullPointerException     if {@code value} is {@code null}
         * @throws IllegalArgumentException if a number is outside 0..255
         */
        public DataSet {
            Objects.requireNonNull(value, "value");
            if (record < 0 || record > 255 || number < 0 || number > 255) {
                throw new IllegalArgumentException("invalid IPTC data set " + record + ":" + number);
            }
            value = value.clone();
        }

        /**
         * Returns the value bytes.
         *
         * @return a copy of the value
         */
        @Override
        public byte[] value() {
            return value.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof DataSet that && record == that.record && number == that.number
                    && Arrays.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(record, number, Arrays.hashCode(value));
        }

        @Override
        public String toString() {
            return record + ":" + number + "=" + value.length + " bytes";
        }
    }

    /**
     * Names the commonly used IIM data sets.
     */
    public enum Field {
        /**
         * The coded character set of the text, record 1 data set 90.
         */
        CODED_CHARACTER_SET(1, 90),

        /**
         * The object name, a short title, record 2 data set 5.
         */
        OBJECT_NAME(2, 5),

        /**
         * The urgency, record 2 data set 10.
         */
        URGENCY(2, 10),

        /**
         * The category, record 2 data set 15.
         */
        CATEGORY(2, 15),

        /**
         * A supplemental category, record 2 data set 20.
         */
        SUPPLEMENTAL_CATEGORY(2, 20),

        /**
         * A keyword, record 2 data set 25.
         */
        KEYWORDS(2, 25),

        /**
         * The special instructions, record 2 data set 40.
         */
        SPECIAL_INSTRUCTIONS(2, 40),

        /**
         * The creation date ({@code CCYYMMDD}), record 2 data set 55.
         */
        DATE_CREATED(2, 55),

        /**
         * The creation time ({@code HHMMSS+HHMM}), record 2 data set 60.
         */
        TIME_CREATED(2, 60),

        /**
         * The creator, record 2 data set 80.
         */
        BYLINE(2, 80),

        /**
         * The creator's job title, record 2 data set 85.
         */
        BYLINE_TITLE(2, 85),

        /**
         * The city, record 2 data set 90.
         */
        CITY(2, 90),

        /**
         * The sub-location, record 2 data set 92.
         */
        SUBLOCATION(2, 92),

        /**
         * The province or state, record 2 data set 95.
         */
        PROVINCE_STATE(2, 95),

        /**
         * The ISO 3166 country code, record 2 data set 100.
         */
        COUNTRY_CODE(2, 100),

        /**
         * The country name, record 2 data set 101.
         */
        COUNTRY(2, 101),

        /**
         * The original transmission reference, record 2 data set 103.
         */
        TRANSMISSION_REFERENCE(2, 103),

        /**
         * The headline, record 2 data set 105.
         */
        HEADLINE(2, 105),

        /**
         * The credit line, record 2 data set 110.
         */
        CREDIT(2, 110),

        /**
         * The source, record 2 data set 115.
         */
        SOURCE(2, 115),

        /**
         * The copyright notice, record 2 data set 116.
         */
        COPYRIGHT_NOTICE(2, 116),

        /**
         * The caption or abstract, record 2 data set 120.
         */
        CAPTION(2, 120),

        /**
         * The writer of the caption, record 2 data set 122.
         */
        WRITER(2, 122);

        /**
         * The record number.
         */
        private final int record;

        /**
         * The data set number.
         */
        private final int number;

        /**
         * Creates a constant.
         *
         * @param record the record number
         * @param number the data set number
         */
        Field(int record, int number) {
            this.record = record;
            this.number = number;
        }

        /**
         * Returns the record number.
         *
         * @return the record number
         */
        public int record() {
            return record;
        }

        /**
         * Returns the data set number.
         *
         * @return the data set number
         */
        public int number() {
            return number;
        }
    }
}
