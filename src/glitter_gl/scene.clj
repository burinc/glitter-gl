(ns glitter-gl.scene
  "Declarative 3D scene graph — the GL analogue of glitter's hiccup tree.

  A scene is a hiccup tree of nodes; `flatten` compiles it into a pure render
  plan (threaded world matrices, collected lights, a single camera) that a
  renderer later realizes into GL draw calls.

  Node shapes (hiccup vectors):
    [:camera {:eye :target :up :fov :near :far}]   — exactly one
    [:light  {:dir [x y z] :color [r g b]}]        — directional, collected anywhere
    [:group  {:transform <Matrix44>} & children]   — threads world matrix
    [:mesh   {:geom <Mesh> :material <kw> :cast-shadow <bool>}]
  The render plan: {:camera <props|nil> :lights [...] :items [...]}, each item
  {:world <Matrix44> :geom <Mesh> :material <kw> :cast-shadow <bool>}.

  Ported from glimmer-gl.scene (see NOTICE.md), with one deliberate
  deviation: `plan` no longer wraps the compiled plan in a
  glimmer.ratom/reaction. glitter has no per-node reactive-cell tracking —
  its own state-atom watcher (glitter.gtk/mount!) already recomputes the
  WHOLE view on every state change, so a GL scene built the same way (a pure
  function of state, recomputed on every call rather than dependency-tracked)
  fits glitter's model directly instead of needing its own tracking layer.
  See the design spec's 'glitter-gl.scene / glitter-gl.app' section."
  (:require [glitter-gl.matrix :as m]))

;; --- node constructors (readable scene building) -----------------------------
(defn camera [props]            [:camera props])
(defn light  [props]            [:light props])
(defn mesh   [props]            [:mesh props])
;; NB: must use `into`, NOT `(vec (list* …))` — list* splices a seqable final
;; child (every child node is a vector), flattening its contents into siblings.
(defn group  [transform & kids] (into [:group {:transform transform}] kids))

;; --- compiler ----------------------------------------------------------------
(defn- walk [node world items lights camera]
  (let [tag (nth node 0)]
    (case tag
      :group (let [local  (get (nth node 1) :transform (m/ident))
                   world' (m/mul world local)]
               (reduce (fn [acc c]
                         (walk c world' (acc 0) (acc 1) (acc 2)))
                       [items lights camera]
                       (drop 2 node)))
      :mesh  (let [p (nth node 1)]
               [(conj items {:world        world
                             :geom         (:geom p)
                             :material     (:material p)
                             :cast-shadow  (if (false? (:cast-shadow p)) false true)})
                lights camera])
      :light [items (conj lights (nth node 1)) camera]
      :camera [items lights (nth node 1)]
      [items lights camera])))

(defn flatten
  "Compile a declarative scene tree into a render plan. The root may be any
  node; wrap several top-level nodes in a :group."
  [node]
  (let [[items lights camera] (walk node (m/ident) [] [] nil)]
    {:camera camera :lights lights :items items}))

;; --- component expansion -----------------------------------------------------
(declare expand)

(defn- expand-children
  "Normalize a parent's child forms into a flat vector of expanded hiccup
  elements: splice (possibly nested) seqs, drop nils, expand each survivor. A
  bare vector is one child, not spliced (standard hiccup)."
  [xs]
  (letfn [(walk [acc x]
            (cond
              (nil? x) acc
              (seq? x) (reduce walk acc x)
              :else    (conj acc (expand x))))]
    (reduce walk [] xs)))

(defn expand
  "Expand component invocations ([fn args...] -> (apply fn args)) in a hiccup
  scene tree down to native nodes, recursively. A component is marked by a fn
  head (fn?, not ifn? — hiccup vectors are themselves callable, so ifn? would
  misclassify native nodes). This scene-graph mini-hiccup is independent of
  glitter's own widget hiccup (it's consumed by glitter-gl.renderer, never by
  glitter.core/reconcile), so glitter's own no-function-tags convention —
  glitter's AGENTS.md convention #10 (not glitter-gl's own gotcha list,
  which numbers things differently) — does not apply here."
  [node]
  (cond
    (nil? node) nil
    (vector? node)
    (let [head (first node)]
      (cond
        (keyword? head)
        (let [body   (next node)
              props? (and (seq body) (map? (first body)))
              props  (if props? (first body) {})
              kids   (if props? (rest body) body)]
          (into [head props] (expand-children kids)))
        (fn? head) (expand (apply head (rest node)))
        :else (throw (ex-info (str "unsupported scene node: " (pr-str node))
                              {:node node}))))
    :else (throw (ex-info (str "unsupported scene node: " (pr-str node))
                          {:node node}))))

(defn plan
  "Build a render plan from `state` and `scene-fn` — a pure (fn [state] ->
  hiccup), possibly mixing native nodes and [fn args...] component
  invocations, exactly like glitter's own state -> hiccup view function.
  Returns the flattened plan directly (a plain map — no reaction, no deref).
  Call this fresh from :on-render each frame; there is no dependency
  tracking to short-circuit, matching glitter's top-down re-render model."
  [state scene-fn]
  (flatten (expand (scene-fn state))))

;; --- render-time camera (ported from thi.ng.geom.gl.camera via glimmer-gl) ---
(defn perspective-camera
  "Build a camera map carrying :view and :proj matrices from the eye/target/up
  basis and a perspective frustum (fov degrees, aspect, near, far)."
  [{:keys [eye target up fov aspect near far]}]
  (let [view (m/look-at eye target up)
        proj (m/perspective fov aspect near far)]
    {:eye eye :target target :up up
     :fov fov :aspect aspect :near near :far far
     :view view :proj proj}))

(defn apply-camera
  "Merge a camera's :view/:proj into a draw-spec's :uniforms."
  [spec cam]
  (-> spec
      (assoc-in [:uniforms :view] (:view cam))
      (assoc-in [:uniforms :proj] (:proj cam))))
