# glitter-gl — Guide

## Why this exists

`glitter-gl` is glimmer-gl's geometry/matrix/shader/GL library, ported
onto [glitter](https://github.com/burinc/glitter) instead of
[glimmer](https://github.com/jolt-lang/glimmer). Most of it (vectors,
matrices, meshes, the shader DSL, raw GL bindings, the renderer) has no
dependency on either UI library at all and ports across unchanged. The
part that does — a `:gl-area` widget so a GL pane can live inside a
glitter hiccup tree — needed real adaptation, and hit a real, live-found
correction along the way. This guide covers that adaptation.

## What glitter-gl is

A `.clj` (Jolt/Chez Scheme host, not JVM) library, split into two halves:

- Pure geometry/matrix/mesh/shader/GL code — usable from any Jolt program
  with an OpenGL context, glitter or not.
- `glitter-gl.gtk`/`.scene`/`.app` — the glitter-specific layer: a
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
- **This page** — the guide map.

### GTK integration
- [`gl-area-widget-layer.md`](gl-area-widget-layer.md) — the `:gl-area`
  widget in depth: why its handlers wire from the widget spec's `:apply`
  closure rather than glitter.widget's `:connect` hook (a real correction
  to the original design, found live), and the signal shapes that don't
  fit glitter's uniform `void(widget,data)` path — `"render"`'s non-void
  return, `"resize"`'s extra int arguments, `on-tick`'s separate
  `gtk_widget_add_tick_callback` path (not a GTK signal at all), and the
  motion/key/button controllers layered on top.

### See also
- `AGENTS.md` (repo root) — canonical agent context: build/run commands,
  file map, and the full numbered list of conventions & gotchas (the
  `:gl-area` correction is gotcha #2 there, in summary form).
- `NOTICE.md` (repo root) — the file-by-file attribution ledger and
  porting summary (verbatim / adapted / new buckets).
- [glimmer-gl](https://github.com/jolt-lang/glimmer-gl) — the library
  this project ports from.
- [glitter](https://github.com/burinc/glitter) — the renderer this
  project extends; see its own `docs/guide/` for the reconcile →
  `IRender`/`IMemory` architecture and the GTK widget layer this
  project's `:gl-area` plugs into.
