package com.productms.db;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps an existing database in step with db/schema.sql without losing data.
 * <ol>
 *   <li>Runs schema.sql (every statement is CREATE ... IF NOT EXISTS, so new tables appear).</li>
 *   <li>Adds every column that schema.sql defines but the live table lacks.</li>
 *   <li>Widens columns whose type changed (e.g. ENUM to VARCHAR, longer VARCHAR).</li>
 * </ol>
 * The migration result is recorded in the {@code settings} table as {@code schema_version}.
 */
public final class Migrations {

    public static final int SCHEMA_VERSION = 3;

    private static final Pattern TABLE = Pattern.compile(
            "CREATE TABLE IF NOT EXISTS\\s+(\\w+)\\s*\\((.*?)\\)\\s*ENGINE", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern COLUMN = Pattern.compile("^\\s*([a-z_][a-z0-9_]*)\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    private Migrations() {
    }

    /** Result of a migration run, for the start-up log. */
    public static class Result {
        public final List<String> changes = new ArrayList<>();
    }

    public static Result migrate(Connection con, String schemaSql) throws SQLException {
        Result r = new Result();
        try (Statement st = con.createStatement()) {
            Database.runScript(st, schemaSql);
        }
        String db = con.getCatalog();
        Matcher t = TABLE.matcher(schemaSql);
        while (t.find()) {
            String table = t.group(1);
            Map<String, String> live = liveColumns(con, db, table);
            StringBuilder body = new StringBuilder();
            for (String rawLine : t.group(2).split("\\R")) {
                body.append(stripComment(rawLine)).append(' ');
            }
            for (String part : splitTopLevel(body.toString())) {
                String line = part.trim().replaceAll("\\s+", " ");
                Matcher c = COLUMN.matcher(line);
                if (line.isEmpty() || !c.matches()) {
                    continue;
                }
                String name = c.group(1);
                String upper = name.toUpperCase(Locale.ROOT);
                if (upper.equals("CONSTRAINT") || upper.equals("INDEX") || upper.equals("UNIQUE")
                        || upper.equals("PRIMARY") || upper.equals("KEY")) {
                    continue;
                }
                String def = c.group(2);
                String liveType = live.get(name.toLowerCase(Locale.ROOT));
                if (liveType == null) {
                    exec(con, "ALTER TABLE " + table + " ADD COLUMN " + name + " " + def);
                    r.changes.add("added " + table + "." + name);
                } else {
                    String want = normalize(typeOf(def));
                    if (!want.equals(normalize(liveType)) && widenable(want)) {
                        String modify = def.replaceAll("(?i)\\s+UNIQUE\\b", "").replaceAll("(?i)\\s+PRIMARY KEY\\b", "");
                        exec(con, "ALTER TABLE " + table + " MODIFY COLUMN " + name + " " + modify);
                        r.changes.add("changed " + table + "." + name + " " + liveType + " -> " + want);
                    }
                }
            }
        }
        exec(con, "INSERT INTO settings (setting_key, setting_value) VALUES ('schema_version', '" + SCHEMA_VERSION
                + "') ON DUPLICATE KEY UPDATE setting_value = '" + SCHEMA_VERSION + "'");
        return r;
    }

    public static Result migrate(Connection con, Path schemaFile) throws SQLException, IOException {
        return migrate(con, Database.forThisDatabase(Files.readString(schemaFile, StandardCharsets.UTF_8)));
    }

    private static Map<String, String> liveColumns(Connection con, String db, String table) throws SQLException {
        Map<String, String> m = new HashMap<>();
        for (Object[] row : Jdbc.list(con, "SELECT column_name, column_type FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = ?", rs -> new Object[]{rs.getString(1), rs.getString(2)}, db, table)) {
            m.put(((String) row[0]).toLowerCase(Locale.ROOT), (String) row[1]);
        }
        return m;
    }

    /** Splits a CREATE TABLE body on commas that are outside parentheses and quotes. */
    static List<String> splitTopLevel(String body) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean quoted = false;
        StringBuilder cur = new StringBuilder();
        for (char ch : body.toCharArray()) {
            if (ch == '\'') {
                quoted = !quoted;
            } else if (!quoted && ch == '(') {
                depth++;
            } else if (!quoted && ch == ')') {
                depth--;
            } else if (!quoted && depth == 0 && ch == ',') {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(ch);
        }
        if (cur.toString().trim().length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    private static String stripComment(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length() - 1; i++) {
            char ch = line.charAt(i);
            if (ch == '\'') {
                quoted = !quoted;
            } else if (!quoted && ch == '-' && line.charAt(i + 1) == '-') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    /** "ENUM('A','B') NOT NULL" -> "ENUM('A','B')", "DECIMAL(14,2) NOT NULL" -> "DECIMAL(14,2)". */
    static String typeOf(String def) {
        int depth = 0;
        for (int i = 0; i < def.length(); i++) {
            char ch = def.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            } else if (Character.isWhitespace(ch) && depth == 0) {
                return def.substring(0, i);
            }
        }
        return def;
    }

    static String normalize(String type) {
        String t = type.toLowerCase(Locale.ROOT).replace(" ", "");
        return t.replaceAll("^(int|bigint|smallint)\\(\\d+\\)", "$1");
    }

    private static boolean widenable(String type) {
        return type.startsWith("varchar") || type.startsWith("char") || type.startsWith("enum")
                || type.startsWith("decimal") || type.startsWith("text");
    }

    private static void exec(Connection con, String sql) throws SQLException {
        try (Statement st = con.createStatement()) {
            st.execute(sql);
        }
    }
}
