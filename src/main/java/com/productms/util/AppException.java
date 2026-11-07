package com.productms.util;

/**
 * A business-rule violation with a message that can be shown directly to the user
 * (e.g. "Only 3 units in stock").
 */
public class AppException extends RuntimeException {

    public AppException(String message) {
        super(message);
    }
}
