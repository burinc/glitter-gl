(ns glitter-gl.test-runner
  "Entry point for `jolt -M:test`. Requires each glitter-gl test namespace and
  runs clojure.test against it. Exits non-zero on any failure."
  (:require [clojure.test :as t]))

(defmethod t/report :error [m]
  (t/with-test-out
    (t/inc-report-counter :error)
    (println "\nERROR in" (t/testing-vars-str m))
    (when (seq t/*testing-contexts*) (println (t/testing-contexts-str)))
    (when-let [message (:message m)] (println message))
    (when-let [e (:actual m)]
      (if (instance? Throwable e)
        (do (println "  ->" (.getName (class e)) ":" (ex-message e))
            (when-let [d (ex-data e)] (prn d))
            (when-let [c (ex-cause e)]
              (println "  caused by:" (.getName (class c)) ":" (ex-message c))))
        (prn e)))))

(defn- exit
  "Terminate the process with `code`.

  Call System/exit DIRECTLY. `System/exit` is a static-method interop FORM,
  not a var, so `(resolve 'System/exit)` is ALWAYS nil — under Jolt and on
  the JVM alike. A cond guarded on that resolve therefore never fires and
  silently falls through to nil, which is what the previous version of this
  fn did: the suite printed its failures and still exited 0, so `jolt test`
  could not fail CI at all. `jolt.host` ships no `exit` either, so that
  branch was dead for the same reason.

  Verified during the final whole-branch review: a deliberately-failing
  assertion made `jolt -M:test` exit non-zero after this fix (it exited 0
  with the same failure before it), and exits 0 again once the suite is
  genuinely green. Same bug and same fix as glitter's own
  `glitter.test-runner/exit` (see its docstring)."
  [code]
  (System/exit code))

(defn -main [& _]
  (let [namespaces '[glitter-gl.vector-test glitter-gl.vec2-test
                     glitter-gl.matrix-test glitter-gl.quaternion-test
                     glitter-gl.aabb-test glitter-gl.rect-test glitter-gl.circle-test
                     glitter-gl.line-test glitter-gl.plane-test glitter-gl.triangle-test
                     glitter-gl.sphere-test glitter-gl.polygon-test glitter-gl.bezier-test
                     glitter-gl.intersect-test glitter-gl.mesh-test glitter-gl.glmesh-test
                     glitter-gl.primitives-test glitter-gl.polyhedra-test
                     glitter-gl.gl-test glitter-gl.shader-test glitter-gl.offscreen-test
                     glitter-gl.renderer-test glitter-gl.scene-test
                     glitter-gl.app-test]]
    (doseq [ns namespaces]
      (try (require ns :reload)
           (catch Throwable e
             (println "ERROR requiring" ns ":" (pr-str e)))))
    (let [results (apply t/run-tests namespaces)
          failed (+ (:fail results 0) (:error results 0))]
      (println "----")
      (println "tests:" (:test results 0)
               "assertions:" (:pass results 0) "passed /"
               failed "failed")
      (when (pos? failed) (exit 1)))))
