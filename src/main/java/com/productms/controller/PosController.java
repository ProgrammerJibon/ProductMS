package com.productms.controller;

import com.productms.dao.CustomerDAO;
import com.productms.dao.HeldCartDAO;
import com.productms.dao.MasterDataDAO;
import com.productms.dao.ProductDAO;
import com.productms.db.Jdbc;
import com.productms.model.BundleItem;
import com.productms.model.Cart;
import com.productms.model.CartItem;
import com.productms.model.Category;
import com.productms.model.Customer;
import com.productms.model.Payment;
import com.productms.model.Sale;
import com.productms.model.Variant;
import com.productms.service.AuditService;
import com.productms.service.HardwareService;
import com.productms.service.OfflineQueue;
import com.productms.service.ReceiptService;
import com.productms.service.SchedulerService;
import com.productms.service.SaleService;
import com.productms.service.ShiftService;
import com.productms.util.AppException;
import com.productms.util.ApprovalRequiredException;
import com.productms.util.Money;
import com.productms.util.Session;
import com.productms.util.Settings;
import com.productms.view.CustomerDisplay;
import com.productms.view.CustomerPickerDialog;
import com.productms.view.HeldCartsDialog;
import com.productms.view.ManagerApproval;
import com.productms.view.NumberPadDialog;
import com.productms.view.PaymentDialog;
import com.productms.view.PosView;
import com.productms.view.ReceiptPreviewDialog;
import com.productms.view.RowTableModel;
import com.productms.view.SerialPickerDialog;
import com.productms.view.UiKit;
import com.productms.view.VariantChooserDialog;
import java.awt.BorderLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Point of Sale: scanning (barcode, SKU, pack, scale and IMEI codes, qty*code), product grid with
 * favourites / frequent / recent views and quick buttons, multiple carts, quantity, discount and
 * price-override rules with manager approval, customers with credit warnings, hold / resume with
 * reservations, payment, offline fallback, cash drawer, customer display and receipts.
 */
public class PosController implements Module {

    private final PosView view = new PosView();
    private final ProductDAO products = new ProductDAO();
    private final CustomerDAO customers = new CustomerDAO();
    private final HeldCartDAO held = new HeldCartDAO();
    private final SaleService sales = new SaleService();
    private final MainController main;
    private final List<Cart> carts = new ArrayList<>();
    private int cartCounter;
    private boolean switching;
    private boolean paying;
    private List<Variant> catalogue = new ArrayList<>();
    private List<List<Variant>> visibleTiles = new ArrayList<>();
    private List<Integer> recentIds = new ArrayList<>();

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
        view.cmbSort.addActionListener(e -> UiKit.guard(view, this::reloadCatalogue));
        for (javax.swing.JToggleButton t : new javax.swing.JToggleButton[]{view.tglAll, view.tglFav, view.tglFrequent, view.tglRecent}) {
            t.addActionListener(e -> refreshGrid());
        }
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
        view.btnPrice.addActionListener(e -> overridePrice());
        view.btnNote.addActionListener(e -> lineNote());
        view.btnFavourite.addActionListener(e -> toggleFavourite());
        view.btnRemove.addActionListener(e -> removeLine());
        view.btnHold.addActionListener(e -> hold());
        view.btnHeld.addActionListener(e -> resumeHeld());
        view.btnCartDisc.addActionListener(e -> cartDiscount());
        view.btnVoid.addActionListener(e -> voidCart());
        view.btnReprint.addActionListener(e -> reprintLast());
        view.btnDrawer.addActionListener(e -> openDrawer());
        view.btnDisplay.addActionListener(e -> toggleDisplay());
        view.btnOffline.addActionListener(e -> offlineQueue());
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
        UiKit.bind(view, "F11", this::overridePrice);
        UiKit.bind(view, "ctrl P", this::reprintLast);
        UiKit.bind(view, "ctrl UP", () -> step(1));
        UiKit.bind(view, "ctrl DOWN", () -> step(-1));
        for (int i = 1; i <= 8; i++) {
            int n = i;
            UiKit.bind(view, "alt " + i, () -> quickButton(n - 1));
        }
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
        SchedulerService.setAfterSync(() -> javax.swing.SwingUtilities.invokeLater(this::refreshOfflineButton));
        new Timer(30_000, e -> refreshOfflineButton()).start();
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
        String sort = Settings.get("pos_tile_sort", "NAME");
        if (view.cmbSort.getSelectedIndex() == 0 && !"NAME".equals(sort)) {
            view.cmbSort.setSelectedIndex("POPULAR".equals(sort) ? 1 : "CUSTOM".equals(sort) ? 2 : 0);
        }
        reloadCatalogue();
        refreshCart();
        refreshOfflineButton();
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

    private int maxCarts() {
        return Math.max(1, Math.min(20, Settings.getInt("max_carts", 8)));
    }

    // ================================================================== catalogue / grid

    private void reloadCatalogue() throws Exception {
        ProductDAO.Filter f = new ProductDAO.Filter();
        f.sellableOnly = true;
        f.limit = 5000;
        f.sort = new String[]{"NAME", "BEST_SELLING", "CUSTOM", "PRICE_ASC", "PRICE_DESC"}[Math.max(0, view.cmbSort.getSelectedIndex())];
        catalogue = products.search(f);
        recentIds = Jdbc.list("SELECT si.variant_id FROM sale_items si JOIN sales s ON s.sale_id = si.sale_id "
                + "WHERE s.status = 'COMPLETED' GROUP BY si.variant_id ORDER BY MAX(si.sale_item_id) DESC LIMIT 40", rs -> rs.getInt(1));
        buildQuickBar();
        refreshGrid();
    }

    private List<Variant> favourites() {
        return catalogue.stream().filter(v -> v.favourite)
                .sorted(Comparator.comparingInt((Variant v) -> v.posOrder == 0 ? Integer.MAX_VALUE : v.posOrder)
                        .thenComparing(v -> v.productName)).limit(8).collect(Collectors.toList());
    }

    private void buildQuickBar() {
        view.quickBar.removeAll();
        List<Variant> fav = favourites();
        for (int i = 0; i < fav.size(); i++) {
            Variant v = fav.get(i);
            String name = v.productName.length() > 14 ? v.productName.substring(0, 13) + "…" : v.productName;
            JButton b = UiKit.plain((i + 1) + " " + name);
            b.setToolTipText("Alt+" + (i + 1) + ": " + v.displayName() + " - " + Money.cur(v.retailPrice));
            b.setFocusable(false);
            b.addActionListener(e -> UiKit.guard(view, () -> add(v, null, null, null)));
            view.quickBar.add(b);
        }
        view.quickBar.revalidate();
        view.quickBar.repaint();
    }

    private void quickButton(int index) {
        List<Variant> fav = favourites();
        if (index < fav.size()) {
            UiKit.guard(view, () -> add(fav.get(index), null, null, null));
        }
    }

    private void refreshGrid() {
        String q = view.txtScan.getText().trim().toLowerCase();
        Object cat = view.cmbCategory.getSelectedItem();
        Integer catId = cat instanceof Category ? ((Category) cat).categoryId : null;
        String catName = cat instanceof Category ? ((Category) cat).displayName() : null;
        List<Variant> source = new ArrayList<>(catalogue);
        if (view.tglFav.isSelected()) {
            source.removeIf(v -> !v.favourite);
        } else if (view.tglFrequent.isSelected()) {
            source.removeIf(v -> v.soldLast30.signum() <= 0);
            source.sort(Comparator.comparing((Variant v) -> v.soldLast30).reversed());
        } else if (view.tglRecent.isSelected()) {
            Map<Integer, Integer> order = new LinkedHashMap<>();
            for (int i = 0; i < recentIds.size(); i++) {
                order.put(recentIds.get(i), i);
            }
            source.removeIf(v -> !order.containsKey(v.variantId));
            source.sort(Comparator.comparingInt(v -> order.get(v.variantId)));
        }
        Map<Integer, List<Variant>> groups = new LinkedHashMap<>();
        for (Variant v : source) {
            if (catId != null && (v.categoryName == null || !(catId.equals(v.categoryId) || v.categoryName.startsWith(catName + " ›")))) {
                continue;
            }
            if (!q.isEmpty() && !(v.productName.toLowerCase().contains(q) || v.sku.toLowerCase().contains(q)
                    || (v.barcode != null && v.barcode.equals(q)) || v.label().toLowerCase().contains(q)
                    || (v.brandName != null && v.brandName.toLowerCase().contains(q))
                    || (v.tags != null && v.tags.toLowerCase().contains(q)))) {
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
            UiKit.guard(view, () -> add(v, null, null, null));
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
            // weighed (scale) barcode: <prefix><PLU 5><grams 5><check>
            String prefix = Settings.get("scale_barcode_prefix", "21");
            if (code.matches("\\d{13}") && !prefix.isBlank() && code.startsWith(prefix) && prefix.length() <= 2) {
                Variant scale = products.findByPlu(code.substring(2, 7));
                if (scale != null) {
                    BigDecimal kg = new BigDecimal(code.substring(7, 12)).divide(new BigDecimal("1000"), 3, RoundingMode.HALF_UP);
                    add(scale, kg, null, "scale " + Money.qty(kg) + " " + scale.unitShort);
                    view.txtScan.setText("");
                    return;
                }
            }
            ProductDAO.CodeMatch match = products.findByCode(code);
            if (match != null) {
                BigDecimal q = (qty == null ? BigDecimal.ONE : qty).multiply(match.quantity);
                add(match.variant, match.packName == null && qty == null ? null : q, null,
                        match.packName == null ? null : (qty == null ? "1" : Money.qty(qty)) + " " + match.packName);
                view.txtScan.setText("");
                return;
            }
            Variant v = products.findBySerial(code);
            if (v != null) {
                add(v, BigDecimal.ONE, new ArrayList<>(List.of(code)), null);
                view.txtScan.setText("");
                return;
            }
            refreshGrid();
            if (visibleTiles.size() == 1) {
                view.txtScan.setText("");
                tileClicked(visibleTiles.get(0));
                return;
            }
            java.awt.Toolkit.getDefaultToolkit().beep();
            status("No product with barcode / SKU \"" + code + "\"" + (visibleTiles.isEmpty() ? "." : " - "
                    + visibleTiles.size() + " matches, pick one from the grid."), UiKit.DANGER);
            view.txtScan.selectAll();
        });
    }

    private void status(String text, java.awt.Color color) {
        view.lblStatus.setText(text);
        view.lblStatus.setForeground(color);
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
                    + (fresh.reservedQty.signum() > 0 ? "\nReserved for held carts: " + Money.qty(fresh.reservedQty) : "")
                    + "\nYou can add at most " + Money.qty(canAdd) + " more."
                    + (fresh.trackBatch ? "\n(Expired / near-expiry batches are not counted.)" : ""));
        }
    }

    /** Fresh product data, or the cached copy when the database is offline. */
    private Variant fresh(Variant v) throws SQLException {
        try {
            return products.findVariant(v.variantId);
        } catch (SQLException e) {
            if (OfflineQueue.isConnectionError(e) && Settings.getBool("offline_mode_enabled")) {
                status("Database offline - using cached product data.", UiKit.WARNING);
                for (Variant c : catalogue) {
                    if (c.variantId == v.variantId) {
                        return c;
                    }
                }
            }
            throw e;
        }
    }

    private void add(Variant v, BigDecimal qty, List<String> serials, String packInfo) throws Exception {
        Cart cart = cart();
        if (cart == null) {
            return;
        }
        Variant fresh = fresh(v);
        if (fresh == null || !fresh.isSellable()) {
            throw new AppException(v.displayName() + " is inactive and cannot be sold.");
        }
        List<Integer> excluded = new ArrayList<>();
        if (fresh.isBundle() && fresh.components.stream().anyMatch(b -> b.optional)) {
            excluded = chooseOptionalParts(fresh);
            if (excluded == null) {
                return;
            }
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
        CartItem line = excluded.isEmpty() ? cart.find(fresh.variantId) : null;
        if (fresh.trackSerial) {
            List<String> inCarts = new ArrayList<>();
            for (Cart c : carts) {
                for (CartItem i : c.items) {
                    if (i.variant.variantId == fresh.variantId) {
                        inCarts.addAll(i.serials);
                    }
                }
            }
            List<String> avail = new ArrayList<>(products.serialsInStock(fresh.variantId));
            avail.removeAll(inCarts);
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
            line.excludedComponents = excluded;
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
        warnNearExpiry(fresh);
        status("Added " + (packInfo != null ? packInfo + " = " : "") + Money.qty(qty) + " × " + fresh.displayName(), UiKit.SUCCESS);
    }

    /** Asks which optional parts of a combo the customer wants. Returns excluded ids, or null if cancelled. */
    private List<Integer> chooseOptionalParts(Variant combo) {
        List<JCheckBox> boxes = new ArrayList<>();
        List<Object> content = new ArrayList<>();
        content.add("<html><b>" + combo.productName + "</b><br>Optional parts (untick to leave out):</html>");
        for (BundleItem b : combo.components) {
            if (b.optional) {
                JCheckBox cb = new JCheckBox(b.componentName + "  (" + Money.cur(b.unitPrice.multiply(b.quantity)) + ")", true);
                boxes.add(cb);
                content.add(cb);
            }
        }
        if (JOptionPane.showConfirmDialog(view, content.toArray(), "Combo options", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return null;
        }
        List<Integer> excluded = new ArrayList<>();
        int i = 0;
        for (BundleItem b : combo.components) {
            if (b.optional && !boxes.get(i++).isSelected()) {
                excluded.add(b.componentVariantId);
            }
        }
        return excluded;
    }

    private void warnNearExpiry(Variant v) {
        if (!v.trackBatch) {
            return;
        }
        int warn = Settings.getInt("near_expiry_warn_days", 7);
        UiKit.guard(view, () -> {
            for (com.productms.model.Batch b : products.batches(v.variantId, true)) {
                if (b.expiryDate != null && !b.isExpired()) {
                    if (b.daysToExpiry() <= warn) {
                        status(v.productName + ": the batch being sold (" + b.batchNo + ") expires in " + b.daysToExpiry()
                                + " day(s). Tell the customer.", UiKit.WARNING);
                    }
                    return;
                }
            }
        });
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
                    add(line.variant, null, null, null);
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
            checkAvailable(fresh(line.variant), q, line);
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
            String packHint = "";
            List<Object[]> packs = products.packs(v.variantId);
            if (!packs.isEmpty()) {
                packHint = "<br><span style='color:#64748B'>Packs: " + packs.stream().map(p -> "1 " + p[0] + " = "
                        + Money.qty((BigDecimal) p[1])).collect(Collectors.joining(", ")) + " " + v.unitShort + "</span>";
            }
            BigDecimal q = NumberPadDialog.ask(view, "Quantity", "New quantity for <b>" + v.displayName() + "</b> (" + v.unitShort + "):"
                    + packHint, line.quantity, v.allowDecimal);
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
            checkAvailable(fresh(v), q, line);
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

    private void lineNote() {
        CartItem line = selectedLine();
        if (line == null) {
            return;
        }
        String n = UiKit.input(view, "Note for " + line.variant.displayName() + " (printed on the receipt):", line.note == null ? "" : line.note);
        if (n != null) {
            line.note = n.isBlank() ? null : (n.length() > 250 ? n.substring(0, 250) : n.trim());
            refreshCart();
            select(line);
        }
    }

    private void toggleFavourite() {
        CartItem line = selectedLine();
        if (line == null) {
            UiKit.info(view, "Select a cart line to add / remove it as a favourite quick button.");
            return;
        }
        UiKit.guard(view, () -> {
            boolean fav = !line.variant.favourite;
            products.setFavourite(line.variant.variantId, fav);
            line.variant.favourite = fav;
            reloadCatalogue();
            status(line.variant.displayName() + (fav ? " added to" : " removed from") + " favourites.", UiKit.INFO);
        });
    }

    // ================================================================== prices & discounts

    private BigDecimal personalLimit() {
        return Session.user().maxDiscountPercent != null ? Session.user().maxDiscountPercent : Settings.bd("max_cashier_discount", "10");
    }

    /** Returns the approver (or "" when no approval is needed), or null when refused. */
    private String authorizeDiscount(BigDecimal percent, String what) {
        List<String> reasons = new ArrayList<>();
        BigDecimal shopMax = Settings.bd("max_line_discount_percent", "100");
        if (percent.compareTo(shopMax) > 0 && !Session.isAdmin()) {
            reasons.add(what + " of " + percent.setScale(1, RoundingMode.HALF_UP) + "% is above the shop maximum of " + Money.qty(shopMax) + "%");
        }
        if (!Session.isManager() && percent.compareTo(personalLimit()) > 0) {
            reasons.add(what + " of " + percent.setScale(1, RoundingMode.HALF_UP) + "% is above your limit of " + Money.qty(personalLimit()) + "%");
        }
        if (reasons.isEmpty()) {
            return "";
        }
        return ManagerApproval.ask(view, reasons);
    }

    private void itemDiscount() {
        CartItem line = selectedLine();
        if (line == null) {
            return;
        }
        BigDecimal base = line.gross.subtract(line.productDiscount);
        NumberPadDialog.Discount d = NumberPadDialog.askDiscount(view, "Item discount",
                "Extra discount on <b>" + line.variant.displayName() + "</b><br>Line value: " + Money.cur(base)
                + "<br><span style='color:#64748B'>Your limit: " + Money.qty(personalLimit()) + "%</span>",
                line.itemDiscountValue, "PERCENT".equals(line.itemDiscountType));
        if (d == null) {
            return;
        }
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
        String approver = authorizeDiscount(pct, "Item discount");
        if (approver == null) {
            return;
        }
        line.itemDiscountType = d.percent ? "PERCENT" : "AMOUNT";
        line.itemDiscountValue = d.value;
        if (!approver.isEmpty()) {
            line.approvedBy = approver;
        }
        refreshCart();
        if (!approver.isEmpty() || line.effectiveUnitNet().compareTo(line.variant.minPrice) >= 0
                || line.variant.minPrice.signum() == 0 || Session.isManager()) {
            select(line);
            return;
        }
        String ok = ManagerApproval.ask(view, line.variant.displayName() + " would sell at " + Money.fmt(line.effectiveUnitNet())
                + ", below its minimum price " + Money.fmt(line.variant.minPrice));
        if (ok == null) {
            line.itemDiscountValue = BigDecimal.ZERO;
        } else {
            line.approvedBy = ok;
        }
        refreshCart();
        select(line);
    }

    private void overridePrice() {
        CartItem line = selectedLine();
        if (line == null) {
            return;
        }
        Variant v = line.variant;
        BigDecimal p = NumberPadDialog.ask(view, "Change price", "New unit price for <b>" + v.displayName() + "</b><br>List price: "
                + Money.cur(line.listPrice) + (v.mrp.signum() > 0 ? " · MRP " + Money.cur(v.mrp) : "")
                + (v.minPrice.signum() > 0 ? " · minimum " + Money.cur(v.minPrice) : "")
                + "<br><span style='color:#64748B'>Clear and press OK on the list price to remove the override.</span>",
                line.unitPrice, true);
        if (p == null) {
            return;
        }
        if (!Money.pos(p)) {
            UiKit.warn(view, "The price must be greater than 0.");
            return;
        }
        if (v.mrp.signum() > 0 && p.compareTo(v.mrp) > 0) {
            UiKit.warn(view, "Selling above the MRP (" + Money.cur(v.mrp) + ") is not allowed.");
            return;
        }
        if (p.compareTo(line.listPrice) == 0) {
            line.overridePrice = null;
            refreshCart();
            return;
        }
        List<String> reasons = new ArrayList<>();
        if (!Session.isManager() && !Session.user().canOverridePrice) {
            reasons.add("Price change for " + v.displayName() + ": " + Money.fmt(line.listPrice) + " → " + Money.fmt(p));
        }
        if (!Session.isManager() && v.minPrice.signum() > 0 && p.compareTo(v.minPrice) < 0) {
            reasons.add("New price is below the minimum selling price " + Money.fmt(v.minPrice));
        }
        String approver = reasons.isEmpty() ? Session.user().username : ManagerApproval.ask(view, reasons);
        if (approver == null) {
            return;
        }
        line.overridePrice = Money.r2(p);
        line.approvedBy = approver;
        AuditService.log("PRICE_OVERRIDE", "VARIANT", v.variantId, v.sku + " " + Money.fmt(line.listPrice) + " → " + Money.fmt(p)
                + " in " + cart().label, approver.equals(Session.user().username) ? null : approver);
        refreshCart();
        select(line);
    }

    private void cartDiscount() {
        Cart c = cart();
        if (c == null || c.isEmpty()) {
            return;
        }
        BigDecimal base = c.subtotal.subtract(c.itemDiscountTotal);
        BigDecimal maxPct = Settings.bd("max_cart_discount_percent", "100");
        BigDecimal maxAmt = Settings.bd("max_cart_discount_amount", "0");
        NumberPadDialog.Discount d = NumberPadDialog.askDiscount(view, "Cart discount",
                "Discount on the whole cart<br>Cart value after item discounts: " + Money.cur(base)
                + "<br><span style='color:#64748B'>Shop maximum: " + Money.qty(maxPct) + "%"
                + (maxAmt.signum() > 0 ? " or " + Money.cur(maxAmt) : "") + "</span>",
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
        BigDecimal amount = d.percent ? Money.pct(base, d.value) : d.value;
        List<String> reasons = new ArrayList<>();
        if (!Session.isAdmin() && (pct.compareTo(maxPct) > 0 || (maxAmt.signum() > 0 && amount.compareTo(maxAmt) > 0))) {
            reasons.add("Cart discount " + pct.setScale(1, RoundingMode.HALF_UP) + "% (" + Money.cur(amount) + ") is above the shop maximum");
        }
        if (!Session.isManager() && pct.compareTo(personalLimit()) > 0) {
            reasons.add("Cart discount of " + pct + "% is above your limit of " + Money.qty(personalLimit()) + "%");
        }
        if (!reasons.isEmpty()) {
            String approver = ManagerApproval.ask(view, reasons);
            if (approver == null) {
                return;
            }
            c.approvedBy = approver;
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
            status("Wholesale customer - wholesale prices applied.", UiKit.PURPLE);
        } else if (!cu.isWholesale() && "WHOLESALE".equals(c.priceMode)) {
            c.priceMode = "RETAIL";
        }
        refreshCart();
        List<String> msgs = new ArrayList<>();
        if (cu.overdueInvoices > 0) {
            msgs.add("• " + cu.overdueInvoices + " OVERDUE invoice(s): " + Money.cur(cu.overdueAmount) + " older than "
                    + cu.paymentTermsDays + " days.");
        }
        if (cu.creditLimit != null && cu.due.compareTo(cu.creditLimit) >= 0) {
            msgs.add("• Credit limit reached: owes " + Money.cur(cu.due) + " of " + Money.cur(cu.creditLimit)
                    + ". New due sales need manager approval.");
        } else if (cu.due.signum() > 0) {
            msgs.add("• Current due: " + Money.cur(cu.due) + (cu.creditLimit == null ? "" : " (limit " + Money.cur(cu.creditLimit) + ")"));
        }
        if (cu.storeCredit.signum() > 0) {
            msgs.add("• Store credit available: " + Money.cur(cu.storeCredit) + " (use it at payment).");
        }
        if (cu.notes != null && !cu.notes.isBlank()) {
            msgs.add("• Note: " + cu.notes);
        }
        if (!msgs.isEmpty()) {
            JOptionPane.showMessageDialog(view, cu.name + "\n\n" + String.join("\n", msgs), "Customer information",
                    cu.overdueInvoices > 0 ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
        }
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
        if (carts.size() >= maxCarts()) {
            UiKit.warn(view, "Up to " + maxCarts() + " carts can be open (Settings). Finish, hold or void one first.");
            return;
        }
        UiKit.guard(view, () -> {
            Cart c = new Cart("Cart " + (++cartCounter));
            try {
                c.customer = customers.walkIn();
            } catch (SQLException e) {
                if (!OfflineQueue.isConnectionError(e)) {
                    throw e;
                }
                c.customer = new Customer();
                c.customer.customerId = 1;
                c.customer.name = "Walk-in Customer";
                c.customer.walkIn = true;
            }
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
        if (!c.isEmpty()) {
            AuditService.log("VOID_CART", "CART", c.label, c.items.size() + " line(s), total " + Money.fmt(c.grandTotal), null);
        }
        closeCurrentCart();
    }

    private void hold() {
        Cart c = cart();
        if (c == null || c.isEmpty()) {
            UiKit.warn(view, "The cart is empty.");
            return;
        }
        int hours = Settings.getInt("held_cart_expiry_hours", 24);
        String label = UiKit.input(view, "Name for this held cart (e.g. customer name).\nIt expires after " + hours + " hour(s)"
                + (Settings.getBool("reserve_held_stock") ? "; its stock is reserved until then." : "."),
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
            reloadCatalogue();
            status("Cart \"" + c.label + "\" is on hold. Resume it with F6.", UiKit.INFO);
        });
    }

    private void resumeHeld() {
        Integer id = HeldCartsDialog.pick(view);
        if (id == null) {
            return;
        }
        UiKit.guard(view, () -> {
            Cart current = cart();
            if (current != null && current.isEmpty()) {
                closeCurrentCartSilently();
            }
            if (carts.size() >= maxCarts()) {
                throw new AppException("Too many open carts. Close one first.");
            }
            Cart c = held.resume(id, products, customers);
            if (c == null) {
                UiKit.warn(view, "This held cart no longer exists (it may have expired).");
                return;
            }
            c.heldId = null;
            addCartTab(c);
            reloadCatalogue();
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
        if (i < 0) {
            return;
        }
        switching = true;
        carts.remove(i);
        view.cartTabs.removeTabAt(i);
        switching = false;
    }

    /** Starts a new cart carrying store credit from an exchange (called from Sales). */
    public void startExchange(BigDecimal credit, String returnNo, Customer customer) {
        if (carts.size() >= maxCarts()) {
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
        Customer cu = c.customer;
        view.btnCustomer.setText((cu.walkIn ? "○  " : "●  ") + cu.name + "   (F4 change)");
        String info = cu.walkIn ? "Walk-in sale" : (cu.phone == null ? "" : cu.phone + "  ·  ") + cu.customerType
                + (cu.due.signum() > 0 ? "  ·  due " + Money.cur(cu.due) : "")
                + (cu.creditLimit != null ? "  ·  limit " + Money.cur(cu.creditLimit) : "")
                + (cu.storeCredit.signum() > 0 ? "  ·  credit " + Money.cur(cu.storeCredit) : "");
        view.lblCustomerInfo.setText(info);
        view.lblCustomerInfo.setForeground(cu.overdueInvoices > 0 ? UiKit.DANGER : UiKit.muted());
        view.lblCustomerInfo.setToolTipText(cu.notes);
        view.tglWholesale.setSelected("WHOLESALE".equals(c.priceMode));
        BigDecimal payable = c.grandTotal.subtract(Money.min(c.storeCredit, c.grandTotal));
        if (c.storeCredit.signum() > 0) {
            BigDecimal diff = c.grandTotal.subtract(c.storeCredit);
            view.lblCredit.setText("Exchange credit " + Money.cur(c.storeCredit) + (diff.signum() >= 0 ? " · customer pays " + Money.cur(diff)
                    : " · balance " + Money.cur(diff.negate()) + " (" + Settings.get("exchange_excess_policy", "ACCOUNT").toLowerCase() + ")"));
        } else {
            view.lblCredit.setText(" ");
        }
        view.lblItems.setText(c.items.size() + " line(s), " + Money.qty(c.itemCount()) + " item(s)");
        view.lblSubtotal.setText(Money.fmt(c.subtotal));
        BigDecimal disc = c.itemDiscountTotal.add(c.cartDiscount);
        view.lblDiscount.setText(disc.signum() == 0 ? "0.00" : "-" + Money.fmt(disc));
        view.lblVatCaption.setText(Settings.pricesIncludeVat() ? "VAT (included)" : "VAT");
        view.lblVat.setText(Money.fmt(c.vatTotal));
        view.lblTotal.setText(Settings.currency() + " " + Money.fmt(c.grandTotal)
                + (c.storeCredit.signum() > 0 ? "  → " + Money.fmt(payable) : ""));
        view.btnPay.setEnabled(!c.isEmpty() && !paying);
        for (int i = 0; i < carts.size(); i++) {
            Cart x = carts.get(i);
            view.cartTabs.setTitleAt(i, x.label + (x.isEmpty() ? "" : " (" + x.items.size() + ")"));
        }
        CustomerDisplay.showCart(c);
    }

    private void refreshOfflineButton() {
        try {
            List<OfflineQueue.Entry> q = OfflineQueue.list();
            long pending = q.stream().filter(e -> "PENDING".equals(e.status)).count();
            long failed = q.size() - pending;
            view.btnOffline.setText("Offline queue" + (q.isEmpty() ? "" : " (" + pending + (failed > 0 ? " / " + failed + " failed" : "") + ")"));
            view.btnOffline.setForeground(failed > 0 ? UiKit.DANGER : pending > 0 ? UiKit.WARNING : javax.swing.UIManager.getColor("Button.foreground"));
        } catch (java.io.IOException ignored) {
            // nothing to show
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
        if (c == null || c.isEmpty() || paying) {
            return;
        }
        paying = true;
        view.btnPay.setEnabled(false);
        try {
            UiKit.guard(view, () -> doPay(c));
        } finally {
            paying = false;
            refreshCart();
        }
    }

    private void doPay(Cart c) throws Exception {
        boolean dbUp = true;
        try {
            if (!ensureShift()) {
                return;
            }
        } catch (SQLException e) {
            if (!OfflineQueue.isConnectionError(e)) {
                throw e;
            }
            dbUp = false;
        }
        c.recalculate();
        try {
            c.customer.storeCredit = c.customer.walkIn || !dbUp ? BigDecimal.ZERO : customers.storeCredit(c.customer.customerId);
        } catch (SQLException ignored) {
            dbUp = false;
        }
        PaymentDialog d = new PaymentDialog(UiKit.window(view), c);
        d.setVisible(true);
        if (!d.isConfirmed()) {
            return;
        }
        Sale sale = null;
        while (sale == null) {
            try {
                sale = sales.checkout(c, d.payments(), null);
            } catch (ApprovalRequiredException ex) {
                String approver = ManagerApproval.ask(view, ex.reasons);
                if (approver == null) {
                    return;
                }
                c.approvedBy = approver;
            } catch (SQLException ex) {
                if (!OfflineQueue.isConnectionError(ex) || !Settings.getBool("offline_mode_enabled")) {
                    throw ex;
                }
                sellOffline(c, d);
                return;
            }
        }
        closeCurrentCart();
        reloadCatalogue();
        main.refreshAlerts();
        if (d.hasCash() && Settings.getBool("cash_drawer_enabled")) {
            String err = HardwareService.openCashDrawer();
            if (err != null) {
                status(err, UiKit.WARNING);
            }
        }
        CustomerDisplay.showThanks(sale.grandTotal, sale.changeAmount);
        status("Sale " + sale.invoiceNo + " completed. Total " + Money.cur(sale.grandTotal)
                + (sale.changeAmount.signum() > 0 ? ", change " + Money.cur(sale.changeAmount) : ""), UiKit.SUCCESS);
        printOrPreview(sale, d.receiptFormat(), false);
        focusScan();
    }

    private void sellOffline(Cart c, PaymentDialog d) throws Exception {
        OfflineQueue.checkOfflineAllowed(c, d.payments());
        if (!UiKit.confirm(view, "The database cannot be reached.\n\nSave this sale OFFLINE and print a provisional receipt?\n"
                + "It will be booked automatically when the database is back.")) {
            return;
        }
        String ref = OfflineQueue.enqueue(c, d.payments());
        Sale provisional = OfflineQueue.provisionalSale(c, d.payments(), ref);
        closeCurrentCart();
        refreshOfflineButton();
        CustomerDisplay.showThanks(provisional.grandTotal, provisional.changeAmount);
        status("OFFLINE sale " + ref + " saved. It will sync when the database is available.", UiKit.WARNING);
        printOrPreview(provisional, d.receiptFormat(), false);
    }

    private void printOrPreview(Sale sale, String format, boolean reprint) {
        if (format == null) {
            UiKit.info(view, "Sale " + sale.invoiceNo + " completed."
                    + (sale.changeAmount.signum() > 0 ? "\nChange to return: " + Money.cur(sale.changeAmount) : ""));
            return;
        }
        if (Settings.getBool("auto_print_receipt") && !reprint) {
            UiKit.guard(view, () -> {
                if ("A4".equals(format)) {
                    ReceiptService.printA4(sale);
                } else {
                    int mm = Integer.parseInt(format);
                    ReceiptService.printThermal(ReceiptService.receiptLines(sale, mm, false), mm, "Receipt " + sale.invoiceNo,
                            HardwareService.receiptPrinter() == null);
                }
            });
            if (sale.changeAmount.signum() > 0) {
                UiKit.info(view, "Change to return: " + Money.cur(sale.changeAmount));
            }
            return;
        }
        ReceiptPreviewDialog.show(view, sale, format, reprint);
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

    // ================================================================== hardware & offline queue

    private void openDrawer() {
        String err = HardwareService.openCashDrawer();
        if (err != null) {
            UiKit.warn(view, err);
        } else {
            AuditService.log("OPEN_DRAWER", "SHIFT", Session.shiftId(), "Cash drawer opened without a sale", null);
            status("Cash drawer opened (logged).", UiKit.INFO);
        }
    }

    private void toggleDisplay() {
        UiKit.guard(view, () -> {
            if (!CustomerDisplay.enabled()) {
                Settings.save(java.util.Collections.singletonMap("customer_display", "1"));
                CustomerDisplay.showCart(cart());
            } else if (CustomerDisplay.get().isVisible()) {
                CustomerDisplay.hideDisplay();
                Settings.save(java.util.Collections.singletonMap("customer_display", "0"));
            } else {
                CustomerDisplay.showCart(cart());
            }
        });
    }

    private void offlineQueue() {
        UiKit.guard(view, () -> {
            RowTableModel<OfflineQueue.Entry> m = new RowTableModel<OfflineQueue.Entry>()
                    .col("Reference", String.class, e -> e.ref)
                    .col("Sold at", String.class, e -> e.soldAt == null ? "" : e.soldAt.replace('T', ' ').substring(0, Math.min(19, e.soldAt.length())))
                    .col("Total", BigDecimal.class, e -> e.total)
                    .col("Status", String.class, e -> e.status)
                    .col("Problem", String.class, e -> e.error);
            m.setRows(OfflineQueue.list());
            JTable t = new JTable(m);
            UiKit.styleTable(t);
            JButton sync = UiKit.primary("Sync now");
            JButton retry = UiKit.plain("Retry failed");
            JButton discard = UiKit.danger("Discard failed...");
            JDialog dlg = new JDialog(UiKit.window(view), "Offline sales queue", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
            sync.addActionListener(e -> UiKit.guard(dlg, () -> {
                int n = OfflineQueue.syncAll();
                m.setRows(OfflineQueue.list());
                UiKit.info(dlg, n + " sale(s) booked." + (com.productms.db.Database.isReachable() ? "" : "\nThe database is still offline."));
            }));
            retry.addActionListener(e -> UiKit.guard(dlg, () -> {
                OfflineQueue.Entry en = m.selected(t);
                if (en != null && "FAILED".equals(en.status)) {
                    OfflineQueue.retry(en.file);
                    m.setRows(OfflineQueue.list());
                }
            }));
            discard.addActionListener(e -> UiKit.guard(dlg, () -> {
                OfflineQueue.Entry en = m.selected(t);
                if (en == null || !"FAILED".equals(en.status)) {
                    return;
                }
                if (!Session.isManager()) {
                    throw new AppException("Only a manager can discard an offline sale.");
                }
                String why = UiKit.input(dlg, "Why is " + en.ref + " discarded? (settle the money manually)", "");
                if (why != null) {
                    OfflineQueue.discard(en.file, why);
                    m.setRows(OfflineQueue.list());
                }
            }));
            JPanel p = new JPanel(new BorderLayout(0, 10));
            p.setBorder(javax.swing.BorderFactory.createEmptyBorder(14, 14, 14, 14));
            p.add(UiKit.mutedLabel("<html>Sales taken while the database was unreachable. They are booked automatically every minute; "
                    + "failed ones (e.g. stock sold elsewhere meanwhile) need a manager.</html>"), BorderLayout.NORTH);
            p.add(UiKit.tableCard(t));
            p.add(UiKit.rowRight(discard, retry, sync), BorderLayout.SOUTH);
            dlg.setContentPane(p);
            dlg.setSize(860, 420);
            dlg.setLocationRelativeTo(view);
            dlg.setVisible(true);
            refreshOfflineButton();
        });
    }
}
