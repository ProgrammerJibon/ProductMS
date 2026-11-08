package com.productms.model;

import com.productms.util.Money;
import com.productms.util.Settings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A POS cart and its pricing rules, applied per line in this order:
 * <ol>
 *   <li><b>List price</b>: an approved manual override, else the retail price; for combos either the
 *       fixed combo price or the sum of the included components minus the combo discount.</li>
 *   <li><b>Quantity price</b>: wholesale mode uses the wholesale price; in any mode the lowest
 *       quantity tier the line qualifies for (price tiers, pack prices, legacy wholesale-from qty) wins.</li>
 *   <li><b>Product discount</b> (percent or fixed per unit) when today is inside its start / end window.</li>
 *   <li><b>Cashier item discount</b> (amount or percent).</li>
 *   <li><b>Cart discount</b>, spread over lines in proportion to their value.</li>
 *   <li><b>VAT</b> on the discounted net, per line, rounded half-up to 2 decimals; prices either
 *       exclude VAT (added) or include it (extracted). Invoice totals are sums of rounded lines,
 *       and refunds use the same per-line figures, so invoices, returns and reports always agree.</li>
 * </ol>
 */
public class Cart {
    public String label;
    public Customer customer;
    public String priceMode = "RETAIL";
    public String cartDiscountType = "AMOUNT";
    public BigDecimal cartDiscountValue = BigDecimal.ZERO;
    public final List<CartItem> items = new ArrayList<>();
    public String note;

    /** Exchange credit from a return, applied automatically at payment. */
    public BigDecimal storeCredit = BigDecimal.ZERO;
    public String storeCreditRef;
    public Integer heldId;
    /** Idempotency key: the same checkout can never create two sales. */
    public String clientTxnId = UUID.randomUUID().toString();
    /** Manager who approved cart-level limits (sale amount, credit limit, cart discount). */
    public String approvedBy;

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
            if (i.variant.variantId == variantId && i.overridePrice == null && i.excludedComponents.isEmpty()) {
                return i;
            }
        }
        return null;
    }

    /** List price of one unit before quantity pricing (handles combos and optional parts). */
    public static BigDecimal listPrice(Variant v, List<Integer> excluded, boolean wholesale) {
        BigDecimal base = wholesale && v.wholesalePrice.signum() > 0 ? v.wholesalePrice : v.retailPrice;
        if (!v.isBundle() || v.components.isEmpty()) {
            return base;
        }
        BigDecimal included = BigDecimal.ZERO;
        BigDecimal left = BigDecimal.ZERO;
        for (BundleItem b : v.components) {
            BigDecimal value = b.unitPrice.multiply(b.quantity);
            if (excluded.contains(b.componentVariantId)) {
                left = left.add(value);
            } else {
                included = included.add(value);
            }
        }
        if ("SUM_DISCOUNT".equals(v.bundlePricing)) {
            BigDecimal pct = Money.nz(v.bundleDiscountPercent);
            return Money.r2(included.subtract(Money.pct(included, pct)));
        }
        return Money.max(BigDecimal.ZERO, base.subtract(left));
    }

    /** Lowest tier price the quantity qualifies for, or null. */
    public static BigDecimal tierPrice(Variant v, BigDecimal qty) {
        BigDecimal best = null;
        for (BigDecimal[] t : v.tiers) {
            if (qty.compareTo(t[0]) >= 0 && (best == null || t[1].compareTo(best) < 0)) {
                best = t[1];
            }
        }
        if (v.wholesaleMinQty.signum() > 0 && qty.compareTo(v.wholesaleMinQty) >= 0 && v.wholesalePrice.signum() > 0
                && (best == null || v.wholesalePrice.compareTo(best) < 0)) {
            best = v.wholesalePrice;
        }
        return best;
    }

    public void recalculate() {
        boolean vatIncluded = Settings.pricesIncludeVat();
        boolean wholesaleMode = "WHOLESALE".equals(priceMode);
        BigDecimal base = BigDecimal.ZERO;
        subtotal = Money.ZERO;
        itemDiscountTotal = Money.ZERO;

        for (CartItem it : items) {
            Variant v = it.variant;
            it.listPrice = listPrice(v, it.excludedComponents, false);
            BigDecimal price;
            it.wholesaleApplied = false;
            it.tierApplied = false;
            if (it.overridePrice != null) {
                price = it.overridePrice;
            } else {
                price = listPrice(v, it.excludedComponents, wholesaleMode);
                if (!v.isBundle()) {
                    BigDecimal tier = tierPrice(v, it.quantity);
                    if (tier != null && tier.compareTo(price) < 0) {
                        price = tier;
                        it.tierApplied = true;
                        it.wholesaleApplied = !wholesaleMode;
                    }
                }
            }
            it.unitPrice = Money.r2(price);
            it.gross = Money.r2(it.unitPrice.multiply(it.quantity));

            BigDecimal pd = BigDecimal.ZERO;
            if (it.overridePrice == null && v.discountActive()) {
                if ("PERCENT".equals(v.discountType)) {
                    pd = Money.pct(it.gross, v.discountValue);
                } else if ("FIXED".equals(v.discountType)) {
                    pd = v.discountValue.multiply(it.quantity);
                }
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

        BigDecimal cd = "PERCENT".equals(cartDiscountType) ? Money.pct(base, cartDiscountValue) : Money.nz(cartDiscountValue);
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
            BigDecimal rate = Money.nz(it.variant.vatPercent);
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
}
