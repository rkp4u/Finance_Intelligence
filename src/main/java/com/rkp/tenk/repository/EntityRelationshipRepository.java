package com.rkp.tenk.repository;

import com.rkp.tenk.model.entity.EntityRelationship;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EntityRelationshipRepository extends JpaRepository<EntityRelationship, UUID> {

    List<EntityRelationship> findBySourceCompanyId(UUID sourceCompanyId);

    List<EntityRelationship> findByTargetCompanyId(UUID targetCompanyId);

    /** All relationships involving a company (as source or target). */
    @Query("SELECT r FROM EntityRelationship r WHERE r.sourceCompany.id = :companyId OR r.targetCompany.id = :companyId")
    List<EntityRelationship> findByCompanyId(UUID companyId);
}
