# Step 01 — Git repo + Maven multi-module parent + common-lib skeleton

> **Sprint 1 — Foundation & Identity** · **Flow:** login → JWT · **Infra que sobe:** none · **Diagram:** ARCHITECTURE §6.1

## Objective
A buildable Maven multi-module repository: parent POM, empty `services/common-lib` module, `.gitignore`, `.editorconfig`. `mvn clean package` succeeds.

## Why / what you'll learn
Multi-module Maven is how a polyrepo-like microservice layout lives in one repo: the **parent POM** centralizes versions (Java 21, Spring Boot BOM, AWS SDK BOM, Testcontainers BOM) via `dependencyManagement`, so no module ever declares a version by hand — this is the single most effective defense against dependency drift between services. `common-lib` exists from day one so shared code has an obvious home and doesn't get copy-pasted later. In the vertical plan, this is the only "layer" step: everything after it is a flow.

## Prerequisites
None (first step).

## Tasks
1. add `.gitignore` (Maven `target/`, IDE files, `.env`, LocalStack volume dirs) and `.editorconfig` (4-space Java, LF, UTF-8).
2. Root `pom.xml`: `packaging=pom`; properties `java.version=21`, `maven.compiler.release=21`; `dependencyManagement` importing Spring Boot BOM (3.3.x), AWS SDK v2 BOM, Testcontainers BOM; `modules` listing `services/common-lib`; plugins: surefire (unit `*Test`), failsafe (integration `*IT`), spring-boot-maven-plugin managed.
3. `services/common-lib/pom.xml`: jar module, parent reference; dependencies: spring-boot-starter (minimal), slf4j.
4. One placeholder class `com.platinumcoin.pix.common.Placeholder` + one trivial test so the test plugins are exercised.
5. First commit: `chore: scaffold maven multi-module repository (step 01)`.

## Tests (TDD)
- `PlaceholderTest` — asserts true; proves surefire wiring, Java 21 toolchain, and BOM resolution end to end.

## Verify locally
```bash
mvn -q clean package && echo BUILD OK
mvn -q dependency:tree -pl services/common-lib | head   # versions come from BOMs, none hardcoded
```

## Definition of Done
- [ ] `mvn clean package` green from a clean checkout
- [ ] No dependency in any module declares an explicit version (BOM-managed)
- [ ] `.gitignore` keeps `target/` and env files out of git

## CHANGELOG entry
`### Added` → `Maven multi-module scaffold with parent POM (Java 21, Spring Boot & AWS BOMs) and common-lib module (step 01)`
