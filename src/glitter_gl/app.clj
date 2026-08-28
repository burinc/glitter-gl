(ns glitter-gl.app
  "Reactive mount point for a glitter-gl scene — the layer that keeps GL out
  of application code.

  This is the GL analogue of glitter's top-level mount: the app supplies a
  scene component (a pure (fn [state] -> hiccup), exactly like glitter's own
  view fn) and a few render-environment values, and gets back a `:gl-area`
  widget prop map whose lifecycle callbacks are owned by the library.

  Ported from glimmer-gl.app (see NOTICE.md), redesigned around glitter's
  state-atom model instead of glimmer's reactive cells: `reactive-area` now
  takes glitter's shared top-level `state` atom directly. Its installed
  :on-render closure derefs `state` fresh on every GTK-driven call and
  recomputes the scene plan from the current value — no dependency tracking
  needed, since GTK's frame clock already calls it as often as needed.
  :on-tick/:on-motion/:on-key/:on-button are plain closures that swap!/reset!
  the shared `state` atom directly, not routed through glitter.core's
  action-dispatch tuples — this mirrors glimmer-gl's shape closely (least new
  surface area) and matches existing precedent in glitter's own
  CONTRIBUTING.md (invariant #8), where value-bearing GTK signals already
  bypass the action-tuple system as a documented exception. See the design
  spec's 'glitter-gl.scene / glitter-gl.app' section for the full rationale.

  opts' values are now plain (no more 'may be a reactive cell' affordance) —
  but this is NOT the same as saying opts/closures get re-read on every
  render. `:gl-area`'s handler props (`:on-realize`/`:on-render`/etc.) wire
  through the widget spec's `:apply` closure, guarded idempotent per
  `[area event]` (see gtk.clj's `wired` atom): each event only ever connects
  the FIRST closure it sees for a given `:gl-area` widget, and every later
  `:apply` call for that same event is a no-op, no matter what closure it
  carries. So `reactive-area` must be called exactly ONCE per `:gl-area`
  mount point — its returned prop map handed to a single, stable
  `[:gl-area ...]` hiccup position (built once outside the view fn, or the
  view fn returning the SAME `reactive-area` call's result across renders)
  — not called fresh on every render expecting new opts/closures to take
  over; they will not. `opts`' values are captured once, at that single
  call. `:on-render`'s own body is unaffected by this: it derefs `state`
  fresh on every GTK-driven call regardless, since that deref happens
  inside the one closure that DID get wired, not by re-wiring a new one.
  Full mechanics: docs/guide/gl-area-widget-layer.md."
  (:require [glitter-gl.gl       :as gl]
            [glitter-gl.gtk      :as gtk]
            [glitter-gl.matrix   :as m]
            [glitter-gl.renderer :as renderer]
            [glitter-gl.scene    :as scene]))

;; Shadow frustum for a directional light. The light node carries the direction
;; the light travels (`:dir`) plus optional frustum tuning; `target` is the scene
;; center the camera looks at. The light's eye sits back along -dir from target
;; and looks at it through an ortho box. Returns {:lview :lproj}.
(defn- light-frustum [{:keys [dir eye-dist bounds near far]} target]
  (let [ed (double (or eye-dist 40.0))
        b  (double (or bounds 30.0))
        n  (double (or near 1.0))
        f  (double (or far 150.0))
        mag (Math/sqrt (+ (* (nth dir 0) (nth dir 0))
                          (* (nth dir 1) (nth dir 1))
                          (* (nth dir 2) (nth dir 2))))
        inv (if (zero? mag) 0.0 (/ 1.0 mag))
        dx (* (nth dir 0) inv)
        dy (* (nth dir 1) inv)
        dz (* (nth dir 2) inv)
        leye [(+ (nth target 0) (* (- dx) ed))
              (+ (nth target 1) (* (- dy) ed))
              (+ (nth target 2) (* (- dz) ed))]]
    {:lview (m/look-at leye target [0.0 1.0 0.0])
     :lproj (m/ortho (- b) b (- b) b n f)}))

(def ^:private keyval-kw
  "GDK keyval -> movement keyword."
  {;; arrows
   0xff52 :up,
   0xff54 :down,
   0xff51 :left,
   0xff53 :right
   ;; w a (lower/upper)
   0x77 :w,
   0x57 :w,
   0x61 :a,
   0x41 :a
   ;; s d
   0x73 :s,
   0x53 :s,
   0x64 :d,
   0x44 :d
   0xff1b :escape,
   0x20 :space,
   0xff0d :enter})

(defn keyval->kw
  "Map a GDK keyval to a movement-friendly keyword, or nil when not one we
  care about."
  [keyval]
  (keyval-kw (long keyval)))

(defn reactive-area
  "Return a `:gl-area` widget prop map that mounts `scene-fn` behind a
  lib-owned GL render loop, driven by glitter's shared top-level `state`
  atom.

  scene-fn is a (fn [state] -> hiccup scene tree) (one `:camera`, one or more
  `:light`, and geometry) — a pure function of `state`, exactly like
  glitter's own view fn. It is called fresh on every GTK-driven render; there
  is no dependency tracking to short-circuit.

  opts (plain values — no reactive-cell affordance):
    :bg [r g b]              clear color (default near-black)
    :ambient float|[r g b]   ambient term (scalar -> [s s s])
    :shadow-bias float       shadow depth bias (default 0.002)
    :fog {:near :far :color} distance fog
    :materials {}            material-kw -> [r g b] (defaults to the renderer's)
    :depth-spec/:lit-spec    alternative shader specs (default: shadow + Blinn-Phong)
    :version [maj min]       GL version required (default [3 2])
    :depth-buffer bool       request a depth buffer (default true)
    :on-tick (fn [area])     optional app animation policy; runs each frame
                             before render. Mutate state-driving cells here.
    :on-motion (fn [nx ny])  optional pointer-move handler; nx,ny are the
                             pointer position normalized to [-1,1] across the
                             GL area (origin at centre).
    :on-key (fn [kw pressed?])  optional keyboard handler; kw is a stable
                                keyword — see keyval->kw.
    :on-button (fn [pressed?])  optional mouse-button handler."
  ([state scene-fn] (reactive-area state scene-fn {}))
  ([state scene-fn opts]
   (let [st (atom nil)     ; renderer state, built on realize
         vp (atom [960 600])]
     {:version     (or (:version opts) [3 2])
      :depth-buffer (if (nil? (:depth-buffer opts)) true (:depth-buffer opts))
      :hexpand true
      :vexpand true
      :on-realize
      (fn [_area]
        (gl/gl-enable gl/GL-DEPTH-TEST)
        (gl/gl-enable gl/GL-CULL-FACE)
        (gl/gl-front-face gl/GL-CCW)
        (reset! st (renderer/make-renderer!
                    (select-keys opts [:depth-spec :lit-spec]))))
      :on-resize
      (fn [_area w h] (reset! vp [(max (long w) 1) (max (long h) 1)]))
      :on-tick
      (or (:on-tick opts) (fn [_area]))
      :on-motion
      (when-let [m (:on-motion opts)]
        (fn [area x y]
          (let [w (double (max (long (gtk/widget-width area)) 1))
                h (double (max (long (gtk/widget-height area)) 1))]
            (m (- (/ (* 2.0 (double x)) w) 1.0)
               (- (/ (* 2.0 (double y)) h) 1.0)))))
      :on-key
      (when-let [k (:on-key opts)]
        (fn [_area keyval pressed?]
          (when-let [kw (keyval->kw keyval)]
            (k kw pressed?))))
      :on-button
      (when-let [b (:on-button opts)]
        (fn [_area _btn pressed? _x _y] (b pressed?)))
      :on-render
      (fn [_area]
        (when-let [s @st]
          (let [current @state
                plan (scene/plan current scene-fn)]
            (when plan
              (let [cam    (:camera plan)
                    [cw ch] @vp
                    cam2   (scene/perspective-camera
                            (assoc cam :aspect (/ (double cw) (double ch))))
                    light  (first (:lights plan))
                    lf     (when light (light-frustum light (:target cam)))
                    bg     (or (:bg opts) [0.0 0.0 0.0])
                    amb    (or (:ambient opts) 0.1)
                    amb3   (if (number? amb) [amb amb amb] amb)
                    bias   (or (:shadow-bias opts) 0.002)
                    fog    (or (:fog opts) {:near 8.0
                                            :far 50.0
                                            :color [0.0 0.0 0.0]})]
                (renderer/draw! s
                                {:plan        plan
                                 :view        (:view cam2)
                                 :proj        (:proj cam2)
                                 :eye         (:eye cam2)
                                 :canvas      [cw ch]
                                 :bg          bg
                                 :light       (when light
                                                (assoc (select-keys light [:dir :color])
                                                       :lview (:lview lf) :lproj (:lproj lf)))
                                 :ambient     amb3
                                 :shadow-bias bias
                                 :fog         fog
                                 :materials   (:materials opts)}))))))})))
