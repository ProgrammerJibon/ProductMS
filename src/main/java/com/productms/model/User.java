package com.productms.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A system user (ADMIN, MANAGER or CASHIER) with per-user selling permissions. */
public class User {
    public int userId;
    public String username;
    public String fullName;
    public String role = "CASHIER";
    public boolean active = true;
    public boolean mustChangePassword;
    public boolean canOverridePrice;
    public boolean canSellOnCredit = true;
    /** Personal discount limit in percent; null = shop-wide limit. */
    public BigDecimal maxDiscountPercent;
    /** Largest invoice this user may complete without approval; null = no limit. */
    public BigDecimal maxSaleAmount;
    public LocalDateTime lockedUntil;
    public LocalDateTime lastLogin;
    public int failedAttempts;

    public boolean isManager() {
        return "ADMIN".equals(role) || "MANAGER".equals(role);
    }

    @Override
    public String toString() {
        return fullName + " (" + username + ")";
    }
}
