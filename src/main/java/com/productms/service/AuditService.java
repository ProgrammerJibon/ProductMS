package com.productms.service;

import com.productms.db.Jdbc;
import com.productms.util.Session;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;

/**
 * Audit trail: who changed prices, stock, discounts, invoices, settings and users, and when.
 * Inside a transaction pass the connection so the entry commits (or rolls back) with the change.
 */
public final class AuditService {

    private AuditService() {
    }

    public static void log(Connection con, String action, String entity, Object entityId, String details, String approvedBy)
            throws SQLException {
        Jdbc.insert(con, "INSERT INTO audit_log (user_id, username, action, entity, entity_id, details, approved_by) "
                + "VALUES (?,?,?,?,?,?,?)", Session.user() == null ? null : Session.userId(),
                Session.user() == null ? null : Session.user().username, action, entity,
                entityId == null ? null : String.valueOf(entityId), details, approvedBy);
    }

    public static void log(Connection con, String action, String entity, Object entityId, String details) throws SQLException {
        log(con, action, entity, entityId, details, null);
    }

    /** Logs on its own connection; never throws (auditing must not break the user's action). */
    public static void log(String action, String entity, Object entityId, String details, String approvedBy) {
        try (Connection con = com.productms.db.Database.getConnection()) {
            log(con, action, entity, entityId, details, approvedBy);
        } catch (SQLException e) {
            System.err.println("Audit log failed: " + e.getMessage());
        }
    }

    public static void log(String action, String entity, Object entityId, String details) {
        log(action, entity, entityId, details, null);
    }

    public static Jdbc.TableData search(LocalDate from, LocalDate to, String entity, String text) throws SQLException {
        String like = "%" + (text == null ? "" : text.trim()) + "%";
        return Jdbc.table("SELECT created_at AS `Time`, username AS `User`, action AS `Action`, entity AS `Entity`, "
                + "entity_id AS `ID`, details AS `Details`, approved_by AS `Approved by` FROM audit_log "
                + "WHERE DATE(created_at) BETWEEN ? AND ? AND (? = 'ALL' OR entity = ?) "
                + "AND (COALESCE(details,'') LIKE ? OR COALESCE(username,'') LIKE ? OR COALESCE(entity_id,'') LIKE ? OR action LIKE ?) "
                + "ORDER BY audit_id DESC LIMIT 3000", from, to, entity, entity, like, like, like, like);
    }
}
