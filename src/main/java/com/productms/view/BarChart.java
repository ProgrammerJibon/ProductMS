package com.productms.view;

import com.productms.util.Money;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.UIManager;

/** Minimal vertical bar chart (labels under bars, value above). */
public class BarChart extends JComponent {

    private final List<String> labels = new ArrayList<>();
    private final List<BigDecimal> values = new ArrayList<>();

    public BarChart() {
        setPreferredSize(new Dimension(400, 220));
    }

    public void setData(List<String> l, List<BigDecimal> v) {
        labels.clear();
        labels.addAll(l);
        values.clear();
        values.addAll(v);
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int w = getWidth();
        int h = getHeight();
        int bottom = h - 26;
        int top = 22;
        BigDecimal max = BigDecimal.ONE;
        for (BigDecimal v : values) {
            if (v.compareTo(max) > 0) {
                max = v;
            }
        }
        Color grid = UIManager.getColor("Component.borderColor");
        g2.setColor(grid);
        for (int i = 0; i <= 3; i++) {
            int y = bottom - (bottom - top) * i / 3;
            g2.drawLine(0, y, w, y);
        }
        int n = Math.max(1, values.size());
        double slot = w / (double) n;
        double barW = Math.min(46, slot * 0.55);
        Font f = UIManager.getFont("defaultFont").deriveFont(11f);
        g2.setFont(f);
        FontMetrics fm = g2.getFontMetrics();
        for (int i = 0; i < values.size(); i++) {
            BigDecimal v = values.get(i);
            int barH = (int) Math.round(v.doubleValue() / max.doubleValue() * (bottom - top));
            int x = (int) (i * slot + (slot - barW) / 2);
            boolean last = i == values.size() - 1;
            g2.setColor(last ? UiKit.PRIMARY : new Color(0x93C5FD));
            g2.fillRoundRect(x, bottom - barH, (int) barW, Math.max(barH, 2), 8, 8);
            g2.setColor(UIManager.getColor("Label.foreground"));
            String val = v.signum() == 0 ? "0" : compact(v);
            g2.drawString(val, (int) (x + (barW - fm.stringWidth(val)) / 2), bottom - barH - 5);
            g2.setColor(UiKit.muted());
            String lbl = labels.get(i);
            g2.drawString(lbl, (int) (i * slot + (slot - fm.stringWidth(lbl)) / 2), h - 8);
        }
        g2.dispose();
    }

    private static String compact(BigDecimal v) {
        double d = v.doubleValue();
        if (d >= 1_000_000) {
            return String.format("%.1fM", d / 1_000_000);
        }
        if (d >= 10_000) {
            return String.format("%.1fK", d / 1_000);
        }
        return Money.fmt(v).replace(".00", "");
    }
}
