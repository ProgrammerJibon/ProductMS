package com.productms.view;

import com.productms.model.Sale;
import com.productms.service.ReceiptService;
import com.productms.util.Money;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Window;
import java.io.File;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;

/** Shows a receipt / invoice and prints it (58 mm, 80 mm, A4) or saves it as PDF. */
public class ReceiptPreviewDialog extends JDialog {

    private final Sale sale;
    private final boolean reprint;
    private final JLabel preview = new JLabel("", SwingConstants.CENTER);
    private String format;

    public ReceiptPreviewDialog(Window owner, Sale sale, String format, boolean reprint, String headline) {
        super(owner, (reprint ? "Reprint - " : "Receipt - ") + sale.invoiceNo, ModalityType.APPLICATION_MODAL);
        this.sale = sale;
        this.reprint = reprint;
        this.format = format == null ? "80" : format;

        JToggleButton t58 = new JToggleButton("58 mm");
        JToggleButton t80 = new JToggleButton("80 mm");
        JToggleButton tA4 = new JToggleButton("A4 invoice");
        ButtonGroup g = new ButtonGroup();
        g.add(t58);
        g.add(t80);
        g.add(tA4);
        ("58".equals(this.format) ? t58 : "A4".equals(this.format) ? tA4 : t80).setSelected(true);
        t58.addActionListener(e -> setFormat("58"));
        t80.addActionListener(e -> setFormat("80"));
        tA4.addActionListener(e -> setFormat("A4"));

        JButton btnPrint = UiKit.big(UiKit.primary("Print (Enter)"), 42);
        JButton btnPdf = UiKit.big(UiKit.plain("Save PDF"), 42);
        JButton btnClose = UiKit.big(UiKit.plain("Close (Esc)"), 42);
        JButton btnShare = UiKit.big(UiKit.plain("Share..."), 42);
        javax.swing.JPopupMenu share = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem copy = new javax.swing.JMenuItem("Copy receipt text");
        javax.swing.JMenuItem whatsapp = new javax.swing.JMenuItem("Send by WhatsApp");
        javax.swing.JMenuItem email = new javax.swing.JMenuItem("Send by e-mail");
        share.add(copy);
        share.add(whatsapp);
        share.add(email);
        btnShare.addActionListener(e -> share.show(btnShare, 0, -share.getPreferredSize().height));
        copy.addActionListener(e -> {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                    new java.awt.datatransfer.StringSelection(ReceiptService.plainText(sale)), null);
            UiKit.info(this, "Receipt text copied. Paste it into any chat or message.");
        });
        whatsapp.addActionListener(e -> UiKit.guard(this, () -> {
            String phone = sale.customerPhone == null ? UiKit.input(this, "Customer's mobile number (e.g. 01711000000):", "")
                    : sale.customerPhone;
            if (phone == null || phone.isBlank()) {
                return;
            }
            String digits = phone.replaceAll("[^0-9]", "");
            if (digits.startsWith("0")) {
                digits = "88" + digits;
            }
            String url = "https://wa.me/" + digits + "?text="
                    + java.net.URLEncoder.encode(ReceiptService.plainText(sale), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
            Desktop.getDesktop().browse(new java.net.URI(url));
        }));
        email.addActionListener(e -> UiKit.guard(this, () -> {
            String to = UiKit.input(this, "Customer's e-mail address:", "");
            if (to == null || to.isBlank()) {
                return;
            }
            String subject = "Receipt " + sale.invoiceNo + " - " + com.productms.util.Settings.shopName();
            String uri = "mailto:" + to.trim() + "?subject=" + enc(subject) + "&body=" + enc(ReceiptService.plainText(sale)
                    + "\n(Use Save PDF to attach the invoice as a PDF.)");
            Desktop.getDesktop().mail(new java.net.URI(uri));
        }));
        btnPrint.addActionListener(e -> print());
        btnPdf.addActionListener(e -> savePdf());
        btnClose.addActionListener(e -> dispose());

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        JPanel north = new JPanel(new BorderLayout(0, 6));
        if (headline != null) {
            JLabel h = UiKit.title(headline, 18f);
            h.setForeground(UiKit.SUCCESS);
            north.add(h, BorderLayout.NORTH);
        }
        north.add(UiKit.row(t58, t80, tA4), BorderLayout.SOUTH);
        root.add(north, BorderLayout.NORTH);
        root.add(UiKit.scroll(preview));
        root.add(UiKit.rowRight(btnShare, btnPdf, btnClose, btnPrint), BorderLayout.SOUTH);
        setContentPane(root);
        UiKit.bind(root, "ESCAPE", this::dispose);
        getRootPane().setDefaultButton(btnPrint);
        setSize(720, 760);
        setLocationRelativeTo(owner);
        render();
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    private void setFormat(String f) {
        format = f;
        render();
    }

    private void render() {
        if ("A4".equals(format)) {
            preview.setIcon(new ImageIcon(ReceiptService.a4Preview(sale, 1.0)));
        } else {
            int mm = Integer.parseInt(format);
            preview.setIcon(new ImageIcon(ReceiptService.previewImage(ReceiptService.receiptLines(sale, mm, reprint), mm, 1.6)));
        }
    }

    private void print() {
        UiKit.guard(this, () -> {
            if ("A4".equals(format)) {
                ReceiptService.printA4(sale);
            } else {
                int mm = Integer.parseInt(format);
                ReceiptService.printThermal(ReceiptService.receiptLines(sale, mm, reprint), mm, "Receipt " + sale.invoiceNo, true);
            }
        });
    }

    private void savePdf() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File(sale.invoiceNo + ".pdf"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        UiKit.guard(this, () -> {
            File f = fc.getSelectedFile();
            if (!f.getName().toLowerCase().endsWith(".pdf")) {
                f = new File(f.getPath() + ".pdf");
            }
            ReceiptService.savePdf(sale, f.toPath());
            if (UiKit.confirm(this, "PDF saved:\n" + f + "\n\nOpen it now?") && Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(f);
            }
        });
    }

    public static void show(java.awt.Component parent, Sale sale, String format, boolean reprint) {
        String headline = reprint ? null : "Sale completed  —  " + sale.invoiceNo
                + (sale.changeAmount.signum() > 0 ? "   ·   Change: " + Money.cur(sale.changeAmount) : "")
                + (sale.dueTotal.signum() > 0 ? "   ·   Due: " + Money.cur(sale.dueTotal) : "");
        new ReceiptPreviewDialog(UiKit.window(parent), sale, format, reprint, headline).setVisible(true);
    }
}
