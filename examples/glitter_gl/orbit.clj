(ns glitter-gl.orbit
  "glitter-gl demo: several distinct solids orbiting a lit, shadowed ground
  plane, mounted through glitter-gl.app/reactive-area rather than direct
  :gl-area wiring.

  This is the first live exercise of glitter-gl.scene + glitter-gl.app +
  glitter-gl.renderer: docs/guide/limitations.md records that reactive-area
  is unit-tested (app_test.clj asserts the prop map it returns) but has
  never been mounted against a real :gl-area. Every other example
  (plasma.clj, ripple.clj) wires :gl-area directly instead.

  Six solids (glitter-gl.primitives' cuboid/sphere/tetrahedron plus
  glitter-gl.polyhedra's octahedron/icosahedron/dodecahedron) orbit the
  origin at their own radius, phase, and orbit speed, most also spinning on
  their own axis. A single directional light casts shadows from the
  orbiting solids onto a ground quad (glitter-gl.primitives/plane, rotated
  flat) below them.

  Per CONTRIBUTING.md invariant #9 and glitter-gl.app/reactive-area's own
  docstring, the returned :gl-area prop map is built exactly once, as a
  top-level def, and `view` below always splices that same map: a later
  render supplying a fresh reactive-area call would be a silent no-op for
  every handler, since :gl-area only ever wires the first closure it sees
  per event.

  Orbit state (:t, advanced by :on-tick) lives in glitter's own shared
  `state` atom rather than a private plain atom, by design: scene-fn
  must be a genuine function of `state`, not of a closed-over mutable,
  the way plasma.clj's/ripple.clj's GL-plumbing atoms are. That
  means every tick's `swap!` also fires glitter.gtk/mount!'s state-atom
  watcher and re-runs the whole `view` -> reconcile path, not just the GL
  render loop, unlike plasma/ripple, which advance a private `clock` atom
  outside `state` for exactly this reason."
  (:require [glitter-gl.app        :as gl-app]
            [glitter-gl.matrix     :as m]
            [glitter-gl.polyhedra  :as poly]
            [glitter-gl.primitives :as p]
            [glitter-gl.scene      :as scene]
            [glitter.app           :as app]
            [glitter.core          :as core]
            [glitter.gtk           :as gtk]))

;; --- shared app state: glitter's ONE atom. :t is the only mutable field, and
;; it drives every solid's orbit/spin phase analytically (see solid-node) so
;; scene-fn stays a pure function of state, never a closure over a private
;; clock atom -----------------------------------------------------------------
(defonce state (atom {:t 0.0}))

(def ^:private frame-dt 0.016)

;; --- geometry, built once (Mesh is a value-equal defrecord; renderer/
;; ensure-meshes! caches uploads keyed on it, so every orbiting copy of the
;; SAME solid still shares one VAO -- only the six meshes below and the
;; ground ever get uploaded, no matter how many frames render) ---------------
(def ^:private ground-mesh (p/plane 26.0 1))

;; [geom material orbit-radius phase orbit-speed spin-speed], one row per
;; solid. Phases are spread around the circle so the six never bunch up;
;; orbit/spin speeds are irrational-ish multiples of each other so the
;; whole arrangement never repeats into a static-looking pose.
(def ^:private solids
  [{:geom (p/cuboid 1.1)
    :material :cuboid
    :radius 3.0
    :phase 0.0
    :orbit-speed 0.55
    :spin-speed 1.3}
   {:geom (p/sphere 0.75 22 16)
    :material :sphere
    :radius 4.4
    :phase (/ Math/PI 3.0)
    :orbit-speed 0.35
    :spin-speed 0.0}
   {:geom (p/tetrahedron 1.0)
    :material :tetra
    :radius 2.1
    :phase (/ Math/PI 1.4)
    :orbit-speed 0.85
    :spin-speed 1.9}
   {:geom (poly/octahedron 0.85)
    :material :octa
    :radius 5.4
    :phase Math/PI
    :orbit-speed 0.28
    :spin-speed 1.05}
   {:geom (poly/icosahedron 0.85)
    :material :icosa
    :radius 3.7
    :phase (* 1.65 Math/PI)
    :orbit-speed 0.48
    :spin-speed -1.15}
   {:geom (poly/dodecahedron 0.9)
    :material :dodeca
    :radius 6.2
    :phase (/ Math/PI 5.0)
    :orbit-speed 0.22
    :spin-speed 0.6}])

;; Six visually distinct colors (one per solid) plus the ground, fully
;; replacing renderer/material-colors for this demo -- reactive-area's
;; :materials opt is the whole palette, not a delta merged onto the default.
(def ^:private materials
  {:cuboid  [0.85 0.62 0.20]     ; amber
   :sphere  [0.28 0.55 0.85]     ; sky blue
   :tetra   [0.78 0.24 0.24]     ; brick red
   :octa    [0.24 0.62 0.42]     ; jade
   :icosa   [0.55 0.42 0.78]     ; violet
   :dodeca  [0.82 0.78 0.30]     ; gold-green
   :ground  [0.13 0.13 0.16]})  ; near-black slate

;; --- scene ---------------------------------------------------------------
;; One solid's node: spin about its own center, translate out to its orbit
;; radius (and up, above the ground), then revolve that translated position
;; around the world Y axis. Nesting order matters (walk composes transforms
;; parent-then-child): spin is innermost so it rotates the mesh's own local
;; vertices before the translate/orbit groups move it, exactly the
;; "rotate in place, then swing around a pivot" composition.
(defn- solid-node [t {:keys [geom material radius phase orbit-speed spin-speed]}]
  (scene/group (m/rotate-y (+ phase (* t orbit-speed)))
               (scene/group (m/translation radius 1.3 0.0)
                            (scene/group (m/rotate-y (* t spin-speed))
                                         (scene/mesh {:geom geom
                                                      :material material})))))

;; The ground never orbits: a single flat quad, rotated from primitives/
;; plane's native XY-facing-+Z orientation down into the XZ plane (normal
;; +Y) so it reads as a floor under an elevated camera. cast-shadow false --
;; it should receive the orbiting solids' shadows, not cast one of its own.
(def ^:private ground-node
  (scene/group (m/rotate-x (- (/ Math/PI 2.0)))
               (scene/mesh {:geom ground-mesh
                            :material :ground
                            :cast-shadow false})))

(defn- scene-fn
  "Pure (fn [state] -> scene tree): one camera, one light, the static ground,
  and the six solids positioned from `t` alone. Called fresh every render,
  per scene.clj's/app.clj's no-reactive-cell design -- there is nothing to
  memoize here, `solids`/`ground-mesh` are already built once above."
  [state]
  (let [t (double (:t state 0.0))]
    (apply scene/group (m/ident)
           (scene/camera {:eye [0.0 9.0 15.0]
                          :target [0.0 0.9 0.0]
                          :up [0.0 1.0 0.0]
                          :fov 48.0
                          :aspect 1.6
                          :near 0.1
                          :far 100.0})
           (scene/light {:dir [-0.45 -1.0 -0.3]
                         :color [1.0 0.96 0.88]})
           ground-node
           (map (partial solid-node t) solids))))

;; --- mount, once ------------------------------------------------------------
;; Built exactly once, at namespace load: see the ns docstring and
;; CONTRIBUTING.md invariant #9. `view` below always splices this SAME map.
(def ^:private gl-area-props
  (gl-app/reactive-area state scene-fn
                        {:bg [0.04 0.05 0.08]
                         :ambient 0.16
                         :materials materials
                         :on-tick (fn [_area] (swap! state update :t + frame-dt))}))

(defn view [_state]
  [:box {:spacing 0}
   [:gl-area gl-area-props]])

(core/set-dispatch! (fn [_event _actions] nil))

(defn -main [& _]
  ;; :gl-area's :on-* props trip a cosmetic dev-time hiccup warning on every
  ;; render (glitter.core flags any prop key starting with "on" as a
  ;; probable :on {} mistake); harmless, no practical way to silence it from
  ;; this namespace -- see CONTRIBUTING.md invariant #10. plasma.clj and
  ;; ripple.clj carry the same warning.
  ;; GLITTER_GL_DEMO_QUIT_MS auto-closes the window after N ms (smoke
  ;; testing); unset, the window stays open until closed.
  (let [quit-ms (some-> (System/getenv "GLITTER_GL_DEMO_QUIT_MS") Integer/parseInt)]
    (apply app/run (fn [window] (gtk/mount! window view state))
           :app-id "glitter-gl.orbit"
           :title  "glitter-gl - orbit"
           :width  960 :height 620
           (when quit-ms [:auto-quit-ms quit-ms]))))
