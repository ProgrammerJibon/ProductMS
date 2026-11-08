package com.productms.dao;

import com.productms.db.Jdbc;
import com.productms.model.User;
import com.productms.service.AuditService;
import com.productms.util.AppException;
import com.productms.util.PasswordUtil;
import com.productms.util.Settings;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Users: bcrypt passwords (legacy SHA-256 upgraded on login), persistent time-based lockout
 * after repeated failures, forced password change, and per-user selling permissions.
 */
public class UserDAO {

    private static User map(ResultSet rs) throws SQLException {
        User u = new User();
        u.userId = rs.getInt("user_id");
        u.username = rs.getString("username");
        u.fullName = rs.getString("full_name");
        u.role = rs.getString("role");
        u.active = rs.getBoolean("active");
        u.mustChangePassword = rs.getBoolean("must_change_password");
        u.canOverridePrice = rs.getBoolean("can_override_price");
        u.canSellOnCredit = rs.getBoolean("can_sell_on_credit");
        u.maxDiscountPercent = rs.getBigDecimal("max_discount_percent");
        u.maxSaleAmount = rs.getBigDecimal("max_sale_amount");
        u.lockedUntil = Jdbc.time(rs, "locked_until");
        u.lastLogin = Jdbc.time(rs, "last_login");
        u.failedAttempts = rs.getInt("failed_attempts");
        return u;
    }

    /** Outcome of a sign-in attempt. */
    public static class LoginResult {
        public User user;
        public String error;
    }

    /**
     * Checks credentials. Wrong passwords count towards a lockout that is stored in the
     * database, so restarting the application does not reset it.
     */
    public LoginResult login(String username, String password) throws SQLException {
        LoginResult r = new LoginResult();
        Object[] row = Jdbc.one("SELECT user_id, password_hash FROM users WHERE username = ?",
                rs -> new Object[]{rs.getInt(1), rs.getString(2)}, username);
        User u = row == null ? null : findById((Integer) row[0]);
        if (u != null && u.lockedUntil != null && u.lockedUntil.isAfter(LocalDateTime.now())) {
            long min = Math.max(1, Duration.between(LocalDateTime.now(), u.lockedUntil).toMinutes() + 1);
            r.error = "Account locked after too many failed attempts. Try again in " + min + " minute(s), "
                    + "or ask an admin to unlock it.";
            return r;
        }
        if (u == null || !PasswordUtil.verify(password, (String) row[1])) {
            if (u != null) {
                int max = Math.max(3, Settings.getInt("lockout_attempts", 5));
                int attempts = u.failedAttempts + 1;
                if (attempts >= max) {
                    Jdbc.update("UPDATE users SET failed_attempts = 0, locked_until = NOW() + INTERVAL ? MINUTE WHERE user_id = ?",
                            Settings.getInt("lockout_minutes", 15), u.userId);
                    AuditService.log("LOCKED", "USER", u.userId, "Locked after " + attempts + " failed sign-in attempts", null);
                    r.error = "Too many failed attempts. The account is locked for "
                            + Settings.getInt("lockout_minutes", 15) + " minutes.";
                } else {
                    Jdbc.update("UPDATE users SET failed_attempts = ? WHERE user_id = ?", attempts, u.userId);
                    r.error = "Invalid username or password (" + (max - attempts) + " attempt(s) left before lockout).";
                }
            } else {
                r.error = "Invalid username or password.";
            }
            return r;
        }
        if (!u.active) {
            r.error = "This account is deactivated.";
            return r;
        }
        if (PasswordUtil.needsRehash((String) row[1])) {
            Jdbc.update("UPDATE users SET password_hash = ? WHERE user_id = ?", PasswordUtil.hash(password), u.userId);
        }
        Jdbc.update("UPDATE users SET failed_attempts = 0, locked_until = NULL, last_login = NOW() WHERE user_id = ?", u.userId);
        r.user = u;
        return r;
    }

    /** Verifies a manager / admin for an approval prompt (respects lockout). Returns the user or null. */
    public User verifyManager(String username, String password) throws SQLException {
        LoginResult r = login(username, password);
        if (r.user != null && r.user.isManager()) {
            return r.user;
        }
        if (r.user == null && r.error != null && r.error.startsWith("Account locked")) {
            throw new AppException(r.error);
        }
        return null;
    }

    public User findById(int id) throws SQLException {
        return Jdbc.one("SELECT * FROM users WHERE user_id = ?", UserDAO::map, id);
    }

    public List<User> findAll() throws SQLException {
        return Jdbc.list("SELECT * FROM users ORDER BY username", UserDAO::map);
    }

    public void save(User u, String newPassword) throws SQLException {
        if (u.username == null || u.username.isBlank() || u.fullName == null || u.fullName.isBlank()) {
            throw new AppException("Username and full name are required.");
        }
        if (!u.username.matches("[A-Za-z0-9._-]{3,50}")) {
            throw new AppException("Username: 3-50 letters, digits, dot, dash or underscore.");
        }
        if (Jdbc.count("SELECT COUNT(*) FROM users WHERE username = ? AND user_id <> ?", u.username, u.userId) > 0) {
            throw new AppException("Username \"" + u.username + "\" is already taken.");
        }
        if (u.userId == 0) {
            String err = PasswordUtil.checkStrength(newPassword, u.username);
            if (err != null) {
                throw new AppException(err);
            }
            u.userId = Jdbc.insert("INSERT INTO users (username, password_hash, full_name, role, active, must_change_password, "
                    + "can_override_price, can_sell_on_credit, max_discount_percent, max_sale_amount, password_changed_at) "
                    + "VALUES (?,?,?,?,?,1,?,?,?,?,NOW())",
                    u.username, PasswordUtil.hash(newPassword), u.fullName, u.role, u.active, u.canOverridePrice,
                    u.canSellOnCredit, u.maxDiscountPercent, u.maxSaleAmount);
            AuditService.log("CREATE", "USER", u.userId, u.username + " (" + u.role + ")", null);
        } else {
            Jdbc.update("UPDATE users SET username=?, full_name=?, role=?, active=?, can_override_price=?, can_sell_on_credit=?, "
                    + "max_discount_percent=?, max_sale_amount=? WHERE user_id=?",
                    u.username, u.fullName, u.role, u.active, u.canOverridePrice, u.canSellOnCredit, u.maxDiscountPercent,
                    u.maxSaleAmount, u.userId);
            AuditService.log("UPDATE", "USER", u.userId, u.username + " role=" + u.role + " active=" + u.active
                    + " overridePrice=" + u.canOverridePrice + " credit=" + u.canSellOnCredit
                    + " maxDisc=" + u.maxDiscountPercent + " maxSale=" + u.maxSaleAmount, null);
            if (newPassword != null && !newPassword.isEmpty()) {
                resetPassword(u.userId, newPassword);
            }
        }
    }

    /** Admin reset: the user must choose a new password at next sign-in. */
    public void resetPassword(int userId, String newPassword) throws SQLException {
        String err = PasswordUtil.checkStrength(newPassword, null);
        if (err != null) {
            throw new AppException(err);
        }
        Jdbc.update("UPDATE users SET password_hash=?, must_change_password=1, failed_attempts=0, locked_until=NULL, "
                + "password_changed_at=NOW() WHERE user_id=?", PasswordUtil.hash(newPassword), userId);
        AuditService.log("RESET_PASSWORD", "USER", userId, "Password reset by admin", null);
    }

    /** The user changes their own password. */
    public void changeOwnPassword(User u, String current, String newPassword) throws SQLException {
        String stored = Jdbc.one("SELECT password_hash FROM users WHERE user_id=?", rs -> rs.getString(1), u.userId);
        if (!PasswordUtil.verify(current, stored)) {
            throw new AppException("Current password is wrong.");
        }
        if (current.equals(newPassword)) {
            throw new AppException("The new password must be different from the current one.");
        }
        String err = PasswordUtil.checkStrength(newPassword, u.username);
        if (err != null) {
            throw new AppException(err);
        }
        Jdbc.update("UPDATE users SET password_hash=?, must_change_password=0, password_changed_at=NOW() WHERE user_id=?",
                PasswordUtil.hash(newPassword), u.userId);
        u.mustChangePassword = false;
        AuditService.log("CHANGE_PASSWORD", "USER", u.userId, "Password changed by user", null);
    }

    public void unlock(int userId) throws SQLException {
        Jdbc.update("UPDATE users SET failed_attempts=0, locked_until=NULL WHERE user_id=?", userId);
        AuditService.log("UNLOCK", "USER", userId, "Account unlocked", null);
    }

    public int countActiveAdmins() throws SQLException {
        return Jdbc.count("SELECT COUNT(*) FROM users WHERE role='ADMIN' AND active=1");
    }

    /** True while any active account still uses a demo password or is flagged to change it. */
    public boolean defaultCredentialsInUse() throws SQLException {
        for (Object[] r : Jdbc.list("SELECT username, password_hash FROM users WHERE active=1",
                rs -> new Object[]{rs.getString(1), rs.getString(2)})) {
            for (String demo : new String[]{"admin123", "cashier123"}) {
                if (PasswordUtil.verify(demo, (String) r[1])) {
                    return true;
                }
            }
        }
        return false;
    }
}
