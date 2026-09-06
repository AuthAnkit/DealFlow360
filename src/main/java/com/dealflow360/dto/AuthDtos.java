package com.dealflow360.dto;

/** Simple auth-related request/response shapes. Public fields keep these DTOs short and easy to read. */
public class AuthDtos {

    public static class LoginRequest {
        public String username;
        public String password;
    }

    /** PDF A1 - internal user self-signup (always creates a Sales Rep). */
    public static class SignupRequest {
        public String username;
        public String password;
        public String fullName;
        public String email;
    }

    public static class LoginResponse {
        public String username;
        public String fullName;
        public String role; // "ADMIN" / "SALES_REP" / ... or "CUSTOMER"
        public Long customerId; // only set for customer-portal logins

        public LoginResponse(String username, String fullName, String role, Long customerId) {
            this.username = username;
            this.fullName = fullName;
            this.role = role;
            this.customerId = customerId;
        }
    }
}
