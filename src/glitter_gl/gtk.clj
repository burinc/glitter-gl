(ns glitter-gl.gtk
  "glitter-gl's bridge into glitter's GTK4 widget tree: the GTK widget glitter
  itself omits but a GL app needs — GtkGLArea (the OpenGL drawing surface).
  Requiring this namespace registers one hiccup tag into glitter's widget
  registry so the whole UI, GL pane included, is one reconciled glitter tree:

    [:gl-area {:version [3 2] :on-realize f :on-render f :on-resize f
               :on-tick f :on-motion (fn [area x y])}]

  The GLArea is inherently imperative — its realize/render/resize signals
  build and drive raw GL objects, and render returns a gboolean — so those
  handlers are wired directly (see `gl-area-spec`'s :connect) rather than
  through glitter's uniform void(widget,data) signal path. This is the same
  shape glitter.widget/register-widget! documents as its motivating example
  for the :connect hook (see glitter.widget's register-widget! docstring).

  Ported from glimmer-gl.gtk (see NOTICE.md). Unlike glimmer-gl.gtk, this
  namespace does NOT register :scale — glitter already ships a richer
  first-party :scale widget (min/max/step/value/digits/draw-value, with
  :on-value-changed already wired through the standard signals table). See
  the design spec's 'glitter-gl.gtk' section for why."
  (:require [glitter.ffi :as g]
            [glitter.widget :as w]
            [jolt.ffi :as ffi]))

;; --- GtkGLArea ---------------------------------------------------------------
(ffi/defcfn gtk-gl-area-new
  "gtk_gl_area_new" [] :pointer)
(ffi/defcfn gtk-gl-area-make-current
  "gtk_gl_area_make_current" [:pointer] :void)
(ffi/defcfn gtk-gl-area-queue-render
  "gtk_gl_area_queue_render" [:pointer] :void)
(ffi/defcfn gtk-gl-area-set-required-version
  "gtk_gl_area_set_required_version" [:pointer :int :int] :void)
(ffi/defcfn gtk-gl-area-get-error
  "gtk_gl_area_get_error" [:pointer] :pointer)
(ffi/defcfn gtk-gl-area-set-has-depth-buffer
  "gtk_gl_area_set_has_depth_buffer" [:pointer :int] :void)

;; gtk_widget_add_tick_callback registers a callback synced to the widget's
;; GdkFrameClock; GTK passes the widget pointer on every invocation and only
;; fires while the widget is mapped + realized.
(ffi/defcfn gtk-widget-add-tick-callback
  "gtk_widget_add_tick_callback" [:pointer :pointer :pointer :pointer] :uint)

;; --- GtkEventControllerMotion (pointer tracking) -----------------------------
(ffi/defcfn gtk-event-controller-motion-new
  "gtk_event_controller_motion_new" [] :pointer)
(ffi/defcfn gtk-widget-add-controller
  "gtk_widget_add_controller" [:pointer :pointer] :void)
(ffi/defcfn gtk-widget-get-width
  "gtk_widget_get_width" [:pointer] :int)
(ffi/defcfn gtk-widget-get-height
  "gtk_widget_get_height" [:pointer] :int)

;; --- GtkEventControllerKey (keyboard) ----------------------------------------
;; A key controller; GTK emits "key-pressed"/"key-released" with
;; (keyval, keycode, modifier-state). In gl-area-connect! it is attached to the
;; toplevel root window, not the GLArea — GtkGLArea can't hold keyboard focus
;; (gtk_widget_grab_focus returns FALSE even with :can-focus set), so a
;; controller on the area itself never receives key events.
(ffi/defcfn gtk-event-controller-key-new
  "gtk_event_controller_key_new" [] :pointer)

;; --- GtkGestureClick (mouse buttons) -----------------------------------------
(ffi/defcfn gtk-gesture-click-new
  "gtk_gesture_click_new" [] :pointer)

;; --- root lookup -------------------------------------------------------------
(ffi/defcfn gtk-widget-get-root
  "gtk_widget_get_root" [:pointer] :pointer)

;; --- cursor (blank-cursor pointer lock) --------------------------------------
(ffi/defcfn gdk-cursor-new-from-name
  "gdk_cursor_new_from_name" [:string :pointer] :pointer)
(ffi/defcfn gtk-widget-set-cursor
  "gtk_widget_set_cursor" [:pointer :pointer] :void)

;; Monotonic clock (microseconds, gint64) — for frame-rate-independent animation
;; in :on-tick handlers.
(ffi/defcfn g-get-monotonic-time
  "g_get_monotonic_time" [] :int64)

(defn gl-area-error-message
  "Decode a GtkGLArea's GError (if any) to a string; nil when there is no error.
   GError is { guint32 domain; gint32 code; gchar *message; } — message at byte 8."
  [area]
  (let [err (gtk-gl-area-get-error area)]
    (when-not (ffi/null? err)
      (let [msg (ffi/read err :pointer 8)]
        (when-not (ffi/null? msg)
          (ffi/ptr->string msg))))))

(defn make-current
  "Make the GLArea's GL context current. Call before issuing GL on realize."
  [area] (gtk-gl-area-make-current area))

(def ^:private blank-cursor
  (delay (gdk-cursor-new-from-name "none" ffi/null)))

(defn hide-cursor!
  "Hide the system cursor over `widget` (e.g. the GLArea). Idempotent."
  [widget]
  (gtk-widget-set-cursor widget @blank-cursor))

(defn show-cursor!
  "Restore the default cursor over `widget`."
  [widget]
  (gtk-widget-set-cursor widget ffi/null))

(defn queue-render
  "Ask the GLArea to redraw on the next frame."
  [area] (gtk-gl-area-queue-render area))

(defn widget-width
  "A widget's current allocated width, in pixels (GTK4)."
  [w] (gtk-widget-get-width w))

(defn widget-height
  "A widget's current allocated height, in pixels (GTK4)."
  [w] (gtk-widget-get-height w))

(defn- connect!
  "Wire `cb` (a foreign-callable pointer) to `signal` on `widget`, retaining it
  for the process lifetime so GTK's raw pointer never dangles."
  [widget signal cb]
  (w/retain-callable! cb)
  (g/g-signal-connect-data widget signal cb ffi/null ffi/null g/CONNECT-DEFAULT))

;; --- :gl-area widget ---------------------------------------------------------
;; The handler props, each called with the GLArea pointer GTK hands us:
;;   :on-realize (fn [area])        build GL objects (context is current)
;;   :on-render  (fn [area])        issue draw calls; we always return TRUE
;;   :on-resize  (fn [area w h])    glViewport, store aspect, …
;;   :on-tick    (fn [area])        per-frame; we auto queue-render afterwards
;;   :on-motion  (fn [area x y])    pointer move (widget-relative px)
;;   :on-key     (fn [area keyval pressed?])  key press/release
;;   :on-button  (fn [area btn pressed? x y]) mouse press/release
(defn- gl-area-connect! [area props]
  (let [{:keys [on-realize on-render on-resize on-tick on-motion on-key on-button]} props]
    (when on-realize
      (connect! area "realize"
                (ffi/foreign-callable (fn [a _] (on-realize a))
                                      [:pointer :pointer] :void :collect-safe)))
    (when on-render
      (connect! area "render"
                (ffi/foreign-callable (fn [a _] (on-render a) 1)
                                      [:pointer :pointer] :int :collect-safe)))
    (when on-resize
      (connect! area "resize"
                (ffi/foreign-callable (fn [a w h _] (on-resize a w h))
                                      [:pointer :int :int :pointer] :void :collect-safe)))
    (when on-tick
      (gtk-widget-add-tick-callback area
                                    (let [cb (ffi/foreign-callable
                                              (fn [a _clock _data] (on-tick a) (queue-render a) 1)
                                              [:pointer :pointer :pointer] :int :collect-safe)]
                                      (w/retain-callable! cb) cb)
                                    ffi/null ffi/null))
    (when on-motion
      (let [ctl (gtk-event-controller-motion-new)]
        (gtk-widget-add-controller area ctl)
        (connect! ctl "motion"
                  (ffi/foreign-callable (fn [_ x y _] (on-motion area (double x) (double y)))
                                        [:pointer :double :double :pointer] :void :collect-safe))))
    (when on-key
      (let [armed? (atom false)
            arm (ffi/foreign-callable
                 (fn [_area _]
                   (when-not @armed?
                     (reset! armed? true)
                     (let [root (gtk-widget-get-root area)
                           ctl  (gtk-event-controller-key-new)]
                       (when-not (ffi/null? root)
                         (gtk-widget-add-controller root ctl)
                         (connect! ctl "key-pressed"
                                   (ffi/foreign-callable (fn [_ kv _kc _st _]
                                                           (on-key area (int kv) true) 0)
                                                         [:pointer :uint :uint :uint :pointer] :int :collect-safe))
                         (connect! ctl "key-released"
                                   (ffi/foreign-callable (fn [_ kv _kc _st _] (on-key area (int kv) false))
                                                         [:pointer :uint :uint :uint :pointer] :void :collect-safe))))))
                 [:pointer :pointer] :void :collect-safe)]
        (connect! area "realize" arm)))
    (when on-button
      (let [g (gtk-gesture-click-new)]
        (gtk-widget-add-controller area g)
        (connect! g "pressed"
                  (ffi/foreign-callable (fn [_ _n x y _]
                                          (on-button area 1 true (double x) (double y)))
                                        [:pointer :int :double :double :pointer] :void :collect-safe))
        (connect! g "released"
                  (ffi/foreign-callable (fn [_ _n x y _] (on-button area 1 false (double x) (double y)))
                                        [:pointer :int :double :double :pointer] :void :collect-safe))))))

(defn gl-area-spec []
  {:ctor      (fn [props]
                (let [area (gtk-gl-area-new)
                      [maj min] (or (:version props) [3 2])]
                  (gtk-gl-area-set-required-version area maj min)
                  (gtk-gl-area-set-has-depth-buffer
                   area (if (false? (:depth-buffer props)) 0 1))
                  area))
   :apply     (fn [_ _])     ; signals wire once via :connect; nothing to re-apply
   :connect   gl-area-connect!
   :container :none})

;; --- registration ------------------------------------------------------------
;; Registering at load time means a simple `(require '[glitter-gl.gtk])` in an
;; app makes [:gl-area ...] usable in glitter hiccup.
(w/register-widget! :gl-area (gl-area-spec))
