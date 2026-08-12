package com.example.settlementbatchperformance.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.settlementbatchperformance.domain.Settlement;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ZeroOffsetItemReaderTest {

    private static final String READER_NAME = "testZeroOffsetReader";

    private final EntityManagerFactory entityManagerFactory;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    ZeroOffsetItemReaderTest(
            EntityManagerFactory entityManagerFactory, JdbcTemplate jdbcTemplate) {
        this.entityManagerFactory = entityManagerFactory;
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeEach
    void clearSettlements() {
        jdbcTemplate.update("delete from settlement");
    }

    @Test
    void readsAllIdsInOrderAcrossMultiplePagesIncludingGaps() throws Exception {
        insertSettlements(List.of(1L, 2L, 5L, 8L, 13L, 21L, 34L));
        ZeroOffsetItemReader reader = newReader(3);

        List<Long> actualIds = readAll(reader, new ExecutionContext());

        assertThat(actualIds).containsExactly(1L, 2L, 5L, 8L, 13L, 21L, 34L);
    }

    @Test
    void readsDataSmallerThanPageSize() throws Exception {
        insertSettlements(List.of(1L, 2L));

        assertThat(readAll(newReader(3), new ExecutionContext())).containsExactly(1L, 2L);
    }

    @Test
    void readsDataExactlyEqualToPageSize() throws Exception {
        insertSettlements(List.of(1L, 2L, 3L));

        assertThat(readAll(newReader(3), new ExecutionContext())).containsExactly(1L, 2L, 3L);
    }

    @Test
    void returnsNullRepeatedlyAfterAnEmptyPageInsteadOfRepeatingTheLastPage() throws Exception {
        insertSettlements(List.of(10L, 20L, 30L, 40L));
        ZeroOffsetItemReader reader = newReader(2);
        reader.open(new ExecutionContext());

        assertThat(reader.read().getId()).isEqualTo(10L);
        assertThat(reader.read().getId()).isEqualTo(20L);
        assertThat(reader.read().getId()).isEqualTo(30L);
        assertThat(reader.read().getId()).isEqualTo(40L);
        assertThat(reader.read()).isNull();
        assertThat(reader.read()).isNull();

        reader.close();
    }

    @Test
    void restoresLastIdAndContinuesAfterTheCheckpoint() throws Exception {
        insertSettlements(List.of(1L, 3L, 7L, 9L, 15L, 20L));
        ExecutionContext executionContext = new ExecutionContext();
        ZeroOffsetItemReader firstReader = newReader(3);
        firstReader.open(executionContext);

        assertThat(firstReader.read().getId()).isEqualTo(1L);
        assertThat(firstReader.read().getId()).isEqualTo(3L);
        assertThat(firstReader.read().getId()).isEqualTo(7L);
        firstReader.update(executionContext);
        firstReader.close();

        assertThat(executionContext.getLong(READER_NAME + ".lastId")).isEqualTo(7L);
        assertThat(readAll(newReader(3), executionContext)).containsExactly(9L, 15L, 20L);
    }

    @Test
    void completesImmediatelyForAnEmptyTable() throws Exception {
        ZeroOffsetItemReader reader = newReader(3);
        reader.open(new ExecutionContext());

        assertThat(reader.read()).isNull();

        reader.close();
    }

    private ZeroOffsetItemReader newReader(int pageSize) {
        return new ZeroOffsetItemReader(READER_NAME, entityManagerFactory, pageSize, pageSize);
    }

    private List<Long> readAll(ZeroOffsetItemReader reader, ExecutionContext executionContext)
            throws Exception {
        List<Long> ids = new ArrayList<>();
        reader.open(executionContext);
        Settlement settlement;
        while ((settlement = reader.read()) != null) {
            ids.add(settlement.getId());
        }
        reader.close();
        return ids;
    }

    private void insertSettlements(List<Long> ids) {
        LocalDateTime baseTime = LocalDateTime.of(2026, 1, 1, 0, 0);
        jdbcTemplate.batchUpdate(
                "insert into settlement "
                        + "(id, merchant_id, status, amount, settled_at, created_at) "
                        + "values (?, ?, ?, ?, ?, ?)",
                ids,
                ids.size(),
                (statement, id) -> {
                    statement.setLong(1, id);
                    statement.setLong(2, id % 10 + 1);
                    statement.setString(3, "COMPLETED");
                    statement.setBigDecimal(4, BigDecimal.valueOf(id));
                    statement.setTimestamp(5, Timestamp.valueOf(baseTime.plusSeconds(id)));
                    statement.setTimestamp(6, Timestamp.valueOf(baseTime));
                });
    }
}
