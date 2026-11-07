package com.productms.service;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.productms.model.Payment;
import com.productms.model.Sale;
import com.productms.model.SaleItem;
import com.productms.util.BarcodeUtil;
import com.productms.util.Money;
import com.productms.util.Settings;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Builds receipts and invoices: thermal (58 / 80 mm), A4 and PDF.
 * The thermal receipt is a list of text lines rendered with a monospaced font, so
 * the on-screen preview is exactly what gets printed.
 */
public class ReceiptService {

    public static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd-MM-yyyy hh:mm a", java.util.Locale.ENGLISH);
    private static final double MM = 72 / 25.4;

    /** One receipt line. */
    public static class Line {
        public final String text;
        public final boolean center;
        public final boolean bold;
        public final boolean big;
        public final String barcode;

        Line(String text, boolean center, boolean bold, boolean big, String barcode) {
            this.text = text;
            this.center = center;
            this.bold = bold;
            this.big = big;
            this.barcode = barcode;
        }

        public static Line left(String t) {
            return new Line(t, false, false, false, null);
        }

        public static Line center(String t) {
            return new Line(t, true, false, false, null);
        }

        public static Line bold(String t) {
            return new Line(t, false, true, false, null);
        }

        public static Line title(String t) {
            return new Line(t, true, true, true, null);
        }

        public static Line barcode(String code) {
            return new Line(code, true, false, false, code);
        }
    }

    public static int charsFor(int paperMm) {
        return paperMm <= 58 ? 32 : 42;
    }

    // ================================================================== thermal text

    public static List<Line> receiptLines(Sale s, int paperMm, boolean reprint) {
        int w = charsFor(paperMm);
        List<Line> out = new ArrayList<>();
        out.add(Line.title(Settings.shopName()));
        for (String t : wrap(Settings.get("shop_address", ""), w)) {
            out.add(Line.center(t));
        }
        if (!Settings.get("shop_phone", "").isEmpty()) {
            out.add(Line.center("Phone: " + Settings.get("shop_phone", "")));
        }
        if (!Settings.get("shop_vat_reg", "").isEmpty()) {
            out.add(Line.center(Settings.get("shop_vat_reg", "")));
        }
        out.add(Line.left(rule(w, '=')));
        out.add(new Line(s.isCancelled() ? "*** CANCELLED ***" : (s.dueTotal.signum() > 0 ? "INVOICE (DUE)" : "SALES RECEIPT"),
                true, true, false, null));
        if (reprint) {
            out.add(Line.center("-- REPRINT --"));
        }
        out.add(Line.left(pair("Invoice:", s.invoiceNo, w)));
        out.add(Line.left(pair("Date:", s.createdAt.format(DT), w)));
        out.add(Line.left(pair("Cashier:", s.cashierName, w)));
        out.add(Line.left(pair("Customer:", s.customerName, w)));
        if (s.customerPhone != null) {
            out.add(Line.left(pair("Phone:", s.customerPhone, w)));
        }
        if ("WHOLESALE".equals(s.priceMode)) {
            out.add(Line.left(pair("Price:", "Wholesale", w)));
        }
        out.add(Line.left(rule(w, '-')));
        out.add(Line.bold(pair("Item", "Amount", w)));
        out.add(Line.left(rule(w, '-')));
        int n = 1;
        BigDecimal qtyTotal = BigDecimal.ZERO;
        for (SaleItem it : s.items) {
            for (String t : wrap(n++ + ". " + it.displayName(), w, "   ")) {
                out.add(Line.left(t));
            }
            out.add(Line.left(pair("   " + Money.qty(it.quantity) + " " + it.unitName + " x " + Money.fmt(it.unitPrice),
                    Money.fmt(it.unitPrice.multiply(it.quantity)), w)));
            if (it.discountAmount.signum() > 0) {
                out.add(Line.left(pair("   Discount", "-" + Money.fmt(it.discountAmount), w)));
            }
            if (it.vatAmount.signum() > 0) {
                out.add(Line.left(pair("   VAT " + Money.qty(it.vatPercent) + "%" + (Settings.pricesIncludeVat() ? " (incl.)" : ""),
                        Money.fmt(it.vatAmount), w)));
            }
            if (it.serialNumbers != null) {
                for (String t : wrap("SN: " + it.serialNumbers, w - 3, "")) {
                    out.add(Line.left("   " + t));
                }
            }
            if (it.returnedQty.signum() > 0) {
                out.add(Line.left("   (returned " + Money.qty(it.returnedQty) + ")"));
            }
            qtyTotal = qtyTotal.add(it.quantity);
        }
        out.add(Line.left(rule(w, '-')));
        out.add(Line.left(pair("Subtotal", Money.fmt(s.subtotal), w)));
        if (s.itemDiscount.signum() > 0) {
            out.add(Line.left(pair("Item discount", "-" + Money.fmt(s.itemDiscount), w)));
        }
        if (s.cartDiscount.signum() > 0) {
            out.add(Line.left(pair("Cart discount", "-" + Money.fmt(s.cartDiscount), w)));
        }
        out.add(Line.left(pair(Settings.pricesIncludeVat() ? "VAT (included)" : "VAT", Money.fmt(s.vatTotal), w)));
        out.add(new Line(pair("TOTAL " + Settings.currency(), Money.fmt(s.grandTotal), w), false, true, false, null));
        out.add(Line.left(rule(w, '-')));
        for (Payment p : s.payments) {
            String label = ("DUE_COLLECTION".equals(p.paymentType) ? "Due paid " : "Paid ") + Payment.label(p.method);
            out.add(Line.left(pair(label, Money.fmt(p.amount), w)));
            if (p.reference != null && !"CASH".equals(p.method)) {
                out.add(Line.left("   Ref: " + p.reference));
            }
        }
        if (s.tenderedTotal.signum() > 0) {
            out.add(Line.left(pair("Tendered", Money.fmt(s.tenderedTotal), w)));
            out.add(Line.left(pair("Change", Money.fmt(s.changeAmount), w)));
        }
        if (s.dueTotal.signum() > 0) {
            out.add(new Line(pair("DUE", Money.fmt(s.dueTotal), w), false, true, false, null));
        }
        if (s.returnedTotal.signum() > 0) {
            out.add(Line.left(pair("Returned goods", "-" + Money.fmt(s.returnedTotal), w)));
        }
        out.add(Line.left(rule(w, '-')));
        out.add(Line.left(pair("Items: " + s.items.size(), "Qty: " + Money.qty(qtyTotal), w)));
        out.add(Line.barcode(s.invoiceNo));
        for (String t : wrap(Settings.get("receipt_footer", "Thank you!"), w)) {
            out.add(Line.center(t));
        }
        return out;
    }

    public static String rule(int w, char c) {
        return String.valueOf(c).repeat(w);
    }

    /** Left text and right text on one line, padded to width. */
    public static String pair(String left, String right, int w) {
        left = left == null ? "" : left;
        right = right == null ? "" : right;
        int space = w - left.length() - right.length();
        if (space < 1) {
            int max = Math.max(1, w - right.length() - 1);
            left = left.length() > max ? left.substring(0, max) : left;
            space = Math.max(1, w - left.length() - right.length());
        }
        return left + " ".repeat(space) + right;
    }

    public static List<String> wrap(String text, int w) {
        return wrap(text, w, "");
    }

    /** Word-wraps text; continuation lines start with the given indent. */
    public static List<String> wrap(String text, int w, String indent) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            int limit = w;
            while (word.length() > limit - indent.length()) {
                if (line.length() > 0) {
                    out.add(line.toString());
                    line.setLength(0);
                    line.append(indent);
                }
                int cut = limit - line.length();
                out.add(line + word.substring(0, cut));
                word = word.substring(cut);
                line.setLength(0);
                line.append(indent);
            }
            boolean empty = line.length() == 0 || line.toString().equals(indent);
            if (line.length() + word.length() + (empty ? 0 : 1) > limit) {
                out.add(line.toString());
                line.setLength(0);
                line.append(indent);
                empty = true;
            }
            if (!empty) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }

    // ================================================================== thermal rendering

    /** Paints receipt lines at the given width (points). Returns the total height used. */
    public static double paintLines(Graphics2D g, List<Line> lines, double width, int chars) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float size = (float) (width / (chars * 0.6));
        java.awt.Font normal = new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 10).deriveFont(size);
        java.awt.Font bold = normal.deriveFont(java.awt.Font.BOLD);
        java.awt.Font big = bold.deriveFont(size * 1.35f);
        g.setColor(Color.BLACK);
        double y = 0;
        for (Line l : lines) {
            if (l.barcode != null) {
                double h = size * 3.2;
                double bw = Math.min(width * 0.9, size * 22);
                try {
                    BarcodeUtil.drawBarcode(g, BarcodeUtil.encodeBarcode(l.barcode), (width - bw) / 2, y + size * 0.4, bw, h);
                } catch (RuntimeException ignored) {
                    // unprintable code: skip graphic, still print the text below
                }
                y += h + size * 0.6;
                g.setFont(normal);
                FontMetrics fm = g.getFontMetrics();
                y += fm.getAscent();
                g.drawString(l.text, (float) ((width - fm.stringWidth(l.text)) / 2), (float) y);
                y += fm.getDescent() + 2;
                continue;
            }
            java.awt.Font f = l.big ? big : (l.bold ? bold : normal);
            g.setFont(f);
            FontMetrics fm = g.getFontMetrics();
            y += fm.getAscent();
            double x = l.center ? (width - fm.stringWidth(l.text)) / 2 : 0;
            g.drawString(l.text, (float) Math.max(0, x), (float) y);
            y += fm.getDescent() + fm.getLeading() + (l.big ? 2 : 0);
        }
        return y;
    }

    public static BufferedImage previewImage(List<Line> lines, int paperMm, double scale) {
        double printable = (paperMm <= 58 ? 48 : 72) * MM;
        int chars = charsFor(paperMm);
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D pg = probe.createGraphics();
        double h = paintLines(pg, lines, printable, chars);
        pg.dispose();
        double pad = 4 * MM;
        int iw = (int) Math.ceil((printable + pad * 2) * scale);
        int ih = (int) Math.ceil((h + pad * 2) * scale);
        BufferedImage img = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, iw, ih);
        g.scale(scale, scale);
        g.translate(pad, pad);
        paintLines(g, lines, printable, chars);
        g.dispose();
        return img;
    }

    /** Sends receipt lines to a printer using a roll-paper page of the right width. */
    public static void printThermal(List<Line> lines, int paperMm, String jobName, boolean showDialog) throws PrinterException {
        double paperW = paperMm * MM;
        double printable = (paperMm <= 58 ? 48 : 72) * MM;
        int chars = charsFor(paperMm);
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D pg = probe.createGraphics();
        double contentH = paintLines(pg, lines, printable, chars);
        pg.dispose();
        double margin = (paperW - printable) / 2;
        Paper paper = new Paper();
        paper.setSize(paperW, contentH + 20 * MM);
        paper.setImageableArea(margin, 4 * MM, printable, contentH + 12 * MM);
        PageFormat pf = new PageFormat();
        pf.setPaper(paper);
        PrinterJob job = PrinterJob.getPrinterJob();
        job.setJobName(jobName);
        job.setPrintable((graphics, format, page) -> {
            if (page > 0) {
                return Printable.NO_SUCH_PAGE;
            }
            Graphics2D g = (Graphics2D) graphics;
            g.translate(format.getImageableX(), format.getImageableY());
            paintLines(g, lines, printable, chars);
            return Printable.PAGE_EXISTS;
        }, pf);
        if (!showDialog || job.printDialog()) {
            job.print();
        }
    }

    // ================================================================== A4 invoice

    public static void printA4(Sale s) throws PrinterException {
        PrinterJob job = PrinterJob.getPrinterJob();
        job.setJobName("Invoice " + s.invoiceNo);
        PageFormat pf = job.defaultPage();
        Paper paper = new Paper();
        paper.setSize(210 * MM, 297 * MM);
        paper.setImageableArea(15 * MM, 15 * MM, 180 * MM, 267 * MM);
        pf.setPaper(paper);
        job.setPrintable(new A4Invoice(s), pf);
        if (job.printDialog()) {
            job.print();
        }
    }

    /** A4 invoice painter with simple pagination. */
    public static class A4Invoice implements Printable {
        private static final int ROWS_FIRST = 22;
        private static final int ROWS_NEXT = 34;
        private final Sale s;

        public A4Invoice(Sale s) {
            this.s = s;
        }

        public int pageCount() {
            int n = s.items.size();
            return n <= ROWS_FIRST ? 1 : 1 + (int) Math.ceil((n - ROWS_FIRST) / (double) ROWS_NEXT);
        }

        @Override
        public int print(Graphics graphics, PageFormat pf, int page) {
            if (page >= pageCount()) {
                return NO_SUCH_PAGE;
            }
            Graphics2D g = (Graphics2D) graphics;
            g.translate(pf.getImageableX(), pf.getImageableY());
            paintPage(g, page, pf.getImageableWidth(), pf.getImageableHeight());
            return PAGE_EXISTS;
        }

        public void paintPage(Graphics2D g, int page, double w, double h) {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            java.awt.Font base = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 9);
            java.awt.Font bold = base.deriveFont(java.awt.Font.BOLD);
            Color accent = new Color(37, 99, 235);
            g.setColor(Color.BLACK);
            double y = 0;
            if (page == 0) {
                g.setFont(base.deriveFont(java.awt.Font.BOLD, 18f));
                g.setColor(accent);
                g.drawString(Settings.shopName(), 0, 18);
                g.setColor(Color.DARK_GRAY);
                g.setFont(base);
                g.drawString(Settings.get("shop_address", ""), 0, 32);
                g.drawString("Phone: " + Settings.get("shop_phone", "") + "    " + Settings.get("shop_vat_reg", ""), 0, 44);
                g.setFont(base.deriveFont(java.awt.Font.BOLD, 22f));
                g.setColor(s.isCancelled() ? Color.RED : accent);
                String title = s.isCancelled() ? "CANCELLED" : "INVOICE";
                g.drawString(title, (float) (w - g.getFontMetrics().stringWidth(title)), 20);
                g.setColor(Color.BLACK);
                g.setFont(base);
                rightText(g, "Invoice No: " + s.invoiceNo, w, 36);
                rightText(g, "Date: " + s.createdAt.format(DT), w, 48);
                rightText(g, "Cashier: " + s.cashierName, w, 60);
                y = 78;
                g.setColor(new Color(241, 245, 249));
                g.fill(new java.awt.geom.Rectangle2D.Double(0, y, w / 2 - 10, 54));
                g.setColor(Color.BLACK);
                g.setFont(bold);
                g.drawString("BILL TO", 8, (float) y + 14);
                g.setFont(base);
                g.drawString(s.customerName, 8, (float) y + 28);
                if (s.customerPhone != null) {
                    g.drawString("Phone: " + s.customerPhone, 8, (float) y + 40);
                }
                if (s.customerAddress != null) {
                    g.drawString(s.customerAddress, 8, (float) y + 52);
                }
                g.setFont(bold);
                rightText(g, "Payment: " + s.paymentStatus + ("WHOLESALE".equals(s.priceMode) ? "   (Wholesale)" : ""), w, y + 14);
                try {
                    BarcodeUtil.drawBarcode(g, BarcodeUtil.encodeBarcode(s.invoiceNo), w - 150, y + 20, 150, 30);
                } catch (RuntimeException ignored) {
                    // no barcode
                }
                y += 66;
            }
            // table
            double[] cols = {0, 22, 0.47 * w, 0.58 * w, 0.68 * w, 0.79 * w, 0.88 * w, w};
            String[] heads = {"#", "Item", "Qty", "Unit Price", "Discount", "VAT", "Total"};
            g.setColor(accent);
            g.fill(new java.awt.geom.Rectangle2D.Double(0, y, w, 18));
            g.setColor(Color.WHITE);
            g.setFont(bold);
            for (int i = 0; i < heads.length; i++) {
                if (i >= 2) {
                    rightText(g, heads[i], cols[i + 1] - 4, y + 12);
                } else {
                    g.drawString(heads[i], (float) cols[i] + 4, (float) y + 12);
                }
            }
            y += 18;
            int start = page == 0 ? 0 : ROWS_FIRST + (page - 1) * ROWS_NEXT;
            int end = Math.min(s.items.size(), start + (page == 0 ? ROWS_FIRST : ROWS_NEXT));
            g.setFont(base);
            for (int i = start; i < end; i++) {
                SaleItem it = s.items.get(i);
                if (i % 2 == 1) {
                    g.setColor(new Color(248, 250, 252));
                    g.fill(new java.awt.geom.Rectangle2D.Double(0, y, w, 20));
                }
                g.setColor(Color.BLACK);
                g.drawString(String.valueOf(i + 1), (float) cols[0] + 4, (float) y + 13);
                String name = it.displayName();
                FontMetrics fm = g.getFontMetrics();
                while (fm.stringWidth(name) > cols[2] - cols[1] - 8 && name.length() > 4) {
                    name = name.substring(0, name.length() - 2);
                }
                g.drawString(name, (float) cols[1] + 4, (float) y + 10);
                g.setColor(Color.GRAY);
                g.setFont(base.deriveFont(7f));
                g.drawString(it.sku + (it.serialNumbers != null ? "  SN: " + it.serialNumbers : ""), (float) cols[1] + 4, (float) y + 18);
                g.setFont(base);
                g.setColor(Color.BLACK);
                rightText(g, Money.qty(it.quantity) + " " + it.unitName, cols[3] - 4, y + 13);
                rightText(g, Money.fmt(it.unitPrice), cols[4] - 4, y + 13);
                rightText(g, Money.fmt(it.discountAmount), cols[5] - 4, y + 13);
                rightText(g, Money.fmt(it.vatAmount), cols[6] - 4, y + 13);
                rightText(g, Money.fmt(it.lineTotal), cols[7] - 4, y + 13);
                y += 20;
            }
            g.setColor(Color.LIGHT_GRAY);
            g.setStroke(new BasicStroke(0.5f));
            g.draw(new java.awt.geom.Line2D.Double(0, y, w, y));
            if (page == pageCount() - 1) {
                y += 10;
                double lx = w * 0.62;
                String[][] totals = {
                    {"Subtotal", Money.fmt(s.subtotal)},
                    {"Discount", "-" + Money.fmt(s.itemDiscount.add(s.cartDiscount))},
                    {Settings.pricesIncludeVat() ? "VAT (included)" : "VAT", Money.fmt(s.vatTotal)},
                    {"Grand Total (" + Settings.currency() + ")", Money.fmt(s.grandTotal)},
                    {"Paid", Money.fmt(s.paidTotal)},
                    {"Due", Money.fmt(s.dueTotal)}
                };
                for (int i = 0; i < totals.length; i++) {
                    boolean strong = i == 3;
                    if (strong) {
                        g.setColor(new Color(241, 245, 249));
                        g.fill(new java.awt.geom.Rectangle2D.Double(lx - 6, y - 2, w - lx + 6, 18));
                    }
                    g.setColor(i == 5 && s.dueTotal.signum() > 0 ? Color.RED : Color.BLACK);
                    g.setFont(strong ? bold.deriveFont(11f) : base);
                    g.drawString(totals[i][0], (float) lx, (float) y + 12);
                    rightText(g, totals[i][1], w - 4, y + 12);
                    y += 18;
                }
                double py = y - 18 * totals.length;
                g.setColor(Color.BLACK);
                g.setFont(bold);
                g.drawString("Payments", 0, (float) py + 12);
                g.setFont(base);
                for (Payment p : s.payments) {
                    py += 14;
                    g.drawString(Payment.label(p.method) + "  " + Money.fmt(p.amount)
                            + (p.reference != null && !"CASH".equals(p.method) ? "  (Ref: " + p.reference + ")" : ""), 0, (float) py + 12);
                }
                if (s.changeAmount.signum() > 0) {
                    py += 14;
                    g.drawString("Change returned: " + Money.fmt(s.changeAmount), 0, (float) py + 12);
                }
                y = Math.max(y, py + 20) + 30;
                g.setColor(Color.GRAY);
                g.draw(new java.awt.geom.Line2D.Double(0, y, 140, y));
                g.draw(new java.awt.geom.Line2D.Double(w - 140, y, w, y));
                g.drawString("Customer signature", 0, (float) y + 12);
                rightText(g, "Authorised signature", w, y + 12);
            }
            g.setColor(Color.GRAY);
            g.setFont(base.deriveFont(8f));
            String footer = Settings.get("receipt_footer", "") + "   Page " + (page + 1) + " of " + pageCount();
            g.drawString(footer, 0, (float) h - 4);
        }

        private static void rightText(Graphics2D g, String t, double right, double y) {
            g.drawString(t, (float) (right - g.getFontMetrics().stringWidth(t)), (float) y);
        }
    }

    public static BufferedImage a4Preview(Sale s, double scale) {
        double w = 180 * MM;
        double h = 267 * MM;
        A4Invoice inv = new A4Invoice(s);
        double pad = 15 * MM;
        int iw = (int) ((w + pad * 2) * scale);
        int ih = (int) ((h + pad * 2) * scale);
        BufferedImage img = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, iw, ih);
        g.scale(scale, scale);
        g.translate(pad, pad);
        inv.paintPage(g, 0, w, h);
        g.dispose();
        return img;
    }

    // ================================================================== PDF

    public static void savePdf(Sale s, Path file) throws IOException {
        try (OutputStream os = new FileOutputStream(file.toFile())) {
            Document doc = new Document(PageSize.A4, 40, 40, 40, 40);
            PdfWriter.getInstance(doc, os);
            doc.open();
            Font h1 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, new Color(37, 99, 235));
            Font h2 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 20, s.isCancelled() ? Color.RED : new Color(37, 99, 235));
            Font normal = FontFactory.getFont(FontFactory.HELVETICA, 9);
            Font small = FontFactory.getFont(FontFactory.HELVETICA, 7, Color.GRAY);
            Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
            Font white = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE);

            PdfPTable head = new PdfPTable(new float[]{60, 40});
            head.setWidthPercentage(100);
            PdfPCell shop = new PdfPCell();
            shop.setBorder(0);
            shop.addElement(new Paragraph(Settings.shopName(), h1));
            shop.addElement(new Paragraph(Settings.get("shop_address", ""), normal));
            shop.addElement(new Paragraph("Phone: " + Settings.get("shop_phone", "") + "   " + Settings.get("shop_vat_reg", ""), normal));
            head.addCell(shop);
            PdfPCell meta = new PdfPCell();
            meta.setBorder(0);
            Paragraph t = new Paragraph(s.isCancelled() ? "CANCELLED" : "INVOICE", h2);
            t.setAlignment(Element.ALIGN_RIGHT);
            meta.addElement(t);
            for (String m : new String[]{"Invoice No: " + s.invoiceNo, "Date: " + s.createdAt.format(DT),
                "Cashier: " + s.cashierName, "Payment: " + s.paymentStatus}) {
                Paragraph p = new Paragraph(m, normal);
                p.setAlignment(Element.ALIGN_RIGHT);
                meta.addElement(p);
            }
            head.addCell(meta);
            doc.add(head);

            PdfPTable bill = new PdfPTable(new float[]{60, 40});
            bill.setWidthPercentage(100);
            bill.setSpacingBefore(12);
            PdfPCell to = new PdfPCell();
            to.setBorder(0);
            to.setBackgroundColor(new Color(241, 245, 249));
            to.setPadding(8);
            to.addElement(new Paragraph("BILL TO", bold));
            to.addElement(new Paragraph(s.customerName, normal));
            if (s.customerPhone != null) {
                to.addElement(new Paragraph("Phone: " + s.customerPhone, normal));
            }
            if (s.customerAddress != null) {
                to.addElement(new Paragraph(s.customerAddress, normal));
            }
            bill.addCell(to);
            PdfPCell bc = new PdfPCell();
            bc.setBorder(0);
            bc.setHorizontalAlignment(Element.ALIGN_RIGHT);
            try {
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                ImageIO.write(BarcodeUtil.barcodeImage(s.invoiceNo, 420, 90), "png", png);
                Image img = Image.getInstance(png.toByteArray());
                img.scaleToFit(160, 40);
                img.setAlignment(Element.ALIGN_RIGHT);
                bc.addElement(img);
            } catch (RuntimeException ignored) {
                // no barcode
            }
            bill.addCell(bc);
            doc.add(bill);

            PdfPTable items = new PdfPTable(new float[]{5, 41, 10, 11, 11, 10, 12});
            items.setWidthPercentage(100);
            items.setSpacingBefore(14);
            items.setHeaderRows(1);
            for (String h : new String[]{"#", "Item", "Qty", "Unit Price", "Discount", "VAT", "Total"}) {
                PdfPCell c = new PdfPCell(new Phrase(h, white));
                c.setBackgroundColor(new Color(37, 99, 235));
                c.setPadding(5);
                c.setBorder(0);
                c.setHorizontalAlignment(h.equals("Item") || h.equals("#") ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
                items.addCell(c);
            }
            int n = 1;
            for (SaleItem it : s.items) {
                Color bg = n % 2 == 0 ? new Color(248, 250, 252) : Color.WHITE;
                items.addCell(cell(String.valueOf(n++), normal, Element.ALIGN_LEFT, bg));
                PdfPCell name = new PdfPCell();
                name.setBorder(0);
                name.setBackgroundColor(bg);
                name.setPadding(4);
                name.addElement(new Paragraph(it.displayName(), normal));
                name.addElement(new Paragraph(it.sku + (it.serialNumbers != null ? "   SN: " + it.serialNumbers : ""), small));
                items.addCell(name);
                items.addCell(cell(Money.qty(it.quantity) + " " + it.unitName, normal, Element.ALIGN_RIGHT, bg));
                items.addCell(cell(Money.fmt(it.unitPrice), normal, Element.ALIGN_RIGHT, bg));
                items.addCell(cell(Money.fmt(it.discountAmount), normal, Element.ALIGN_RIGHT, bg));
                items.addCell(cell(Money.fmt(it.vatAmount), normal, Element.ALIGN_RIGHT, bg));
                items.addCell(cell(Money.fmt(it.lineTotal), normal, Element.ALIGN_RIGHT, bg));
            }
            doc.add(items);

            PdfPTable bottom = new PdfPTable(new float[]{55, 45});
            bottom.setWidthPercentage(100);
            bottom.setSpacingBefore(12);
            PdfPCell pays = new PdfPCell();
            pays.setBorder(0);
            pays.addElement(new Paragraph("Payments", bold));
            for (Payment p : s.payments) {
                pays.addElement(new Paragraph(Payment.label(p.method) + ":  " + Money.fmt(p.amount)
                        + (p.reference != null && !"CASH".equals(p.method) ? "   (Ref: " + p.reference + ")" : ""), normal));
            }
            if (s.changeAmount.signum() > 0) {
                pays.addElement(new Paragraph("Change returned: " + Money.fmt(s.changeAmount), normal));
            }
            bottom.addCell(pays);
            PdfPTable tot = new PdfPTable(new float[]{55, 45});
            String[][] rows = {
                {"Subtotal", Money.fmt(s.subtotal)},
                {"Discount", "-" + Money.fmt(s.itemDiscount.add(s.cartDiscount))},
                {Settings.pricesIncludeVat() ? "VAT (included)" : "VAT", Money.fmt(s.vatTotal)},
                {"Grand Total (" + Settings.currency() + ")", Money.fmt(s.grandTotal)},
                {"Paid", Money.fmt(s.paidTotal)},
                {"Due", Money.fmt(s.dueTotal)}
            };
            for (int i = 0; i < rows.length; i++) {
                Color bg = i == 3 ? new Color(241, 245, 249) : Color.WHITE;
                Font f = i == 3 ? FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11) : normal;
                tot.addCell(cell(rows[i][0], f, Element.ALIGN_LEFT, bg));
                tot.addCell(cell(rows[i][1], f, Element.ALIGN_RIGHT, bg));
            }
            PdfPCell totCell = new PdfPCell(tot);
            totCell.setBorder(0);
            bottom.addCell(totCell);
            doc.add(bottom);

            Paragraph foot = new Paragraph(Settings.get("receipt_footer", ""), small);
            foot.setSpacingBefore(30);
            foot.setAlignment(Element.ALIGN_CENTER);
            doc.add(foot);
            doc.close();
        }
    }

    private static PdfPCell cell(String text, Font f, int align, Color bg) {
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setBorder(0);
        c.setPadding(4);
        c.setHorizontalAlignment(align);
        c.setBackgroundColor(bg);
        return c;
    }

    // ================================================================== shift (Z) report

    public static List<Line> shiftReportLines(com.productms.model.Shift shift, java.util.Map<String, BigDecimal> summary, int paperMm) {
        int w = charsFor(paperMm);
        List<Line> out = new ArrayList<>();
        out.add(Line.title(Settings.shopName()));
        out.add(new Line("SHIFT REPORT (Z)", true, true, false, null));
        out.add(Line.left(rule(w, '=')));
        out.add(Line.left(pair("Shift #", String.valueOf(shift.shiftId), w)));
        out.add(Line.left(pair("Cashier", shift.userName, w)));
        out.add(Line.left(pair("Opened", shift.openedAt.format(DT), w)));
        if (shift.closedAt != null) {
            out.add(Line.left(pair("Closed", shift.closedAt.format(DT), w)));
        }
        out.add(Line.left(rule(w, '-')));
        for (java.util.Map.Entry<String, BigDecimal> e : summary.entrySet()) {
            String v = e.getKey().equals("Invoices") ? Money.qty(e.getValue()) : Money.fmt(e.getValue());
            boolean strong = e.getKey().startsWith("Expected");
            out.add(new Line(pair(e.getKey(), v, w), false, strong, false, null));
        }
        if (shift.countedCash != null) {
            out.add(Line.left(rule(w, '-')));
            out.add(Line.left(pair("Counted cash", Money.fmt(shift.countedCash), w)));
            out.add(Line.bold(pair(shift.difference.signum() < 0 ? "SHORT" : "OVER", Money.fmt(shift.difference.abs()), w)));
        }
        out.add(Line.left(rule(w, '=')));
        out.add(Line.center("Printed " + java.time.LocalDateTime.now().format(DT)));
        return out;
    }
}
