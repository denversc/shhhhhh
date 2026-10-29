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
typeset -r bundle_id="prefix924.shhhhhh"

if ! xcrun simctl list devices "${device_name}" | grep -Fq "(Booted)"; then
  say_error "Simulator '${device_name}' is not booted. Run scripts/ios_simulator_launch.zsh first."
  exit 1
fi

typeset -r args=(
  xcrun simctl uninstall "${device_name}" "${bundle_id}" "$@"
)

say_args "${args[@]}"
exec "${args[@]}" # zshellcheck disable=ZC1909
