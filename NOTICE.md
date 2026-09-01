glitter-gl
==========

An OpenGL geometry, matrix, and shader library for the [glitter](https://github.com/jlt-commons/glitter)
GTK4 renderer for Jolt, ported from
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

Verbatim port (namespace rename only; see the plan's "Standard Verbatim
Port Procedure"): `vector.clj`, `vec2.clj`, `matrix.clj`, `quaternion.clj`,
`aabb.clj`, `rect.clj`, `circle.clj`, `line.clj`, `plane.clj`,
`triangle.clj`, `sphere.clj`, `polygon.clj`, `bezier.clj`, `intersect.clj`,
`mesh.clj`, `glmesh.clj`, `primitives.clj`, `polyhedra.clj`, `shader.clj`,
`gl.clj`, `offscreen.clj`, `renderer.clj` (+ every corresponding test file,
**except `renderer.clj`'s**: its SOURCE is verbatim but its test is new,
added in this port, since glimmer-gl ships none; see below).
`gl.clj` landed before `shader.clj`, the reverse of the plan's original
task order: `shader.clj` itself requires `glitter-gl.gl`, so the plan's
numbering was swapped to match the real dependency direction; the ported
code itself is unaffected.

`polygon/cog` (ported from thi.ng/geom's `thi.ng.geom.polygon/cog`) was added
to the already-ported `polygon.clj` in a later 2026-08-27 arc.

Adapted for glitter's Replicant-style model (see the port design spec in
the private planning store for the full rationale, and
`docs/guide/gl-area-widget-layer.md` for a correction to one part of it):
`gtk.clj` (`:gl-area`'s realize/render/resize/tick/
motion/key/button handlers wire from the widget spec's `:apply` closure,
guarded idempotent per `[area event]` via a `wired` atom, **not** via
glitter.widget's `:connect` hook, which the design spec originally called
for and which turns out to never see a hiccup element's real props under
glitter's actual reconcile flow, so it silently never fired; `:scale` is
dropped entirely, since glitter already ships its own, richer native `:scale`),
`scene.clj` (`plan` drops the `glimmer.ratom/reaction` wrapper, becomes a
plain function of `state`), `app.clj` (`reactive-area` takes glitter's
state atom directly instead of reactive cells).

Demo (`examples/glitter_gl/`), ported from
the `glimmer-gl-app` jolt example's `gl_demo/*.clj`: `plasma_shader.clj`
and `check.clj` near-verbatim; `plasma.clj` (from `gl_demo/core.clj`)
rewrites its reactive-cell control panel as glitter's state atom + action
dispatch, keeping the GL render-loop plumbing's direct state-atom
read/write; its `shape-button` helper is called directly,
`(shape-button ...)`, not as a bracket-vector hiccup tag
`[shape-button ...]`; glitter has no function-as-hiccup-tag convention
(see `CONTRIBUTING.md`'s Invariants).

Tooling config adapted from glitter (same author/org, no license file, no
attribution obligation, listed here for provenance only): `.clj-kondo/
hooks/jolt_ffi.clj`, `.clj-kondo/config.edn`, `.lsp/config.edn`, diffing
cleanly against glitter's own copies modulo project-name renames.
glitter's own `NOTICE.md` in turn credits b12n-rljlt for the original
`jolt_ffi.clj` hook. `scripts/check_positional_args.clj` is adapted from
glitter's own copy with one real logic change on top of the rename; see
the script's own header comment (glitter-gl's type-hint-heavy geometry/
matrix layer needed the arg-counter fixed, not just renamed). `bb.edn`'s
`check:positional-args`/`:strict` and `nrepl` tasks are the same
rename-only adaptation as the `.clj-kondo`/`.lsp` config above, with task
bodies unchanged beyond path/alias substitution. `hooks:install`/
`:install:full`/`:uninstall` carry one further deviation on top of
that: they resolve the hook path via `git rev-parse --git-path
hooks/pre-commit` rather than glitter's hardcoded
`.git/hooks/pre-commit` literal: the hardcoded relative path breaks
when run from inside a linked git worktree (where `.git` is a gitlink
file, not a directory); the git-path form resolves correctly in both a
normal checkout and any worktree, since hooks are shared, not
per-worktree, in git.

New, not present in glimmer-gl: `examples/glitter_gl/gl_area_smoke.clj`
(live-GTK smoke), `test/glitter_gl/renderer_test.clj` (glimmer-gl ships
no test for `renderer.clj`: its source above is verbatim, its test is
not) and `test/glitter_gl/app_test.clj` (glimmer-gl ships no test for
`app.clj` either).

`glitter-gl.glmesh-test` briefly failed to `require` partway through this
arc: `glmesh.clj`'s real transitive dependencies (`gl.clj`, `shader.clj`,
`primitives.clj`, `scene.clj`) hadn't landed yet at the point `glmesh.clj`
itself was ported, and a namespace that fails to require contributes zero
tests rather than a build failure, which is easy to mistake for "nothing
to test" rather than "broken." Closed once the last of those four
dependencies landed; `glmesh-test` now requires and passes cleanly.

## Three further examples: reimplementations, not ports (2026-08-26 arc)

`examples/glitter_gl/ripple.clj`, `orbit.clj`, and `knot.clj` are a
different kind of source entry than anything above, and are recorded
here separately so the distinction doesn't blur into the porting ledger.
The 22 files above are namespace-rename ports: the Standard Verbatim
Port Procedure makes "only the namespace changed" a checkable claim
about a specific commit. These three are not that. Two of them start
from a named [thi.ng/geom](https://thi.ng/geom) example, but only the
*idea* and the geometry setup transfer; the windowing, event loop, and
every GL call are original to this project, written against
`glitter-gl.gtk`'s `:gl-area` and `glitter-gl.gl` rather than JOGL or
WebGL. Calling that a port, verbatim or adapted, would misrepresent what
actually crossed over and understate what didn't.

- **`ripple.clj`**: idea from [thi.ng/geom](https://github.com/thi-ng/geom)'s `examples/jogl/fullscreen_shader.clj`
  (a full-screen fragment shader over a single quad, no mesh, no
  lighting, no camera). The JOGL windowing and GL setup do not transfer;
  only the "one quad, all the interest in the fragment shader" shape
  does. The shader itself, composed through `glitter-gl.shader/merge-specs`
  from a ripple module and a color module, is original to this file.
- **`knot.clj`**: idea from [thi.ng/geom](https://github.com/thi-ng/geom)'s `examples/gl/torus_knot.cljs`
  (thi.ng/geom's `cinquefoil` curve swept into a tube via
  `ptf/sweep-mesh`, a parallel-transport-frame construction). The
  ClojureScript/WebGL plumbing and the parallel-transport-frame
  machinery do not transfer; only the parametric idea, a torus knot
  swept as a tube, does. `torus-knot-faces`' own frame construction is
  original to this file, built differently from `sweep-mesh`'s (see the
  file's own docstring for the difference).
- **`orbit.clj`**: no direct geom-example model. It's original to this
  project: a scene built entirely from `primitives`/`polyhedra`
  constructors this library already ships, mounted through
  `glitter-gl.app/reactive-area` specifically to exercise that path live
  for the first time (see `docs/guide/limitations.md`).

## Three more examples: reimplementations, not ports (2026-08-27 arc)

`examples/glitter_gl/gears.clj`, `textured.clj`, and `picking.clj` are the
same kind of source entry as the three above, for the same reason: each
starts from a named [thi.ng/geom](https://thi.ng/geom) example, but only
the *idea* transfers, not the windowing, event loop, or GL calls, which
are original to this project against `glitter-gl.gtk`'s `:gl-area` rather
than ClojureScript/WebGL.

- **`gears.clj`**: idea from [thi.ng/geom](https://github.com/thi-ng/geom)'s `examples/gl/gears2d.cljs`
  (three counter-rotating cog outlines in 2D). The ClojureScript/WebGL
  windowing does not transfer; only the idea, three counter-rotating
  cogs, does. `polygon/cog` itself is a port, already recorded above;
  what's original to this file is feeding its tessellated output into a
  flat GL buffer, the camera-less orthographic scene, and the direct
  `:gl-area` wiring.
- **`textured.clj`**: idea from [thi.ng/geom](https://github.com/thi-ng/geom)'s `examples/gl/textured_cube.cljs`
  (a rotating cube sampling a texture). The WebGL buffer/camera/
  texture-load plumbing does not transfer; only the idea, a textured,
  rotating cube, does. The procedural checkerboard generation (no image
  asset), the per-corner UV buffer construction, and the direct
  `:gl-area`/FFI texture upload are all original to this file.
- **`picking.clj`**: idea from [thi.ng/geom](https://github.com/thi-ng/geom)'s `examples/gl/picking.cljs`
  (a scene picked by unprojecting the pointer into a world-space ray).
  The ClojureScript/WebGL windowing and event plumbing do not transfer;
  only the idea, a ground and a wall plane, ray-cast to find and mark the
  pointer's hit, does. The `pointer-ray` implementation and the direct
  `:gl-area` wiring are original to this file. `unproject` is not: it
  re-derives the divide-by-w half of `unproject-point`'s 6-arity body from
  thi.ng/geom's `src/thi/ng/geom/matrix.cljc`. glitter-gl's own port of
  that file, `matrix.clj`, never carried `unproject-point` over, so this
  file supplies its own rather than reaching for one that doesn't exist;
  see `docs/guide/gl-area-widget-layer.md` for a widget-layer correction
  this example's development also found, unrelated to the porting
  question.

## `glitter-gl.ffi-compat`: new, no upstream (2026-09-01)

`src/glitter_gl/ffi_compat.clj` and `test/glitter_gl/ffi_compat_test.clj`
are original to this project. glimmer-gl has no equivalent, and neither
does thi.ng/geom: the file exists only because of a runtime change in
jolt itself.

jolt 0.8.0 ([jolt-lang/jolt#802](https://github.com/jolt-lang/jolt/pull/802))
swaps `jolt.ffi/write`'s last two arguments, from `(p type offset value)`
to `(p type value offset)`. An offset and a value are both plain integers,
so a call written for one order does not fail on the other. It writes to
the wrong address and reports nothing. `ffi-compat` probes the order once
at load and binds `write!` to whichever spelling the running jolt wants,
which is what lets the 10 migrated call sites in `gl.clj`, `offscreen.clj`
and `examples/glitter_gl/textured.clj` read value-before-offset while
still running on a released jolt that predates the change.

The call-site migration itself came from
[jlt-commons/glitter-gl#8](https://github.com/jlt-commons/glitter-gl/pull/8),
contributed by [yogthos](https://github.com/yogthos) as part of a
fleet-wide migration across the jolt repositories; that commit is
preserved in this repo's history under its own authorship. The compat
namespace was added on top here, so this repo's shape deliberately differs
from its siblings, which take the argument swap together with a
`:jolt/min-version "0.8.0"` floor.

Those 10 call sites sit inside three files the porting ledger above lists
as verbatim ports. Changing them is a real behavioral edit rather than a
formatting pass, so it is recorded here rather than folded silently into
the verbatim-port claim, per Invariant #1's requirement that any such
change land in its own reviewed commit.

Delete the namespace when `deps.edn` declares `:jolt/min-version "0.8.0"`
or higher. At that point `write!` is exactly `ffi/write`, and removing it
is a rename plus a `git rm`.
