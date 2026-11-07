package com.productms.model;

import java.math.BigDecimal;

/** Customer; the walk-in customer is used for anonymous sales. */
public class Customer {
    public int customerId;
    public String name;
    public String phone;
    public String email;
    public String address;
    public String customerType = "RETAIL";
    public boolean walkIn;
    public boolean active = true;
    public BigDecimal due = BigDecimal.ZERO;
    public BigDecimal totalPurchases = BigDecimal.ZERO;

    public boolean isWholesale() {
        return "WHOLESALE".equals(customerType);
    }

    @Override
    public String toString() {
        return phone == null || phone.isBlank() ? name : name + " - " + phone;
    }
}
