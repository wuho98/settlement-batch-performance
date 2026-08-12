package com.example.settlementbatchperformance;

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.EnableJdbcJobRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableBatchProcessing
@EnableJdbcJobRepository
public class SettlementBatchPerformanceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SettlementBatchPerformanceApplication.class, args);
    }

}
