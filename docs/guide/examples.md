# The examples

Everything runnable lives under `examples/glitter_gl/`: seven namespaces,
six of them with their own `jolt -M:<alias>`/`bb <name>` entry point, one
(`plasma_shader.clj`) that exists only to be required by `plasma.clj`. They
split cleanly along one axis: two exist to **fail when a regression lands**
(`check.clj`, `gl_area_smoke.clj`), one exists to be **composed and read**
(`plasma_shader.clj`), and four exist to be **watched**
(`plasma.clj`, `ripple.clj`, `orbit.clj`, `knot.clj`). Only
the first two are part of the project's actual regression coverage; say so
plainly rather than letting a reader assume a demo is tested because it
runs.

## `check.clj`: headless, and the one that pins the widest surface

```sh
jolt -M:check     # or: bb check
```

Needs no GL context and no display, so it's the fastest thing in the
project to run, and the only example safe to call from a machine with no
windowing system at all. It asserts three unrelated things in one process:

1. `plasma-shader/shader-spec` compiles to GLSL with the declarations the
   composition promised: `a_pos` attribute, `u_time` uniform, the `palette`
   and `plasma` prelude functions. If a future shader-module edit drops a
   uniform or forgets to thread a prelude function through
   `merge-specs`, this is what catches it, headlessly, before anyone opens
   the demo and notices the picture looks wrong.
2. `mesh/->floats` produces a well-formed vertex buffer (positive vertex
   count, buffer length exactly `count * stride`) for each of the three
   primitive shapes the demo can switch between (cube, sphere, tetra).
3. `:gl-area` and `:scale` are both present in `glitter.widget/specs` after
   requiring `glitter-gl.gtk`.

That third assertion is a **load-order check, not an ownership check**,
worth stating precisely because it's easy to misread. `:gl-area` is
registered by this library; `:scale` is not (invariant #6 in
[`CONTRIBUTING.md`](https://github.com/burinc/glitter-gl/blob/main/CONTRIBUTING.md); glitter already ships a
richer, first-party `:scale`, and glitter-gl deliberately doesn't shadow
it). `check.clj`'s own docstring says as much: `:scale` "comes from
glitter's own native widget, not from glitter-gl.gtk." So if `:scale` is
missing when this runs, the bug is that **glitter itself never loaded**,
not that glitter-gl broke something it owns.

Measured, run just now:

```
shader: vs 1327 chars, fs 1403 chars
geometry:
  cube    faces=6 tris=12 verts=36 floats=216 stride=6
  sphere  faces=504 tris=952 verts=2856 floats=17136 stride=6
  tetra   faces=4 tris=4 verts=12 floats=72 stride=6
widgets registered: true
check: ok
```

**What it pins:** the shader-composition contract and the mesh-buffer
contract, both without touching GTK at all. This is the one example a CI
runner could execute today with no display, once CI is wired (see
[`testing-and-tasks.md`](testing-and-tasks.md)).

## `gl_area_smoke.clj`: live GTK, and the one that pins the correction

```sh
jolt -M:gl-area-smoke     # or: bb gl-area-smoke
```

Needs a real display: it opens an actual `GtkGLArea` under glitter's real
reconciler (not a fake renderer) and drives it through
`:on-realize`/`:on-render`/`:on-resize`, then reads back a `results` atom
the handlers populated and exits non-zero if any of `:realized?`,
`:rendered?`, `:resized?` didn't land, or `:error` is non-nil. Confirmed by
running it here, with a display available:

```
:results {:realized? true, :rendered? true, :resized? [640 480], :error nil}
```

**What it pins:** this is the one example that exercises invariant #2,
`:gl-area`'s handlers wiring through the widget spec's `:apply` closure
rather than `glitter.widget`'s `:connect` hook. That correction was found
*because* the original `:connect`-based design silently never fired under
glitter's real reconciler; a fake in-memory renderer (the kind
`test/glitter_gl/*_test.clj` uses) can't reproduce that failure, because it
never routes props through the real `apply-props!` path at all. This is why
the smoke exists as a separate live-GTK example rather than a unit test;
see the smoke's own docstring, and
[`gl-area-widget-layer.md`](gl-area-widget-layer.md) for the full mechanics.

It's CI-safe in the sense that `bb.edn`'s `smokes` task runs it via
`jolt -M:gl-area-smoke` (the exit-code-propagating alias form, not the task
form; see [`testing-and-tasks.md`](testing-and-tasks.md)), but CI isn't
actually wired for this project yet, so today it's a local gate a
contributor runs by hand before opening a PR.

## `plasma_shader.clj`: composed, not run

This one has no `-main`, no `deps.edn` alias, no `bb.edn` task. It's the
shared shader spec: four data maps (`base`, `plasma-module`,
`stripes-module`, `main-module`) combined with a single call:

```clojure
(def shader-spec
  (sh/merge-specs base plasma-module stripes-module main-module))
```

This demonstrates `glitter-gl.shader`'s composition model rather than
anything glitter-gl-specific. `base` supplies the vertex stage and framing
uniforms; `plasma-module` contributes a domain-warped, 4-octave plasma
field plus an Inigo Quilez cosine palette (both as GLSL text in
`:prelude`, since the shader DSL's data IR only compiles `main()` bodies,
not free functions); `stripes-module` contributes an independent animated
stripe pattern; `main-module` blends the two by `u_mix` and applies
Blinn-Phong-style lighting to the result. Drop a module from the merge, or
add a third, and the visual changes accordingly. That's the point being
demonstrated.

**What it pins:** nothing on its own. It's exercised, not asserted,
by `check.clj`'s shader-compile checks (does the merged spec still emit
`a_pos`, `u_time`, `palette(`, `plasma(`) and rendered, not verified, by
`plasma.clj`. If you're adding a new shader module to this codebase, this
file plus `check.clj`'s three assertions are the pattern to copy: one
adds a data map to the merge, the other adds a `str/includes?` line
asserting the thing that map was supposed to contribute is actually in the
generated source.

## `plasma.clj`: the one to watch, not the one to trust

```sh
jolt -M:plasma     # or: bb plasma
```

A rotating cube/sphere/tetrahedron lit by the composed plasma+stripes
shader, with a glitter-native control panel: shape buttons, five sliders
(speed, zoom, scale, warp, blend), a smooth-shading checkbutton, a
pause/resume button.

| preview | what it shows |
|---|---|
| [<img src="../demos/pane/plasma-cube.gif" width="300">](../demos/plasma-cube.gif) | **`plasma-cube`**: the default cube, rotating under the plasma/stripes shader with flat shading, so each face reads as its own solid-color panel, bounded by a sharp edge. |
| [<img src="../demos/pane/plasma-sphere.gif" width="300">](../demos/plasma-sphere.gif) | **`plasma-sphere`**: the same shader on a sphere. Same flat-shading toggle (off), but the sphere's much higher facet count makes the color read as a continuous gradient instead of blocks. |
| [<img src="../demos/pane/plasma-tetra.gif" width="300">](../demos/plasma-tetra.gif) | **`plasma-tetra`**: the same shader on a tetrahedron. With only four large triangular faces, this is the clearest case for seeing exactly where flat shading draws the line between one face's color and the next. |
| [<img src="../demos/pane/plasma-smooth.gif" width="300">](../demos/plasma-smooth.gif) | **`plasma-smooth`**: the cube again, with the smooth-shading checkbutton on: the same plasma pattern now flows continuously across an edge instead of jumping between two flat blocks. The direct visual contrast with the take above. |

Every preview is a real recording of the demo running, not a mockup. They
are committed under `docs/demos/`, and each thumbnail links to the
full-size recording.

**Be honest about what this one is not**: it is not part of `bb.edn`'s
`smokes` task, it makes no assertions, and nothing fails when its picture
regresses except a human noticing it looks wrong. Per the organising
principle for this whole guide (an example that exists only to be pretty
is worth less than one that fails when a regression lands), this is the
"pretty" one. (It does support a `GLITTER_GL_DEMO_QUIT_MS` env var,
mirroring the original's `GLIMMER_GL_DEMO_QUIT_MS`, that auto-closes the
window after N ms, useful for confirming it launches without hanging, but
that's a liveness check, not a correctness one, and it isn't wired into
any automated task today.)

What it *is* worth reading for is invariant #4, in the clearest form
anywhere in this codebase.

### Why it wires `:gl-area` directly instead of going through `reactive-area`

`glitter-gl.app/reactive-area` exists precisely to keep GL plumbing out of
application code: build a scene as data, hand it to `reactive-area`, get
back a ready-made `:gl-area` prop map. `plasma.clj` doesn't use it. It
wires `on-realize`/`on-render`/`on-resize`/`on-tick` by hand, the same way
`gl_area_smoke.clj` does.

The reason is provenance, not oversight: `plasma.clj`'s own docstring says
it's "ported from `gl-demo.core`... converting its reactive-cell control
panel... into glitter's single state atom + data-driven action dispatch,"
while "the GL render-loop plumbing (on-realize/on-render/on-resize/on-tick)
is otherwise unchanged". Direct wiring is how the upstream glimmer-gl demo
already worked, and the port kept that shape rather than routing it through
the newer `reactive-area` layer built later in this project's history.

The honest cost of that choice: until this arc, `reactive-area` had direct
unit coverage (`app_test.clj` checks its returned prop map's shape and
defaults) but no example that mounted it end to end against a real
`:gl-area` and actually rendered through it. See
the `orbit.clj` section below below for the
example that closes that gap, [`scene-and-app.md`](scene-and-app.md)'s
"What `reactive-area` actually is" section for the full "honest status"
note, and [`limitations.md`](limitations.md) for this and the project's
other known gaps.

### The two things it demonstrates about the state split

`plasma.clj` is the clearest illustration of invariant #4 (`reactive-area`'s
own handlers read/write state directly rather than dispatching, the same
principle plasma.clj follows even though it bypasses `reactive-area`
itself), because both halves of the split sit right next to each other in
one file:

1. **The GL handlers read and write plain atoms directly.** `on-tick`
   doesn't dispatch anything; it just mutates:

   ```clojure
   (defn on-tick [_area]
     (when-not (:paused @state)
       (swap! clock + (* (double (:speed @state)) frame-dt))))
   ```

   `clock`, `viewport`, and `gl-state` are all `defonce` atoms outside the
   reconciled view entirely, updated 60 times a second by GTK's frame
   clock, exactly the kind of state invariant #4 says has no business
   round-tripping through `swap!`-then-re-render.

2. **The control panel dispatches actions like any other glitter UI.**
   Every slider and button emits a data tuple instead of calling a
   function:

   ```clojure
   [:scale {:min lo :max hi :step step :value value :digits 2 :hexpand true
            :on {:value-changed [[:effect/assoc-in [key] [:glitter/value]]]}}]
   ```

   and the checkbutton/pause controls go through registered actions
   (`:action/toggle-smooth`, `:action/toggle-paused`) that expand to the
   same `:effect/assoc-in` primitive, dispatched through `glitter.nexus`
   exactly the way `todo.clj`/`crud.clj` do in glitter's own demo set.

Put side by side, the contrast answers a question a new contributor will
otherwise have to reconstruct from first principles: *which category does
my new piece of state belong to?* If it changes on every frame and nothing
in the UI needs to react to a click causing it, it's a plain atom. If a
person clicks or drags something, it's an action.

## `ripple.clj`: the shader DSL with no geometry to speak of

```sh
jolt -M:ripple     # or: bb ripple
```

A single `primitives/quad` scaled to fill clip space directly, no MVP, no
camera, no lighting, no material system, textured by a fragment shader
composed through `glitter-gl.shader/merge-specs` from two small modules:
a drifting concentric-ripple field, and a wave-to-color mapping with a
soft corner vignette. `on-tick` advances a time uniform; `on-render`
draws.

The claim this one makes is about the library's two independent halves.
`plasma.clj` already shows a lit solid whose surface comes from a
composable shader, so a second lit-solid-plus-shader demo would say
nothing new. `ripple.clj` has no geometry to speak of, and demonstrates
that the shader-spec DSL is useful entirely on its own, with no mesh
half of the library involved at all.

| preview | what it shows |
|---|---|
| [<img src="../demos/ripple.gif" width="300">](../demos/ripple.gif) | **`ripple`**: concentric rings drifting across a full-window quad, generated entirely by the fragment shader; there is no mesh visible because there is nothing to the geometry but the quad. |

Like `plasma.clj`, this is watched, not trusted: it makes no assertions
and is not part of `bb smokes`. `bb.edn`'s own comment on the `smokes`
task explains why: what makes an example usable as a smoke is that it
raises or exits non-zero when something is wrong, not that it calls
`System/exit`. `ripple.clj` wraps program construction in `(try ...
(catch Throwable _ nil))`, so a broken shader just prints and the
process still exits 0; folding it into `smokes` would look like a gate
that cannot fail.

## `orbit.clj`: the first live `reactive-area` demo

```sh
jolt -M:orbit     # or: bb orbit
```

Six distinct solids drawn from the library's own shapes
(`primitives/cuboid`, `primitives/sphere`, `primitives/tetrahedron`, and
`polyhedra`'s octahedron/icosahedron/dodecahedron) orbit above a lit,
shadowed ground plane, each at its own radius, phase, and speed. Unlike
every other example, it's mounted through `glitter-gl.app/reactive-area`
rather than direct `:gl-area` wiring, and its orbit phase lives in
glitter's own shared `state` atom rather than a private clock atom, so
that `scene-fn` is a genuine function of state.

That choice is the whole point of the example, not incidental:
`reactive-area` had direct unit coverage but had never before been
mounted against a real `:gl-area`. `orbit.clj` closes that gap; see
[`limitations.md`](limitations.md#reactive-area-now-has-a-live-demo) for
the before-and-after, and
[`scene-and-app.md`](scene-and-app.md)
for the real cost this example pays, by design, for driving glitter's
watched state atom on every tick, which `plasma.clj` and `ripple.clj`
avoid.

| preview | what it shows |
|---|---|
| [<img src="../demos/orbit.gif" width="300">](../demos/orbit.gif) | **`orbit`**: six differently-shaped, differently-colored solids circling a shadowed ground plane at independent speeds, most also spinning on their own axis. |

Like `plasma.clj` and `ripple.clj`, watched not trusted: no assertions,
not part of `bb smokes`, for the same reason.

## `knot.clj`: geometry the library does not ship

```sh
jolt -M:knot     # or: bb knot
```

A (2,3) trefoil torus knot, swept as a tube and rendered as a single
rotating lit solid: 2400 quads generated from scratch by a pure function
(`torus-knot-faces`) from three radii (major `R`, winding amplitude `r`,
tube radius `tube-r`) and two counts (`samples`, `sides`), handed
straight to `mesh/mesh`. Everything downstream of the generator, upload,
shader, draw, is the exact single-mesh plumbing `plasma.clj` already
established, wired directly to `:gl-area` rather than through
`reactive-area` (one mesh, no scene composition, nothing for
`reactive-area` to coordinate).

The point is what `primitives.clj` and `polyhedra.clj` are not: a
starting set, not the boundary of what `mesh/mesh` can build. No other
example generates its own geometry; every other one draws a shape the
library already ships as a constructor.

| preview | what it shows |
|---|---|
| [<img src="../demos/knot.gif" width="300">](../demos/knot.gif) | **`knot`**: a rotating, smooth-shaded trefoil torus knot, its tube visibly self-crossing the way a genuine (2,3) knot does rather than closing into a plain ring. |

Like the other two, watched not trusted: no assertions, not part of
`bb smokes`.

## Adding an example

Six touchpoints; skip one and the example is invisible to something:

1. **The namespace**, under `examples/glitter_gl/`.
2. **A `deps.edn` alias**, so `jolt -M:<name>` works without babashka:

   ```clojure
   :gl-area-smoke {:extra-paths ["examples"] :main-opts ["-m" "glitter-gl.gl-area-smoke"]}
   ```
3. **A `bb.edn` task**, so `bb <name>` works and it shows up in `bb info`:

   ```clojure
   gl-area-smoke {:doc "Live-GTK smoke: :gl-area construct/realize/render/resize"
                  :task (shell "jolt" "-M:gl-area-smoke")}
   ```
4. **A `bb.edn` `demos:examples` row**, so `bb record`'s gallery pipeline
   knows the example exists:

   ```clojure
   {:id "orbit"
    :group "orbit"
    :desc "six distinct solids orbiting above a ground plane, lit and shadowed, mounted via reactive-area"
    :src "examples/glitter_gl/orbit.clj"
    :run "jolt -M:orbit"}
   ```
5. **A `scripts/demo_manifest.edn` group entry**, matching the row's
   `:group` key, so the recorded GIF gets its own gallery heading:

   ```clojure
   {:key "orbit" :title "the orbit demo"}
   ```
6. **If it's a live-GTK example that asserts and exits non-zero on
   failure**, add it to `bb.edn`'s `smokes` task so `bb smokes` actually
   runs it:

   ```clojure
   smokes {:doc "Run every live-GTK smoke and headless check in sequence; stops at the first failure"
           :task (do (shell "jolt" "-M:gl-area-smoke")
                     (shell "jolt" "-M:check"))}
   ```

   Skipping this step doesn't break the new example; it just means
   nobody running `bb smokes` (or a future CI job built on it) ever
   exercises it, the same silent gap `plasma.clj` has today by design.

A smoke's own `-main` must exit non-zero on failure and be invoked via
`jolt -M:<alias>`, never the bare `jolt <task>` shorthand; see
[`testing-and-tasks.md`](testing-and-tasks.md) for why the task form can't
be trusted to fail a build at all.
