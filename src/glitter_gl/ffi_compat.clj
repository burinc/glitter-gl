(ns glitter-gl.ffi-compat
  "One `write!` that means the same thing on every jolt.

  jolt 0.8.0 (jolt-lang/jolt#802) swapped `jolt.ffi/write`'s last two
  arguments, from `(write p type offset value)` to `(write p type value
  offset)`. An offset and a value are both plain integers, so a call written
  for one order does not fail on the other. It writes to the wrong address
  and reports nothing, which is the worst shape a breaking change can take.

  Rather than pick a side and gate the whole library behind a jolt that is
  not released yet, resolve the order once at load and expose a `write!`
  that always reads value before offset. Call sites are then written for the
  world jolt is moving to, and run correctly on the one it has shipped.

  When to delete: once every jolt this project supports is 0.8.0 or newer. At
  that point `(write! p t v off)` is exactly `(ffi/write p t v off)`, so the
  removal is a mechanical rename plus a `git rm`.

  Do NOT treat declaring `:jolt/min-version \"0.8.0\"` as reaching that point on
  its own. jolt honours that key only from #804, which merged AFTER #802
  reversed the arguments and shipped in the same release, so every runtime
  carrying the OLD order predates the key and ignores it as unknown data. The
  floor is prospective by construction: it guards the NEXT break, not this one.
  A runtime old enough to need this shim will read the floor, not understand
  it, run anyway, and corrupt memory in the shim's absence. Measured on a
  sibling project: a released v0.7.29 with the floor declared did not refuse,
  it dropped namespaces with \"ERROR requiring ... : nil\" and reported a green
  half-suite.

  Where this pattern stops working: a probe normalises BEHAVIOUR at a boundary
  this library owns, which is why it fits here, since `write!` wraps a call we
  make and nothing downstream reads its result as data. It cannot normalise
  DATA that crosses a boundary someone else interprets. A function returning a
  layout descriptor whose shape changed between runtimes must not probe: its
  output would vary by whichever jolt happened to be loaded, and a generator
  whose result moves under you is worse than one that refuses on the wrong
  runtime."
  (:require [jolt.ffi :as ffi]))

(defn- value-before-offset?
  "Probes this jolt's `ffi/write` and answers true when it takes the value
  before the offset.

  Probing beats parsing a version string, because it asks the question that
  actually matters and stays correct for a dev build whose version parses
  below the release that changed the order.

  The seeding step works because a write whose value EQUALS its offset means
  the same thing under either order, so those four bytes can be set to known
  contents before the order is known. That is what makes the result
  deterministic rather than a bet on freshly allocated memory being zeroed,
  which is itself something jolt 0.8.0 changed."
  []
  (let [p (ffi/alloc 16)
        byte-at (fn [i] (ffi/read p :uint8 i))]
    (try
      (doseq [n [1 2 9 10]] (ffi/write p :uint8 n n))
      (ffi/write p :uint8 1 9)
      (ffi/write p :uint8 2 10)
      (let [value-first?  (and (= 1 (byte-at 9)) (= 2 (byte-at 10)))
            offset-first? (and (= 9 (byte-at 1)) (= 10 (byte-at 2)))]
        (when (= value-first? offset-first?)
          (throw (ex-info "cannot tell jolt.ffi/write's argument order apart"
                          {:probe (zipmap [1 2 9 10] (map byte-at [1 2 9 10]))})))
        value-first?)
      (finally
        (ffi/free p)))))

(def ^{:arglists '([p type v offset])}
  write!
  "Writes value `v` of `type` into pointer `p` at `offset`, on any jolt.

  Argument order matches jolt 0.8.0's `ffi/write`: value, then offset. The
  order is resolved once at load rather than tested per call, so a hot loop
  like `glitter-gl.gl/write-floats` pays one indirect call and no branch."
  (if (value-before-offset?)
    (fn [p type v offset] (ffi/write p type v offset))
    (fn [p type v offset] (ffi/write p type offset v))))
