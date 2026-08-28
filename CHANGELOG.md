# Changelog

Notable changes to glitter-gl, newest first. The format follows
[babashka's changelog](https://github.com/babashka/babashka/blob/master/CHANGELOG.md):
one bullet per user-visible change, written as what a reader would notice
rather than what a commit did.

Nothing has been released yet, so there is a single `Unreleased` section. It
covers the project from its first commit (2026-08-06) to now, grouped by
theme rather than dated per change; the whole thing is one continuous first
pass.

## Unreleased

### Geometry and shaders

- **A composable geometry model**, ported (via
  [glimmer-gl](https://github.com/jolt-lang/glimmer-gl)) from
  [thi.ng/geom](https://thi.ng/geom): unboxed 3D vectors, column-major 4×4
  matrices and quaternions, one record type per geometric primitive (AABB,
  rect, circle, line, plane, triangle, sphere, polygon, Bézier/Catmull-Rom
  curve), and ray-intersection tests for picking: all plain functions over
  immutable records, no protocols, no mutation.
- **A mesh model and primitive/polyhedron constructors** (`mesh`, `glmesh`,
  `primitives`, `polyhedra`): build a mesh as a sequence of faces, transform
  it (translate, scale, tessellate, subdivide, compute normals), and
  construct cuboids, spheres, tetrahedra, octahedra, icosahedra, and
  dodecahedra directly.
- **Shaders as data** (`glitter-gl.shader`): a shader's interface
  (uniforms, attributes, varyings, an optional GLSL prelude, `:vs-main`/
  `:fs-main` bodies as data) is a plain map, so composing shaders is just
  map composition. `merge-specs` combines fragments, with later entries
  winning on key conflicts and statement vectors concatenating. GLSL text is
  only generated when you call `sources` or `program`.
- **A worked composition example** (`examples/glitter_gl/plasma_shader.clj`):
  a shared vertex/framing base, a domain-warped plasma module, an animated
  stripes module, and a lighting module merged into one shader. Drop a
  module from the merge, or add a fourth, and the composed shader changes
  shape with no edits to the others.
- **A second worked example with no mesh at all**
  (`examples/glitter_gl/ripple.clj`, `jolt -M:ripple` / `bb ripple`): a
  single full-screen quad textured by a fragment shader composed from a
  drifting-ripple module and a color module. Demonstrates that
  `glitter-gl.shader`'s composition model is useful entirely on its own,
  without the mesh half of the library.
- **A parametric mesh example** (`examples/glitter_gl/knot.clj`,
  `jolt -M:knot` / `bb knot`): a (2,3) trefoil torus knot swept as a
  tube, 2400 quads generated from scratch and handed to `mesh/mesh`.
  The first example to render geometry the library doesn't ship as a
  built-in primitive.
- **`polygon/cog`**, ported from thi.ng/geom's `thi.ng.geom.polygon/cog`:
  builds a toothed 2D polygon outline from a radius and a tooth count.
  Demoed by **`examples/glitter_gl/gears.clj`** (`jolt -M:gears` /
  `bb gears`): three counter-rotating cog outlines, flat-shaded in a
  camera-less 2D scene, `polygon/tessellate`'s first real consumer
  anywhere: `mesh.clj` never calls it and has its own unrelated
  `tessellate-face` over Vec3 meshes, so before this example the only
  caller was `polygon/tessellate`'s own test suite.
- **A texturing example** (`examples/glitter_gl/textured.clj`,
  `jolt -M:textured` / `bb textured`): a rotating cube wearing a
  procedurally generated checkerboard, no image file. The first shader
  spec in the project to declare a `:sampler2D` uniform, and the first
  consumer of `glitter-gl.gl`'s texture FFI outside the renderer's
  internal shadow-map path and the test suite; no library gap needed
  fixing to support it.

### GTK integration

- **A `:gl-area` widget for glitter**: requiring `glitter-gl.gtk` registers
  a `:gl-area` (a `GtkGLArea` drawing surface, with `:on-realize`/
  `:on-render`/`:on-resize`/`:on-tick`/`:on-motion`/`:on-key`/`:on-button`
  handlers) into glitter's widget registry, so a GL pane lives in the same
  reconciled hiccup tree as the rest of a glitter UI.
- **`:gl-area`'s handlers wire from the widget spec's `:apply` closure, not
  `:connect`. A real, live-found correction, not the original design.**
  Every other glitter widget's non-standard signals are documented to wire
  through `:connect`, and that's exactly what the first version of `:gl-area`
  did: it passed every unit test and then never fired a single handler
  against a real window, because `:connect` never actually receives a hiccup
  element's real props under glitter's reconciler. Handlers now wire from
  `:apply` instead (called once per prop key, at construction and on every
  re-render), guarded so each event connects exactly once per widget.
- **`:scale` is deliberately not shipped.** glimmer-gl ships its own
  `:scale` widget; glitter already has a richer, first-party native one
  (min/max/step/value/digits/draw-value, wired through glitter's standard
  signals table), and porting glimmer-gl's would silently shadow it.
- **An interactive demo** (`jolt -M:plasma` / `bb plasma`): a rotating
  cube/sphere/tetrahedron lit by the composed plasma+stripes shader, with a
  glitter-native control panel (shape buttons, five sliders, a
  smooth-shading checkbutton, pause/resume) dispatched through
  `glitter.nexus` like the rest of a glitter UI, while the GL render loop
  itself reads and writes plain atoms directly.
- **The first example to react to pointer input**
  (`examples/glitter_gl/picking.clj`, `jolt -M:picking` / `bb picking`): a
  ground plane and a back wall, with a marker drawn at wherever the
  pointer's world-space ray hits them. `:on-motion` now has a live
  exerciser, wired directly onto `:gl-area`; `:on-button` is not wired by
  this example and remains as unexercised as `:on-key`. Building it found
  a real, previously undocumented widget-layer trap: `"resize"` reports
  device pixels while `:on-motion` reports logical points, so unprojecting
  a pointer with the wrong one is silently wrong by the display's scale
  factor, invisible on a non-Retina display. See
  `docs/guide/gl-area-widget-layer.md`.

### Scene graph

- **`glitter-gl.scene` and `glitter-gl.app`**: a declarative, hiccup-shaped
  scene tree (`[:group {:transform m} & children]`, `[:mesh {...}]`,
  `[:light {...}]`, `[:camera {...}]`, plus first-class `[component
  args...]` invocation) compiles to a render plan that `glitter-gl.renderer`
  turns into GL draw calls, instead of hand-writing GL calls per frame.
- **Redesigned around glitter's state-atom model, not glimmer-gl's reactive
  cells.** glimmer-gl's original wraps the compiled scene plan in a
  dependency-tracked reaction; glitter has no equivalent, since its
  state-atom watcher already recomputes the whole view on every change. The
  ported `plan` is a plain function of `state`, called fresh on every
  render: no reaction, no deref-tracking, no cache.
- **`reactive-area`** builds a ready-made `:gl-area` prop map from a scene
  function and glitter's shared state atom. Its own tick/motion/key/button
  handlers read and write that state atom directly rather than
  dispatching action tuples, which is deliberate: GL-plumbing state can
  change 60 times a second and has no business paying the
  action-tuple/dispatch indirection on every frame. That does not mean
  every `reactive-area` demo escapes a full view recompute, though:
  ticking glitter's *shared* state atom, which `orbit.clj` does below,
  still fires one, because glitter's render watch triggers on any change
  to that atom regardless of how it was written. See
  `docs/guide/scene-and-app.md` for the full trade-off.
- **The first live `reactive-area` demo** (`examples/glitter_gl/orbit.clj`,
  `jolt -M:orbit` / `bb orbit`): six distinct solids (cuboid, sphere,
  tetrahedron, octahedron, icosahedron, dodecahedron) orbiting a lit,
  shadowed ground plane, mounted through `reactive-area` and driven by
  glitter's own shared state atom. Closes the gap
  `docs/guide/limitations.md` previously recorded: `reactive-area` had
  unit coverage but no example that mounted it end to end.

### Tooling

- **A full `bb`/`jolt` task surface**: `bb info` for a grouped cheat-sheet,
  `bb test`/`bb check`/`bb plasma`/`bb gl-area-smoke`, and `bb smokes`
  (runs both live-GTK checks in sequence, stopping at the first failure).
  `jolt -M:test` never actually failed CI before this: its exit path relied
  on an interop form that always resolved to nil, so a failing suite still
  printed its failure count and exited 0. Fixed to call `System/exit`
  directly.
- **Quality gates**: `bb lint`/`lint:strict` (clj-kondo), `bb
  lsp:format`/`format-check` and `lsp:clean-ns`/`clean-ns-check`
  (clojure-lsp), `bb verify` as a pre-commit-shaped bundle, and `bb
  check:positional-args`/`:strict` flagging functions with 3+ positional
  arguments. The codebase is uniformly formatted, including `examples/`
  and the 22 files ported verbatim from glimmer-gl, since a project-wide
  reformat changes whitespace and `:require` ordering only, never logic.
  `examples/` was outside that net for a while: clojure-lsp derives its
  source paths from the `:extra-paths` of aliases it recognizes, and no
  demo alias is named `:dev` or `:test`, so the directory was invisible to
  it and drifted unnoticed. `.lsp/config.edn` now pins `:source-paths`
  explicitly.
- **`.clj-kondo/hooks/jolt_ffi.clj`** rewrites `jolt.ffi/defcfn` into an
  equivalent `defn` so clj-kondo and clojure-lsp can see through the FFI
  macro. Without it, every FFI-bound name in `gl.clj`/`gtk.clj` reports as
  unresolved.
- **`bb hooks:install`/`:install:full`/`:uninstall`** install a git
  pre-commit hook (lint errors plus `clojure-lsp format --dry` and
  `clean-ns --dry` by default, with `:install:full` adding the full unit
  suite), so formatting and namespace hygiene stay enforced on every
  commit, not just when someone remembers to run `bb verify` by hand.
- **`bb nrepl`** starts a Jolt nREPL server for interactive development.
- **GitHub Actions CI** (`.github/workflows/ci.yml`), as two jobs on pull
  requests to `main`, pushes to `main`, and manual dispatch. The required
  `gates` job is headless: unit suite, clj-kondo, clojure-lsp format and
  clean-ns, plus a check that no tracked Markdown file has gained an
  em-dash. It lints with `lint:strict`, so warnings fail the build too.
- **An OpenGL job that really runs OpenGL.** A second, informational job
  runs the suite and `bb smokes` under Xvfb on mesa's llvmpipe, which
  reports GL 4.5, so the render-to-texture round trip and a live
  `[:gl-area]` mount both execute on a runner with no GPU. It guards
  against being decoration: `offscreen-test` passes when it skips, by
  design, so the job greps its own output for the skip banner and fails on
  it rather than trusting a green suite.

### Documentation

- **A nine-page guide** under `docs/guide/`: architecture, the
  geometry/shader library, the `:gl-area` widget layer in full mechanical
  detail, the scene-graph/app layer, porting and attribution, the examples,
  testing and tasks, and every known v1 limitation with the reasoning
  behind leaving it.
- **`CONTRIBUTING.md`** with ten numbered invariants this project does not
  regress, plus build/test setup, the PR gates and which of them CI runs
  for you, the architecture diagram, and how to add a widget.
- **`NOTICE.md`**: a file-by-file attribution ledger tracking which files
  are verbatim ports (22, from glimmer-gl, itself from thi.ng/geom), which
  are adapted for glitter's model, and which are new to this project.
- **Issue and pull-request templates**, and a filled-in `LICENSE` copyright
  line (Apache License 2.0).
