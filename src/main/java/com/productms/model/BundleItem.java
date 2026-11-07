package com.productms.model;

import java.math.BigDecimal;

/** One component of a bundle/combo. */
public class BundleItem {
    public int componentVariantId;
    public String componentName;
    public String componentSku;
    public BigDecimal quantity = BigDecimal.ONE;
}
