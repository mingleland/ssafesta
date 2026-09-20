package com.example.ssafesta.inventory;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Provides the catalog reads used by palette rendering and equip validation. */
public interface CatalogItemRepository extends JpaRepository<CatalogItem, Long> {

    List<CatalogItem> findAllByItemTypeOrderById(String itemType);

    List<CatalogItem> findAllByItemTypeAndAssetKeyIn(String itemType, Collection<String> assetKeys);
}
