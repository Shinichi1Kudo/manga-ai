# Episode Detail Performance Design

## Goal

Reduce the perceived load time of the episode production detail page without changing the first-screen editing experience.

## Context

The previous optimization moved the page from the full episode detail endpoint to `GET /v1/episodes/{episodeId}?basic=true` and parallelized the Django backend calls. The remaining slow path is `GET /v1/shots/episode/{episodeId}`. Django waits for that response before rendering the HTML, so a slow shot-list response can make the page shell arrive while text fields are still delayed.

## Approach

Keep the current server-rendered first screen and optimize the shot-list path beneath it.

1. Add a dedicated lightweight shot-list mapper query for `getShotsByEpisodeId`.
2. The list query keeps fields needed by the current template: IDs, ordering, names, scene fields, editable description fields, duration/model settings, video URLs/status, generation timing/error, review status, and edit flags.
3. The list query does not return large generation-only fields such as `characters_json`, `reference_prompt`, or `user_prompt`.
4. Replace the `props_json` fallback in the list assembler with `shot_prop` and `prop` table data, so the list path does not need the JSON blob to show prop thumbnails.
5. Add composite database indexes for the list query and its batched asset lookups.
6. Keep Django's parallel backend requests, but collect completed futures as they finish so one slow request does not delay processing of earlier successful responses.

## Testing

- Unit test that `ShotServiceImpl.getShotsByEpisodeId` calls the lightweight mapper method and does not use `selectList` for the primary shot fetch.
- Unit test that lightweight results preserve fields the template needs while omitting large generation-only fields.
- Unit test or file-level test that the migration contains the expected composite indexes.
- Existing frontend template tests remain valid because the server-rendered HTML contract is unchanged.

## Documentation

Record the implementation in `docs/changes/2026-05-17-episode-detail-shot-list-performance.md` and add it to `docs/changes/README.md`.
