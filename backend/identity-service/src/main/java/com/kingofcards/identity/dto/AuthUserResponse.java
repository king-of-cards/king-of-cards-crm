package com.kingofcards.identity.dto;

import java.util.List;
import java.util.UUID;

public record AuthUserResponse(UUID id, String email, List<String> roles) {}