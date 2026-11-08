package com.productms.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A product with its variants. Bundles have exactly one variant and a list of components.
 */
public class Product {
    public int productId;
    public String name;
    public String description;
    public Integer categoryId;
    public Integer brandId;
    public int unitId;
    public String productType = "STANDARD";
    public boolean trackBatch;
    public boolean trackSerial;
    public BigDecimal vatPercent = BigDecimal.ZERO;
    public String discountType = "NONE";
    public BigDecimal discountValue = BigDecimal.ZERO;
    public LocalDate discountStart;
    public LocalDate discountEnd;
    public String imagePath;
    public String tags;
    public String attributes;
    public int warrantyMonths;
    public String bundlePricing = "FIXED";
    public BigDecimal bundleDiscountPercent = BigDecimal.ZERO;
    public boolean active = true;
    public boolean deleted;
    public List<Variant> variants = new ArrayList<>();
    public List<BundleItem> bundleItems = new ArrayList<>();
    /** Image file paths, first = primary. */
    public List<String> images = new ArrayList<>();
    /** Reason recorded in price history when prices change on save. */
    public String priceChangeReason;

    public boolean isBundle() {
        return "BUNDLE".equals(productType);
    }
}
