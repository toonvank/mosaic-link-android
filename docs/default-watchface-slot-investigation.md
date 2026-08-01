# Default watchface slot investigation

Date: 2026-08-01

## Goal

Determine whether Mosaic Link can replace one of the HK8 Pro Max's
preinstalled/default watchfaces, instead of replacing the single YaLan/custom
download slot.

## Watch and firmware

- Watch: HK8 PRO MAX (`C2:AB:11:1B:C5:F4`)
- Equipment code: 6167
- Firmware: 2.09
- SoC family: SiFli SF32LB551

## Tests performed

The existing SiFli type-0 store installer was tested with two official-module
packages:

1. An unmodified official `wf_clock449` package completed successfully.
2. The already watch-proven, asset-only Omega package using the byte-exact
   official `wf_clock23` executable completed successfully after retrying with
   the known-good 4096-byte slice size.

The first Omega attempt used a 2048-byte slice and timed out before the first
file completed. No `ENTIRE_END` was sent for that aborted transaction. The
watch remained usable, and the successful retry completed every file plus
`ENTIRE_END` with result 0.

Observed result: the successful package replaced the YaLan/custom downloaded
face. It did **not** replace a preinstalled/default face.

## Protocol finding

Mosaic Link's type-0 transfer commands only expose whole-install and file
transfer operations (`ENTIRE_START`, `FILE_START`, `FILE_DATA`, `FILE_END`,
`ENTIRE_END`, loss checking, termination, and space checking). No command was
found for listing, selecting, deleting, or overwriting a preinstalled face.

The Wearfit application protocol examined locally also has no identified
command that maps a downloaded package onto a chosen preinstalled watchface
slot.

## Firmware/framework finding

The SiFli SDK separates the two classes of watchface:

- Preinstalled faces are registered at firmware build time through
  `APP_BUILTIN_CLOCK_REGISTER` / `APP_BUILTIN_WATCHFACE_LAYOUT_REGISTER`
  section entries.
- Downloaded faces are installed into the external `watchface` program path
  and `ex/resource` resource path, then loaded from a dynamic watchface
  database.
- The module installer calls `app_uninstall()` and `app_install()` only for
  that external program/resource location.

An older implementation retained in the SDK Git history confirms that built-in
faces are registered first and dynamic modules are loaded afterward as
additional list entries. `app_clock_register()` does not perform an ID lookup
or replace an existing entry; it appends another descriptor. Therefore an
external module with a colliding ID is not a supported mechanism for shadowing
or replacing a built-in slot.

Relevant local sources used during the investigation:

- `SiFli-SDK/middleware/mod_installer/mod_installer.c`
- `SiFli-SDK/middleware/elm_rw/elm_rw.c`
- `SiFli-SDK/middleware/app_fwk/app_clock_main.h`
- `SiFli-SDK/middleware/lvgl/lvsf/lv_layout_loader.h`
- SDK commit `27e6b873`,
  `example/watch_demo/gui_apps/clock/app_clock_main.c`

## Current conclusion

The normal watchface installer cannot overwrite a default slot. It always
targets the dynamic/downloaded watchface area, which this HK8 firmware presents
as the YaLan/custom slot.

A true replacement would require modifying the firmware image or its compiled
built-in registration/resources. The captured 2.09 OTA payload is opaque and
has not been safely unpacked, patched, re-signed, or proven recoverable. No
firmware write was attempted.

## Safe state and next gate

- The watch was left operational with Omega in the YaLan/custom slot.
- No default/preinstalled face was changed.
- No app code was changed during this investigation.
- Do not add a "replace default slot" control to the app until a firmware image
  can be unpacked and rebuilt reproducibly, its authenticity checks are known,
  and recovery from a failed firmware update is independently proven.

