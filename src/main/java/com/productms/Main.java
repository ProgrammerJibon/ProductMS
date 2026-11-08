package com.productms;

import com.productms.controller.LoginController;
import com.productms.db.Database;
import com.productms.db.Migrations;
import com.productms.service.BackupService;
import com.productms.service.SchedulerService;
import com.productms.util.Settings;
import com.productms.view.UiKit;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * Application entry point:
 * <ol>
 *   <li>checks MySQL (Retry / Exit when it is down),</li>
 *   <li>creates the database when missing (asks first) and applies the non-destructive migration,</li>
 *   <li>takes the daily automatic backup in the background,</li>
 *   <li>starts scheduled jobs (price changes, held-cart expiry, offline sync) and shows the login.</li>
 * </ol>
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
                        "The database \"" + Database.databaseName() + "\" does not exist yet.\n\n"
                        + "Create it now from db/schema.sql (with demo data from db/seed.sql)?\n"
                        + "Nothing existing is deleted.", "Database setup", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (a != JOptionPane.YES_OPTION) {
                    System.exit(0);
                }
                break;
            }
            Object[] options = {"Retry", "Exit"};
            int a = JOptionPane.showOptionDialog(null,
                    "Cannot connect to MySQL on " + Database.host() + ".\n\n" + problem
                    + "\n\nStart MySQL (XAMPP Control Panel → MySQL → Start) and click Retry.",
                    "Database not available", JOptionPane.DEFAULT_OPTION, JOptionPane.ERROR_MESSAGE, null, options, options[0]);
            if (a != 0) {
                System.exit(1);
            }
        }
        try {
            Migrations.Result r = Database.ensureSchema();
            if (!r.changes.isEmpty()) {
                System.out.println("Database updated: " + String.join("; ", r.changes));
            }
            Settings.load();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "Database setup failed:\n" + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
        if ("dark".equals(Settings.get("theme", "light"))) {
            UiKit.switchTheme(true);
        }
        Thread backup = new Thread(() -> {
            try {
                new BackupService().autoBackupIfDue();
            } catch (Exception e) {
                System.err.println("Automatic backup failed: " + e.getMessage());
            }
        }, "auto-backup");
        backup.setDaemon(true);
        backup.start();
        SchedulerService.start();
        new LoginController().start();
    }
}
