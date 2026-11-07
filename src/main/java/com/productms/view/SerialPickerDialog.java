package com.productms.view;

import java.awt.BorderLayout;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;

/** Select (or scan) serial numbers / IMEIs being sold or returned. */
public class SerialPickerDialog extends JDialog {

    private final DefaultListModel<String> listModel = new DefaultListModel<>();
    private final JList<String> list = new JList<>(listModel);
    private final JLabel lblCount = UiKit.mutedLabel(" ");
    private List<String> result;

    private SerialPickerDialog(Window owner, String title, List<String> available, Collection<String> preselected,
            Integer exactCount) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        available.forEach(listModel::addElement);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < available.size(); i++) {
            if (preselected.contains(available.get(i))) {
                idx.add(i);
            }
        }
        list.setSelectedIndices(idx.stream().mapToInt(Integer::intValue).toArray());
        list.addListSelectionListener(e -> lblCount.setText(list.getSelectedIndices().length + " selected"
                + (exactCount != null ? " (need " + exactCount + ")" : "")));
        lblCount.setText(list.getSelectedIndices().length + " selected" + (exactCount != null ? " (need " + exactCount + ")" : ""));

        JTextField scan = UiKit.search("Scan serial / IMEI and press Enter to tick it");
        scan.addActionListener(e -> {
            String s = scan.getText().trim();
            int i = available.indexOf(s);
            if (i < 0) {
                UiKit.warn(this, "Serial " + s + " is not available here.");
            } else {
                list.addSelectionInterval(i, i);
                list.ensureIndexIsVisible(i);
            }
            scan.setText("");
        });
        JButton ok = UiKit.primary("OK");
        JButton cancel = UiKit.plain("Cancel");
        ok.addActionListener(e -> {
            List<String> sel = list.getSelectedValuesList();
            if (sel.isEmpty() || (exactCount != null && sel.size() != exactCount)) {
                UiKit.warn(this, exactCount != null ? "Select exactly " + exactCount + " serial number(s)."
                        : "Select at least one serial number.");
                return;
            }
            result = new ArrayList<>(sel);
            dispose();
        });
        cancel.addActionListener(e -> dispose());
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        root.add(scan, BorderLayout.NORTH);
        root.add(UiKit.scroll(list));
        JPanel south = new JPanel(new BorderLayout());
        south.add(lblCount, BorderLayout.WEST);
        south.add(UiKit.rowRight(cancel, ok), BorderLayout.EAST);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        setSize(440, 420);
        setLocationRelativeTo(owner);
    }

    /** Returns the chosen serials, or null if cancelled. exactCount may be null (any number &gt; 0). */
    public static List<String> pick(java.awt.Component parent, String title, List<String> available,
            Collection<String> preselected, Integer exactCount) {
        if (available.isEmpty()) {
            UiKit.warn(parent, "No serial numbers available.");
            return null;
        }
        SerialPickerDialog d = new SerialPickerDialog(UiKit.window(parent), title, available, preselected, exactCount);
        d.setVisible(true);
        return d.result;
    }
}
