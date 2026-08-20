#!/usr/bin/env bash
set -euo pipefail

archive="$(mktemp)"
trap 'rm -f "$archive"' EXIT

cat \
  tools/fork-hardening.bundle.00.00 \
  tools/fork-hardening.bundle.00.01 \
  tools/fork-hardening.bundle.00.02 \
  tools/fork-hardening.bundle.00.03 \
  tools/fork-hardening.bundle.00.04 \
  tools/fork-hardening.bundle.01 \
  tools/fork-hardening.bundle.02 \
  tools/fork-hardening.bundle.03 \
  tools/fork-hardening.bundle.04 \
  tools/fork-hardening.bundle.05 \
  | base64 --decode > "$archive"
tar -xzf "$archive"


git rm -f --ignore-unmatch -- \
  app/src/main/java/com/whisperonnx/asr/RecordBuffer.java \
  app/src/test/java/com/whisperonnx/asr/RecorderBluetoothTest.java \
  tools/fork-hardening.bundle.* \
  tools/apply-fork-hardening-bundle.sh

git add -- \
  app/build.gradle \
  app/src/main/AndroidManifest.xml \
  app/src/main/java/com/whisperonnx/MainActivity.java \
  app/src/main/java/com/whisperonnx/SettingsActivity.java \
  app/src/main/java/com/whisperonnx/WhisperInputMethodService.java \
  app/src/main/java/com/whisperonnx/WhisperRecognitionService.java \
  app/src/main/java/com/whisperonnx/WhisperRecognizeActivity.java \
  app/src/main/java/com/whisperonnx/WordReplacementActivity.java \
  app/src/main/java/com/whisperonnx/asr \
  app/src/main/java/com/whisperonnx/utils/RecordingProgressTimer.java \
  app/src/main/java/com/whisperonnx/utils/WordReplacementAdapter.java \
  app/src/main/res/layout/activity_settings.xml \
  app/src/main/res/layout/activity_word_replacements.xml \
  app/src/main/res/layout/item_word_replacement.xml \
  app/src/main/res/values/strings.xml \
  app/src/test/java/com/whisperonnx/asr \
  app/src/test/java/com/whisperonnx/WordReplacementActivityTest.java

git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"

git commit -m "refactor: harden recording and transcription pipeline" \
  -m "Implements issues #1-#8, #10, and #12 with request-owned audio, bounded jobs, lossless segmentation, shared recording state, deterministic replacements, editor UX, and regression tests."
git push origin HEAD:codex/fork-hardening
