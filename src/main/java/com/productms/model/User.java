package com.productms.model;

/** A system user (ADMIN, MANAGER or CASHIER). */
public class User {
    public int userId;
    public String username;
    public String fullName;
    public String role = "CASHIER";
    public boolean active = true;

    @Override
    public String toString() {
        return fullName + " (" + username + ")";
    }
}
