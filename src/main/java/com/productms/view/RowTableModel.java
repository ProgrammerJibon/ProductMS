package com.productms.view;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;

/**
 * Table model over a list of objects; columns are declared with getters:
 * <pre>new RowTableModel&lt;Customer&gt;().col("Name", String.class, c -&gt; c.name)</pre>
 */
public class RowTableModel<T> extends AbstractTableModel {

    private static class Col<T> {
        final String name;
        final Class<?> type;
        final Function<T, Object> getter;

        Col(String name, Class<?> type, Function<T, Object> getter) {
            this.name = name;
            this.type = type;
            this.getter = getter;
        }
    }

    private final List<Col<T>> cols = new ArrayList<>();
    private List<T> rows = new ArrayList<>();

    public RowTableModel<T> col(String name, Class<?> type, Function<T, Object> getter) {
        cols.add(new Col<>(name, type, getter));
        return this;
    }

    public void setRows(List<T> rows) {
        this.rows = new ArrayList<>(rows);
        fireTableDataChanged();
    }

    public List<T> rows() {
        return rows;
    }

    public T row(int modelIndex) {
        return rows.get(modelIndex);
    }

    /** The object behind the selected row of the table (works with sorting), or null. */
    public T selected(JTable table) {
        int v = table.getSelectedRow();
        return v < 0 ? null : rows.get(table.convertRowIndexToModel(v));
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return cols.size();
    }

    @Override
    public String getColumnName(int c) {
        return cols.get(c).name;
    }

    @Override
    public Class<?> getColumnClass(int c) {
        return cols.get(c).type;
    }

    @Override
    public Object getValueAt(int r, int c) {
        return cols.get(c).getter.apply(rows.get(r));
    }
}
