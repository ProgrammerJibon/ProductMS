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
    /** NULL = unlimited credit, 0 = no credit allowed. */
    public BigDecimal creditLimit;
    public int paymentTermsDays = 30;
    public BigDecimal storeCredit = BigDecimal.ZERO;
    public String notes;
    /** Due invoices older than the payment terms. */
    public int overdueInvoices;
    public BigDecimal overdueAmount = BigDecimal.ZERO;

    public boolean isWholesale() {
        return "WHOLESALE".equals(customerType);
    }

    /** Credit still available (null = unlimited). */
    public BigDecimal creditAvailable() {
        return creditLimit == null ? null : creditLimit.subtract(due);
    }

    @Override
    public String toString() {
        return phone == null || phone.isBlank() ? name : name + " - " + phone;
    }
}
