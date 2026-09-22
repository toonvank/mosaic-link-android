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
- The official executable, descriptor, resource manager, and center caps are
  hash-pinned and never modified.
- The UI requires the user to activate a different official face before an
  install begins.
- BLE errors stop the transfer; there is no factory-reset feature.

## Externally sourced binary dependencies

Two files in this repository are **not original Mosaic Link code** and are
**not covered by the MIT license**:

- `app/src/main/jniLibs/armeabi-v7a/libezip.so` — the SiFli eZip image
  encoder extracted from the Wearfit package for personal device testing;
- `app/src/main/assets/stock_wf_clock23.zip` — the official stock watchface
  package used as a donor.

They are the property of their respective owners (SiFli / Wearfit) and are
included here as-is for personal-device interoperability testing. All
application code and UI are original and MIT-licensed.

## License

The application source code is licensed under the MIT license — see
[LICENSE](LICENSE). The binary dependencies listed above are excluded from
this license and remain the property of their owners.

## Continuous integration

`.github/workflows/android.yml` runs unit tests and Android lint, builds the
debug APK, and uploads it as the `mosaic-link-debug` workflow artifact. The
workflow does not sign or publish a release build.
