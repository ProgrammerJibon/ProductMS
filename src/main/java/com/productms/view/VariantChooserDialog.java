package com.productms.view;

import com.productms.model.Variant;
import com.productms.util.Money;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Window;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;

/** POS: pick size / colour / weight of a product with big touch buttons. */
public class VariantChooserDialog extends JDialog {

    private Variant result;

    private VariantChooserDialog(Window owner, String productName, List<Variant> variants) {
        super(owner, "Choose variant - " + productName, ModalityType.APPLICATION_MODAL);
        JPanel grid = new JPanel(new GridLayout(0, Math.min(3, variants.size()), 10, 10));
        for (Variant v : variants) {
            boolean out = v.available.signum() <= 0;
            String html = "<html><center><b style='font-size:13px'>" + (v.label().isEmpty() ? v.sku : v.label())
                    + "</b><br>" + Money.cur(v.retailPrice) + "<br><span style='color:" + (out ? "#DC2626" : "#16A34A") + "'>"
                    + (out ? "Out of stock" : Money.qty(v.available) + " " + v.unitShort + " available") + "</span></center></html>";
            JButton b = UiKit.plain(html);
            b.setPreferredSize(new Dimension(190, 86));
            b.setEnabled(!out);
            b.addActionListener(e -> {
                result = v;
                dispose();
            });
            grid.add(b);
        }
        JButton cancel = UiKit.plain("Cancel (Esc)");
        cancel.addActionListener(e -> dispose());
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        root.add(UiKit.title(productName, 17f), BorderLayout.NORTH);
        root.add(UiKit.scroll(grid));
        root.add(UiKit.rowRight(cancel), BorderLayout.SOUTH);
        UiKit.bind(root, "ESCAPE", this::dispose);
        setContentPane(root);
        pack();
        setSize(Math.min(getWidth(), 700), Math.min(getHeight(), 560));
        setLocationRelativeTo(owner);
    }

    public static Variant choose(java.awt.Component parent, String productName, List<Variant> variants) {
        VariantChooserDialog d = new VariantChooserDialog(UiKit.window(parent), productName, variants);
        d.setVisible(true);
        return d.result;
    }
}
