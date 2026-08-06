(ns glitter-gl.renderer-test
  "glimmer-gl ships no test for renderer.clj (verified: no renderer_test.clj
  in its test/ dir) — this is a new, minimal addition for the port, not a
  behavior change. Confirms the namespace loads and its two public entry
  points exist as functions; the renderer's real GL behavior is exercised
  only by a live app (no live app currently exists that uses it — see the
  design spec's Known gaps section)."
  (:require [clojure.test :refer [deftest is]]
            [glitter-gl.renderer :as renderer]))

(deftest public-api-present
  (is (fn? renderer/make-renderer!))
  (is (fn? renderer/draw!)))
