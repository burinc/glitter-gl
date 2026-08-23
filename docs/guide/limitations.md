# Known v1 limitations

Each of these is a deliberate scope decision, not an oversight. If
you're tempted to "just fix" one of these opportunistically, read the
rationale first — each has a reason the fix (or the workaround) was left
for a later round, not a reason it's impossible.

## `reactive-area` has no live demo

`glitter-gl.app/reactive-area` is real, adapted code with direct unit
coverage: `app_test.clj` asserts it returns a `:gl-area` prop map with
all four standard handler keys present as functions, that its defaults
match the documented ones (`[3 2]` version, depth buffer on,
`hexpand`/`vexpand` true), and that explicit `opts` override them. What
it does not have is a demo that mounts it and drives a real render loop
through it end to end. Grepping the whole tree for `reactive-area`
outside its own definition and test file returns nothing — the shipped
`plasma` demo (`examples/glitter_gl/plasma.clj`) wires `:gl-area`
directly instead, matching its own upstream source: the demo's docstring
describes itself as ported from `gl-demo.core`, with "the GL render-loop
plumbing (on-realize/on-render/on-resize/on-tick) ... otherwise
unchanged" — direct wiring is how the original glimmer-gl demo already
worked, not a shortcut taken during this port.

**What a reader should treat with extra care:** `reactive-area` is
verified at the unit level — its prop-map shape, its defaults — but not
at the integration level. No example in this project actually mounts it
against a live `GtkGLArea` and renders a frame through it. If you adopt
it for a real app, your app is the first thing that ever exercises the
wiring between its returned prop map and a real render loop; treat that
first integration as genuinely new ground, not as something this
codebase has already proven under fire.

**Why left as-is:** a second demo whose only purpose is exercising
`reactive-area` end to end wasn't judged worth the maintenance cost of a
largely-redundant example for v1 — the `plasma` demo already exercises
the same render loop, the same `:gl-area` prop shape, and the same
state-atom-direct-write pattern `reactive-area`'s handlers follow, just
wired by hand instead of through `reactive-area` itself. See
[`scene-and-app.md`](scene-and-app.md)'s "What `reactive-area` actually
is" section for the full status note.

## `"render"`'s `foreign-callable` declares two arguments; GTK4 passes three

Verified directly against the source, not restated from a plan. From
`gtk.clj`'s `gl-area-apply!` (the block wiring `:on-render`):

```clojure
(when (and on-render (wire-once! area :on-render))
  (connect! area "render"
            (ffi/foreign-callable (fn [a _] (on-render a) 1)
                                  [:pointer :pointer] :int :collect-safe)))
```

The `foreign-callable` call declares its argument-type vector as
`[:pointer :pointer]` — two arguments — and the function value receives
exactly two positional parameters, `a` (the widget) and `_` (discarded).
GTK4's real `"render"` signal is `gboolean render(GtkGLArea*,
GdkGLContext*, gpointer)` — three arguments, the middle one a
`GdkGLContext*` this callback never reads. Inherited unmodified from
glimmer-gl's own file; see
[`gl-area-widget-layer.md`](gl-area-widget-layer.md) for the same finding
in the context of `:gl-area`'s other non-standard signal shapes
(`"resize"`'s extra `int` arguments, `on-tick`'s frame-clock callback
that isn't a signal at all).

**Why this is harmless, not merely "hasn't broken yet."** The declared
type list only shapes this one callback's own entry stub; GTK's
signal-emission code is what decides how many arguments to pass, and it
passes exactly what the real `"render"` signal always passes — three —
regardless of what the callback declares it wants. On the C calling
conventions this project's native targets use, the caller places
arguments into fixed positions (registers, then the stack) before the
call happens, and the callee reads however many of those positions its
own declared signature asks for. A callback that reads only the first
two never touches whatever position the third argument landed in:
nothing is misread and nothing shifts, because the widget pointer is
still argument 1 and the discarded user-data pointer is still argument 2
on both sides. The only real cost is informational — this callback
could read the `GdkGLContext*` (to assert the expected context is
current, say) and currently doesn't.

## `:gl-area`'s dev-time hiccup warning cannot be silenced from application code

`:gl-area`'s `:on-realize`/`:on-render`/`:on-resize`/`:on-tick`/etc. prop
keys all start with the two characters `"on"` — exactly the pattern
`glitter.core`'s ported-from-Replicant hiccup validation flags as a
probable `:on {}` mistake, printing a warning once per flagged key, per
render. The warning is purely cosmetic: `glitter.widget/apply-props!`
still receives and applies the value correctly regardless (see
[`gl-area-widget-layer.md`](gl-area-widget-layer.md)) — but for a GL
scene re-rendering every frame, that's a lot of console noise, and there
is no way to exempt one widget's non-event `on-*` props from the check.

`glitter.env/configure!` — `(configure! :glitter/asserts? false)` —
looks like the obvious escape hatch, and **it genuinely works**, but
only if it runs before `glitter.core` is first `require`d anywhere in
the process. No application namespace's own `-main` can arrange that on
its own.

**Why:** `glitter.assert`'s `enter-node`/`assert` are macros, and the
`(when (assert? ) ...)` gate inside them runs at MACROEXPANSION time —
once, when `glitter.core` itself is compiled — not per-call at runtime.
Any namespace that `:require`s `glitter.app`/`glitter.gtk` (both pull in
`glitter.core` transitively) triggers that compilation while processing
its own `ns` form, before a single line of that file's own code —
`-main` included — ever runs. By the time `-main` calls `configure!`,
`glitter.core`'s macros have already expanded with asserts baked in.

**Verified live, not just read off the source and assumed:** a two-line
probe — `(configure! ...)` followed by `(require '[glitter.core])` —
confirms the ordering directly. Calling `configure!` first and only then
requiring `glitter.core` leaves `glitter.assert/assert?` reporting
`false` afterward, exactly as asked. Calling them in the only order a
single namespace's own `-main` can actually achieve — its own `ns`
form's `:require` of `glitter.app`/`glitter.gtk` has already run before
`-main` exists to call anything — leaves `assert?` at its default
`true`: the config change never gets a chance to land before the macros
have already expanded.

**Why left as-is:** the only way to make `configure!` actually take
effect is a genuinely separate bootstrap namespace — one that requires
*only* `glitter.env`, calls `configure!` first, and only then
dynamically requires the real application namespace, so
`glitter.core`'s compilation happens after the config change instead of
before. Neither `plasma.clj` nor `gl_area_smoke.clj` ships one; the
added complexity of a second entry-point file per app wasn't judged
worth it for a cosmetic wart. Both examples' own comments near `-main`
acknowledge the same tradeoff. Full mechanics:
[`gl-area-widget-layer.md`](gl-area-widget-layer.md).

## `:scale` is deliberately not registered

`glimmer-gl.gtk` ships its own `:scale` widget. `glitter-gl.gtk` does
not port it — deliberately, invariant #6 in
[`CONTRIBUTING.md`](../../CONTRIBUTING.md): glitter already has a
richer, first-party native `:scale` (min/max/step/value/digits/
draw-value, with `:on-value-changed` already wired through glitter's own
standard signals table), and porting glimmer-gl's version alongside it
would silently conflict with, or shadow, the one glitter already
provides.

This is a scope decision, not a gap — but it belongs on this page
because a reader porting glimmer-gl application code will go looking for
`glitter-gl.gtk`'s `:scale` and not find it. If that's you: `:scale` is
registered by glitter itself, not by this library, and it already
covers what glimmer-gl's version does, plus more.

`examples/glitter_gl/check.clj` asserts `:scale` is registered — by
glitter, not by glitter-gl — as a load-order sanity check that this
project doesn't accidentally shadow it in some future change.

## See also

- [`gl-area-widget-layer.md`](gl-area-widget-layer.md) — the `:gl-area`
  widget in depth, including the `"render"`/`"resize"`/`on-tick` signal
  shapes and the full dev-time-warning trace.
- [`scene-and-app.md`](scene-and-app.md) — `reactive-area`'s design and
  its "honest status" note.
- [`examples.md`](examples.md) — why the shipped `plasma` demo wires
  `:gl-area` directly rather than through `reactive-area`.
- [`CONTRIBUTING.md`](../../CONTRIBUTING.md) (repo root) — the ten
  numbered invariants this project does not regress.
