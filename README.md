# Mosaic Link

Mosaic Link is a standalone Material Android companion for the HK8 PRO MAX.
It has two deliberately narrow jobs:

1. synchronize the watch clock from the phone;
2. convert a compatible Clock2 file with the verified `wf_clock23` asset-only
   adapter and transfer it over the SiFli BLE protocol.

The last successfully connected watch is remembered and preferred on later
connection attempts. Opening the app does not connect automatically.

The app is a separate project from `clock2_hk8`. It does not modify the
Obsidian research vault.

## Verified compatibility

- **Input:** Apple Clock2 `.clock2` files, limited to the layer types that the
  compatibility checker accepts. It is not a general Clock2 renderer.
- **Watch profile:** HK8 PRO MAX, equipment code `6167`, firmware `2.09`, using
  the SiFli BLE service and the official `wf_clock23` donor module.
- **Display profile:** a 410 × 494 watchface viewport on the 485 × 520 panel.
- **Physical testing:** verified on one watch. Another watch with the exact
  same model, equipment code, firmware, protocol, and donor module should be
  compatible, but has not yet been physically tested.

This is not currently compatible with every watch sold as “HK8,” every HK8
PRO MAX revision, or arbitrary SiFli watches. Treat any other equipment code
or firmware as unsupported until its stock module and protocol are verified.

## Safety boundary

- A selected `.clock2` file is inspected before conversion. The current
  adapter accepts only layer combinations that can be mapped safely onto the
  proven stock watchface binary; unsupported files are rejected without
  contacting the watch.
- The official executable, descriptor, resource manager, and center caps are
  hash-pinned and never modified.
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
