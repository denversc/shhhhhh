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

typeset ignore_x86_cpu_requirement=0

for arg in "$@"; do
  if [[ "$arg" == "--ignore-x86-cpu-requirement" ]]; then
    ignore_x86_cpu_requirement=1
  elif [[ "$arg" == ("--help"|"-h") ]]; then
    say "Usage: ${0:t} [--ignore-x86-cpu-requirement]"
    exit 0
  else
    say_error "Unknown argument: $arg" >&2
    say "Run with --help for help" >&2
    exit 1
  fi
done

typeset cpu_arch
cpu_arch="$(uname -m)"
if (( ! ignore_x86_cpu_requirement )) && [[ "$cpu_arch" != ("x86_64"|"amd64") ]]; then
  say_error "x86_64 CPU is required, but detected ${cpu_arch}" >&2
  say "This requirement is to avoid a cross-architecture floating-point/SIMD rounding" >&2
  say "divergence (ARM NEON vs x86 SSE) when rasterizing SVGs." >&2
  say "Specify --ignore-x86-cpu-requirement to bypass this check." >&2
  exit 1
fi

typeset -r shhhhhh_root_dir="${0:A:h:h}"
typeset -r master_svg="${shhhhhh_root_dir}/assets/app_icon.svg"
typeset -r tools_dir="${shhhhhh_root_dir}/tools/icon"
typeset -r ios_appicon_dir="${shhhhhh_root_dir}/ios/shhhhhh/shhhhhh/Assets.xcassets/AppIcon.appiconset"
typeset -r android_res_dir="${shhhhhh_root_dir}/android/app/src/main/res"
typeset -r required_node_version="24.12.0"

if ! command -v node >/dev/null 2>&1; then
  say_error "node is required to run the icon generator." >&2
  exit 1
fi

typeset current_node_version
current_node_version="$(node --version | sed 's/^v//')"

autoload -Uz is-at-least
if ! is-at-least "${required_node_version}" "${current_node_version}"; then
  say_error "Node.js version ${current_node_version} is less than required ${required_node_version}." >&2
  exit 1
fi

if [[ ! -f "$master_svg" ]]; then
  say_error "Master SVG icon not found at $master_svg" >&2
  exit 1
fi

if [[ ! -d "${tools_dir}/node_modules/sharp" ]]; then
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

run_generator() {
  typeset output_file="$1"
  typeset output_type="$2"
  typeset -a args=(
    "node"
    "${tools_dir}/src/index.ts"
    "--input"
    "${master_svg}"
    "--output"
    "${output_file}"
    "--type"
    "${output_type}"
  )
  say_args "${args[@]}"
  "${args[@]}"
}

say "==> Generating iOS AppIcon assets..."
mkdir -p "$ios_appicon_dir"

run_generator "${ios_appicon_dir}/AppIcon.png" "AppIcon.png"
run_generator "${ios_appicon_dir}/AppIcon-Dark.png" "ios/AppIcon-Dark.png"
run_generator "${ios_appicon_dir}/AppIcon-Tinted.png" "ios/AppIcon-Tinted.png"

cat << 'EOF' > "${ios_appicon_dir}/Contents.json"
{
  "images" : [
    {
      "filename" : "AppIcon.png",
      "idiom" : "universal",
      "platform" : "ios",
      "size" : "1024x1024"
    },
    {
      "appearances" : [
        {
          "appearance" : "luminosity",
          "value" : "dark"
        }
      ],
      "filename" : "AppIcon-Dark.png",
      "idiom" : "universal",
      "platform" : "ios",
      "size" : "1024x1024"
    },
    {
      "appearances" : [
        {
          "appearance" : "luminosity",
          "value" : "tinted"
        }
      ],
      "filename" : "AppIcon-Tinted.png",
      "idiom" : "universal",
      "platform" : "ios",
      "size" : "1024x1024"
    }
  ],
  "info" : {
    "author" : "xcode",
    "version" : 1
  }
}
EOF

say "==> Generating Android Adaptive Icon XML drawables..."
mkdir -p "${android_res_dir}/drawable"

run_generator "${android_res_dir}/drawable/ic_launcher_background.xml" "android/drawable/ic_launcher_background.xml"
run_generator "${android_res_dir}/drawable/ic_launcher_foreground.xml" "android/drawable/ic_launcher_foreground.xml"
run_generator "${android_res_dir}/drawable/ic_launcher_monochrome.xml" "android/drawable/ic_launcher_monochrome.xml"

mkdir -p "${android_res_dir}/mipmap-anydpi"
cat << 'EOF' > "${android_res_dir}/mipmap-anydpi/ic_launcher.xml"
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
EOF

cat << 'EOF' > "${android_res_dir}/mipmap-anydpi/ic_launcher_round.xml"
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
EOF

say "==> Generating Android raster mipmaps (WebP)..."
typeset -a densities=("mdpi" "hdpi" "xhdpi" "xxhdpi" "xxxhdpi")

for density in "${densities[@]}"; do
  typeset target_dir="${android_res_dir}/mipmap-${density}"
  mkdir -p "$target_dir"

  run_generator "${target_dir}/ic_launcher.webp" "android/mipmap-${density}/ic_launcher.webp"
  run_generator "${target_dir}/ic_launcher_round.webp" "android/mipmap-${density}/ic_launcher_round.webp"
done

say "==> Application icon assets successfully generated for iOS and Android!"
