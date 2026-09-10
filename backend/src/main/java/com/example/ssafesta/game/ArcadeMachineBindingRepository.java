package com.example.ssafesta.game;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Arcade machine bindings, keyed by the scene's machine id.
 *
 * <p>No finder is declared: the id <i>is</i> the lookup key, so {@code findById} is the whole
 * interface. Rows are operator seed data in v1 — there is no write endpoint yet (관리자 UI 는
 * S15P21A604-602 범위 밖).
 */
public interface ArcadeMachineBindingRepository extends JpaRepository<ArcadeMachineBinding, String> {
}
