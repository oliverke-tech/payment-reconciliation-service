package com.oliverke.payments.recon;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ReconDiffRepository extends JpaRepository<ReconDiff, Long> {

    List<ReconDiff> findByReconDateOrderByChannelRefAscDiffTypeAsc(LocalDate reconDate);

    long countByReconDate(LocalDate reconDate);
}
