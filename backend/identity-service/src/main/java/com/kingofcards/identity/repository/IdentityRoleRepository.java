package com.kingofcards.identity.repository;

import java.util.Optional;
import com.kingofcards.identity.entity.IdentityRole;

/** Legacy type retained for source compatibility; no role table or runtime bean. */
@Deprecated
public interface IdentityRoleRepository {
    Optional<IdentityRole> findByCode(String code);
}
