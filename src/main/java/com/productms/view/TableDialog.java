package com.productms.view;

import com.productms.db.Jdbc;
import java.awt.BorderLayout;
import java.awt.Component;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;

/** Shows one or more read-only tables (history, audit, previews) in a dialog with CSV export. */
public final class TableDialog {

    private TableDialog() {
    }

    public static void show(Component parent, String title, String subtitle, Map<String, Jdbc.TableData> tabs) {
        JDialog d = new JDialog(UiKit.window(parent), title, java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        JTabbedPane tp = new JTabbedPane();
        Map<Integer, JTable> tables = new LinkedHashMap<>();
        for (Map.Entry<String, Jdbc.TableData> e : tabs.entrySet()) {
            DataTableModel m = new DataTableModel();
            m.setData(e.getValue());
            JTable t = new JTable(m);
            UiKit.styleTable(t);
            tables.put(tp.getTabCount(), t);
            JPanel tab = new JPanel(new BorderLayout());
            tab.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
            tab.add(UiKit.tableCard(t));
            tp.addTab(e.getKey() + " (" + m.getRowCount() + ")", tab);
        }
        JButton export = UiKit.plain("Export CSV");
        JButton close = UiKit.primary("Close");
        export.addActionListener(e -> TableExport.csv(d, tables.get(tp.getSelectedIndex()), title));
        close.addActionListener(e -> d.dispose());
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        if (subtitle != null) {
            root.add(UiKit.mutedLabel("<html>" + subtitle + "</html>"), BorderLayout.NORTH);
        }
        root.add(tabs.size() == 1 ? tp.getComponentAt(0) : tp);
        root.add(UiKit.rowRight(export, close), BorderLayout.SOUTH);
        UiKit.bind(root, "ESCAPE", d::dispose);
        d.setContentPane(root);
        d.setSize(1000, 560);
        d.setLocationRelativeTo(parent);
        d.setVisible(true);
    }

    public static void show(Component parent, String title, Jdbc.TableData data) {
        Map<String, Jdbc.TableData> m = new LinkedHashMap<>();
        m.put(title, data);
        show(parent, title, null, m);
    }
}
