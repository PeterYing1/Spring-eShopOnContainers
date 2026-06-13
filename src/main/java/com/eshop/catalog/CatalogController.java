package com.eshop.catalog;

import com.eshop.catalog.CatalogModels.CatalogItem;
import com.eshop.catalog.CatalogModels.PaginatedItemsViewModel;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {
    private final CatalogRepository catalog;

    public CatalogController(CatalogRepository catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/items")
    public ResponseEntity<?> items(@RequestParam(defaultValue = "10") int pageSize,
                                   @RequestParam(defaultValue = "0") int pageIndex,
                                   @RequestParam(required = false) String ids,
                                   HttpServletRequest request) {
        if (ids != null && !ids.isBlank()) {
            try {
                List<Integer> parsed = Arrays.stream(ids.split(",")).map(String::trim).map(Integer::parseInt).toList();
                List<CatalogItem> items = catalog.findByIds(parsed).stream().map(item -> withPictureUri(item, request)).toList();
                if (items.isEmpty()) {
                    return ResponseEntity.badRequest().body("ids value invalid. Must be comma-separated list of numbers");
                }
                return ResponseEntity.ok(items);
            } catch (NumberFormatException ex) {
                return ResponseEntity.badRequest().body("ids value invalid. Must be comma-separated list of numbers");
            }
        }
        List<CatalogItem> data = catalog.findPage(pageSize, pageIndex).stream().map(item -> withPictureUri(item, request)).toList();
        return ResponseEntity.ok(new PaginatedItemsViewModel<>(pageIndex, pageSize, catalog.countAll(), data));
    }

    @GetMapping("/items/{id}")
    public ResponseEntity<?> item(@PathVariable int id, HttpServletRequest request) {
        if (id <= 0) {
            return ResponseEntity.badRequest().build();
        }
        return catalog.findById(id)
                .map(item -> ResponseEntity.ok(withPictureUri(item, request)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/items/withname/{name}")
    public PaginatedItemsViewModel<CatalogItem> byName(@PathVariable String name,
                                                       @RequestParam(defaultValue = "10") int pageSize,
                                                       @RequestParam(defaultValue = "0") int pageIndex,
                                                       HttpServletRequest request) {
        List<CatalogItem> data = catalog.findByNamePrefix(name, pageSize, pageIndex).stream().map(item -> withPictureUri(item, request)).toList();
        return new PaginatedItemsViewModel<>(pageIndex, pageSize, catalog.countByNamePrefix(name), data);
    }

    @GetMapping({"/items/type/{catalogTypeId}/brand", "/items/type/{catalogTypeId}/brand/{catalogBrandId}"})
    public PaginatedItemsViewModel<CatalogItem> byTypeAndBrand(@PathVariable int catalogTypeId,
                                                               @PathVariable(required = false) Integer catalogBrandId,
                                                               @RequestParam(defaultValue = "10") int pageSize,
                                                               @RequestParam(defaultValue = "0") int pageIndex,
                                                               HttpServletRequest request) {
        List<CatalogItem> data = catalog.findByTypeAndBrand(catalogTypeId, catalogBrandId, pageSize, pageIndex)
                .stream().map(item -> withPictureUri(item, request)).toList();
        return new PaginatedItemsViewModel<>(pageIndex, pageSize, catalog.countByTypeAndBrand(catalogTypeId, catalogBrandId), data);
    }

    @GetMapping({"/items/type/all/brand", "/items/type/all/brand/{catalogBrandId}"})
    public PaginatedItemsViewModel<CatalogItem> byBrand(@PathVariable(required = false) Integer catalogBrandId,
                                                        @RequestParam(defaultValue = "10") int pageSize,
                                                        @RequestParam(defaultValue = "0") int pageIndex,
                                                        HttpServletRequest request) {
        List<CatalogItem> data = catalog.findByTypeAndBrand(null, catalogBrandId, pageSize, pageIndex)
                .stream().map(item -> withPictureUri(item, request)).toList();
        return new PaginatedItemsViewModel<>(pageIndex, pageSize, catalog.countByTypeAndBrand(null, catalogBrandId), data);
    }

    @GetMapping("/catalogtypes")
    public Object types() {
        return catalog.types();
    }

    @GetMapping("/catalogbrands")
    public Object brands() {
        return catalog.brands();
    }

    @PostMapping("/items")
    public ResponseEntity<Void> create(@Valid @org.springframework.web.bind.annotation.RequestBody CatalogItem item) {
        int id = catalog.create(item);
        return ResponseEntity.created(URI.create("/api/v1/catalog/items/" + id)).build();
    }

    @PutMapping("/items")
    public ResponseEntity<?> update(@Valid @org.springframework.web.bind.annotation.RequestBody CatalogItem item) {
        if (item.id() == null || !catalog.update(item)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(java.util.Map.of("message", "Item with id " + item.id() + " not found."));
        }
        return ResponseEntity.created(URI.create("/api/v1/catalog/items/" + item.id())).build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable int id) {
        return catalog.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/items/{catalogItemId}/pic")
    public ResponseEntity<byte[]> picture(@PathVariable int catalogItemId) {
        if (catalogItemId <= 0) {
            return ResponseEntity.badRequest().build();
        }
        return catalog.findById(catalogItemId)
                .map(item -> {
                    byte[] svg = placeholderSvg(item.name());
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + item.pictureFileName() + "\"")
                            .contentType(MediaType.valueOf("image/svg+xml"))
                            .body(svg);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private CatalogItem withPictureUri(CatalogItem item, HttpServletRequest request) {
        String root = request.getRequestURL().toString().replace(request.getRequestURI(), "");
        return new CatalogItem(item.id(), item.name(), item.description(), item.price(), item.pictureFileName(),
                root + "/api/v1/catalog/items/" + item.id() + "/pic", item.catalogTypeId(), item.catalogType(),
                item.catalogBrandId(), item.catalogBrand(), item.availableStock(), item.restockThreshold(),
                item.maxStockThreshold(), item.onReorder());
    }

    private byte[] placeholderSvg(String name) {
        String escaped = name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return ("""
                <svg xmlns="http://www.w3.org/2000/svg" width="640" height="420" viewBox="0 0 640 420">
                  <rect width="640" height="420" fill="#f5f7fb"/>
                  <rect x="70" y="60" width="500" height="300" rx="22" fill="#0f766e"/>
                  <text x="320" y="205" text-anchor="middle" font-family="Arial,sans-serif" font-size="34" font-weight="700" fill="white">eShop</text>
                  <text x="320" y="252" text-anchor="middle" font-family="Arial,sans-serif" font-size="22" fill="white">%s</text>
                </svg>
                """.formatted(escaped)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
