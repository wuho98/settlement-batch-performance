-- Verifies the dataset size and deterministic distribution.
-- Set @expected_dataset_size before running this file.
SET @expected_dataset_size = COALESCE(@expected_dataset_size, 100000);

SELECT
    @expected_dataset_size AS expected_count,
    COUNT(*) AS total_count,
    COUNT(DISTINCT id) AS distinct_id_count,
    MIN(id) AS min_id,
    MAX(id) AS max_id,
    COUNT(*) = @expected_dataset_size
        AND COUNT(DISTINCT id) = @expected_dataset_size
        AND MIN(id) = 1
        AND MAX(id) = @expected_dataset_size AS valid
FROM settlement;

SELECT status, COUNT(*) AS row_count
FROM settlement
GROUP BY status
ORDER BY status;

SELECT
    COUNT(DISTINCT merchant_id) AS merchant_count,
    MIN(rows_per_merchant) AS min_rows_per_merchant,
    MAX(rows_per_merchant) AS max_rows_per_merchant
FROM (
    SELECT merchant_id, COUNT(*) AS rows_per_merchant
    FROM settlement
    GROUP BY merchant_id
) merchant_distribution;

SELECT
    MIN(amount) AS min_amount,
    MAX(amount) AS max_amount,
    MIN(settled_at) AS first_settled_at,
    MAX(settled_at) AS last_settled_at
FROM settlement;
