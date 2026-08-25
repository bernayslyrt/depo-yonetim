package com.depo.enums;

public enum Role {
    ADMIN,
    USER,
    /**
     * Legacy value retained so existing databases can still be read during migration.
     */
    STAFF
}
