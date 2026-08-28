(ns glitter-gl.ripple
  "glitter-gl demo: a full-screen fragment shader with no mesh to speak of.

  Model: geom's jogl/fullscreen_shader.clj (the idea only; its JOGL
  windowing does not transfer to glitter-gl's :gl-area). plasma.clj already
  shows a lit solid whose surface comes from a composable shader; this
  demonstrates the opposite claim about glitter-gl's two independent
  halves, that the shader-spec DSL (glitter-gl.shader) is useful entirely
  on its own, with no geometry beyond a flat quad, no lighting, no camera,
  and no material system. The one primitives/quad is scaled to 2.0, which
  makes it span [-1, 1] on both axes, i.e. clip space directly, so the
  vertex stage needs no MVP at all: a_pos already IS the clip-space
  position, drawn with an identity transform in effect.

  The fragment shader is composed from two small reusable modules via
  glitter-gl.shader/merge-specs, the same technique plasma-shader.clj uses
  for its own two modules: a ripple module computing a concentric wave
  field around a drifting origin, and a color module turning that wave
  into a color with a soft vignette. Swap or drop either module to change
  the look, same as plasma.

  IMPORTANT (found live, cost the first version an all-black, non-animating
  frame): a shader spec's uniform [type default] pair, e.g. :u_freq [:float
  12.0], is metadata only. shader.clj's located-uniforms stores it under
  :default for documentation, but nothing in shader/program ever uploads
  that value to the GPU. A uniform never given a value via set-uniforms!
  stays at GLSL's zero-initialized default. on-render below sets every
  uniform this shader declares, every frame, including the ones that look
  like fixed constants, sourced from the same defs the spec's [type
  default] pairs use, precisely to avoid the two ever drifting apart."
  (:require [glitter-gl.gl :as gl]
            [glitter-gl.gtk :as glx]
            [glitter-gl.mesh :as mesh]
            [glitter-gl.primitives :as p]
            [glitter-gl.shader :as sh]
            [glitter.app :as app]
            [glitter.core :as core]
            [glitter.gtk :as gtk]
            [jolt.ffi :as ffi]))

;; --- shared constants: the single source for both the shader-spec's
;; documentation-only [type default] pairs and on-render's actual
;; set-uniforms! values below, so the two cannot drift apart -----------------
(def ^:private ripple-freq   12.0)
(def ^:private ripple-speed  2.4)
(def ^:private color-deep    [0.03 0.07 0.18])
(def ^:private color-bright  [0.4 0.9 1.0])

;; --- the shader: composed from two small modules, no MVP uniform at all
;; because the quad already fills clip space (see ns docstring) --------------
(def ^:private base
  {:version  "330 core"
   :uniforms {:u_time       [:float 0.0]
              :u_resolution [:vec2 [900.0 560.0]]}
   :attribs  {:a_pos [:vec3 0]}
   :varying  {:v_uv :vec2}
   :vs-main  [[:set :v_uv [:. :a_pos :xy]]
              [:set :gl_Position [:vec4 :a_pos 1.0]]]})

;; --- module 1: concentric ripples from a drifting origin --------------------
(def ^:private ripple-module
  {:uniforms {:u_freq [:float ripple-freq]
              :u_speed [:float ripple-speed]}
   :prelude
   "// A slow Lissajous drift for the ripple origin, so the rings never
// settle into a static pattern.
vec2 ripple_origin(float t) {
  return vec2(0.5 * sin(t * 0.35), 0.4 * cos(t * 0.27));
}
// Concentric wave value from distance to the drifting origin, in [-1, 1]
// across the whole frame (no distance falloff): the bands stay clearly
// visible edge to edge instead of only near the origin.
float ripple_wave(vec2 uv, float t) {
  float d = length(uv - ripple_origin(t));
  return sin(d * u_freq - t * u_speed);
}
"})

;; --- module 2: wave value -> color, plus a mild corner vignette -------------
(def ^:private color-module
  {:uniforms {:u_deep [:vec3 color-deep]
              :u_bright [:vec3 color-bright]}
   :prelude
   "vec3 ripple_color(float wave) {
  // wave's full [-1, 1] range maps to the full deep-to-bright range, so
  // the rings read as clear bands everywhere rather than mostly dark.
  float g = smoothstep(-1.0, 1.0, wave);
  return mix(u_deep, u_bright, g);
}
float vignette(vec2 uv) {
  return clamp(1.0 - 0.22 * dot(uv, uv), 0.0, 1.0);
}
"})

(def ^:private main-module
  {:fs-out  {:frag :vec4}
   :fs-main [[:let :aspect :float [:/ [:. :u_resolution :x] [:. :u_resolution :y]]]
             [:let :uv :vec2 [:* :v_uv [:vec2 :aspect 1.0]]]
             [:let :wave :float [:ripple_wave :uv :u_time]]
             [:let :col :vec3 [:ripple_color :wave]]
             [:set :frag [:vec4 [:* :col [:vignette :v_uv]] 1.0]]]})

(def shader-spec
  (sh/merge-specs base ripple-module color-module main-module))

;; --- GL-plumbing state (mirrors plasma.clj: plain atoms, read/written
;; directly by the :gl-area handlers, never routed through glitter's state
;; atom or action dispatch, since there is no control panel here to
;; dispatch from) ---------------------------------------------------------
(defonce ^:private clock    (atom 0.0))
(defonce ^:private viewport (atom [900 560]))
(defonce ^:private gl-state (atom {}))

(def ^:private frame-dt 0.016)

;; The quad already spans [-1, 1] on both axes, i.e. clip space directly, so
;; size 2.0 needs no further scaling or camera.
(def ^:private quad-mesh (p/quad 2.0))

(def ^:private stride-bytes (* 6 (ffi/sizeof :float)))

(defn- upload!
  "Fill the bound VBO with the quad's interleaved position+normal floats.
  The shader never reads a_normal (there is no lighting here), but
  mesh/->floats always emits it, so the buffer layout still carries it;
  only a_pos is enabled below. Returns the vertex count to draw."
  [vbo]
  (let [{:keys [data]
         vcount :count} (mesh/->floats quad-mesh)
        ptr (gl/write-floats data)]
    (gl/gl-bind-buffer gl/GL-ARRAY-BUFFER vbo)
    (gl/gl-buffer-data gl/GL-ARRAY-BUFFER
                       (* (count data) (ffi/sizeof :float))
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
      (println "glitter-gl.ripple: failed to build GL program (see info log above)")
      (let [idp (ffi/alloc (ffi/sizeof :uint))]
        (gl/gl-gen-vertex-arrays 1 idp)
        (let [vao (ffi/read idp :uint)]
          (gl/gl-gen-buffers 1 idp)
          (let [vbo (ffi/read idp :uint)]
            (ffi/free idp)
            (gl/gl-bind-vertex-array vao)
            (let [n (upload! vbo)]
              (setup-attribs! shader)
              (swap! gl-state assoc area {:shader shader
                                          :vao vao
                                          :vbo vbo
                                          :count n})
              (println "glitter-gl.ripple: GL ready, program" (:program shader)
                       "vao" vao "verts" n))))))))

(defn on-resize [_area w h]
  (reset! viewport [w h])
  (gl/gl-viewport 0 0 w h))

;; Every declared uniform is set explicitly, every frame, even the ones
;; that read like fixed constants (:u_freq, :u_speed, :u_deep, :u_bright).
;; See the ns docstring's IMPORTANT note: a shader-spec [type default]
;; pair is never auto-uploaded, so skipping any of these here would leave
;; that uniform at GLSL's zero-initialized default instead.
(defn on-render [area]
  (when-let [{:keys [shader vao count]} (get @gl-state area)]
    (let [[w h] @viewport
          t     (double @clock)]
      (gl/gl-clear-color 0.0 0.0 0.0 1.0)
      (gl/gl-clear gl/GL-COLOR-BUFFER-BIT)
      (gl/gl-use-program (:program shader))
      (sh/set-uniforms! shader {:u_time       t
                                :u_resolution [(double w) (double h)]
                                :u_freq       ripple-freq
                                :u_speed      ripple-speed
                                :u_deep       color-deep
                                :u_bright     color-bright})
      (gl/gl-bind-vertex-array vao)
      (gl/gl-draw-arrays gl/GL-TRIANGLES 0 count))))

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
  ;; render (glitter.core flags any prop key starting with "on" as a
  ;; probable :on {} mistake); harmless, and there is no practical way to
  ;; silence it from this namespace; see CONTRIBUTING.md invariant #10 for
  ;; the full mechanism. plasma.clj carries the same warning.
  ;; GLITTER_GL_DEMO_QUIT_MS auto-closes the window after N ms (smoke
  ;; testing); unset, the window stays open until closed.
  (let [quit-ms (some-> (System/getenv "GLITTER_GL_DEMO_QUIT_MS") Integer/parseInt)]
    (apply app/run (fn [window] (gtk/mount! window view (atom {})))
           :app-id "glitter-gl.ripple"
           :title  "glitter-gl • ripple"
           :width  900 :height 560
           (when quit-ms [:auto-quit-ms quit-ms]))))
