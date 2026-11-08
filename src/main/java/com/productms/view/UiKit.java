package com.productms.view;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.ui.FlatLineBorder;
import com.productms.util.AppException;
import com.productms.util.Money;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SpinnerDateModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Shared look and feel: theme, colours, buttons, cards, forms, tables and dialogs.
 */
public final class UiKit {

    public static final Color PRIMARY = new Color(0x2563EB);
    public static final Color SUCCESS = new Color(0x16A34A);
    public static final Color DANGER = new Color(0xDC2626);
    public static final Color WARNING = new Color(0xD97706);
    public static final Color INFO = new Color(0x0891B2);
    public static final Color PURPLE = new Color(0x7C3AED);
    public static final Color SLATE = new Color(0x475569);
    public static final Color SIDEBAR = new Color(0x0F172A);
    public static final Color SIDEBAR_HOVER = new Color(0x1E293B);

    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH);
    public static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd-MM-yyyy hh:mm a", Locale.ENGLISH);

    private UiKit() {
    }

    // ------------------------------------------------------------------ theme

    public static void applyTheme(boolean dark) {
        Map<String, String> extra = new HashMap<>();
        extra.put("@accentColor", "#2563EB");
        FlatLaf.setGlobalExtraDefaults(extra);
        UIManager.put("defaultFont", new Font("Segoe UI", Font.PLAIN, 13));
        UIManager.put("Button.arc", 10);
        UIManager.put("Component.arc", 10);
        UIManager.put("TextComponent.arc", 10);
        UIManager.put("CheckBox.arc", 6);
        UIManager.put("ProgressBar.arc", 10);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.innerFocusWidth", 0);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
        UIManager.put("ScrollBar.width", 11);
        UIManager.put("Table.rowHeight", 30);
        UIManager.put("Table.showHorizontalLines", true);
        UIManager.put("Table.intercellSpacing", new Dimension(0, 1));
        UIManager.put("TableHeader.height", 34);
        UIManager.put("TabbedPane.tabHeight", 38);
        UIManager.put("TabbedPane.selectedBackground", null);
        UIManager.put("TabbedPane.showTabSeparators", false);
        UIManager.put("OptionPane.buttonMinimumWidth", 90);
        UIManager.put("TitlePane.unifiedBackground", true);
        if (dark) {
            FlatDarkLaf.setup();
        } else {
            FlatLightLaf.setup();
        }
    }

    public static void switchTheme(boolean dark) {
        applyTheme(dark);
        FlatLaf.updateUI();
    }

    public static boolean isDark() {
        return FlatLaf.isLafDark();
    }

    public static Color cardBg() {
        Color c = UIManager.getColor(isDark() ? "Panel.background" : "Table.background");
        return isDark() ? c.brighter() : c;
    }

    public static Color pageBg() {
        return isDark() ? UIManager.getColor("Panel.background") : new Color(0xF1F5F9);
    }

    public static Color muted() {
        return UIManager.getColor("Label.disabledForeground");
    }

    public static Color borderColor() {
        return UIManager.getColor("Component.borderColor");
    }

    // ------------------------------------------------------------------ components

    public static JButton button(String text, Color bg) {
        JButton b = new JButton(text);
        if (bg != null) {
            b.setBackground(bg);
            b.setForeground(Color.WHITE);
            b.setFont(b.getFont().deriveFont(Font.BOLD));
            b.putClientProperty("JButton.buttonType", "roundRect");
            b.setBorderPainted(false);
        }
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setFocusable(true);
        return b;
    }

    public static JButton primary(String text) {
        return button(text, PRIMARY);
    }

    public static JButton success(String text) {
        return button(text, SUCCESS);
    }

    public static JButton danger(String text) {
        return button(text, DANGER);
    }

    public static JButton plain(String text) {
        return button(text, null);
    }

    public static JButton big(JButton b, int height) {
        b.setFont(b.getFont().deriveFont(Font.BOLD, 15f));
        b.setPreferredSize(new Dimension(b.getPreferredSize().width + 20, height));
        return b;
    }

    public static JTextField search(String placeholder) {
        JTextField f = new JTextField();
        f.putClientProperty("JTextField.placeholderText", placeholder);
        f.putClientProperty("JTextField.showClearButton", true);
        return f;
    }

    public static JTextField field(String placeholder) {
        JTextField f = new JTextField();
        f.putClientProperty("JTextField.placeholderText", placeholder);
        return f;
    }

    public static JLabel title(String text, float size) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, size));
        return l;
    }

    public static JLabel mutedLabel(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(muted());
        return l;
    }

    public static Border cardBorder() {
        return new FlatLineBorder(new Insets(14, 16, 14, 16), borderColor(), 1, 14);
    }

    /** A rounded white card. */
    public static JPanel card(java.awt.LayoutManager layout) {
        JPanel p = new JPanel(layout) {
            @Override
            public void updateUI() {
                super.updateUI();
                setBackground(cardBg());
                setBorder(cardBorder());
            }
        };
        p.setBackground(cardBg());
        p.setBorder(cardBorder());
        return p;
    }

    /** A page with a title row (and optional actions on the right). */
    public static JPanel page(String title, String subtitle, JComponent actions, JComponent content) {
        JPanel page = new JPanel(new BorderLayout(0, 14)) {
            @Override
            public void updateUI() {
                super.updateUI();
                setBackground(pageBg());
            }
        };
        page.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));
        page.setBackground(pageBg());
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        JPanel titles = new JPanel(new java.awt.GridLayout(subtitle == null ? 1 : 2, 1));
        titles.setOpaque(false);
        titles.add(title(title, 22f));
        if (subtitle != null) {
            titles.add(mutedLabel(subtitle));
        }
        head.add(titles, BorderLayout.WEST);
        if (actions != null) {
            head.add(actions, BorderLayout.EAST);
        }
        page.add(head, BorderLayout.NORTH);
        page.add(content, BorderLayout.CENTER);
        return page;
    }

    public static JPanel row(Component... comps) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        p.setOpaque(false);
        for (Component c : comps) {
            p.add(c);
        }
        return p;
    }

    public static JPanel rowRight(Component... comps) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        p.setOpaque(false);
        for (Component c : comps) {
            p.add(c);
        }
        return p;
    }

    public static JScrollPane scroll(Component c) {
        JScrollPane sp = new JScrollPane(c);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getVerticalScrollBar().setUnitIncrement(16);
        if (c instanceof JPanel) {
            sp.setOpaque(false);
            sp.getViewport().setOpaque(false);
        }
        return sp;
    }

    /** Table wrapped in a scroll pane inside a card. */
    public static JPanel tableCard(JTable t) {
        JPanel p = card(new BorderLayout());
        p.setBorder(new FlatLineBorder(new Insets(1, 1, 1, 1), borderColor(), 1, 14));
        JScrollPane sp = new JScrollPane(t);
        sp.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        autoFit(t, sp);
        p.add(sp);
        return p;
    }

    /** Columns stretch to fill wide windows, but scroll horizontally instead of squeezing on narrow ones. */
    public static void autoFit(JTable t, JScrollPane sp) {
        Runnable fit = () -> {
            int needed = 0;
            for (int i = 0; i < t.getColumnModel().getColumnCount(); i++) {
                needed += Math.max(t.getColumnModel().getColumn(i).getPreferredWidth(), 60);
            }
            int avail = sp.getViewport().getWidth();
            int mode = avail > 0 && avail < needed ? JTable.AUTO_RESIZE_OFF : JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS;
            if (t.getAutoResizeMode() != mode) {
                t.setAutoResizeMode(mode);
            }
        };
        sp.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                fit.run();
            }
        });
        t.getModel().addTableModelListener(e -> SwingUtilities.invokeLater(fit));
    }

    // ------------------------------------------------------------------ forms

    /** Two-column label/field form using GridBagLayout. */
    public static class Form {
        public final JPanel panel = new JPanel(new GridBagLayout());
        private int row;

        public Form() {
            panel.setOpaque(false);
        }

        public Form add(String label, Component field) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = row;
            c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(5, 0, 5, 12);
            panel.add(new JLabel(label), c);
            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(5, 0, 5, 0);
            panel.add(field, c);
            row++;
            return this;
        }

        public Form full(Component comp) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = row++;
            c.gridwidth = 2;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(5, 0, 5, 0);
            panel.add(comp, c);
            return this;
        }

        public Form section(String text) {
            JLabel l = title(text, 14f);
            l.setBorder(BorderFactory.createEmptyBorder(row == 0 ? 0 : 10, 0, 2, 0));
            return full(l);
        }

        public Form filler() {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = row++;
            c.weighty = 1;
            panel.add(new JLabel(), c);
            return this;
        }
    }

    // ------------------------------------------------------------------ tables

    public static void styleTable(JTable t) {
        t.setRowHeight(30);
        t.setFillsViewportHeight(true);
        t.setShowVerticalLines(false);
        t.setShowHorizontalLines(true);
        t.setAutoCreateRowSorter(true);
        t.getTableHeader().setReorderingAllowed(false);
        t.getTableHeader().setFont(t.getTableHeader().getFont().deriveFont(Font.BOLD));
        t.setDefaultRenderer(BigDecimal.class, new NumberRenderer());
        t.setDefaultRenderer(Integer.class, new NumberRenderer());
        t.setDefaultRenderer(Long.class, new NumberRenderer());
        t.setDefaultRenderer(LocalDateTime.class, new DateRenderer());
        t.setDefaultRenderer(LocalDate.class, new DateRenderer());
        t.setDefaultRenderer(Object.class, new StatusRenderer());
        t.setDefaultRenderer(String.class, new StatusRenderer());
    }

    /** Right-aligned numbers: money with 2 decimals, quantities without trailing zeros. */
    public static class NumberRenderer extends DefaultTableCellRenderer {
        public NumberRenderer() {
            setHorizontalAlignment(SwingConstants.RIGHT);
        }

        @Override
        protected void setValue(Object value) {
            if (value instanceof BigDecimal) {
                BigDecimal b = (BigDecimal) value;
                setText(b.scale() == 3 || b.scale() == 0 ? Money.qty(b) : Money.fmt(b));
            } else {
                setText(value == null ? "" : value.toString());
            }
        }
    }

    public static class DateRenderer extends DefaultTableCellRenderer {
        @Override
        protected void setValue(Object value) {
            if (value instanceof LocalDateTime) {
                setText(((LocalDateTime) value).format(DATE_TIME));
            } else if (value instanceof LocalDate) {
                setText(((LocalDate) value).format(DATE));
            } else {
                setText(value == null ? "" : value.toString());
            }
        }
    }

    /** Colours well-known status words (PAID, DUE, CANCELLED, LOW STOCK...). */
    public static class StatusRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            Color color = statusColor(value == null ? "" : value.toString());
            if (!isSelected) {
                c.setForeground(color != null ? color : table.getForeground());
            }
            c.setFont(color != null ? c.getFont().deriveFont(Font.BOLD) : table.getFont());
            if (value instanceof Boolean) {
                setText((Boolean) value ? "Yes" : "No");
            }
            return c;
        }
    }

    public static Color statusColor(String s) {
        switch (s) {
            case "PAID": case "COMPLETED": case "APPROVED": case "IN WARRANTY": case "CREATE": case "APPLIED": case "ACTIVE": case "IN STOCK": case "OPEN": case "OK": case "IN_STOCK":
            case "STOCK_IN": case "RETURN": case "OPENING": case "ADJUSTMENT_IN": case "SALE_CANCEL":
                return SUCCESS;
            case "DUE": case "CANCELLED": case "OUT OF STOCK": case "EXPIRED": case "INACTIVE": case "DAMAGED": case "DELETED": case "OVERDUE":
            case "REJECTED": case "FAILED": case "WARRANTY EXPIRED": case "ERROR": case "CANCEL": case "LOCKED":
            case "ADJUSTMENT_OUT": case "STOCK_OUT":
                return DANGER;
            case "PARTIAL": case "LOW STOCK": case "PENDING": case "SUBMITTED": case "UPDATE": case "MANUAL": case "EXPIRING": case "EXPIRING SOON": case "CLOSED": case "SOLD":
            case "EXCHANGE": case "REFUND": case "DUE_ADJUST":
                return WARNING;
            case "SALE":
                return PRIMARY;
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ dates

    public static JSpinner dateSpinner(LocalDate initial) {
        JSpinner s = new JSpinner(new SpinnerDateModel());
        s.setEditor(new JSpinner.DateEditor(s, "dd-MM-yyyy"));
        s.setValue(Date.from(initial.atStartOfDay(ZoneId.systemDefault()).toInstant()));
        s.setPreferredSize(new Dimension(125, s.getPreferredSize().height));
        return s;
    }

    public static LocalDate dateOf(JSpinner s) {
        return ((Date) s.getValue()).toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    public static void setDate(JSpinner s, LocalDate d) {
        s.setValue(Date.from(d.atStartOfDay(ZoneId.systemDefault()).toInstant()));
    }

    /** Parses an optional dd-MM-yyyy (or yyyy-MM-dd) date; blank returns null. */
    public static LocalDate parseDate(String text, String field) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim().replace('/', '-').replace('.', '-');
        try {
            return t.matches("\\d{4}-\\d{1,2}-\\d{1,2}") ? LocalDate.parse(t, DateTimeFormatter.ofPattern("yyyy-M-d"))
                    : LocalDate.parse(t, DateTimeFormatter.ofPattern("d-M-yyyy"));
        } catch (DateTimeParseException e) {
            throw new AppException(field + ": \"" + text.trim() + "\" is not a valid date. Use dd-mm-yyyy.");
        }
    }

    // ------------------------------------------------------------------ dialogs & errors

    @FunctionalInterface
    public interface Work {
        void run() throws Exception;
    }

    /** Runs work and shows business / database errors as friendly messages. */
    public static void guard(Component parent, Work w) {
        try {
            w.run();
        } catch (AppException | IllegalArgumentException e) {
            warn(parent, e.getMessage());
        } catch (SQLException e) {
            error(parent, "Database error: " + e.getMessage()
                    + "\n\nCheck that MySQL is running (XAMPP Control Panel → MySQL → Start).");
        } catch (Exception e) {
            e.printStackTrace();
            error(parent, "Unexpected error: " + e);
        }
    }

    public static void info(Component parent, String msg) {
        JOptionPane.showMessageDialog(parent, msg, "Information", JOptionPane.INFORMATION_MESSAGE);
    }

    public static void warn(Component parent, String msg) {
        JOptionPane.showMessageDialog(parent, msg, "Please check", JOptionPane.WARNING_MESSAGE);
    }

    public static void error(Component parent, String msg) {
        JOptionPane.showMessageDialog(parent, msg, "Error", JOptionPane.ERROR_MESSAGE);
    }

    public static boolean confirm(Component parent, String msg) {
        return JOptionPane.showConfirmDialog(parent, msg, "Confirm", JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION;
    }

    public static String input(Component parent, String msg, String initial) {
        Object v = JOptionPane.showInputDialog(parent, msg, "Input", JOptionPane.QUESTION_MESSAGE, null, null, initial);
        return v == null ? null : v.toString();
    }

    public static Window window(Component c) {
        return c instanceof Window ? (Window) c : SwingUtilities.getWindowAncestor(c);
    }

    /** Binds a key (e.g. "F9", "ctrl ENTER") anywhere in the component's window. */
    public static void bind(JComponent root, String key, Runnable action) {
        String name = "bind-" + key;
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), name);
        root.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (root.isShowing()) {
                    action.run();
                }
            }
        });
    }

    /** Small coloured pill label. */
    public static JLabel badge(String text, Color color) {
        JLabel l = new JLabel(text);
        l.setOpaque(true);
        l.setForeground(Color.WHITE);
        l.setBackground(color);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 11f));
        l.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        return l;
    }
}
