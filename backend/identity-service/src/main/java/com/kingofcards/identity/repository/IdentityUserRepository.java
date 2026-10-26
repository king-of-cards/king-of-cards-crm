package com.kingofcards.identity.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import com.kingofcards.identity.entity.IdentityUser;

public interface IdentityUserRepository extends JpaRepository<IdentityUser, UUID> {
    Optional<IdentityUser> findByNormalizedEmail(String normalizedEmail);
}