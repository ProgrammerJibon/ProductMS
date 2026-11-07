package com.productms.db;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Small JDBC helper: parameter binding, list/one queries and inserts returning keys.
 */
public final class Jdbc {

    private Jdbc() {
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    public static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            int idx = i + 1;
            if (p == null) {
                ps.setNull(idx, Types.NULL);
            } else if (p instanceof LocalDate) {
                ps.setDate(idx, Date.valueOf((LocalDate) p));
            } else if (p instanceof LocalDateTime) {
                ps.setTimestamp(idx, Timestamp.valueOf((LocalDateTime) p));
            } else if (p instanceof Boolean) {
                ps.setInt(idx, ((Boolean) p) ? 1 : 0);
            } else {
                ps.setObject(idx, p);
            }
        }
    }

    public static <T> List<T> list(Connection con, String sql, RowMapper<T> m, Object... params) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(m.map(rs));
                }
                return out;
            }
        }
    }

    public static <T> List<T> list(String sql, RowMapper<T> m, Object... params) throws SQLException {
        try (Connection con = Database.getConnection()) {
            return list(con, sql, m, params);
        }
    }

    public static <T> T one(Connection con, String sql, RowMapper<T> m, Object... params) throws SQLException {
        List<T> l = list(con, sql, m, params);
        return l.isEmpty() ? null : l.get(0);
    }

    public static <T> T one(String sql, RowMapper<T> m, Object... params) throws SQLException {
        try (Connection con = Database.getConnection()) {
            return one(con, sql, m, params);
        }
    }

    public static BigDecimal decimal(Connection con, String sql, Object... params) throws SQLException {
        BigDecimal v = one(con, sql, rs -> rs.getBigDecimal(1), params);
        return v == null ? BigDecimal.ZERO : v;
    }

    public static BigDecimal decimal(String sql, Object... params) throws SQLException {
        try (Connection con = Database.getConnection()) {
            return decimal(con, sql, params);
        }
    }

    public static int count(String sql, Object... params) throws SQLException {
        Integer v = one(sql, rs -> rs.getInt(1), params);
        return v == null ? 0 : v;
    }

    public static int update(Connection con, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    public static int update(String sql, Object... params) throws SQLException {
        try (Connection con = Database.getConnection()) {
            return update(con, sql, params);
        }
    }

    /** Executes an INSERT and returns the generated key. */
    public static int insert(Connection con, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, params);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : -1;
            }
        }
    }

    public static int insert(String sql, Object... params) throws SQLException {
        try (Connection con = Database.getConnection()) {
            return insert(con, sql, params);
        }
    }

    public static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    public static LocalDate date(ResultSet rs, String col) throws SQLException {
        Date d = rs.getDate(col);
        return d == null ? null : d.toLocalDate();
    }

    public static LocalDateTime time(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toLocalDateTime();
    }

    /** Runs any SELECT and returns its columns and rows (used by reports). */
    public static TableData table(String sql, Object... params) throws SQLException {
        try (Connection con = Database.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                TableData t = new TableData();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    t.columns.add(md.getColumnLabel(i));
                }
                while (rs.next()) {
                    List<Object> row = new ArrayList<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        Object o = rs.getObject(i);
                        if (o instanceof Timestamp) {
                            o = ((Timestamp) o).toLocalDateTime();
                        } else if (o instanceof Date) {
                            o = ((Date) o).toLocalDate();
                        } else if (o instanceof Long || o instanceof java.math.BigInteger) {
                            o = ((Number) o).longValue();
                        }
                        row.add(o);
                    }
                    t.rows.add(row);
                }
                return t;
            }
        }
    }

    /** Columns + rows of a report. */
    public static class TableData {
        public final List<String> columns = new ArrayList<>();
        public final List<List<Object>> rows = new ArrayList<>();
    }
}
