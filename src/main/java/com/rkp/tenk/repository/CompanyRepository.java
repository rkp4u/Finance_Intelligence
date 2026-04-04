package com.rkp.tenk.repository;

import com.rkp.tenk.model.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CompanyRepository extends JpaRepository<Company, UUID> {

    Optional<Company> findByCanonicalName(String canonicalName);

    Optional<Company> findByTicker(String ticker);

    Optional<Company> findByCik(String cik);
}
