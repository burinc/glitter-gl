(ns glitter-gl.gtk
  "glitter-gl's bridge into glitter's GTK4 widget tree: the GTK widget glitter
  itself omits but a GL app needs — GtkGLArea (the OpenGL drawing surface).
  Requiring this namespace registers one hiccup tag into glitter's widget
  registry so the whole UI, GL pane included, is one reconciled glitter tree:

    [:gl-area {:version [3 2] :on-realize f :on-render f :on-resize f
               :on-tick f :on-motion (fn [area x y])}]

  The GLArea is inherently imperative — its realize/render/resize signals
  build and drive raw GL objects, and render returns a gboolean — so those
  handlers are wired directly from `gl-area-spec`'s :apply (see the
  correction note above :gl-area's :apply closure for why :apply, not
  :connect — glitter.core's create-node never passes real hiccup props to
  create-element/:connect; only the separate set-attributes/:apply path
  does) rather than through glitter's uniform void(widget,data) signal path.

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
;; CORRECTION (found live via the Task 17 smoke, 2026-08-07): glitter.core's
;; create-node calls `(r/create-element renderer tag-name (when ns {:ns ns}))`
;; — the options glitter.gtk's create-element (and therefore glitter.widget's
;; create!) receives is ONLY an :ns hint, never the hiccup element's real
;; props. A :connect hook (which only ever runs inside create!) can never see
;; :on-realize/:on-render/etc. under glitter's actual reconcile flow — this
;; differs from glimmer's single-shot creation model gtk.clj was originally
;; ported from. The real props arrive via a SEPARATE path: create-node's own
;; (set-attributes ...) call right after create-element returns, which routes
;; through set-attribute -> glitter.widget/apply-props! -> the widget spec's
;; :apply closure, called ONCE PER PROP KEY (not the whole map at once), and
;; again on every re-render. This is the exact same path :scale's min/max/step
;; re-ranging already relies on (see :scale's own docstring in
;; glitter.widget: "construct horizontal by default... :apply then re-ranges
;; ... on every render if they change" — :ctor never sees real props either).
;; So the fix is to wire from :apply, not :connect, guarded so each event
;; only ever connects once per widget (:apply may be called multiple times
;; for the same event: once per key-arrival during creation, and again on
;; every re-render).
;;
;; The handler props, each called with the GLArea pointer GTK hands us:
;;   :on-realize (fn [area])        build GL objects (context is current)
;;   :on-render  (fn [area])        issue draw calls; we always return TRUE
;;   :on-resize  (fn [area w h])    glViewport, store aspect, …
;;   :on-tick    (fn [area])        per-frame; we auto queue-render afterwards
;;   :on-motion  (fn [area x y])    pointer move (widget-relative px)
;;   :on-key     (fn [area keyval pressed?])  key press/release
;;   :on-button  (fn [area btn pressed? x y]) mouse press/release
(defonce ^:private wired (atom {}))
;; KNOWN V1 LIMITATION, found during Task 17's review: `wired` is keyed by
;; the raw GtkGLArea pointer (a plain machine address), with no release path
;; when a widget is destroyed. Beyond plain unbounded growth (a shape this
;; project already accepts elsewhere — glitter.gtk's own `memory` atom has
;; the same no-release characteristic), this is a genuine latent risk: if
;; GTK/glib ever reuses a freed :gl-area's address for a new widget, the new
;; widget would silently inherit the old one's `wired` entries and
;; `wire-once!` would skip connecting its real handlers — no exception, just
;; a GL area that never realizes. glitter.gtk's own `memory` atom sidesteps
;; this exact trap by keying off the tracking atom's Clojure identity
;; instead of the raw pointer (see its IMemory comment) — that pattern isn't
;; available here without changing glitter.widget's :apply contract to pass
;; a stable identity alongside the raw widget pointer, which is genuinely
;; out of scope for this fix. Not fixed now because no current call site in
;; this project destroys/recreates a :gl-area — Tasks 18-19's demo mounts
;; one GL area for the app's lifetime. Revisit if/when a future task
;; introduces dynamic :gl-area mount/unmount.

(defn- wire-once!
  "True the FIRST time `event` is seen for `area`; false (and no side effect)
  on any repeat call. See the correction note above :gl-area's :apply for why
  this guard is necessary."
  [area event]
  (let [seen (get @wired area #{})]
    (when-not (contains? seen event)
      (swap! wired update area (fnil conj #{}) event)
      true)))

(defn- gl-area-apply! [area props]
  (let [{:keys [version depth-buffer on-realize on-render on-resize on-tick
                on-motion on-key on-button]} props]
    (when version
      (let [[maj min] version]
        (gtk-gl-area-set-required-version area maj min)))
    (when (contains? props :depth-buffer)
      (gtk-gl-area-set-has-depth-buffer area (if (false? depth-buffer) 0 1)))
    (when (and on-realize (wire-once! area :on-realize))
      (connect! area "realize"
                (ffi/foreign-callable (fn [a _] (on-realize a))
                                      [:pointer :pointer] :void :collect-safe)))
    (when (and on-render (wire-once! area :on-render))
      (connect! area "render"
                (ffi/foreign-callable (fn [a _] (on-render a) 1)
                                      [:pointer :pointer] :int :collect-safe)))
    (when (and on-resize (wire-once! area :on-resize))
      (connect! area "resize"
                (ffi/foreign-callable (fn [a w h _] (on-resize a w h))
                                      [:pointer :int :int :pointer] :void :collect-safe)))
    (when (and on-tick (wire-once! area :on-tick))
      (gtk-widget-add-tick-callback area
                                    (let [cb (ffi/foreign-callable
                                              (fn [a _clock _data] (on-tick a) (queue-render a) 1)
                                              [:pointer :pointer :pointer] :int :collect-safe)]
                                      (w/retain-callable! cb) cb)
                                    ffi/null ffi/null))
    (when (and on-motion (wire-once! area :on-motion))
      (let [ctl (gtk-event-controller-motion-new)]
        (gtk-widget-add-controller area ctl)
        (connect! ctl "motion"
                  (ffi/foreign-callable (fn [_ x y _] (on-motion area (double x) (double y)))
                                        [:pointer :double :double :pointer] :void :collect-safe))))
    (when (and on-key (wire-once! area :on-key))
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
    (when (and on-button (wire-once! area :on-button))
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
   :apply     gl-area-apply!
   :container :none})

;; --- registration ------------------------------------------------------------
;; Registering at load time means a simple `(require '[glitter-gl.gtk])` in an
;; app makes [:gl-area ...] usable in glitter hiccup.
(w/register-widget! :gl-area (gl-area-spec))
