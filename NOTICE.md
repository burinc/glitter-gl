glitter-gl
==========

An OpenGL geometry, matrix, and shader library for the [glitter](../glitter)
GTK4 renderer for Jolt — ported from
[glimmer-gl](https://github.com/jolt-lang/glimmer-gl), which was itself
ported from thi.ng/geom.

This product includes software developed as part of thi.ng/geom
(https://thi.ng/geom) by Karsten Schmidt, licensed under the Apache License,
Version 2.0. Derived from geom (via glimmer-gl): the matrix arithmetic,
cofactor inversion, and constructor formulas (thi.ng.geom.matrix); the mesh
model, tessellation, and primitive vertex/face definitions
(thi.ng.geom.{basicmesh,utils,cuboid,tetrahedron,sphere,plane}); and the
shader-spec model (thi.ng.geom.gl.shaders).

Copyright 2026 Burin Choomnuan. glitter-gl is licensed under the Apache
License, Version 2.0 (see ./LICENSE), which is compatible with jolt's
Eclipse Public License 1.0.

## Porting ledger: glimmer-gl → glitter-gl (2026-08-06 arc)

Verbatim port (namespace rename only — see the plan's "Standard Verbatim
Port Procedure"): `vector.clj`, `vec2.clj`, `matrix.clj`, `quaternion.clj`,
`aabb.clj`, `rect.clj`, `circle.clj`, `line.clj`, `plane.clj`,
`triangle.clj`, `sphere.clj`, `polygon.clj`, `bezier.clj`, `intersect.clj`,
`mesh.clj`, `glmesh.clj`, `primitives.clj`, `polyhedra.clj`, `shader.clj`,
`gl.clj`, `offscreen.clj`, `renderer.clj` (+ every corresponding test file,
**except `renderer.clj`'s** — its SOURCE is verbatim but its test is new,
added in this port, since glimmer-gl ships none; see below).
`gl.clj` landed before `shader.clj`, the reverse of the plan's original
task order — `shader.clj` itself requires `glitter-gl.gl`, so the plan's
numbering was swapped to match the real dependency direction; the ported
code itself is unaffected.

Adapted for glitter's Replicant-style model (see the port design spec in
the private planning store — `~/dev/b12n-sp-docs/glitter-gl/specs/` — for
the full rationale, and `docs/guide/gl-area-widget-layer.md` for a
correction to one part of it): `gtk.clj` (`:gl-area`'s realize/render/resize/tick/
motion/key/button handlers wire from the widget spec's `:apply` closure,
guarded idempotent per `[area event]` via a `wired` atom — **not** via
glitter.widget's `:connect` hook, which the design spec originally called
for and which turns out to never see a hiccup element's real props under
glitter's actual reconcile flow, so it silently never fired; `:scale` is
dropped entirely — glitter already ships its own, richer native `:scale`),
`scene.clj` (`plan` drops the `glimmer.ratom/reaction` wrapper, becomes a
plain function of `state`), `app.clj` (`reactive-area` takes glitter's
state atom directly instead of reactive cells).

Demo (`examples/glitter_gl/`), ported from
`~/dev/jolt-examples/glimmer-gl-app`'s `gl_demo/*.clj`: `plasma_shader.clj`
and `check.clj` near-verbatim; `plasma.clj` (from `gl_demo/core.clj`)
rewrites its reactive-cell control panel as glitter's state atom + action
dispatch, keeping the GL render-loop plumbing's direct state-atom
read/write — its `shape-button` helper is called directly,
`(shape-button ...)`, not as a bracket-vector hiccup tag
`[shape-button ...]`; glitter has no function-as-hiccup-tag convention
(see `AGENTS.md`'s Conventions & gotchas).

Tooling config adapted from glitter (same author/org, no license file, no
attribution obligation, listed here for provenance only): `.clj-kondo/
hooks/jolt_ffi.clj`, `.clj-kondo/config.edn`, `.lsp/config.edn` — diffing
cleanly against glitter's own copies modulo project-name renames.
glitter's own `NOTICE.md` in turn credits b12n-rljlt for the original
`jolt_ffi.clj` hook. `scripts/check_positional_args.clj` is adapted from
glitter's own copy with one real logic change on top of the rename — see
the script's own header comment (glitter-gl's type-hint-heavy geometry/
matrix layer needed the arg-counter fixed, not just renamed). `bb.edn`'s
`check:positional-args`/`:strict` and `nrepl` tasks are the same
rename-only adaptation as the `.clj-kondo`/`.lsp` config above — task
bodies unchanged beyond path/alias substitution. `hooks:install`/
`:install:full`/`:uninstall` carry one further deviation on top of
that: they resolve the hook path via `git rev-parse --git-path
hooks/pre-commit` rather than glitter's hardcoded
`.git/hooks/pre-commit` literal — the hardcoded relative path breaks
when run from inside a linked git worktree (where `.git` is a gitlink
file, not a directory); the git-path form resolves correctly in both a
normal checkout and any worktree, since hooks are shared, not
per-worktree, in git.

New, not present in glimmer-gl: `examples/glitter_gl/gl_area_smoke.clj`
(live-GTK smoke), `test/glitter_gl/renderer_test.clj` (glimmer-gl ships
no test for `renderer.clj` — its source above is verbatim, its test is
not) and `test/glitter_gl/app_test.clj` (glimmer-gl ships no test for
`app.clj` either).

`glitter-gl.glmesh-test` briefly failed to `require` partway through this
arc: `glmesh.clj`'s real transitive dependencies (`gl.clj`, `shader.clj`,
`primitives.clj`, `scene.clj`) hadn't landed yet at the point `glmesh.clj`
itself was ported, and a namespace that fails to require contributes zero
tests rather than a build failure — easy to mistake for "nothing to test"
rather than "broken." Closed once the last of those four dependencies
landed; `glmesh-test` now requires and passes cleanly.
