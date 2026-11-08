package com.productms.service;

import com.productms.util.Settings;
import java.awt.print.PrinterJob;
import java.util.ArrayList;
import java.util.List;
import javax.print.Doc;
import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintException;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.SimpleDoc;

/**
 * Printers and the cash drawer.
 * <ul>
 *   <li>Receipt / A4 printers chosen in Settings are used automatically (no print dialog).</li>
 *   <li>The cash drawer is opened with the standard ESC/POS "drawer kick" pulse sent to the
 *       receipt printer it is plugged into (most 58 / 80 mm thermal printers support it).</li>
 * </ul>
 */
public final class HardwareService {

    /** ESC p 0 25 250 : pulse pin 2 for 50 ms on / 500 ms off. */
    private static final byte[] DRAWER_KICK = {27, 112, 0, 25, (byte) 250};

    private HardwareService() {
    }

    public static List<String> printers() {
        List<String> out = new ArrayList<>();
        for (PrintService s : PrintServiceLookup.lookupPrintServices(null, null)) {
            out.add(s.getName());
        }
        return out;
    }

    public static PrintService printer(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (PrintService s : PrintServiceLookup.lookupPrintServices(null, null)) {
            if (s.getName().equalsIgnoreCase(name.trim())) {
                return s;
            }
        }
        return null;
    }

    public static PrintService receiptPrinter() {
        return printer(Settings.get("receipt_printer", ""));
    }

    public static PrintService a4Printer() {
        return printer(Settings.get("a4_printer", ""));
    }

    /**
     * Points the job at the configured printer. Returns true when a printer was set (print
     * silently), false when the user should choose one in the print dialog.
     */
    public static boolean usePrinter(PrinterJob job, PrintService service) throws java.awt.print.PrinterException {
        if (service == null) {
            return false;
        }
        job.setPrintService(service);
        return true;
    }

    /** Opens the cash drawer (if enabled in Settings). Returns an error message or null. */
    public static String openCashDrawer() {
        if (!Settings.getBool("cash_drawer_enabled")) {
            return "The cash drawer is not enabled (Settings → Printing & Hardware).";
        }
        PrintService s = receiptPrinter();
        if (s == null) {
            return "No receipt printer is selected (Settings → Printing & Hardware). The drawer is opened through it.";
        }
        try {
            DocPrintJob job = s.createPrintJob();
            Doc doc = new SimpleDoc(DRAWER_KICK, DocFlavor.BYTE_ARRAY.AUTOSENSE, null);
            job.print(doc, null);
            return null;
        } catch (PrintException e) {
            return "Could not open the cash drawer: " + e.getMessage();
        }
    }
}
