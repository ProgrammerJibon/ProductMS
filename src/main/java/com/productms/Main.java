package com.productms;

import com.productms.controller.LoginController;
import com.productms.db.Database;
import com.productms.util.Settings;
import com.productms.view.UiKit;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * Application entry point. Checks the database (offering to create it from db/schema.sql
 * and db/seed.sql when missing), applies the theme and shows the login screen.
 */
public class Main {

    public static void main(String[] args) {
        UiKit.applyTheme(false);
        SwingUtilities.invokeLater(Main::boot);
    }

    private static void boot() {
        while (true) {
            String problem = Database.checkConnection();
            if (problem == null) {
                break;
            }
            if ("NO_DATABASE".equals(problem)) {
                int a = JOptionPane.showConfirmDialog(null,
                        "The database \"" + Database.databaseName() + "\" was not found.\n\n"
                        + "Create it now from db/schema.sql and db/seed.sql (with demo data)?",
                        "Database setup", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (a != JOptionPane.YES_OPTION) {
                    System.exit(0);
                }
                try {
                    Database.installFromScripts();
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(null, "Database setup failed:\n" + e.getMessage(), "Error",
                            JOptionPane.ERROR_MESSAGE);
                    System.exit(1);
                }
                continue;
            }
            Object[] options = {"Retry", "Exit"};
            int a = JOptionPane.showOptionDialog(null,
                    "Cannot connect to MySQL on localhost:3306.\n\n" + problem
                    + "\n\nStart MySQL (XAMPP Control Panel → MySQL → Start) and click Retry.",
                    "Database not available", JOptionPane.DEFAULT_OPTION, JOptionPane.ERROR_MESSAGE, null, options, options[0]);
            if (a != 0) {
                System.exit(1);
            }
        }
        try {
            Settings.load();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "Could not load settings: " + e.getMessage());
            System.exit(1);
        }
        if ("dark".equals(Settings.get("theme", "light"))) {
            UiKit.switchTheme(true);
        }
        new LoginController().start();
    }
}
