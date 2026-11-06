package com.kingofcards.identity.entity;


public class IdentityRole {
    public static final java.util.Set<String> SUPPORTED_CODES = java.util.Set.of("CRM_ADMIN", "CRM_VENDOR");


    private String code;

    private String displayName;

    protected IdentityRole() {}

    public IdentityRole(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }
}
