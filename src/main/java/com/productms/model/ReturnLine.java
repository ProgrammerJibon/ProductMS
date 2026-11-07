package com.productms.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** One line of a sales return request. */
public class ReturnLine {
    public int saleItemId;
    public BigDecimal quantity;
    public String condition = "GOOD";
    public List<String> serials = new ArrayList<>();

    public ReturnLine(int saleItemId, BigDecimal quantity, String condition) {
        this.saleItemId = saleItemId;
        this.quantity = quantity;
        this.condition = condition;
    }
}
