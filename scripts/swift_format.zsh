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

typeset subcommand="format"
typeset -a mode_flags=("--in-place")
typeset -a custom_args=()
typeset -a targets=()

for arg in "$@"; do
  if [[ "${arg}" == ("--check"|"-c") ]]; then
    subcommand="lint"
    mode_flags=("--strict")
  elif [[ "${arg}" == ("--apply"|"-w"|"--write") ]]; then
    subcommand="format"
    mode_flags=("--in-place")
  elif [[ "${arg}" == ("--help"|"-h") ]]; then
    say "Usage: ${0:t} [--check|-c] [--apply|-w|--write] [paths...]"
    exit 0
  elif [[ "${arg}" == -* ]]; then
    custom_args+=("${arg}")
  else
    targets+=("${arg}")
  fi
done

if (( ${#targets} == 0 )); then
  targets+=("${shhhhhh_root_dir}/ios")
fi

typeset -a swift_format_cmd=()
if command -v swift-format >/dev/null 2>&1; then
  swift_format_cmd=("swift-format")
elif command -v xcrun >/dev/null 2>&1; then
  swift_format_cmd=("xcrun" "swift-format")
else
  say_error "swift-format not found. Please install swift-format or Xcode."
  exit 1
fi

typeset -r args=(
  "${swift_format_cmd[@]}"
  "${subcommand}"
  "--configuration"
  "${shhhhhh_root_dir}/.swift-format"
  "--parallel"
  "--recursive"
  "${mode_flags[@]}"
  "${custom_args[@]}"
  "${targets[@]}"
)

say_args "${args[@]}"
exec "${args[@]}" # zshellcheck disable=ZC1909
