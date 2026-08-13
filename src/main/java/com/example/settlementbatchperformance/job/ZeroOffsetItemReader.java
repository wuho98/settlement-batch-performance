package com.example.settlementbatchperformance.job;

import com.example.settlementbatchperformance.domain.Settlement;
import com.example.settlementbatchperformance.metrics.BenchmarkPageLog;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import java.util.Collections;
import java.util.List;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.support.AbstractItemStreamItemReader;
import org.hibernate.jpa.HibernateHints;
import org.springframework.util.Assert;

/**
 * Reads settlements using the primary key as a keyset instead of an offset.
 *
 * <p>The persisted checkpoint is the ID of the last item handed to the step. Spring Batch calls
 * {@link #update(ExecutionContext)} in the chunk transaction, so a rolled-back chunk does not move
 * the durable checkpoint past uncommitted items.
 */
public class ZeroOffsetItemReader extends AbstractItemStreamItemReader<Settlement> {

    private static final String LAST_ID_KEY_SUFFIX = ".lastId";
    private static final long INITIAL_LAST_ID = Long.MIN_VALUE;

    private final EntityManagerFactory entityManagerFactory;
    private final int pageSize;
    private final int fetchSize;
    private final boolean benchmarkMetricsEnabled;

    private EntityManager entityManager;
    private List<Settlement> page = Collections.emptyList();
    private int index;
    private long lastId = INITIAL_LAST_ID;
    private boolean exhausted;
    private int pageNumber;

    public ZeroOffsetItemReader(
            String name, EntityManagerFactory entityManagerFactory, int pageSize, int fetchSize) {
        this(name, entityManagerFactory, pageSize, fetchSize, false);
    }

    public ZeroOffsetItemReader(
            String name,
            EntityManagerFactory entityManagerFactory,
            int pageSize,
            int fetchSize,
            boolean benchmarkMetricsEnabled) {
        Assert.hasText(name, "name must not be blank");
        Assert.notNull(entityManagerFactory, "entityManagerFactory must not be null");
        Assert.isTrue(pageSize > 0, "pageSize must be greater than zero");
        Assert.isTrue(fetchSize > 0, "fetchSize must be greater than zero");

        setName(name);
        this.entityManagerFactory = entityManagerFactory;
        this.pageSize = pageSize;
        this.fetchSize = fetchSize;
        this.benchmarkMetricsEnabled = benchmarkMetricsEnabled;
    }

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        super.open(executionContext);
        lastId = executionContext.getLong(executionContextKey(), INITIAL_LAST_ID);
        page = Collections.emptyList();
        index = 0;
        exhausted = false;
        pageNumber = 0;
        entityManager = entityManagerFactory.createEntityManager();
    }

    @Override
    public Settlement read() {
        if (exhausted) {
            return null;
        }

        if (index >= page.size()) {
            loadNextPage();
            if (exhausted) {
                return null;
            }
        }

        Settlement item = page.get(index++);
        if (item.getId() <= lastId) {
            throw new IllegalStateException(
                    "Zero Offset query did not advance beyond lastId=" + lastId);
        }
        lastId = item.getId();
        return item;
    }

    private void loadNextPage() {
        long queryLastId = lastId;
        long startedAt = System.nanoTime();
        EntityTransaction transaction = entityManager.getTransaction();
        transaction.begin();
        try {
            page = entityManager
                    .createQuery(
                            "select s from Settlement s where s.id > :lastId order by s.id asc",
                            Settlement.class)
                    .setParameter("lastId", queryLastId)
                    .setHint(HibernateHints.HINT_FETCH_SIZE, fetchSize)
                    .setMaxResults(pageSize)
                    .getResultList();
            transaction.commit();
        } catch (RuntimeException exception) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            throw exception;
        }
        long durationNanos = System.nanoTime() - startedAt;
        index = 0;

        BenchmarkPageLog.record(
                benchmarkMetricsEnabled,
                "ZERO_OFFSET",
                pageNumber++,
                "LAST_ID",
                queryLastId == INITIAL_LAST_ID ? "INITIAL" : Long.toString(queryLastId),
                page,
                durationNanos);

        if (page.isEmpty()) {
            exhausted = true;
            return;
        }

        // The reader only needs scalar entity state. Do not retain every page in the persistence
        // context throughout a large batch execution.
        entityManager.clear();
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        super.update(executionContext);
        executionContext.putLong(executionContextKey(), lastId);
    }

    @Override
    public void close() throws ItemStreamException {
        page = Collections.emptyList();
        index = 0;
        exhausted = true;
        if (entityManager != null && entityManager.isOpen()) {
            entityManager.close();
        }
        entityManager = null;
        super.close();
    }

    private String executionContextKey() {
        return getName() + LAST_ID_KEY_SUFFIX;
    }
}
