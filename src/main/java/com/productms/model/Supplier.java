package com.productms.model;

/** Supplier goods are received from. */
public class Supplier {
    public int supplierId;
    public String name;
    public String phone;
    public String address;
    public boolean active = true;

    @Override
    public String toString() {
        return name;
    }
}
