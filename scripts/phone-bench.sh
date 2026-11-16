#!/usr/bin/env bash
# Benchmarks Fily on a connected phone without speech: every model file given, on the CPU and on the
# GPU (OpenCL), through the app's real request path (debug build, FilyBench). Prints one line per
# configuration and writes build/phone-bench/results.tsv.
#
#   scripts/phone-bench.sh training/exports/fily-edge-v5-5h/Fily-EzFiles-350M-Q8_0.gguf [more.gguf ...]
#
# Needs: the debug APK installed, Assistant access (and content access) on in EzFiles Info, adb.
# Optional: RUNS=5, CPU_THREADS="4 6", GPU_UBATCH="64 128 256 512", SKIP_GPU=1.
set -euo pipefail
export MSYS_NO_PATHCONV=1

RUNS="${RUNS:-5}"
CPU_THREADS="${CPU_THREADS:-4 6}"
GPU_UBATCH="${GPU_UBATCH:-64 128 256 512}"
PACKAGE=com.ezfiles
OUT=build/phone-bench
mkdir -p "$OUT"
RESULTS="$OUT/results.tsv"
echo -e "label\tgpu\tthreads\tubatch\tloadMs\tfirstMs\twarmMedianMs\twarmTotalMs\tpssMb" > "$RESULTS"

wait_for() { # pattern, timeout seconds
  local deadline=$((SECONDS + $2))
  while (( SECONDS < deadline )); do
    if adb logcat -d | grep -qE "$1"; then return 0; fi
    sleep 3
  done
  return 1
}

run_config() { # remote model, label, gpu, threads, ubatch, cold(1 clears the GPU kernel cache)
  local model=$1 label=$2 gpu=$3 threads=$4 ubatch=$5 cold=$6
  adb shell am force-stop "$PACKAGE"
  if [[ "$cold" == 1 ]]; then adb shell run-as "$PACKAGE" rm -rf cache/opencl-kernels; fi
  adb logcat -c
  # In the foreground, so Android does not freeze the process mid-benchmark.
  adb shell am start -n "$PACKAGE/.android.LauncherAuto" > /dev/null
  wait_for "EzLlama.*Model loaded successfully" 120 || { echo "app model did not load: $label"; return 1; }
  adb shell "am broadcast -n $PACKAGE/com.ezfiles.android.FilyBenchReceiver --es spec 'model=$model;gpu=$gpu;threads=$threads;ubatch=$ubatch;runs=$RUNS;label=$label'" > /dev/null
  if ! wait_for "EzBench.*(SUMMARY label=$label |FAILED)" 1800; then echo "timed out: $label"; return 1; fi
  local line
  line=$(adb logcat -d -s EzBench | grep -E "SUMMARY label=$label |FAILED" | tail -1)
  echo "$line"
  adb logcat -d -s EzBench EzLlama EzGgml > "$OUT/$label.log"
  if [[ "$line" == *SUMMARY* ]]; then
    echo "$line" | sed -E 's/.*SUMMARY //' | tr ' ' '\n' | cut -d= -f2 | paste -sd '\t' >> "$RESULTS"
  fi
}

for local_model in "$@"; do
  name=$(basename "$local_model" .gguf)
  remote="/data/local/tmp/$(basename "$local_model")"
  if [[ "$(adb shell stat -c %s "$remote" 2>/dev/null | tr -d '\r')" != "$(stat -c %s "$local_model")" ]]; then
    adb push "$local_model" "$remote" > /dev/null
    adb shell chmod 644 "$remote"
  fi
  for t in $CPU_THREADS; do
    run_config "$remote" "$name-cpu-t$t" 0 "$t" 512 0 || true
  done
  if [[ "${SKIP_GPU:-0}" != 1 ]]; then
    first=1
    for ub in $GPU_UBATCH; do
      # The first GPU run starts without compiled kernels; the rest reuse the cache it fills.
      run_config "$remote" "$name-gpu-ub$ub" 1 4 "$ub" "$first" || true
      first=0
    done
  fi
done
adb shell am force-stop "$PACKAGE"
echo; column -t -s $'\t' "$RESULTS" 2>/dev/null || cat "$RESULTS"
