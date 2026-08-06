-- Recreates the deterministic baseline dataset. Do not run against data to preserve.
TRUNCATE TABLE settlement;

INSERT INTO settlement (
    id,
    merchant_id,
    status,
    amount,
    settled_at,
    created_at
)
SELECT
    sequence_number AS id,
    MOD(sequence_number - 1, 1000) + 1 AS merchant_id,
    CASE
        WHEN MOD(sequence_number - 1, 100) < 80 THEN 'COMPLETED'
        WHEN MOD(sequence_number - 1, 100) < 95 THEN 'PENDING'
        ELSE 'FAILED'
    END AS status,
    CAST(MOD(sequence_number * 7919, 99990001) / 100 + 100 AS DECIMAL(15, 2)) AS amount,
    TIMESTAMP('2025-01-01 00:00:00')
        + INTERVAL MOD(sequence_number - 1, 365) DAY
        + INTERVAL MOD(sequence_number * 37, 86400) SECOND AS settled_at,
    TIMESTAMP('2024-12-01 00:00:00')
        + INTERVAL MOD(sequence_number - 1, 365) DAY
        + INTERVAL MOD(sequence_number * 17, 86400) SECOND AS created_at
FROM (
    SELECT
        ones.n
        + tens.n * 10
        + hundreds.n * 100
        + thousands.n * 1000
        + ten_thousands.n * 10000
        + 1 AS sequence_number
    FROM
        (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
         UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
    CROSS JOIN
        (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
         UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) tens
    CROSS JOIN
        (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
         UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) hundreds
    CROSS JOIN
        (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
         UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) thousands
    CROSS JOIN
        (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
         UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ten_thousands
) sequence_source;
