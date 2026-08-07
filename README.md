# Mosaic Link

Mosaic Link is a standalone Material Android companion for the HK8 PRO MAX.
It has two deliberately narrow jobs:

1. synchronize the watch clock from the phone;
2. convert a compatible Clock2 file with the verified `wf_clock23` asset-only
   adapter and transfer it over the SiFli BLE protocol.

On startup, Mosaic Link connects automatically to the last successfully
connected watch. On a first launch, or when that watch is unavailable, it
connects to a nearby device advertising an HK8 name. If neither is found within
the startup scan, the nearby-device picker is shown instead.

The app is a separate project from `clock2_hk8`. It does not modify the
Obsidian research vault.

## Experimental visible-panel build

This branch now produces `0.2.0-experimental.8`. The earlier 485 × 520 attempt
overflowed horizontally on the watch. An independent HK8 PRO MAX
[grid test in the XDA Wearfit thread](https://xdaforums.com/t/w37-watch-7-cpu-hs6621.4362181/page-9)
reported roughly 434 visible horizontal pixels, so this build uses a
deliberately narrower geometry:

- root viewport: 410 × 494 → **434 × 494**;
- analog center: `(205,247)` → **`(217,247)`**;
- page glue: enabled → disabled, preventing the widened root from becoming
  pannable.

The patch is fail-closed: it starts from the hash-pinned official module,
requires each original byte sequence to occur exactly once, keeps the ELF size
unchanged, and pins the resulting output hash. The 494-pixel stock height is
left untouched; only the width reported visible by the grid test is added.

This 434 × 494 profile has passed offline validation and phone compilation but
still needs an on-watch visual test. Treat a boot loop as possible. Keep a
different official stock face active, close Wearfit, and retain the known-good
package before testing. Use stable `v0.1.1` for the watch-proven asset-only
path.

## Automatic fit behavior

Clock2 does not store a dedicated canvas-size field. The previous converter
used the largest layer as the canvas, which is wrong for faces whose hands use
large transparent frames. For example, the tested AP export has a 202 × 245
background but a 327 × 327 complication hand. Treating that hand as the canvas
made AUTO shrink the face and forced per-face fitting tweaks.

The converter now infers the logical canvas from the largest centered image
background. **Safe fit** is the default and uniformly contains the composition
inside 434 × 494, preserving every layer's proportions. **Fill screen** is the
only optional alternative; it uniformly fills the viewport and may crop the
outer edges.

Every raster, shape, label position, and secondary hand receives the same
composition transform. Distorting stretch modes and the unreliable automatic
choice are no longer exposed in the UI.

## Verified compatibility

- **Input:** Apple Clock2 `.clock2` files, limited to the layer types that the
  compatibility checker accepts. It is not a general Clock2 renderer.
- **Catalog source:** the public XEOS HK8 `508` and `501` feeds are shown with
  their supplied image previews. Their downloaded `.res` files use a separate,
  explicitly experimental SiFli custom-resource transfer path; they are not
  converted to Clock2 and do not alter the verified Clock2 donor-module flow.
- **Watch profile:** HK8 PRO MAX, equipment code `6167`, firmware `2.09`, using
  the SiFli BLE service and the official `wf_clock23` donor module.
- **Stable display profile:** a 410 × 494 watchface viewport.
- **Experimental display profile:** a 434 × 494 visible-area profile based on
  the HK8 grid measurement. The advertised 485 × 520 value is not treated as
  a verified addressable watchface area.
- **Physical testing:** verified on one watch. Another watch with the exact
  same model, equipment code, firmware, protocol, and donor module should be
  compatible, but has not yet been physically tested.

This is not currently compatible with every watch sold as “HK8,” every HK8
PRO MAX revision, or arbitrary SiFli watches. Treat any other equipment code
or firmware as unsupported until its stock module and protocol are verified.

## Clock2 conversion behavior

Mosaic Link keeps the stock module safe by turning Clock2 content into a
verified static background plus the central live hour, minute, and optional
seconds hands.

- Images, common date formats, shapes, and secondary hands are flattened.
- Image strips select the frame for the installation time.
- Videos use a still first frame; video animation is not transferred.
- Clock2 group headers are metadata and are ignored.
- Weather, sensor, and unsupported digital data layers are omitted and shown
  as conversion notes in the preview.
- A rejected or failed file never replaces the last successfully built file.

This is intentionally honest about partial conversion: “compatible with
notes” means the generated package is structurally safe, not that unavailable
Apple Watch services have become live HK8 complications.

## Safety boundary

- A selected `.clock2` file is inspected before conversion. The current
  adapter accepts only layer combinations that can be mapped safely onto the
  proven stock watchface binary; unsupported files are rejected without
  contacting the watch.
- Stable `v0.1.1` keeps the official executable, descriptor, resource manager,
  and center caps hash-pinned and unmodified. This experimental branch applies
  only its pinned 434 × 494 geometry patch to the executable; see above.
- The UI requires the user to activate a different official face before an
  install begins.
- BLE errors stop the transfer; there is no factory-reset feature.

## Local prototype dependency

SiFli eZip image encoding is performed by the Android ARMv7 encoder from the
locally inspected Wearfit package. This prototype is for personal device
testing. The binary is not claimed as original Mosaic Link code and should not
be redistributed without establishing its license. All application code and
UI are original.

## Repository visibility

Keep this repository **private in its current form**. A public push would also
publish these externally sourced binary dependencies:

- `app/src/main/jniLibs/armeabi-v7a/libezip.so`;
- `app/src/main/assets/stock_wf_clock23.zip`.

Before making the project public, either obtain redistribution permission for
both files or remove them from version control and require each user to import
their own legally obtained copies locally. The application source can then be
published under a chosen license without implying that the two binary
dependencies share that license.

## Continuous integration

`.github/workflows/android.yml` runs unit tests and Android lint, builds the
debug APK, and uploads it as the `mosaic-link-debug` workflow artifact. The
workflow does not sign or publish a release build.
