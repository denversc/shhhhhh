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
typeset -r tools_dir="${shhhhhh_root_dir}/tools/prettier"

if [[ ! -x "${tools_dir}/node_modules/.bin/prettier" ]]; then
  typeset -a npm_install_args=(
    "npm"
    "--prefix"
    "${tools_dir}"
    "ci"
    "--fund=false"
    "--audit=false"
  )
  say_args "${npm_install_args[@]}"
  "${npm_install_args[@]}"
fi

typeset has_mode=0
typeset has_targets=0

for arg in "$@"; do
  if [[ "${arg}" == -* ]]; then
    if [[ "${arg}" == ("--write"|"-w"|"--check"|"-c"|"--list-different"|"-l"|"--file-info") ]]; then
      has_mode=1
    elif [[ "${arg}" == ("--help"|"-h"|"--version"|"-v") ]]; then
      has_mode=1
      has_targets=1
    fi
  else
    has_targets=1
  fi
done

typeset -a args=(
  "${tools_dir}/node_modules/.bin/prettier"
  "--config"
  "${shhhhhh_root_dir}/.prettierrc.yaml"
  "--ignore-path"
  "${shhhhhh_root_dir}/.gitignore"
  "--ignore-path"
  "${shhhhhh_root_dir}/android/.gitignore"
  "--ignore-path"
  "${shhhhhh_root_dir}/android/app/.gitignore"
  "--ignore-path"
  "${shhhhhh_root_dir}/ios/.gitignore"
  "--ignore-path"
  "${shhhhhh_root_dir}/tools/prettier/.gitignore"
  "--ignore-path"
  "${shhhhhh_root_dir}/.prettierignore"
  "--ignore-path"
  "${shhhhhh_root_dir}/ios/shhhhhh/shhhhhh/Assets.xcassets/.prettierignore"
  "--ignore-path"
  "${shhhhhh_root_dir}/tools/prettier/.prettierignore"
)

if (( ! has_mode )); then
  args+=("--write")
fi

args+=("$@")

if (( ! has_targets )); then
  args+=("${shhhhhh_root_dir}")
fi

say_args "${args[@]}"
exec "${args[@]}" # zshellcheck disable=ZC1909
