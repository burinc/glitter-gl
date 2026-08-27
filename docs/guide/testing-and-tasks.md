# Testing and tasks

glitter-gl has two layers of verification that catch different classes of
bug: a headless unit suite, and `bb smokes`, a live-GTK smoke that opens
a real window, immediately followed by a second headless check. Both
matter for the same reason glitter's own guide gives: this project's one
real bug (`:gl-area`'s handlers silently never firing under the
`:connect` hook glitter's own docstring recommends) was "obviously
correct" against the headless suite and wrong only when actually run
against live GTK. See [`examples.md`](examples.md) for what each of the
two `bb smokes` steps individually pins.

## Unit suite: `jolt -M:test` / `bb test`

`test/glitter_gl/test_runner.clj` is the entry point (`deps.edn`'s `:test`
alias points `-m` at it). It requires 24 test namespaces, one per
`src/glitter_gl` file except `gtk.clj` (which needs a live widget registry
and is exercised by the smoke instead, not a headless test), runs
`clojure.test` against all of them, and calls `(System/exit code)`
directly on failure rather than any resolve-guarded exit path.

Run it:

```sh
jolt -M:test     # or: bb test
```

Measured just now:

```
Ran 178 tests. 559 assertions passed, 0 failures, 0 errors.
----
tests: 178 assertions: 559 passed / 0 failed
```

That's 178 `deftest` forms and 559 individual assertions. Don't multiply
one by the other; a single `deftest` built on `clojure.test/are` can
contribute many assertions under one test count. If this number drifts in
your own run, re-measure with `bb test 2>&1 | tail -3` rather than trusting
this page: it's a snapshot, not a promise.

## `offscreen_test.clj`: the suite is not purely in-memory

Almost everything else in the suite runs against pure data (vectors,
matrices, mesh buffers, generated GLSL strings) with no GPU involved at
all. `offscreen_test.clj` is the exception: it asks GDK for a context bound
to the display rather than to a window surface
(`gdk_display_create_gl_context`, GTK 4.6+), compiles a real shader program
against it, renders one triangle into an RGBA32F texture, and reads the
texel back: a real render-to-texture round trip, not a mock of one. The
test asserts on **exact** float values (`[0.25 0.5 0.75 1.0]`), which only
works because those specific numbers are exact in binary32; that's a
sharper assertion than anything relying on interpolation tolerance would
give.

Measured on this machine, from the same `bb test` run above:

```
offscreen GL 4 . 1 Apple M1 Pro
```

**What it means when this is the test that fails on a new machine**: not
necessarily that glitter-gl broke. `off/ensure-current!` is designed to
degrade to a printed skip; `off/ensure-current!`'s own usage comment in
`offscreen.clj` shows the pattern (`(if-let [err (:error ...)] (println "no
offscreen GL:" err) ...)`), and `offscreen_test.clj`'s own docstring states
it plainly: it "Skips (with a printed reason) when no display is
available", calling a CI box without one a legitimate environment, not a
failure. A genuine CI runner with no windowing system, or a GL driver
stuck below 3.2 core, is exactly the case this test is built to tolerate
rather than fail on. If
you see a *skip* printed here, that's the offscreen path degrading
correctly, not the library breaking; a `:fail`/`:error` assertion actually
firing is the real signal to chase.

## `bb smokes`: the live-GTK smoke and headless check

```sh
bb smokes     # jolt -M:gl-area-smoke, then jolt -M:check
```

`bb smokes` runs `gl-area-smoke` (a live `:gl-area` mounted through the
real reconciler) and `check` (headless shader/geometry/registration
sanity) in sequence, and **stops at the first failure**. `bb.edn`'s
`smokes` task is a plain `(do (shell "jolt" "-M:gl-area-smoke") (shell
"jolt" "-M:check"))`, and babashka's task runner aborts a task body on the
first non-zero-exit `shell` call, so there's no extra control flow making
that happen; it falls out of `shell`'s default behavior.

It's a **local gate, not a CI one**, for one direct reason: `gl-area-smoke`
opens a real GTK window, and this project has no CI wired up to give it a
display (see "CI status" below). Run it yourself before opening a PR; see
[`examples.md`](examples.md) for what each of the two checks it runs
individually pins.

## The `jolt -M:<alias>` vs `jolt <task>` exit-code trap

This is the single most important operational fact on this page, because
getting it wrong doesn't look wrong: a suite that fails silently in CI is
worse than no CI at all, since it reports green.

A `deps.edn` `:tasks` entry does **not** propagate its child process's
exit status. `jolt test` (the task shorthand) runs the suite, prints
failures to stdout, and still exits 0. `jolt -M:test` (the `-M:<alias>`
form) correctly exits non-zero on failure. `bb.edn`'s tasks already use
the alias form throughout (`bb test`, `bb check`, `bb smokes`, etc. all
shell to `jolt -M:<alias>`, never the bare task name), so this is a trap
for anyone bypassing `bb` and driving `jolt` directly, not a live bug in
this repo's own tasks.

This was originally verified against jolt v0.6.3 (in glitter, where the
finding was first made), then reverified against `v0.7.23-10-gc50a3717`
rather than carried forward on the older claim: a minimal `deps.edn` with a
task shelling to a process that exits 7:

```
$ jolt fail            # task form
EXIT(task form)=0
$ jolt -M:fail          # alias form
EXIT(alias form)=7
```

**This is fixed on jolt `main`.** Re-running the same probe under
`v0.7.27-22-g502008db` gives `EXIT(task form)=7`: jolt now exits with the
command's status for a string task body. The fix sits under `[Unreleased]`
in jolt's own CHANGELOG and no tagged release contains it yet, so anyone on
`v0.7.27` or earlier still hits the swallow. Keep using `-M:<alias>` until
the fix ships in a release. **Always use `-M:<alias>`
(or a `bb.edn` task, which already does) to gate a build or a commit.**
The bare task form is fine for interactive use where a human is watching
stdout directly, and nowhere else.

## Quality tooling: lint, format, positional-args

```
bb lint / lint:strict                    clj-kondo (report | propagate exit code)
bb lsp:format / lsp:format-check          clojure-lsp reformat, or dry-run check
bb lsp:clean-ns / lsp:clean-ns-check      clojure-lsp ns cleanup, or dry-run check
bb lsp:diagnostics / lsp:check / lsp:fix  diagnostics | all dry-run checks | auto-fix
bb verify                                 pre-commit-shaped gate: lint (report) + test (must pass)
bb check:positional-args / :strict        fns with 3+ positional args (report | gate)
bb nrepl [port]                           jolt nREPL server (default 7888)
```

`bb lint`/`lint:strict`, every `bb lsp:*` task, and `bb verify` all need
**`clj-kondo` and `clojure-lsp` on `PATH`**. Without them the unit suite
and every demo/smoke still run fine; you just lose the fast local
lint/format/clean-ns loop, including the git pre-commit hook below, which
depends on both binaries too. `bb check:positional-args`/`:strict` and `bb
nrepl` need neither: the first is a plain babashka script
(`scripts/check_positional_args.clj`), the second only needs `jolt`.

`bb check:positional-args` flags any function with 3 or more genuinely
positional arguments (a leading 1-2 "subject" args before a `{:keys
[...]}` map are allowed), scanning `src/glitter_gl` only. Its `exceptions`
set is pre-populated with every finding whose only appearance is inside
one of the 22 glimmer-gl verbatim-port files: refactoring a ported file's
signature to a kwargs map would be a real behavioral change, which
invariant #1 reserves for its own reviewed commit, so those are permanent
exceptions, not TODOs. The two genuinely-adapted-layer findings
(`glitter-gl.gtk/connect!`,
`glitter-gl.scene/walk`) are deliberately *not* in that set; they stay
flagged as legitimately refactorable.

## `.clj-kondo/hooks/jolt_ffi.clj`: making FFI bindings visible to lint

`jolt.ffi/defcfn` binds a C symbol to a Clojure var. `glitter-gl.gl` and
`glitter-gl.gtk` are built almost entirely out of calls like this:

```clojure
(ffi/defcfn gtk-box-new "gtk_box_new" [:int :int] :pointer)
```

clj-kondo cannot see through a macro it doesn't know, so without a hook
every `defcfn`-bound name reports `Unresolved symbol` at its definition
and `Unresolved var` at every call site, enough noise across a
FFI-heavy library to make the linter useless as a signal. The hook rewrites
each `defcfn` form into an equivalent `defn` of the same name and arity
(derived from the declared C argument-type vector), with a body that's a
literal of the declared C return type. That buys clj-kondo three things it
couldn't otherwise infer: the var exists, its arity (so passing the wrong
argument count, exactly the FFI mistake that would otherwise surface only
as a native crash, is now a lint error instead), and a plausible return
type for downstream type-checking.

One deliberate deviation worth knowing if you ever port this hook
elsewhere: `:pointer` maps to a **number**, not `nil`. `glitter-gl.gl` and
`glitter-gl.gtk`'s own docstrings describe pointers as "plain machine
addresses (jolt numbers)," and the codebase leans on that directly:
`glitter.genum`/`glitter.widget` call `zero?`/arithmetic straight on
`:pointer`-typed return values. Mapping `:pointer` to `nil` (the choice the
hook this one adapts from makes, for raylib's opaque-handle pointers)
would trip a spurious `type-mismatch` ("Expected: number, received: nil")
against code that's already correct.

This is also why `.clj-kondo/hooks/jolt_ffi.clj` itself is tracked in git
while `.clj-kondo/.cache/` is not (see `.gitignore`): the hook is
configuration this project depends on to make `bb lint` usable at all;
the cache is disposable, regenerated analysis output.

## `bb verify` vs the git hook: they check different things

`bb verify` bundles a lint report plus `jolt -M:test` (which must pass)
into one command, a convenient manual gate to run before committing. **It
does not check formatting.** The installed git pre-commit hook
(`bb hooks:install`) is a separate, stricter, automatic gate that runs on
every `git commit`: lint errors-only, then `clojure-lsp format --dry`,
then `clojure-lsp clean-ns --dry` (`bb hooks:install:full` adds the full
unit suite as a fourth step). Concretely: a clean `bb verify` run tells
you the tests pass and lint has nothing new to report; it tells you
**nothing** about whether your file is formatted the way `clojure-lsp
format` wants it. If you've drifted on formatting, `bb verify` stays
green while the commit itself gets rejected by the hook.

This gap is real enough that contributors hit it in practice; it isn't a
hypothetical corner case. If a commit is rejected on step 2 or 3 of the
hook after `bb verify` passed clean, that's this gap, not a bug in either
tool: run `bb lsp:format` (or `bb lsp:clean-ns`) and re-commit.

## `bb hooks:install` / `:install:full` / `:uninstall`

`bb hooks:install` writes an executable `.git/hooks/pre-commit` via
`spit`. It is not tracked in the repo, so each clone opts in with its
own `bb hooks:install` run. The FAST hook (`bb.edn`'s own doc string calls
it "~2s") runs clj-kondo errors-only, `clojure-lsp format --dry`, and
`clojure-lsp clean-ns --dry`. `bb hooks:install:full` adds a fourth step,
the complete `jolt -M:test` suite, measured on this machine just now at
roughly 5.6 seconds wall time (`time bb test` → `5.586 total`), so budget
single-digit seconds more per commit with the full hook installed versus
the fast one. `bb hooks:uninstall` deletes the hook file and is idempotent:
it reports "no pre-commit hook found" rather than erroring if run twice.
`git commit --no-verify` skips the hook for one commit, useful for a
genuine emergency, not for routing around a failure the hook caught
correctly.

## CI status

**Not yet wired, deliberately.** This is a scope decision, not an
oversight, pending the repo going public and free CI runners applying to
it. Until then, `bb verify` plus `bb smokes` (run by a human, locally,
before opening a PR) is the whole gate. `CONTRIBUTING.md`'s "Before you
open a PR" section lists the exact four commands.
