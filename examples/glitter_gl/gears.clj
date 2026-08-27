(ns glitter-gl.gears
  "glitter-gl demo: three counter-rotating cog outlines, flat-shaded in 2D.

  Model: geom's examples/gl/gears2d.cljs (the idea only; its ClojureScript/
  WebGL windowing does not transfer to glitter-gl's :gl-area). Every other
  example draws geometry glitter-gl.mesh already tessellates for it
  (primitives.clj/polyhedra.clj shapes go through mesh/->floats). This one
  gives glitter-gl.polygon/tessellate its first consumer outside mesh.clj:
  polygon/cog builds a toothed 2D outline, and polygon/tessellate ear-clips
  that outline directly, with no mesh.clj involved at all.

  RENDER PATH: direct :gl-area wiring (like plasma.clj/ripple.clj/knot.clj),
  not glitter-gl.app/reactive-area. Three flat cogs and no lighting is not a
  scene-graph problem.

  Each cog's triangle list is built exactly once, at namespace load (see
  `cog-meshes` below): cog -> tessellate -> a flat [x y z ...] float buffer,
  z always 0.0. on-render below never rebuilds geometry; it only feeds a
  rotated model matrix per cog, every frame, the same way knot.clj spins its
  tube by matrix alone. `m/ortho` stands in for `m/perspective`: this is a
  flat 2D scene with no camera.

  IMPORTANT (see ripple.clj's ns docstring for the mechanism): a shader
  spec's uniform [type default] pair is documentation only -- nothing
  uploads it. on-render below sets every uniform this shader declares
  (:u_mvp, :u_color), every frame, per cog."
  (:require [glitter-gl.gl :as gl]
            [glitter-gl.gtk :as glx]
            [glitter-gl.matrix :as m]
            [glitter-gl.polygon :as poly]
            [glitter-gl.shader :as sh]
            [glitter-gl.vec2 :as v2]
            [glitter.app :as app]
            [glitter.core :as core]
            [glitter.gtk :as gtk]
            [jolt.ffi :as ffi]))

;; --- shared constants: the single source for both the shader-spec's
;; documentation-only [type default] pair and on-render's actual
;; set-uniforms! values below, so the two cannot drift apart -----------------
(def ^:private profile [0.9 1.0 1.0 0.9])

(def ^:private color-a [0.85 0.62 0.20])   ; amber
(def ^:private color-b [0.28 0.55 0.85])   ; sky blue
(def ^:private color-c [0.78 0.24 0.24])   ; brick red

;; [radius teeth pos spin color], one row per cog. Spins alternate sign so
;; adjacent cogs counter-rotate; radii/positions are spaced so the three
;; outlines never overlap.
(def ^:private cogs
  [{:radius 0.9  :teeth 8  :pos [-1.7  0.0] :spin  0.9 :color color-a}
   {:radius 0.6  :teeth 6  :pos [0.0  0.0] :spin -1.3 :color color-b}
   {:radius 0.75 :teeth 10 :pos [1.65 0.0] :spin  0.9 :color color-c}])

;; --- the shader: a plain flat fill, no lighting, no varyings -- u_mvp alone
;; places each cog's own vertices, u_color alone fills it ---------------------
(def ^:private base
  {:version  "330 core"
   :uniforms {:u_mvp   :mat4
              :u_color [:vec3 color-a]}
   :attribs  {:a_pos [:vec3 0]}
   :vs-main  [[:set :gl_Position [:* :u_mvp [:vec4 :a_pos 1.0]]]]})

(def ^:private main-module
  {:fs-out  {:frag :vec4}
   :fs-main [[:set :frag [:vec4 :u_color 1.0]]]})

(def shader-spec
  (sh/merge-specs base main-module))

;; --- GL-plumbing state (plain atoms, read/written directly by the :gl-area
;; handlers, like plasma.clj/ripple.clj/knot.clj: there is no control panel
;; here to dispatch through) ---------------------------------------------------
(defonce ^:private clock    (atom 0.0))
(defonce ^:private viewport (atom [900 560]))
(defonce ^:private gl-state (atom {}))

(def ^:private frame-dt 0.016)

(defn- cog-floats
  "One cog's outline, ear-clipped and flattened to an interleaved [x y z ...]
  float seq (z always 0.0), ready for gl/write-floats. Pure -- no GL."
  [{:keys [radius teeth]}]
  (->> (poly/cog radius teeth profile)
       poly/tessellate
       (mapcat identity)
       (mapcat (fn [v] [(v2/x v) (v2/y v) 0.0]))
       vec))

;; Built once at load: each cog spec plus its own flattened triangle buffer.
;; on-realize below only uploads these; on-render only rotates them via a
;; model matrix (see the ns docstring).
(def ^:private cog-meshes
  (mapv (fn [spec] (assoc spec :floats (cog-floats spec))) cogs))

(def ^:private stride-bytes (* 3 (ffi/sizeof :float)))

(defn- upload!
  "Fill the bound VBO with one cog's flattened position floats. Returns the
  vertex count to draw."
  [vbo floats]
  (let [vcount (quot (count floats) 3)
        ptr    (gl/write-floats floats)]
    (gl/gl-bind-buffer gl/GL-ARRAY-BUFFER vbo)
    (gl/gl-buffer-data gl/GL-ARRAY-BUFFER
                       (* (count floats) (ffi/sizeof :float))
                       ptr gl/GL-STATIC-DRAW)
    (ffi/free ptr)
    vcount))

(defn- setup-attribs! [shader]
  (let [pos (sh/attrib-loc shader :a_pos)]
    (when (>= pos 0)
      (gl/gl-enable-vertex-attrib-array pos)
      (gl/gl-vertex-attrib-pointer pos 3 gl/GL-FLOAT gl/GL-FALSE stride-bytes 0))))

;; --- GLArea handlers ---------------------------------------------------------
(defn on-realize [area]
  (glx/make-current area)
  (when-let [err (glx/gl-area-error-message area)]
    (println "GLArea context error:" err))
  (let [shader (try (sh/program shader-spec)
                    (catch Throwable _ nil))]
    (if-not shader
      (println "glitter-gl.gears: failed to build GL program (see info log above)")
      (let [meshes
            (mapv
             (fn [{:keys [floats] :as spec}]
               (let [idp (ffi/alloc (ffi/sizeof :uint))]
                 (gl/gl-gen-vertex-arrays 1 idp)
                 (let [vao (ffi/read idp :uint)]
                   (gl/gl-gen-buffers 1 idp)
                   (let [vbo (ffi/read idp :uint)]
                     (ffi/free idp)
                     (gl/gl-bind-vertex-array vao)
                     (let [n (upload! vbo floats)]
                       (setup-attribs! shader)
                       (assoc spec :vao vao :vbo vbo :count n))))))
             cog-meshes)]
        (swap! gl-state assoc area {:shader shader :meshes meshes})
        (println "glitter-gl.gears: GL ready, program" (:program shader)
                 "cogs" (count meshes))))))

(defn on-resize [_area w h]
  (reset! viewport [w h])
  (gl/gl-viewport 0 0 w h))

;; Every declared uniform is set explicitly, every frame, per cog -- even
;; :u_color, which reads like a fixed constant per mesh. See the ns
;; docstring's IMPORTANT note.
(defn on-render [area]
  (when-let [{:keys [shader meshes]} (get @gl-state area)]
    (let [[w h]  @viewport
          aspect (/ (double w) (max 1.0 (double h)))
          extent 2.2
          proj   (m/ortho (* (- extent) aspect) (* extent aspect)
                          (- extent) extent -1.0 1.0)
          t      (double @clock)]
      (gl/gl-clear-color 0.05 0.06 0.09 1.0)
      (gl/gl-clear gl/GL-COLOR-BUFFER-BIT)
      (gl/gl-use-program (:program shader))
      (doseq [{:keys [pos spin color vao count]} meshes]
        (let [[px py] pos
              model  (m/mul (m/translation px py 0.0) (m/rotate-z (* t spin)))
              mvp    (m/mul proj model)]
          (sh/set-uniforms! shader {:u_mvp mvp :u_color color})
          (gl/gl-bind-vertex-array vao)
          (gl/gl-draw-arrays gl/GL-TRIANGLES 0 count))))))

(defn on-tick [_area]
  (swap! clock + frame-dt))

(core/set-dispatch! (fn [_event _actions] nil))

(defn view [_state]
  [:box {:spacing 0}
   [:gl-area {:version [3 2]
              :depth-buffer false
              :hexpand true
              :vexpand true
              :on-realize on-realize
              :on-render  on-render
              :on-resize  on-resize
              :on-tick    on-tick}]])

(defn -main [& _]
  ;; :gl-area's :on-* props trip a cosmetic dev-time hiccup warning on every
  ;; render; harmless, no practical way to silence it from this namespace --
  ;; see CONTRIBUTING.md invariant #10. plasma.clj/ripple.clj/orbit.clj/
  ;; knot.clj carry the same warning.
  ;; GLITTER_GL_DEMO_QUIT_MS auto-closes the window after N ms (smoke
  ;; testing); unset, the window stays open until closed.
  (let [quit-ms (some-> (System/getenv "GLITTER_GL_DEMO_QUIT_MS") Integer/parseInt)]
    (apply app/run (fn [window] (gtk/mount! window view (atom {})))
           :app-id "glitter-gl.gears"
           :title  "glitter-gl - gears"
           :width  900 :height 560
           (when quit-ms [:auto-quit-ms quit-ms]))))
