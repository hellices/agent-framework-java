# Microsoft Agent Framework 설계 기준

이 디렉터리는 Microsoft Agent Framework(MAF)의 특정 upstream commit을 기준으로 Java
구현에 필요한 동작과 설계를 추적한다.

## 현재 기준

- Upstream: <https://github.com/microsoft/agent-framework>
- Branch: `main`
- Commit: `d0a4165f170193ba1d026a259af40d35bb7eaefe`
- Commit time: 2026-08-10 05:51:59 UTC

## 분석 우선순위

동일 기능을 설명하는 자료가 다르면 다음 순서로 판단한다.

1. 고정 commit의 production source
2. 고정 commit의 unit, integration 및 conformance tests
3. 고정 commit 안의 specification과 feature 문서
4. Microsoft Learn의 공식 문서
5. Sample과 README

문서에만 있고 코드와 테스트에서 확인되지 않는 기능은 구현 완료로 간주하지 않는다. .NET과
Python 구현이 다르면 한쪽을 임의로 표준으로 선택하지 않고 차이를 기록한 뒤 Java 설계
결정으로 분리한다.

## 문서 집합

- [스냅샷 인덱스](snapshots/d0a4165f/README.md)
- [스냅샷 매니페스트](snapshots/d0a4165f/snapshot-manifest.md)
- [Coverage ledger](snapshots/d0a4165f/coverage-ledger.md)
- [호환성 매트릭스](snapshots/d0a4165f/compatibility-matrix.md)

31개 기능 문서는 스냅샷 인덱스에서 기능군별로 묶어 탐색하고, 언어별 기능 매트릭스와 Java
호환성 결정은 coverage ledger와 호환성 매트릭스를 함께 기준으로 삼는다.

## 갱신 정책

새 upstream 기준으로 이동할 때 기존 스냅샷 문서를 덮어쓰지 않는다. 새 commit 디렉터리를
추가하고 두 commit 사이의 기능 및 동작 차이를 별도 delta 문서로 기록한다. 이 방식으로
Java 구현이 어떤 upstream 상태를 지원하는지 재현할 수 있게 한다.
