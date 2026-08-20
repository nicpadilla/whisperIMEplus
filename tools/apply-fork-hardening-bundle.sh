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

cat > .github/workflows/build.yml <<'CLEAN_WORKFLOW'
name: Build and test

on:
  push:
    branches: [ master ]
  pull_request:
    branches: [ master ]

concurrency:
  group: ${{ github.workflow }}-${{ github.event.pull_request.number || github.ref }}
  cancel-in-progress: true

permissions:
  contents: read

jobs:
  verify:
    runs-on: ubuntu-latest
    timeout-minutes: 45

    steps:
      - name: Check out source
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Build debug APK
        run: ./gradlew --stacktrace assembleDebug

      - name: Run unit tests
        run: ./gradlew --stacktrace testDebugUnitTest

      - name: Run Android lint
        run: ./gradlew --stacktrace lintDebug

      - name: Upload test and lint reports
        uses: actions/upload-artifact@v4
        if: always()
        with:
          name: verification-reports-${{ github.sha }}
          path: |
            app/build/reports/tests/**
            app/build/test-results/**
            app/build/reports/lint-results-debug.*
          if-no-files-found: ignore
          retention-days: 14

      - name: Upload debug APK
        uses: actions/upload-artifact@v4
        if: success()
        with:
          name: debug-apk-${{ github.sha }}
          path: app/build/outputs/apk/debug/app-debug.apk
          if-no-files-found: error
          retention-days: 14
CLEAN_WORKFLOW

git rm -f --ignore-unmatch -- \
  app/src/main/java/com/whisperonnx/asr/RecordBuffer.java \
  app/src/test/java/com/whisperonnx/asr/RecorderBluetoothTest.java \
  tools/fork-hardening.bundle.* \
  tools/apply-fork-hardening-bundle.sh \
  .github/workflows/apply-fork-hardening-bundle.yml

git add -- \
  .github/workflows/build.yml \
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
