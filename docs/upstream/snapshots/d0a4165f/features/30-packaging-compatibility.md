# 30. Packaging & Compatibility

## 상태

- 문서 상태: upstream snapshot 분석 문서
- 기준 스냅샷: `d0a4165f170193ba1d026a259af40d35bb7eaefe`
- 분석 범위: package/project layout, release metadata, stable/alpha/beta/experimental 판정, versioning, dependency bounds, CI release validation, cross-language compatibility, snapshot update policy
- 비범위:
  - observability/telemetry 세부 동작은 observability 문서 소유
  - error taxonomy/timeout/security boundary는 errors-resilience-security 문서 소유
  - evaluation algorithm과 conformance corpus의 상세 동작은 evaluation-testing 문서 소유
  - serialization/versioning의 객체 포맷 계약은 session 문서 소유이며, 본 문서는 배포/릴리스/호환성 관점의 versioning만 다룬다

## 스냅샷 요약

이 스냅샷은 하나의 저장소 안에 `.NET`, Python, declarative assets를 함께 두는 **다중 언어 monorepo** 구조를 사용한다. CI의 sparse checkout과 Python workspace 설정을 보면 저장소 운영 단위가 언어별 하위 트리로 분리되어 있고, Python은 `packages/*` workspace로, `.NET`은 중앙 패키지 버전 관리와 다수의 SDK-style project로 묶여 있다. Python 쪽은 package-level lifecycle 문서가 매우 명시적이고, `.NET` 쪽은 각 csproj의 `IsReleased`, `IsReleaseCandidate`, `VersionSuffix` 같은 build metadata로 릴리스 단계를 표현한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L106-L114  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L73-L77  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L309-L355  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L5-L18  

Release metadata와 compatibility gate도 언어별로 다르다. `.NET`은 `dotnet/nuget/nuget-package.props`에서 중앙 `VersionPrefix`, preview/RC/stable package version 규칙, SourceLink, symbols, package validation baseline, NuGet audit를 관리하고, CI에서 실제 패키지를 pack한 뒤 새 콘솔 앱에 설치해 build하는 install check까지 수행한다. Python은 root `pyproject.toml`에서 stable classifier와 release URLs를 노출하고, `PACKAGE_STATUS.md`에서 alpha/beta/rc/released/deprecated를 선언하며, dependency bounds는 coding standard와 validation task로 관리한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L3-L10  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L16-L33  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L35-L60  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L130-L164  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L1-L27  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L5-L12  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L413-L431  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L386-L401  

Cross-language compatibility는 “같은 package manager semantics를 공유한다”는 뜻이 아니다. 실제로 Python은 PyPI-style package lifecycle과 bounded dependency ranges를 쓰고, `.NET`은 multi-TFM binary compatibility와 package validation baseline을 쓴다. 다만 저장소는 `.NET`/Python 전반에서 conformance와 engineering testing을 수행한다고 밝히며, Python changelog는 외부 extension repo로 코드가 이동해도 re-export와 install surface를 유지해 기존 import를 깨지 않도록 하는 호환성 전략을 보여준다. 즉 언어 간 호환성은 동일 배포 방식이 아니라 **공개 surface 유지, 테스트 기반 behavioral alignment, staged promotion** 으로 달성된다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/TRANSPARENCY_FAQ.md#L26-L28  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L10-L11  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L29-L29  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L109-L122  

## 원본 기능 목적과 경계

### 1. Package / project layout의 목적

이 저장소의 layout 목적은 기능별 package 분리, 언어별 build system 독립성, 그리고 selective installation을 동시에 만족하는 것이다. Python은 `agent-framework` meta package와 `packages/*` 하위 package들로 분해되어 있으며, core가 lazy-loading root surface 역할을 한다. `.NET`은 다수의 `dotnet/src/*` project를 개별 NuGet package로 유지하면서도 중앙 버전 관리와 공통 build props를 적용한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L309-L355  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/README.md#L17-L37  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/Directory.Packages.props#L3-L6  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests.csproj#L19-L23  

### 2. Release metadata의 목적

Release metadata의 목적은 artifact version을 계산하고, package feed/portal/debugging metadata를 일관되게 노출하며, 릴리스 단계별 validation 정책을 적용하는 것이다. `.NET`은 `VersionPrefix`, `PackageVersion`, `GitTag`, package URL, SourceLink, symbols, docs, package validation baseline을 중앙화한다. Python은 project version, homepage/source/release-notes URL, PyPI classifiers를 통해 설치와 release surface를 규정한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L3-L11  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L35-L60  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L1-L27  

### 3. Stable / alpha / beta / experimental 판정의 목적

릴리스 단계 표시는 사용자 기대치와 CI 정책을 맞추기 위한 것이다. Python은 package lifecycle을 `alpha`, `beta`, `rc`, `released`, `deprecated`로 명시하고, 별도로 feature-level `experimental` stage를 관리한다. `.NET`은 이번 수집 근거 기준 package metadata 수준에서 `released`, `release candidate`, `preview`가 직접 확인되며, Python과 동일한 `alpha`/`beta` package taxonomy가 csproj 메타데이터에서 직접 드러나는지는 확인 불가다. 대신 `.NET`은 API-level `[Experimental]` 사용과 legacy TFM용 `ExperimentalAttribute` 주입 경로를 가진다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L5-L12  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L60-L66  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L87-L153  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Microsoft.Agents.AI.csproj#L3-L6  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Microsoft.Agents.AI.Purview.csproj#L3-L5  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/Microsoft.Agents.AI.Hosting.csproj#L3-L5  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L97-L98  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/eng/MSBuild/LegacySupport.props#L14-L16  

### 4. Versioning의 목적

Versioning의 목적은 release train을 관리하고, preview/RC/stable artifact 구분을 자동화하며, consuming projects가 어떤 compatibility promise를 기대할 수 있는지 명시하는 것이다. `.NET`은 central `VersionPrefix` 위에 preview/RC/stable suffix policy를 얹고, Python은 semver 기반 root version, package-specific version tags, changelog-driven promotion을 사용한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L3-L10  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L1-L8  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L13-L29  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L60-L74  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L113-L122  

### 5. Dependency bounds의 목적

Dependency bounds는 외부 의존성의 지원 범위를 넓히되, 검증된 범위만 약속하기 위한 것이다. Python coding standard는 stable dependency에는 lower bound + explicit upper major cap을, prerelease/`<1.0` dependency에는 더 엄격한 bounded range를 요구한다. `.NET`은 dependency version bounds를 per-package spec 대신 중앙 package version pinning과 transitive pinning으로 관리한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L420-L431  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L386-L401  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L60-L67  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/Directory.Packages.props#L3-L6  

### 6. CI release validation의 목적

CI release validation의 목적은 “빌드가 된다”보다 강하게 “패키지가 실제 설치되고, baseline과 호환되고, 감사 기준을 통과한다”를 보장하는 것이다. `.NET`은 package validation과 install check를, Python은 dependency-bound validation과 release-tag build workflow를 통해 이를 수행한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/CONTRIBUTING.md#L77-L97  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L16-L33  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L130-L164  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L386-L401  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-release.yml#L14-L63  

### 7. Cross-language compatibility의 목적

Cross-language compatibility의 목적은 동일 기능을 동일 배포 방식으로 제공하는 데 있지 않고, 각 언어에 맞는 package system을 사용하면서도 **공개 contract와 expected behavior를 안정적으로 유지**하는 데 있다. Python changelog가 durable/azurefunctions integration을 외부 extension repo로 이동시키면서도 re-export와 install surface를 유지한다고 밝히는 점은 이 전략의 좋은 사례다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/TRANSPARENCY_FAQ.md#L26-L28  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L10-L11  

### 8. Snapshot update policy의 목적

Snapshot 문서 업데이트 정책의 목적은 upstream의 stage promotion, repository split, dependency compatibility adjustment, release metadata change를 놓치지 않고 downstream 문서를 다시 생성하는 것이다. 이 저장소는 changelog와 package status 문서가 매우 적극적으로 lifecycle 변화와 compatibility adjustments를 기록하므로, snapshot update의 주 트리거로 적합하다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L10-L11  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L25-L29  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L66-L74  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L109-L122  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L13-L58  

## 공개 API

이 문서에서의 “공개 API”는 runtime method API가 아니라, 외부 소비자와 downstream maintainer가 의존하는 **배포/메타데이터/호환성 surface** 를 뜻한다.

### .NET 공개 surface

1. `IsReleased`, `IsReleaseCandidate`, `VersionSuffix`  
   - package stage를 결정하는 csproj metadata  
   출처:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Microsoft.Agents.AI.csproj#L3-L6  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Microsoft.Agents.AI.Purview.csproj#L3-L5  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/Microsoft.Agents.AI.Hosting.csproj#L3-L5  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.A2A/Microsoft.Agents.AI.A2A.csproj#L3-L6  

2. `VersionPrefix`, `PackageVersion`, `GitTag`, `PackageValidationBaselineVersion`, `EnablePackageValidation`, package URLs and symbols  
   - central package metadata and compatibility policy  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L3-L60

3. `ManagePackageVersionsCentrally`, `CentralPackageTransitivePinningEnabled`, `PackageVersion Include=...`  
   - central dependency version surface  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/Directory.Packages.props#L3-L24

4. `CompatibilitySuppressions.xml`  
   - approved break/suppression artifact  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/CompatibilitySuppressions.xml#L1-L120

5. `LegacySupport.props`  
   - legacy TFMs에 experimental/trim/init/diagnostic shims를 주입하는 compatibility build surface  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/eng/MSBuild/LegacySupport.props#L2-L32

### Python 공개 surface

1. root `pyproject.toml`  
   - project name/version, stable classifier, release note/source URLs, Python requirement  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L1-L27

2. `PACKAGE_STATUS.md`  
   - alpha/beta/rc/released/deprecated package lifecycle와 feature-level staged API 상태  
   출처:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L5-L12  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L13-L58  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L60-L153  

3. `tool.uv.workspace`, `tool.uv.sources`, dependency/constraint override settings  
   - Python monorepo build/install surface와 dependency bound override surface  
   출처:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L60-L77  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L76-L112  

4. Python release tag format  
   - `python-<package>-<version>`  
   출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-release.yml#L36-L49

5. Dependency bounds policy in coding standard / validation tasks  
   - stable/pre-release/<1.0 dependency bound rules  
   출처:  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L413-L431  
   - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L386-L401  

## 상세 실행 흐름

### 1. .NET release version 계산 흐름

1. 중앙 `VersionPrefix` 와 `DateSuffix`, `RCNumber`가 정의된다.
2. `IsReleaseCandidate=true` 이면 `PackageVersion = <VersionPrefix>-rc<RCNumber>` 이다.
3. `IsReleaseCandidate!=true` 이고 `VersionSuffix != ''` 이면 `<VersionPrefix>-<VersionSuffix>.<DateSuffix>.1` 을 쓴다.
4. `IsReleaseCandidate!=true` 이고 `VersionSuffix == ''` 이면 `<VersionPrefix>-preview.<DateSuffix>.1` 이다.
5. `IsReleased=true` 이면 suffix 없는 stable `<VersionPrefix>` 를 사용한다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L3-L10

### 2. .NET package validation / compatibility 흐름

1. shared props는 `PackageValidationBaselineVersion=1.0.0` 을 설정한다.
2. `IsReleased=true` 인 package에서만 package validation을 자동 활성화한다.
3. release build/pack 중 CP diagnostics가 발생하면 CONTRIBUTING 문서 기준으로:
   - 의도치 않은 break면 refactor
   - 의도된 break면 suppression file 생성  
4. 승인된 break는 `CompatibilitySuppressions.xml` 에 기록된다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L16-L23  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/CONTRIBUTING.md#L77-L97  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/CompatibilitySuppressions.xml#L1-L120  

### 3. .NET install validation 흐름

CI는 pack만 하지 않는다.
1. release configuration에서 solution을 pack 한다.
2. 새 임시 console app을 만든다.
3. local artifacts를 source로 넣고 NuGet.org를 추가한다.
4. 실제로 `Microsoft.Agents.AI` 와 `Microsoft.Agents.AI.LocalCodeAct` package를 add 한 뒤 build 한다.  
이 단계는 “NuGet metadata가 실제 소비 가능한가”를 검증한다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L130-L164

### 4. Python install surface와 selective installation 흐름

Python은 두 가지 install story를 제공한다.
1. `pip install agent-framework` 로 전체 framework와 모든 sub-package를 받는 development mode
2. integration이 필요한 package만 selective install  
또한 released package는 더 이상 `--pre`가 필요 없고, preview connector는 여전히 `--pre`가 필요하다. 이 차이가 package maturity를 실제 설치 UX에 반영한다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/README.md#L7-L37

### 5. Python lifecycle 판정 흐름

Python package 상태는 `PACKAGE_STATUS.md`가 단일 진실 원천처럼 동작한다.
- meta package/root package
- 각 integration package
- deprecated package  
그리고 feature-level experimental API는 package 상태와 별도로 관리된다. 즉 package가 released라도 feature가 experimental 일 수 있다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L13-L58  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L60-L153  

### 6. Python dependency bounds / validation 흐름

1. coding standard가 stable/prerelease/`<1.0` dependency의 bound 규칙을 정의한다.
2. `validate-dependency-bounds-test` 는 workspace-wide lower/upper resolution gate를 수행한다.
3. `validate-dependency-bounds-project` 는 특정 package/dependency에 대해 실제 lower/upper 범위를 탐색·검증한다.
4. `add-dependency-and-validate-bounds` 는 dependency 추가와 bound validation을 묶는다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L420-L431  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L386-L401  

### 7. Python release asset build 흐름

release workflow는 published GitHub release가 `python-` tag로 시작할 때만 실행된다. tag에서 package name을 추출해 `packages/<package>` 존재 여부를 검증하고, `uv run poe --directory packages/<package> build` 를 수행한 뒤 결과물을 GitHub release asset으로 업로드한다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-release.yml#L14-L63

### 8. Cross-language compatibility 유지 흐름

Python changelog는 durabletask/azurefunctions integration이 별도 repository로 이동했지만, core가 public symbol을 re-export 하고 `all` extra 설치 surface도 유지한다고 설명한다. 이 방식은 implementation 위치가 바뀌어도 consumer-facing import/install contract를 보존한다는 점에서 cross-language/extension compatibility 운영 원칙을 잘 보여준다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L10-L11

### 9. Snapshot update policy 흐름

이 snapshot에 대한 downstream 문서 갱신은 changelog와 status file의 변화에 의해 트리거되는 것이 합리적이다.
- package promotion (`rc -> stable`, `preview/beta` 전환)
- dependency compatibility adjustment
- repo split / package move
- main installation surface 변경  
이들은 모두 changelog와 status 문서에 직접 반영되기 때문이다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L10-L11  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L66-L74  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L113-L122  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L13-L58  

## 상태 및 구성

### 저장소 / monorepo layout

- 저장소 운영 단위는 최소한 `dotnet/`, `python/`, `declarative-agents/` 로 분리되어 있다.
- Python은 workspace `packages/*` 구조를 갖고, root meta project가 전체 install surface를 담당한다.
- `.NET`은 `src` package projects와 `tests` projects가 분리되어 있으며, test csproj는 명시적 `ProjectReference`로 source project를 참조한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L106-L114  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L73-L77  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L309-L355  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/tests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests/Microsoft.Agents.AI.Hosting.OpenAI.UnitTests.csproj#L19-L23  

### `.NET` 현재 stage 예시

- released: `Microsoft.Agents.AI`
- release candidate: `Microsoft.Agents.AI.Purview`
- preview: `Microsoft.Agents.AI.Hosting`, `Microsoft.Agents.AI.A2A`  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Microsoft.Agents.AI.csproj#L3-L6  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Microsoft.Agents.AI.Purview.csproj#L3-L5  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/Microsoft.Agents.AI.Hosting.csproj#L3-L5  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.A2A/Microsoft.Agents.AI.A2A.csproj#L3-L6  

### Python 현재 stage 예시

- released: `agent-framework`, `agent-framework-core`, `agent-framework-foundry`, `agent-framework-openai`, `agent-framework-orchestrations`
- beta: `agent-framework-anthropic`, `agent-framework-bedrock`, `agent-framework-tools` 등
- alpha: `agent-framework-hosting`, `agent-framework-hosting-a2a`, `agent-framework-hosting-mcp`, `agent-framework-azure-cosmos-memory`
- deprecated: `agent-framework-azure-ai`  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L15-L58

### Experimental 판정 구성

- Python은 package status 문서에서 feature-level experimental을 명시적으로 나눈다.
- `.NET`은 이번 수집 근거에서 package-level experimental taxonomy보다는 API-level `[Experimental]` 과 legacy TFM용 `ExperimentalAttribute` 지원이 보인다.
- `.NET`의 alpha/beta package taxonomy는 이번 수집 근거만으로는 확인 불가다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L60-L153  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/OpenTelemetryAgent.cs#L97-L98  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/eng/MSBuild/LegacySupport.props#L14-L16  

## 오류와 보안

### 1. Supply-chain 보안

`.NET`은 central package management 하에서 transitive pinning을 켜고, NuGet audit를 활성화한다. Python은 `constraint-dependencies` 와 `override-dependencies` 로 security floors를 강제한다. 즉 둘 다 dependency compatibility를 “빌드 편의”만이 아니라 공급망 위험 관리로 본다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/Directory.Packages.props#L3-L6  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L25-L33  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L63-L66  

### 2. Patched dependency pinning

`.NET` central package file는 취약점 remediation 때문에 transitive dependency를 더 높은 버전으로 pin 했음을 주석으로 남긴다. Python도 동일하게 override dependency 주석으로 보안 목적 조정을 기록한다. 이런 주석은 downstream compatibility 문서에서 중요한 근거다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/Directory.Packages.props#L23-L24  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/Directory.Packages.props#L77-L80  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L63-L66  

### 3. Compatibility validation 실패의 처리

`.NET`은 CP diagnostics를 의도치 않은 breaking change로 해석하고, 승인된 경우에만 suppression file 생성을 허용한다. Python은 lower/upper dependency bounds validation이 실패하면 bound를 넓히기 전에 실제 지원 범위를 다시 검증하도록 유도한다. 즉 둘 다 compatibility failure를 “무시할 경고”가 아니라 **명시적 설계 판단이 필요한 실패**로 취급한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/CONTRIBUTING.md#L90-L97  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L386-L401  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L420-L431  

### 4. Release build failure surface

- Python release workflow는 tag에서 package를 뽑아 실제 디렉터리 존재 여부를 검증하고 없으면 실패한다.
- `.NET`은 release build + package validation + install check 중 하나라도 깨지면 artifact 소비 가능성이 보장되지 않는다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-release.yml#L36-L47  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L123-L164  

## .NET 구현

### 1. 중앙 버전/패키지 메타데이터

`.NET`은 `dotnet/nuget/nuget-package.props`에서 version 계산, package validation baseline, audit, package URLs, docs file, SourceLink, symbols를 통합 관리한다. 개별 csproj는 stage flag만 다르고, 공통 release metadata는 중앙 props를 import 한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L3-L60  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Microsoft.Agents.AI.csproj#L3-L19  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Microsoft.Agents.AI.Purview.csproj#L3-L13  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/Microsoft.Agents.AI.Hosting.csproj#L3-L13  

### 2. 중앙 dependency version 관리

`.NET`은 `ManagePackageVersionsCentrally` 와 `CentralPackageTransitivePinningEnabled` 를 사용한다. 이는 개별 csproj가 직접 version strings를 중복 선언하는 대신, 중앙 version registry를 쓰게 만든다. OpenTelemetry, Azure, System.*, Aspire.* 같은 broad dependency set도 중앙 파일에 pin 되어 있다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/Directory.Packages.props#L3-L6  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/Directory.Packages.props#L14-L24  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/Directory.Packages.props#L26-L33  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/Directory.Packages.props#L66-L80  

### 3. Binary/API compatibility 전략

`.NET`은 release package에 package validation baseline을 적용하고, intentional break는 suppression file로 기록한다. 또한 `LegacySupport.props`는 older TFMs에 compiler/runtime support shim을 주입해 multi-targeting compatibility를 보존한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L16-L23  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/CompatibilitySuppressions.xml#L1-L120  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/eng/MSBuild/LegacySupport.props#L2-L32  

### 4. Stage vocabulary의 특징

`.NET` build metadata에서 직접 보이는 vocabulary는 released / release-candidate / preview 이다. Python처럼 package-level alpha/beta table이 문서 파일 하나에 정리된 형태는 이번 수집 근거에서 직접 확인되지 않는다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Microsoft.Agents.AI.csproj#L3-L6  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Microsoft.Agents.AI.Purview.csproj#L3-L5  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/Microsoft.Agents.AI.Hosting.csproj#L3-L5  

## Python 구현

### 1. Meta package + workspace package 구조

Python은 root `agent-framework` meta package가 전체 framework umbrella 역할을 하고, 실제 implementation은 `packages/*` 의 sub-package에 있다. selective install guidance와 workspace declaration, coding standard의 package structure 설명이 이 구조를 함께 보여준다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L1-L27  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L73-L77  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/README.md#L17-L37  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L309-L355  

### 2. Lifecycle와 feature-stage가 문서화됨

Python은 package lifecycle 상태를 문서에서 직접 추적하고, experimental features를 package-level status와 별도로 식별한다. 따라서 package가 `released` 라도 내부 feature가 experimental 일 수 있다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L13-L58  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L60-L153  

### 3. Dependency bounds와 compatibility 지원 범위

Python은 bounds를 좁게 고정하기보다, 검증된 범위 안에서 넓게 유지하는 철학을 명시한다. 지원 범위를 넓히기 위해 version-conditional import나 branch를 허용하되, lower/upper validation을 실제로 돌린 뒤에만 bound를 넓힌다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L420-L431  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L386-L401  

### 4. Promotion / deprecation / repo split 신호가 changelog에 잘 드러남

Python changelog는 package promotion, compatibility adjustment, dependency validation tooling, repo split을 모두 적는다. 예를 들어 GitHub Copilot package stable promotion, declarative stable promotion, foundry-hosting beta promotion, extension repo split 등이 downstream snapshot update의 강한 신호다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L10-L11  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L66-L74  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L113-L122  

## 테스트 근거

### 1. `.NET` release/install validation 근거

CI는 build 후 단순 pack에 그치지 않고 실제로 새 console app에 package를 추가하고 build 한다. 이는 restore metadata, dependency resolution, installability를 모두 검증한다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L130-L164

### 2. `.NET` compatibility validation 근거

CONTRIBUTING 문서는 package validation이 build/pack에서 baseline API surface와 비교된다고 설명하고, suppression file 생성 절차를 문서화한다. 실제 suppression file도 repository에 존재한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/CONTRIBUTING.md#L77-L97  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/CompatibilitySuppressions.xml#L1-L120  

### 3. Python bounds/coverage validation 근거

Python DEV_SETUP 문서는 dependency bound validation task를 실제 작업 흐름의 일부로 규정하고, coverage enforcement 정책도 package maturity와 연결한다. 별도 coverage workflow는 threshold 85를 CI에서 검증한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L176-L190  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L386-L401  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-test-coverage.yml#L15-L46  

### 4. Python release packaging 근거

Python release workflow는 release tag prefix를 검사하고, tag에서 package name을 추출하고, 실제 패키지 디렉터리의 존재를 검증한 뒤 build artifact를 release에 첨부한다. release asset build가 package directory naming convention과 직접 연결되어 있다는 점이 중요하다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-release.yml#L14-L63

## 문서와 코드 차이

### 1. `.NET` package validation 설명과 실제 조건 이름 차이

CONTRIBUTING 문서는 “GA packages (`IsGenerallyAvailable=true`)” 라는 표현을 사용하지만, 실제 중앙 packaging props는 `IsReleased` 를 기준으로 validation을 켠다. 따라서 현재 snapshot에서 authoritative 한 조건 이름은 `IsReleased` 다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/CONTRIBUTING.md#L84-L88  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L16-L20  

### 2. Python package stability와 feature stability의 차이

Python root package는 stable classifier와 released status를 갖지만, `PACKAGE_STATUS.md` 는 feature-level experimental surface를 따로 추적한다. 따라서 package-level release 문구만 읽고 내부 feature까지 stable하다고 결론내리면 안 된다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L13-L24  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L60-L153  

### 3. `.NET` alpha/beta taxonomy의 직접 증거 부족

Python은 package-level alpha/beta/rc/released/deprecated가 명시적이지만, `.NET`은 이번 수집 근거에서 released/RC/preview만 명시적으로 확인된다. 따라서 `.NET beta` 또는 `.NET alpha` 를 Python package status와 1:1 대응시켜 쓰는 것은 이 snapshot 증거만으로는 과장이다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L5-L12  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Microsoft.Agents.AI.csproj#L3-L6  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Microsoft.Agents.AI.Purview.csproj#L3-L5  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/Microsoft.Agents.AI.Hosting.csproj#L3-L5  

## Java 설계 결정

### 결정 1. Java monorepo는 root BOM + module-local artifact 조합으로 설계한다

Java는 Python workspace와 `.NET` central package management의 장점을 합쳐야 한다. 구체적으로:
- root `platform` 또는 BOM module이 공통 dependency version을 선언
- 실제 기능은 `core`, `foundry`, `hosting`, `tools`, `orchestrations` 같은 개별 artifact로 분리
- root “all-in-one” aggregator artifact는 optional  
이 구조가 selective install과 centrally managed compatibility를 동시에 준다.  
근거:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/Directory.Packages.props#L3-L6  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/README.md#L17-L37  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L309-L355  

### 결정 2. Package stage는 artifact metadata와 별도 lifecycle registry를 함께 둔다

Java는 `.NET`처럼 build metadata만 두지 말고, Python `PACKAGE_STATUS.md` 같은 lifecycle registry 문서도 같이 유지하는 편이 낫다. artifact POM/Gradle metadata만으로는 “beta vs preview vs deprecated” 같은 운영 정보가 충분히 드러나지 않는다.  
근거:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L5-L58  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Microsoft.Agents.AI.csproj#L3-L6  

### 결정 3. Java dependency management는 BOM + validated bounds를 결합한다

Java는 `.NET`식 중앙 pinning만으로 끝내지 말고, Python식 validated bounds 전략도 가져가는 편이 낫다. 즉:
- BOM은 현재 권장 tested version을 고정
- 라이브러리 POM은 supported lower/upper range를 문서화
- CI는 lower/upper resolution smoke lane을 가진다  
이렇게 해야 consumers의 dependency convergence 문제와 upstream 확장성 둘 다 대응할 수 있다.  
근거:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/Directory.Packages.props#L3-L6  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L420-L431  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L386-L401  

### 결정 4. Binary/API compatibility gate를 릴리스 단계와 연결한다

Java도 `.NET`처럼 stable/released artifact에 대해서는 binary/API compatibility gate를 기본 활성화하고, preview/experimental artifact는 보고 중심 또는 완화된 gate를 둘 수 있다. intentional break는 suppression/waiver artifact로 남겨야 한다.  
근거:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L16-L23  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/CONTRIBUTING.md#L77-L97  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/CompatibilitySuppressions.xml#L1-L120  

### 결정 5. Installability smoke test를 release pipeline에 포함한다

Java release pipeline은 artifact publish 전:
- 예제 consumer project 생성
- BOM import 또는 direct dependency 선언
- 실제 build/resolve 성공 여부 확인  
단계를 포함해야 한다. `.NET`의 install check는 이 전략이 유효함을 보여준다.  
근거:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L130-L164  

### 결정 6. Cross-language compatibility는 import surface와 behavior를 기준으로 본다

Java와 `.NET`/Python 사이의 compatibility 문서는 “같은 version number 체계”보다 다음을 기준으로 해야 한다.
- 공개 API 이름/개념 대응
- stable vs experimental stage 대응
- conformance/behavior test 정렬
- package split/repo split 시 facade 유지 여부  
Python changelog의 re-export 사례는 facade 유지가 핵심임을 보여준다.  
근거:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L10-L11  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/TRANSPARENCY_FAQ.md#L26-L28  

### 결정 7. Snapshot update policy를 changelog-driven으로 운영한다

Java upstream snapshot 문서는 다음 변화가 발생할 때 재생성해야 한다.
1. package stage promotion/demotion
2. dependency compatibility adjustment
3. extension repo split 또는 re-export 변경
4. main installation surface 변경
5. central version/BOM 변경  
트리거 판정의 1차 근거는 changelog, package status, central metadata 파일이어야 한다.  
근거:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L10-L11  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L25-L29  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L66-L74  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L109-L122  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L13-L58  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L3-L23  

## 구체적인 acceptance scenarios

### Scenario 1. `.NET` released package는 suffix 없는 stable version을 가져야 한다

- Given: csproj에 `IsReleased=true`
- When: package version이 계산된다
- Then: `PackageVersion`은 suffix 없는 `VersionPrefix` 여야 한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L7-L10  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Microsoft.Agents.AI.csproj#L3-L6  

### Scenario 2. `.NET` preview package는 preview version path를 사용한다

- Given: csproj에 `VersionSuffix=preview` 이고 `IsReleased`가 아니다
- When: package version이 계산된다
- Then: stable version이 아니라 preview suffix를 포함한 package version이어야 한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L7-L10  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/Microsoft.Agents.AI.Hosting.csproj#L3-L5  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.A2A/Microsoft.Agents.AI.A2A.csproj#L3-L6  

### Scenario 3. `.NET` released package는 package validation baseline을 적용받아야 한다

- Given: released `.NET` package
- When: release build 또는 pack을 수행한다
- Then: package validation이 baseline version against current API surface로 동작해야 한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L16-L20  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/CONTRIBUTING.md#L77-L88  

### Scenario 4. `.NET` intentional breaking change는 suppression artifact를 남겨야 한다

- Given: approved breaking change
- When: package validation이 CP error를 낸다
- Then: suppression file을 생성·커밋하고 justification과 함께 관리해야 한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/CONTRIBUTING.md#L90-L97  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/CompatibilitySuppressions.xml#L1-L120  

### Scenario 5. Python selective install은 stable package와 preview package를 구분해야 한다

- Given: `agent-framework-foundry` 와 `agent-framework-copilotstudio`
- When: 사용자가 selective install 을 수행한다
- Then: released package는 `--pre` 없이 설치 가능하고, preview connector는 `--pre`가 필요해야 한다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/README.md#L17-L37

### Scenario 6. Python lifecycle registry는 package stage를 명시해야 한다

- Given: `PACKAGE_STATUS.md`
- When: downstream snapshot 문서가 package maturity를 읽는다
- Then: package별 `alpha`/`beta`/`rc`/`released`/`deprecated` 상태를 직접 확인할 수 있어야 한다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L13-L58

### Scenario 7. Python dependency range 변경은 validated bounds task를 거쳐야 한다

- Given: 외부 dependency bound를 넓히거나 새 dependency를 추가한다
- When: maintainer가 변경을 준비한다
- Then: workspace-wide 또는 package-scoped bound validation task를 먼저 실행해야 한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L420-L431  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L386-L401  

### Scenario 8. Python release workflow는 tag에서 package directory를 해석해야 한다

- Given: published release tag `python-<package>-<version>`
- When: Python release workflow가 실행된다
- Then: tag에서 package를 추출하고 `packages/<package>` 존재를 검증한 뒤 build artifact를 업로드해야 한다.  
출처: https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-release.yml#L36-L63

### Scenario 9. Snapshot update는 promotion/compatibility 신호가 있을 때 재실행해야 한다

- Given: changelog에 stable promotion, beta promotion, compatibility adjustment, repo split이 기록된다
- When: downstream snapshot 문서를 유지한다
- Then: packaging/compatibility 문서는 재생성 또는 최소 재검토되어야 한다.  
출처:  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L10-L11  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L25-L29  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L66-L74  
- https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L109-L122  

## Source inventory

### Repository layout and metadata

- `.github/workflows/dotnet-build-and-test.yml`  
  - multi-language sparse checkout, `.NET` build/test flow, install check, coverage threshold  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L106-L114  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L130-L164  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/dotnet-build-and-test.yml#L317-L336  

- `dotnet/nuget/nuget-package.props`  
  - version prefix/suffix logic, validation baseline, audit, URLs, SourceLink, symbols  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/nuget/nuget-package.props#L3-L60

- `dotnet/Directory.Packages.props`  
  - central package version management and transitive pinning  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/Directory.Packages.props#L3-L24  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/Directory.Packages.props#L26-L80  

- `dotnet/eng/MSBuild/LegacySupport.props`  
  - compatibility shims for older TFMs  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/eng/MSBuild/LegacySupport.props#L2-L32

- `dotnet/src/Microsoft.Agents.AI/Microsoft.Agents.AI.csproj`  
  - released stage, eval limited to net8.0+  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Microsoft.Agents.AI.csproj#L3-L6  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/Microsoft.Agents.AI.csproj#L36-L42  

- `dotnet/src/Microsoft.Agents.AI.Purview/Microsoft.Agents.AI.Purview.csproj`  
  - RC stage example  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Purview/Microsoft.Agents.AI.Purview.csproj#L3-L5  

- `dotnet/src/Microsoft.Agents.AI.Hosting/Microsoft.Agents.AI.Hosting.csproj`  
  - preview stage example  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.Hosting/Microsoft.Agents.AI.Hosting.csproj#L3-L5  

- `dotnet/src/Microsoft.Agents.AI.A2A/Microsoft.Agents.AI.A2A.csproj`  
  - preview stage example  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI.A2A/Microsoft.Agents.AI.A2A.csproj#L3-L6  

- `dotnet/src/Microsoft.Agents.AI/CompatibilitySuppressions.xml`  
  - approved compatibility suppressions  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/dotnet/src/Microsoft.Agents.AI/CompatibilitySuppressions.xml#L1-L120

- `CONTRIBUTING.md`  
  - automated API compatibility validation policy  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/CONTRIBUTING.md#L71-L97

### Python package/lifecycle/version sources

- `python/pyproject.toml`  
  - project metadata, stable classifiers, workspace, sources, security override dependencies  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L1-L27  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L60-L77  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/pyproject.toml#L76-L112  

- `python/README.md`  
  - development vs selective install, released vs preview `--pre` guidance  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/README.md#L7-L37

- `python/PACKAGE_STATUS.md`  
  - lifecycle buckets, package states, feature-level stage table  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L5-L58  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/PACKAGE_STATUS.md#L60-L153  

- `python/CODING_STANDARD.md`  
  - monorepo/package structure, versioning and dependency bounds rules  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L309-L355  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CODING_STANDARD.md#L413-L431  

- `python/DEV_SETUP.md`  
  - coverage enforcement, dependency bound validation tasks  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L176-L190  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/DEV_SETUP.md#L386-L401  

- `python/CHANGELOG.md`  
  - extension repo split, validation tooling changes, stage promotions, compatibility adjustments  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L10-L11  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L25-L29  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L66-L74  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/python/CHANGELOG.md#L109-L122  

### Release workflows and cross-language references

- `.github/workflows/python-release.yml`  
  - release tag parsing and package asset build  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/.github/workflows/python-release.yml#L14-L63  

- `TRANSPARENCY_FAQ.md`  
  - engineering/integration/conformance testing across `.NET` and Python  
  - https://github.com/microsoft/agent-framework/blob/d0a4165f170193ba1d026a259af40d35bb7eaefe/TRANSPARENCY_FAQ.md#L26-L28