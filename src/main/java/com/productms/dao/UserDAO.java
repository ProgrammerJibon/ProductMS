package com.productms.dao;

import com.productms.db.Jdbc;
import com.productms.model.User;
import com.productms.util.AppException;
import com.productms.util.PasswordUtil;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** Data access for users. */
public class UserDAO {

    private static User map(ResultSet rs) throws SQLException {
        User u = new User();
        u.userId = rs.getInt("user_id");
        u.username = rs.getString("username");
        u.fullName = rs.getString("full_name");
        u.role = rs.getString("role");
        u.active = rs.getBoolean("active");
        return u;
    }

    /** Returns the user when username/password are valid and the account is active, otherwise null. */
    public User authenticate(String username, String password) throws SQLException {
        return Jdbc.one("SELECT * FROM users WHERE username = ? AND password_hash = ? AND active = 1",
                UserDAO::map, username, PasswordUtil.sha256(password));
    }

    public List<User> findAll() throws SQLException {
        return Jdbc.list("SELECT * FROM users ORDER BY username", UserDAO::map);
    }

    public void save(User u, String newPassword) throws SQLException {
        if (u.username == null || u.username.isBlank() || u.fullName == null || u.fullName.isBlank()) {
            throw new AppException("Username and full name are required.");
        }
        int dup = Jdbc.count("SELECT COUNT(*) FROM users WHERE username = ? AND user_id <> ?", u.username, u.userId);
        if (dup > 0) {
            throw new AppException("Username \"" + u.username + "\" is already taken.");
        }
        if (u.userId == 0) {
            if (newPassword == null || newPassword.length() < 4) {
                throw new AppException("Password must be at least 4 characters.");
            }
            u.userId = Jdbc.insert("INSERT INTO users (username, password_hash, full_name, role, active) VALUES (?,?,?,?,?)",
                    u.username, PasswordUtil.sha256(newPassword), u.fullName, u.role, u.active);
        } else {
            Jdbc.update("UPDATE users SET username=?, full_name=?, role=?, active=? WHERE user_id=?",
                    u.username, u.fullName, u.role, u.active, u.userId);
            if (newPassword != null && !newPassword.isEmpty()) {
                changePassword(u.userId, newPassword);
            }
        }
    }

    public void changePassword(int userId, String newPassword) throws SQLException {
        if (newPassword == null || newPassword.length() < 4) {
            throw new AppException("Password must be at least 4 characters.");
        }
        Jdbc.update("UPDATE users SET password_hash=? WHERE user_id=?", PasswordUtil.sha256(newPassword), userId);
    }

    public int countActiveAdmins() throws SQLException {
        return Jdbc.count("SELECT COUNT(*) FROM users WHERE role='ADMIN' AND active=1");
    }
}
