package com.productms.service;

import com.productms.db.Jdbc;
import com.productms.db.Jdbc.TableData;
import com.productms.util.Settings;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Inventory, sales and profit reports. Every report is a table that can be shown, printed or exported. */
public class ReportService {

    public static final String[] REPORTS = {
        "Daily Sales Summary",
        "Product-wise Sales",
        "Sales Profit by Invoice",
        "Product Profit Margins",
        "Inventory & Stock Valuation",
        "Low Stock",
        "Out of Stock",
        "Expiry Report",
        "Damaged & Expired Products",
        "Stock Movement Summary",
        "Payment Methods",
        "Sales by Cashier",
        "Sales by Category",
        "Customer Dues",
        "Customer Overdue",
        "Sales Returns",
        "Refunds by Method",
        "Discounts & Price Overrides",
        "Reorder Suggestions",
        "Stock Buckets (available / reserved / damaged)",
        "Price Change History",
        "Stock Count Differences",
        "Cash In / Out & Safe Drops",
        "Shift Reconciliation",
        "Warranty Register"
    };

    /** True when the report uses the date range. */
    public static boolean usesDates(String report) {
        switch (report) {
            case "Product Profit Margins":
            case "Inventory & Stock Valuation":
            case "Low Stock":
            case "Out of Stock":
            case "Expiry Report":
            case "Customer Dues":
            case "Customer Overdue":
            case "Reorder Suggestions":
            case "Stock Buckets (available / reserved / damaged)":
                return false;
            default:
                return true;
        }
    }

    // Returns per sale item in the period (net of cancellations, which are excluded through status)
    private static final String RETURN_VALUE = "(SELECT COALESCE(SUM(ri.amount),0) FROM sales_return_items ri "
            + "JOIN sales_returns r ON r.return_id = ri.return_id WHERE ri.sale_item_id = si.sale_item_id)";

    public TableData run(String report, LocalDate from, LocalDate to) throws SQLException {
        int expiryDays = Settings.getInt("expiry_alert_days", 30);
        switch (report) {
            case "Daily Sales Summary":
                // Gross = list value; Net = after discounts, excluding VAT; returns are booked on the day they happen
                // (cancelled invoices are excluded entirely, so their CANCEL reversals are excluded too).
                return Jdbc.table("SELECT d.day AS `Date`, d.invoices AS `Invoices`, d.gross AS `Gross Sales`, d.discounts AS `Discounts`, "
                        + "d.net AS `Net Sales (ex VAT)`, d.vat AS `VAT`, d.total AS `Total (incl VAT)`, "
                        + "COALESCE(r.ret_net,0) AS `Returns (ex VAT)`, COALESCE(r.ret_vat,0) AS `Return VAT`, "
                        + "d.net - COALESCE(r.ret_net,0) AS `Net after Returns`, d.cost AS `Cost of Sales`, "
                        + "COALESCE(r.ret_cost,0) AS `Cost Returned to Stock`, "
                        + "ROUND(d.net - COALESCE(r.ret_net,0) - (d.cost - COALESCE(r.ret_cost,0)), 2) AS `Gross Profit`, "
                        + "d.paid AS `Collected`, d.due AS `Due` FROM "
                        + "(SELECT DATE(s.created_at) AS day, COUNT(*) AS invoices, SUM(s.subtotal) AS gross, "
                        + " SUM(s.item_discount + s.cart_discount) AS discounts, SUM(s.grand_total - s.vat_total) AS net, "
                        + " SUM(s.vat_total) AS vat, SUM(s.grand_total) AS total, SUM(s.cost_total) AS cost, SUM(s.paid_total) AS paid, "
                        + " SUM(s.due_total) AS due FROM sales s WHERE s.status='COMPLETED' AND DATE(s.created_at) BETWEEN ? AND ? "
                        + " GROUP BY DATE(s.created_at)) d LEFT JOIN "
                        + "(SELECT DATE(r.created_at) AS day, SUM(r.total_amount - r.vat_amount) AS ret_net, SUM(r.vat_amount) AS ret_vat, "
                        + " SUM((SELECT COALESCE(SUM(ri.cost_amount),0) FROM sales_return_items ri WHERE ri.return_id = r.return_id "
                        + "  AND ri.item_condition = 'GOOD')) AS ret_cost FROM sales_returns r JOIN sales x ON x.sale_id = r.sale_id "
                        + " WHERE r.return_type <> 'CANCEL' AND x.status = 'COMPLETED' AND DATE(r.created_at) BETWEEN ? AND ? "
                        + " GROUP BY DATE(r.created_at)) r ON r.day = d.day ORDER BY d.day DESC", from, to, from, to);
            case "Product-wise Sales":
                return Jdbc.table("SELECT si.sku AS `SKU`, si.product_name AS `Product`, si.variant_label AS `Variant`, "
                        + "SUM(si.quantity) AS `Qty Sold`, SUM(si.returned_qty) AS `Qty Returned`, "
                        + "SUM(si.line_total) AS `Revenue`, SUM(" + RETURN_VALUE + ") AS `Returned Value`, "
                        + "ROUND(SUM((si.quantity - si.returned_qty) * si.unit_cost), 2) AS `Cost`, "
                        + "ROUND(SUM((si.line_total - si.vat_amount) * (si.quantity - si.returned_qty) / si.quantity "
                        + "  - (si.quantity - si.returned_qty) * si.unit_cost), 2) AS `Profit (net of returns)` "
                        + "FROM sale_items si JOIN sales s ON s.sale_id = si.sale_id "
                        + "WHERE s.status='COMPLETED' AND DATE(s.created_at) BETWEEN ? AND ? "
                        + "GROUP BY si.variant_id, si.sku, si.product_name, si.variant_label ORDER BY `Revenue` DESC", from, to);
            case "Sales Profit by Invoice":
                return Jdbc.table("SELECT s.invoice_no AS `Invoice`, s.created_at AS `Date`, c.name AS `Customer`, "
                        + "s.grand_total AS `Total`, s.vat_total AS `VAT`, s.cost_total AS `Cost`, "
                        + "(s.grand_total - s.vat_total - s.cost_total) AS `Profit`, "
                        + "ROUND((s.grand_total - s.vat_total - s.cost_total) * 100 / NULLIF(s.grand_total - s.vat_total, 0), 1) AS `Margin %` "
                        + "FROM sales s JOIN customers c ON c.customer_id=s.customer_id "
                        + "WHERE s.status='COMPLETED' AND DATE(s.created_at) BETWEEN ? AND ? ORDER BY s.sale_id DESC", from, to);
            case "Product Profit Margins":
                return Jdbc.table("SELECT v.sku AS `SKU`, p.name AS `Product`, CONCAT_WS(' / ', v.size, v.colour, v.weight) AS `Variant`, "
                        + "v.purchase_price AS `Cost`, v.retail_price AS `Retail`, (v.retail_price - v.purchase_price) AS `Retail Profit`, "
                        + "ROUND((v.retail_price - v.purchase_price) * 100 / NULLIF(v.retail_price,0), 1) AS `Retail Margin %`, "
                        + "v.wholesale_price AS `Wholesale`, ROUND((v.wholesale_price - v.purchase_price) * 100 / NULLIF(v.wholesale_price,0), 1) AS `Wholesale Margin %` "
                        + "FROM product_variants v JOIN products p ON p.product_id=v.product_id WHERE v.active=1 AND p.active=1 "
                        + "AND p.product_type <> 'BUNDLE' ORDER BY `Retail Margin %` DESC");
            case "Inventory & Stock Valuation":
                return Jdbc.table("SELECT v.sku AS `SKU`, p.name AS `Product`, CONCAT_WS(' / ', v.size, v.colour, v.weight) AS `Variant`, "
                        + "cat.name AS `Category`, b.name AS `Brand`, v.stock_qty AS `Stock`, u.short_name AS `Unit`, "
                        + "v.purchase_price AS `Unit Cost`, ROUND(v.stock_qty * v.purchase_price, 2) AS `Value at Cost`, "
                        + "v.retail_price AS `Retail Price`, ROUND(v.stock_qty * v.retail_price, 2) AS `Value at Retail`, "
                        + "ROUND(v.stock_qty * (v.retail_price - v.purchase_price), 2) AS `Potential Profit` "
                        + "FROM product_variants v JOIN products p ON p.product_id=v.product_id JOIN units u ON u.unit_id=p.unit_id "
                        + "LEFT JOIN categories cat ON cat.category_id=p.category_id LEFT JOIN brands b ON b.brand_id=p.brand_id "
                        + "WHERE p.product_type <> 'BUNDLE' ORDER BY `Value at Cost` DESC");
            case "Low Stock":
                return Jdbc.table("SELECT v.sku AS `SKU`, p.name AS `Product`, CONCAT_WS(' / ', v.size, v.colour, v.weight) AS `Variant`, "
                        + "v.stock_qty AS `In Stock`, v.reorder_level AS `Reorder Level`, "
                        + "GREATEST(v.reorder_level * 2 - v.stock_qty, 0) AS `Suggested Order Qty`, v.purchase_price AS `Unit Cost` "
                        + "FROM product_variants v JOIN products p ON p.product_id=v.product_id "
                        + "WHERE v.active=1 AND p.active=1 AND p.product_type <> 'BUNDLE' AND v.stock_qty > 0 AND v.stock_qty <= v.reorder_level "
                        + "ORDER BY v.stock_qty / NULLIF(v.reorder_level,0)");
            case "Out of Stock":
                return Jdbc.table("SELECT v.sku AS `SKU`, p.name AS `Product`, CONCAT_WS(' / ', v.size, v.colour, v.weight) AS `Variant`, "
                        + "v.reorder_level AS `Reorder Level`, "
                        + "(SELECT MAX(m.created_at) FROM stock_movements m WHERE m.variant_id=v.variant_id AND m.quantity < 0) AS `Last Sold / Out` "
                        + "FROM product_variants v JOIN products p ON p.product_id=v.product_id "
                        + "WHERE v.active=1 AND p.active=1 AND p.product_type <> 'BUNDLE' AND v.stock_qty <= 0 ORDER BY p.name");
            case "Expiry Report":
                return Jdbc.table("SELECT p.name AS `Product`, v.sku AS `SKU`, b.batch_no AS `Batch`, b.manufacture_date AS `Mfg Date`, "
                        + "b.expiry_date AS `Expiry`, DATEDIFF(b.expiry_date, CURDATE()) AS `Days Left`, "
                        + "CASE WHEN b.expiry_date < CURDATE() THEN 'EXPIRED' ELSE 'EXPIRING SOON' END AS `Status`, "
                        + "b.qty_remaining AS `Qty`, ROUND(b.qty_remaining * b.unit_cost, 2) AS `Value at Cost` "
                        + "FROM product_batches b JOIN product_variants v ON v.variant_id=b.variant_id JOIN products p ON p.product_id=v.product_id "
                        + "WHERE b.qty_remaining > 0 AND b.expiry_date IS NOT NULL AND b.expiry_date <= CURDATE() + INTERVAL ? DAY "
                        + "ORDER BY b.expiry_date", expiryDays);
            case "Damaged & Expired Products":
                return Jdbc.table("SELECT m.created_at AS `Date`, p.name AS `Product`, v.sku AS `SKU`, m.movement_type AS `Type`, "
                        + "ABS(m.quantity) AS `Qty`, m.unit_cost AS `Unit Cost`, ROUND(ABS(m.quantity) * m.unit_cost, 2) AS `Loss`, "
                        + "b.batch_no AS `Batch`, m.reference_no AS `Reference`, m.note AS `Note`, u.username AS `User` "
                        + "FROM stock_movements m JOIN product_variants v ON v.variant_id=m.variant_id JOIN products p ON p.product_id=v.product_id "
                        + "LEFT JOIN product_batches b ON b.batch_id=m.batch_id LEFT JOIN users u ON u.user_id=m.user_id "
                        + "WHERE m.movement_type IN ('DAMAGED','EXPIRED') AND DATE(m.created_at) BETWEEN ? AND ? ORDER BY m.movement_id DESC",
                        from, to);
            case "Stock Movement Summary":
                return Jdbc.table("SELECT v.sku AS `SKU`, p.name AS `Product`, CONCAT_WS(' / ', v.size, v.colour, v.weight) AS `Variant`, "
                        + "SUM(CASE WHEN m.movement_type IN ('OPENING','STOCK_IN') THEN m.quantity ELSE 0 END) AS `Received`, "
                        + "-SUM(CASE WHEN m.movement_type = 'SALE' THEN m.quantity ELSE 0 END) AS `Sold`, "
                        + "SUM(CASE WHEN m.movement_type IN ('RETURN','SALE_CANCEL') THEN m.quantity ELSE 0 END) AS `Returned`, "
                        + "SUM(CASE WHEN m.movement_type IN ('ADJUSTMENT_IN','ADJUSTMENT_OUT','STOCK_OUT') THEN m.quantity ELSE 0 END) AS `Adjusted`, "
                        + "-SUM(CASE WHEN m.movement_type IN ('DAMAGED','EXPIRED') THEN m.quantity ELSE 0 END) AS `Written Off`, "
                        + "SUM(m.quantity) AS `Net Change`, v.stock_qty AS `Current Stock` "
                        + "FROM stock_movements m JOIN product_variants v ON v.variant_id=m.variant_id JOIN products p ON p.product_id=v.product_id "
                        + "WHERE DATE(m.created_at) BETWEEN ? AND ? GROUP BY v.variant_id, v.sku, p.name, v.size, v.colour, v.weight, v.stock_qty "
                        + "ORDER BY p.name", from, to);
            case "Payment Methods":
                return Jdbc.table("SELECT pm.method AS `Method`, pm.payment_type AS `Type`, COUNT(*) AS `Transactions`, SUM(pm.amount) AS `Amount` "
                        + "FROM payments pm JOIN sales s ON s.sale_id = pm.sale_id "
                        + "WHERE DATE(pm.created_at) BETWEEN ? AND ? GROUP BY pm.method, pm.payment_type ORDER BY `Amount` DESC", from, to);
            case "Sales by Cashier":
                return Jdbc.table("SELECT u.full_name AS `Cashier`, COUNT(*) AS `Invoices`, SUM(s.grand_total) AS `Sales`, "
                        + "ROUND(AVG(s.grand_total), 2) AS `Avg Basket`, SUM(s.item_discount + s.cart_discount) AS `Discounts`, "
                        + "SUM(s.grand_total - s.vat_total - s.cost_total) AS `Gross Profit` "
                        + "FROM sales s JOIN users u ON u.user_id=s.user_id WHERE s.status='COMPLETED' AND DATE(s.created_at) BETWEEN ? AND ? "
                        + "GROUP BY u.user_id, u.full_name ORDER BY `Sales` DESC", from, to);
            case "Sales by Category":
                return Jdbc.table("SELECT COALESCE(par.name, cat.name, '(none)') AS `Category`, SUM(si.quantity) AS `Qty`, "
                        + "SUM(si.line_total) AS `Revenue`, ROUND(SUM(si.line_total - si.vat_amount - si.quantity * si.unit_cost), 2) AS `Gross Profit` "
                        + "FROM sale_items si JOIN sales s ON s.sale_id=si.sale_id JOIN product_variants v ON v.variant_id=si.variant_id "
                        + "JOIN products p ON p.product_id=v.product_id LEFT JOIN categories cat ON cat.category_id=p.category_id "
                        + "LEFT JOIN categories par ON par.category_id=cat.parent_id "
                        + "WHERE s.status='COMPLETED' AND DATE(s.created_at) BETWEEN ? AND ? GROUP BY `Category` ORDER BY `Revenue` DESC", from, to);
            case "Customer Dues":
                return Jdbc.table("SELECT c.name AS `Customer`, c.phone AS `Phone`, c.customer_type AS `Type`, COUNT(*) AS `Due Invoices`, "
                        + "SUM(s.due_total) AS `Total Due`, MIN(s.created_at) AS `Oldest Due Since` "
                        + "FROM sales s JOIN customers c ON c.customer_id=s.customer_id WHERE s.status='COMPLETED' AND s.due_total > 0 "
                        + "GROUP BY c.customer_id, c.name, c.phone, c.customer_type ORDER BY `Total Due` DESC");
            case "Sales Returns":
                return new SaleService().returns(from, to, "");
            case "Customer Overdue":
                return Jdbc.table("SELECT c.name AS `Customer`, c.phone AS `Phone`, s.invoice_no AS `Invoice`, s.created_at AS `Date`, "
                        + "DATEDIFF(CURDATE(), DATE(s.created_at)) AS `Days Old`, c.payment_terms_days AS `Terms`, "
                        + "s.due_total AS `Due`, c.credit_limit AS `Credit Limit` FROM sales s JOIN customers c ON c.customer_id = s.customer_id "
                        + "WHERE s.status = 'COMPLETED' AND s.due_total > 0 AND s.created_at < NOW() - INTERVAL c.payment_terms_days DAY "
                        + "ORDER BY `Days Old` DESC");
            case "Refunds by Method":
                return Jdbc.table("SELECT rp.method AS `Method`, r.return_type AS `Type`, COUNT(*) AS `Refunds`, SUM(rp.amount) AS `Amount`, "
                        + "SUM(CASE WHEN rp.original_payment_id IS NOT NULL THEN rp.amount ELSE 0 END) AS `To Original Payment` "
                        + "FROM refund_payments rp JOIN sales_returns r ON r.return_id = rp.return_id "
                        + "WHERE DATE(rp.created_at) BETWEEN ? AND ? GROUP BY rp.method, r.return_type ORDER BY `Amount` DESC", from, to);
            case "Discounts & Price Overrides":
                return Jdbc.table("SELECT s.invoice_no AS `Invoice`, s.created_at AS `Date`, u.username AS `Cashier`, si.product_name AS `Product`, "
                        + "si.quantity AS `Qty`, si.original_price AS `List Price`, si.unit_price AS `Sold At`, si.discount_amount AS `Discount`, "
                        + "s.approved_by AS `Approved by` FROM sale_items si JOIN sales s ON s.sale_id = si.sale_id JOIN users u ON u.user_id = s.user_id "
                        + "WHERE s.status='COMPLETED' AND (si.original_price IS NOT NULL OR si.discount_amount > 0) "
                        + "AND DATE(s.created_at) BETWEEN ? AND ? ORDER BY s.sale_id DESC", from, to);
            case "Reorder Suggestions":
                // average daily sales over 30 days x 14 days cover, at least the usual order quantity
                return Jdbc.table("SELECT p.name AS `Product`, CONCAT_WS(' / ', v.size, v.colour, v.weight) AS `Variant`, v.sku AS `SKU`, "
                        + "v.stock_qty AS `In Stock`, v.reorder_level AS `Reorder At`, "
                        + "ROUND(COALESCE(sold.qty,0) / 30, 2) AS `Avg Daily Sales`, "
                        + "GREATEST(v.reorder_qty, CEIL(COALESCE(sold.qty,0) / 30 * 14) - v.stock_qty, v.reorder_level - v.stock_qty, 0) AS `Suggested Order`, "
                        + "v.purchase_price AS `Last Avg Cost`, (SELECT s.name FROM stock_movements m JOIN suppliers s ON s.supplier_id = m.supplier_id "
                        + " WHERE m.variant_id = v.variant_id AND m.movement_type = 'STOCK_IN' ORDER BY m.movement_id DESC LIMIT 1) AS `Last Supplier` "
                        + "FROM product_variants v JOIN products p ON p.product_id = v.product_id LEFT JOIN "
                        + "(SELECT si.variant_id, SUM(si.quantity - si.returned_qty) AS qty FROM sale_items si JOIN sales s ON s.sale_id = si.sale_id "
                        + " WHERE s.status = 'COMPLETED' AND s.created_at >= NOW() - INTERVAL 30 DAY GROUP BY si.variant_id) sold ON sold.variant_id = v.variant_id "
                        + "WHERE v.active = 1 AND p.active = 1 AND p.deleted = 0 AND p.product_type <> 'BUNDLE' "
                        + "AND (v.stock_qty <= v.reorder_level OR v.stock_qty < COALESCE(sold.qty,0) / 30 * 7) ORDER BY v.stock_qty / NULLIF(v.reorder_level, 0)");
            case "Stock Buckets (available / reserved / damaged)":
                return Jdbc.table("SELECT p.name AS `Product`, v.sku AS `SKU`, v.stock_qty AS `Sellable`, "
                        + "COALESCE((SELECT SUM(r.quantity) FROM stock_reservations r WHERE r.variant_id = v.variant_id AND r.expires_at > NOW()),0) AS `Reserved`, "
                        + "v.stock_qty - COALESCE((SELECT SUM(r.quantity) FROM stock_reservations r WHERE r.variant_id = v.variant_id AND r.expires_at > NOW()),0) "
                        + " - CASE WHEN p.track_batch = 1 THEN COALESCE((SELECT SUM(b.qty_remaining) FROM product_batches b WHERE b.variant_id = v.variant_id "
                        + "   AND b.expiry_date < CURDATE()),0) ELSE 0 END AS `Available`, "
                        + "CASE WHEN p.track_batch = 1 THEN COALESCE((SELECT SUM(b.qty_remaining) FROM product_batches b WHERE b.variant_id = v.variant_id "
                        + "   AND b.expiry_date < CURDATE()),0) ELSE 0 END AS `Expired (not sellable)`, "
                        + "v.damaged_qty AS `Damaged`, v.stock_qty + v.damaged_qty AS `Physical`, "
                        + "ROUND((v.stock_qty + v.damaged_qty) * v.purchase_price, 2) AS `Physical Value` "
                        + "FROM product_variants v JOIN products p ON p.product_id = v.product_id WHERE p.product_type <> 'BUNDLE' AND p.deleted = 0 "
                        + "ORDER BY p.name");
            case "Price Change History":
                return Jdbc.table("SELECT h.effective_at AS `Date`, p.name AS `Product`, v.sku AS `SKU`, h.field AS `Price`, h.old_value AS `Old`, "
                        + "h.new_value AS `New`, ROUND((h.new_value - h.old_value) * 100 / NULLIF(h.old_value, 0), 1) AS `Change %`, "
                        + "h.reason AS `Reason`, u.username AS `By` FROM price_history h JOIN product_variants v ON v.variant_id = h.variant_id "
                        + "JOIN products p ON p.product_id = v.product_id LEFT JOIN users u ON u.user_id = h.user_id "
                        + "WHERE h.old_value IS NOT NULL AND DATE(h.effective_at) BETWEEN ? AND ? ORDER BY h.history_id DESC", from, to);
            case "Stock Count Differences":
                return Jdbc.table("SELECT c.count_no AS `Count`, c.name AS `Name`, c.status AS `Status`, p.name AS `Product`, v.sku AS `SKU`, "
                        + "i.system_qty AS `System`, i.counted_qty AS `Counted`, i.counted_qty - i.system_qty AS `Difference`, "
                        + "ROUND((i.counted_qty - i.system_qty) * i.unit_cost, 2) AS `Value` FROM stock_count_items i "
                        + "JOIN stock_counts c ON c.count_id = i.count_id JOIN product_variants v ON v.variant_id = i.variant_id "
                        + "JOIN products p ON p.product_id = v.product_id WHERE i.counted_qty IS NOT NULL AND i.counted_qty <> i.system_qty "
                        + "AND DATE(c.created_at) BETWEEN ? AND ? ORDER BY c.count_id DESC, p.name", from, to);
            case "Cash In / Out & Safe Drops":
                return Jdbc.table("SELECT m.created_at AS `Time`, m.shift_id AS `Shift`, u.full_name AS `By`, m.type AS `Type`, "
                        + "m.amount AS `Amount`, m.reason AS `Reason` FROM cash_movements m JOIN users u ON u.user_id = m.user_id "
                        + "WHERE DATE(m.created_at) BETWEEN ? AND ? ORDER BY m.movement_id DESC", from, to);
            case "Shift Reconciliation":
                return Jdbc.table("SELECT s.shift_id AS `Shift`, u.full_name AS `Cashier`, s.closed_at AS `Closed`, r.method AS `Method`, "
                        + "r.expected AS `Expected`, r.declared AS `Counted / Declared`, r.difference AS `Difference` "
                        + "FROM shift_reconciliation r JOIN shifts s ON s.shift_id = r.shift_id JOIN users u ON u.user_id = s.user_id "
                        + "WHERE DATE(s.closed_at) BETWEEN ? AND ? ORDER BY s.shift_id DESC, r.method", from, to);
            case "Warranty Register":
                return Jdbc.table("SELECT s.invoice_no AS `Invoice`, s.created_at AS `Sold`, c.name AS `Customer`, c.phone AS `Phone`, "
                        + "si.product_name AS `Product`, si.serial_numbers AS `Serials`, si.warranty_months AS `Months`, "
                        + "DATE(DATE_ADD(s.created_at, INTERVAL si.warranty_months MONTH)) AS `Warranty Until`, "
                        + "CASE WHEN DATE_ADD(s.created_at, INTERVAL si.warranty_months MONTH) >= NOW() THEN 'IN WARRANTY' ELSE 'WARRANTY EXPIRED' END AS `Status` "
                        + "FROM sale_items si JOIN sales s ON s.sale_id = si.sale_id JOIN customers c ON c.customer_id = s.customer_id "
                        + "WHERE si.warranty_months > 0 AND s.status = 'COMPLETED' AND DATE(s.created_at) BETWEEN ? AND ? "
                        + "ORDER BY s.sale_id DESC", from, to);
            default:
                throw new IllegalArgumentException(report);
        }
    }

    // ------------------------------------------------------------------ dashboard

    public Map<String, BigDecimal> dashboardStats() throws SQLException {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        int days = Settings.getInt("expiry_alert_days", 30);
        m.put("todaySales", Jdbc.decimal("SELECT COALESCE(SUM(grand_total),0) FROM sales WHERE status='COMPLETED' AND DATE(created_at)=CURDATE()"));
        m.put("todayInvoices", Jdbc.decimal("SELECT COUNT(*) FROM sales WHERE status='COMPLETED' AND DATE(created_at)=CURDATE()"));
        m.put("todayProfit", Jdbc.decimal("SELECT COALESCE(SUM(grand_total - vat_total - cost_total),0) FROM sales WHERE status='COMPLETED' AND DATE(created_at)=CURDATE()"));
        m.put("todayReturns", Jdbc.decimal("SELECT COALESCE(SUM(total_amount),0) FROM sales_returns WHERE DATE(created_at)=CURDATE()"));
        m.put("monthSales", Jdbc.decimal("SELECT COALESCE(SUM(grand_total),0) FROM sales WHERE status='COMPLETED' AND created_at >= DATE_FORMAT(CURDATE(), '%Y-%m-01')"));
        m.put("stockValue", Jdbc.decimal("SELECT COALESCE(SUM(stock_qty * purchase_price),0) FROM product_variants"));
        m.put("totalDue", Jdbc.decimal("SELECT COALESCE(SUM(due_total),0) FROM sales WHERE status='COMPLETED'"));
        m.put("products", Jdbc.decimal("SELECT COUNT(*) FROM products WHERE active=1"));
        m.put("lowStock", Jdbc.decimal("SELECT COUNT(*) FROM product_variants v JOIN products p ON p.product_id=v.product_id "
                + "WHERE v.active=1 AND p.active=1 AND p.product_type<>'BUNDLE' AND v.stock_qty > 0 AND v.stock_qty <= v.reorder_level"));
        m.put("outOfStock", Jdbc.decimal("SELECT COUNT(*) FROM product_variants v JOIN products p ON p.product_id=v.product_id "
                + "WHERE v.active=1 AND p.active=1 AND p.product_type<>'BUNDLE' AND v.stock_qty <= 0"));
        m.put("expiringSoon", Jdbc.decimal("SELECT COUNT(*) FROM product_batches WHERE qty_remaining > 0 AND expiry_date >= CURDATE() "
                + "AND expiry_date <= CURDATE() + INTERVAL ? DAY", days));
        m.put("expired", Jdbc.decimal("SELECT COUNT(*) FROM product_batches WHERE qty_remaining > 0 AND expiry_date < CURDATE()"));
        return m;
    }

    /** Net sales of the last 7 days, oldest first: date -> amount. */
    public Map<LocalDate, BigDecimal> last7Days() throws SQLException {
        Map<LocalDate, BigDecimal> m = new LinkedHashMap<>();
        for (int i = 6; i >= 0; i--) {
            m.put(LocalDate.now().minusDays(i), BigDecimal.ZERO);
        }
        for (Object[] r : Jdbc.list("SELECT DATE(created_at), SUM(grand_total) FROM sales WHERE status='COMPLETED' "
                + "AND created_at >= CURDATE() - INTERVAL 6 DAY GROUP BY DATE(created_at)",
                rs -> new Object[]{rs.getDate(1).toLocalDate(), rs.getBigDecimal(2)})) {
            m.put((LocalDate) r[0], (BigDecimal) r[1]);
        }
        return m;
    }

    public TableData recentSales() throws SQLException {
        return Jdbc.table("SELECT s.invoice_no AS `Invoice`, DATE_FORMAT(s.created_at, '%d %b %h:%i %p') AS `Time`, "
                + "c.name AS `Customer`, s.grand_total AS `Total`, s.payment_status AS `Payment`, s.status AS `Status` "
                + "FROM sales s JOIN customers c ON c.customer_id=s.customer_id ORDER BY s.sale_id DESC LIMIT 12");
    }

    public TableData stockAlerts() throws SQLException {
        int days = Settings.getInt("expiry_alert_days", 30);
        return Jdbc.table("SELECT * FROM ("
                + "SELECT CASE WHEN v.stock_qty <= 0 THEN 'OUT OF STOCK' ELSE 'LOW STOCK' END AS `Alert`, "
                + "CONCAT(p.name, IF(CONCAT_WS(' / ', v.size, v.colour, v.weight) = '', '', CONCAT(' (', CONCAT_WS(' / ', v.size, v.colour, v.weight), ')'))) AS `Product`, "
                + "CONCAT(TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM v.stock_qty)), ' left (reorder at ', TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM v.reorder_level)), ')') AS `Detail` "
                + "FROM product_variants v JOIN products p ON p.product_id=v.product_id "
                + "WHERE v.active=1 AND p.active=1 AND p.product_type<>'BUNDLE' AND v.stock_qty <= v.reorder_level "
                + "UNION ALL "
                + "SELECT CASE WHEN b.expiry_date < CURDATE() THEN 'EXPIRED' ELSE 'EXPIRING' END, p.name, "
                + "CONCAT('Batch ', b.batch_no, ': ', TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM b.qty_remaining)), ' units, ', "
                + "  CASE WHEN b.expiry_date < CURDATE() THEN CONCAT('expired ', DATEDIFF(CURDATE(), b.expiry_date), ' day(s) ago') "
                + "  ELSE CONCAT('expires in ', DATEDIFF(b.expiry_date, CURDATE()), ' day(s)') END) "
                + "FROM product_batches b JOIN product_variants v ON v.variant_id=b.variant_id JOIN products p ON p.product_id=v.product_id "
                + "WHERE b.qty_remaining > 0 AND b.expiry_date <= CURDATE() + INTERVAL ? DAY) x "
                + "ORDER BY FIELD(`Alert`, 'EXPIRED', 'OUT OF STOCK', 'EXPIRING', 'LOW STOCK')", days);
    }

    public TableData topProducts() throws SQLException {
        return Jdbc.table("SELECT si.product_name AS `Product`, SUM(si.quantity) AS `Qty`, SUM(si.line_total) AS `Revenue` "
                + "FROM sale_items si JOIN sales s ON s.sale_id=si.sale_id WHERE s.status='COMPLETED' "
                + "AND s.created_at >= CURDATE() - INTERVAL 30 DAY GROUP BY si.product_name ORDER BY `Revenue` DESC LIMIT 8");
    }
}
