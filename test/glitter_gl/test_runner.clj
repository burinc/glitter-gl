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

(defn- exit [code]
  (cond
    (resolve 'jolt.host/exit) ((resolve 'jolt.host/exit) code)
    (resolve 'System/exit)    ((resolve 'System/exit) code)
    :else nil))

(defn -main [& _]
  (let [namespaces '[glitter-gl.vector-test glitter-gl.vec2-test
                     glitter-gl.matrix-test glitter-gl.quaternion-test
                     glitter-gl.aabb-test glitter-gl.rect-test glitter-gl.circle-test
                     glitter-gl.line-test glitter-gl.plane-test glitter-gl.triangle-test]]
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
