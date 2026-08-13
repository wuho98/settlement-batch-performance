# 100k baseline 02

## 실행 조건

- 실행 시각: 2026-08-13 03:57 KST
- 데이터셋: 100,000건
- chunk/page/fetch size: 1,000
- Java: 21.0.7 LTS
- DB: Docker MySQL 8.4.10
- 실행 순서: 워밍업 `PAGING, ZERO_OFFSET`, 측정 `PAGING, ZERO_OFFSET, ZERO_OFFSET, PAGING, PAGING, ZERO_OFFSET`
- Writer: 두 Job 모두 동일한 no-op Writer

## Step 실행 시간

| Reader | 1회차 | 2회차 | 3회차 | 평균 | 중앙값 |
| --- | ---: | ---: | ---: | ---: | ---: |
| JpaPaging | 1,418.181ms | 1,324.990ms | 1,555.820ms | 1,432.997ms | 1,418.181ms |
| Zero Offset | 886.788ms | 857.492ms | 851.566ms | 865.282ms | 857.492ms |

Zero Offset 평균은 Paging 평균의 60.4%였다. 워밍업 값은 표와 평균에서 제외했다.

## 정합성 및 실패 기록

- 워밍업 2회와 측정 6회 모두 `COMPLETED`
- 모든 실행의 `readCount=100000`, `writeCount=100000`
- 모든 실행의 `skipCount=0`, `rollbackCount=0`
- 실패 실행 없음
- 측정값과 페이지별 일시적 상승 구간을 제외하지 않음

## 파일

- `metrics.log`: 세션, 실행 8회, 페이지 808회의 구조화 지표
- `raw/benchmark.log`: 애플리케이션 전체 원본 로그
- `raw/dataset-verification.log`: 데이터 건수와 분포 검증 결과
- `raw/schema.log`, `raw/dataset-generation.log`: 스키마와 데이터 준비 로그
- `environment.txt`: 소스 기준점과 실행 환경
- `source-diff.patch`: 측정 시점의 커밋되지 않은 소스 변경
- `benchmark-report.xlsx`: 수식 기반 요약, 개별 실행값, 페이지 추세 차트, 원본 페이지 지표, 환경

## 한계

단일 로컬 머신의 한 세션 결과다. DB 버퍼 풀, OS 캐시, JIT 영향을 완전히 제거하지 못했다. 페이지 측정 로그의 오버헤드가 절대 시간에 포함되며, 50만·100만 건은 아직 측정하지 않았다.
