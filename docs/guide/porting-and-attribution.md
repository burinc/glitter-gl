# Porting and attribution

glitter-gl's source falls into three sourcing buckets. `NOTICE.md` (repo
root) is the authoritative, maintained ledger; this page explains what
the buckets mean, walks through the procedure that keeps the largest
bucket trustworthy, and summarizes the one real correction found along
the way. If this page and `NOTICE.md` ever disagree, `NOTICE.md` wins —
see "Keeping `NOTICE.md` current" below.

For what these files actually *do* — the three groups, where a mesh's
data changes shape on its way to the GPU, why the matrices are
column-major, shaders-as-data — see
[`geometry-and-shaders.md`](geometry-and-shaders.md), which this page
links back to.

## The three sourcing buckets

### Bucket 1: verbatim from glimmer-gl — 22 files

The entire geometry/matrix/mesh/shader/GL layer is a namespace-rename
port, and nothing else, of the equivalent files in
[glimmer-gl](https://github.com/jolt-lang/glimmer-gl). Per `NOTICE.md`'s
own porting ledger:

`vector.clj`, `vec2.clj`, `matrix.clj`, `quaternion.clj`, `aabb.clj`,
`rect.clj`, `circle.clj`, `line.clj`, `plane.clj`, `triangle.clj`,
`sphere.clj`, `polygon.clj`, `bezier.clj`, `intersect.clj` (14 pure
geometry/math files) — `mesh.clj`, `glmesh.clj`, `primitives.clj`,
`polyhedra.clj` (4 mesh files) — `shader.clj`, `gl.clj`, `offscreen.clj`,
`renderer.clj` (4 GL-plumbing files). 14 + 4 + 4 = **22**, plus every
corresponding test file except `renderer.clj`'s (glimmer-gl ships no
test for `renderer.clj`; `renderer_test.clj` here is new — see Bucket 3).

### Bucket 2: adapted for glitter's model — 3 core files, plus demo material

`gtk.clj`, `scene.clj`, `app.clj` carry glimmer-gl's design forward but
are not mechanical renames — each was redesigned against glitter's own
architecture (its state-atom model has no reactive cells; its widget
props arrive through `:apply`, not `:connect`). `geometry-and-shaders.md`
and [`scene-and-app.md`](scene-and-app.md) cover what each does;
this page's job is only the provenance.

Two more files in `examples/glitter_gl/` sit in the same bucket for a
different reason: they're ports of a *different* upstream —
`~/dev/jolt-examples/glimmer-gl-app`'s `gl_demo/*.clj`, not glimmer-gl
itself. `plasma_shader.clj` and `check.clj` are near-verbatim; `plasma.clj`
(from `gl_demo/core.clj`) is a real adaptation, rewriting its reactive-cell
control panel as glitter's state-atom-plus-action-dispatch pattern while
keeping the GL render loop's direct state-atom read/write.

### Bucket 3: new to glitter-gl

Not present in glimmer-gl at all: `examples/glitter_gl/gl_area_smoke.clj`
(the live-GTK smoke exercising `:gl-area` construct/realize/render/resize),
`test/glitter_gl/renderer_test.clj`, and `test/glitter_gl/app_test.clj` —
glimmer-gl ships no test for either `renderer.clj` or `app.clj`, even
though `renderer.clj`'s own source is a verbatim port (Bucket 1).

`NOTICE.md` also separately tracks a fourth kind of material — tooling
config (`.clj-kondo/hooks/jolt_ffi.clj`, `.clj-kondo/config.edn`,
`.lsp/config.edn`, `bb.edn` task bodies, `scripts/check_positional_args.clj`)
adapted from glitter itself. Same author, same org, no license file, no
attribution obligation — `NOTICE.md` lists it for provenance only, not
because it needs a grant. It isn't one of the three source-code buckets
above and isn't repeated here in detail; see `NOTICE.md`'s own entry.

## The lineage: two hops back to thi.ng/geom

Chasing where a geometry function actually came from takes two hops,
not one: **glitter-gl ports from glimmer-gl, and glimmer-gl ports from
[thi.ng/geom](https://thi.ng/geom)** (Karsten Schmidt, Apache License
2.0). `NOTICE.md`'s own attribution paragraph names the specific
thi.ng/geom modules this project's Bucket 1 derives from: the matrix
arithmetic, cofactor inversion, and constructor formulas
(`thi.ng.geom.matrix`); the mesh model, tessellation, and primitive
vertex/face definitions (`thi.ng.geom.{basicmesh,utils,cuboid,
tetrahedron,sphere,plane}`); and the shader-spec model
(`thi.ng.geom.gl.shaders`). A reader who wants the *original* design
rationale for any of Bucket 1's 22 files — not just what changed in
transit — has to go past glimmer-gl to thi.ng/geom itself; glimmer-gl's
own copies are themselves unmodified ports and don't add commentary of
their own.

## The Standard Verbatim Port Procedure

Bucket 1's 22 files are large — `gl.clj` alone is over 400 lines — and
"trust me, I only renamed the namespace" isn't a claim a reviewer can
verify by reading a diff of glitter-gl against nothing. The procedure
used at port time was a mechanical check rather than a manual review:
take the glimmer-gl source file, apply the namespace-rename substitution
mechanically (`;; sketch, not from the source`):

```clojure
;; illustrative only — not a script that lives in this repo
(sed 's/glimmer-gl/glitter-gl/g' path/to/glimmer-gl/src/glimmer_gl/mesh.clj)
```

and diff the result against the same substitution applied a *second*
way (e.g. via a different tool, or reapplied to the file as it landed
in this repo). If the two diffs agree, the only difference between the
glimmer-gl source and the glitter-gl file is the namespace rename —
nothing else moved. This is what makes "verbatim port" a checkable
claim about a specific commit rather than an assertion to take on
faith: it catches a smuggled logic change the same way a checksum
catches a corrupted download, independent of how carefully anyone
proofread the diff by eye.

## The formatting-pass exemption, in full

This is invariant #1's substance in `CONTRIBUTING.md`, and the single
most likely thing for a future contributor to get wrong — in either
direction.

The Standard Verbatim Port Procedure above was a **point-in-time**
verification, run once, at the moment each of the 22 files was ported.
It was never a promise to keep those files byte-identical to that sed
output forever. A project-wide `clojure-lsp format` / `clean-ns` pass —
run across the whole codebase, Bucket 1 included, to keep the git
pre-commit hook's `format --dry` gate meaningful — changes whitespace
and `:require` ordering only. It cannot change logic: that's what the
tool does by construction, not a property this project has to verify
per-file the way the sed-diff check verifies a hand-edited port. Running
it across the 22 verbatim-port files does not violate "do not improve
them," because reformatting isn't improving — there's no behavioral
decision being made, and the same source expression compiles to the
same code before and after.

Getting this wrong runs in both directions:

- Treating the exemption as broader than it is — using a "just
  formatting" pass to sneak in a real edit — defeats the whole point of
  Bucket 1 being verifiable. A logic change hidden inside a
  reformatting commit is exactly the failure mode the sed-diff
  procedure exists to catch, and a reviewer who trusts "it's just
  `clean-ns`" won't go looking for it.
- Treating the exemption as narrower than it is — insisting Bucket 1
  must stay byte-for-byte identical to the original port forever, even
  through formatter runs — either blocks legitimate tooling adoption or
  forces Bucket 1 to silently drift out of sync with the rest of the
  codebase's formatting conventions.

This project isn't the first to hit this tension: glitter's own
[`docs/guide/testing-and-tasks.md`](https://github.com/burinc/glitter/blob/main/docs/guide/testing-and-tasks.md)
records an identical reversal for its own Replicant-ported files, for
the same reason.

## What a real behavioral change requires

A genuine behavioral change to one of Bucket 1's 22 files — not a
rename, not a reformat — is not something the port procedure, or the
formatting exemption, cover at all. It requires:

1. Its own reviewed commit, never folded silently into a port or
   formatting commit.
2. A `NOTICE.md` entry recording what changed and why, in the same
   commit as the code change — not as a follow-up.

`CONTRIBUTING.md`'s own "Licensing" section states the same requirement
from the contributor's side: moving code between the verbatim/adapted/new
buckets, or introducing a new upstream source, means updating both
`NOTICE.md` and this page in the same PR.

## The one documented live-found correction

Bucket 2's `gtk.clj` carries the project's one significant live-found
correction: `:gl-area`'s realize/render/resize/tick/motion/key/button
handlers wire from the widget spec's `:apply` closure, guarded
idempotent per `[area event]`, **not** from `glitter.widget`'s
`:connect` hook — even though `:connect`'s own docstring names a
`GtkGLArea`'s realize/render/resize as its motivating example. Under
glitter's actual reconcile flow, `:connect` never sees an element's real
hiccup props, so a handler wired through it silently never fires; `:apply`
is called once per prop key, both at construction and on every
re-render, and does see them. This isn't a rename-vs-behavior question —
`gtk.clj` was never a Bucket 1 verbatim file, and the original adapted
design (before the correction) called for `:connect` in the first place.
Full mechanics, including the exact `create-node`/`set-attributes` code
path traced to prove `:connect` doesn't work here:
[`gl-area-widget-layer.md`](gl-area-widget-layer.md).

## Keeping `NOTICE.md` current

Any new ported/adapted file, or any new deviation in an already-ported
file, gets a line added to `NOTICE.md` in the same commit as the code
change, not as a follow-up. `NOTICE.md` is what a downstream consumer or
license auditor actually reads; this page is context for a contributor
trying to understand the shape of that ledger, not a substitute for it.
Where the two disagree, `NOTICE.md` wins.

## See also

- [`geometry-and-shaders.md`](geometry-and-shaders.md) — what these
  files do, grouped by the three-way split (pure geometry/math, mesh,
  GL plumbing), including the mesh → GL data-shape trace.
- [`gl-area-widget-layer.md`](gl-area-widget-layer.md) — the full
  `:apply`-vs-`:connect` correction referenced above.
- [`CONTRIBUTING.md`](../../CONTRIBUTING.md) (repo root) — the ten
  numbered invariants, including invariant #1's summary of this page.
- `NOTICE.md` (repo root) — the authoritative, file-by-file ledger.
- [thi.ng/geom](https://thi.ng/geom) and
  [glimmer-gl](https://github.com/jolt-lang/glimmer-gl) — the two
  upstream hops Bucket 1 traces back through.
