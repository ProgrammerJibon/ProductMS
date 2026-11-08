package com.productms.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;

/**
 * Barcode (EAN-13 / Code-128) and QR code generation using ZXing.
 */
public final class BarcodeUtil {

    private BarcodeUtil() {
    }

    /** Internal store barcode: "200" + 9-digit number + EAN-13 check digit. */
    public static String internalEan13(long number) {
        String base = "200" + String.format("%09d", number);
        return base + ean13CheckDigit(base);
    }

    public static int ean13CheckDigit(String first12) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int d = first12.charAt(i) - '0';
            sum += (i % 2 == 0) ? d : d * 3;
        }
        return (10 - sum % 10) % 10;
    }

    public static boolean isValidEan13(String code) {
        return code != null && code.matches("\\d{13}")
                && ean13CheckDigit(code.substring(0, 12)) == code.charAt(12) - '0';
    }

    /** GTIN check digit for EAN-8 / UPC-A / EAN-13 (weights 3,1 from the right). */
    public static int gtinCheckDigit(String body) {
        int sum = 0;
        for (int i = 0; i < body.length(); i++) {
            int d = body.charAt(body.length() - 1 - i) - '0';
            sum += (i % 2 == 0) ? d * 3 : d;
        }
        return (10 - sum % 10) % 10;
    }

    public static boolean isValidGtin(String code) {
        return code != null && code.matches("\\d{8}|\\d{12}|\\d{13}")
                && gtinCheckDigit(code.substring(0, code.length() - 1)) == code.charAt(code.length() - 1) - '0';
    }

    /** "EAN-8", "UPC-A", "EAN-13" or "Code-128". */
    public static String formatName(String code) {
        if (isValidGtin(code)) {
            return code.length() == 8 ? "EAN-8" : code.length() == 12 ? "UPC-A" : "EAN-13";
        }
        return "Code-128";
    }

    /**
     * Error message when a numeric code looks like EAN-8 / UPC-A / EAN-13 but its check digit is
     * wrong (a typo), otherwise null. Other codes are accepted and printed as Code-128.
     */
    public static String checkDigitError(String code) {
        if (code != null && code.matches("\\d{8}|\\d{12}|\\d{13}") && !isValidGtin(code)) {
            String body = code.substring(0, code.length() - 1);
            return "wrong check digit for " + (code.length() == 8 ? "EAN-8" : code.length() == 12 ? "UPC-A" : "EAN-13")
                    + " (should end in " + gtinCheckDigit(body) + "). Check for a typo.";
        }
        if (code != null && !code.matches("[\\x20-\\x7E]{1,48}")) {
            return "barcodes may only contain plain letters, digits and symbols (max 48).";
        }
        return null;
    }

    /** EAN-8, UPC-A or EAN-13 when the code is a valid GTIN of that length, otherwise Code-128. */
    public static BitMatrix encodeBarcode(String code) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 0);
            BarcodeFormat format = !isValidGtin(code) ? BarcodeFormat.CODE_128
                    : code.length() == 8 ? BarcodeFormat.EAN_8 : code.length() == 12 ? BarcodeFormat.UPC_A : BarcodeFormat.EAN_13;
            return new MultiFormatWriter().encode(code, format, 0, 0, hints);
        } catch (WriterException | IllegalArgumentException e) {
            throw new AppException("Cannot encode barcode \"" + code + "\": " + e.getMessage());
        }
    }

    public static BitMatrix encodeQr(String text) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 0);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            return new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
        } catch (WriterException e) {
            throw new AppException("Cannot create QR code: " + e.getMessage());
        }
    }

    /** Draws a 1-D barcode (only the bars, using the top row) into the rectangle. */
    public static void drawBarcode(Graphics2D g, BitMatrix m, double x, double y, double w, double h) {
        double module = w / m.getWidth();
        g.setColor(Color.BLACK);
        for (int i = 0; i < m.getWidth(); i++) {
            if (m.get(i, 0)) {
                g.fill(new Rectangle2D.Double(x + i * module, y, module, h));
            }
        }
    }

    /** Draws a 2-D matrix (QR) into a square. */
    public static void drawMatrix(Graphics2D g, BitMatrix m, double x, double y, double size) {
        double module = size / Math.max(m.getWidth(), m.getHeight());
        g.setColor(Color.BLACK);
        for (int row = 0; row < m.getHeight(); row++) {
            for (int col = 0; col < m.getWidth(); col++) {
                if (m.get(col, row)) {
                    g.fill(new Rectangle2D.Double(x + col * module, y + row * module, module + 0.01, module + 0.01));
                }
            }
        }
    }

    public static BufferedImage qrImage(String text, int size) {
        BitMatrix m = encodeQr(text);
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, size, size);
        drawMatrix(g, m, 4, 4, size - 8);
        g.dispose();
        return img;
    }

    public static BufferedImage barcodeImage(String code, int width, int height) {
        BitMatrix m = encodeBarcode(code);
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        drawBarcode(g, m, 6, 4, width - 12, height - 8);
        g.dispose();
        return img;
    }
}
