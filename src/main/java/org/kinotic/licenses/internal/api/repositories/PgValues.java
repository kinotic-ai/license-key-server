package org.kinotic.licenses.internal.api.repositories;

import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Conversions between the Vert.x Postgres client's values and the model's: {@code TIMESTAMPTZ} columns read and
 * write as {@link Instant}, enum columns as their names, and a result set maps row by row.
 */
final class PgValues {

    private PgValues() {}

    static Instant instant(Row row, String column) {
        OffsetDateTime value = row.getOffsetDateTime(column);
        return value == null ? null : value.toInstant();
    }

    static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static <E extends Enum<E>> E enumValue(Row row, String column, Class<E> type) {
        String value = row.getString(column);
        return value == null ? null : Enum.valueOf(type, value);
    }

    /** A {@code TEXT[]} column of enum names as a set, empty for SQL {@code NULL}. */
    static <E extends Enum<E>> Set<E> enumSet(Row row, String column, Class<E> type) {
        Set<E> ret = EnumSet.noneOf(type);
        String[] names = row.getArrayOfStrings(column);
        if (names != null) {
            for (String name : names) {
                ret.add(Enum.valueOf(type, name));
            }
        }
        return ret;
    }

    static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    static <T> List<T> map(RowSet<Row> rows, Function<Row, T> mapper) {
        List<T> ret = new ArrayList<>(rows.size());
        for (Row row : rows) {
            ret.add(mapper.apply(row));
        }
        return ret;
    }

    static <T> T first(RowSet<Row> rows, Function<Row, T> mapper) {
        return rows.size() == 0 ? null : mapper.apply(rows.iterator().next());
    }
}
