# 1m baseline 01 anomaly note

- 워밍업 2회와 측정 6회는 모두 `COMPLETED`로 종료됐다.
- 모든 실행은 1,000,000건을 읽고 썼으며 `skipCount=0`, `rollbackCount=0`이었다.
- 측정 2회차 Paging(sequence 6)의 `stepDurationNanos`는 511,195.758ms로 기록됐지만, 같은 실행을 `System.nanoTime()`으로 잰 `wallDurationNanos`는 118,807.568ms였다.
- sequence 6의 페이지별 `System.nanoTime()` 합계는 113,050.750ms로 단조 시계 경과 시간과 비슷하다.
- 두 시계의 차이 392,388.190ms는 실행 중 시스템 시각 보정이 Step 시작·종료 시각 계산에 들어간 이상치로 판단한다.
- 원본 실행과 이상치를 삭제하거나 수정하지 않는다. 최종 3회 Step 통계는 같은 고정 조건으로 다시 실행한 별도 실행 ID에서 산출한다.
