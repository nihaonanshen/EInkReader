#!/bin/bash
set -e

PROJECT_DIR="/d/EInkReader"
BACKUP_DIR="${PROJECT_DIR}_backup_$(date +%Y%m%d_%H%M%S)"
echo "Creating backup at ${BACKUP_DIR}"
cp -r "${PROJECT_DIR}" "${BACKUP_DIR}"

echo "=== Adding Kotlin plugin & stdlib ==="
# 1. Ensure top-level build.gradle has kotlin plugin & stdlib
TOP_GRADLE="${PROJECT_DIR}/build.gradle"
if ! grep -q "org.jetbrains.kotlin.android" "${TOP_GRADLE}"; then
  echo " Applying Kotlin plugin..."
  # Insert plugin line after 'plugins {'
  sed -i '/^plugins {/a\    id \"org.jetbrains.kotlin.android\" version \"1.9.20\"' "${TOP_GRADLE}"
  # Add stdlib dependency at the end of dependencies block
  sed -i '/^dependencies {/a\    implementation \"org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.20\"' "${TOP_GRADLE}"
fi

echo "=== Enabling ViewBinding & Hilt in app module ==="
APP_GRADLE="${PROJECT_DIR}/app/build.gradle"
# Enable ViewBinding
if ! grep -q "viewBinding" "${APP_GRADLE}"; then
  echo " Enabling ViewBinding..."
  # Add viewBinding flag inside android {}
  sed -i '/^android {/a\    viewBinding true' "${APP_GRADLE}"
fi
# Add Hilt dependencies
if ! grep -q "hilt-android" "${APP_GRADLE}"; then
  echo " Adding Hilt dependencies..."
  # Add Hilt plugin line after plugins {}
  if grep -q "^plugins {" "${APP_GRADLE}"; then
    sed -i '/^plugins {/a\    id \"dagger.hilt.android.plugin\" version \"2.48\"\n    kotlin("kapt") version \"1.9.20\"" "${APP_GRADLE}"
  else
    echo "    id \"dagger.hilt.android.plugin\" version \"2.48\"" >> "${APP_GRADLE}"
    echo "    kotlin(\"kapt\") version \"1.9.20\"" >> "${APP_GRADLE}"
  fi
  # Add implementation & kapt dependencies
  sed -i '/^dependencies {/a\    implementation \"com.google.di:hilt-android:2.48\"\n    kapt \"com.google.di:hilt-compiler:2.48\"' "${APP_GRADLE}"
fi

echo "=== Syncing Gradle & running clean check ==="
cd "${PROJECT_DIR}"
./gradlew clean check --no-daemon
echo "Migration setup completed successfully."
echo "Next step: Open each .java file in Android Studio and choose ‘Refactor → Convert Java File to Kotlin File’."