package com.eshop.catalog;

import com.eshop.catalog.CatalogModels.CatalogBrand;
import com.eshop.catalog.CatalogModels.CatalogItem;
import com.eshop.catalog.CatalogModels.CatalogType;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogRepository {
    private final JdbcTemplate jdbc;

    public CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<CatalogItem> findPage(int pageSize, int pageIndex) {
        return jdbc.query("""
                SELECT c.*, b.Brand, t.Type
                FROM Catalog c
                JOIN CatalogBrand b ON b.Id = c.CatalogBrandId
                JOIN CatalogType t ON t.Id = c.CatalogTypeId
                ORDER BY c.Name
                LIMIT ? OFFSET ?
                """, this::mapItem, pageSize, pageSize * pageIndex);
    }

    public int countAll() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM Catalog", Integer.class);
    }

    public List<CatalogItem> findByIds(List<Integer> ids) {
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        return jdbc.query("""
                SELECT c.*, b.Brand, t.Type
                FROM Catalog c
                JOIN CatalogBrand b ON b.Id = c.CatalogBrandId
                JOIN CatalogType t ON t.Id = c.CatalogTypeId
                WHERE c.Id IN (%s)
                ORDER BY c.Id
                """.formatted(placeholders), this::mapItem, ids.toArray());
    }

    public Optional<CatalogItem> findById(int id) {
        List<CatalogItem> items = jdbc.query("""
                SELECT c.*, b.Brand, t.Type
                FROM Catalog c
                JOIN CatalogBrand b ON b.Id = c.CatalogBrandId
                JOIN CatalogType t ON t.Id = c.CatalogTypeId
                WHERE c.Id = ?
                """, this::mapItem, id);
        return items.stream().findFirst();
    }

    public List<CatalogItem> findByNamePrefix(String name, int pageSize, int pageIndex) {
        return jdbc.query("""
                SELECT c.*, b.Brand, t.Type
                FROM Catalog c
                JOIN CatalogBrand b ON b.Id = c.CatalogBrandId
                JOIN CatalogType t ON t.Id = c.CatalogTypeId
                WHERE LOWER(c.Name) LIKE LOWER(?)
                ORDER BY c.Name
                LIMIT ? OFFSET ?
                """, this::mapItem, name + "%", pageSize, pageSize * pageIndex);
    }

    public int countByNamePrefix(String name) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM Catalog WHERE LOWER(Name) LIKE LOWER(?)", Integer.class, name + "%");
    }

    public List<CatalogItem> findByTypeAndBrand(Integer typeId, Integer brandId, int pageSize, int pageIndex) {
        String typeFilter = typeId == null ? "1=1" : "c.CatalogTypeId = " + typeId;
        String brandFilter = brandId == null ? "1=1" : "c.CatalogBrandId = " + brandId;
        return jdbc.query("""
                SELECT c.*, b.Brand, t.Type
                FROM Catalog c
                JOIN CatalogBrand b ON b.Id = c.CatalogBrandId
                JOIN CatalogType t ON t.Id = c.CatalogTypeId
                WHERE %s AND %s
                ORDER BY c.Name
                LIMIT ? OFFSET ?
                """.formatted(typeFilter, brandFilter), this::mapItem, pageSize, pageSize * pageIndex);
    }

    public int countByTypeAndBrand(Integer typeId, Integer brandId) {
        String typeFilter = typeId == null ? "1=1" : "CatalogTypeId = " + typeId;
        String brandFilter = brandId == null ? "1=1" : "CatalogBrandId = " + brandId;
        return jdbc.queryForObject("SELECT COUNT(*) FROM Catalog WHERE %s AND %s".formatted(typeFilter, brandFilter), Integer.class);
    }

    public List<CatalogBrand> brands() {
        return jdbc.query("SELECT Id, Brand FROM CatalogBrand ORDER BY Brand", (rs, row) -> new CatalogBrand(rs.getInt("Id"), rs.getString("Brand")));
    }

    public List<CatalogType> types() {
        return jdbc.query("SELECT Id, Type FROM CatalogType ORDER BY Type", (rs, row) -> new CatalogType(rs.getInt("Id"), rs.getString("Type")));
    }

    public int create(CatalogItem item) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO Catalog (Name, Description, Price, PictureFileName, CatalogTypeId, CatalogBrandId, AvailableStock, RestockThreshold, MaxStockThreshold, OnReorder)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[] {"Id"});
            statement.setString(1, item.name());
            statement.setString(2, item.description());
            statement.setBigDecimal(3, item.price());
            statement.setString(4, item.pictureFileName());
            statement.setInt(5, item.catalogTypeId());
            statement.setInt(6, item.catalogBrandId());
            statement.setInt(7, item.availableStock());
            statement.setInt(8, item.restockThreshold());
            statement.setInt(9, item.maxStockThreshold());
            statement.setBoolean(10, item.onReorder());
            return statement;
        }, keyHolder);
        return keyHolder.getKey().intValue();
    }

    public boolean update(CatalogItem item) {
        return jdbc.update("""
                UPDATE Catalog
                SET Name = ?, Description = ?, Price = ?, PictureFileName = ?, CatalogTypeId = ?, CatalogBrandId = ?,
                    AvailableStock = ?, RestockThreshold = ?, MaxStockThreshold = ?, OnReorder = ?
                WHERE Id = ?
                """, item.name(), item.description(), item.price(), item.pictureFileName(), item.catalogTypeId(), item.catalogBrandId(),
                item.availableStock(), item.restockThreshold(), item.maxStockThreshold(), item.onReorder(), item.id()) == 1;
    }

    public boolean delete(int id) {
        return jdbc.update("DELETE FROM Catalog WHERE Id = ?", id) == 1;
    }

    public void decrementStock(int productId, int units) {
        jdbc.update("UPDATE Catalog SET AvailableStock = GREATEST(AvailableStock - ?, 0) WHERE Id = ?", units, productId);
    }

    private CatalogItem mapItem(ResultSet rs, int rowNum) throws SQLException {
        int brandId = rs.getInt("CatalogBrandId");
        int typeId = rs.getInt("CatalogTypeId");
        BigDecimal price = rs.getBigDecimal("Price");
        return new CatalogItem(
                rs.getInt("Id"),
                rs.getString("Name"),
                rs.getString("Description"),
                price,
                rs.getString("PictureFileName"),
                null,
                typeId,
                new CatalogType(typeId, rs.getString("Type")),
                brandId,
                new CatalogBrand(brandId, rs.getString("Brand")),
                rs.getInt("AvailableStock"),
                rs.getInt("RestockThreshold"),
                rs.getInt("MaxStockThreshold"),
                rs.getBoolean("OnReorder"));
    }
}
