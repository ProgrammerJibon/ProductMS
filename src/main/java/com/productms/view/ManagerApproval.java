package com.productms.view;

import com.productms.dao.UserDAO;
import com.productms.model.User;
import com.productms.util.AppException;
import java.awt.Component;
import java.awt.GridLayout;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;

/**
 * Asks a manager / admin to approve something on the spot (discount above limit, price override,
 * credit limit, refund...). The approver's username is stored with the transaction and in the audit log.
 */
public final class ManagerApproval {

    private ManagerApproval() {
    }

    /** Returns the approving manager's username, or null when cancelled / wrong credentials. */
    public static String ask(Component parent, List<String> reasons) {
        JTextField user = UiKit.field("Manager username");
        JPasswordField pass = new JPasswordField();
        pass.putClientProperty("JTextField.placeholderText", "Password");
        JPanel p = new JPanel(new GridLayout(0, 1, 4, 4));
        p.add(new JLabel("<html><b>Manager approval needed</b><br>&bull; " + String.join("<br>&bull; ",
                reasons.stream().map(r -> r.replace("&", "&amp;").replace("<", "&lt;")).toArray(String[]::new)) + "</html>"));
        p.add(user);
        p.add(pass);
        while (JOptionPane.showConfirmDialog(parent, p, "Manager approval", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION) {
            try {
                User u = new UserDAO().verifyManager(user.getText().trim(), new String(pass.getPassword()));
                if (u != null) {
                    return u.username;
                }
                UiKit.warn(parent, "Approval failed: not a manager / admin account, or wrong password.");
            } catch (AppException e) {
                UiKit.warn(parent, e.getMessage());
                return null;
            } catch (java.sql.SQLException e) {
                UiKit.error(parent, e.getMessage());
                return null;
            }
            pass.setText("");
        }
        return null;
    }

    public static String ask(Component parent, String reason) {
        return ask(parent, List.of(reason));
    }
}
