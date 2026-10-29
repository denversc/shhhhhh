#!/usr/bin/env zsh

# Copyright 2026 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

emulate -L zsh
setopt errexit nounset pipefail extendedglob

source "${0:A:h}/util/say.zsh"

typeset -r shhhhhh_root_dir="${0:A:h:h}"
typeset -r device_name="iPhone 17e"

if ! xcrun simctl list devices "${device_name}" | grep -Fq "(Booted)"; then
  say_error "Simulator '${device_name}' is not booted. Run scripts/ios_simulator_launch.zsh first."
  exit 1
fi

typeset -r build_args=(
  "xcodebuild"
  "-project"
  "${shhhhhh_root_dir}/ios/shhhhhh/shhhhhh.xcodeproj"
  "-scheme"
  "shhhhhh"
  "-destination"
  "platform=iOS Simulator,name=${device_name}"
  "$@"
  "build"
)

say_args "${build_args[@]}"
"${build_args[@]}"

typeset app_path
app_path="$(xcodebuild -project "${shhhhhh_root_dir}/ios/shhhhhh/shhhhhh.xcodeproj" -scheme "shhhhhh" -destination "platform=iOS Simulator,name=${device_name}" "$@" -showBuildSettings | awk -F ' = ' '$1 ~ /^[[:space:]]*TARGET_BUILD_DIR$/ { dir = $2 } $1 ~ /^[[:space:]]*FULL_PRODUCT_NAME$/ { app = $2 } END { print dir "/" app }')"

if [[ ! -d "${app_path}" ]]; then
  say_error "Built application bundle not found at: ${app_path}"
  exit 1
fi

typeset -r install_args=(
  xcrun
  simctl
  install
  "${device_name}"
  "${app_path}"
)

say_args "${install_args[@]}"
exec "${install_args[@]}" # zshellcheck disable=ZC1909
