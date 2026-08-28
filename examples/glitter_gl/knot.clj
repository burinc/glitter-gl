(ns glitter-gl.knot
  "glitter-gl demo: a (p,q) torus knot, swept as a tube and rendered as a
  rotating lit solid.

  Every other example renders geometry the library ships as a built-in:
  plasma.clj a cuboid/sphere/tetrahedron, orbit.clj those plus the
  polyhedra set. This one generates geometry glitter-gl does not ship at
  all -- primitives.clj and polyhedra.clj are a starting set, not the
  boundary of what mesh/mesh can build. `torus-knot-faces` below is a
  plain, pure function from (p, q, radii, sample counts) to a seq of Vec3
  quad faces; it is the only new thing this file introduces, and it never
  touches GL. Everything downstream of it -- upload, shader, draw -- is
  the exact plumbing plasma.clj already established for a single lit
  mesh, unchanged.

  Idea from thi.ng/geom's examples/gl/torus_knot.cljs (its
  `cinquefoil` + `ptf/sweep-mesh`), reimplemented rather than ported: the
  ClojureScript/WebGL/parallel-transport-frame plumbing does not transfer,
  only the parametric idea of a torus knot swept as a tube. This example's
  frame is built differently (see `torus-knot-frame`'s docstring) and is
  original to this file.

  RENDER PATH: direct :gl-area wiring (like plasma.clj/ripple.clj), not
  glitter-gl.app/reactive-area (like orbit.clj). This demo has exactly one
  mesh and no scene composition -- one camera-less rotation, one light,
  one draw call. reactive-area's value is coordinating a scene graph of
  several positioned nodes; adding it here would wrap the generator in
  scene/camera/light/group boilerplate that has nothing to compose,
  pushing the actual point of the file (the generator function) further
  from the top. plasma.clj's plain-atom, single-mesh shape is the closer
  fit and keeps the generator the first thing a reader sees.

  IMPORTANT (see ripple.clj's ns docstring for the mechanism): a shader
  spec's uniform [type default] pair is documentation only -- nothing
  uploads it. on-render below sets every uniform this shader declares,
  every frame, from the same defs the spec's defaults use."
  (:require [glitter-gl.gl :as gl]
            [glitter-gl.gtk :as glx]
            [glitter-gl.matrix :as m]
            [glitter-gl.mesh :as mesh]
            [glitter-gl.shader :as sh]
            [glitter-gl.vector :as v]
            [glitter.app :as app]
            [glitter.core :as core]
            [glitter.gtk :as gtk]
            [jolt.ffi :as ffi]))

;; --- the generator: pure maths, no GL --------------------------------------
;; This is the geometry the library does not ship. Everything below this
;; section is rendering plumbing that already exists in plasma.clj.

(defn- torus-knot-point
  "Point on the (p,q) torus knot's core curve at parameter `t`: wound `p`
  times around the main axis and `q` times around the tube of a torus of
  major radius R and winding amplitude r."
  [p q R r t]
  (let [pt  (* (double p) t)
        qt  (* (double q) t)
        rad (+ R (* r (Math/cos qt)))]
    (v/vec3 (* rad (Math/cos pt)) (* rad (Math/sin pt)) (* r (Math/sin qt)))))

(def ^:private tangent-eps (/ (* 2.0 Math/PI) 100000.0))

(defn- torus-knot-frame
  "Position plus an orthonormal (normal, binormal) perpendicular to the
  curve's tangent at `t`. `normal` starts from the torus's own meridian
  direction (cos(p*t), sin(p*t), 0) -- itself periodic in t -- projected
  perpendicular to the tangent by Gram-Schmidt. Unlike a frame propagated
  ring-to-ring (parallel transport), this one is a pure function of `t`
  alone, so the last ring's orientation lines up with the first ring's
  exactly where the tube closes on itself; a propagated frame can drift
  and leave a visible twist at that seam."
  [p q R r t]
  (let [p0      (torus-knot-point p q R r t)
        p1      (torus-knot-point p q R r (+ t tangent-eps))
        tangent (v/normalize (v/sub p1 p0))
        pt      (* (double p) t)
        ref     (v/vec3 (Math/cos pt) (Math/sin pt) 0.0)
        normal  (v/normalize (v/sub ref (v/scale tangent (v/dot ref tangent))))]
    [p0 normal (v/cross tangent normal)]))

(defn torus-knot-faces
  "Faces (a seq of 4-vertex Vec3 quads, ready for mesh/mesh) of a (p,q)
  torus knot's core curve, swept as a tube of circular cross-section
  `tube-r`. `samples` rings run along the knot, `sides` points run around
  each ring; both wrap modulo their count, since the curve and every ring
  are closed loops. p and q should be coprime for a single closed curve
  (not checked here). Pure: no GL, just Vec3 faces."
  [{:keys [p q R r tube-r samples sides]
    :or {p 2
         q 3
         R 1.6
         r 0.55
         tube-r 0.22
         samples 200
         sides 12}}]
  (let [two-pi (* 2.0 Math/PI)
        rings  (mapv (fn [w]
                       (let [t (* two-pi (/ (double w) samples))
                             [p0 normal binormal] (torus-knot-frame p q R r t)]
                         (mapv (fn [u]
                                 (let [theta (* two-pi (/ (double u) sides))]
                                   (v/add p0
                                          (v/add (v/scale normal (* tube-r (Math/cos theta)))
                                                 (v/scale binormal (* tube-r (Math/sin theta)))))))
                               (range sides))))
                     (range samples))]
    (vec (for [w (range samples)
               u (range sides)
               :let [ww (mod (inc w) samples)
                     uu (mod (inc u) sides)]]
           [(-> rings (nth w) (nth u)) (-> rings (nth w) (nth uu))
            (-> rings (nth ww) (nth uu)) (-> rings (nth ww) (nth u))]))))

;; --- shared constants: the single source for both the shader-spec's
;; documentation-only [type default] pairs and on-render's actual
;; set-uniforms! values below, so the two cannot drift apart -----------------
(def ^:private light      [0.45 0.85 0.55])
(def ^:private color-deep   [0.10 0.05 0.24])
(def ^:private color-bright [0.16 0.85 0.80])

;; --- the shader: a plain lit surface, plus a purely decorative color band
;; by angle around the knot's main axis -- the lobe count to verify visually
;; is the geometry's, not this gradient's ------------------------------------
(def ^:private base
  {:version  "330 core"
   :uniforms {:u_mvp   :mat4
              :u_model :mat4
              :u_light [:vec3 light]}
   :attribs  {:a_pos    [:vec3 0]
              :a_normal [:vec3 1]}
   :varying  {:v_obj :vec3
              :v_normal :vec3}
   :vs-main  [[:set :v_obj :a_pos]
              [:set :v_normal [:* [:mat3 :u_model] :a_normal]]
              [:set :gl_Position [:* :u_mvp [:vec4 :a_pos 1.0]]]]})

(def ^:private color-module
  {:uniforms {:u_deep [:vec3 color-deep]
              :u_bright [:vec3 color-bright]}
   :prelude
   "vec3 knot_color(vec3 p) {
  float a = atan(p.y, p.x);
  float g = 0.5 + 0.5 * sin(a * 5.0);
  return mix(u_deep, u_bright, g);
}
"})

(def ^:private main-module
  {:fs-out  {:frag :vec4}
   :fs-main [[:let :n :vec3 [:normalize :v_normal]]
             [:let :diff :float [:max [:dot :n [:normalize :u_light]] 0.0]]
             [:let :col :vec3 [:knot_color :v_obj]]
             [:set :frag [:vec4 [:* :col [:+ 0.32 [:* 0.68 :diff]]] 1.0]]]})

(def shader-spec
  (sh/merge-specs base color-module main-module))

;; --- GL-plumbing state (plain atoms, read/written directly by the :gl-area
;; handlers, like plasma.clj/ripple.clj: there is no control panel here to
;; dispatch through) ----------------------------------------------------------
(defonce ^:private clock    (atom 0.0))
(defonce ^:private viewport (atom [900 600]))
(defonce ^:private gl-state (atom {}))

(def ^:private frame-tick-dt 0.016)

(def ^:private knot-mesh (mesh/mesh (torus-knot-faces {})))

(def ^:private stride-bytes (* 6 (ffi/sizeof :float)))

(defn- upload!
  "Fill the bound VBO with the knot mesh's interleaved position+normal
  floats, smooth-shaded so the tube reads as a rounded surface rather than
  its individual quads. Returns the vertex count to draw."
  [vbo]
  (let [{:keys [data]
         vcount :count} (mesh/->floats knot-mesh {:shading :smooth})
        ptr (gl/write-floats data)]
    (gl/gl-bind-buffer gl/GL-ARRAY-BUFFER vbo)
    (gl/gl-buffer-data gl/GL-ARRAY-BUFFER
                       (* (count data) (ffi/sizeof :float))
                       ptr gl/GL-STATIC-DRAW)
    (ffi/free ptr)
    vcount))

(defn- setup-attribs! [shader]
  (let [pos (sh/attrib-loc shader :a_pos)
        nrm (sh/attrib-loc shader :a_normal)]
    (when (>= pos 0)
      (gl/gl-enable-vertex-attrib-array pos)
      (gl/gl-vertex-attrib-pointer pos 3 gl/GL-FLOAT gl/GL-FALSE stride-bytes 0))
    (when (>= nrm 0)
      (gl/gl-enable-vertex-attrib-array nrm)
      (gl/gl-vertex-attrib-pointer nrm 3 gl/GL-FLOAT gl/GL-FALSE stride-bytes
                                   (* 3 (ffi/sizeof :float))))))

;; --- GLArea handlers ---------------------------------------------------------
(defn on-realize [area]
  (glx/make-current area)
  (when-let [err (glx/gl-area-error-message area)]
    (println "GLArea context error:" err))
  (let [shader (try (sh/program shader-spec)
                    (catch Throwable _ nil))]
    (if-not shader
      (println "glitter-gl.knot: failed to build GL program (see info log above)")
      (let [idp (ffi/alloc (ffi/sizeof :uint))]
        (gl/gl-gen-vertex-arrays 1 idp)
        (let [vao (ffi/read idp :uint)]
          (gl/gl-gen-buffers 1 idp)
          (let [vbo (ffi/read idp :uint)]
            (ffi/free idp)
            (gl/gl-enable gl/GL-DEPTH-TEST)
            (gl/gl-bind-vertex-array vao)
            (let [n (upload! vbo)]
              (setup-attribs! shader)
              (swap! gl-state assoc area {:shader shader
                                          :vao vao
                                          :vbo vbo
                                          :count n})
              (println "glitter-gl.knot: GL ready, program" (:program shader)
                       "vao" vao "verts" n))))))))

(defn on-resize [_area w h]
  (reset! viewport [w h])
  (gl/gl-viewport 0 0 w h))

;; Every declared uniform is set explicitly, every frame, even the ones that
;; read like fixed constants (:u_light, :u_deep, :u_bright). See the ns
;; docstring's IMPORTANT note.
(defn on-render [area]
  (when-let [{:keys [shader vao count]} (get @gl-state area)]
    (let [[w h]  @viewport
          aspect (/ (double w) (max 1.0 (double h)))
          t      (double @clock)
          model  (m/mul (m/rotate-y t) (m/rotate-x (* t 0.35)))
          view   (m/translation 0.0 0.0 -5.5)
          proj   (m/perspective 50.0 aspect 0.1 100.0)
          mvp    (m/mul proj (m/mul view model))]
      (gl/gl-clear-color 0.05 0.06 0.09 1.0)
      (gl/gl-clear (bit-or gl/GL-COLOR-BUFFER-BIT gl/GL-DEPTH-BUFFER-BIT))
      (gl/gl-use-program (:program shader))
      (sh/set-uniforms! shader
                        {:u_mvp    mvp
                         :u_model  model
                         :u_light  light
                         :u_deep   color-deep
                         :u_bright color-bright})
      (gl/gl-bind-vertex-array vao)
      (gl/gl-draw-arrays gl/GL-TRIANGLES 0 count))))

(defn on-tick [_area]
  (swap! clock + frame-tick-dt))

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
              :on-tick    on-tick}]])

(defn -main [& _]
  ;; :gl-area's :on-* props trip a cosmetic dev-time hiccup warning on every
  ;; render; harmless, no practical way to silence it from this namespace --
  ;; see CONTRIBUTING.md invariant #10. plasma.clj/ripple.clj/orbit.clj carry
  ;; the same warning.
  ;; GLITTER_GL_DEMO_QUIT_MS auto-closes the window after N ms (smoke
  ;; testing); unset, the window stays open until closed.
  (let [quit-ms (some-> (System/getenv "GLITTER_GL_DEMO_QUIT_MS") Integer/parseInt)]
    (apply app/run (fn [window] (gtk/mount! window view (atom {})))
           :app-id "glitter-gl.knot"
           :title  "glitter-gl - knot"
           :width  900 :height 600
           (when quit-ms [:auto-quit-ms quit-ms]))))
