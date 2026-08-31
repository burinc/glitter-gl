(ns glitter-gl.textured
  "glitter-gl demo: a rotating cube wearing a procedural checkerboard texture.

  Model: geom's examples/gl/textured_cube.cljs (the idea only; its WebGL
  buffer/camera/texture-load plumbing does not transfer to glitter-gl's
  :gl-area). Every other example in this project fills its fragments from
  lighting math or a flat color -- plasma.clj/knot.clj/gears.clj never
  sample a texture. This is the first consumer of glitter-gl.gl's texture
  FFI (gl-gen-textures/gl-bind-texture/gl-tex-image-2d/gl-tex-parameter-i/
  gl-active-texture) outside renderer.clj's internal shadow-map path and
  the test suite (offscreen_test.clj exercises the same fns headlessly),
  and the first shader spec in the project to declare a :sampler2D uniform --
  shader.clj's gles-sampler-types already knows the type (see adapt-spec),
  it has simply never had a caller before.

  The texture is generated procedurally at realize time: `checkerboard-ptr`
  writes an RGBA byte buffer straight into foreign memory, no image file,
  no binary asset in the repo. p/cuboid supplies the mesh; this file adds
  the UV coordinate per corner itself (`cube-uv-floats`), since
  mesh/->floats only ever emits position+normal.

  RENDER PATH: direct :gl-area wiring (like plasma.clj/ripple.clj/knot.clj/
  gears.clj), not glitter-gl.app/reactive-area -- one mesh, one texture,
  one camera-less rotation, no scene to compose.

  IMPORTANT (see ripple.clj's ns docstring for the mechanism): a shader
  spec's uniform [type default] pair is documentation only -- nothing
  uploads it. on-render below sets every uniform this shader declares,
  every frame, including :u_texture, which must be set to the texture
  UNIT index (0), not the GL texture id gl-gen-textures returned."
  (:require [glitter-gl.gl :as gl]
            [glitter-gl.gtk :as glx]
            [glitter-gl.matrix :as m]
            [glitter-gl.mesh :as mesh]
            [glitter-gl.primitives :as p]
            [glitter-gl.shader :as sh]
            [glitter-gl.vector :as v]
            [glitter.app :as app]
            [glitter.core :as core]
            [glitter.gtk :as gtk]
            [jolt.ffi :as ffi]))

;; --- checkerboard texture: procedural, no image file, no binary asset ------
(def ^:private tex-size  64)   ; texture is tex-size x tex-size RGBA texels
(def ^:private cell-size 8)    ; -> an 8x8 board of cell-size px squares
(def ^:private color-a [255 255 255])  ; white
(def ^:private color-b [225 90 25])    ; vivid orange -- unmistakable from a
                                        ; failed-sampler flat grey or black

(defn- checkerboard-ptr
  "Allocate a tex-size*tex-size RGBA byte buffer (caller frees) and fill it
  with an alternating color-a/color-b checkerboard, cell-size pixels per
  square. Ready for gl-tex-image-2d's pixel argument."
  []
  (let [ptr (ffi/alloc (* tex-size tex-size 4))]
    (dotimes [y tex-size]
      (dotimes [x tex-size]
        (let [[r g b] (if (even? (+ (quot x cell-size) (quot y cell-size)))
                        color-a color-b)
              i (* 4 (+ x (* y tex-size)))]
          (ffi/write ptr :uint8 r i)
          (ffi/write ptr :uint8 g (+ i 1))
          (ffi/write ptr :uint8 b (+ i 2))
          (ffi/write ptr :uint8 255 (+ i 3)))))
    ptr))

;; --- the shader: a_uv straight through to a texture() sample, no lighting --
(def shader-spec
  {:version  "330 core"
   :uniforms {:u_mvp     :mat4
              :u_texture :sampler2D}
   :attribs  {:a_pos [:vec3 0]
              :a_uv  [:vec2 1]}
   :varying  {:v_uv :vec2}
   :fs-out   {:frag :vec4}
   :vs-main  [[:set :v_uv :a_uv]
              [:set :gl_Position [:* :u_mvp [:vec4 :a_pos 1.0]]]]
   :fs-main  [[:set :frag [:texture :u_texture :v_uv]]]})

;; --- GL-plumbing state (plain atoms, read/written directly by the :gl-area
;; handlers, like plasma.clj/ripple.clj/knot.clj/gears.clj: there is no
;; control panel here to dispatch through) -----------------------------------
(defonce ^:private clock    (atom 0.0))
(defonce ^:private viewport (atom [900 600]))
(defonce ^:private gl-state (atom {}))

(def ^:private frame-dt  0.016)
(def ^:private cube-size 1.6)

;; UV corners in the same order as p/cuboid's own per-face CCW winding, so
;; each face shows the full checkerboard once.
(def ^:private quad-uv   [[0.0 0.0] [1.0 0.0] [1.0 1.0] [0.0 1.0]])
(def ^:private quad-tris [[0 1 2] [0 2 3]])

(defn- cube-uv-floats
  "p/cuboid's 6 quad faces, tessellated to 2 triangles each and interleaved
  with per-corner UVs -- [x y z u v ...], stride 5 floats. mesh/->floats
  cannot do this (position+normal only), so this builds the buffer by hand."
  []
  (let [faces (mesh/faces (p/cuboid cube-size))]
    {:data (vec (mapcat
                 (fn [face]
                   (mapcat (fn [tri]
                             (mapcat (fn [i]
                                       (let [pt (nth face i)
                                             [u vv] (nth quad-uv i)]
                                         [(v/x pt) (v/y pt) (v/z pt) u vv]))
                                     tri))
                           quad-tris))
                 faces))
     :count (* 6 (count faces))}))

(def ^:private stride-bytes (* 5 (ffi/sizeof :float)))

(defn- upload-mesh!
  "Fill the bound VBO with the cube's interleaved position+UV floats.
  Returns the vertex count to draw."
  [vbo]
  (let [{:keys [data]
         vcount :count} (cube-uv-floats)
        ptr (gl/write-floats data)]
    (gl/gl-bind-buffer gl/GL-ARRAY-BUFFER vbo)
    (gl/gl-buffer-data gl/GL-ARRAY-BUFFER
                       (* (count data) (ffi/sizeof :float))
                       ptr gl/GL-STATIC-DRAW)
    (ffi/free ptr)
    vcount))

(defn- setup-attribs! [shader]
  (let [pos (sh/attrib-loc shader :a_pos)
        uv  (sh/attrib-loc shader :a_uv)]
    (when (>= pos 0)
      (gl/gl-enable-vertex-attrib-array pos)
      (gl/gl-vertex-attrib-pointer pos 3 gl/GL-FLOAT gl/GL-FALSE stride-bytes 0))
    (when (>= uv 0)
      (gl/gl-enable-vertex-attrib-array uv)
      (gl/gl-vertex-attrib-pointer uv 2 gl/GL-FLOAT gl/GL-FALSE stride-bytes
                                   (* 3 (ffi/sizeof :float))))))

;; Texture setup lives in on-realize per the house pattern: generate the id,
;; bind it, upload the checkerboard, set min/mag filter and wrap. Binding for
;; drawing (gl-active-texture + gl-bind-texture) happens again in on-render,
;; since another :gl-area could rebind unit 0 to something else between frames.
(defn- setup-texture! []
  (let [tex (gl/gen-one gl/gl-gen-textures)
        px  (checkerboard-ptr)]
    (gl/gl-bind-texture gl/GL-TEXTURE-2D tex)
    (gl/gl-tex-image-2d gl/GL-TEXTURE-2D 0 gl/GL-RGBA tex-size tex-size 0
                        gl/GL-RGBA gl/GL-UNSIGNED-BYTE px)
    (ffi/free px)
    (gl/gl-tex-parameter-i gl/GL-TEXTURE-2D gl/GL-TEXTURE-MIN-FILTER gl/GL-NEAREST)
    (gl/gl-tex-parameter-i gl/GL-TEXTURE-2D gl/GL-TEXTURE-MAG-FILTER gl/GL-NEAREST)
    (gl/gl-tex-parameter-i gl/GL-TEXTURE-2D gl/GL-TEXTURE-WRAP-S gl/GL-REPEAT)
    (gl/gl-tex-parameter-i gl/GL-TEXTURE-2D gl/GL-TEXTURE-WRAP-T gl/GL-REPEAT)
    tex))

;; --- GLArea handlers ---------------------------------------------------------
(defn on-realize [area]
  (glx/make-current area)
  (when-let [err (glx/gl-area-error-message area)]
    (println "GLArea context error:" err))
  (let [shader (try (sh/program shader-spec)
                    (catch Throwable _ nil))]
    (if-not shader
      (println "glitter-gl.textured: failed to build GL program (see info log above)")
      (let [idp (ffi/alloc (ffi/sizeof :uint))]
        (gl/gl-gen-vertex-arrays 1 idp)
        (let [vao (ffi/read idp :uint)]
          (gl/gl-gen-buffers 1 idp)
          (let [vbo (ffi/read idp :uint)]
            (ffi/free idp)
            (gl/gl-enable gl/GL-DEPTH-TEST)
            (gl/gl-bind-vertex-array vao)
            (let [n (upload-mesh! vbo)]
              (setup-attribs! shader)
              (let [tex (setup-texture!)]
                (swap! gl-state assoc area
                       {:shader shader
                        :vao vao
                        :vbo vbo
                        :count n
                        :tex tex})
                (println "glitter-gl.textured: GL ready, program" (:program shader)
                         "vao" vao "verts" n "tex" tex)))))))))

(defn on-resize [_area w h]
  (reset! viewport [w h])
  (gl/gl-viewport 0 0 w h))

;; Every declared uniform is set explicitly, every frame -- including
;; :u_texture, set to the texture unit index (0), never the GL texture id.
;; See the ns docstring's IMPORTANT note.
(defn on-render [area]
  (when-let [{:keys [shader vao count tex]} (get @gl-state area)]
    (let [[w h]  @viewport
          aspect (/ (double w) (max 1.0 (double h)))
          t      (double @clock)
          model  (m/mul (m/rotate-y t) (m/rotate-x (* t 0.35)))
          view   (m/translation 0.0 0.0 -4.5)
          proj   (m/perspective 50.0 aspect 0.1 100.0)
          mvp    (m/mul proj (m/mul view model))]
      (gl/gl-clear-color 0.05 0.06 0.09 1.0)
      (gl/gl-clear (bit-or gl/GL-COLOR-BUFFER-BIT gl/GL-DEPTH-BUFFER-BIT))
      (gl/gl-use-program (:program shader))
      (gl/gl-active-texture gl/GL-TEXTURE0)
      (gl/gl-bind-texture gl/GL-TEXTURE-2D tex)
      (sh/set-uniforms! shader {:u_mvp mvp
                                :u_texture 0})
      (gl/gl-bind-vertex-array vao)
      (gl/gl-draw-arrays gl/GL-TRIANGLES 0 count))))

(defn on-tick [_area]
  (swap! clock + frame-dt))

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
  ;; see CONTRIBUTING.md invariant #10. plasma.clj/ripple.clj/orbit.clj/
  ;; knot.clj/gears.clj carry the same warning.
  ;; GLITTER_GL_DEMO_QUIT_MS auto-closes the window after N ms (smoke
  ;; testing); unset, the window stays open until closed.
  (let [quit-ms (some-> (System/getenv "GLITTER_GL_DEMO_QUIT_MS") Integer/parseInt)]
    (apply app/run (fn [window] (gtk/mount! window view (atom {})))
           :app-id "glitter-gl.textured"
           :title  "glitter-gl - textured"
           :width  900 :height 600
           (when quit-ms [:auto-quit-ms quit-ms]))))
