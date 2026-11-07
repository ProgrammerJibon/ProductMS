package com.productms.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A cashier shift with opening and closing cash balance. */
public class Shift {
    public int shiftId;
    public int userId;
    public String userName;
    public LocalDateTime openedAt;
    public LocalDateTime closedAt;
    public BigDecimal openingCash = BigDecimal.ZERO;
    public BigDecimal expectedCash;
    public BigDecimal countedCash;
    public BigDecimal difference;
    public String status = "OPEN";
    public String note;

    public boolean isOpen() {
        return "OPEN".equals(status);
    }
}
