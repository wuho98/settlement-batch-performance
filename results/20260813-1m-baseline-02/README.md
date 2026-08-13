# 1m baseline 02

## 실행 조건

- 실행 시각: 2026-08-13 19:47 KST
- 데이터셋: 1,000,000건
- chunk/page/fetch size: 1,000
- Java: 21.0.7 LTS
- DB: Docker MySQL 8.4.10
- 실행 순서: 워밍업 `PAGING, ZERO_OFFSET`, 측정 `PAGING, ZERO_OFFSET, ZERO_OFFSET, PAGING, PAGING, ZERO_OFFSET`
- Writer: 두 Job 모두 동일한 no-op Writer

## Step 실행 시간

| Reader | 1회차 | 2회차 | 3회차 | 평균 | 중앙값 | Paging 대비 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| JpaPaging | 124,191.909ms | 129,580.721ms | 123,945.169ms | 125,905.933ms | 124,191.909ms | 100.0% |
| Zero Offset | 10,306.721ms | 8,788.994ms | 8,321.469ms | 9,139.061ms | 8,788.994ms | 7.3% |

Zero Offset 평균 Step 시간은 Paging의 7.3%였다. 워밍업 값은 표와 평균에서 제외했다.

## 페이지 진행 경향

| Reader | 초반 page 0~9 | 중반 page 495~504 | 후반 page 990~999 | 후반/초반 |
| --- | ---: | ---: | ---: | ---: |
| JpaPaging | 6.630ms | 59.865ms | 367.600ms | 55.45배 |
| Zero Offset | 5.988ms | 5.199ms | 4.977ms | 0.83배 |

페이지별 수치는 측정 3회의 각 구간 10개 page를 합쳐 평균 냈다. 데이터를 반환하지 않는 page 1000은 원본 로그에 보존하되 추세에서는 제외했다.

## 정합성 및 실패 기록

- 워밍업 2회와 측정 6회 모두 `COMPLETED`
- 모든 실행의 `readCount=1000000`, `writeCount=1000000`
- 모든 실행의 `skipCount=0`, `rollbackCount=0`
- 페이지 로그 8,008개
- Step 시간과 단조 시계 시간이 모든 실행에서 일치하는 수준인지 확인함

## 재측정 사유

첫 실행 `20260813-1m-baseline-01`의 Paging 2회차는 시스템 시각 보정으로 Step 시간과 단조 시계 시간이 약 392초 차이 났다. 첫 실행을 삭제하지 않고 원본과 `NOTES.md`를 보존했으며, 최종 3회 통계는 같은 조건으로 전체를 다시 실행한 이 결과를 사용한다.

## 파일

- `metrics.log`: 세션, 실행 8회, 페이지 8,008회의 구조화 지표
- `raw/benchmark.log`: 애플리케이션 전체 원본 로그
- `raw/dataset-verification.log`: 데이터 건수와 분포 검증 결과
- `environment.txt`: 소스 기준점과 실행 환경
- `source-status.txt`, `source-diff.patch`, `source-snapshot.tar.gz`: 측정 시점 소스 보존

## 한계

단일 로컬 머신의 한 세션 결과다. DB 버퍼 풀, OS 캐시, JIT 영향을 완전히 제거하지 못했고 페이지 측정 로그의 오버헤드가 절대 시간에 포함된다.
