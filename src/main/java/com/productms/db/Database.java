package com.productms.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Central place for JDBC connections and transactions.
 * <p>
 * Defaults: localhost:3306, user root, empty password, database ProductMSEasirArafat.
 * Any of these can be overridden with a {@code db.properties} file in the working
 * directory (keys: host, port, user, password, database).
 */
public final class Database {

    private static final Properties CONFIG = new Properties();

    static {
        CONFIG.setProperty("host", "localhost");
        CONFIG.setProperty("port", "3306");
        CONFIG.setProperty("user", "root");
        CONFIG.setProperty("password", "");
        CONFIG.setProperty("database", "ProductMSEasirArafat");
        Path file = Paths.get("db.properties");
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                CONFIG.load(in);
            } catch (IOException ignored) {
                // keep defaults
            }
        }
    }

    private Database() {
    }

    public static String databaseName() {
        return CONFIG.getProperty("database");
    }

    private static String serverUrl() {
        return "jdbc:mysql://" + CONFIG.getProperty("host") + ":" + CONFIG.getProperty("port") + "/";
    }

    private static String options() {
        return "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Dhaka"
                + "&useUnicode=true&characterEncoding=UTF-8&connectTimeout=5000";
    }

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(serverUrl() + databaseName() + options(),
                CONFIG.getProperty("user"), CONFIG.getProperty("password"));
    }

    /** Runs work inside a transaction; commits on success, rolls back on any exception. */
    public static <T> T inTransaction(TxWork<T> work) throws SQLException {
        try (Connection con = getConnection()) {
            con.setAutoCommit(false);
            try {
                T result = work.run(con);
                con.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            }
        }
    }

    @FunctionalInterface
    public interface TxWork<T> {
        T run(Connection con) throws SQLException;
    }

    /**
     * Checks the server and database. Returns null when everything is fine,
     * "NO_DATABASE" when MySQL is running but the database is missing,
     * otherwise a readable error.
     */
    public static String checkConnection() {
        try (Connection con = getConnection()) {
            try (Statement st = con.createStatement()) {
                st.executeQuery("SELECT 1 FROM settings LIMIT 1").close();
            }
            return null;
        } catch (SQLException e) {
            if (e.getErrorCode() == 1049 || e.getErrorCode() == 1146) {
                return "NO_DATABASE";
            }
            return e.getMessage();
        }
    }

    /** Creates the database by running db/schema.sql then db/seed.sql. */
    public static void installFromScripts() throws SQLException, IOException {
        Path schema = Paths.get("db", "schema.sql");
        Path seed = Paths.get("db", "seed.sql");
        if (!Files.exists(schema) || !Files.exists(seed)) {
            throw new IOException("db/schema.sql or db/seed.sql not found in " + Paths.get("").toAbsolutePath());
        }
        try (Connection con = DriverManager.getConnection(serverUrl() + options(),
                CONFIG.getProperty("user"), CONFIG.getProperty("password"));
             Statement st = con.createStatement()) {
            runScript(st, Files.readString(schema, StandardCharsets.UTF_8));
            runScript(st, Files.readString(seed, StandardCharsets.UTF_8));
        }
    }

    private static void runScript(Statement st, String script) throws SQLException {
        StringBuilder current = new StringBuilder();
        for (String line : script.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--") || trimmed.isEmpty()) {
                continue;
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                String sql = current.toString().trim();
                st.execute(sql.substring(0, sql.length() - 1));
                current.setLength(0);
            }
        }
    }
}
