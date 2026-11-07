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

    /** EAN-13 when the code is a valid EAN-13, otherwise Code-128. */
    public static BitMatrix encodeBarcode(String code) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 0);
            BarcodeFormat format = isValidEan13(code) ? BarcodeFormat.EAN_13 : BarcodeFormat.CODE_128;
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
