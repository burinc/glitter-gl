(ns glitter-gl.app-test
  "glimmer-gl ships no test for app.clj either, but reactive-area is
  genuinely redesigned in this port (state-atom based, not reactive-cell
  based) — see the design spec's 'glitter-gl.scene / glitter-gl.app'
  section — so it gets real coverage, not just a load-smoke."
  (:require [clojure.test :refer [deftest is testing]]
            [glitter-gl.app :as app]
            [glitter-gl.scene :as scene]))

(deftest reactive-area-returns-a-gl-area-prop-map
  (let [state (atom {:zoom 1.0})
        scene-fn (fn [_state] (scene/camera {:eye [0 0 5] :target [0 0 0] :up [0 1 0]
                                             :fov 50 :near 0.1 :far 100}))
        props (app/reactive-area state scene-fn)]
    (testing "the standard :gl-area handler keys are all present as functions"
      (is (fn? (:on-realize props)))
      (is (fn? (:on-render props)))
      (is (fn? (:on-resize props)))
      (is (fn? (:on-tick props))))
    (testing "defaults"
      (is (= [3 2] (:version props)))
      (is (true? (:depth-buffer props)))
      (is (true? (:hexpand props)))
      (is (true? (:vexpand props))))))

(deftest reactive-area-honors-explicit-opts
  (let [state (atom {})
        scene-fn (fn [_state] (scene/camera {:eye [0 0 5] :target [0 0 0] :up [0 1 0]
                                             :fov 50 :near 0.1 :far 100}))
        props (app/reactive-area state scene-fn {:version [4 1] :depth-buffer false})]
    (is (= [4 1] (:version props)))
    (is (false? (:depth-buffer props)))))

(deftest keyval->kw-maps-known-keys-and-drops-unknown
  (is (= :up (app/keyval->kw 0xff52)))
  (is (= :w (app/keyval->kw 0x77)))
  (is (= :escape (app/keyval->kw 0xff1b)))
  (is (nil? (app/keyval->kw 0x99999))))
