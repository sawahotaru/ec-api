package com.example.ecapi.domain;

public enum OrderStatus {
    PENDING,
    PAID,
    SHIPPED,
    DELIVERED,
    CANCELLED,
    /** Auto-cancelled because payment was not completed within the hold window. */
    EXPIRED
}
