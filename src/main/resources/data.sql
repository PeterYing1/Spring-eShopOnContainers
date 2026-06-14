INSERT INTO CatalogBrand (Id, Brand) VALUES
    (1, 'Azure'),
    (2, '.NET'),
    (3, 'Visual Studio'),
    (4, 'SQL Server'),
    (5, 'Other'),
    (6, 'CatalogBrandTestOne'),
    (7, 'CatalogBrandTestTwo');

INSERT INTO CatalogType (Id, Type) VALUES
    (1, 'Mug'),
    (2, 'T-Shirt'),
    (3, 'Sheet'),
    (4, 'USB Memory Stick'),
    (5, 'CatalogTypeTestOne'),
    (6, 'CatalogTypeTestTwo');

INSERT INTO Catalog (
    Id, CatalogTypeId, CatalogBrandId, Description, Name, Price, PictureFileName,
    AvailableStock, RestockThreshold, MaxStockThreshold, OnReorder
) VALUES
    (1, 2, 2, '.NET Bot Black Hoodie, and more', '.NET Bot Black Hoodie', 19.50, '1.png', 100, 0, 0, FALSE),
    (2, 1, 2, '.NET Black & White Mug', '.NET Black & White Mug', 8.50, '2.png', 89, 0, 0, TRUE),
    (3, 2, 5, 'Prism White T-Shirt', 'Prism White T-Shirt', 12.00, '3.png', 56, 0, 0, FALSE),
    (4, 2, 2, '.NET Foundation T-shirt', '.NET Foundation T-shirt', 12.00, '4.png', 120, 0, 0, FALSE),
    (5, 3, 5, 'Roslyn Red Sheet', 'Roslyn Red Sheet', 8.50, '5.png', 55, 0, 0, FALSE),
    (6, 2, 2, '.NET Blue Hoodie', '.NET Blue Hoodie', 12.00, '6.png', 17, 0, 0, FALSE),
    (7, 2, 5, 'Roslyn Red T-Shirt', 'Roslyn Red T-Shirt', 12.00, '7.png', 8, 0, 0, FALSE),
    (8, 2, 5, 'Kudu Purple Hoodie', 'Kudu Purple Hoodie', 8.50, '8.png', 34, 0, 0, FALSE),
    (9, 1, 5, 'Cup<T> White Mug', 'Cup<T> White Mug', 12.00, '9.png', 76, 0, 0, FALSE),
    (10, 3, 2, '.NET Foundation Sheet', '.NET Foundation Sheet', 12.00, '10.png', 11, 0, 0, FALSE),
    (11, 3, 2, 'Cup<T> Sheet', 'Cup<T> Sheet', 8.50, '11.png', 3, 0, 0, FALSE),
    (12, 2, 5, 'Prism White TShirt', 'Prism White TShirt', 12.00, '12.png', 0, 0, 0, FALSE),
    (13, 1, 5, 'De los Palotes', 'pepito', 12.00, '12.png', 0, 0, 0, FALSE);

INSERT INTO ordering.cardtypes (Id, Name) VALUES
    (1, 'Amex'),
    (2, 'Visa'),
    (3, 'MasterCard'),
    (4, 'Capital One');

INSERT INTO ordering.orderstatus (Id, Name) VALUES
    (1, 'Submitted'),
    (2, 'Awaiting Validation'),
    (3, 'Stock Confirmed'),
    (4, 'Paid'),
    (5, 'Shipped'),
    (6, 'Cancelled');

ALTER TABLE CatalogBrand ALTER COLUMN Id RESTART WITH 8;
ALTER TABLE CatalogType ALTER COLUMN Id RESTART WITH 7;
ALTER TABLE Catalog ALTER COLUMN Id RESTART WITH 14;
