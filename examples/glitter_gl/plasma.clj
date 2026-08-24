(ns glitter-gl.plasma
  "glitter-gl demo: a rotating cube/sphere/tetrahedron lit by a composable
  plasma+stripes procedural shader (glitter-gl.plasma-shader), driven by a
  reactive control panel — the GL analogue of glitter's todo demo.

  Ported from gl-demo.core (~/dev/jolt-examples/glimmer-gl-app), converting
  its reactive-cell control panel (glimmer.ratom/atom + closures) into
  glitter's single state atom + data-driven action dispatch. The GL
  render-loop plumbing (on-realize/on-render/on-resize/on-tick) is
  otherwise unchanged: per the design spec's 'glitter-gl.scene /
  glitter-gl.app' decision, it reads/writes the shared state atom directly
  rather than going through dispatch — the same 'plain atom, not reactive
  cell' treatment the original already gave its GL-plumbing-only atoms
  (clock/viewport/gl-state), now just applied to the WHOLE state map instead
  of a handful of separate atoms. The dispatch mechanism itself is
  glitter.nexus (see glitter's docs/guide/nexus.md) — the control panel's
  :effect/assoc-in passthroughs and :action/toggle-smooth/
  :action/toggle-paused expansions mirror the same effect/action split
  glitter's own todo.clj/crud.clj retrofit onto the same engine, not a
  hand-written case form."
  (:require [clojure.tools.logging :as log]
            [glitter-gl.gl   :as gl]
            [glitter-gl.gtk  :as glx]
            [glitter-gl.matrix :as m]
            [glitter-gl.mesh :as mesh]
            [glitter-gl.plasma-shader :as pshader]
            [glitter-gl.primitives :as p]
            [glitter-gl.shader :as sh]
            [glitter.app     :as app]
            [glitter.core    :as core]
            [glitter.gtk     :as gtk]
            [glitter.nexus.registry :as nxr]
            [jolt.ffi :as ffi]))

;; --- app state (glitter: ONE atom, pure view fn, data-driven dispatch) ------
(defonce state (atom {:shape :cube
                      :speed 1.0
                      :zoom 1.0
                      :p-scale 3.0
                      :warp 0.5
                      :blend 0.5
                      :smooth false
                      :paused false}))

;; --- GL-plumbing-only state (not part of the reconciled view; read/written
;; directly by the :gl-area handlers, exactly like the original's plain
;; clock/viewport/gl-state atoms) --------------------------------------------
(defonce clock    (atom 0.0))
(defonce viewport (atom [900 560]))
(defonce gl-state (atom {}))

(def ^:private frame-dt 0.016)
(def ^:private light [0.4 0.85 0.6])

;; --- geometry ----------------------------------------------------------------
(defn- build-mesh [shape]
  (case shape
    :sphere (p/sphere 1.0 28 18)
    :tetra  (p/tetrahedron 1.35)
    (p/cuboid 1.5)))

(defn- buffer-for [shape smooth?]
  (mesh/->floats (build-mesh shape) {:shading (if smooth? :smooth :flat)}))

(def ^:private stride-bytes (* 6 (ffi/sizeof :float)))

(defn- upload!
  "(Re)fill the bound VBO from the mesh for `shape`/`smooth?`. Returns the
  vertex count to draw."
  [vbo shape smooth?]
  (let [{:keys [data]
         vcount :count} (buffer-for shape smooth?)
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

;; --- GLArea handlers — read/write `state`/`clock`/`viewport`/`gl-state`
;; directly (the escape-hatch category, per the design decision) -------------
(defn on-realize [area]
  (glx/make-current area)
  (when-let [err (glx/gl-area-error-message area)]
    (println "GLArea context error:" err))
  (let [shader (try (sh/program pshader/shader-spec)
                    (catch Throwable _ nil))]
    (if-not shader
      (println "glitter-gl.plasma: failed to build GL program (see info log above)")
      (let [idp (ffi/alloc (ffi/sizeof :uint))]
        (gl/gl-gen-vertex-arrays 1 idp)
        (let [vao (ffi/read idp :uint)]
          (gl/gl-gen-buffers 1 idp)
          (let [vbo (ffi/read idp :uint)
                {:keys [shape smooth]} @state]
            (ffi/free idp)
            (gl/gl-enable gl/GL-DEPTH-TEST)
            (gl/gl-bind-vertex-array vao)
            (let [n (upload! vbo shape smooth)]
              (setup-attribs! shader)
              (swap! gl-state assoc area
                     {:shader shader
                      :vao vao
                      :vbo vbo
                      :count n
                      :shape shape
                      :smooth smooth})
              (println "glitter-gl.plasma: GL ready — program" (:program shader)
                       "vao" vao "verts" n))))))))

(defn on-resize [_area w h]
  (reset! viewport [w h])
  (gl/gl-viewport 0 0 w h))

(defn on-render [area]
  (when-let [st (get @gl-state area)]
    (let [{:keys [shape smooth zoom p-scale warp blend]} @state
          st (if (or (not= (:shape st) shape) (not= (:smooth st) smooth))
               (let [n (upload! (:vbo st) shape smooth)
                     st' (assoc st :count n :shape shape :smooth smooth)]
                 (swap! gl-state assoc area st') st')
               st)
          shader (:shader st)
          [w h]  @viewport
          aspect (/ (double w) (max 1.0 (double h)))
          t      (double @clock)
          s      (double zoom)
          model  (m/mul (m/mul (m/rotate-y t) (m/rotate-x (* t 0.5)))
                        (m/scaling s s s))
          view   (m/translation 0.0 0.0 -4.5)
          proj   (m/perspective 50.0 aspect 0.1 100.0)
          mvp    (m/mul proj (m/mul view model))]
      (gl/gl-clear-color 0.05 0.06 0.09 1.0)
      (gl/gl-clear (bit-or gl/GL-COLOR-BUFFER-BIT gl/GL-DEPTH-BUFFER-BIT))
      (gl/gl-use-program (:program shader))
      (sh/set-uniforms! shader
                        {:u_mvp     mvp
                         :u_model   model
                         :u_time    t
                         :u_scale   p-scale
                         :u_warp    warp
                         :u_mix     blend
                         :u_stripes 8.0
                         :u_light   light})
      (gl/gl-bind-vertex-array (:vao st))
      (gl/gl-draw-arrays gl/GL-TRIANGLES 0 (:count st)))))

(defn on-tick [_area]
  (when-not (:paused @state)
    (swap! clock + (* (double (:speed @state)) frame-dt))))

(defn- parse-shape
  "GLITTER_GL_DEMO_SHAPE -> shape keyword; nil/unrecognised input returns nil
  so the caller can leave today's default in place."
  [s]
  (case s "cube" :cube "sphere" :sphere "tetra" :tetra nil))

;; --- reactive control panel (glitter: DATA, dispatched through one fn) ------
(defn- slider [label-text lo hi step value key]
  [:box {:spacing 8}
   [:label {:label label-text
            :width-chars 6
            :xalign 0.0}]
   [:scale {:min lo
            :max hi
            :step step
            :value value
            :digits 2
            :hexpand true
            :on {:value-changed [[:effect/assoc-in [key] [:glitter/value]]]}}]])

(defn- shape-button [label kw current-shape]
  [:button {:label label
            :sensitive (not= current-shape kw)
            :on {:click [[:effect/assoc-in [:shape] kw]]}}])

(defn- control-panel [{:keys [shape speed zoom p-scale warp blend smooth paused]}]
  [:box {:spacing 6
         :margin 8
         :orientation :vertical}
   [:hbox {:spacing 6}
    (shape-button "Cube" :cube shape)
    (shape-button "Sphere" :sphere shape)
    (shape-button "Tetra" :tetra shape)]
   (slider "Speed" 0.0 4.0 0.05 speed :speed)
   (slider "Zoom"  0.3 2.5 0.05 zoom :zoom)
   (slider "Scale" 0.5 8.0 0.1  p-scale :p-scale)
   (slider "Warp"  0.0 1.5 0.05 warp :warp)
   (slider "Blend" 0.0 1.0 0.05 blend :blend)
   [:hbox {:spacing 12}
    [:checkbutton {:label "Smooth shading"
                   :active smooth
                   :on {:toggled [[:action/toggle-smooth]]}}]
    [:button {:label (if paused "Resume" "Pause")
              :on {:click [[:action/toggle-paused]]}}]]])

(defn view [state]
  [:box {:spacing 0
         :orientation :vertical}
   (control-panel state)
   [:separator {}]
   [:gl-area {:version [3 2]
              :depth-buffer true
              :hexpand true
              :vexpand true
              :on-realize on-realize
              :on-render  on-render
              :on-resize  on-resize
              :on-tick    on-tick}]])

(nxr/register-effect! :effect/assoc-in
                      (fn [_ system path v] (swap! system assoc-in path v)))

(nxr/register-placeholder! :glitter/value
                           (fn [event] (get-in event [:glitter/dom-event :glitter/value])))

(nxr/register-action! :action/toggle-smooth
                      (fn [state] [[:effect/assoc-in [:smooth] (not (:smooth state))]]))

(nxr/register-action! :action/toggle-paused
                      (fn [state] [[:effect/assoc-in [:paused] (not (:paused state))]]))

(nxr/register-system->state! deref)
(nxr/on-error (fn [_ctx {:keys [err]
                         :as error}]
                (log/error err "glitter.nexus dispatch error" (dissoc error :err))))

(core/set-dispatch!
 (fn [event actions] (nxr/dispatch state event actions)))

(defn -main [& _]
  ;; :gl-area's :on-realize/:on-render/:on-resize/:on-tick trip a cosmetic
  ;; dev-time hiccup warning on every render (glitter.core, ported from
  ;; Replicant, flags any prop key starting with "on" as a probable :on {}
  ;; mistake) — harmless, see CONTRIBUTING.md invariant #10 and docs/guide/
  ;; gl-area-widget-layer.md for why there's no practical way to silence it
  ;; from here: glitter.assert's checks are macro-expanded into glitter.core
  ;; at glitter.core's OWN compile time, which happens the moment this ns
  ;; form's own :require of glitter.app/glitter.gtk runs — before any code
  ;; in this file, -main included, ever executes. (glitter.env/configure!
  ;; :glitter/asserts? false) genuinely works, but only if called before
  ;; glitter.core is first required anywhere in the process — not reachable
  ;; from inside this namespace's own -main.
  ;; GLITTER_GL_DEMO_QUIT_MS auto-closes the window after N ms (smoke
  ;; testing); unset, the window stays open until closed. Mirrors the
  ;; original's GLIMMER_GL_DEMO_QUIT_MS.
  ;; GLITTER_GL_DEMO_SHAPE / GLITTER_GL_DEMO_SMOOTH seed the initial shape
  ;; and shading for deterministic demo captures; unset/unrecognised values
  ;; leave today's defaults (:cube, flat) unchanged.
  (let [quit-ms (some-> (System/getenv "GLITTER_GL_DEMO_QUIT_MS") Integer/parseInt)
        shape   (parse-shape (System/getenv "GLITTER_GL_DEMO_SHAPE"))
        smooth? (contains? #{"1" "true"} (System/getenv "GLITTER_GL_DEMO_SMOOTH"))]
    (when shape (swap! state assoc :shape shape))
    (when smooth? (swap! state assoc :smooth true))
    (apply app/run (fn [window] (gtk/mount! window view state))
           :app-id "glitter-gl.plasma"
           :title  "glitter-gl • plasma mesh"
           :width  900 :height 640
           (when quit-ms [:auto-quit-ms quit-ms]))))
