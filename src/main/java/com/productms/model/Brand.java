package com.productms.model;

/** Product brand. */
public class Brand {
    public int brandId;
    public String name;
    public boolean active = true;

    @Override
    public String toString() {
        return name;
    }
}
