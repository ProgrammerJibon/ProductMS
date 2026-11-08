package com.productms.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A line in a POS cart. Inputs: variant, quantity (base units), cashier discount, optional
 * price override, serials, note and left-out optional combo parts. The rest is calculated by
 * {@link Cart#recalculate()}.
 */
public class CartItem {
    public Variant variant;
    public BigDecimal quantity = BigDecimal.ONE;
    public String itemDiscountType = "AMOUNT";
    public BigDecimal itemDiscountValue = BigDecimal.ZERO;
    public List<String> serials = new ArrayList<>();
    /** Manually set unit price (null = normal pricing). */
    public BigDecimal overridePrice;
    /** Manager who approved an override / discount beyond limits, if any. */
    public String approvedBy;
    public String note;
    /** Variant ids of optional combo components the customer left out. */
    public List<Integer> excludedComponents = new ArrayList<>();

    // ---- calculated ----
    public BigDecimal listPrice = BigDecimal.ZERO;
    public BigDecimal unitPrice = BigDecimal.ZERO;
    public boolean wholesaleApplied;
    public boolean tierApplied;
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

    /** Net amount before VAT, after every discount. */
    public BigDecimal netBeforeVat() {
        return gross.subtract(totalDiscount());
    }

    /** Effective unit price actually charged (before VAT) after all discounts. */
    public BigDecimal effectiveUnitNet() {
        return quantity.signum() == 0 ? BigDecimal.ZERO
                : netBeforeVat().divide(quantity, 2, java.math.RoundingMode.HALF_UP);
    }

    /** Total discount on this line as a percentage of its list value. */
    public BigDecimal discountPercent() {
        BigDecimal list = listPrice.multiply(quantity);
        if (list.signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal reduction = list.subtract(gross).add(totalDiscount());
        return reduction.multiply(new BigDecimal("100")).divide(list, 2, java.math.RoundingMode.HALF_UP);
    }
}
