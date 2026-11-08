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

    // ---- v3 fields ----
    public String extraAttributes;
    public String plu;
    public BigDecimal mrp = BigDecimal.ZERO;
    public BigDecimal minPrice = BigDecimal.ZERO;
    public BigDecimal damagedQty = BigDecimal.ZERO;
    public BigDecimal reservedQty = BigDecimal.ZERO;
    public BigDecimal reorderQty = BigDecimal.ZERO;
    public String location;
    public boolean favourite;
    public int posOrder;
    public BigDecimal soldLast30 = BigDecimal.ZERO;
    public String tags;
    public int warrantyMonths;
    public java.time.LocalDate discountStart;
    public java.time.LocalDate discountEnd;
    public String bundlePricing = "FIXED";
    public BigDecimal bundleDiscountPercent = BigDecimal.ZERO;
    public boolean deleted;
    /** Quantity price tiers, ascending by min quantity: [minQty, unitPrice]. */
    public java.util.List<BigDecimal[]> tiers = new java.util.ArrayList<>();
    /** Bundle components (bundles only), used for optional parts and SUM_DISCOUNT pricing. */
    public java.util.List<BundleItem> components = new java.util.ArrayList<>();
    /** Text form of tiers / packs / extra barcodes used by the product editor grid. */
    public String tiersText;
    public String packsText;
    public String barcodesText;

    /** Is the product discount active today (inside its optional start / end window)? */
    public boolean discountActive() {
        java.time.LocalDate today = java.time.LocalDate.now();
        return !"NONE".equals(discountType) && (discountStart == null || !today.isBefore(discountStart))
                && (discountEnd == null || !today.isAfter(discountEnd));
    }

    /** Physical stock on the premises = sellable + damaged (quarantined). */
    public BigDecimal physicalQty() {
        return stockQty.add(damagedQty);
    }

    public boolean isBundle() {
        return "BUNDLE".equals(productType);
    }

    public boolean isSellable() {
        return active && productActive;
    }

    /** "L / Black / 1 KG" or "" when the product has no variant attributes. */
    public String label() {
        StringBuilder sb = new StringBuilder();
        for (String s : new String[]{size, colour, weight, extraAttributes}) {
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
