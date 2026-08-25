package com.depo.exception;

public class AlreadyCancelledException extends RuntimeException {

    public AlreadyCancelledException(Long movementId) {
        super("Stok hareketi zaten iptal edilmiş. Hareket ID: " + movementId);
    }
}
