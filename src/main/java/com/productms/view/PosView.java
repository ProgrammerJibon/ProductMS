package com.productms.view;

import com.productms.model.CartItem;
import com.productms.model.Variant;
import com.productms.util.Money;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;

/**
 * Point of Sale screen. Left: scan/search and touch product grid. Right: multiple carts,
 * customer, price mode, cart lines, totals and payment.
 */
public class PosView extends JPanel {

    public final JTextField txtScan = UiKit.search("Scan barcode, type SKU / name, or qty*code (e.g. 3*2000000000015)   [F1]");
    public final JComboBox<Object> cmbCategory = new JComboBox<>();
    public final JPanel grid = new JPanel(new GridLayout(0, 4, 10, 10));
    public final JLabel lblGridInfo = UiKit.mutedLabel(" ");
    public final JToggleButton tglAll = new JToggleButton("All", true);
    public final JToggleButton tglFav = new JToggleButton("\u2665 Favourites");
    public final JToggleButton tglFrequent = new JToggleButton("Frequent");
    public final JToggleButton tglRecent = new JToggleButton("Recent");
    public final JComboBox<String> cmbSort = new JComboBox<>(new String[]{"Sort: Name", "Sort: Best selling", "Sort: Custom order",
        "Sort: Price low-high", "Sort: Price high-low"});
    public final JPanel quickBar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));

    public final JTabbedPane cartTabs = new JTabbedPane();
    public final JButton btnNewCart = UiKit.plain("+ New cart (F8)");
    public final JButton btnCustomer = UiKit.plain("Walk-in Customer  (F4)");
    public final JLabel lblCustomerInfo = UiKit.mutedLabel(" ");
    public final JToggleButton tglWholesale = new JToggleButton("Wholesale prices (F7)");
    public final JLabel lblCredit = new JLabel(" ");

    public final RowTableModel<CartItem> cartModel = new RowTableModel<CartItem>()
            .col("Item", String.class, PosView::itemText)
            .col("Qty", String.class, i -> Money.qty(i.quantity) + " " + i.variant.unitShort)
            .col("Price", BigDecimal.class, i -> i.unitPrice)
            .col("Disc.", BigDecimal.class, i -> i.lineDiscountBeforeCart().add(i.cartDiscountShare))
            .col("Total", BigDecimal.class, i -> i.lineTotal);
    public final JTable cartTable = new JTable(cartModel);

    public final JButton btnMinus = UiKit.plain("−");
    public final JButton btnPlus = UiKit.plain("+");
    public final JButton btnQty = UiKit.plain("Qty F2");
    public final JButton btnItemDisc = UiKit.plain("Disc F3");
    public final JButton btnRemove = UiKit.plain("Remove");
    public final JButton btnPrice = UiKit.plain("Price F11");
    public final JButton btnNote = UiKit.plain("Note");
    public final JButton btnFavourite = UiKit.plain("\u2665 Fav");

    public final JLabel lblItems = new JLabel("0 items");
    public final JLabel lblSubtotal = valueLabel();
    public final JLabel lblDiscount = valueLabel();
    public final JLabel lblVat = valueLabel();
    public final JLabel lblVatCaption = new JLabel("VAT");
    public final JLabel lblTotal = new JLabel("0.00", SwingConstants.RIGHT);

    public final JButton btnHold = UiKit.plain("Hold (F5)");
    public final JButton btnHeld = UiKit.plain("Held carts (F6)");
    public final JButton btnCartDisc = UiKit.plain("Cart disc. (F10)");
    public final JButton btnVoid = UiKit.plain("Void cart");
    public final JButton btnReprint = UiKit.plain("Last receipt (Ctrl+P)");
    public final JButton btnDrawer = UiKit.plain("Open drawer");
    public final JButton btnDisplay = UiKit.plain("Customer display");
    public final JButton btnOffline = UiKit.plain("Offline queue");
    public final JButton btnPay = UiKit.big(UiKit.success("PAY  (F9)"), 62);
    public final JLabel lblStatus = UiKit.mutedLabel(" ");

    private static JLabel valueLabel() {
        JLabel l = new JLabel("0.00", SwingConstants.RIGHT);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 14f));
        return l;
    }

    private static String itemText(CartItem i) {
        Variant v = i.variant;
        StringBuilder sb = new StringBuilder("<html><b>").append(esc(v.productName)).append("</b>");
        if (!v.label().isEmpty()) {
            sb.append(" <span style='color:#64748B'>").append(esc(v.label())).append("</span>");
        }
        if (i.wholesaleApplied) {
            sb.append(" <span style='color:#7C3AED'>[wholesale qty]</span>");
        }
        if (i.overridePrice != null) {
            sb.append(" <span style='color:#D97706'>[price changed]</span>");
        }
        if (i.tierApplied && !i.wholesaleApplied) {
            sb.append(" <span style='color:#7C3AED'>[qty price]</span>");
        }
        if (!i.excludedComponents.isEmpty()) {
            sb.append(" <span style='color:#64748B'>[without ").append(i.excludedComponents.size()).append(" part(s)]</span>");
        }
        if (i.note != null && !i.note.isBlank()) {
            sb.append("<br><span style='color:#0891B2;font-size:9px'>Note: ").append(esc(i.note)).append("</span>");
        }
        if (!i.serials.isEmpty()) {
            sb.append("<br><span style='color:#64748B;font-size:9px'>SN: ").append(esc(String.join(", ", i.serials))).append("</span>");
        }
        return sb.append("</html>").toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

    public PosView() {
        super(new BorderLayout());
        setBackground(UiKit.pageBg());

        // ---------------- left: search + product grid
        txtScan.setFont(txtScan.getFont().deriveFont(16f));
        txtScan.setPreferredSize(new Dimension(400, 46));
        cmbCategory.setPreferredSize(new Dimension(220, 46));
        JPanel searchBar = new JPanel(new BorderLayout(10, 0));
        searchBar.setOpaque(false);
        searchBar.add(txtScan);
        JPanel combos = new JPanel(new BorderLayout(8, 0));
        combos.setOpaque(false);
        combos.add(cmbCategory);
        cmbSort.setPreferredSize(new Dimension(170, 46));
        combos.add(cmbSort, BorderLayout.EAST);
        searchBar.add(combos, BorderLayout.EAST);
        javax.swing.ButtonGroup views = new javax.swing.ButtonGroup();
        for (JToggleButton t : new JToggleButton[]{tglAll, tglFav, tglFrequent, tglRecent}) {
            views.add(t);
            t.setFocusable(false);
        }
        JPanel viewRow = new JPanel(new BorderLayout(10, 0));
        viewRow.setOpaque(false);
        viewRow.add(UiKit.row(tglAll, tglFav, tglFrequent, tglRecent), BorderLayout.WEST);
        quickBar.setOpaque(false);
        viewRow.add(quickBar, BorderLayout.CENTER);
        JPanel topLeft = new JPanel(new BorderLayout(0, 8));
        topLeft.setOpaque(false);
        topLeft.add(searchBar, BorderLayout.NORTH);
        topLeft.add(viewRow, BorderLayout.SOUTH);

        grid.setOpaque(false);
        JPanel gridHolder = new JPanel(new BorderLayout());
        gridHolder.setOpaque(false);
        gridHolder.add(grid, BorderLayout.NORTH);
        JPanel left = new JPanel(new BorderLayout(0, 10));
        left.setOpaque(false);
        left.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 8));
        left.add(topLeft, BorderLayout.NORTH);
        left.add(UiKit.scroll(gridHolder));
        JPanel leftSouth = new JPanel(new BorderLayout(0, 2));
        leftSouth.setOpaque(false);
        leftSouth.add(lblGridInfo, BorderLayout.NORTH);
        JLabel keys = UiKit.mutedLabel("<html><div style='width:560px'>F1 search · F2 qty · F3 disc · F4 customer · F5 hold · F6 held · F7 wholesale · "
                + "F8 new cart · F9 pay · F10 cart disc · F11 price · Del remove · Ctrl+↑/↓ qty · Ctrl+P reprint · Alt+1..8 quick buttons</div></html>");
        keys.setFont(keys.getFont().deriveFont(11.5f));
        leftSouth.add(keys, BorderLayout.CENTER);
        left.add(leftSouth, BorderLayout.SOUTH);

        // ---------------- right: cart
        UiKit.styleTable(cartTable);
        cartTable.setAutoCreateRowSorter(false);
        cartTable.setRowHeight(42);
        cartTable.getColumnModel().getColumn(0).setPreferredWidth(240);
        cartTable.getColumnModel().getColumn(1).setPreferredWidth(70);
        cartTable.getColumnModel().getColumn(2).setPreferredWidth(80);
        cartTable.getColumnModel().getColumn(3).setPreferredWidth(70);
        cartTable.getColumnModel().getColumn(4).setPreferredWidth(90);
        cartTable.getColumnModel().getColumn(1).setCellRenderer(new UiKit.NumberRenderer());

        JPanel tabsRow = new JPanel(new BorderLayout(8, 0));
        tabsRow.setOpaque(false);
        cartTabs.putClientProperty("JTabbedPane.tabType", "card");
        cartTabs.setPreferredSize(new Dimension(300, 38));
        tabsRow.add(cartTabs);
        tabsRow.add(btnNewCart, BorderLayout.EAST);

        JPanel customerRow = new JPanel(new BorderLayout(8, 4));
        customerRow.setOpaque(false);
        btnCustomer.setHorizontalAlignment(SwingConstants.LEFT);
        btnCustomer.setFont(btnCustomer.getFont().deriveFont(Font.BOLD));
        customerRow.add(btnCustomer);
        customerRow.add(tglWholesale, BorderLayout.EAST);
        JPanel custInfo = new JPanel(new BorderLayout());
        custInfo.setOpaque(false);
        custInfo.add(lblCustomerInfo, BorderLayout.WEST);
        lblCredit.setForeground(UiKit.PURPLE);
        lblCredit.setFont(lblCredit.getFont().deriveFont(Font.BOLD));
        custInfo.add(lblCredit, BorderLayout.EAST);
        customerRow.add(custInfo, BorderLayout.SOUTH);

        for (JButton b : new JButton[]{btnMinus, btnPlus}) {
            b.setFont(b.getFont().deriveFont(Font.BOLD, 18f));
            b.setPreferredSize(new Dimension(48, 38));
        }
        JPanel lineButtons = new JPanel(new GridLayout(2, 4, 6, 6));
        lineButtons.setOpaque(false);
        for (JButton b : new JButton[]{btnMinus, btnPlus, btnQty, btnItemDisc, btnPrice, btnNote, btnFavourite, btnRemove}) {
            b.setFocusable(false);
            lineButtons.add(b);
        }

        JPanel totals = new JPanel(new GridLayout(0, 2, 6, 4));
        totals.setOpaque(false);
        totals.add(lblItems);
        totals.add(new JLabel(""));
        totals.add(new JLabel("Subtotal"));
        totals.add(lblSubtotal);
        totals.add(new JLabel("Discount"));
        totals.add(lblDiscount);
        totals.add(lblVatCaption);
        totals.add(lblVat);
        JPanel totalBox = new JPanel(new BorderLayout());
        totalBox.setBackground(new Color(0x0F172A));
        totalBox.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));
        JLabel tl = new JLabel("TOTAL");
        tl.setForeground(new Color(0x94A3B8));
        tl.setFont(tl.getFont().deriveFont(Font.BOLD, 15f));
        lblTotal.setForeground(Color.WHITE);
        lblTotal.setFont(lblTotal.getFont().deriveFont(Font.BOLD, 32f));
        totalBox.add(tl, BorderLayout.WEST);
        totalBox.add(lblTotal, BorderLayout.EAST);

        JPanel actions = new JPanel(new GridLayout(3, 3, 6, 6));
        actions.setOpaque(false);
        for (JButton b : new JButton[]{btnHold, btnHeld, btnCartDisc, btnVoid, btnReprint, btnDrawer, btnDisplay, btnOffline}) {
            b.setFocusable(false);
            actions.add(b);
        }
        actions.add(new JLabel());
        btnPay.setFont(btnPay.getFont().deriveFont(Font.BOLD, 22f));
        btnPay.setFocusable(false);

        JPanel bottom = new JPanel(new BorderLayout(0, 10));
        bottom.setOpaque(false);
        bottom.add(lineButtons, BorderLayout.NORTH);
        JPanel mid = new JPanel(new BorderLayout(0, 8));
        mid.setOpaque(false);
        mid.add(totals, BorderLayout.NORTH);
        mid.add(totalBox, BorderLayout.CENTER);
        mid.add(actions, BorderLayout.SOUTH);
        bottom.add(mid, BorderLayout.CENTER);
        JPanel paySouth = new JPanel(new BorderLayout(0, 6));
        paySouth.setOpaque(false);
        paySouth.add(btnPay, BorderLayout.CENTER);
        paySouth.add(lblStatus, BorderLayout.SOUTH);
        bottom.add(paySouth, BorderLayout.SOUTH);

        JPanel cartCard = UiKit.card(new BorderLayout(0, 10));
        JPanel cartTop = new JPanel(new BorderLayout(0, 8));
        cartTop.setOpaque(false);
        cartTop.add(tabsRow, BorderLayout.NORTH);
        cartTop.add(customerRow, BorderLayout.SOUTH);
        cartCard.add(cartTop, BorderLayout.NORTH);
        javax.swing.JScrollPane sp = new javax.swing.JScrollPane(cartTable);
        cartCard.add(sp);
        cartCard.add(bottom, BorderLayout.SOUTH);

        JPanel right = new JPanel(new BorderLayout());
        right.setOpaque(false);
        right.setBorder(BorderFactory.createEmptyBorder(14, 8, 14, 16));
        right.setPreferredSize(new Dimension(560, 0));
        right.add(cartCard);

        add(left);
        add(right, BorderLayout.EAST);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        setBackground(UiKit.pageBg());
    }

    /** Rebuilds the product tiles. Each entry is the list of variants of one product. */
    public void setTiles(List<List<Variant>> products, Consumer<List<Variant>> onClick) {
        grid.removeAll();
        int cols = Math.max(2, Math.min(6, (getWidth() - 600) / 175));
        ((GridLayout) grid.getLayout()).setColumns(cols);
        for (List<Variant> vs : products) {
            grid.add(new Tile(vs, onClick));
        }
        grid.revalidate();
        grid.repaint();
    }

    /** A touch-friendly product tile. */
    private static class Tile extends JPanel {
        Tile(List<Variant> vs, Consumer<List<Variant>> onClick) {
            super(new BorderLayout(0, 4));
            Variant first = vs.get(0);
            BigDecimal avail = BigDecimal.ZERO;
            BigDecimal min = first.retailPrice;
            BigDecimal max = first.retailPrice;
            for (Variant v : vs) {
                avail = avail.add(v.available);
                min = Money.min(min, v.retailPrice);
                max = Money.max(max, v.retailPrice);
            }
            boolean out = avail.signum() <= 0;
            boolean low = !out && vs.stream().anyMatch(Variant::isLowStock);
            setBackground(UiKit.cardBg());
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 3, 0, out ? UiKit.DANGER : low ? UiKit.WARNING : UiKit.SUCCESS),
                    BorderFactory.createCompoundBorder(UiKit.cardBorder(), BorderFactory.createEmptyBorder(-4, -4, -4, -4))));
            setPreferredSize(new Dimension(160, 112));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            JLabel name = new JLabel("<html><b>" + esc(first.productName) + "</b></html>");
            name.setFont(name.getFont().deriveFont(13f));
            name.setVerticalAlignment(SwingConstants.TOP);
            String sub = vs.size() > 1 ? vs.size() + " variants" : (first.label().isEmpty() ? first.sku : first.label());
            if (first.isBundle()) {
                sub = "Combo";
            }
            JLabel subL = UiKit.mutedLabel(sub);
            subL.setFont(subL.getFont().deriveFont(11f));
            JLabel price = new JLabel(min.compareTo(max) == 0 ? Money.fmt(min) : Money.fmt(min) + "+");
            price.setFont(price.getFont().deriveFont(Font.BOLD, 15f));
            price.setForeground(UiKit.PRIMARY);
            JLabel stock = new JLabel(out ? "Out of stock" : Money.qty(avail) + " " + first.unitShort);
            stock.setFont(stock.getFont().deriveFont(Font.BOLD, 11f));
            stock.setForeground(out ? UiKit.DANGER : low ? UiKit.WARNING : UiKit.SUCCESS);
            JPanel bottom = new JPanel(new BorderLayout());
            bottom.setOpaque(false);
            bottom.add(price, BorderLayout.WEST);
            bottom.add(stock, BorderLayout.EAST);
            JPanel top = new JPanel(new BorderLayout());
            top.setOpaque(false);
            top.add(name, BorderLayout.CENTER);
            top.add(subL, BorderLayout.SOUTH);
            add(top, BorderLayout.CENTER);
            add(bottom, BorderLayout.SOUTH);
            BigDecimal reserved = BigDecimal.ZERO;
            for (Variant v : vs) {
                reserved = reserved.add(v.reservedQty);
            }
            setToolTipText("<html><b>" + esc(first.productName) + "</b>" + (first.brandName != null ? " - " + esc(first.brandName) : "")
                    + (first.location != null ? "<br>Shelf: " + esc(first.location) : "")
                    + (reserved.signum() > 0 ? "<br>Reserved for held carts: " + Money.qty(reserved) : "")
                    + (first.tiers.isEmpty() ? "" : "<br>Quantity prices available") + "</html>");
            if (vs.stream().anyMatch(v -> v.favourite)) {
                name.setText("<html><b><span style='color:#E11D48'>♥</span> " + esc(first.productName) + "</b></html>");
            }
            Color normal = getBackground();
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    onClick.accept(vs);
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    setBackground(UiKit.isDark() ? normal.brighter() : new Color(0xEFF6FF));
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    setBackground(normal);
                }
            });
            if (out) {
                name.setForeground(UiKit.muted());
            }
        }
    }
}
