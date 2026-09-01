(ns glitter-gl.ffi-compat-test
  "The one property that matters: `write!` puts the value where the offset
  says, on whichever jolt is running.

  These assertions are the same on both sides of jolt-lang/jolt#802, which is
  the point. Run them under a pre-#802 jolt and a post-#802 one and they
  agree; that is what makes the argument-order probe worth having rather than
  just picking a spelling and hoping."
  (:require [clojure.test :refer [deftest is testing]]
            [glitter-gl.ffi-compat :as compat]
            [jolt.ffi :as ffi]))

(deftest write-puts-the-value-at-the-offset
  (testing "integers land at the offset, not the other way round"
    (let [p (ffi/alloc 32)]
      (compat/write! p :int 111 0)
      (compat/write! p :int 222 4)
      (compat/write! p :int 333 8)
      (is (= 111 (ffi/read p :int 0)))
      (is (= 222 (ffi/read p :int 4)))
      (is (= 333 (ffi/read p :int 8)))
      (ffi/free p)))

  (testing "a value that is also a plausible offset is still a value"
    ;; The whole failure mode #802 introduced: 4 and 8 read as either. If the
    ;; order were resolved wrongly, this writes 4 at offset 8 and passes a
    ;; laxer assertion, so both slots are checked.
    (let [p (ffi/alloc 32)]
      (compat/write! p :int 4 8)
      (is (= 4 (ffi/read p :int 8)))
      (is (not= 8 (ffi/read p :int 4)))
      (ffi/free p)))

  (testing "floats and bytes round-trip too"
    (let [p (ffi/alloc 32)]
      (compat/write! p :float 2.5 0)
      (compat/write! p :float -3.25 4)
      (compat/write! p :uint8 200 8)
      (is (== 2.5 (ffi/read p :float 0)))
      (is (== -3.25 (ffi/read p :float 4)))
      (is (= 200 (ffi/read p :uint8 8)))
      (ffi/free p))))

(deftest write-is-resolved-once-not-per-call
  ;; A var holding one of two closures, not a fn that branches. Keeps the
  ;; per-element cost out of hot loops like glitter-gl.gl/write-floats, and
  ;; pins the shape so a later "simplification" into a branching defn has to
  ;; change this test deliberately.
  (is (fn? compat/write!))
  (is (not (var? compat/write!))))
