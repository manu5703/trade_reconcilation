package com.recon.service.run;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconRunRepository extends JpaRepository<ReconRun, Long> {

    List<ReconRun> findByBusinessDateOrderByIdDesc(LocalDate businessDate);

    List<ReconRun> findTop20ByOrderByIdDesc();

    Optional<ReconRun> findFirstByStatusOrderByIdDesc(RunStatus status);
}
