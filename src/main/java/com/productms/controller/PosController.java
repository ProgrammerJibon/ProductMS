package com.productms.controller;

import com.productms.dao.CustomerDAO;
import com.productms.dao.HeldCartDAO;
import com.productms.dao.MasterDataDAO;
import com.productms.dao.ProductDAO;
import com.productms.dao.UserDAO;
import com.productms.model.Cart;
import com.productms.model.CartItem;
import com.productms.model.Category;
import com.productms.model.Customer;
import com.productms.model.Sale;
import com.productms.model.User;
import com.productms.model.Variant;
import com.productms.service.SaleService;
import com.productms.service.ShiftService;
import com.productms.util.AppException;
import com.productms.util.Money;
import com.productms.util.Session;
import com.productms.util.Settings;
import com.productms.view.CustomerPickerDialog;
import com.productms.view.HeldCartsDialog;
import com.productms.view.NumberPadDialog;
import com.productms.view.PaymentDialog;
import com.productms.view.PosView;
import com.productms.view.ReceiptPreviewDialog;
import com.productms.view.SerialPickerDialog;
import com.productms.view.UiKit;
import com.productms.view.VariantChooserDialog;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Point of Sale logic: barcode scanning, product grid, multiple carts, quantity and discount
 * rules (with manager override), customers, hold / resume, payment and receipts.
 */
public class PosController implements Module {

    private static final int MAX_CARTS = 8;

    private final PosView view = new PosView();
    private final ProductDAO products = new ProductDAO();
    private final CustomerDAO customers = new CustomerDAO();
    private final HeldCartDAO held = new HeldCartDAO();
    private final SaleService sales = new SaleService();
    private final MainController main;
    private final List<Cart> carts = new ArrayList<>();
    private int cartCounter;
    private boolean switching;
    private List<Variant> catalogue = new ArrayList<>();
    private List<List<Variant>> visibleTiles = new ArrayList<>();

    public PosController(MainController main) {
        this.main = main;
        Timer debounce = new Timer(250, e -> refreshGrid());
        debounce.setRepeats(false);
        view.txtScan.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                debounce.restart();
            }

            public void removeUpdate(DocumentEvent e) {
                debounce.restart();
            }

            public void changedUpdate(DocumentEvent e) {
                debounce.restart();
            }
        });
        view.txtScan.addActionListener(e -> {
            debounce.stop();
            scan(view.txtScan.getText());
        });
        view.cmbCategory.addActionListener(e -> refreshGrid());
        view.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                refreshGrid();
            }
        });
        view.cartTabs.addChangeListener(e -> {
            if (!switching) {
                refreshCart();
            }
        });
        view.btnNewCart.addActionListener(e -> newCart(true));
        view.btnCustomer.addActionListener(e -> chooseCustomer());
        view.tglWholesale.addActionListener(e -> setWholesale(view.tglWholesale.isSelected()));
        view.btnPlus.addActionListener(e -> step(1));
        view.btnMinus.addActionListener(e -> step(-1));
        view.btnQty.addActionListener(e -> changeQty());
        view.btnItemDisc.addActionListener(e -> itemDiscount());
        view.btnRemove.addActionListener(e -> removeLine());
        view.btnHold.addActionListener(e -> hold());
        view.btnHeld.addActionListener(e -> resumeHeld());
        view.btnCartDisc.addActionListener(e -> cartDiscount());
        view.btnVoid.addActionListener(e -> voidCart());
        view.btnReprint.addActionListener(e -> reprintLast());
        view.btnPay.addActionListener(e -> pay());

        UiKit.bind(view, "F1", this::focusScan);
        UiKit.bind(view, "F2", this::changeQty);
        UiKit.bind(view, "F3", this::itemDiscount);
        UiKit.bind(view, "F4", this::chooseCustomer);
        UiKit.bind(view, "F5", this::hold);
        UiKit.bind(view, "F6", this::resumeHeld);
        UiKit.bind(view, "F7", () -> {
            view.tglWholesale.setSelected(!view.tglWholesale.isSelected());
            setWholesale(view.tglWholesale.isSelected());
        });
        UiKit.bind(view, "F8", () -> newCart(true));
        UiKit.bind(view, "F9", this::pay);
        UiKit.bind(view, "F10", this::cartDiscount);
        UiKit.bind(view, "ctrl P", this::reprintLast);
        UiKit.bind(view, "ctrl UP", () -> step(1));
        UiKit.bind(view, "ctrl DOWN", () -> step(-1));
        UiKit.bind(view.cartTable, "DELETE", this::removeLine);
        UiKit.bind(view.cartTable, "ADD", () -> step(1));
        UiKit.bind(view.cartTable, "SUBTRACT", () -> step(-1));
        UiKit.bind(view.txtScan, "ESCAPE", () -> view.txtScan.setText(""));
        UiKit.bind(view.txtScan, "DOWN", () -> {
            if (view.cartTable.getRowCount() > 0) {
                view.cartTable.requestFocusInWindow();
                view.cartTable.setRowSelectionInterval(0, 0);
            }
        });
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void onShow() throws Exception {
        if (carts.isEmpty()) {
            newCart(false);
        }
        Object sel = view.cmbCategory.getSelectedItem();
        view.cmbCategory.removeAllItems();
        view.cmbCategory.addItem("All categories");
        for (Category c : new MasterDataDAO().categories(true)) {
            view.cmbCategory.addItem(c);
        }
        if (sel != null) {
            view.cmbCategory.setSelectedItem(sel);
        }
        reloadCatalogue();
        refreshCart();
        focusScan();
    }

    public boolean hasOpenCarts() {
        return carts.stream().anyMatch(c -> !c.isEmpty());
    }

    private void focusScan() {
        view.txtScan.requestFocusInWindow();
        view.txtScan.selectAll();
    }

    private Cart cart() {
        int i = view.cartTabs.getSelectedIndex();
        return i < 0 || i >= carts.size() ? null : carts.get(i);
    }

    // ================================================================== catalogue / grid

    private void reloadCatalogue() throws Exception {
        ProductDAO.Filter f = new ProductDAO.Filter();
        f.sellableOnly = true;
        f.limit = 5000;
        catalogue = products.search(f);
        refreshGrid();
    }

    private void refreshGrid() {
        String q = view.txtScan.getText().trim().toLowerCase();
        Object cat = view.cmbCategory.getSelectedItem();
        Integer catId = cat instanceof Category ? ((Category) cat).categoryId : null;
        String catName = cat instanceof Category ? ((Category) cat).displayName() : null;
        Map<Integer, List<Variant>> groups = new LinkedHashMap<>();
        for (Variant v : catalogue) {
            if (catId != null && (v.categoryName == null || !(v.categoryId.equals(catId) || v.categoryName.startsWith(catName + " ›")))) {
                continue;
            }
            if (!q.isEmpty() && !(v.productName.toLowerCase().contains(q) || v.sku.toLowerCase().contains(q)
                    || (v.barcode != null && v.barcode.equals(q)) || v.label().toLowerCase().contains(q)
                    || (v.brandName != null && v.brandName.toLowerCase().contains(q)))) {
                continue;
            }
            groups.computeIfAbsent(v.productId, k -> new ArrayList<>()).add(v);
        }
        List<List<Variant>> tiles = new ArrayList<>(groups.values());
        visibleTiles = tiles;
        int shown = Math.min(tiles.size(), 120);
        view.setTiles(tiles.subList(0, shown), this::tileClicked);
        view.lblGridInfo.setText(tiles.size() + " product(s)" + (tiles.size() > shown ? " - showing first " + shown
                + ", refine the search" : ""));
    }

    private void tileClicked(List<Variant> vs) {
        Variant v = vs.size() == 1 ? vs.get(0) : VariantChooserDialog.choose(view, vs.get(0).productName, vs);
        if (v != null) {
            UiKit.guard(view, () -> add(v, null, null));
        }
        focusScan();
    }

    // ================================================================== scanning

    private void scan(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return;
        }
        UiKit.guard(view, () -> {
            BigDecimal qty = null;
            String code = text;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*[*xX]\\s*(.+)$").matcher(text);
            if (m.matches()) {
                qty = new BigDecimal(m.group(1));
                code = m.group(2).trim();
            }
            Variant v = products.findByCode(code);
            List<String> serials = null;
            if (v == null) {
                v = products.findBySerial(code);
                if (v != null) {
                    serials = new ArrayList<>(List.of(code));
                    qty = BigDecimal.ONE;
                }
            }
            if (v == null) {
                refreshGrid();
                if (visibleTiles.size() == 1) {
                    view.txtScan.setText("");
                    tileClicked(visibleTiles.get(0));
                    return;
                }
                java.awt.Toolkit.getDefaultToolkit().beep();
                view.lblStatus.setText("No product with barcode / SKU \"" + code + "\""
                        + (visibleTiles.isEmpty() ? "." : " - " + visibleTiles.size() + " matches, pick one from the grid."));
                view.lblStatus.setForeground(UiKit.DANGER);
                view.txtScan.selectAll();
                return;
            }
            add(v, qty, serials);
            view.txtScan.setText("");
        });
    }

    // ================================================================== cart lines

    private BigDecimal qtyInCarts(int variantId, CartItem except) {
        BigDecimal n = BigDecimal.ZERO;
        for (Cart c : carts) {
            for (CartItem i : c.items) {
                if (i != except && i.variant.variantId == variantId) {
                    n = n.add(i.quantity);
                }
            }
        }
        return n;
    }

    private void checkAvailable(Variant fresh, BigDecimal newQty, CartItem line) {
        BigDecimal others = qtyInCarts(fresh.variantId, line);
        if (others.add(newQty).compareTo(fresh.available) > 0) {
            BigDecimal canAdd = Money.max(BigDecimal.ZERO, fresh.available.subtract(others));
            throw new AppException("Not enough stock for " + fresh.displayName() + ".\nAvailable: " + Money.qty(fresh.available)
                    + " " + fresh.unitShort + (others.signum() > 0 ? " (" + Money.qty(others) + " already in carts)" : "")
                    + "\nYou can add at most " + Money.qty(canAdd) + " more."
                    + (fresh.trackBatch ? "\n(Expired batches are not counted.)" : ""));
        }
    }

    private void add(Variant v, BigDecimal qty, List<String> serials) throws Exception {
        Cart cart = cart();
        if (cart == null) {
            return;
        }
        Variant fresh = products.findVariant(v.variantId);
        if (fresh == null || !fresh.isSellable()) {
            throw new AppException(v.displayName() + " is inactive and cannot be sold.");
        }
        if (qty == null && fresh.allowDecimal && !fresh.trackSerial) {
            qty = NumberPadDialog.ask(view, "Quantity", "Enter weight / quantity of <b>" + fresh.displayName() + "</b> in "
                    + fresh.unitShort + ":", BigDecimal.ONE, true);
            if (qty == null) {
                return;
            }
        }
        if (qty == null) {
            qty = BigDecimal.ONE;
        }
        if (!Money.pos(qty)) {
            throw new AppException("Quantity must be greater than 0.");
        }
        if (!fresh.allowDecimal && !Money.isWhole(qty)) {
            throw new AppException(fresh.displayName() + " is sold in whole " + fresh.unitShort + " only.");
        }
        CartItem line = cart.find(fresh.variantId);
        if (fresh.trackSerial) {
            List<String> inOtherLines = new ArrayList<>();
            for (Cart c : carts) {
                for (CartItem i : c.items) {
                    if (i.variant.variantId == fresh.variantId) {
                        inOtherLines.addAll(i.serials);
                    }
                }
            }
            List<String> avail = new ArrayList<>(products.serialsInStock(fresh.variantId));
            avail.removeAll(inOtherLines);
            if (serials == null) {
                serials = SerialPickerDialog.pick(view, "Select serial / IMEI for " + fresh.displayName(), avail, List.of(), null);
                if (serials == null) {
                    return;
                }
            } else if (!avail.containsAll(serials)) {
                throw new AppException("Serial " + serials.get(0) + " is already in a cart or not in stock.");
            }
            qty = BigDecimal.valueOf(serials.size());
        }
        BigDecimal newQty = line == null ? qty : line.quantity.add(qty);
        checkAvailable(fresh, newQty, line);
        if (line == null) {
            line = new CartItem(fresh, qty);
            cart.items.add(line);
        } else {
            line.variant = fresh;
            line.quantity = newQty;
        }
        if (serials != null) {
            line.serials.addAll(serials);
        }
        refreshCart();
        select(line);
        view.lblStatus.setText("Added " + Money.qty(qty) + " × " + fresh.displayName());
        view.lblStatus.setForeground(UiKit.SUCCESS);
    }

    private void select(CartItem line) {
        int i = cart().items.indexOf(line);
        if (i >= 0) {
            view.cartTable.setRowSelectionInterval(i, i);
            view.cartTable.scrollRectToVisible(view.cartTable.getCellRect(i, 0, true));
        }
    }

    private CartItem selectedLine() {
        Cart c = cart();
        if (c == null || c.isEmpty()) {
            return null;
        }
        int r = view.cartTable.getSelectedRow();
        return r < 0 ? c.items.get(c.items.size() - 1) : c.items.get(r);
    }

    private void step(int delta) {
        CartItem line = selectedLine();
        if (line == null) {
            return;
        }
        UiKit.guard(view, () -> {
            if (line.variant.trackSerial) {
                if (delta > 0) {
                    add(line.variant, null, null);
                } else if (line.serials.size() > 1) {
                    line.serials.remove(line.serials.size() - 1);
                    line.quantity = BigDecimal.valueOf(line.serials.size());
                    refreshCart();
                    select(line);
                } else {
                    removeLine();
                }
                return;
            }
            BigDecimal q = line.quantity.add(BigDecimal.valueOf(delta));
            if (q.signum() <= 0) {
                removeLine();
                return;
            }
            checkAvailable(products.findVariant(line.variant.variantId), q, line);
            line.quantity = q;
            refreshCart();
            select(line);
        });
    }

    private void changeQty() {
        CartItem line = selectedLine();
        if (line == null) {
            return;
        }
        UiKit.guard(view, () -> {
            Variant v = line.variant;
            if (v.trackSerial) {
                List<String> avail = new ArrayList<>(products.serialsInStock(v.variantId));
                for (Cart c : carts) {
                    for (CartItem i : c.items) {
                        if (i != line && i.variant.variantId == v.variantId) {
                            avail.removeAll(i.serials);
                        }
                    }
                }
                List<String> s = SerialPickerDialog.pick(view, "Serials for " + v.displayName(), avail, line.serials, null);
                if (s != null) {
                    line.serials = s;
                    line.quantity = BigDecimal.valueOf(s.size());
                    refreshCart();
                    select(line);
                }
                return;
            }
            BigDecimal q = NumberPadDialog.ask(view, "Quantity", "New quantity for <b>" + v.displayName() + "</b> (" + v.unitShort + "):",
                    line.quantity, v.allowDecimal);
            if (q == null) {
                return;
            }
            if (q.signum() == 0) {
                removeLine();
                return;
            }
            if (!v.allowDecimal && !Money.isWhole(q)) {
                throw new AppException(v.displayName() + " is sold in whole units only.");
            }
            checkAvailable(products.findVariant(v.variantId), q, line);
            line.quantity = q;
            refreshCart();
            select(line);
        });
    }

    private void removeLine() {
        CartItem line = selectedLine();
        if (line != null) {
            cart().items.remove(line);
            refreshCart();
        }
    }

    // ================================================================== discounts

    private boolean authorize(BigDecimal percent, String what) {
        BigDecimal max = Settings.bd("max_cashier_discount", "10");
        if (Session.isManager() || percent.compareTo(max) <= 0) {
            return true;
        }
        JTextField user = UiKit.field("Manager username");
        JPasswordField pass = new JPasswordField();
        JPanel p = new JPanel(new java.awt.GridLayout(0, 1, 4, 4));
        p.add(new JLabel("<html>" + what + " of " + percent.setScale(1, RoundingMode.HALF_UP) + "% exceeds your limit of "
                + Money.qty(max) + "%.<br>A manager must approve it.</html>"));
        p.add(user);
        p.add(pass);
        if (JOptionPane.showConfirmDialog(view, p, "Manager approval", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return false;
        }
        try {
            User u = new UserDAO().authenticate(user.getText().trim(), new String(pass.getPassword()));
            if (u != null && ("ADMIN".equals(u.role) || "MANAGER".equals(u.role))) {
                return true;
            }
        } catch (java.sql.SQLException e) {
            UiKit.error(view, e.getMessage());
            return false;
        }
        UiKit.warn(view, "Approval failed: not a manager account or wrong password.");
        return false;
    }

    private void itemDiscount() {
        CartItem line = selectedLine();
        if (line == null) {
            return;
        }
        NumberPadDialog.Discount d = NumberPadDialog.askDiscount(view, "Item discount",
                "Extra discount on <b>" + line.variant.displayName() + "</b><br>Line value: " + Money.cur(line.gross.subtract(line.productDiscount)),
                line.itemDiscountValue, "PERCENT".equals(line.itemDiscountType));
        if (d == null) {
            return;
        }
        BigDecimal base = line.gross.subtract(line.productDiscount);
        if (d.percent && d.value.compareTo(Money.HUNDRED) > 0) {
            UiKit.warn(view, "Percent cannot be more than 100.");
            return;
        }
        if (!d.percent && d.value.compareTo(base) > 0) {
            UiKit.warn(view, "Discount cannot be more than the line value (" + Money.cur(base) + ").");
            return;
        }
        BigDecimal pct = d.percent ? d.value : (base.signum() == 0 ? BigDecimal.ZERO
                : d.value.multiply(Money.HUNDRED).divide(base, 2, RoundingMode.HALF_UP));
        if (!authorize(pct, "Item discount")) {
            return;
        }
        line.itemDiscountType = d.percent ? "PERCENT" : "AMOUNT";
        line.itemDiscountValue = d.value;
        refreshCart();
        select(line);
    }

    private void cartDiscount() {
        Cart c = cart();
        if (c == null || c.isEmpty()) {
            return;
        }
        BigDecimal base = c.subtotal.subtract(c.itemDiscountTotal);
        NumberPadDialog.Discount d = NumberPadDialog.askDiscount(view, "Cart discount",
                "Discount on the whole cart<br>Cart value after item discounts: " + Money.cur(base),
                c.cartDiscountValue, "PERCENT".equals(c.cartDiscountType));
        if (d == null) {
            return;
        }
        if (d.percent && d.value.compareTo(Money.HUNDRED) > 0 || !d.percent && d.value.compareTo(base) > 0) {
            UiKit.warn(view, "Discount is more than the cart value.");
            return;
        }
        BigDecimal pct = d.percent ? d.value : (base.signum() == 0 ? BigDecimal.ZERO
                : d.value.multiply(Money.HUNDRED).divide(base, 2, RoundingMode.HALF_UP));
        if (!authorize(pct, "Cart discount")) {
            return;
        }
        c.cartDiscountType = d.percent ? "PERCENT" : "AMOUNT";
        c.cartDiscountValue = d.value;
        refreshCart();
    }

    // ================================================================== customer / mode

    private void chooseCustomer() {
        Cart c = cart();
        if (c == null) {
            return;
        }
        Customer cu = CustomerPickerDialog.pick(view);
        if (cu == null) {
            return;
        }
        c.customer = cu;
        if (cu.isWholesale() && !"WHOLESALE".equals(c.priceMode)) {
            c.priceMode = "WHOLESALE";
            view.lblStatus.setText("Wholesale customer - wholesale prices applied.");
            view.lblStatus.setForeground(UiKit.PURPLE);
        } else if (!cu.isWholesale() && "WHOLESALE".equals(c.priceMode)) {
            c.priceMode = "RETAIL";
        }
        refreshCart();
    }

    private void setWholesale(boolean on) {
        Cart c = cart();
        if (c == null) {
            return;
        }
        c.priceMode = on ? "WHOLESALE" : "RETAIL";
        refreshCart();
    }

    // ================================================================== carts

    private void newCart(boolean focus) {
        if (carts.size() >= MAX_CARTS) {
            UiKit.warn(view, "Up to " + MAX_CARTS + " carts can be open. Finish, hold or void one first.");
            return;
        }
        UiKit.guard(view, () -> {
            Cart c = new Cart("Cart " + (++cartCounter));
            c.customer = customers.walkIn();
            addCartTab(c);
            if (focus) {
                focusScan();
            }
        });
    }

    private void addCartTab(Cart c) {
        switching = true;
        carts.add(c);
        view.cartTabs.addTab(c.label, new JPanel());
        view.cartTabs.setSelectedIndex(carts.size() - 1);
        switching = false;
        refreshCart();
    }

    private void closeCurrentCart() {
        int i = view.cartTabs.getSelectedIndex();
        if (i < 0) {
            return;
        }
        switching = true;
        carts.remove(i);
        view.cartTabs.removeTabAt(i);
        switching = false;
        if (carts.isEmpty()) {
            newCart(false);
        } else {
            refreshCart();
        }
    }

    private void voidCart() {
        Cart c = cart();
        if (c == null) {
            return;
        }
        if (!c.isEmpty() && !UiKit.confirm(view, "Void " + c.label + " (" + c.items.size() + " line(s))?")) {
            return;
        }
        closeCurrentCart();
    }

    private void hold() {
        Cart c = cart();
        if (c == null || c.isEmpty()) {
            UiKit.warn(view, "The cart is empty.");
            return;
        }
        String label = UiKit.input(view, "Name for this held cart (e.g. customer name):",
                (c.customer.walkIn ? c.label : c.customer.name) + " - "
                + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("hh:mm a", java.util.Locale.ENGLISH)));
        if (label == null) {
            return;
        }
        UiKit.guard(view, () -> {
            c.label = label.isBlank() ? c.label : label.trim();
            if (c.storeCredit.signum() > 0) {
                throw new AppException("A cart with exchange credit cannot be held. Complete or void it.");
            }
            held.hold(c);
            closeCurrentCart();
            view.lblStatus.setText("Cart \"" + c.label + "\" is on hold. Resume it with F6.");
            view.lblStatus.setForeground(UiKit.INFO);
        });
    }

    private void resumeHeld() {
        Integer id = HeldCartsDialog.pick(view);
        if (id == null) {
            return;
        }
        UiKit.guard(view, () -> {
            Cart c = held.resume(id, products, customers);
            if (c == null) {
                return;
            }
            Cart current = cart();
            if (current != null && current.isEmpty()) {
                closeCurrentCartSilently();
            }
            if (carts.size() >= MAX_CARTS) {
                held.hold(c);
                throw new AppException("Too many open carts. Close one first.");
            }
            addCartTab(c);
            List<String> problems = new ArrayList<>();
            for (CartItem it : c.items) {
                if (!it.variant.isSellable()) {
                    problems.add(it.variant.displayName() + " is now inactive");
                } else if (it.quantity.compareTo(it.variant.available) > 0) {
                    problems.add(it.variant.displayName() + ": only " + Money.qty(it.variant.available) + " available");
                }
            }
            if (!problems.isEmpty()) {
                UiKit.warn(view, "Stock changed since this cart was held:\n• " + String.join("\n• ", problems)
                        + "\n\nAdjust the quantities before payment.");
            }
        });
    }

    private void closeCurrentCartSilently() {
        int i = view.cartTabs.getSelectedIndex();
        switching = true;
        carts.remove(i);
        view.cartTabs.removeTabAt(i);
        switching = false;
    }

    /** Starts a new cart carrying store credit from an exchange (called from Sales). */
    public void startExchange(BigDecimal credit, String returnNo, Customer customer) {
        if (carts.size() >= MAX_CARTS) {
            closeCurrentCartSilently();
        }
        Cart c = new Cart("Exchange " + returnNo);
        c.customer = customer;
        c.storeCredit = credit;
        c.storeCreditRef = returnNo;
        if (customer.isWholesale()) {
            c.priceMode = "WHOLESALE";
        }
        addCartTab(c);
    }

    // ================================================================== refresh

    private void refreshCart() {
        Cart c = cart();
        if (c == null) {
            return;
        }
        int sel = view.cartTable.getSelectedRow();
        c.recalculate();
        view.cartModel.setRows(c.items);
        if (sel >= 0 && sel < c.items.size()) {
            view.cartTable.setRowSelectionInterval(sel, sel);
        }
        view.btnCustomer.setText((c.customer.walkIn ? "○  " : "●  ") + c.customer.name + "   (F4 change)");
        view.lblCustomerInfo.setText(c.customer.walkIn ? "Walk-in sale" : (c.customer.phone == null ? "" : c.customer.phone + "  ·  ")
                + c.customer.customerType + (c.customer.due.signum() > 0 ? "  ·  previous due " + Money.cur(c.customer.due) : ""));
        view.tglWholesale.setSelected("WHOLESALE".equals(c.priceMode));
        view.lblCredit.setText(c.storeCredit.signum() > 0 ? "Store credit: " + Money.cur(c.storeCredit) : " ");
        view.lblItems.setText(c.items.size() + " line(s), " + Money.qty(c.itemCount()) + " item(s)");
        view.lblSubtotal.setText(Money.fmt(c.subtotal));
        BigDecimal disc = c.itemDiscountTotal.add(c.cartDiscount);
        view.lblDiscount.setText(disc.signum() == 0 ? "0.00" : "-" + Money.fmt(disc));
        view.lblVatCaption.setText(Settings.pricesIncludeVat() ? "VAT (included)" : "VAT");
        view.lblVat.setText(Money.fmt(c.vatTotal));
        BigDecimal payable = c.grandTotal.subtract(Money.min(c.storeCredit, c.grandTotal));
        view.lblTotal.setText(Settings.currency() + " " + Money.fmt(c.grandTotal)
                + (c.storeCredit.signum() > 0 ? "  → " + Money.fmt(payable) : ""));
        view.btnPay.setEnabled(!c.isEmpty());
        for (int i = 0; i < carts.size(); i++) {
            Cart x = carts.get(i);
            view.cartTabs.setTitleAt(i, x.label + (x.isEmpty() ? "" : " (" + x.items.size() + ")"));
        }
    }

    // ================================================================== payment

    private boolean ensureShift() throws Exception {
        if (!Settings.getBool("require_shift") || Session.shift() != null) {
            return true;
        }
        BigDecimal opening = NumberPadDialog.ask(view, "Open shift", "No cash shift is open.<br>Count the cash in the drawer "
                + "and enter the <b>opening cash</b> to start your shift:", BigDecimal.ZERO, true);
        if (opening == null) {
            return false;
        }
        new ShiftService().open(opening);
        main.refreshShift();
        return true;
    }

    private void pay() {
        Cart c = cart();
        if (c == null || c.isEmpty()) {
            return;
        }
        UiKit.guard(view, () -> {
            if (!ensureShift()) {
                return;
            }
            c.recalculate();
            PaymentDialog d = new PaymentDialog(UiKit.window(view), c);
            d.setVisible(true);
            if (!d.isConfirmed()) {
                return;
            }
            Sale sale = sales.checkout(c, d.payments(), null);
            closeCurrentCart();
            reloadCatalogue();
            main.refreshAlerts();
            view.lblStatus.setText("Sale " + sale.invoiceNo + " completed. Total " + Money.cur(sale.grandTotal)
                    + (sale.changeAmount.signum() > 0 ? ", change " + Money.cur(sale.changeAmount) : ""));
            view.lblStatus.setForeground(UiKit.SUCCESS);
            if (d.receiptFormat() != null) {
                ReceiptPreviewDialog.show(view, sale, d.receiptFormat(), false);
            } else {
                UiKit.info(view, "Sale " + sale.invoiceNo + " completed."
                        + (sale.changeAmount.signum() > 0 ? "\nChange to return: " + Money.cur(sale.changeAmount) : ""));
            }
            focusScan();
        });
    }

    private void reprintLast() {
        UiKit.guard(view, () -> {
            Integer id = sales.lastSaleId();
            if (id == null) {
                UiKit.info(view, "You have no sales yet.");
                return;
            }
            ReceiptPreviewDialog.show(view, sales.load(id), Settings.get("receipt_size", "80"), true);
        });
    }
}
