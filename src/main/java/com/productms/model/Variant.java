package com.productms.model;

import java.math.BigDecimal;

/**
 * A sellable product variant (size / colour / weight). Holds SKU, barcode,
 * prices and stock, plus the parent product fields needed for display and pricing.
 */
public class Variant {
    public int variantId;
    public int productId;
    public String size;
    public String colour;
    public String weight;
    public String sku;
    public String barcode;
    public BigDecimal purchasePrice = BigDecimal.ZERO;
    public BigDecimal retailPrice = BigDecimal.ZERO;
    public BigDecimal wholesalePrice = BigDecimal.ZERO;
    public BigDecimal wholesaleMinQty = BigDecimal.ZERO;
    public BigDecimal stockQty = BigDecimal.ZERO;
    public BigDecimal reorderLevel = BigDecimal.ZERO;
    public boolean active = true;

    /** Quantity that can actually be sold now (unexpired batches, bundle components). */
    public BigDecimal available = BigDecimal.ZERO;

    // ---- parent product fields (joined) ----
    public String productName;
    public String description;
    public String productType = "STANDARD";
    public boolean trackBatch;
    public boolean trackSerial;
    public boolean productActive = true;
    public BigDecimal vatPercent = BigDecimal.ZERO;
    public String discountType = "NONE";
    public BigDecimal discountValue = BigDecimal.ZERO;
    public Integer categoryId;
    public String categoryName;
    public Integer brandId;
    public String brandName;
    public String unitShort = "PCS";
    public boolean allowDecimal;
    public String imagePath;

    /** Opening stock entered for a brand-new variant (product editor only). */
    public BigDecimal openingStock = BigDecimal.ZERO;

    public boolean isBundle() {
        return "BUNDLE".equals(productType);
    }

    public boolean isSellable() {
        return active && productActive;
    }

    /** "L / Black / 1 KG" or "" when the product has no variant attributes. */
    public String label() {
        StringBuilder sb = new StringBuilder();
        for (String s : new String[]{size, colour, weight}) {
            if (s != null && !s.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(" / ");
                }
                sb.append(s.trim());
            }
        }
        return sb.toString();
    }

    public String displayName() {
        String l = label();
        return l.isEmpty() ? productName : productName + " (" + l + ")";
    }

    public boolean isLowStock() {
        return !isBundle() && stockQty.signum() > 0 && stockQty.compareTo(reorderLevel) <= 0;
    }

    public boolean isOutOfStock() {
        return available.signum() <= 0;
    }

    @Override
    public String toString() {
        return displayName() + " [" + sku + "]";
    }
}
