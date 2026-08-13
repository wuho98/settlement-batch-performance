# 500k baseline 01

## 실행 조건

- 실행 시각: 2026-08-13 19:28 KST
- 데이터셋: 500,000건
- chunk/page/fetch size: 1,000
- Java: 21.0.7 LTS
- DB: Docker MySQL 8.4.10
- 실행 순서: 워밍업 `PAGING, ZERO_OFFSET`, 측정 `PAGING, ZERO_OFFSET, ZERO_OFFSET, PAGING, PAGING, ZERO_OFFSET`
- Writer: 두 Job 모두 동일한 no-op Writer

## Step 실행 시간

| Reader | 1회차 | 2회차 | 3회차 | 평균 | 중앙값 | Paging 대비 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| JpaPaging | 18,766.024ms | 18,636.937ms | 18,163.810ms | 18,522.257ms | 18,636.937ms | 100.0% |
| Zero Offset | 4,228.155ms | 4,205.353ms | 4,164.991ms | 4,199.500ms | 4,205.353ms | 22.7% |

Zero Offset 평균 Step 시간은 Paging의 22.7%였다. 워밍업 값은 표와 평균에서 제외했다.

## 페이지 진행 경향

| Reader | 초반 page 0~9 | 중반 page 245~254 | 후반 page 490~499 | 후반/초반 |
| --- | ---: | ---: | ---: | ---: |
| JpaPaging | 5.659ms | 31.605ms | 55.416ms | 9.79배 |
| Zero Offset | 5.123ms | 4.579ms | 4.597ms | 0.90배 |

페이지별 수치는 측정 3회의 각 구간 10개 page를 합쳐 평균 냈다. 데이터를 반환하지 않는 page 500은 원본 로그에 보존하되 추세에서는 제외했다.

## 정합성 및 실패 기록

- 워밍업 2회와 측정 6회 모두 `COMPLETED`
- 모든 실행의 `readCount=500000`, `writeCount=500000`
- 모든 실행의 `skipCount=0`, `rollbackCount=0`
- 페이지 로그 4,008개
- 실패 실행과 제외한 측정값 없음

## 파일

- `metrics.log`: 세션, 실행 8회, 페이지 4,008회의 구조화 지표
- `raw/benchmark.log`: 애플리케이션 전체 원본 로그
- `raw/dataset-verification.log`: 데이터 건수와 분포 검증 결과
- `environment.txt`: 소스 기준점과 실행 환경
- `source-status.txt`, `source-diff.patch`, `source-snapshot.tar.gz`: 측정 시점 소스 보존

## 한계

단일 로컬 머신의 한 세션 결과다. DB 버퍼 풀, OS 캐시, JIT 영향을 완전히 제거하지 못했고 페이지 측정 로그의 오버헤드가 절대 시간에 포함된다.
