package com.productms.model;

/** Unit of measure (PCS, KG, Litre, Box...). */
public class Unit {
    public int unitId;
    public String name;
    public String shortName;
    public boolean allowDecimal;

    @Override
    public String toString() {
        return name + " (" + shortName + ")";
    }
}
