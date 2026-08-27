# Scene and app: the declarative layer

`glitter-gl.scene` and `glitter-gl.app` are the two files that let a GL
pane be authored declaratively (a hiccup-shaped scene tree in, a
compiled render plan out) instead of hand-writing GL calls per frame.
Both are adapted from glimmer-gl, not verbatim ports, and the adaptation
is where four of `CONTRIBUTING.md`'s ten invariants (#3, #4, #5, #9)
live. This page is the detailed version of what those invariants state
in summary.

## `glitter-gl.scene`'s mini-hiccup dialect is not glitter's hiccup

A scene tree looks like glitter hiccup (vectors, keyword tags, a props
map) and mostly behaves like it: `[:group {:transform m} & children]`
threads a world matrix, `[:mesh {...}]` and `[:light {...}]` are leaves,
`[:camera {...}]` is collected once. But `glitter-gl.scene/expand`
recognizes a second kind of vector head that glitter's own hiccup does
not:

```clojure
;; scene.clj: expand
(fn? head) (expand (apply head (rest node)))
```

`[my-component args...]`, a vector whose first element is a **function
value**, not a keyword, is a component invocation. `expand` calls it,
takes whatever hiccup it returns, and recursively expands that. This is
exactly Reagent's/glimmer's component convention, deliberately carried
over into this one corner of glitter-gl even though the rest of the
project follows glitter's stricter rule. `scene_test.clj` pins the
behavior directly:

```clojure
(deftest component-invocation-expands-to-native-hiccup
  (let [box (fn [material] [:mesh {:geom ::cube
                                   :material material}])
        items (:items (scene/flatten (scene/expand [box :stone])))]
    (is (= [::cube] (map :geom items)))
    (is (= [:stone] (map :material items)))))
```

and `nested-components-compose` confirms it recurses: a component
returning a tree that itself contains more `[fn args...]` invocations
expands all the way down to native nodes, with group transforms still
threading correctly to the leaf mesh.

**Say both directions, because both are real traps:**

- A reader who carries glitter's own rule (*"never write `[my-fn
  args...]`, always call `(my-fn args...)` directly and splice the
  result"*) into a scene tree will not write broken code (calling
  plainly and splicing the result still works fine here too, since
  `expand-children` walks and expands whatever it's handed). But they
  will write *needlessly constrained* code: scene trees were built to
  support the `[component args...]` shape as a first-class authoring
  style (see `nested-components-compose`) and avoiding it means
  giving up the one place in this codebase where that Reagent-style
  ergonomic is actually available and intended.
- A reader who learns scene's rule first and carries it back into
  ordinary glitter UI hiccup hits **invariant #5**, a real, live-found
  bug. `glitter.hiccup/hiccup?` requires a literal keyword in position
  0; a vector whose head is a function value fails that check entirely
  and is treated as an opaque child value, stringified via `str` rather
  than expanded or rejected. No exception. The failure is silent: the
  UI renders literal text like `[#object[my_ns$shape_button 0x1234
  "..."] "Cube"]` where a button should be. This happened for real in
  `examples/glitter_gl/plasma.clj`'s `control-panel`, whose
  `shape-button` calls were originally bracket-wrapped.

The reason the two dialects can afford to disagree is that they're
consumed by different code entirely: scene hiccup is compiled by
`glitter-gl.scene/flatten` into a render plan for
`glitter-gl.renderer`, and never touches `glitter.core/reconcile`. Mixing
the two mental models is the mistake, not either model on its own.

## No reactive cells: a deliberate simplification (invariant #3)

glimmer-gl's originals wrap the compiled scene plan in a
`glimmer.ratom/reaction`, so a scene only recomputes when a cell it
actually dereferenced changes, dependency-tracked, like the rest of
glimmer's reactive model. glitter has no equivalent machinery: there is
no per-node dependency tracking anywhere in `glitter.core`. Its
state-atom watcher (`glitter.gtk/mount!`) already recomputes the *whole*
`state -> hiccup` view on every change, unconditionally. Given that,
building a scene the same dependency-tracked way `glimmer.ratom` does
would be solving a problem glitter's own model doesn't have, so `plan`
doesn't try:

```clojure
;; scene.clj: docstring elided, code otherwise verbatim
(defn plan
  ...
  [state scene-fn]
  (flatten (expand (scene-fn state))))
```

No reaction, no deref-tracking, no cache. It's a plain function: call
it, get a plan, done. `scene_test.clj`'s
`plan-reflects-the-current-state-on-each-call` and
`plan-reflects-the-latest-of-many-state-values` both confirm this by
calling `plan` twice with two different state maps and asserting the
second call reflects the second map. "Recomputes when state changes"
here just means "call it again with the new state," the same top-down
re-render model glitter itself uses for `view`. Frame this as matching
the host's model deliberately, not as a missing feature relative to
glimmer-gl: a reaction wrapper here would add bookkeeping for a
short-circuit glitter's own reconciler never offers the caller anyway.

## Handlers read and write the state atom directly (invariant #4)

`reactive-area`'s own docstring states the contract plainly for
`:on-tick`: *"optional app animation policy; runs each frame before
render. Mutate state-driving cells here."* Concretely, `app.clj`'s
`reactive-area` installs thin wrapper closures for
`:on-tick`/`:on-motion`/`:on-key`/`:on-button` that each delegate
straight to the caller-supplied `opts` function:

```clojure
;; app.clj
:on-tick
(or (:on-tick opts) (fn [_area]))
:on-motion
(when-let [m (:on-motion opts)]
  (fn [area x y]
    (let [w (double (max (long (gtk/widget-width area)) 1))
          h (double (max (long (gtk/widget-height area)) 1))]
      (m (- (/ (* 2.0 (double x)) w) 1.0)
         (- (/ (* 2.0 (double y)) h) 1.0)))))
```

`reactive-area` itself never touches the shared `state` atom except to
`deref` it inside `:on-render`. The *contract*, and the reason this
page exists, is that the app's own `:on-tick`/`:on-motion`/`:on-key`/
`:on-button` functions are expected to `swap!`/`reset!` that same
`state` atom directly, not to return `[[:action/foo ...]]` tuples the
way glitter's `:on {:click [...]}` handlers do. `:on-render` then
derefs `state` fresh on every GTK-driven call, so whatever those
handlers changed is picked up on the next frame with no dispatch step
in between.

Why the deliberate departure from glitter's one-atom/pure-view/
action-dispatch model: that model's entire value is separating "what
happened" from "what changed" behind an inspectable, replayable data
structure, worth paying for on a button click, worth nothing on a
value that can legitimately change 60 times a second (a camera
following the pointer, a clock advancing every tick). Routing
per-frame motion deltas through `swap!` → full hiccup recompute → diff
→ dispatch would spend the reconciler's cost on state a `:gl-area`
element doesn't even re-render through when it changes (see
[`architecture.md`](architecture.md)'s "How a frame actually happens":
state changes don't drive `:on-render` at all; the frame clock does).

This isn't glitter-gl inventing an undisciplined escape hatch from
nothing, either. glitter's own dispatch system already has a documented
precedent for values that can't be expressed as static action-tuple
data: a value-bearing GTK signal (a slider's `"value-changed"`, an
entry's `"changed"`) carries its live value in the dispatched event map
under `:glitter/value`, read back as `(get-in event [:glitter/dom-event
:glitter/value])`, precisely because the action tuple itself is fixed
at the moment `view` runs, before a value that only exists once the
user acts can be known. That pattern still dispatches through the
normal `*dispatch*` path; only the *value* travels outside the static
tuple. `reactive-area`'s handlers go one step further and skip dispatch
entirely, which is the right call specifically because GL-plumbing
state changes far more often, and far less discretely, than a slider
release.

Demo code mirrors the same split. `examples/glitter_gl/plasma.clj`'s
`on-realize`/`on-render`/`on-resize`/`on-tick` read and write plain
atoms (`clock`, `viewport`, `gl-state`) directly, while its
`control-panel` (ordinary buttons, sliders, a checkbutton) dispatches
`[[:effect/assoc-in ...]]`/`[[:action/toggle-paused]]` tuples exactly
like any other glitter view.

## Ticking glitter's state atom costs a full view recompute, not just a frame

Invariant #4 says `reactive-area`'s handlers read and write the shared
`state` atom directly, and that's real: `:on-tick`'s own docstring
contract is to "mutate state-driving cells here." What that contract
doesn't spell out on its own is that *which* atom a caller chooses to
tick from `:on-tick` has a cost, and the cost differs depending on the
answer.

`glitter.gtk/mount!`, the function every example's `-main` calls to
start the reconciler except `check.clj` (headless, never mounts) and
`plasma_shader.clj` (no `-main` at all), installs a watcher on
whichever state atom it's handed:

```clojure
;; glitter/src/glitter/gtk.clj:409
(add-watch state-atom ::render (fn [_ _ _ state] (app/on-gui (fn [] (render! state)))))
```

Any `swap!`/`reset!` on that specific atom fires this watcher, which
re-runs `view` and reconciles the *whole* hiccup tree, whether or not
anything outside the GL pane actually depends on what changed.
`examples/glitter_gl/orbit.clj` advances orbit phase from `:on-tick` with
`(swap! state update :t + frame-dt)` on exactly that atom: `orbit.clj`
calls `(gtk/mount! window view state)` and passes the same `state` into
`reactive-area`, deliberately, so that `scene-fn` is a genuine function
of `state` rather than a closure over a private mutable, a deliberate
choice for this example. Every tick therefore drives a full `view` →
reconcile pass, not just the GL render loop.

`plasma.clj`, `ripple.clj`, `knot.clj`, `gears.clj` and `textured.clj`
don't pay this cost, and not by accident: all five advance a private
`clock` atom from their own `:on-tick`, outside glitter's `state`
entirely. `glitter.gtk/mount!` never watches `clock`, so nothing outside
the render loop reruns when it changes. `picking.clj` doesn't pay it
either, for a related but distinct reason: it has no `clock` atom at
all, and its own `:on-tick` is a deliberate no-op (see
[`examples.md`](examples.md#pickingclj-the-first-example-to-react-to-the-pointer));
`:on-motion` resets a private `pointer-pos` atom instead, which
`mount!` never watches either.

Neither choice is wrong. `orbit.clj` is the honest exercise of
`reactive-area`, whose own docstring says it's "driven by glitter's
shared top-level `state` atom" by design; a demo that routed around that
to dodge the cost would prove nothing about the thing it exists to
prove. But the cost is real and worth naming for anyone reaching for
`reactive-area` in a busier reconciled tree than a single `:gl-area`
pane: the recorded frames and the live run showed no visible stutter,
but that's a single-widget view, not a claim the same approach scales
once `view` has real work to do outside the GL pane. See
[`limitations.md`](limitations.md#reactive-area-now-has-a-live-demo) for
the rest of what `orbit.clj` did, and didn't, verify.

## The write-once handler contract (invariant #9)

Every other glitter widget's `:apply` closure re-applies on every
render: that's how `:scale`'s min/max/step pick up new values each
time `view` returns a different range. `:gl-area` is a deliberate
exception for its event-handler props, and the guard that makes it one
lives in `gtk.clj`:

```clojure
;; gtk.clj
(defonce ^:private wired (atom {}))

(defn- wire-once!
  "True the FIRST time `event` is seen for `area`; false (and no side effect)
  on any repeat call. See the correction note above :gl-area's :apply for why
  this guard is necessary."
  [area event]
  (let [seen (get @wired area #{})]
    (when-not (contains? seen event)
      (swap! wired update area (fnil conj #{}) event)
      true)))
```

**Verified against the source, not recalled:** `wired` is a single atom
holding a map from `area` (the raw `GtkGLArea` pointer) to a *set* of
event keywords already connected for that widget. `wire-once!` reads
that per-`area` set (`seen`), and only proceeds (updating the set and
returning `true`) when `event` is not already a member. Every call
site in `gl-area-apply!` gates on this exact function, e.g.
`(when (and on-realize (wire-once! area :on-realize)) ...)`. So while
the literal map key is `area` alone (with `event` living inside that
entry's set), the *effective* guard is keyed on the pair: a second
`:apply` call for the same `[area event]` combination finds `event`
already in `seen`, `wire-once!` returns `nil`, and the `when` guarding
the `connect!`/`gtk-widget-add-tick-callback` call short-circuits:
no new signal connection, no side effect, silently.

**What this means in practice:** the closure connected on an event's
first arrival is the one that runs for the widget's entire life. If a
caller invokes `reactive-area` again (say, on every render, expecting
a fresh set of opts/closures to replace the stale ones), the new
closures are simply discarded; the original ones keep firing. There is
no exception, no log line, nothing observably different about the call
that failed to take effect. This differs from the *general* `:apply`
mechanism (every prop key re-applies on every render, which is what
lets `:scale` re-range live) specifically for `:gl-area`'s seven
event-handler props. `:version`/`:depth-buffer` are NOT gated this way
and do re-apply each render, since they're plain value-setting FFI
calls with no signal-connection cost to guard against.

**The practical rule:** build a `:gl-area` prop map, directly or via
`glitter-gl.app/reactive-area`, exactly once, at a stable call site,
and reuse that same result across every render. Never call
`reactive-area` fresh inside a `view` function expecting each render's
new closures to take over; they will not. Full mechanics, including the
`:connect`-vs-`:apply` correction this guard exists to work around:
[`gl-area-widget-layer.md`](gl-area-widget-layer.md).

## What `reactive-area` actually is: an honest status note

`reactive-area` is real, adapted (not verbatim-ported) code with direct
unit coverage: `app_test.clj` asserts it returns a `:gl-area` prop map
with all four standard handler keys present as functions, that its
defaults match the documented ones (`[3 2]` version, depth buffer on,
`hexpand`/`vexpand` true), and that explicit `opts` override them. It
also unit-tests `keyval->kw`'s GDK-keyval-to-movement-keyword mapping in
isolation.

What changed this arc: `examples/glitter_gl/orbit.clj` now mounts
`reactive-area` against a real `:gl-area` and renders a frame through
it: six solids orbiting a lit, shadowed ground plane, driven by
glitter's own shared `state` atom. It's the only example built
specifically to exercise `reactive-area`; every other example
(`plasma.clj`, `ripple.clj`, `knot.clj`, `gears.clj`, `textured.clj`,
`picking.clj`) wires `:gl-area` directly instead, matching `plasma.clj`'s
own upstream source: its docstring describes itself as ported from
`gl-demo.core`, with "the GL render-loop
plumbing (on-realize/on-render/on-resize/on-tick) ... otherwise
unchanged". `orbit.clj` mounted and rendered correctly on the first
version that actually ran: no crash, no black window, no stalled scene.
`reactive-area` is consequently verified at the unit level (its prop map
shape, its defaults) AND, for the one scene shape `orbit.clj` builds, at
the integration level: a real render loop actually driving a real
`:gl-area` through it. It doesn't exercise every opt `reactive-area`
accepts (`:fog`, `:shadow-bias`, `:depth-spec`/`:lit-spec`,
`:on-motion`, `:on-key`, `:on-button`), and it pays a real cost for
ticking glitter's watched `state` atom that a busier reconciled tree
would need to weigh (see "Ticking glitter's state atom..." above). See
[`limitations.md`](limitations.md#reactive-area-now-has-a-live-demo) for
this project's other known gaps.
