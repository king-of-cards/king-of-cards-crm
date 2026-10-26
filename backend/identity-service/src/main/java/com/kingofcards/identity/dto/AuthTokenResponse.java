package com.kingofcards.identity.dto;

public record AuthTokenResponse(String accessToken, String tokenType, long expiresIn,
                                AuthUserResponse user) {
    @Override
    public String toString() {
        return "AuthTokenResponse[accessToken=[REDACTED], tokenType=" + tokenType +
            ", expiresIn=" + expiresIn + ", user=" + user + "]";
    }
}