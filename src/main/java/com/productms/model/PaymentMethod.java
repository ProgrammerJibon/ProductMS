package com.productms.model;

/** A configurable payment method (Cash, Card, bKash, Nagad, Rocket, or shop-defined). */
public class PaymentMethod {
    public String code;
    public String name;
    /** CASH, CARD, MOBILE, CREDIT (store credit) or OTHER. */
    public String kind = "OTHER";
    public boolean requiresReference = true;
    public String referenceRegex;
    public String referenceHint;
    public String color;
    public int sortOrder;
    public boolean active = true;

    public boolean isCash() {
        return "CASH".equals(kind);
    }

    @Override
    public String toString() {
        return name;
    }
}
