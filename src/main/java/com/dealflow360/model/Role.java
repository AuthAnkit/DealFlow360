package com.dealflow360.model;

/**
 * Internal user roles (see PDF section "3) User Roles").
 * CUSTOMER is handled separately via {@link Customer} portal login,
 * not through AppUser, but is listed here so the whole role model is in
 * one place and can be referenced consistently (e.g. in ApprovalLog).
 */
public enum Role {
    SALES_REP,
    SALES_MANAGER,
    FINANCE,
    ADMIN,
    CUSTOMER
}
