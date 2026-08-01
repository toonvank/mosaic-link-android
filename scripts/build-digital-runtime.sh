#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
compiler_image='zxvcv/gcc-arm-none-eabi@sha256:34a209df195190b88e954f424bae3287123c640c5724f1887bbb3b04af242c88'
output_dir="$project_dir/build/native"

mkdir -p "$output_dir"
for stage in 1 4; do
  suffix="stage${stage}"
  if [[ "$stage" == 4 ]]; then suffix="digital"; fi
  docker run --rm --user "$(id -u):$(id -g)" \
    -v "$project_dir:/work" -w /work "$compiler_image" \
    arm-none-eabi-gcc \
    -mcpu=cortex-m33 -mthumb -mfpu=fpv5-sp-d16 -mfloat-abi=hard \
    -mabi=aapcs -Os -ffunction-sections -fdata-sections -fno-common \
    -fshort-enums -fshort-wchar -mlong-calls -fPIC -shared -nostartfiles \
    -nostdlib -static-libgcc -DDIGITAL_RUNTIME_STAGE="$stage" \
    -Wl,--gc-sections,-z,max-page-size=0x4 -Wl,-e,0 \
    native/digital_runtime.c -o "build/native/wf_clock443_${suffix}.nostrip.so"

  docker run --rm --user "$(id -u):$(id -g)" \
    -v "$project_dir:/work" -w /work "$compiler_image" \
    arm-none-eabi-strip -R .hash \
    "build/native/wf_clock443_${suffix}.nostrip.so" \
    -o "build/native/wf_clock443_${suffix}.so"
done

sha256sum native/digital_runtime.c \
  build/native/wf_clock443_stage1.so \
  build/native/wf_clock443_digital.so
