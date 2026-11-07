package com.productms.controller;

import com.productms.dao.UserDAO;
import com.productms.model.User;
import com.productms.service.ShiftService;
import com.productms.util.Session;
import com.productms.util.Settings;
import com.productms.view.LoginView;
import com.productms.view.UiKit;
import java.sql.SQLException;

/** Verifies credentials against the database and opens the main window. */
public class LoginController {

    private final LoginView view;
    private final UserDAO userDAO = new UserDAO();
    private int failedAttempts;

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
        if (failedAttempts >= 5) {
            view.showError("Too many failed attempts. Restart the application to try again.");
            view.btnLogin.setEnabled(false);
            return;
        }
        try {
            User user = userDAO.authenticate(username, password);
            if (user == null) {
                failedAttempts++;
                view.showError("Invalid username or password (" + (5 - failedAttempts) + " attempts left).");
                return;
            }
            Session.setUser(user);
            Session.setShift(new ShiftService().openShiftOf(user.userId));
            view.dispose();
            new MainController().start();
        } catch (SQLException ex) {
            UiKit.error(view, "Could not connect to the database.\n" + ex.getMessage());
        }
    }
}
