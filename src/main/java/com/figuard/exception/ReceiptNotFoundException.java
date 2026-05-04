package com.figuard.exception;

public class ReceiptNotFoundException extends RuntimeException {

    public ReceiptNotFoundException(String token) {
        super("Receipt not found: " + token);
    }
}
