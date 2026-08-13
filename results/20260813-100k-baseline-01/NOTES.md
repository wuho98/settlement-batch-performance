# 100k baseline format-check run

- 실행 자체는 성공했으며 워밍업 2회와 측정 6회가 모두 `COMPLETED`로 종료됐다.
- 각 실행에서 `readCount`와 `writeCount`는 100,000건, `skipCount`와 `rollbackCount`는 0건이었다.
- 페이지 로그에 실행 회차를 직접 식별하는 필드가 없어서 최종 결과 집계에서는 제외한다.
- Gradle 실행 JVM이 고정 조건인 Java 21이 아니라 Java 26이었으므로 최종 결과 집계에서는 제외한다.
- 이 실행의 일부 수치만 선택해 최종 결과에 사용하지 않으며, 최종 측정은 로그 형식을 보강한 뒤 별도 실행 ID로 수행한다.
