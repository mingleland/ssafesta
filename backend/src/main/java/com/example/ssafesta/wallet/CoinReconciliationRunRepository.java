package com.example.ssafesta.wallet;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoinReconciliationRunRepository extends JpaRepository<CoinReconciliationRun, Long> {

    Optional<CoinReconciliationRun> findFirstByOrderByStartedAtDescIdDesc();
}
