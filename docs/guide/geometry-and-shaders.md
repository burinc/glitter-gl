# Geometry and shaders

## Read this first: these files are frozen, and upstream is the reference

The 22 namespaces this page orients you in — `glitter_gl/vector.clj`
through `glitter_gl/renderer.clj` — are not authored in this repository.
They are verbatim namespace-rename ports of
[glimmer-gl](https://github.com/jolt-lang/glimmer-gl), which itself
ports [thi.ng/geom](https://thi.ng/geom). Nobody should change their
behavior here; a real behavioral change belongs in its own reviewed
commit against upstream, or in its own commit here with a `NOTICE.md`
entry (see
[`porting-and-attribution.md`](porting-and-attribution.md) for exactly
what that requires).

Because of that, **this is not API reference documentation.** The
reference is thi.ng/geom's own docs and source, and glimmer-gl's
(unmodified) copy of them. What this page gives you instead is
orientation: how the 22 files group, where the data changes shape as it
crosses from plain Clojure values to a GPU buffer, and the two design
decisions (column-major matrices, shaders as data) that a reader
new to this codebase would otherwise have to reconstruct by reading all
22 files. For "where this code came from and what changed on the way,"
see [`porting-and-attribution.md`](porting-and-attribution.md), which
this page links back to.

## The three groups

**14 pure geometry/math namespaces:** `vector`, `vec2`, `matrix`,
`quaternion`, `aabb`, `rect`, `circle`, `line`, `plane`, `triangle`,
`sphere`, `polygon`, `bezier`, `intersect`. Vec3/Vec2 arithmetic, 4×4
matrices, quaternion rotation, and one record type per geometric
primitive (axis-aligned box, rectangle, circle, line segment, plane,
triangle, sphere, polygon, Bézier/Catmull-Rom curve), plus
`intersect`'s ray tests for picking. All plain functions over
`defrecord`/`deftype` values — no protocols, no mutation. Two of these
(`aabb`, `rect`) carry a small, genuinely interesting scar from the
host: their extent field is stored as `sz`, not `size`, because in Jolt
a record field literally named `size` is shadowed by record
introspection (`.-size` would return the field count, not the value).
The public accessor is still `size`; only the storage field is
renamed. That's a Jolt constraint neither thi.ng/geom nor glimmer-gl
had to work around.

**4 mesh namespaces:** `mesh`, `glmesh`, `primitives`, `polyhedra`.
`mesh` is the composable data model (a mesh is a sequence of faces of
`Vec3` vertices) plus the ops that transform one — translate, scale,
tessellate, subdivide, compute normals. `primitives` and `polyhedra`
are constructors: cuboid, tetrahedron, plane, UV sphere; octahedron,
icosahedron, dodecahedron. `glmesh` is where a mesh stops being pure
data and becomes a GL buffer spec — the boundary the next section
traces in detail.

**GL plumbing: `shader`, `gl`, `offscreen`, `renderer`.** `shader` is
the shader-spec-as-data DSL (its own section below). `gl` is the raw
FFI layer — every `glGetString`/`glBufferData`/`glUniform*` call the
rest of the library needs, and nothing else (its own section below
too). `offscreen` solves a narrower problem: every other GL entry
point needs a *realized* `GtkGLArea` to have a current context, which
means GL code can normally only run inside a live GTK app. `offscreen`
asks GDK directly for a context bound to the display rather than a
surface (`gdk_display_create_gl_context`, GTK 4.6+), so the unit suite
can exercise real GL calls — real shader compilation, real buffer
uploads — from a headless test runner with no window at all. `renderer`
is the one file in this group that isn't infrastructure: it's a
complete two-pass shadow-mapped Blinn-Phong renderer (depth pass from
the light, then a lit pass sampling that depth texture), built entirely
out of the other three.

## Where a mesh becomes GL data

This is the part upstream's docs can't tell you, because it's specific
to how *this* codebase wires its own layers together — thi.ng/geom
documents the mesh model and the GL layer separately, not the path
data actually takes between them in glitter-gl's shipped renderer.

**Plain data.** A mesh is `(defrecord Mesh [faces])` in `mesh.clj`: a
vector of faces, each face a vector of `glitter-gl.vector/Vec3` records
wound counter-clockwise so the face normal points outward. A
constructor like `primitives/cuboid` builds one directly —

```clojure
(mesh/mesh
 [[c d h g]    ;; east  (+X)
  [a b f e]    ;; west  (-X)
  [f g h e]    ;; north (+Y)
  [a d c b]    ;; south (-Y)
  [b c g f]    ;; front (+Z)
  [d a e h]])) ;; back  (-Z)
```

six quad faces, each just four `Vec3` corners — nothing GL-shaped about
it yet.

**Tessellation and normals.** `mesh/triangles` fans every face down to
triangles via `mesh/tessellate-face` (a triangle is itself; a quad
splits into two triangles across a diagonal; a larger n-gon fans
around `v/centroid`). `mesh/->floats` then computes a normal per
triangle-corner — `mesh/face-normal` (the same flat normal for all
three corners of a triangle) in `:flat` mode, or `mesh/vertex-normals`
(every triangle touching a corner contributes its face normal, summed
and renormalized) in `:smooth` mode — and interleaves position and
normal into one flat sequence of doubles:

```clojure
{:data data
 :count (* 3 (count tris))
 :stride 6}
```

six doubles per vertex (`x y z nx ny nz`), three vertices per triangle,
no index buffer, no sharing between triangles even where corners
coincide.

**What actually reaches GL — and the pipeline that doesn't.**
`glmesh.clj` documents itself as *the* mesh → GL pipeline: `as-gl-buffer-spec`
compiles a mesh into a buffer spec of **separate** attribute buffers
(`{:attribs {:position {...} :normal {...}} :num-vertices N :mode
gl/GL-TRIANGLES}`), `make-buffers-in-spec` uploads each attribute to its
own VBO, `make-vertex-array` binds them into a VAO against a compiled
shader's attribute locations, and `draw-with-shader` issues the actual
draw call. It's real, pure where it can be (`as-gl-buffer-spec` needs no
GL context at all — that's exactly why `glmesh_test.clj` can exercise it
headlessly), and verbatim-ported from thi.ng's `gl.glmesh` + `gl.core`.

But it is **not** the path this project's own renderer or demo take.
Grep the whole tree for callers of `make-buffers-in-spec`,
`make-vertex-array`, or `draw-with-shader` outside `glmesh.clj` itself,
and the only hit is `glmesh_test.clj` — which, per its own comment,
exercises just the context-free half. `renderer.clj`'s private
`upload-mesh` instead calls `mesh/->floats` directly (the *interleaved*
single-buffer shape above, not `glmesh`'s separate-attribute one) and
hand-rolls the upload with eleven raw `glitter-gl.gl` calls in total —
VAO/VBO generation, bind/unbind, and attrib-array enables among them —
but the three that matter for the vertex layout are: one
`gl-buffer-data` to copy the whole interleaved blob into a single VBO,
then two `gl-vertex-attrib-pointer` calls describing that one buffer to
the GPU as two attributes — location 0 (`a_pos`) reads 3 floats at byte
offset 0, location 1 (`a_normal`) reads 3 floats at byte offset 12,
both at a 24-byte (6-float) stride. `gl/write-floats` marshals the
Clojure double sequence into a native `float*` via jolt's FFI
immediately beforehand, and is freed right after `gl-buffer-data`
copies it. The mesh is cached by value in the render state's `:meshes`
map, keyed on the `Mesh` record itself, so equal geometry (every column
in a scene, say) uploads once and every instance replays the same VAO.

The upshot for a reader: `glmesh.clj`'s pipeline is real, tested, and
the more general of the two — a future caller who wants
separate-attribute buffers or `draw-with-shader`'s composed draw call
has it available. But if you're tracing what `jolt -M:plasma` or
`jolt -M:check` actually uploads to the GPU today, follow
`mesh/->floats` → `renderer.clj`'s `upload-mesh`, not `glmesh.clj`.
Either way the draw call at the end is `gl-draw-arrays` — this codebase
never binds `glDrawElements` or an index buffer at all (see the `gl.clj`
section below), so every triangle's three vertices are always emitted
in full, whichever pipeline built the buffer.

## Why matrices are column-major

`matrix.clj`'s `Matrix44` stores its sixteen fields in column-major
order — each contiguous group of four is one column — because that is
the layout `glUniformMatrix4fv` expects on the wire, and `->vec` /
`shader/set-uniform!`'s `:mat4` case hand that layout straight through
with the transpose flag set to `GL-FALSE` ("don't transpose, this is
already what you want"). `transform-point`'s docstring states the
consequence directly: component *r* of the transformed point is
`Σ_c m{c}{r}·p_c`, and the translation column (`m30 m31 m32`) is added
last — i.e. translation lives in the *last group of four* values, not
scattered across the last position of each group. `translation`'s own
constructor makes the same point by construction:

```clojure
(defn translation ^Matrix44 [^double tx ^double ty ^double tz]
  (Matrix44. 1 0 0 0  0 1 0 0  0 0 1 0  tx ty tz 1))
```

If someone "fixed" this to row-major — reordering the sixteen fields so
each group of four reads as a row instead of a column — two things
would break, not one. First, every matrix already built by `translation`/
`scaling`/`rotate-x`/`rotate-y`/`rotate-z`/`perspective`/`ortho`/
`look-at` encodes its translation and axis vectors as column groups;
reinterpreting the same sixteen numbers as rows moves the translation
out of the last-four-values position into scattered single entries
across all four rows, which is a different matrix, not a transposed
view of the same one. Second, even if every constructor were rewritten
to match, `shader/set-uniform!` still uploads with `GL-FALSE` (no
transpose) — so unless that flag also flipped to `GL-TRUE` everywhere a
`:mat4` uniform is set, the GPU would receive data in the opposite
layout from what the shader's `mat4` type expects, silently transposing
every matrix multiply on the GPU side. `mul`'s cofactor pattern (the
`madd`/`msub` macros) is written to match the *current* column-major
layout too, so a row-major rewrite would need every arithmetic function
in the file re-derived, not just the storage order relabeled. This is
inherited unmodified from thi.ng/geom, which made the same column-major
choice for the same reason.

## Shaders as data

`shader.clj` doesn't hold GLSL strings. A shader is a plain map
declaring its interface — uniforms, attributes, varyings, fragment
outputs, an optional GLSL `:prelude` of helper functions, and the
`:version` string — with `:vs-main`/`:fs-main` bodies as vectors of
statements built from small expression nodes (`[:* a b]`, `[:. x :xyz]`,
`[:vec3 :a_pos 1.0]`, and so on; `compile-expr`/`compile-stmt` document
every node form). `sources` is the only function that turns any of this
into an actual GLSL string, and it needs no GL context to run — you can
call it from a REPL or a test and read the generated shader source
directly. `program` is the one function in the file that does need a
context: it compiles and links the generated GLSL and returns the spec
enriched with the program id and each uniform/attribute's real GL
location.

Because a shader spec is just a map, composing shaders is just map
composition. `merge-specs` combines several spec fragments — later
`:uniforms`/`:attribs`/`:varying`/`:fs-out` entries win on key
conflicts, `:vs-main`/`:fs-main` statement vectors concatenate in
argument order, `:prelude` strings concatenate. `examples/glitter_gl/
plasma_shader.clj` is the real worked example this project ships: a
shared vertex-stage-and-framing `base` map, a `plasma-module` (domain-warped
plasma via a GLSL helper in `:prelude`, its own `u_scale`/`u_warp`
uniforms), a `stripes-module` (animated stripes, its own `u_stripes`
uniform), and a `main-module` that blends the two effects by `u_mix`
and applies simple diffuse lighting — composed as

```clojure
(def shader-spec
  (sh/merge-specs base plasma-module stripes-module main-module))
```

Drop `stripes-module` from that call, or write a fourth module and add
it, and the composed shader changes shape with no edits to the other
three maps — each module owns only the uniforms and statements its own
effect needs. This is the intended reading of the shaders-as-data
model: reusable GLSL logic as merge-able data fragments, GLSL text
generated once at the very end.

## What `gl.clj` binds — and doesn't

`gl.clj` is a minimal FFI surface, not a general OpenGL binding: it
exists to compile shaders and fill buffers/VAOs/uniforms, and it stops
there. Its `defcfn` declarations cover context/state (`glClear`,
`glViewport`, `glEnable`/`glDisable`, `glBlendFunc`, `glScissor`),
buffer and vertex-array objects, shader/program compilation and
linking, uniform upload (`glUniform1f`/`2f`/`3f`/`4f`, the vector-array
forms, `glUniformMatrix4fv`), vertex attributes, face culling, textures,
and framebuffers (the render-to-texture path `renderer.clj`'s shadow
pass uses). That's the whole surface every other file in this project
calls.

What it deliberately leaves out, verifiably: there is no
`glDrawElements` binding anywhere in the file, so this codebase has no
index-buffer draw path at all — every draw call in `renderer.clj` and
`glmesh.clj` is a non-indexed `glDrawArrays`, matching the
never-deduplicated triangle-soup shape `mesh/->floats` produces (see
above). There's no compute-shader or debug-callback binding either.

One block is worth calling out on its own, because it doesn't fit "the
slice needed to compile shaders and fill buffers" and is easy to miss
reading top-to-bottom: a self-contained transform-feedback + geometry-shader
+ texture-buffer + query-object section, with its own header comment
explaining the intended use (GPU-side stream compaction — a
vertex+geometry program that conditionally emits survivors into a
buffer via transform feedback, a query object reporting how many, the
result read back through a texture buffer — chosen specifically because
the target macOS GL version has no compute shaders or SSBOs to do the
same job more directly). Grepping the tree for its bindings
(`gl-begin-transform-feedback`, `gl-tex-buffer`, `gl-begin-query`, and
neighbors) turns up no caller anywhere in `src/`, `examples/`, or
`test/` outside `gl.clj` itself — this is capability the port carries
forward, verbatim, not code exercised by anything glitter-gl currently
ships.

## See also

- [`porting-and-attribution.md`](porting-and-attribution.md) — where
  every one of these 22 files came from, the Standard Verbatim Port
  Procedure that keeps them that way, and what a real change to one of
  them would require.
- [`architecture.md`](architecture.md) — how this geometry/GL layer
  and the glitter-integration layer (`gtk`/`scene`/`app`) fit together
  as one repository, and how thin the seam between them actually is.
- [thi.ng/geom](https://thi.ng/geom) and
  [glimmer-gl](https://github.com/jolt-lang/glimmer-gl) — the reference
  documentation for everything these 22 files do, since none of it was
  designed here.
