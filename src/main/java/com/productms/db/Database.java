package com.productms.db;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * JDBC connections, transactions and safe database setup.
 * <p>
 * Defaults: localhost:3306, user root, empty password, database ProductMSJibon. Override them
 * with a {@code db.properties} file in the working directory (keys: host, port, user, password,
 * database). Settings → Database can create a restricted MySQL account and write that file.
 * <p>
 * Setup never drops data: {@link #ensureSchema()} creates missing tables / columns only, and
 * demo data is loaded only into a brand-new, empty database.
 */
public final class Database {

    public static final String DEFAULT_DB = "ProductMSJibon";
    private static final Path CONFIG_FILE = Paths.get("db.properties");
    private static final Properties CONFIG = new Properties();

    static {
        CONFIG.setProperty("host", "localhost");
        CONFIG.setProperty("port", "3306");
        CONFIG.setProperty("user", "root");
        CONFIG.setProperty("password", "");
        CONFIG.setProperty("database", DEFAULT_DB);
        if (Files.exists(CONFIG_FILE)) {
            try (InputStream in = Files.newInputStream(CONFIG_FILE)) {
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

    public static String user() {
        return CONFIG.getProperty("user");
    }

    public static String host() {
        return CONFIG.getProperty("host") + ":" + CONFIG.getProperty("port");
    }

    /** True when connected as root with an empty password (unsafe outside a dev machine). */
    public static boolean isInsecureRoot() {
        return "root".equalsIgnoreCase(user()) && CONFIG.getProperty("password", "").isEmpty();
    }

    private static String serverUrl() {
        return "jdbc:mysql://" + CONFIG.getProperty("host") + ":" + CONFIG.getProperty("port") + "/";
    }

    private static String options() {
        return "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Dhaka"
                + "&useUnicode=true&characterEncoding=UTF-8&connectTimeout=5000&socketTimeout=60000";
    }

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(serverUrl() + databaseName() + options(), user(), CONFIG.getProperty("password"));
    }

    private static Connection serverConnection() throws SQLException {
        return DriverManager.getConnection(serverUrl() + options(), user(), CONFIG.getProperty("password"));
    }

    /** Runs work inside a transaction; commits on success, rolls back on any exception. */
    public static <T> T inTransaction(TxWork<T> work) throws SQLException {
        try (Connection con = getConnection()) {
            con.setAutoCommit(false);
            con.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
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
     * Returns null when the database is reachable and initialised, "NO_DATABASE" when MySQL is
     * running but the database does not exist yet, otherwise a readable error.
     */
    public static String checkConnection() {
        try (Connection con = getConnection()) {
            return con.isValid(3) ? null : "The database connection is not valid.";
        } catch (SQLException e) {
            if (e.getErrorCode() == 1049) {
                return "NO_DATABASE";
            }
            return e.getMessage();
        }
    }

    /** Quick liveness probe used by the offline queue. */
    public static boolean isReachable() {
        try (Connection con = getConnection()) {
            return con.isValid(3);
        } catch (SQLException e) {
            return false;
        }
    }

    private static Path schemaFile() {
        return Paths.get("db", "schema.sql");
    }

    /** Replaces the default database name in a script with the configured one. */
    static String forThisDatabase(String script) {
        return script.replace(DEFAULT_DB, databaseName());
    }

    /**
     * Brings the database up to date (non-destructive). Creates the database when missing and
     * loads demo data only when it has no users at all. Returns what changed.
     */
    public static Migrations.Result ensureSchema() throws SQLException, IOException {
        Path schema = schemaFile();
        if (!Files.exists(schema)) {
            throw new IOException("db/schema.sql not found in " + Paths.get("").toAbsolutePath()
                    + ". Run the application from the project folder.");
        }
        String script = forThisDatabase(Files.readString(schema, StandardCharsets.UTF_8));
        if ("NO_DATABASE".equals(checkConnection())) {
            try (Connection server = serverConnection(); Statement st = server.createStatement()) {
                st.execute("CREATE DATABASE IF NOT EXISTS `" + databaseName() + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            }
        }
        Migrations.Result result;
        try (Connection con = getConnection()) {
            result = Migrations.migrate(con, script);
            Integer users = Jdbc.one(con, "SELECT COUNT(*) FROM users", rs -> rs.getInt(1));
            if (users != null && users == 0) {
                Path seed = Paths.get("db", "seed.sql");
                if (Files.exists(seed)) {
                    try (Statement st = con.createStatement()) {
                        runScript(st, forThisDatabase(Files.readString(seed, StandardCharsets.UTF_8)));
                    }
                    result.changes.add("loaded demo data from db/seed.sql");
                }
            }
        }
        return result;
    }

    /** Splits a script on lines ending with ';' (comments and blank lines skipped). */
    static void runScript(Statement st, String script) throws SQLException {
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

    /**
     * Creates a MySQL account limited to this database (no DROP, no other databases, no admin
     * rights) and writes db.properties so the next start uses it. Requires a privileged connection.
     */
    public static String createRestrictedAccount(String username) throws SQLException, IOException {
        String password = randomPassword();
        try (Connection con = serverConnection(); Statement st = con.createStatement()) {
            String user = "'" + username.replace("'", "") + "'@'localhost'";
            st.execute("CREATE USER IF NOT EXISTS " + user + " IDENTIFIED BY '" + password + "'");
            st.execute("ALTER USER " + user + " IDENTIFIED BY '" + password + "'");
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES, LOCK TABLES, "
                    + "CREATE TEMPORARY TABLES ON `" + databaseName() + "`.* TO " + user);
            st.execute("FLUSH PRIVILEGES");
        }
        Properties p = new Properties();
        p.setProperty("host", CONFIG.getProperty("host"));
        p.setProperty("port", CONFIG.getProperty("port"));
        p.setProperty("database", databaseName());
        p.setProperty("user", username);
        p.setProperty("password", password);
        try (OutputStream out = Files.newOutputStream(CONFIG_FILE)) {
            p.store(out, "ProductMS database account (restricted). Keep this file private.");
        }
        return CONFIG_FILE.toAbsolutePath().toString();
    }

    private static String randomPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append(chars.charAt(r.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
