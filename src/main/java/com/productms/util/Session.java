package com.productms.util;

import com.productms.model.Shift;
import com.productms.model.User;

/**
 * The logged-in user and their open cash shift.
 */
public final class Session {

    private static User user;
    private static Shift shift;

    private Session() {
    }

    public static User user() {
        return user;
    }

    public static int userId() {
        return user == null ? 0 : user.userId;
    }

    public static void setUser(User u) {
        user = u;
        shift = null;
    }

    public static Shift shift() {
        return shift;
    }

    public static Integer shiftId() {
        return shift == null ? null : shift.shiftId;
    }

    public static void setShift(Shift s) {
        shift = s;
    }

    public static boolean isAdmin() {
        return user != null && "ADMIN".equals(user.role);
    }

    /** Admins and managers can manage products, stock, reports and cancel sales. */
    public static boolean isManager() {
        return user != null && ("ADMIN".equals(user.role) || "MANAGER".equals(user.role));
    }

    public static void clear() {
        user = null;
        shift = null;
    }
}
