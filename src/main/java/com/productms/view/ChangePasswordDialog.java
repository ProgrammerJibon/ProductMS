package com.productms.view;

import com.productms.dao.UserDAO;
import com.productms.model.User;
import java.awt.BorderLayout;
import java.awt.Window;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;

/** Change-password form; can be mandatory (first login with a temporary or demo password). */
public class ChangePasswordDialog extends JDialog {

    private final JPasswordField current = new JPasswordField();
    private final JPasswordField p1 = new JPasswordField();
    private final JPasswordField p2 = new JPasswordField();
    private boolean changed;

    public ChangePasswordDialog(Window owner, User user, boolean mandatory) {
        super(owner, mandatory ? "Change your password to continue" : "Change password", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        for (JPasswordField f : new JPasswordField[]{current, p1, p2}) {
            f.putClientProperty("JPasswordField.showRevealButton", true);
        }
        UiKit.Form f = new UiKit.Form();
        if (mandatory) {
            JLabel why = new JLabel("<html><b>" + user.fullName + "</b>, your password is temporary or a demo password.<br>"
                    + "Choose a new one before using the system.</html>");
            f.full(why);
        }
        f.add("Current password", current);
        f.add("New password", p1);
        f.add("Repeat new password", p2);
        f.full(UiKit.mutedLabel("At least 8 characters, letters and numbers, not containing your username."));
        JButton ok = UiKit.primary("Change password");
        JButton cancel = UiKit.plain(mandatory ? "Sign out" : "Cancel");
        ok.addActionListener(e -> UiKit.guard(this, () -> {
            if (!new String(p1.getPassword()).equals(new String(p2.getPassword()))) {
                UiKit.warn(this, "The new passwords do not match.");
                return;
            }
            new UserDAO().changeOwnPassword(user, new String(current.getPassword()), new String(p1.getPassword()));
            changed = true;
            UiKit.info(this, "Password changed.");
            dispose();
        }));
        cancel.addActionListener(e -> dispose());
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));
        root.add(f.panel);
        root.add(UiKit.rowRight(cancel, ok), BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(ok);
        pack();
        setSize(Math.max(getWidth(), 470), getHeight());
        setLocationRelativeTo(owner);
    }

    public static boolean show(java.awt.Component parent, User user, boolean mandatory) {
        ChangePasswordDialog d = new ChangePasswordDialog(UiKit.window(parent), user, mandatory);
        d.setVisible(true);
        return d.changed;
    }
}
