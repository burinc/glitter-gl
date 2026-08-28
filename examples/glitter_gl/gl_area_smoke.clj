(ns glitter-gl.gl-area-smoke
  "Automated :gl-area smoke against the LIVE GTK tree — pins glitter-gl's
  port of glimmer-gl's GtkGLArea integration (see glitter-gl.gtk). Verifies:

  1. [:gl-area ...] mounts through glitter's real reconciler (glitter.gtk),
     using the :apply hook glitter.widget/register-widget! provides.
  2. :on-realize fires (a GL context is made current, no GError).
  3. :on-render fires at least once without an exception escaping.
  4. :on-resize fires with the window's actual allocated size.

  Mirrors examples/glitter/smoke.clj's and scale_smoke.clj's shape in
  glitter — :auto-quit-ms closes the window after a fixed delay so
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
  ;; 2000ms, not the 500 this started with. The window has to realize AND
  ;; paint a frame before the timer closes it, and CI runs this under Xvfb on
  ;; mesa's llvmpipe, where every pixel is rasterized on the CPU on a shared
  ;; runner. 500ms was comfortable on a warm machine with a real driver and
  ;; had no margin anywhere else, which makes a missed :rendered? look like a
  ;; widget-layer regression rather than a slow frame. The cost of the higher
  ;; ceiling is nothing on a fast machine: the window closes on the timer, so
  ;; this only ever spends the full budget when something is genuinely slow.
  (app/run (fn [window] (gtk/mount! window view (atom {})))
           :title "glitter-gl :gl-area smoke" :width 320 :height 240
           :app-id "glitter-gl.gl-area-smoke" :auto-quit-ms 2000)
  (println :results (pr-str @results))
  (when-not (and (:realized? @results) (:rendered? @results)
                 (vector? (:resized? @results)) (nil? (:error @results)))
    (println :FAIL "see :results above")
    (System/exit 1)))
