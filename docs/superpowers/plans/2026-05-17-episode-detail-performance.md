# Episode Detail Performance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Speed up episode production detail page rendering by optimizing the shot-list endpoint and backing indexes.

**Architecture:** Keep the page server-rendered, keep existing endpoint URLs, and replace the broad MyBatis-Plus `SELECT *` shot-list fetch with a dedicated lightweight mapper query. Add an idempotent SQL migration for composite indexes used by the page.

**Tech Stack:** Spring Boot 3, MyBatis-Plus, JUnit 5/Mockito, Django frontend tests, SQL migrations.

---

### Task 1: Lock Down Shot-List Lightweight Query Behavior

**Files:**
- Modify: `backend/src/test/java/com/manga/ai/shot/service/impl/ShotServiceImplTest.java`
- Modify: `backend/src/main/java/com/manga/ai/shot/mapper/ShotMapper.java`
- Modify: `backend/src/main/java/com/manga/ai/shot/service/impl/ShotServiceImpl.java`

- [x] Add a failing test that expects `getShotsByEpisodeId` to call `shotMapper.selectEpisodeDetailList(episodeId)` instead of `shotMapper.selectList(...)`.
- [x] Add the mapper method with explicit selected columns.
- [x] Update `getShotsByEpisodeId` to use the new mapper method.
- [x] Run `mvn -Dtest=ShotServiceImplTest test` and confirm the new test passes.

### Task 2: Remove Large Field Dependency From List Assembly

**Files:**
- Modify: `backend/src/test/java/com/manga/ai/shot/service/impl/ShotServiceImplTest.java`
- Modify: `backend/src/main/java/com/manga/ai/shot/service/impl/ShotServiceImpl.java`

- [x] Add a test proving lightweight shots can still assemble prop display data from `shot_prop` and `prop` rows when `propsJson` is not loaded.
- [x] Remove `propsJson` parsing from the list assembler path or make it unused when relational data is available.
- [x] Add a regression test proving empty `shot_prop` results do not trigger whole-series prop lookups.
- [x] Run `mvn -Dtest=ShotServiceImplTest test`.

### Task 3: Add Database Index Migration

**Files:**
- Create: `backend/src/main/resources/db/migration/V2026.05.17__Optimize_Episode_Detail_Shot_List.sql`
- Modify: `backend/src/test/java/com/manga/ai/shot/service/impl/ShotServiceImplTest.java` or create a focused migration test

- [x] Add a failing test that checks the migration file includes the required composite index names.
- [x] Add the migration with `CREATE INDEX` statements for `shot`, `shot_character`, `shot_prop`, `scene_asset`, `role_asset`, `prop_asset`, and `shot_video_asset`.
- [x] Run the relevant test.

### Task 4: Improve Django Parallel Result Collection

**Files:**
- Modify: `frontend/apps/series/views.py`
- Modify: `frontend/apps/series/tests.py`

- [x] Add a test for `_parallel_backend_gets` proving it can return all named results and uses separate authenticated clients.
- [x] Update `_parallel_backend_gets` to collect futures via `as_completed`.
- [x] Add a regression test that Django collects episode prop references from lightweight `shot.props`.
- [x] Run `python3 manage.py test apps.series.tests`.

### Task 5: Document And Verify

**Files:**
- Create: `docs/changes/2026-05-17-episode-detail-shot-list-performance.md`
- Modify: `docs/changes/README.md`

- [x] Add the change note with background, implementation, affected files, behavior changes, and verification commands.
- [x] Run `mvn -Dtest=ShotServiceImplTest test`.
- [x] Run `mvn -DskipTests compile`.
- [x] Run `python3 manage.py check`.
- [x] Run `python3 manage.py test apps.series.tests`.
