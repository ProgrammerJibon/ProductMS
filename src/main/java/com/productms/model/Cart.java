package com.productms.model;

import com.productms.util.Money;
import com.productms.util.Settings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * A POS shopping cart with real-world pricing rules:
 * <ol>
 *   <li>Unit price = wholesale price in wholesale mode, or in retail mode once the
 *       quantity reaches the variant's wholesale minimum quantity; otherwise retail price.</li>
 *   <li>Product discount (percent or fixed per unit) from the product master.</li>
 *   <li>Cashier item discount (amount or percent).</li>
 *   <li>Cart discount spread over lines in proportion to their value (so VAT stays correct).</li>
 *   <li>VAT per product rate; prices either exclude VAT (VAT added) or include it (VAT extracted).</li>
 * </ol>
 */
public class Cart {
    public String label;
    public Customer customer;
    public String priceMode = "RETAIL";
    public String cartDiscountType = "AMOUNT";
    public BigDecimal cartDiscountValue = BigDecimal.ZERO;
    public final List<CartItem> items = new ArrayList<>();

    /** Store credit from an exchange, applied automatically at payment. */
    public BigDecimal storeCredit = BigDecimal.ZERO;
    public String storeCreditRef;
    public Integer heldId;

    // ---- calculated ----
    public BigDecimal subtotal = Money.ZERO;
    public BigDecimal itemDiscountTotal = Money.ZERO;
    public BigDecimal cartDiscount = Money.ZERO;
    public BigDecimal vatTotal = Money.ZERO;
    public BigDecimal grandTotal = Money.ZERO;

    public Cart(String label) {
        this.label = label;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public BigDecimal itemCount() {
        BigDecimal n = BigDecimal.ZERO;
        for (CartItem i : items) {
            n = n.add(i.quantity);
        }
        return n;
    }

    public CartItem find(int variantId) {
        for (CartItem i : items) {
            if (i.variant.variantId == variantId) {
                return i;
            }
        }
        return null;
    }

    public void recalculate() {
        boolean vatIncluded = Settings.pricesIncludeVat();
        BigDecimal base = BigDecimal.ZERO;
        subtotal = Money.ZERO;
        itemDiscountTotal = Money.ZERO;

        for (CartItem it : items) {
            Variant v = it.variant;
            boolean wholesale = "WHOLESALE".equals(priceMode)
                    || (v.wholesaleMinQty.signum() > 0 && it.quantity.compareTo(v.wholesaleMinQty) >= 0);
            wholesale = wholesale && v.wholesalePrice.signum() > 0;
            it.wholesaleApplied = wholesale && !"WHOLESALE".equals(priceMode);
            it.unitPrice = wholesale ? v.wholesalePrice : v.retailPrice;
            it.gross = Money.r2(it.unitPrice.multiply(it.quantity));

            BigDecimal pd = BigDecimal.ZERO;
            if ("PERCENT".equals(v.discountType)) {
                pd = Money.pct(it.gross, v.discountValue);
            } else if ("FIXED".equals(v.discountType)) {
                pd = v.discountValue.multiply(it.quantity);
            }
            it.productDiscount = Money.r2(Money.min(pd, it.gross));

            BigDecimal afterProduct = it.gross.subtract(it.productDiscount);
            BigDecimal idisc = "PERCENT".equals(it.itemDiscountType)
                    ? Money.pct(afterProduct, it.itemDiscountValue)
                    : Money.nz(it.itemDiscountValue);
            it.itemDiscount = Money.r2(Money.max(BigDecimal.ZERO, Money.min(idisc, afterProduct)));

            subtotal = subtotal.add(it.gross);
            itemDiscountTotal = itemDiscountTotal.add(it.lineDiscountBeforeCart());
            base = base.add(afterProduct.subtract(it.itemDiscount));
        }

        BigDecimal cd = "PERCENT".equals(cartDiscountType)
                ? Money.pct(base, cartDiscountValue)
                : Money.nz(cartDiscountValue);
        cartDiscount = Money.r2(Money.max(BigDecimal.ZERO, Money.min(cd, base)));

        BigDecimal remaining = cartDiscount;
        vatTotal = Money.ZERO;
        grandTotal = Money.ZERO;
        for (int i = 0; i < items.size(); i++) {
            CartItem it = items.get(i);
            BigDecimal lineBase = it.gross.subtract(it.lineDiscountBeforeCart());
            BigDecimal share;
            if (i == items.size() - 1) {
                share = remaining;
            } else if (base.signum() == 0) {
                share = BigDecimal.ZERO;
            } else {
                share = Money.r2(cartDiscount.multiply(lineBase).divide(base, 6, RoundingMode.HALF_UP));
            }
            share = Money.min(share, lineBase);
            remaining = remaining.subtract(share);
            it.cartDiscountShare = Money.r2(share);

            BigDecimal net = lineBase.subtract(it.cartDiscountShare);
            BigDecimal rate = Money.nz(v(it).vatPercent);
            if (vatIncluded) {
                BigDecimal exVat = net.multiply(Money.HUNDRED).divide(Money.HUNDRED.add(rate), 6, RoundingMode.HALF_UP);
                it.vatAmount = Money.r2(net.subtract(exVat));
                it.lineTotal = Money.r2(net);
            } else {
                it.vatAmount = Money.r2(Money.pct(net, rate));
                it.lineTotal = Money.r2(net.add(it.vatAmount));
            }
            vatTotal = vatTotal.add(it.vatAmount);
            grandTotal = grandTotal.add(it.lineTotal);
        }
    }

    private static Variant v(CartItem it) {
        return it.variant;
    }
}
