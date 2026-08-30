package com.oliverke.payments.recon;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface ReconDiffRepository extends JpaRepository<ReconDiff, Long> {

    List<ReconDiff> findByReconDateOrderByChannelRefAscDiffTypeAsc(LocalDate reconDate);

    long countByReconDate(LocalDate reconDate);

    /**
     * Clears a date's findings so the run about to write can replace them.
     *
     * <p>An explicit bulk delete rather than the derived {@code deleteByReconDate}
     * Spring Data would generate: the derived form loads every matching row into
     * the persistence context and deletes them one at a time, which is one
     * statement per discrepancy for no benefit at all.
     *
     * <p>Bulk JPQL bypasses the persistence context, so anything already loaded in
     * this transaction would be left stale. Nothing loads a ReconDiff before this
     * runs, which is why the cheap version is safe here - and why moving this call
     * would need that checked again.
     *
     * @return how many rows a previous run had left behind
     */
    @Modifying
    @Query("delete from ReconDiff d where d.reconDate = :date")
    int deleteFindingsFor(@Param("date") LocalDate date);
}
