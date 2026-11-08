package com.productms.model;

import java.math.BigDecimal;

/** One component of a bundle/combo. Optional components can be left out at the POS. */
public class BundleItem {
    public int componentVariantId;
    public String componentName;
    public String componentSku;
    public BigDecimal quantity = BigDecimal.ONE;
    public boolean optional;
    /** Retail price of one component unit (filled when loading for pricing). */
    public BigDecimal unitPrice = BigDecimal.ZERO;
}
