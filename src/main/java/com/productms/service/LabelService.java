package com.productms.service;

import com.productms.model.Variant;
import com.productms.util.BarcodeUtil;
import com.productms.util.Money;
import com.productms.util.Settings;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.util.ArrayList;
import java.util.List;

/** Barcode / QR price labels on A4 sticker sheets or label rolls. */
public class LabelService {

    private static final double MM = 72 / 25.4;

    /** Label stock layout. */
    public static class Layout {
        public final String name;
        public final double widthMm;
        public final double heightMm;
        public final int cols;
        public final int rows;
        public final boolean roll;
        public Options defaults;

        public Layout(String name, double widthMm, double heightMm, int cols, int rows, boolean roll, Options defaults) {
            this(name, widthMm, heightMm, cols, rows, roll);
            this.defaults = defaults;
        }

        public Layout(String name, double widthMm, double heightMm, int cols, int rows, boolean roll) {
            this.name = name;
            this.widthMm = widthMm;
            this.heightMm = heightMm;
            this.cols = cols;
            this.rows = rows;
            this.roll = roll;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public static final Layout[] LAYOUTS = {
        new Layout("A4 sheet - 38 x 21 mm (5 x 13 = 65 labels)", 38, 21.2, 5, 13, false),
        new Layout("A4 sheet - 64 x 34 mm (3 x 8 = 24 labels)", 63.5, 33.9, 3, 8, false),
        new Layout("Label roll - 50 x 25 mm", 50, 25, 1, 1, true),
        new Layout("Label roll - 40 x 30 mm", 40, 30, 1, 1, true)
    };

    /** Label content options. */
    public static class Options {
        public boolean showShop = true;
        public boolean showName = true;
        public boolean showPrice = true;
        public boolean showMrp;
        public boolean showSku;
        public boolean showQr;
        public boolean wholesalePrice;
        public double fontScale = 1.0;
    }

    /** Label templates stored in the database (Settings-independent, editable from the label dialog). */
    public static List<Layout> templates() throws java.sql.SQLException {
        return com.productms.db.Jdbc.list("SELECT * FROM label_templates ORDER BY template_id", rs -> new Layout(
                rs.getString("name"), rs.getDouble("width_mm"), rs.getDouble("height_mm"), rs.getInt("cols"), rs.getInt("rows_count"),
                rs.getBoolean("roll"), templateOptions(rs)));
    }

    private static Options templateOptions(java.sql.ResultSet rs) throws java.sql.SQLException {
        Options o = new Options();
        o.showShop = rs.getBoolean("show_shop");
        o.showName = rs.getBoolean("show_name");
        o.showPrice = rs.getBoolean("show_price");
        o.showMrp = rs.getBoolean("show_mrp");
        o.showSku = rs.getBoolean("show_sku");
        o.showQr = rs.getBoolean("show_qr");
        o.fontScale = rs.getDouble("font_scale");
        return o;
    }

    public static void saveTemplate(Layout l, Options o) throws java.sql.SQLException {
        if (l.name == null || l.name.isBlank()) {
            throw new com.productms.util.AppException("Give the template a name.");
        }
        if (l.widthMm < 15 || l.heightMm < 10 || l.widthMm > 210 || l.heightMm > 297 || l.cols < 1 || l.rows < 1) {
            throw new com.productms.util.AppException("Label size must be 15-210 x 10-297 mm, with at least 1 column and row.");
        }
        if (!l.roll && (l.widthMm * l.cols > 210 || l.heightMm * l.rows > 297)) {
            throw new com.productms.util.AppException("These labels do not fit on an A4 sheet (210 x 297 mm).");
        }
        com.productms.db.Jdbc.update("INSERT INTO label_templates (name, width_mm, height_mm, cols, rows_count, roll, show_shop, show_name, "
                + "show_price, show_mrp, show_sku, show_qr, font_scale) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE "
                + "width_mm=VALUES(width_mm), height_mm=VALUES(height_mm), cols=VALUES(cols), rows_count=VALUES(rows_count), roll=VALUES(roll), "
                + "show_shop=VALUES(show_shop), show_name=VALUES(show_name), show_price=VALUES(show_price), show_mrp=VALUES(show_mrp), "
                + "show_sku=VALUES(show_sku), show_qr=VALUES(show_qr), font_scale=VALUES(font_scale)",
                l.name.trim(), l.widthMm, l.heightMm, l.roll ? 1 : l.cols, l.roll ? 1 : l.rows, l.roll, o.showShop, o.showName, o.showPrice,
                o.showMrp, o.showSku, o.showQr, o.fontScale);
    }

    public static void deleteTemplate(String name) throws java.sql.SQLException {
        com.productms.db.Jdbc.update("DELETE FROM label_templates WHERE name=?", name);
    }

    public static List<Variant> expand(List<Variant> variants, List<Integer> copies) {
        List<Variant> out = new ArrayList<>();
        for (int i = 0; i < variants.size(); i++) {
            for (int c = 0; c < copies.get(i); c++) {
                out.add(variants.get(i));
            }
        }
        return out;
    }

    public static void paintLabel(Graphics2D g, Variant v, double x, double y, double w, double h, Options o) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        double pad = Math.max(1.5 * MM, w * 0.04);
        double cx = x + pad;
        double cw = w - pad * 2;
        double cy = y + pad;
        float base = (float) (Math.max(5, Math.min(9, h / 9)) * (o.fontScale <= 0 ? 1 : o.fontScale));
        double qrSize = 0;
        if (o.showQr) {
            qrSize = Math.min(h - pad * 2, cw * 0.36);
            String code = v.barcode == null ? v.sku : v.barcode;
            BarcodeUtil.drawMatrix(g, BarcodeUtil.encodeQr(v.sku + "|" + code + "|" + v.displayName() + "|"
                    + Money.fmt(o.wholesalePrice ? v.wholesalePrice : v.retailPrice)), x + w - pad - qrSize, y + pad, qrSize);
            cw -= qrSize + pad;
        }
        if (o.showShop) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10).deriveFont(base * 0.75f));
            cy += g.getFontMetrics().getAscent();
            g.drawString(fit(g, Settings.shopName(), cw), (float) cx, (float) cy);
        }
        FontMetrics fm;
        if (o.showName) {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10).deriveFont(base));
            fm = g.getFontMetrics();
            cy += fm.getAscent();
            g.drawString(fit(g, v.displayName(), cw), (float) cx, (float) cy);
        }
        if (o.showSku) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10).deriveFont(base * 0.75f));
            fm = g.getFontMetrics();
            cy += fm.getAscent();
            g.drawString(fit(g, "SKU " + v.sku, cw), (float) cx, (float) cy);
        }
        if (o.showPrice) {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10).deriveFont(base * 1.15f));
            fm = g.getFontMetrics();
            cy += fm.getAscent();
            String price = Money.fmt(o.wholesalePrice ? v.wholesalePrice : v.retailPrice);
            String mrp = o.showMrp && v.mrp.signum() > 0 ? "  MRP " + Money.fmt(v.mrp) : "";
            g.drawString(fit(g, Settings.currency() + " " + price + mrp, cw), (float) cx, (float) cy);
        } else if (o.showMrp && v.mrp.signum() > 0) {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10).deriveFont(base));
            fm = g.getFontMetrics();
            cy += fm.getAscent();
            g.drawString(fit(g, "MRP " + Settings.currency() + " " + Money.fmt(v.mrp), cw), (float) cx, (float) cy);
        }
        String code = v.barcode == null || v.barcode.isBlank() ? v.sku : v.barcode;
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10).deriveFont(base * 0.8f));
        fm = g.getFontMetrics();
        double textH = fm.getHeight();
        double barTop = cy + 1.5;
        double barH = y + h - pad - textH - barTop;
        if (barH > 3) {
            try {
                BarcodeUtil.drawBarcode(g, BarcodeUtil.encodeBarcode(code), cx, barTop, cw, barH);
            } catch (RuntimeException ignored) {
                // leave blank
            }
        }
        g.drawString(code, (float) (cx + (cw - fm.stringWidth(code)) / 2), (float) (y + h - pad - fm.getDescent()));
    }

    private static String fit(Graphics2D g, String s, double width) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= width) {
            return s;
        }
        while (s.length() > 1 && fm.stringWidth(s + "…") > width) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "…";
    }

    /** Paints one page (sheet or one roll label). Returns false if the page is past the end. */
    public static boolean paintPage(Graphics2D g, List<Variant> labels, Layout l, Options o, int page, boolean guides) {
        double w = l.widthMm * MM;
        double h = l.heightMm * MM;
        if (l.roll) {
            if (page >= labels.size()) {
                return false;
            }
            paintLabel(g, labels.get(page), 0, 0, w, h, o);
            return true;
        }
        int perPage = l.cols * l.rows;
        int start = page * perPage;
        if (start >= labels.size()) {
            return false;
        }
        double sheetW = 210 * MM;
        double sheetH = 297 * MM;
        double left = (sheetW - l.cols * w) / 2;
        double top = (sheetH - l.rows * h) / 2;
        for (int i = start; i < Math.min(labels.size(), start + perPage); i++) {
            int idx = i - start;
            double x = left + (idx % l.cols) * w;
            double y = top + (idx / l.cols) * h;
            if (guides) {
                g.setColor(new Color(200, 200, 200));
                g.setStroke(new BasicStroke(0.3f));
                g.draw(new Rectangle2D.Double(x, y, w, h));
            }
            paintLabel(g, labels.get(i), x, y, w, h, o);
        }
        return true;
    }

    public static int pageCount(List<Variant> labels, Layout l) {
        if (labels.isEmpty()) {
            return 0;
        }
        return l.roll ? labels.size() : (int) Math.ceil(labels.size() / (double) (l.cols * l.rows));
    }

    public static BufferedImage preview(List<Variant> labels, Layout l, Options o, double scale) {
        double pw = l.roll ? l.widthMm * MM : 210 * MM;
        double ph = l.roll ? l.heightMm * MM : 297 * MM;
        BufferedImage img = new BufferedImage((int) (pw * scale), (int) (ph * scale), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.scale(scale, scale);
        if (!labels.isEmpty()) {
            paintPage(g, labels, l, o, 0, true);
        }
        g.dispose();
        return img;
    }

    public static void print(List<Variant> labels, Layout l, Options o) throws PrinterException {
        PrinterJob job = PrinterJob.getPrinterJob();
        job.setJobName("Barcode labels");
        Paper paper = new Paper();
        if (l.roll) {
            paper.setSize(l.widthMm * MM, l.heightMm * MM);
            paper.setImageableArea(0, 0, l.widthMm * MM, l.heightMm * MM);
        } else {
            paper.setSize(210 * MM, 297 * MM);
            paper.setImageableArea(0, 0, 210 * MM, 297 * MM);
        }
        PageFormat pf = new PageFormat();
        pf.setPaper(paper);
        job.setPrintable((graphics, format, page) ->
                paintPage((Graphics2D) graphics, labels, l, o, page, false) ? Printable.PAGE_EXISTS : Printable.NO_SUCH_PAGE, pf);
        if (job.printDialog()) {
            job.print();
        }
    }
}
