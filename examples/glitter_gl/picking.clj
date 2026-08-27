(ns glitter-gl.picking
  "glitter-gl demo: a ground plane and a back wall, with a marker drawn at
  wherever the pointer's world-space ray hits them.

  Ported from thi.ng/geom's examples/gl/picking.cljs (the idea only; its
  ClojureScript/WebGL windowing does not transfer to glitter-gl's
  :gl-area). Every other example draws geometry that never reacts to
  input; this is the first to use pointer events at all, and the first
  consumer of glitter-gl.intersect (specifically its `ray-plane` fn)
  outside its own namespace and test suite.

  RENDER PATH: direct :gl-area wiring (like plasma.clj/ripple.clj/gears.clj/
  knot.clj/textured.clj), not glitter-gl.app/reactive-area -- two static
  planes and a marker is not a scene-graph problem.

  SCENE: a horizontal ground quad (glitter-gl.primitives/plane, rotated flat
  like orbit.clj's ground) and a vertical wall quad (the same primitive,
  unrotated -- primitives/plane's own normal already points +Z, so it needs
  only a translate to stand at the ground's far edge) facing the camera.
  Each is also a `glitter-gl.plane` record for the actual intersection math;
  the rendered quads are finite, the intersection planes are the same
  infinite mathematical planes the quads sit in. On every frame, the last
  pointer position is unprojected into a ray (`pointer-ray` below), tested
  against both planes with `intersect/ray-plane`, and a small sphere marker
  is drawn at the nearer hit -- colored one way for the ground, another for
  the wall, so the switch is visible without needing to read coordinates.

  Pointer-handling behavior, verified before writing this file, not
  assumed:
  1. :on-motion fires live through :gl-area -- this file's own operation
     is the confirmation. It does not wire :on-button at all; see
     docs/guide/limitations.md for what pointer coverage remains
     unexercised.
  2. Pointer coordinates are widget-relative doubles, origin TOP-LEFT, y
     increasing downward, straight from GTK4 with no transform applied
     (gtk.clj's :on-motion wiring: `(on-motion area (double x) (double y))`).
     `pointer-ray`'s y-flip (`ndc-y = 1 - 2*(y/h)`) converts that into GL's
     +y-up NDC and is correct as written for exactly this reason.
  3. Pointer coordinates can fall OUTSIDE the widget bounds once a button
     press starts a pointer grab (observed x up to 677 on a 600px-wide area,
     y as low as -308). DECISION: this example does not clamp. The NDC
     values below are allowed to fall outside [-1, 1]; the unprojected ray
     just points further off to the side than the visible frustum, and any
     plane hit is still computed and drawn even when it lands outside the
     rendered ground/wall quads' finite extent. Clamping the input would
     hide real pointer-grab behavior instead of demonstrating it.

  IMPORTANT -- verified by hand before writing this file, not assumed:
  `glitter-gl.matrix/transform-point` is documented as an AFFINE-only
  transform (assumes the input matrix's w row is the constant [0 0 0 1],
  true for model/view matrices, and drops the w computation instead of
  computing-then-dividing). `inv` below is the inverse of a *perspective*
  matrix product, whose w row is NOT constant -- it genuinely depends on
  the z component of whatever point you feed in. Unprojecting the near
  (ndc-z=-1) and far (ndc-z=1) clip corners via `transform-point` and
  subtracting, the way a first draft of this file did, silently discards
  that varying w and collapses every screen position onto the SAME ray
  direction: a standalone numeric check (an independent Python/numpy
  reimplementation of both paths against the same view/proj matrices this
  file uses) showed the naive direction identical across four different
  NDC coordinates, while the correct (divide-by-w) direction varied
  correctly with each one. `unproject` below does the real 4-component
  transform, including the divide: it re-derives the divide-by-w half of
  thi.ng.geom.matrix/unproject-point's 6-arity body (thi.ng/geom's
  src/thi/ng/geom/matrix.cljc; see NOTICE.md). The port that produced
  `matrix.clj` never carried that fn over, so there is nothing there to
  call. Promoting it into `matrix.clj` would be a library change, out of
  scope for this file; kept local here instead.

  IMPORTANT -- gtk.clj's :on-motion wiring calls the handler directly with
  no follow-up `queue-render` (unlike :on-tick, whose wiring calls
  `queue-render` right after every invocation). Storing the pointer
  position from :on-motion alone would update the atom but never repaint.
  `on-tick` below is therefore a deliberate no-op: it exists purely so
  gtk.clj's tick wiring keeps queuing a render every frame, and `on-render`
  re-reads whatever `pointer-pos` currently holds -- the same
  read-fresh-state-every-frame shape used throughout: do the raycast in
  on-render, not in the motion callback, so a fast pointer does not queue
  work per event.

  IMPORTANT -- found live, on a Retina display, by the first live-GTK smoke
  of this file (the marker rendered nowhere near the actual pointer): a
  standalone probe (`(gtk-gl-area \"resize\")` vs `gtk_widget_get_width`/
  `gtk_widget_get_height` vs a live :on-motion trace, same window) showed
  the GLArea's \"resize\" signal reports the framebuffer size in DEVICE
  PIXELS (1800x1200 on a 2x display for a 900x600 window -- correct for
  `gl/gl-viewport`, which needs real framebuffer pixels), while :on-motion's
  (x,y) are in LOGICAL POINTS (observed up to ~719 on that same 900-wide
  area, never near 1800). `@viewport`, populated from the resize signal, is
  therefore the wrong denominator for `pointer-ray`'s ndc-x/ndc-y -- it
  silently halves (on this 2x display) the effective NDC offset, which is
  enough to move the ray's target from the ground onto the wall or vice
  versa. `on-render` below gets `pointer-ray`'s w/h from
  `glx/widget-width`/`glx/widget-height` (gtk.clj's own `gtk_widget_get_*`
  wrappers -- logical, matching :on-motion) instead of `@viewport` (device
  pixels, kept for `gl/gl-viewport` and the aspect ratio, which is
  scale-invariant either way).

  Every uniform this shader declares (:u_mvp, :u_color) is set explicitly
  on every draw call below, including the marker's, which is drawn a
  variable number of times per frame (zero or one) -- see ripple.clj's ns
  docstring for why a shader-spec's [type default] pair is documentation
  only and never auto-uploaded."
  (:require [glitter-gl.gl :as gl]
            [glitter-gl.gtk :as glx]
            [glitter-gl.intersect :as isec]
            [glitter-gl.matrix :as m]
            [glitter-gl.mesh :as mesh]
            [glitter-gl.plane :as pl]
            [glitter-gl.primitives :as p]
            [glitter-gl.shader :as sh]
            [glitter-gl.vector :as v3]
            [glitter.app :as app]
            [glitter.core :as core]
            [glitter.gtk :as gtk]
            [jolt.ffi :as ffi]))

;; --- scene sizing / layout: single source for both the render-side model
;; matrices and the intersection-side plane records below, so the drawn
;; quads and the math planes they sit in cannot drift apart -----------------
(def ^:private ground-size   20.0)
(def ^:private wall-size     16.0)
(def ^:private wall-z        -10.0)  ; the wall stands at the ground's far edge
(def ^:private marker-radius 0.3)

;; --- camera: fixed, elevated, looking down and forward at the wall --------
(def ^:private eye-vec     [0.0 6.0 14.0])
(def ^:private target-vec  [0.0 3.0 -4.0])
(def ^:private up-vec      [0.0 1.0 0.0])
(def ^:private eye-pos     (apply v3/vec3 eye-vec))
(def ^:private view-matrix (m/look-at eye-vec target-vec up-vec))
(def ^:private fov-deg     55.0)
(def ^:private near-plane  0.1)
(def ^:private far-plane   100.0)

;; --- colors -----------------------------------------------------------------
(def ^:private ground-color        [0.16 0.17 0.20])
(def ^:private wall-color          [0.12 0.20 0.30])
(def ^:private marker-ground-color [1.0 0.55 0.15])   ; amber: hit the ground
(def ^:private marker-wall-color   [0.95 0.25 0.65])  ; magenta: hit the wall

;; --- intersection planes (glitter-gl.plane records, the math the pointer
;; ray is tested against) ------------------------------------------------
(def ^:private ground-plane (pl/plane (v3/vec3 0.0 1.0 0.0) 0.0))
(def ^:private wall-plane   (pl/plane (v3/vec3 0.0 0.0 1.0) (- wall-z)))

;; --- rendered geometry (built once; primitives/plane's own normal already
;; points +Z, which is exactly the wall's face-the-camera orientation, so
;; the wall needs only a translate -- the ground needs the same rotate-x
;; orbit.clj uses to lay that +Z-normal quad flat) --------------------------
(def ^:private ground-model (m/rotate-x (- (/ Math/PI 2.0))))
(def ^:private wall-model   (m/translation 0.0 (* 0.5 wall-size) wall-z))

(def ^:private ground-mesh (p/plane ground-size 1))
(def ^:private wall-mesh   (p/plane wall-size 1))
(def ^:private marker-mesh (p/sphere marker-radius 14 10))

;; --- the shader: a plain flat fill, no lighting -- mirrors gears.clj's
;; shader-spec exactly, just applied to 3D perspective instead of ortho -----
(def ^:private base
  {:version  "330 core"
   :uniforms {:u_mvp   :mat4
              :u_color [:vec3 ground-color]}
   :attribs  {:a_pos [:vec3 0]}
   :vs-main  [[:set :gl_Position [:* :u_mvp [:vec4 :a_pos 1.0]]]]})

(def ^:private main-module
  {:fs-out  {:frag :vec4}
   :fs-main [[:set :frag [:vec4 :u_color 1.0]]]})

(def shader-spec
  (sh/merge-specs base main-module))

;; --- GL-plumbing state (plain atoms, read/written directly by the :gl-area
;; handlers, like plasma.clj/ripple.clj/gears.clj/knot.clj: there is no
;; control panel here to dispatch through). No `clock` atom -- unlike those
;; four, nothing here animates on a timer; the only moving part is the
;; pointer, tracked in `pointer-pos` -----------------------------------------
(defonce ^:private pointer-pos (atom nil))  ; [x y] widget-relative px, or nil
(defonce ^:private viewport    (atom [900 600]))
(defonce ^:private gl-state    (atom {}))

(def ^:private stride-bytes (* 6 (ffi/sizeof :float)))

(defn- gen-object!
  "One GL object id from a `glGen*` fn taking (count, out-pointer)."
  [gen-fn]
  (let [idp (ffi/alloc (ffi/sizeof :uint))]
    (gen-fn 1 idp)
    (let [id (ffi/read idp :uint)]
      (ffi/free idp)
      id)))

(defn- upload-mesh!
  "Build a VAO+VBO for `m` and upload its interleaved position+normal floats
  (only a_pos is enabled; see ripple.clj's ns docstring for why
  mesh/->floats's normal column is harmlessly present but unread here).
  Returns {:vao :vbo :count}."
  [shader m]
  (let [vao (gen-object! gl/gl-gen-vertex-arrays)
        vbo (gen-object! gl/gl-gen-buffers)
        {:keys [data]
         vcount :count} (mesh/->floats m)
        ptr (gl/write-floats data)]
    (gl/gl-bind-vertex-array vao)
    (gl/gl-bind-buffer gl/GL-ARRAY-BUFFER vbo)
    (gl/gl-buffer-data gl/GL-ARRAY-BUFFER
                       (* (count data) (ffi/sizeof :float))
                       ptr gl/GL-STATIC-DRAW)
    (ffi/free ptr)
    (let [pos (sh/attrib-loc shader :a_pos)]
      (when (>= pos 0)
        (gl/gl-enable-vertex-attrib-array pos)
        (gl/gl-vertex-attrib-pointer pos 3 gl/GL-FLOAT gl/GL-FALSE stride-bytes 0)))
    {:vao vao :vbo vbo :count vcount}))

;; --- unproject: the fix described in the ns docstring's IMPORTANT note.
;; Full 4-component inverse transform, including the divide by the
;; resulting w, unlike glitter-gl.matrix/transform-point's affine-only
;; shortcut. Kept local to this file (not promoted into src/). -------------
(defn- unproject
  [inv [ndc-x ndc-y ndc-z]]
  (let [[m00 m01 m02 m03 m10 m11 m12 m13
         m20 m21 m22 m23 m30 m31 m32 m33] (m/->vec inv)
        w (+ (* m03 ndc-x) (* m13 ndc-y) (* m23 ndc-z) m33)]
    (v3/vec3 (/ (+ (* m00 ndc-x) (* m10 ndc-y) (* m20 ndc-z) m30) w)
             (/ (+ (* m01 ndc-x) (* m11 ndc-y) (* m21 ndc-z) m31) w)
             (/ (+ (* m02 ndc-x) (* m12 ndc-y) (* m22 ndc-z) m32) w))))

(defn- pointer-ray
  "Screen pixel (x,y) -> [origin dir] world-space ray. y is flipped because GL
  NDC is +y up while widget coordinates are +y down (see the ns docstring's
  pointer-handling notes)."
  [^double x ^double y ^double w ^double h view proj eye]
  (let [ndc-x (- (* 2.0 (/ x w)) 1.0)
        ndc-y (- 1.0 (* 2.0 (/ y h)))
        inv   (m/invert (m/mul proj view))]
    (when inv                                  ; invert returns nil when singular
      (let [near (unproject inv [ndc-x ndc-y -1.0])
            far  (unproject inv [ndc-x ndc-y  1.0])]
        [eye (v3/normalize (v3/sub far near))]))))

(defn- nearest-hit
  "Nearer of the ray's hits against the ground and wall planes, or nil.
  {:point <Vec3> :plane :ground|:wall}."
  [origin dir]
  (let [t-ground (isec/ray-plane origin dir ground-plane)
        t-wall   (isec/ray-plane origin dir wall-plane)
        pick     (fn [t plane] {:point (v3/add origin (v3/scale dir t)) :plane plane})]
    (cond
      (and t-ground t-wall) (if (<= t-ground t-wall) (pick t-ground :ground) (pick t-wall :wall))
      t-ground               (pick t-ground :ground)
      t-wall                 (pick t-wall :wall)
      :else                  nil)))

(defn- draw-mesh! [shader {:keys [vao count]} mvp color]
  (sh/set-uniforms! shader {:u_mvp mvp :u_color color})
  (gl/gl-bind-vertex-array vao)
  (gl/gl-draw-arrays gl/GL-TRIANGLES 0 count))

;; --- GLArea handlers ---------------------------------------------------------
(defn on-realize [area]
  (glx/make-current area)
  (when-let [err (glx/gl-area-error-message area)]
    (println "GLArea context error:" err))
  (let [shader (try (sh/program shader-spec)
                    (catch Throwable _ nil))]
    (if-not shader
      (println "glitter-gl.picking: failed to build GL program (see info log above)")
      (do
        (gl/gl-enable gl/GL-DEPTH-TEST)
        (let [ground (upload-mesh! shader ground-mesh)
              wall   (upload-mesh! shader wall-mesh)
              marker (upload-mesh! shader marker-mesh)]
          (swap! gl-state assoc area {:shader shader :ground ground :wall wall :marker marker})
          (println "glitter-gl.picking: GL ready, program" (:program shader)
                   "ground" (:count ground) "wall" (:count wall) "marker" (:count marker)))))))

(defn on-resize [_area w h]
  (reset! viewport [w h])
  (gl/gl-viewport 0 0 w h))

(defn on-motion [_area x y]
  (reset! pointer-pos [x y]))

;; Deliberate no-op body -- see the ns docstring's second IMPORTANT note for
;; why this handler still has to exist.
(defn on-tick [_area]
  nil)

;; Every declared uniform is set explicitly on every draw call, including the
;; marker's variable (zero-or-one-per-frame) draw. See the ns docstring.
(defn on-render [area]
  (when-let [{:keys [shader ground wall marker]} (get @gl-state area)]
    (let [[w h]  @viewport
          aspect (/ (double w) (max 1.0 (double h)))
          proj   (m/perspective fov-deg aspect near-plane far-plane)
          mvp*   (fn [model] (m/mul proj (m/mul view-matrix model)))]
      (gl/gl-clear-color 0.05 0.06 0.09 1.0)
      (gl/gl-clear (bit-or gl/GL-COLOR-BUFFER-BIT gl/GL-DEPTH-BUFFER-BIT))
      (gl/gl-use-program (:program shader))
      (draw-mesh! shader ground (mvp* ground-model) ground-color)
      (draw-mesh! shader wall (mvp* wall-model) wall-color)
      (when-let [[px py] @pointer-pos]
        ;; :on-motion's (x,y) are logical points; @viewport's [w h] are the
        ;; resize signal's DEVICE pixels. See the ns docstring's second
        ;; IMPORTANT note -- widget-width/-height (logical) are the correct
        ;; denominator here, not @viewport.
        (let [logical-w (double (glx/widget-width area))
              logical-h (double (glx/widget-height area))]
          (when-let [[origin dir] (pointer-ray px py logical-w logical-h view-matrix proj eye-pos)]
            (when-let [{:keys [point plane]} (nearest-hit origin dir)]
              (let [[hx hy hz] (v3/->vec point)
                    color (if (= plane :ground) marker-ground-color marker-wall-color)]
                (draw-mesh! shader marker (mvp* (m/translation hx hy hz)) color)))))))))

(core/set-dispatch! (fn [_event _actions] nil))

(defn view [_state]
  [:box {:spacing 0}
   [:gl-area {:version [3 2]
              :depth-buffer true
              :hexpand true
              :vexpand true
              :on-realize on-realize
              :on-render  on-render
              :on-resize  on-resize
              :on-tick    on-tick
              :on-motion  on-motion}]])

(defn -main [& _]
  ;; :gl-area's :on-* props trip a cosmetic dev-time hiccup warning on every
  ;; render; harmless, no practical way to silence it from this namespace --
  ;; see CONTRIBUTING.md invariant #10. plasma.clj/ripple.clj/orbit.clj/
  ;; gears.clj/knot.clj carry the same warning.
  ;; GLITTER_GL_DEMO_QUIT_MS auto-closes the window after N ms (smoke
  ;; testing); unset, the window stays open until closed.
  (let [quit-ms (some-> (System/getenv "GLITTER_GL_DEMO_QUIT_MS") Integer/parseInt)]
    (apply app/run (fn [window] (gtk/mount! window view (atom {})))
           :app-id "glitter-gl.picking"
           :title  "glitter-gl - picking"
           :width  900 :height 600
           (when quit-ms [:auto-quit-ms quit-ms]))))
