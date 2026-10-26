package com.kingofcards.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "identity_role")
public class IdentityRole {
    @Id
    @Column(name = "role_code", nullable = false, length = 40)
    private String code;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    protected IdentityRole() {}

    public IdentityRole(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }
}