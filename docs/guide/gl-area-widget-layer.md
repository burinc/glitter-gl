# The `:gl-area` widget layer

`glitter-gl.gtk` registers exactly one hiccup tag, `:gl-area`, into
glitter's widget registry (`glitter.widget/register-widget!`). This page
covers its mechanics: how its handlers actually get wired (a real,
live-found correction to the original port design), and the GTK4 signal
shapes it has to handle that don't fit glitter's uniform
`void(widget,data)` path.

```clojure
[:gl-area {:version [3 2] :depth-buffer true :hexpand true :vexpand true
           :on-realize (fn [area] ...)
           :on-render  (fn [area] ...)
           :on-resize  (fn [area w h] ...)
           :on-tick    (fn [area] ...)
           :on-motion  (fn [area x y] ...)
           :on-key     (fn [area keyval pressed?] ...)
           :on-button  (fn [area btn pressed? x y] ...)}]
```

## Why `:apply`, not `:connect` — a corrected design

`glitter.widget/register-widget!`'s own docstring describes a `:connect`
key on a widget spec: *"the optional `:connect` runs at `create!` time
after the generic `:on-*` wiring, for widgets whose signals don't fit the
uniform `void(widget,data)` shape (e.g. a `GtkGLArea`'s
realize/render/resize)."* `:gl-area` is that docstring's own motivating
example — so the original port design (see the design spec's
`glitter-gl.gtk` section) naturally called for registering `:gl-area` with
a `:connect` closure that wires realize/render/resize/tick once, at
construction.

**This does not work, and the reason is specific to how `glitter.core`
actually drives widget creation.** `:connect` genuinely runs — the bug
isn't in `:connect` itself — but it never sees any of `:gl-area`'s real
props, so it has nothing to wire.

### Tracing why

`glitter.core/create-node` is the only place `IRender/create-element` is
ever called, and it passes almost nothing:

```clojure
;; glitter.core/create-node
(let [tag-name (hiccup/tag-name headers)
      ns (get-ns headers)
      node (r/create-element renderer tag-name (when ns {:ns ns}))
      [attrs mounting-attrs] (get-mounting-attrs headers)
      _ (set-attributes renderer node (or mounting-attrs attrs))
      ...]
  ...)
```

The `options` argument `create-element` receives is `(when ns {:ns ns})`
— an XML-namespace hint for SVG-style tags, `nil` for everything else.
**Never** the hiccup element's own attribute map. `glitter.gtk`'s
`create-element` passes that same near-empty map straight through to
`glitter.widget/create!`:

```clojure
;; glitter.gtk
(create-element [_ tag-name options]
  (let [tag (keyword tag-name)
        widget (w/create! tag (or options {}))]
    ...))

;; glitter.widget
(defn create! [tag props]
  (let [props (with-orientation tag props)
        s (spec-for! tag)
        widget ((:ctor s) props)]
    ((:apply s) widget props)
    (apply-widget-props! widget props)
    (connect-signals! tag widget props)
    (when-let [connect (:connect s)] (connect widget props))  ; <- props here
    widget))
```

`:connect`'s own `props` argument is exactly the `options` value threaded
all the way from `create-node` — at most `{:ns "..."}`, never
`:on-realize`/`:on-render`/etc. A `:connect` closure registered for
`:gl-area` runs, once, with an empty (or near-empty) prop map, finds none
of the handler keys it's looking for, and does nothing. Silently — no
exception, no warning, just a `GtkGLArea` that never realizes.

The real props arrive through a **separate** path, right after
`create-element` returns: `create-node`'s own `(set-attributes renderer
node (or mounting-attrs attrs))` call. `set-attributes` iterates the
attribute map and calls `set-attr` **once per key**, which — for any key
that isn't `:style`/`:classes`/`:on` — calls `set-attr-val`, which
ultimately routes through `glitter.widget/apply-props!` to the widget
spec's `:apply` closure. `:apply` runs once per prop key, both at
construction and on every re-render — `glitter.gtk`'s `set-attribute`
(the only caller of `apply-props!` outside `create!` itself) always
passes a single-key map, `{(keyword a) v}`, never the full prop map;
`apply-props!`'s own docstring confirms this is exactly the shape it's
designed for ("safe to call with a single-key partial map"). This is the
exact same path `:scale`'s `:apply` already relies on to re-range its
min/max/step on every render (its `:ctor` doesn't see real props either)
— found live while tracing this, and cited directly as precedent in
`gtk.clj`'s own `CORRECTION` comment.

So: **`:apply` is the only widget-spec closure that ever sees a hiccup
element's real prop values, at any point in its lifecycle.** `:connect`
runs at the right *time* (once, at construction) but with the wrong
*data* (never the real props) — it's not a usable extension point for
wiring event handlers under glitter's actual reconcile flow, regardless
of what its docstring promises. This was found live via Task 17's smoke:
the first version of `:gl-area`, wired through `:connect` exactly as the
design spec prescribed, passed every unit test (none of which mount a
real widget tree) and then never fired `:on-realize` when actually run
against GTK.

### The fix: wire from `:apply`, guarded idempotent

```clojure
(defonce ^:private wired (atom {}))

(defn- wire-once!
  "True the FIRST time `event` is seen for `area`; false (and no side
  effect) on any repeat call."
  [area event]
  (let [seen (get @wired area #{})]
    (when-not (contains? seen event)
      (swap! wired update area (fnil conj #{}) event)
      true)))

(defn- gl-area-apply! [area props]
  (let [{:keys [on-realize on-render ...]} props]
    (when (and on-realize (wire-once! area :on-realize))
      (connect! area "realize" ...))
    ...))
```

`:apply` can run several times over a `:gl-area`'s life — once per prop
key, both at construction and on every re-render (never with the whole
prop map in one call) — but each signal must only ever be connected
**once**;
reconnecting `"realize"` on every render would pile up duplicate
callbacks, each firing independently. `wire-once!` gates every branch on
`[area event]`, so `gl-area-apply!` is safe to call as many times as
`:apply` actually is, while each underlying `g_signal_connect_data` (or
`gtk_widget_add_tick_callback`) call happens exactly once per widget per
event.

**Known v1 limitation, found during review, not fixed:** `wired` is keyed
by the raw `GtkGLArea` pointer — a plain machine address — with no
release path when a widget is destroyed. If GTK/GLib ever reuses a freed
`:gl-area`'s address for a brand-new widget, the new widget would
silently inherit the old one's `wired` entries and never get its handlers
connected — no exception, just a GL area that never realizes. `glitter.gtk`'s
own `memory` atom sidesteps this exact trap by keying off a tracking
atom's Clojure identity instead of a raw pointer; that pattern isn't
available here without changing `glitter.widget`'s `:apply` contract to
pass a stable identity alongside the raw widget pointer, which is out of
scope for this fix. Not currently a live risk — every call site in this
project mounts one `:gl-area` for the app's lifetime — but revisit if a
future task introduces dynamic `:gl-area` mount/unmount.

**What this means for extending `:gl-area`, or registering a different
custom widget with non-standard signals (here or in glitter itself):
wire from `:apply`, guarded idempotent, never from `:connect`.**

## Signal shapes that don't fit the uniform `void(widget,data)` path

Almost every GTK signal glitter's own `set-event-handler` connects is
`void(widget, user_data)`. `:gl-area` needs several shapes that don't
match, wired directly by `glitter-gl.gtk` itself (it does not go through
`glitter.widget/signals`/`signal-value` at all — every `:gl-area` handler
is application-supplied, not routed through glitter's action-dispatch
system; see `AGENTS.md`'s gotcha #4 for why).

**`"render"` — non-void return.** `GtkGLArea`'s `"render"` signal is
`gboolean render(GtkGLArea*, GdkGLContext*, gpointer)`. glitter-gl's
`foreign-callable` declares it as `[:pointer :pointer] :void` — 2 args
(the extra `GdkGLContext*` is simply not read, a harmless simplification
inherited unchanged from glimmer-gl's own file) — but with an **`:int`**
return, not `:void`:

```clojure
(ffi/foreign-callable (fn [a _] (on-render a) 1)
                      [:pointer :pointer] :int :collect-safe)
```

Always returning `1` (`TRUE`) tells GTK the draw call is handled and
nothing further needs to run.

**`"resize"` — extra `int` arguments.** `GtkGLArea`'s `"resize"` signal
carries the new width/height directly as signal arguments:
`void resize(GtkGLArea*, gint width, gint height, gpointer)` — 4
parameters, not 2:

```clojure
(ffi/foreign-callable (fn [a w h _] (on-resize a w h))
                      [:pointer :int :int :pointer] :void :collect-safe)
```

**`"realize"` is the one signal here that IS the standard shape** —
`void realize(GtkGLArea*, gpointer)` — wired exactly like any other
glitter signal.

**`on-tick` isn't a GTK *signal* at all.** There is no
`"tick"`/`"frame"` signal on `GtkGLArea`; frame-synced callbacks go
through a completely separate GTK API, `gtk_widget_add_tick_callback`,
which registers directly against the widget's `GdkFrameClock` rather than
via `g_signal_connect_data`:

```clojure
(gtk-widget-add-tick-callback area
  (let [cb (ffi/foreign-callable
            (fn [a _clock _data] (on-tick a) (queue-render a) 1)
            [:pointer :pointer :pointer] :int :collect-safe)]
    (w/retain-callable! cb) cb)
  ffi/null ffi/null)
```

The callback signature is `gboolean callback(GtkWidget*, GdkFrameClock*,
gpointer)`; returning `1` (`GDK_SOURCE_CONTINUE`, aliased through
`gboolean`'s ABI) keeps the tick firing on every subsequent frame, `0`
would cancel it. `on-tick` always queues a render immediately after,
so an app using it gets a continuous render loop for free without having
to call `queue-render` itself.

**`on-motion` layers a `GtkEventControllerMotion` onto the area**, since
`GtkGLArea` itself has no pointer-motion signal — controllers are GTK4's
mechanism for attaching extra input handling to any widget:

```clojure
(let [ctl (gtk-event-controller-motion-new)]
  (gtk-widget-add-controller area ctl)
  (connect! ctl "motion"
            (ffi/foreign-callable (fn [_ x y _] (on-motion area (double x) (double y)))
                                  [:pointer :double :double :pointer] :void :collect-safe)))
```

`"motion"`'s real signature is `void motion(GtkEventControllerMotion*,
gdouble x, gdouble y, gpointer)` — the two `gdouble` coordinates arrive
as direct signal arguments, same shape class as `"resize"`'s extra ints
but with `:double` instead of `:int`.

**`on-key` needs a controller on the *root window*, not the GLArea
itself** — `GtkGLArea` can't hold keyboard focus
(`gtk_widget_grab_focus` returns `FALSE` even with `:can-focus` set), so
a `GtkEventControllerKey` attached directly to the area never receives
key events. The fix wires a small self-arming handler onto `:gl-area`'s
OWN `"realize"` signal (a second, independent `"realize"` connection,
alongside whatever the caller's own `:on-realize` does) that looks up the
root window via `gtk_widget_get_root` — which only resolves once the
widget is actually realized and attached to a window — and attaches the
key controller there, once:

```clojure
(let [armed? (atom false)
      arm (ffi/foreign-callable
           (fn [_area _]
             (when-not @armed?
               (reset! armed? true)
               (let [root (gtk-widget-get-root area)
                     ctl  (gtk-event-controller-key-new)]
                 (when-not (ffi/null? root)
                   (gtk-widget-add-controller root ctl)
                   (connect! ctl "key-pressed" ...)
                   (connect! ctl "key-released" ...)))))
           [:pointer :pointer] :void :collect-safe)]
  (connect! area "realize" arm))
```

`"key-pressed"`'s real signature is `gboolean key_pressed
(GtkEventControllerKey*, guint keyval, guint keycode, GdkModifierType
state, gpointer)` — 4 args, **non-void return** (a third distinct
callable shape in this file, alongside `"render"`'s 2-arg/non-void and
`"resize"`'s 4-arg/void):

```clojure
(ffi/foreign-callable (fn [_ kv _kc _st _] (on-key area (int kv) true) 0)
                      [:pointer :uint :uint :uint :pointer] :int :collect-safe)
```

Returning `0` (`GDK_EVENT_PROPAGATE`) lets the key event continue past
this handler to any other consumer (e.g. GTK's own focus-navigation
keys). `"key-released"` carries the identical 4 arguments but IS
`void`-returning — the pressed/released pair is asymmetric in GTK4's own
API, not a glitter-gl inconsistency.

**`on-button` layers a `GtkGestureClick`**, GTK4's gesture-based mouse
button API, the same controller-attachment pattern as `on-motion`:

```clojure
(let [g (gtk-gesture-click-new)]
  (gtk-widget-add-controller area g)
  (connect! g "pressed"
            (ffi/foreign-callable (fn [_ _n x y _] (on-button area 1 true (double x) (double y)))
                                  [:pointer :int :double :double :pointer] :void :collect-safe))
  (connect! g "released" ...))
```

`"pressed"`/`"released"`'s real signature is `void pressed
(GtkGestureClick*, gint n_press, gdouble x, gdouble y, gpointer)` — 5
args, void return; the `n_press` (click-count) argument is read but
discarded, and `on-button` is always called reporting button `1` (no
button-index disambiguation in this version).

## Summary: every non-standard shape in this file

| Signal / mechanism | Args | Return | Notes |
|---|---|---|---|
| `"realize"` | 2 (standard) | void | The one signal that fits glitter's default shape |
| `"render"` | 2 | **int** | Non-void return; always returns 1 |
| `"resize"` | **4** | void | Width/height as direct int args |
| tick callback | 3 (not a signal — `gtk_widget_add_tick_callback`) | **int** | Frame-clock API, not `g_signal_connect_data`; returns 1 to keep ticking |
| `"motion"` (on a controller) | **4** | void | x/y as direct double args |
| `"key-pressed"` (on a controller, attached to root) | **5** | **int** | Non-void; returns 0 to propagate |
| `"key-released"` (same controller) | **5** | void | Same args as key-pressed, void return |
| `"pressed"`/`"released"` (on a `GtkGestureClick`) | **5** | void | n_press + x/y as direct args |

Every one of these is wired with its own literal `foreign-callable` call
inside `gl-area-apply!` — `jolt.ffi/foreign-callable`'s `argtypes`/
`rettype` must be compile-time literals (the same constraint glitter's
own `:switch`/`:list-box` signal generalization hit and documented in its
own `docs/guide/gtk-widget-layer.md`), so there is no data-driven table
here either; adding a ninth `:gl-area` handler with yet another shape
means adding its own literal branch by hand.
