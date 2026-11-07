package com.productms.dao;

import com.productms.db.Jdbc;
import com.productms.model.Customer;
import com.productms.util.AppException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** Data access for customers, including their outstanding due balance. */
public class CustomerDAO {

    private static final String SELECT =
            "SELECT c.*, "
            + " COALESCE((SELECT SUM(s.due_total) FROM sales s WHERE s.customer_id=c.customer_id AND s.status='COMPLETED'),0) AS due, "
            + " COALESCE((SELECT SUM(s.grand_total) FROM sales s WHERE s.customer_id=c.customer_id AND s.status='COMPLETED'),0) AS purchases "
            + "FROM customers c ";

    private static Customer map(ResultSet rs) throws SQLException {
        Customer c = new Customer();
        c.customerId = rs.getInt("customer_id");
        c.name = rs.getString("name");
        c.phone = rs.getString("phone");
        c.email = rs.getString("email");
        c.address = rs.getString("address");
        c.customerType = rs.getString("customer_type");
        c.walkIn = rs.getBoolean("is_walk_in");
        c.active = rs.getBoolean("active");
        c.due = rs.getBigDecimal("due");
        c.totalPurchases = rs.getBigDecimal("purchases");
        return c;
    }

    public List<Customer> search(String text, boolean activeOnly) throws SQLException {
        String like = "%" + (text == null ? "" : text.trim()) + "%";
        return Jdbc.list(SELECT + "WHERE (c.name LIKE ? OR c.phone LIKE ?) "
                + (activeOnly ? "AND c.active=1 " : "")
                + "ORDER BY c.is_walk_in DESC, c.name", CustomerDAO::map, like, like);
    }

    public Customer findById(int id) throws SQLException {
        return Jdbc.one(SELECT + "WHERE c.customer_id=?", CustomerDAO::map, id);
    }

    public Customer walkIn() throws SQLException {
        Customer c = Jdbc.one(SELECT + "WHERE c.is_walk_in=1 ORDER BY c.customer_id LIMIT 1", CustomerDAO::map);
        if (c == null) {
            int id = Jdbc.insert("INSERT INTO customers (name, is_walk_in) VALUES ('Walk-in Customer', 1)");
            c = findById(id);
        }
        return c;
    }

    public void save(Customer c) throws SQLException {
        if (c.name == null || c.name.isBlank()) {
            throw new AppException("Customer name is required.");
        }
        String phone = c.phone == null || c.phone.isBlank() ? null : c.phone.trim();
        if (phone != null && !phone.matches("[0-9+\\- ]{6,20}")) {
            throw new AppException("Phone number looks invalid.");
        }
        if (phone != null && Jdbc.count("SELECT COUNT(*) FROM customers WHERE phone=? AND customer_id<>?", phone, c.customerId) > 0) {
            throw new AppException("Another customer already has phone " + phone + ".");
        }
        if (c.customerId == 0) {
            c.customerId = Jdbc.insert("INSERT INTO customers (name, phone, email, address, customer_type, active) VALUES (?,?,?,?,?,?)",
                    c.name.trim(), phone, blank(c.email), blank(c.address), c.customerType, c.active);
        } else {
            Jdbc.update("UPDATE customers SET name=?, phone=?, email=?, address=?, customer_type=?, active=? WHERE customer_id=?",
                    c.name.trim(), phone, blank(c.email), blank(c.address), c.customerType, c.active, c.customerId);
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
