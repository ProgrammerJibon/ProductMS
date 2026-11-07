package com.productms.controller;

import com.productms.dao.MasterDataDAO;
import com.productms.model.Brand;
import com.productms.model.Category;
import com.productms.model.Supplier;
import com.productms.model.Unit;
import com.productms.view.CatalogView;
import com.productms.view.UiKit;
import java.util.List;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JTextField;

/** CRUD for categories / subcategories, brands, units and suppliers. */
public class CatalogController implements Module {

    private final CatalogView view = new CatalogView();
    private final MasterDataDAO dao = new MasterDataDAO();

    public CatalogController() {
        view.catCrud.add.addActionListener(e -> editCategory(new Category()));
        view.catCrud.edit.addActionListener(e -> {
            Category c = view.catModel.selected(view.catTable);
            if (c != null) {
                editCategory(c);
            }
        });
        view.catCrud.delete.addActionListener(e -> {
            Category c = view.catModel.selected(view.catTable);
            if (c != null && UiKit.confirm(view, "Delete category \"" + c.displayName() + "\"?")) {
                UiKit.guard(view, () -> {
                    dao.deleteCategory(c.categoryId);
                    onShow();
                });
            }
        });
        view.brandCrud.add.addActionListener(e -> editBrand(new Brand()));
        view.brandCrud.edit.addActionListener(e -> {
            Brand b = view.brandModel.selected(view.brandTable);
            if (b != null) {
                editBrand(b);
            }
        });
        view.brandCrud.delete.addActionListener(e -> {
            Brand b = view.brandModel.selected(view.brandTable);
            if (b != null && UiKit.confirm(view, "Delete brand \"" + b.name + "\"?")) {
                UiKit.guard(view, () -> {
                    dao.deleteBrand(b.brandId);
                    onShow();
                });
            }
        });
        view.unitCrud.add.addActionListener(e -> editUnit(new Unit()));
        view.unitCrud.edit.addActionListener(e -> {
            Unit u = view.unitModel.selected(view.unitTable);
            if (u != null) {
                editUnit(u);
            }
        });
        view.unitCrud.delete.addActionListener(e -> {
            Unit u = view.unitModel.selected(view.unitTable);
            if (u != null && UiKit.confirm(view, "Delete unit \"" + u.name + "\"?")) {
                UiKit.guard(view, () -> {
                    dao.deleteUnit(u.unitId);
                    onShow();
                });
            }
        });
        view.supCrud.add.addActionListener(e -> editSupplier(new Supplier()));
        view.supCrud.edit.addActionListener(e -> {
            Supplier s = view.supModel.selected(view.supTable);
            if (s != null) {
                editSupplier(s);
            }
        });
        view.supCrud.delete.addActionListener(e -> {
            Supplier s = view.supModel.selected(view.supTable);
            if (s != null && UiKit.confirm(view, "Delete supplier \"" + s.name + "\"?")) {
                UiKit.guard(view, () -> {
                    dao.deleteSupplier(s.supplierId);
                    onShow();
                });
            }
        });
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void onShow() throws Exception {
        view.catModel.setRows(dao.categories(false));
        view.brandModel.setRows(dao.brands(false));
        view.unitModel.setRows(dao.units());
        view.supModel.setRows(dao.suppliers(false));
    }

    /** Shows a form until it is cancelled or saving succeeds. */
    private boolean form(String title, UiKit.Form f, UiKit.Work save) {
        f.panel.setPreferredSize(new java.awt.Dimension(420, f.panel.getPreferredSize().height));
        while (JOptionPane.showConfirmDialog(view, f.panel, title, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            boolean[] ok = {false};
            UiKit.guard(view, () -> {
                save.run();
                ok[0] = true;
                onShow();
            });
            if (ok[0]) {
                return true;
            }
        }
        return false;
    }

    private void editCategory(Category c) {
        UiKit.guard(view, () -> {
            JTextField name = UiKit.field("Category name");
            name.setText(c.name);
            JComboBox<Object> parent = new JComboBox<>();
            parent.addItem("(top-level category)");
            List<Category> tops = dao.topCategories();
            for (Category t : tops) {
                if (t.categoryId != c.categoryId) {
                    parent.addItem(t);
                    if (c.parentId != null && c.parentId == t.categoryId) {
                        parent.setSelectedItem(t);
                    }
                }
            }
            JTextField desc = UiKit.field("Optional");
            desc.setText(c.description);
            JCheckBox active = new JCheckBox("Active", c.active);
            UiKit.Form f = new UiKit.Form();
            f.add("Name *", name);
            f.add("Parent", parent);
            f.add("Description", desc);
            f.full(active);
            form(c.categoryId == 0 ? "New category" : "Edit category", f, () -> {
                c.name = name.getText();
                Object p = parent.getSelectedItem();
                c.parentId = p instanceof Category ? ((Category) p).categoryId : null;
                c.description = desc.getText().isBlank() ? null : desc.getText().trim();
                c.active = active.isSelected();
                dao.saveCategory(c);
            });
        });
    }

    private void editBrand(Brand b) {
        JTextField name = UiKit.field("Brand name");
        name.setText(b.name);
        JCheckBox active = new JCheckBox("Active", b.active);
        UiKit.Form f = new UiKit.Form();
        f.add("Name *", name);
        f.full(active);
        form(b.brandId == 0 ? "New brand" : "Edit brand", f, () -> {
            b.name = name.getText();
            b.active = active.isSelected();
            dao.saveBrand(b);
        });
    }

    private void editUnit(Unit u) {
        JTextField name = UiKit.field("e.g. Kilogram");
        JTextField shortName = UiKit.field("e.g. KG");
        name.setText(u.name);
        shortName.setText(u.shortName);
        JCheckBox dec = new JCheckBox("Allow fractional quantities (e.g. 0.5 KG)", u.allowDecimal);
        UiKit.Form f = new UiKit.Form();
        f.add("Name *", name);
        f.add("Short name *", shortName);
        f.full(dec);
        form(u.unitId == 0 ? "New unit" : "Edit unit", f, () -> {
            u.name = name.getText();
            u.shortName = shortName.getText();
            u.allowDecimal = dec.isSelected();
            dao.saveUnit(u);
        });
    }

    private void editSupplier(Supplier s) {
        JTextField name = UiKit.field("Supplier name");
        JTextField phone = UiKit.field("Phone");
        JTextField address = UiKit.field("Address");
        name.setText(s.name);
        phone.setText(s.phone);
        address.setText(s.address);
        JCheckBox active = new JCheckBox("Active", s.active);
        UiKit.Form f = new UiKit.Form();
        f.add("Name *", name);
        f.add("Phone", phone);
        f.add("Address", address);
        f.full(active);
        form(s.supplierId == 0 ? "New supplier" : "Edit supplier", f, () -> {
            s.name = name.getText();
            s.phone = phone.getText().isBlank() ? null : phone.getText().trim();
            s.address = address.getText().isBlank() ? null : address.getText().trim();
            s.active = active.isSelected();
            dao.saveSupplier(s);
        });
    }
}
