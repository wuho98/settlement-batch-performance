package com.example.settlementbatchperformance.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.settlementbatchperformance.domain.Settlement;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.hibernate.jpa.HibernateHints;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.batch.infrastructure.item.database.JpaPagingItemReader;
import org.springframework.batch.infrastructure.item.database.builder.JpaPagingItemReaderBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
class ReaderConsistencyTest {

    private static final int PAGE_SIZE = 3;
    private static final String QUERY = "select s from Settlement s order by s.id asc";

    private final EntityManagerFactory entityManagerFactory;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    ReaderConsistencyTest(
            EntityManagerFactory entityManagerFactory, JdbcTemplate jdbcTemplate) {
        this.entityManagerFactory = entityManagerFactory;
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeEach
    void clearSettlements() {
        jdbcTemplate.update("delete from settlement");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("consistencyScenarios")
    void readersReturnEveryIdExactlyOnceInTheSameOrder(String scenario, List<Long> expectedIds)
            throws Exception {
        insertSettlements(expectedIds);

        List<Long> pagingIds = readIds(newPagingReader(entityManagerFactory));
        List<Long> zeroOffsetIds = readIds(newZeroOffsetReader(entityManagerFactory));

        assertThat(pagingIds).containsExactlyElementsOf(expectedIds).doesNotHaveDuplicates();
        assertThat(zeroOffsetIds).containsExactlyElementsOf(expectedIds).doesNotHaveDuplicates();
        assertThat(zeroOffsetIds).containsExactlyElementsOf(pagingIds);
    }

    @Test
    void pagingReaderDoesNotAccumulatePreviousPagesInItsPersistenceContext() throws Exception {
        insertSettlements(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L));
        EntityManager readerEntityManager = entityManagerFactory.createEntityManager();
        EntityManagerFactory capturingFactory = mock(EntityManagerFactory.class);
        when(capturingFactory.createEntityManager(anyMap())).thenReturn(readerEntityManager);
        JpaPagingItemReader<Settlement> reader = newPagingReader(capturingFactory);
        reader.open(new ExecutionContext());

        try {
            List<Settlement> firstPage = readItems(reader, PAGE_SIZE);

            Settlement firstItemOfNextPage = reader.read();

            assertThat(firstItemOfNextPage).isNotNull();
            assertThat(firstPage)
                    .allSatisfy(item -> assertThat(readerEntityManager.contains(item)).isFalse());

            List<Settlement> allItems = new ArrayList<>(firstPage);
            allItems.add(firstItemOfNextPage);
            Settlement item;
            while ((item = reader.read()) != null) {
                allItems.add(item);
            }

            assertThat(allItems).hasSize(7);
            assertThat(allItems.subList(0, 6))
                    .allSatisfy(
                            settlement ->
                                    assertThat(readerEntityManager.contains(settlement)).isFalse());
            assertThat(readerEntityManager.contains(allItems.get(6))).isTrue();
        } finally {
            reader.close();
        }

        assertThat(readerEntityManager.isOpen()).isFalse();
    }

    @Test
    void zeroOffsetReaderClearsEachPageFromItsPersistenceContext() throws Exception {
        insertSettlements(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L));
        EntityManager readerEntityManager = entityManagerFactory.createEntityManager();
        EntityManagerFactory capturingFactory = mock(EntityManagerFactory.class);
        when(capturingFactory.createEntityManager()).thenReturn(readerEntityManager);
        ZeroOffsetItemReader reader = newZeroOffsetReader(capturingFactory);
        reader.open(new ExecutionContext());

        try {
            List<Settlement> firstPage = readItems(reader, PAGE_SIZE);

            assertThat(firstPage)
                    .allSatisfy(item -> assertThat(readerEntityManager.contains(item)).isFalse());

            List<Settlement> allItems = new ArrayList<>(firstPage);
            Settlement item;
            while ((item = reader.read()) != null) {
                allItems.add(item);
            }

            assertThat(allItems).hasSize(7);
            assertThat(allItems)
                    .allSatisfy(
                            settlement ->
                                    assertThat(readerEntityManager.contains(settlement)).isFalse());
        } finally {
            reader.close();
        }
    }

    private static Stream<Arguments> consistencyScenarios() {
        return Stream.of(
                Arguments.of("empty table", List.of()),
                Arguments.of("smaller than page size", List.of(1L, 2L)),
                Arguments.of("exactly page size", List.of(1L, 2L, 3L)),
                Arguments.of("multiple pages", List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L)),
                Arguments.of("gaps between IDs", List.of(1L, 3L, 7L, 11L, 20L, 42L, 100L)));
    }

    private JpaPagingItemReader<Settlement> newPagingReader(EntityManagerFactory factory) {
        return new JpaPagingItemReaderBuilder<Settlement>()
                .name("consistencyPagingReader")
                .entityManagerFactory(factory)
                .queryString(QUERY)
                .hintValues(Map.of(HibernateHints.HINT_FETCH_SIZE, PAGE_SIZE))
                .pageSize(PAGE_SIZE)
                .saveState(true)
                .build();
    }

    private ZeroOffsetItemReader newZeroOffsetReader(EntityManagerFactory factory) {
        return new ZeroOffsetItemReader(
                "consistencyZeroOffsetReader", factory, PAGE_SIZE, PAGE_SIZE);
    }

    private List<Long> readIds(ItemStreamReader<Settlement> reader) throws Exception {
        List<Long> ids = new ArrayList<>();
        reader.open(new ExecutionContext());
        try {
            Settlement settlement;
            while ((settlement = reader.read()) != null) {
                ids.add(settlement.getId());
            }
        } finally {
            reader.close();
        }
        return ids;
    }

    private List<Settlement> readItems(ItemStreamReader<Settlement> reader, int count)
            throws Exception {
        List<Settlement> items = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            Settlement item = reader.read();
            assertThat(item).isNotNull();
            items.add(item);
        }
        return items;
    }

    private void insertSettlements(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }

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
