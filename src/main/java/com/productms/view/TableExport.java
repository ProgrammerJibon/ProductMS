package com.productms.view;

import com.productms.util.Csv;
import java.awt.Component;
import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JFileChooser;
import javax.swing.JTable;
import javax.swing.table.TableModel;

/** Exports any table (as currently sorted) to CSV and prints tables. */
public final class TableExport {

    private TableExport() {
    }

    public static void csv(Component parent, JTable table, String baseName) {
        if (table.getRowCount() == 0) {
            UiKit.warn(parent, "Nothing to export.");
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File(baseName.replaceAll("[^A-Za-z0-9_-]+", "_") + "_" + LocalDate.now() + ".csv"));
        if (fc.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        UiKit.guard(parent, () -> {
            TableModel m = table.getModel();
            List<String> header = new ArrayList<>();
            for (int c = 0; c < m.getColumnCount(); c++) {
                header.add(m.getColumnName(c));
            }
            List<List<Object>> rows = new ArrayList<>();
            for (int v = 0; v < table.getRowCount(); v++) {
                int r = table.convertRowIndexToModel(v);
                List<Object> row = new ArrayList<>();
                for (int c = 0; c < m.getColumnCount(); c++) {
                    Object o = m.getValueAt(r, c);
                    if (o instanceof BigDecimal) {
                        o = ((BigDecimal) o).toPlainString();
                    } else if (o instanceof LocalDateTime) {
                        o = ((LocalDateTime) o).format(UiKit.DATE_TIME);
                    } else if (o instanceof LocalDate) {
                        o = ((LocalDate) o).format(UiKit.DATE);
                    }
                    row.add(o);
                }
                rows.add(row);
            }
            Csv.write(fc.getSelectedFile().toPath(), header, rows);
            UiKit.info(parent, rows.size() + " row(s) exported to\n" + fc.getSelectedFile());
        });
    }

    public static void print(Component parent, JTable table, String title) {
        UiKit.guard(parent, () -> table.print(JTable.PrintMode.FIT_WIDTH,
                new java.text.MessageFormat(title), new java.text.MessageFormat("Page {0}")));
    }
}
