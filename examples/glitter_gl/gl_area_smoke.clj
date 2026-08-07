(ns glitter-gl.gl-area-smoke
  "Automated :gl-area smoke against the LIVE GTK tree — pins glitter-gl's
  port of glimmer-gl's GtkGLArea integration (see glitter-gl.gtk). Verifies:

  1. [:gl-area ...] mounts through glitter's real reconciler (glitter.gtk),
     using the :apply hook glitter.widget/register-widget! provides.
  2. :on-realize fires (a GL context is made current, no GError).
  3. :on-render fires at least once without an exception escaping.
  4. :on-resize fires with the window's actual allocated size.

  Mirrors examples/glitter/smoke.clj's and scale_smoke.clj's shape in
  ~/dev/glitter — :auto-quit-ms closes the window after a fixed delay so
  this exits on its own for CI."
  (:require [glitter.app :as app]
            [glitter.core :as core]
            [glitter.gtk :as gtk]
            [glitter-gl.gtk :as glx]))

(defonce results (atom {:realized? false :rendered? false :resized? nil :error nil}))

(defn view [_state]
  [:box {:spacing 0}
   [:gl-area
    {:version [3 2] :depth-buffer true :hexpand true :vexpand true
     :on-realize (fn [area]
                   (glx/make-current area)
                   (if-let [err (glx/gl-area-error-message area)]
                     (swap! results assoc :error err)
                     (swap! results assoc :realized? true)))
     :on-render  (fn [_area] (swap! results assoc :rendered? true))
     :on-resize  (fn [_area w h] (swap! results assoc :resized? [w h]))}]])

(core/set-dispatch! (fn [_event _actions] nil))

(defn -main [& _]
  (app/run (fn [window] (gtk/mount! window view (atom {})))
           :title "glitter-gl :gl-area smoke" :width 320 :height 240
           :app-id "glitter-gl.gl-area-smoke" :auto-quit-ms 500)
  (println :results (pr-str @results))
  (when-not (and (:realized? @results) (:rendered? @results)
                 (vector? (:resized? @results)) (nil? (:error @results)))
    (println :FAIL "see :results above")
    (System/exit 1)))
