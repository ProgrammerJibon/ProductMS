package com.productms.view;

import com.productms.model.Brand;
import com.productms.model.Category;
import com.productms.model.Supplier;
import com.productms.model.Unit;
import java.awt.BorderLayout;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;

/** Categories & subcategories, brands, units and suppliers. */
public class CatalogView extends JPanel {

    /** Add / edit / delete buttons for one tab. */
    public static class Crud {
        public final JButton add = UiKit.primary("+ Add");
        public final JButton edit = UiKit.plain("Edit");
        public final JButton delete = UiKit.plain("Delete");
    }

    public final JTabbedPane tabs = new JTabbedPane();
    public final RowTableModel<Category> catModel = new RowTableModel<Category>()
            .col("Category", String.class, c -> c.parentId == null ? c.name : "      ›  " + c.name)
            .col("Parent", String.class, c -> c.parentName)
            .col("Level", String.class, c -> c.parentId == null ? "Category" : "Subcategory")
            .col("Description", String.class, c -> c.description)
            .col("Status", String.class, c -> c.active ? "ACTIVE" : "INACTIVE");
    public final JTable catTable = new JTable(catModel);
    public final Crud catCrud = new Crud();

    public final RowTableModel<Brand> brandModel = new RowTableModel<Brand>()
            .col("Brand", String.class, b -> b.name)
            .col("Status", String.class, b -> b.active ? "ACTIVE" : "INACTIVE");
    public final JTable brandTable = new JTable(brandModel);
    public final Crud brandCrud = new Crud();

    public final RowTableModel<Unit> unitModel = new RowTableModel<Unit>()
            .col("Unit", String.class, u -> u.name)
            .col("Short", String.class, u -> u.shortName)
            .col("Fractions allowed", Boolean.class, u -> u.allowDecimal);
    public final JTable unitTable = new JTable(unitModel);
    public final Crud unitCrud = new Crud();

    public final RowTableModel<Supplier> supModel = new RowTableModel<Supplier>()
            .col("Supplier", String.class, s -> s.name)
            .col("Phone", String.class, s -> s.phone)
            .col("Address", String.class, s -> s.address)
            .col("Status", String.class, s -> s.active ? "ACTIVE" : "INACTIVE");
    public final JTable supTable = new JTable(supModel);
    public final Crud supCrud = new Crud();

    public CatalogView() {
        super(new BorderLayout());
        tabs.addTab("Categories & Subcategories", tab(catTable, catCrud));
        tabs.addTab("Brands", tab(brandTable, brandCrud));
        tabs.addTab("Units (PCS, KG, Litre, Box)", tab(unitTable, unitCrud));
        tabs.addTab("Suppliers", tab(supTable, supCrud));
        catTable.setAutoCreateRowSorter(false);
        add(UiKit.page("Catalog Setup", "Master data used by products and stock", null, tabs));
    }

    private static JPanel tab(JTable t, Crud c) {
        UiKit.styleTable(t);
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        p.add(UiKit.row(c.add, c.edit, c.delete), BorderLayout.NORTH);
        p.add(UiKit.tableCard(t));
        return p;
    }
}
