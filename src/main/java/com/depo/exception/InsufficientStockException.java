package com.depo.exception;

public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(String message) {
        super(message);
    }

    public InsufficientStockException(int available, int requested) {
        super("Yetersiz Stok! Mevcut: " + available + ", İstenen: " + requested);
    }
}
