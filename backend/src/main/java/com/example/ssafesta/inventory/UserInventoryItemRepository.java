package com.example.ssafesta.inventory;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Reads and writes member ownership rows without exposing persistence entities to controllers. */
public interface UserInventoryItemRepository extends JpaRepository<UserInventoryItem, Long> {

    boolean existsByUserIdAndCatalogItemId(Long userId, Long catalogItemId);

    @Query("select i.catalogItemId from UserInventoryItem i where i.userId = :userId")
    List<Long> findCatalogItemIdsByUserId(@Param("userId") Long userId);
}
