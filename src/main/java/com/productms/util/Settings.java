package com.productms.util;

import com.productms.db.Database;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shop settings cached from the settings table.
 */
public final class Settings {

    private static final Map<String, String> VALUES = new LinkedHashMap<>();

    private Settings() {
    }

    public static void load() throws SQLException {
        try (Connection con = Database.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT setting_key, setting_value FROM settings");
             ResultSet rs = ps.executeQuery()) {
            VALUES.clear();
            while (rs.next()) {
                VALUES.put(rs.getString(1), rs.getString(2));
            }
        }
    }

    public static void save(Map<String, String> changes) throws SQLException {
        Database.inTransaction(con -> {
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO settings (setting_key, setting_value) VALUES (?, ?) "
                    + "ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)")) {
                for (Map.Entry<String, String> e : changes.entrySet()) {
                    ps.setString(1, e.getKey());
                    ps.setString(2, e.getValue() == null ? "" : e.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
        VALUES.putAll(changes);
    }

    public static String get(String key, String def) {
        String v = VALUES.get(key);
        return v == null || v.isBlank() ? def : v;
    }

    public static int getInt(String key, int def) {
        try {
            return Integer.parseInt(get(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static boolean getBool(String key) {
        return "1".equals(get(key, "0")) || "true".equalsIgnoreCase(get(key, "0"));
    }

    public static String currency() {
        return get("currency", "Tk");
    }

    public static String shopName() {
        return get("shop_name", "My Shop");
    }

    public static boolean pricesIncludeVat() {
        return getBool("price_includes_vat");
    }

    public static BigDecimal bd(String key, String def) {
        try {
            return new BigDecimal(get(key, def));
        } catch (NumberFormatException e) {
            return new BigDecimal(def);
        }
    }
}
