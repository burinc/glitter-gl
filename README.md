# glitter-gl

An OpenGL geometry, matrix, and shader library for
[glitter](https://github.com/burinc/glitter) — a Replicant-style GTK4
renderer for [Jolt](https://github.com/jolt-lang/jolt). Ported from
[glimmer-gl](https://github.com/jolt-lang/glimmer-gl), which does the same
for [glimmer](https://github.com/jolt-lang/glimmer) (glitter's
Reagent-style sibling). It does two things:

- **Composable geometry**, ported (via glimmer-gl) from
  [thi.ng/geom](https://thi.ng/geom): build 3D solids as plain Clojure
  data, transform and combine them, and tessellate to a GL-ready vertex
  buffer. Vectors, column-major 4×4 matrices, a mesh model, and primitive
  constructors.
- **Shaders as data** (`glitter-gl.shader`), ported (via glimmer-gl) from
  thi.ng/geom's shader-spec model: declare a shader's interface (uniforms,
  attributes, varyings, version) as maps and compose its stages from
  reusable GLSL snippets; the declarations are generated and only emitted
  as a GLSL string when you compile.
- **A GL widget for glitter**: requiring `glitter-gl.gtk` registers a
  `:gl-area` (a GtkGLArea drawing surface) into glitter's widget registry,
  so a GL pane lives in the same reconciled hiccup tree as the rest of
  your UI.

`glitter-gl.vector` gives unboxed 3D vectors, `glitter-gl.matrix` gives
column-major 4×4 matrices (the layout `glUniformMatrix4fv` expects) with
inlined flonum arithmetic, `glitter-gl.gl` binds the slice of OpenGL you
need to compile shaders and fill buffers/VAOs/uniforms, and
`glitter-gl.mesh` / `glitter-gl.primitives` are the thi.ng/geom-style
composition layer.

## Quick start

```sh
jolt -M:plasma         # rotating cube/sphere/tetra + composable plasma/stripes shader
jolt -M:check           # headless sanity check: shader compiles, geometry buffers valid
jolt -M:test             # unit suite
jolt -M:gl-area-smoke     # live-GTK smoke: :gl-area construct/realize/render/resize
```

**In CI, invoke the alias form** (`jolt -M:test`, not `jolt test`) — same
exit-code caveat as glitter itself (see glitter's own README).

### Or via `bb`

```
bb info      # start here — grouped task list
bb test      # jolt -M:test
bb plasma    # interactive demo
bb check     # headless sanity check
bb smokes    # live-GTK smoke, CI-safe
bb hooks:install / :install:full / :uninstall  # git pre-commit hook: fast | +tests | remove
```

`bb hooks:install` sets up a fast pre-commit hook (lint errors + format +
ns cleanliness) that gates every commit on staying `bb lsp:format-check`-
clean — the whole codebase is formatted uniformly, including the 22 files
ported verbatim from glimmer-gl. See [`CONTRIBUTING.md`](CONTRIBUTING.md)'s
invariant #1 for why a project-wide `clojure-lsp format` pass doesn't conflict
with the "don't improve ported files" porting discipline — it mirrors
glitter's identical resolution of the same tension for its own
Replicant-ported files.

## Architecture

```
glitter-gl.vector/.matrix/.quaternion/.aabb/.rect/.circle/.line/.plane/
.triangle/.sphere/.polygon/.bezier/.intersect  — pure geometry/math,
                                                  no glitter dependency
    │
    ▼
glitter-gl.mesh/.glmesh/.primitives/.polyhedra   — mesh model & composition
    │
    ▼
glitter-gl.shader/.gl/.offscreen/.renderer       — GL plumbing
    │
    ▼
glitter-gl.gtk (:gl-area, wired via glitter.widget's
  :apply path — see below)                        glitter-gl.scene/.app
                                                    (declarative scene graph,
                                                     state-atom driven)
```

- `glitter-gl.gtk` registers `:gl-area` into glitter's widget registry.
  The imperative realize/render/resize/tick/motion/key/button signals are
  wired from the widget spec's `:apply` closure (called once per prop key
  at construction, and again on every re-render), guarded so each event
  only ever connects once per widget. This is a real, live-found
  correction to the original design, which called for wiring them once at
  construction via glitter.widget's `:connect` hook — `:connect` never
  actually sees a hiccup element's real props under glitter's reconcile
  flow, so it silently never fired. See
  [`docs/guide/gl-area-widget-layer.md`](docs/guide/gl-area-widget-layer.md)
  for the full story.
- `glitter-gl.scene`/`glitter-gl.app` do NOT track reactive-cell
  dependencies the way glimmer-gl's originals do — glitter's own
  state-atom watcher already recomputes the whole view on every change, so
  a GL scene built the same way (a pure function of `state`) needs no
  separate tracking layer.

## Documentation

- **[`docs/guide/index.md`](docs/guide/index.md)** — the full guide.
- **[`CONTRIBUTING.md`](CONTRIBUTING.md)** — how to build, test, and submit
  changes, and the ten invariants this project does not regress.
- **[`NOTICE.md`](NOTICE.md)** — file-by-file attribution for the ported code.

## Status

Ported from glimmer-gl (2026-08-06 arc) — see `NOTICE.md`'s porting ledger
for the full verbatim/adapted/new breakdown, including a real live-found
correction to `:gl-area`'s original wiring design (see
[`docs/guide/gl-area-widget-layer.md`](docs/guide/gl-area-widget-layer.md)).
Known gaps carried forward from the design spec: `glitter-gl.app`'s
scene-graph/shadow-mapping renderer path (`reactive-area`) is ported and
unit-tested but has no live demo exercising it end to end — the shipped
`plasma` demo wires `:gl-area` directly instead, the same way glimmer-gl's
own upstream demo does.
