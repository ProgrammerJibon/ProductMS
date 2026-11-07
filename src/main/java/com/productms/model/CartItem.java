package com.productms.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A line in a POS cart. Input fields are variant, quantity, cashier discount and
 * serial numbers; the rest is filled in by {@link Cart#recalculate()}.
 */
public class CartItem {
    public Variant variant;
    public BigDecimal quantity = BigDecimal.ONE;
    /** Cashier discount on this line: "AMOUNT" or "PERCENT". */
    public String itemDiscountType = "AMOUNT";
    public BigDecimal itemDiscountValue = BigDecimal.ZERO;
    public List<String> serials = new ArrayList<>();

    // ---- calculated ----
    public BigDecimal unitPrice = BigDecimal.ZERO;
    public boolean wholesaleApplied;
    public BigDecimal gross = BigDecimal.ZERO;
    public BigDecimal productDiscount = BigDecimal.ZERO;
    public BigDecimal itemDiscount = BigDecimal.ZERO;
    public BigDecimal cartDiscountShare = BigDecimal.ZERO;
    public BigDecimal vatAmount = BigDecimal.ZERO;
    public BigDecimal lineTotal = BigDecimal.ZERO;

    public CartItem(Variant variant, BigDecimal quantity) {
        this.variant = variant;
        this.quantity = quantity;
    }

    public BigDecimal totalDiscount() {
        return productDiscount.add(itemDiscount).add(cartDiscountShare);
    }

    public BigDecimal lineDiscountBeforeCart() {
        return productDiscount.add(itemDiscount);
    }
}
