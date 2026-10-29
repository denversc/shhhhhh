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

typeset -r args=(
  "xcodebuild"
  "-project"
  "${shhhhhh_root_dir}/ios/shhhhhh/shhhhhh.xcodeproj"
  "-scheme"
  "shhhhhh"
  "-destination"
  "platform=iOS Simulator,name=iPhone 17e"
  "-only-testing:shhhhhhTests"
  "test"
)

say_args "${args[@]}"
exec "${args[@]}" # zshellcheck disable=ZC1909
