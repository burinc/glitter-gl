# Demos

Animated previews of glitter-gl's example gallery: the plasma demo (four takes) plus ripple, orbit, knot, gears, textured and picking. Regenerate with `bb record` (maintainer-only: it needs an internal capture tool that is not publicly released). The GIFs here are committed, so you do not need it to browse them.

## the plasma demo

### plasma-cube

the default cube, rotating under the composable plasma/stripes shader

![plasma-cube](plasma-cube.gif)

### plasma-sphere

the same shader on a sphere

![plasma-sphere](plasma-sphere.gif)

### plasma-tetra

the same shader on a tetrahedron

![plasma-tetra](plasma-tetra.gif)

### plasma-smooth

smooth shading, against the flat shading of the takes above

![plasma-smooth](plasma-smooth.gif)

## the ripple demo

### ripple

a full-screen fragment shader, no mesh, no lighting, no camera

![ripple](ripple.gif)

## the orbit demo

### orbit

six distinct solids orbiting above a ground plane, lit and shadowed, mounted via reactive-area

![orbit](orbit.gif)

## the knot demo

### knot

a (2,3) trefoil torus knot generated from scratch, 2400 quads

![knot](knot.gif)

## the gears demo

### gears

three counter-rotating cog outlines, flat-shaded in 2D, no lighting or camera

![gears](gears.gif)

## the textured demo

### textured

a rotating cube wearing a procedural checkerboard texture, generated at runtime, no image file

![textured](textured.gif)

## the picking demo

### picking

a ground plane and a back wall with a marker at wherever the pointer's world-space ray hits, pointer-driven

![picking](picking.gif)

This take is captured by hand, not by `bb record`: `picking` is pointer-driven and the recorder's steering is keyboard-only, so an actual recording run would show the scene at rest with no marker at all. `docs/demos/ledger.edn`'s `picking` entry is kept in sync by hand for the same reason, so an unforced `bb record` reports it up to date rather than attempting to re-capture it.

