package com.recon.service.breaks;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ReconBreakRepository extends JpaRepository<ReconBreak, Long>, JpaSpecificationExecutor<ReconBreak> {

    /** Fetches each break's trade and confirm in the same query, avoiding one extra query per row (N+1). */
    @Override
    @EntityGraph(attributePaths = {"trade", "confirm"})
    Page<ReconBreak> findAll(Specification<ReconBreak> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"trade", "confirm"})
    Optional<ReconBreak> findWithRecordsById(Long id);
}
