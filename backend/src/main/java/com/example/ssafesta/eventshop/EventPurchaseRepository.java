package com.example.ssafesta.eventshop;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventPurchaseRepository extends JpaRepository<EventPurchase, Long> {

    /** The replay check for a retried purchase request — see {@code EventShopService.purchase}. */
    Optional<EventPurchase> findByIdempotencyKey(String idempotencyKey);

    Page<EventPurchase> findAllByOrderByPurchasedAtDesc(Pageable pageable);

    Page<EventPurchase> findAllByFulfillmentOrderByPurchasedAtDesc(PurchaseFulfillment fulfillment, Pageable pageable);
}
