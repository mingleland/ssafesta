package com.example.ssafesta.eventshop;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventPurchaseRepository extends JpaRepository<EventPurchase, Long> {

    /** The replay check for a retried purchase request — see {@code EventShopService.purchase}. */
    Optional<EventPurchase> findByIdempotencyKey(String idempotencyKey);

    Page<EventPurchase> findAllByOrderByPurchasedAtDesc(Pageable pageable);

    Page<EventPurchase> findAllByFulfillmentOrderByPurchasedAtDesc(PurchaseFulfillment fulfillment, Pageable pageable);

    /** The one-entry-per-member rule for raffles — see {@code EventShopService.purchase}. */
    boolean existsByPrizeIdAndBuyerUserId(Long prizeId, Long buyerUserId);

    /** Every entry in a raffle, for the draw. Ordered so the shuffle below starts from a fixed list. */
    List<EventPurchase> findAllByPrizeIdOrderByIdAsc(Long prizeId);

    /** The admin console's winner list. */
    Page<EventPurchase> findAllByWonOrderByPurchasedAtDesc(Boolean won, Pageable pageable);

    @Query("select p from EventPurchase p where p.fulfillment = :fulfillment and p.won = :won"
            + " order by p.purchasedAt desc")
    Page<EventPurchase> findAllByFulfillmentAndWon(@Param("fulfillment") PurchaseFulfillment fulfillment,
                                                   @Param("won") Boolean won, Pageable pageable);
}
