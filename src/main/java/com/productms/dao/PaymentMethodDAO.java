package com.productms.dao;

import com.productms.db.Jdbc;
import com.productms.model.PaymentMethod;
import com.productms.service.AuditService;
import com.productms.util.AppException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Payment methods with reference validation rules. A small cache keeps labels available even
 * when the database is briefly unreachable (offline selling).
 */
public class PaymentMethodDAO {

    private static final Map<String, PaymentMethod> CACHE = new LinkedHashMap<>();

    private static PaymentMethod map(ResultSet rs) throws SQLException {
        PaymentMethod m = new PaymentMethod();
        m.code = rs.getString("code");
        m.name = rs.getString("name");
        m.kind = rs.getString("kind");
        m.requiresReference = rs.getBoolean("requires_reference");
        m.referenceRegex = rs.getString("reference_regex");
        m.referenceHint = rs.getString("reference_hint");
        m.color = rs.getString("color");
        m.sortOrder = rs.getInt("sort_order");
        m.active = rs.getBoolean("active");
        return m;
    }

    public static void reload() throws SQLException {
        List<PaymentMethod> all = Jdbc.list("SELECT * FROM payment_methods ORDER BY sort_order, name", PaymentMethodDAO::map);
        synchronized (CACHE) {
            CACHE.clear();
            for (PaymentMethod m : all) {
                CACHE.put(m.code, m);
            }
        }
    }

    public static PaymentMethod get(String code) {
        synchronized (CACHE) {
            if (CACHE.isEmpty()) {
                try {
                    reload();
                } catch (SQLException ignored) {
                    // fall through to defaults
                }
            }
            return CACHE.get(code);
        }
    }

    /** Active methods customers can pay with at the till (store credit excluded). */
    public static List<PaymentMethod> tenderMethods() {
        List<PaymentMethod> out = new ArrayList<>();
        synchronized (CACHE) {
            if (CACHE.isEmpty()) {
                try {
                    reload();
                } catch (SQLException ignored) {
                    // empty
                }
            }
            for (PaymentMethod m : CACHE.values()) {
                if (m.active && !"CREDIT".equals(m.kind)) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    public static String label(String code) {
        if (code == null) {
            return "";
        }
        if ("NONE".equals(code)) {
            return "None";
        }
        if ("ORIGINAL".equals(code)) {
            return "Original method(s)";
        }
        PaymentMethod m = get(code);
        return m == null ? code : m.name;
    }

    /** Checks a payment reference against the method's rule. Returns an error message or null. */
    public static String validateReference(String code, String reference) {
        PaymentMethod m = get(code);
        if (m == null) {
            return "Unknown payment method " + code + ".";
        }
        String ref = reference == null ? "" : reference.trim();
        if (!m.requiresReference) {
            return null;
        }
        if (ref.isEmpty()) {
            return m.name + " needs a reference" + (m.referenceHint == null ? "." : " (" + m.referenceHint + ").");
        }
        if (m.referenceRegex != null && !m.referenceRegex.isBlank() && !ref.toUpperCase().matches(m.referenceRegex)) {
            return m.name + " reference \"" + ref + "\" does not look right"
                    + (m.referenceHint == null ? "." : ". Expected: " + m.referenceHint + ".");
        }
        return null;
    }

    public List<PaymentMethod> findAll() throws SQLException {
        return Jdbc.list("SELECT * FROM payment_methods ORDER BY sort_order, name", PaymentMethodDAO::map);
    }

    public void save(PaymentMethod m, boolean isNew) throws SQLException {
        if (m.code == null || !m.code.matches("[A-Z0-9_]{2,20}")) {
            throw new AppException("Code: 2-20 capital letters, digits or underscore (e.g. UPAY).");
        }
        if (m.name == null || m.name.isBlank()) {
            throw new AppException("Name is required.");
        }
        if (m.referenceRegex != null && !m.referenceRegex.isBlank()) {
            try {
                Pattern.compile(m.referenceRegex);
            } catch (PatternSyntaxException e) {
                throw new AppException("The reference pattern is not a valid regular expression.");
            }
        }
        if (isNew) {
            if (Jdbc.count("SELECT COUNT(*) FROM payment_methods WHERE code=?", m.code) > 0) {
                throw new AppException("A method with code " + m.code + " already exists.");
            }
            Jdbc.insert("INSERT INTO payment_methods (code, name, kind, requires_reference, reference_regex, reference_hint, color, "
                    + "sort_order, active) VALUES (?,?,?,?,?,?,?,?,?)", m.code, m.name.trim(), m.kind, m.requiresReference,
                    blank(m.referenceRegex), blank(m.referenceHint), blank(m.color), m.sortOrder, m.active);
        } else {
            if (("CASH".equals(m.code) || "STORE_CREDIT".equals(m.code)) && !m.active) {
                throw new AppException(m.name + " is required by the system and cannot be deactivated.");
            }
            Jdbc.update("UPDATE payment_methods SET name=?, kind=?, requires_reference=?, reference_regex=?, reference_hint=?, color=?, "
                    + "sort_order=?, active=? WHERE code=?", m.name.trim(), m.kind, m.requiresReference, blank(m.referenceRegex),
                    blank(m.referenceHint), blank(m.color), m.sortOrder, m.active, m.code);
        }
        AuditService.log(isNew ? "CREATE" : "UPDATE", "PAYMENT_METHOD", m.code, m.name + " active=" + m.active, null);
        reload();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
