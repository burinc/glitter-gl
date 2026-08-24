## What this changes

<!-- One or two sentences. -->

## Gates

<!-- The first three are headless and fast. `bb smokes` opens a real GTK4
     window, so it needs a display. Run it locally if you touched
     glitter_gl/gtk.clj, scene.clj, or app.clj. -->

- [ ] `bb test` passes (unit suite, headless)
- [ ] `bb lint` passes (clj-kondo)
- [ ] `bb lsp:format-check` passes (clojure-lsp formatting, **not** cljfmt)
- [ ] `bb smokes` passes, or N/A (live-GTK; needed if you touched
      `glitter_gl/gtk.clj`, `scene.clj`, or `app.clj`)

## If this touches a ported library file

<!-- Skip this section otherwise. See docs/guide/porting-and-attribution.md. -->

- [ ] Is this a behavioral change to one of the 22 verbatim-port files
      (`vector.clj` through `renderer.clj`)? If yes, it's its own reviewed
      commit, not folded into a port or formatting commit (invariant #1)
- [ ] `NOTICE.md` updated to reflect the change

## Invariants

<!-- CONTRIBUTING.md lists ten numbered invariants, each of which was a real
     bug at some point. If your change touches one, say which and why it's
     still safe. -->

- [ ] I read CONTRIBUTING.md § Invariants and this change doesn't regress one

## Environment you tested on

- OS / arch (`uname -sm`):
- jolt version (`jolt --version`):
- GTK4 version (`pkg-config --modversion gtk4`):

## Notes for the reviewer

<!-- Anything surprising, any deliberate deviation, anything you're unsure
     about. If you verified something against real GTK behaviour rather than
     reasoning about it, say so. That's the standard here. -->
