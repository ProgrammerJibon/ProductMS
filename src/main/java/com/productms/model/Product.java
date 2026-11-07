package com.productms.model;

import java.math.BigDecimal;
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
    public String imagePath;
    public boolean active = true;
    public List<Variant> variants = new ArrayList<>();
    public List<BundleItem> bundleItems = new ArrayList<>();

    public boolean isBundle() {
        return "BUNDLE".equals(productType);
    }
}
