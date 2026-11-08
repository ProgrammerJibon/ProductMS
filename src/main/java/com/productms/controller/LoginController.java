package com.productms.controller;

import com.productms.dao.UserDAO;
import com.productms.service.AuditService;
import com.productms.service.ShiftService;
import com.productms.util.Session;
import com.productms.util.Settings;
import com.productms.view.ChangePasswordDialog;
import com.productms.view.LoginView;
import com.productms.view.UiKit;
import java.sql.SQLException;

/**
 * Signs users in. Lockout after repeated failures is stored in the database (time based),
 * and accounts flagged with a temporary / demo password must change it before continuing.
 */
public class LoginController {

    private final LoginView view;
    private final UserDAO userDAO = new UserDAO();

    public LoginController() {
        view = new LoginView(Settings.shopName());
        view.btnLogin.addActionListener(e -> login());
    }

    public void start() {
        view.setVisible(true);
        view.txtUsername.requestFocusInWindow();
    }

    private void login() {
        String username = view.txtUsername.getText().trim();
        String password = new String(view.txtPassword.getPassword());
        if (username.isEmpty() || password.isEmpty()) {
            view.showError("Please enter both username and password.");
            return;
        }
        view.btnLogin.setEnabled(false);
        try {
            UserDAO.LoginResult r = userDAO.login(username, password);
            if (r.user == null) {
                view.showError(r.error);
                return;
            }
            Session.setUser(r.user);
            if (r.user.mustChangePassword && !ChangePasswordDialog.show(view, r.user, true)) {
                Session.clear();
                view.showError("You must change your password to continue.");
                return;
            }
            AuditService.log("LOGIN", "USER", r.user.userId, "Signed in", null);
            Session.setShift(new ShiftService().openShiftOf(r.user.userId));
            view.dispose();
            new MainController().start();
        } catch (SQLException ex) {
            UiKit.error(view, "Could not connect to the database.\n" + ex.getMessage());
        } finally {
            view.btnLogin.setEnabled(true);
        }
    }
}
