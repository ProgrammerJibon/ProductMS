package com.productms.service;

import com.productms.db.Jdbc;
import com.productms.util.Settings;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Document numbers (invoices, returns, cancellations, stock counts) from Settings:
 * <ul>
 *   <li>prefixes: invoice_prefix (INV), return_prefix (RET), cancel_prefix (CXL)</li>
 *   <li>number_format with tokens {PREFIX} {YYYY} {YY} {MM} {DD} {YYMMDD} {SEQ:n}</li>
 *   <li>number_reset: DAILY, MONTHLY, YEARLY or NEVER</li>
 * </ul>
 * The counter row is locked inside the caller's transaction, so numbers are gap-free and unique
 * even with several tills, and a rolled-back sale does not consume a number.
 */
public final class DocNumbers {

    private static final Pattern SEQ = Pattern.compile("\\{SEQ(?::(\\d))?\\}");

    private DocNumbers() {
    }

    public static String next(Connection con, String kind) throws SQLException {
        String prefix;
        switch (kind) {
            case "INVOICE": prefix = Settings.get("invoice_prefix", "INV"); break;
            case "RETURN": prefix = Settings.get("return_prefix", "RET"); break;
            case "CANCEL": prefix = Settings.get("cancel_prefix", "CXL"); break;
            case "COUNT": prefix = "CNT"; break;
            default: prefix = kind;
        }
        LocalDate today = LocalDate.now();
        String reset = Settings.get("number_reset", "DAILY");
        String period = "NEVER".equals(reset) ? "ALL" : "YEARLY".equals(reset) ? today.format(DateTimeFormatter.ofPattern("yyyy"))
                : "MONTHLY".equals(reset) ? today.format(DateTimeFormatter.ofPattern("yyyyMM"))
                : today.format(DateTimeFormatter.BASIC_ISO_DATE);
        Jdbc.update(con, "INSERT INTO doc_sequences (seq_name, period_key, last_no) VALUES (?, ?, LAST_INSERT_ID(1)) "
                + "ON DUPLICATE KEY UPDATE last_no = LAST_INSERT_ID(last_no + 1)", prefix, period);
        int no = Jdbc.one(con, "SELECT LAST_INSERT_ID()", rs -> rs.getInt(1));
        return format(Settings.get("number_format", "{PREFIX}-{YYMMDD}-{SEQ:4}"), prefix, today, no);
    }

    public static String format(String fmt, String prefix, LocalDate d, int no) {
        String s = fmt.replace("{PREFIX}", prefix)
                .replace("{YYMMDD}", d.format(DateTimeFormatter.ofPattern("yyMMdd")))
                .replace("{YYYY}", d.format(DateTimeFormatter.ofPattern("yyyy")))
                .replace("{YY}", d.format(DateTimeFormatter.ofPattern("yy")))
                .replace("{MM}", d.format(DateTimeFormatter.ofPattern("MM")))
                .replace("{DD}", d.format(DateTimeFormatter.ofPattern("dd")));
        Matcher m = SEQ.matcher(s);
        if (m.find()) {
            int width = m.group(1) == null ? 4 : Integer.parseInt(m.group(1));
            return m.replaceAll(String.format("%0" + width + "d", no));
        }
        return s + "-" + no;
    }
}
