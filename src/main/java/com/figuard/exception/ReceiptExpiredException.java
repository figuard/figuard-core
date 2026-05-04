package com.figuard.exception;

public class ReceiptExpiredException extends RuntimeException {

    public ReceiptExpiredException(String token) {
        super("Receipt has expired: " + token);
    }
}
