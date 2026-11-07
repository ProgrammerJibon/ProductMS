package com.productms.view;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;

/** Main window: dark sidebar navigation, top bar and a card area for the modules. */
public class MainFrame extends JFrame {

    public final JPanel content = new JPanel(new CardLayout());
    public final JLabel lblShift = new JLabel();
    public final JLabel lblClock = new JLabel();
    public final JButton btnAlerts = UiKit.plain("Alerts");
    public final JButton btnTheme = UiKit.plain("Dark mode");
    public final JButton btnLogout = UiKit.plain("Log out");
    public final JLabel lblUser = new JLabel();
    private final JPanel nav = new JPanel();
    private final Map<String, NavItem> items = new LinkedHashMap<>();
    private final JLabel lblPage = UiKit.title("", 15f);
    private NavListener listener;

    /** Called when a navigation item is clicked. */
    public interface NavListener {
        void navigate(String name);
    }

    public MainFrame(String shopName) {
        super(shopName + " - Product Management & POS");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setSize(1366, 800);
        setMinimumSize(new Dimension(1100, 680));
        setLocationRelativeTo(null);
        setExtendedState(MAXIMIZED_BOTH);

        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setBackground(UiKit.SIDEBAR);
        sidebar.setPreferredSize(new Dimension(220, 0));
        JPanel brand = new JPanel(new GridLayout(2, 1));
        brand.setOpaque(false);
        brand.setBorder(BorderFactory.createEmptyBorder(20, 20, 18, 16));
        JLabel logo = new JLabel("■ ProductMS");
        logo.setForeground(Color.WHITE);
        logo.setFont(logo.getFont().deriveFont(Font.BOLD, 19f));
        JLabel sub = new JLabel("<html>" + shopName + "</html>");
        sub.setForeground(new Color(0x94A3B8));
        sub.setFont(sub.getFont().deriveFont(11.5f));
        brand.add(logo);
        brand.add(sub);
        sidebar.add(brand, BorderLayout.NORTH);
        nav.setOpaque(false);
        nav.setLayout(new BoxLayout(nav, BoxLayout.Y_AXIS));
        nav.setBorder(BorderFactory.createEmptyBorder(4, 10, 10, 10));
        sidebar.add(nav, BorderLayout.CENTER);
        JPanel userBox = new JPanel(new BorderLayout(0, 8));
        userBox.setOpaque(false);
        userBox.setBorder(BorderFactory.createEmptyBorder(12, 16, 16, 16));
        lblUser.setForeground(new Color(0xCBD5E1));
        userBox.add(lblUser, BorderLayout.NORTH);
        userBox.add(btnLogout, BorderLayout.SOUTH);
        sidebar.add(userBox, BorderLayout.SOUTH);

        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(8, 22, 8, 16)));
        top.add(lblPage, BorderLayout.WEST);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);
        lblClock.setForeground(UiKit.muted());
        lblShift.setFont(lblShift.getFont().deriveFont(Font.BOLD));
        right.add(lblShift);
        right.add(lblClock);
        right.add(btnAlerts);
        right.add(btnTheme);
        top.add(right, BorderLayout.EAST);

        JPanel main = new JPanel(new BorderLayout());
        main.add(top, BorderLayout.NORTH);
        main.add(content, BorderLayout.CENTER);

        getContentPane().add(sidebar, BorderLayout.WEST);
        getContentPane().add(main, BorderLayout.CENTER);
    }

    public void setNavListener(NavListener l) {
        listener = l;
    }

    public void addNav(String name, String icon, String shortcut) {
        NavItem item = new NavItem(name, icon, shortcut);
        items.put(name, item);
        nav.add(item);
        nav.add(Box.createVerticalStrut(2));
    }

    public void addModule(String name, JComponent view) {
        content.add(view, name);
    }

    public void show(String name) {
        ((CardLayout) content.getLayout()).show(content, name);
        for (NavItem i : items.values()) {
            i.setActive(i.name.equals(name));
        }
        lblPage.setText(name);
    }

    public void setAlertCount(int n) {
        btnAlerts.setText(n == 0 ? "Alerts" : "Alerts (" + n + ")");
        btnAlerts.setForeground(n == 0 ? UIManager.getColor("Button.foreground") : UiKit.DANGER);
    }

    /** Sidebar entry with hover and active states. */
    private class NavItem extends JComponent {
        final String name;
        final String icon;
        final String shortcut;
        boolean active;
        boolean hover;

        NavItem(String name, String icon, String shortcut) {
            this.name = name;
            this.icon = icon;
            this.shortcut = shortcut;
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            setPreferredSize(new Dimension(200, 40));
            setAlignmentX(LEFT_ALIGNMENT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(shortcut == null ? name : name + "  (" + shortcut + ")");
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (listener != null) {
                        listener.navigate(NavItem.this.name);
                    }
                }
            });
        }

        void setActive(boolean a) {
            active = a;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            if (active || hover) {
                g2.setColor(active ? UiKit.PRIMARY : UiKit.SIDEBAR_HOVER);
                g2.fillRoundRect(0, 2, getWidth(), getHeight() - 4, 12, 12);
            }
            g2.setFont(UIManager.getFont("defaultFont").deriveFont(Font.BOLD, 13.5f));
            g2.setColor(active ? Color.WHITE : new Color(0xCBD5E1));
            int y = (getHeight() + g2.getFontMetrics().getAscent()) / 2 - 2;
            g2.drawString(icon, 14, y);
            g2.drawString(name, 42, y);
            if (shortcut != null) {
                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 11f));
                g2.setColor(active ? new Color(0xBFDBFE) : new Color(0x64748B));
                int w = g2.getFontMetrics().stringWidth(shortcut);
                g2.drawString(shortcut, getWidth() - w - 12, y);
            }
            g2.dispose();
        }
    }

    public static JLabel centered(String text) {
        JLabel l = new JLabel(text, SwingConstants.CENTER);
        l.setForeground(UiKit.muted());
        return l;
    }
}
