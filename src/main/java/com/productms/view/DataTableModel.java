package com.productms.view;

import com.productms.db.Jdbc.TableData;
import java.math.BigDecimal;
import javax.swing.table.AbstractTableModel;

/** Read-only table model for report results. */
public class DataTableModel extends AbstractTableModel {

    private TableData data = new TableData();

    public void setData(TableData d) {
        data = d == null ? new TableData() : d;
        fireTableStructureChanged();
    }

    public TableData data() {
        return data;
    }

    @Override
    public int getRowCount() {
        return data.rows.size();
    }

    @Override
    public int getColumnCount() {
        return data.columns.size();
    }

    @Override
    public String getColumnName(int c) {
        return data.columns.get(c);
    }

    @Override
    public Class<?> getColumnClass(int c) {
        for (java.util.List<Object> r : data.rows) {
            Object o = r.get(c);
            if (o != null) {
                if (o instanceof Number && !(o instanceof BigDecimal)) {
                    return o instanceof Integer ? Integer.class : Long.class;
                }
                return o.getClass();
            }
        }
        return Object.class;
    }

    @Override
    public Object getValueAt(int r, int c) {
        return data.rows.get(r).get(c);
    }

    /** Sum of a numeric column (for report footers). */
    public BigDecimal sum(int c) {
        BigDecimal s = BigDecimal.ZERO;
        for (java.util.List<Object> r : data.rows) {
            Object o = r.get(c);
            if (o instanceof BigDecimal) {
                s = s.add((BigDecimal) o);
            } else if (o instanceof Number) {
                s = s.add(BigDecimal.valueOf(((Number) o).longValue()));
            }
        }
        return s;
    }
}
