package com.productms.service;

import com.productms.db.Database;
import com.productms.db.Jdbc;
import com.productms.util.AppException;
import com.productms.util.Settings;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Pure-JDBC database backup and restore (no mysqldump needed).
 * <p>
 * A backup is one SQL file: a header, DROP/CREATE + INSERT per table, and a final
 * {@code -- CHECKSUM sha256=...} line over everything before it. Restore refuses files whose
 * checksum or header is wrong, takes a safety backup first, restores in one session, and then
 * validates row counts and the stock ledger.
 */
public class BackupService {

    private static final String HEADER = "-- ProductMS backup";
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public static Path folder() {
        return Paths.get(Settings.get("backup_folder", "backups"));
    }

    // ------------------------------------------------------------------ backup

    public Path backup(String label) throws SQLException, IOException {
        Files.createDirectories(folder());
        Path file = folder().resolve("backup_" + Database.databaseName() + "_" + LocalDateTime.now().format(FILE_TS)
                + (label == null ? "" : "_" + label) + ".sql");
        MessageDigest sha = sha256();
        try (Connection con = Database.getConnection(); BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            List<String> tables = tables(con);
            StringBuilder head = new StringBuilder();
            head.append(HEADER).append(" db=").append(Database.databaseName()).append(" created=").append(LocalDateTime.now())
                    .append(" tables=").append(tables.size()).append('\n');
            head.append("SET FOREIGN_KEY_CHECKS=0;\n");
            write(out, sha, head.toString());
            for (String t : tables) {
                long rows = 0;
                String create = Jdbc.one(con, "SHOW CREATE TABLE `" + t + "`", rs -> rs.getString(2));
                write(out, sha, "DROP TABLE IF EXISTS `" + t + "`;\n" + create.replace("\n", " ") + ";\n");
                try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM `" + t + "`")) {
                    ResultSetMetaData md = rs.getMetaData();
                    StringBuilder batch = new StringBuilder();
                    int inBatch = 0;
                    while (rs.next()) {
                        batch.append(inBatch == 0 ? "INSERT INTO `" + t + "` VALUES " : ",");
                        batch.append('(');
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            if (i > 1) {
                                batch.append(',');
                            }
                            batch.append(literal(rs, i, md.getColumnType(i)));
                        }
                        batch.append(')');
                        rows++;
                        if (++inBatch == 200) {
                            write(out, sha, batch.append(";\n").toString());
                            batch.setLength(0);
                            inBatch = 0;
                        }
                    }
                    if (inBatch > 0) {
                        write(out, sha, batch.append(";\n").toString());
                    }
                }
                write(out, sha, "-- ROWS " + t + "=" + rows + "\n");
            }
            write(out, sha, "SET FOREIGN_KEY_CHECKS=1;\n");
            out.write("-- CHECKSUM sha256=" + hex(sha.digest()) + "\n");
        }
        prune();
        return file;
    }

    private static String literal(ResultSet rs, int i, int type) throws SQLException {
        Object o = rs.getObject(i);
        if (o == null) {
            return "NULL";
        }
        switch (type) {
            case Types.INTEGER: case Types.BIGINT: case Types.SMALLINT: case Types.TINYINT: case Types.DECIMAL:
            case Types.NUMERIC: case Types.DOUBLE: case Types.FLOAT: case Types.REAL:
                return rs.getString(i);
            case Types.BIT: case Types.BOOLEAN:
                return rs.getBoolean(i) ? "1" : "0";
            default:
                String s = rs.getString(i);
                StringBuilder sb = new StringBuilder("'");
                for (char c : s.toCharArray()) {
                    switch (c) {
                        case '\'': sb.append("\\'"); break;
                        case '\\': sb.append("\\\\"); break;
                        case '\n': sb.append("\\n"); break;
                        case '\r': sb.append("\\r"); break;
                        case '\0': sb.append("\\0"); break;
                        default: sb.append(c);
                    }
                }
                return sb.append('\'').toString();
        }
    }

    private static List<String> tables(Connection con) throws SQLException {
        return Jdbc.list(con, "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() "
                + "AND table_type = 'BASE TABLE' ORDER BY table_name", rs -> rs.getString(1));
    }

    private static void write(BufferedWriter out, MessageDigest sha, String s) throws IOException {
        out.write(s);
        sha.update(s.getBytes(StandardCharsets.UTF_8));
    }

    /** Keeps only the newest N automatic / manual backups. */
    private void prune() throws IOException {
        int keep = Math.max(1, Settings.getInt("backup_keep", 14));
        List<Path> files = list();
        for (int i = keep; i < files.size(); i++) {
            Files.deleteIfExists(files.get(i));
        }
    }

    /** Backups, newest first. */
    public List<Path> list() throws IOException {
        if (!Files.isDirectory(folder())) {
            return new ArrayList<>();
        }
        try (Stream<Path> s = Files.list(folder())) {
            return s.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparing((Path p) -> p.toFile().lastModified()).reversed())
                    .collect(Collectors.toList());
        }
    }

    /** Daily automatic backup (called at start-up). Returns the file or null when not needed. */
    public Path autoBackupIfDue() throws SQLException, IOException {
        if (!Settings.getBool("auto_backup_enabled")) {
            return null;
        }
        List<Path> files = list();
        long day = 24L * 60 * 60 * 1000;
        if (!files.isEmpty() && System.currentTimeMillis() - files.get(0).toFile().lastModified() < day) {
            return null;
        }
        return backup("auto");
    }

    // ------------------------------------------------------------------ validate / restore

    /** Validation result of a backup file. */
    public static class Check {
        public boolean valid;
        public String message;
        public int tables;
    }

    public Check validate(Path file) throws IOException {
        Check c = new Check();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).startsWith(HEADER)) {
            c.message = "Not a ProductMS backup file.";
            return c;
        }
        String last = lines.get(lines.size() - 1);
        if (!last.startsWith("-- CHECKSUM sha256=")) {
            c.message = "The backup is incomplete (no checksum line). It may have been cut off.";
            return c;
        }
        MessageDigest sha = sha256();
        for (int i = 0; i < lines.size() - 1; i++) {
            sha.update((lines.get(i) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        if (!hex(sha.digest()).equals(last.substring("-- CHECKSUM sha256=".length()).trim())) {
            c.message = "Checksum mismatch: the file was changed or corrupted.";
            return c;
        }
        c.tables = (int) lines.stream().filter(l -> l.startsWith("DROP TABLE IF EXISTS")).count();
        c.valid = true;
        c.message = "Valid backup: " + c.tables + " tables. " + lines.get(0).substring(HEADER.length()).trim();
        return c;
    }

    /** Restores a validated backup. A safety backup of the current data is taken first. */
    public String restore(Path file) throws SQLException, IOException {
        Check c = validate(file);
        if (!c.valid) {
            throw new AppException("Restore refused: " + c.message);
        }
        Path safety = backup("before_restore");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        java.util.Map<String, Long> expected = new java.util.LinkedHashMap<>();
        try (Connection con = Database.getConnection(); Statement st = con.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS=0");
            for (String line : lines) {
                if (line.startsWith("-- ROWS ")) {
                    String[] kv = line.substring(8).split("=");
                    expected.put(kv[0], Long.parseLong(kv[1].trim()));
                } else if (!line.startsWith("--") && !line.isBlank()) {
                    st.execute(line.endsWith(";") ? line.substring(0, line.length() - 1) : line);
                }
            }
            st.execute("SET FOREIGN_KEY_CHECKS=1");
            StringBuilder problems = new StringBuilder();
            for (java.util.Map.Entry<String, Long> e : expected.entrySet()) {
                Long n = Jdbc.one(con, "SELECT COUNT(*) FROM `" + e.getKey() + "`", rs -> rs.getLong(1));
                if (n == null || !n.equals(e.getValue())) {
                    problems.append("\n• ").append(e.getKey()).append(": expected ").append(e.getValue()).append(", found ").append(n);
                }
            }
            Integer ledger = Jdbc.one(con, "SELECT COUNT(*) FROM product_variants v WHERE v.stock_qty <> "
                    + "(SELECT COALESCE(SUM(quantity),0) FROM stock_movements m WHERE m.variant_id = v.variant_id)", rs -> rs.getInt(1));
            if (ledger != null && ledger > 0) {
                problems.append("\n• ").append(ledger).append(" product(s) whose stock does not match the stock ledger");
            }
            return problems.length() == 0
                    ? "Restore completed and verified (" + expected.size() + " tables, row counts match, stock ledger consistent).\n"
                    + "Safety backup of the previous data: " + safety.getFileName()
                    : "Restore finished with warnings:" + problems + "\nSafety backup of the previous data: " + safety.getFileName();
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}
