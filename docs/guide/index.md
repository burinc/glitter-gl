# glitter-gl Guide

## Why this exists

`glitter-gl` is glimmer-gl's geometry/matrix/shader/GL library, ported
onto [glitter](https://github.com/burinc/glitter) instead of
[glimmer](https://github.com/jolt-lang/glimmer). Most of it (vectors,
matrices, meshes, the shader DSL, raw GL bindings, the renderer) has no
dependency on either UI library at all and ports across unchanged. The
part that does (a `:gl-area` widget so a GL pane can live inside a
glitter hiccup tree) needed real adaptation, and hit a real, live-found
correction along the way. This guide covers that adaptation.

## What glitter-gl is

A `.clj` (Jolt/Chez Scheme host, not JVM) library, split into two halves:

- Pure geometry/matrix/mesh/shader/GL code: usable from any Jolt program
  with an OpenGL context, glitter or not.
- `glitter-gl.gtk`/`.scene`/`.app`, the glitter-specific layer: a
  `:gl-area` widget, a declarative scene graph, and a reactive mount point
  built around glitter's single state atom.

```clojure
(require '[glitter.app :as app]
         '[glitter.core :as core]
         '[glitter.gtk :as gtk]
         '[glitter-gl.gtk])                    ; registers :gl-area

(defn view [_state]
  [:gl-area {:version [3 2] :hexpand true :vexpand true
             :on-realize (fn [area] ...)
             :on-render  (fn [area] ...)
             :on-resize  (fn [area w h] ...)}])

(app/run (fn [window] (gtk/mount! window view (atom {}))))
```

Every `:gl-area` prop key with an `:on-*` handler wires into a raw GTK4
signal (or, for `:on-tick`, a frame-clock callback) the first time it's
seen; `:on-render`'s closure is free to read whatever state it needs on
each call, exactly like any other glitter view function.

## Pages

### Orientation

- [`examples.md`](examples.md): the seven namespaces under
  `examples/glitter_gl/`, split by what each is for: `check.clj` and
  `gl_area_smoke.clj` exist to fail when a regression lands (the only two
  wired into `bb smokes`), `plasma_shader.clj` exists to be composed and
  read (the shader-composition worked example), and `plasma.clj`,
  `ripple.clj`, `orbit.clj` and `knot.clj` exist to be watched: rotating-
  shape and shader demos with no assertions of their own.
  Also covers the four touchpoints (namespace, `deps.edn` alias, `bb.edn`
  task, `smokes` entry) a new example needs so it doesn't go silently
  unexercised.
- [`architecture.md`](architecture.md): how thin the seam to glitter
  actually is: of 25 files under `src/glitter_gl/`, only `gtk.clj` has a
  literal `:require` on `glitter.*`, and `scene.clj` has none at all.
  Traces, against the real source, why a `:gl-area` keeps redrawing
  (`queue-render` has exactly one call site in the whole project:
  `gtk.clj`'s `gl-area-apply!` tick wrapper, which `reactive-area` always
  causes to be installed, and a bare state `swap!` alone never triggers a
  repaint), plus why `:gl-area`
  bypasses glitter's uniform signal table entirely and where GL-plumbing
  state deliberately breaks glitter's one-atom/action-dispatch
  discipline.
- [`porting-and-attribution.md`](porting-and-attribution.md): the three
  sourcing buckets `NOTICE.md` tracks (22 verbatim glimmer-gl files, 3
  adapted core files plus demo material, and what's genuinely new), and
  the two-hop lineage back through glimmer-gl to thi.ng/geom. Explains
  the Standard Verbatim Port Procedure's sed-diff check that makes
  "verbatim port" a checkable claim rather than an assertion, the
  formatting-pass exemption that lets project-wide `clojure-lsp
  format`/`clean-ns` touch those 22 files without violating it, and the
  one documented live-found correction (`:gl-area`'s `:apply`-vs-
  `:connect` fix) told from the provenance angle.

### The library layer

- [`geometry-and-shaders.md`](geometry-and-shaders.md): orientation, not
  reference, over the 22 verbatim-ported namespaces: the three groups (14
  pure geometry/math, 4 mesh, 4 GL plumbing), and the two design
  decisions (column-major matrices, shaders as mergeable data) a new
  reader would otherwise have to reconstruct by hand. Traces where a mesh
  becomes GL data and finds a real surprise along the way:
  `glmesh.clj`'s documented mesh → GL pipeline has no caller anywhere but
  its own test; the shipped renderer hand-rolls the actual upload via
  `mesh/->floats` and eleven raw `gl.clj` calls instead.

### GTK integration

- [`gl-area-widget-layer.md`](gl-area-widget-layer.md): the `:gl-area`
  widget's mechanics in full: the `:apply`-vs-`:connect` correction,
  traced through `create-node`/`set-attributes` to the exact reason a
  `:connect` closure never sees a real prop map under glitter's
  reconciler. Catalogs every GTK4 signal shape that doesn't fit
  glitter's uniform `void(widget,data)` path: `"render"`'s non-void
  return, `"resize"`'s extra int arguments, `on-tick`'s frame-clock API
  that isn't a signal at all, and the controllers `on-motion`/`on-key`/
  `on-button` layer onto the widget or its root window.
- [`scene-and-app.md`](scene-and-app.md): `glitter-gl.scene`'s
  mini-hiccup dialect, and why it's not glitter's hiccup: `[fn
  args...]` is a first-class component invocation here, a real trap in
  both directions the page states plainly with a live example
  (`plasma.clj`'s once-broken `shape-button` calls). Explains why `plan`
  is a plain function with no reactive-cell tracking, the write-once
  handler contract `gtk.clj`'s `wired` atom enforces, and gives
  `reactive-area` an honest status note: unit-tested, and now exercised
  live end to end by `examples/glitter_gl/orbit.clj`, plus the per-tick
  full-view-recompute cost that demo pays.

### Verify

- [`testing-and-tasks.md`](testing-and-tasks.md): the unit suite (`jolt
  -M:test`, 177 tests / 556 assertions) and the live-GTK smoke plus
  headless check `bb smokes` runs, plus `offscreen_test.clj`'s real
  render-to-texture round trip and its designed-to-skip behavior on a
  display-less machine.
  Documents the `jolt -M:<alias>` vs `jolt <task>` exit-code trap
  (reverified fresh against the currently-installed `v0.7.23`, not just
  carried forward from the original `v0.6.3` finding) and the
  quality-tooling surface (`bb lint`, `bb lsp:*`, the FFI-aware
  clj-kondo hook, `bb verify` vs. the stricter git pre-commit hook).
- [`limitations.md`](limitations.md): every known v1 gap, each with the
  reason it was left rather than fixed: `"render"`'s 2-arg
  `foreign-callable` declaration against GTK4's real 3-argument signal
  (harmless, traced through the calling convention, not just asserted),
  the dev-time hiccup warning that has no application-level silencer,
  and why `:scale` is deliberately not registered here.

## See also

- [`CONTRIBUTING.md`](https://github.com/burinc/glitter-gl/blob/main/CONTRIBUTING.md) (repo root): how to build,
  test, and submit changes, plus the ten numbered invariants this project
  does not regress (the `:gl-area` correction is invariant #2 there, in
  summary form).
- `NOTICE.md` (repo root): the file-by-file attribution ledger and
  porting summary (verbatim / adapted / new buckets).
- [glimmer-gl](https://github.com/jolt-lang/glimmer-gl): the library
  this project ports from.
- [glitter](https://github.com/burinc/glitter): the renderer this
  project extends; see its own `docs/guide/` for the reconcile →
  `IRender`/`IMemory` architecture and the GTK widget layer this
  project's `:gl-area` plugs into.
