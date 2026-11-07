package com.productms.controller;

import com.productms.model.Shift;
import com.productms.service.ReceiptService;
import com.productms.service.ShiftService;
import com.productms.util.AppException;
import com.productms.util.Money;
import com.productms.util.Session;
import com.productms.util.Settings;
import com.productms.view.NumberPadDialog;
import com.productms.view.ShiftView;
import com.productms.view.UiKit;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JTextField;

/** Open / close cashier shifts and print X / Z reports. */
public class ShiftController implements Module {

    private final ShiftView view = new ShiftView();
    private final ShiftService service = new ShiftService();
    private final MainController main;

    public ShiftController(MainController main) {
        this.main = main;
        view.btnOpen.addActionListener(e -> open());
        view.btnClose.addActionListener(e -> close());
        view.btnRefresh.addActionListener(e -> UiKit.guard(view, this::onShow));
        view.btnX.addActionListener(e -> {
            if (Session.shift() != null) {
                print(Session.shift().shiftId);
            }
        });
        view.btnZ.addActionListener(e -> {
            int r = view.historyTable.getSelectedRow();
            if (r < 0) {
                UiKit.warn(view, "Select a shift in the history.");
                return;
            }
            Object id = view.historyModel.getValueAt(view.historyTable.convertRowIndexToModel(r), 0);
            print(((Number) id).intValue());
        });
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void onShow() throws Exception {
        Session.setShift(service.openShiftOf(Session.userId()));
        Shift s = Session.shift();
        boolean open = s != null;
        view.btnOpen.setEnabled(!open);
        view.btnClose.setEnabled(open);
        view.btnX.setEnabled(open);
        if (open) {
            view.lblStatus.setText("Shift #" + s.shiftId + " is open");
            view.lblStatus.setForeground(UiKit.SUCCESS);
            view.lblInfo.setText("Opened " + s.openedAt.format(ReceiptService.DT) + " by " + s.userName
                    + "  ·  opening cash " + Money.cur(s.openingCash));
            List<Object[]> rows = new ArrayList<>();
            for (Map.Entry<String, BigDecimal> e : service.summary(s.shiftId).entrySet()) {
                rows.add(new Object[]{e.getKey(), e.getValue()});
            }
            view.summaryModel.setRows(rows);
        } else {
            view.lblStatus.setText("No shift open");
            view.lblStatus.setForeground(UiKit.WARNING);
            view.lblInfo.setText(Settings.getBool("require_shift") ? "Open a shift with the cash in the drawer before selling."
                    : "Shifts are optional (see Settings), but recommended for cash control.");
            view.summaryModel.setRows(List.of());
        }
        view.historyModel.setData(service.history());
        main.refreshShift();
    }

    private void open() {
        BigDecimal cash = NumberPadDialog.ask(view, "Open shift", "Count the cash in the drawer.<br><b>Opening cash</b>:",
                BigDecimal.ZERO, true);
        if (cash == null) {
            return;
        }
        UiKit.guard(view, () -> {
            service.open(cash);
            onShow();
        });
    }

    private void close() {
        Shift s = Session.shift();
        if (s == null) {
            return;
        }
        UiKit.guard(view, () -> {
            BigDecimal expected = service.expectedCash(s.shiftId);
            BigDecimal counted = NumberPadDialog.ask(view, "Close shift", "Count all cash in the drawer.<br>"
                    + "Expected by the system: <b>" + Money.cur(expected) + "</b><br><b>Counted cash</b>:", null, true);
            if (counted == null) {
                return;
            }
            BigDecimal diff = counted.subtract(expected);
            JTextField note = UiKit.field("Note (explain any difference)");
            String msg = diff.signum() == 0 ? "Cash matches exactly." : (diff.signum() < 0 ? "SHORT by " : "OVER by ")
                    + Money.cur(diff.abs());
            if (JOptionPane.showConfirmDialog(view, new Object[]{"Expected: " + Money.cur(expected), "Counted: " + Money.cur(counted),
                msg, " ", note}, "Close shift #" + s.shiftId, JOptionPane.OK_CANCEL_OPTION,
                    diff.signum() == 0 ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
                return;
            }
            if (diff.signum() != 0 && note.getText().isBlank()) {
                throw new AppException("Please explain the cash difference in the note.");
            }
            Shift closed = service.close(s.shiftId, counted, note.getText().isBlank() ? null : note.getText().trim());
            onShow();
            if (UiKit.confirm(view, "Shift closed. Print the shift (Z) report?")) {
                print(closed.shiftId);
            }
        });
    }

    private void print(int shiftId) {
        UiKit.guard(view, () -> {
            Shift s = service.find(shiftId);
            int mm = "58".equals(Settings.get("receipt_size", "80")) ? 58 : 80;
            ReceiptService.printThermal(ReceiptService.shiftReportLines(s, service.summary(shiftId), mm), mm,
                    "Shift report #" + shiftId, true);
        });
    }
}
