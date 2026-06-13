package com.eshop.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;

public final class CatalogModels {
    private CatalogModels() {
    }

    public record CatalogBrand(int id, String brand) {
    }

    public record CatalogType(int id, String type) {
    }

    public record CatalogItem(
            Integer id,
            @NotBlank String name,
            String description,
            @NotNull @PositiveOrZero BigDecimal price,
            String pictureFileName,
            String pictureUri,
            int catalogTypeId,
            CatalogType catalogType,
            int catalogBrandId,
            CatalogBrand catalogBrand,
            int availableStock,
            int restockThreshold,
            int maxStockThreshold,
            boolean onReorder
    ) {
    }

    public record PaginatedItemsViewModel<T>(int pageIndex, int pageSize, int count, List<T> data) {
    }
}
