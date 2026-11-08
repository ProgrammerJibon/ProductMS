package com.productms.model;

import java.math.BigDecimal;

/** One line of a sale. Names and prices are snapshots taken at the time of sale. */
public class SaleItem {
    public int saleItemId;
    public int saleId;
    public int variantId;
    public String productName;
    public String variantLabel;
    public String sku;
    public String unitName;
    public BigDecimal quantity = BigDecimal.ZERO;
    public BigDecimal unitPrice = BigDecimal.ZERO;
    public BigDecimal discountAmount = BigDecimal.ZERO;
    public BigDecimal vatPercent = BigDecimal.ZERO;
    public BigDecimal vatAmount = BigDecimal.ZERO;
    public BigDecimal lineTotal = BigDecimal.ZERO;
    public BigDecimal unitCost = BigDecimal.ZERO;
    public BigDecimal returnedQty = BigDecimal.ZERO;
    public String serialNumbers;
    public BigDecimal originalPrice;
    public int warrantyMonths;
    public String excludedComponents;
    public String note;

    public String displayName() {
        return variantLabel == null || variantLabel.isBlank() ? productName : productName + " (" + variantLabel + ")";
    }

    public BigDecimal returnable() {
        return quantity.subtract(returnedQty);
    }
}
