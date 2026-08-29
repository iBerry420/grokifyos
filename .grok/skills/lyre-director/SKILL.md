---
name: lyre-director
description: >
  Direct LYRE boards via the user-scoped MCP connector (create project, folders,
  Imagine, place, stitch/pop). Local-bot counterpart of lyre_instructions.
  Use whenever the operator asks to direct a LYRE board, generate scenes or
  stills/video, place leftover clips, or stitch/pop. /lyre-director
---

# LYRE Director

You have the **same named ops** as MCP `lyre_instructions`. Do not invent board JSON.

Operator copies `https://grokifyos.grokpot.io/mcp/lyre_mcp_<hex>` from **Apps → LYRE → project picker** (token hashed on disk) and pastes it into **Grok Bot → Add connector**. Call `lyre_instructions` first if the MCP session is cold. **Never print the token.** If these tools are not in this session, tell the operator to add the connector — do not call `/api/lyre.php` `save_board`.

## Pipeline

1. `lyre_create` a new project; write a short bible into `brainstorm` + scenes (`lyre_scene`). Odysseus is listed; do **not** open it unless the operator names it — and even then MCP will refuse mutations.
2. Generate character sheets (angles, attire) into `Characters/{name}` and `Characters/{name}/Attire/…` (`lyre_folder` + `lyre_generate_still`). Use those stills as refs. Always pass `board_id`.
3. Generate environments into `Environments/{place}`.
4. Per scene: compose a still from character + environment refs, `lyre_edit_still` until it holds, then `lyre_generate_video` from that still. Poll `lyre_imagine_status` until `src` exists, then attach (POST / `attach=true` on the tool — GET status does not attach).
5. `lyre_place` clips on the leftover track (after the movie prefix). `lyre_stitch` in order (server drops the last encoded frame so the cut does not flash). `lyre_pop` is the only un-stitch.
6. Tell the operator to keep the phone on this project; the editor polls `updated_at`.

## Rules

- Always pass `board_id` (or `project_id`) on mutating tools. Concurrent bots cannot share a default. After `lyre_create`, pass the returned ids on every mutating call. `lyre_open` is a convenience default for **reads** only (`lyre_snapshot` / `lyre_activity` / `lyre_projects`).
- Odysseus is listed, **not** the default, **not** deletable. MCP mutations (and MCP `open` persist) hard-deny with `odysseus_protected`.
- Never invent board JSON. Never ask for `save_board`.
- A video clip is a **scene frame + leftover-track clip** (`linkedFrameId`). Director ops dual-write. You cannot attach “only on the track”.
- Stitched members are locked. Do not trim/move/delete/edit them. `lyre_pop` is the only un-stitch.
- GET `imagine_status` does not attach. Attach with `attach=true` on `lyre_imagine_status` / POST `imagine_attach`. 409 `movie_locked` if the picture is stitched.
- New-project media lives under `boards/{boardId}/stills|videos|audio/…`. Odysseus still uses root `stills/` / `videos/`.
- Stitch drops the last **encoded** frame of the compiled movie. Keep origs. Stitch writes `boards/{id}/movie.mp4` and snapshots `movie.g{n}.mp4`.
- Prefer `lyre_snapshot` over guessing ids.
- Max 4 image refs, 3 voices (`eve,ara,leo,rex,sal,carina,helix,orion,luna,iris,sirius,atlas`).

## Tools

Reads: `lyre_instructions` · `lyre_projects` · `lyre_snapshot` · `lyre_open` (read default only; Odysseus refused) · `lyre_imagine_status` (no attach) · `lyre_activity` (no `text`)

Mutations (always pass `board_id`/`project_id`; Odysseus → `odysseus_protected`): `lyre_create` (no id; allocates) · `lyre_folder` · `lyre_scene` · `lyre_place` · `lyre_trim` · `lyre_move` · `lyre_delete` · `lyre_generate_still` · `lyre_edit_still` · `lyre_generate_video` · `lyre_edit_video` · `lyre_imagine_status` (`attach=true`) · `lyre_stitch` · `lyre_pop` · `lyre_activity` (`text` set)
