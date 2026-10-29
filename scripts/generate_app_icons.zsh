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
typeset -r master_icon="${shhhhhh_root_dir}/assets/icon/app_icon.png"
typeset -r ios_appicon_dir="${shhhhhh_root_dir}/ios/shhhhhh/shhhhhh/Assets.xcassets/AppIcon.appiconset"
typeset -r android_res_dir="${shhhhhh_root_dir}/android/app/src/main/res"

if ! command -v magick >/dev/null 2>&1; then
  echo "Error: ImageMagick (magick) is required to generate app icon assets." >&2
  echo "Please install ImageMagick: brew install imagemagick" >&2
  exit 1
fi

if [[ ! -f "$master_icon" ]]; then
  echo "Error: Master icon not found at $master_icon" >&2
  exit 1
fi

echo "==> Generating iOS AppIcon assets..."
mkdir -p "$ios_appicon_dir"

# Standard universal 1024x1024 icon
magick "$master_icon" -resize 1024x1024 "${ios_appicon_dir}/AppIcon.png"

# iOS 18 Dark appearance icon (deepened darks, preserving vibrant neon accents)
magick "$master_icon" \
  -evaluate pow 1.3 \
  -modulate 90,110,100 \
  -resize 1024x1024 \
  "${ios_appicon_dir}/AppIcon-Dark.png"

# iOS 18 Tinted appearance icon (grayscale silhouette with alpha transparency)
magick "$master_icon" \
  -colorspace gray \
  -level 25%,85% \
  -background none \
  -alpha shape \
  -resize 1024x1024 \
  "${ios_appicon_dir}/AppIcon-Tinted.png"

# Update Contents.json with all three iOS appearances
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

echo "==> Generating Android Adaptive Icon XML drawables..."
mkdir -p "${android_res_dir}/drawable"

# Background layer: rich linear gradient vector
cat << 'EOF' > "${android_res_dir}/drawable/ic_launcher_background.xml"
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:angle="315"
                android:endColor="#051126"
                android:centerColor="#081D40"
                android:startColor="#0A2550"
                android:type="linear" />
        </aapt:attr>
    </path>
</vector>
EOF

# Foreground layer: MessageLock vector with vibrant shackle and bubble gradients
cat << 'EOF' > "${android_res_dir}/drawable/ic_launcher_foreground.xml"
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <!-- Padlock Shackle -->
    <path android:pathData="M40,46 L40,36 C40,28.27 46.27,22 54,22 C61.73,22 68,28.27 68,36 L68,46 L61,46 L61,36 C61,32.13 57.87,29 54,29 C50.13,29 47,32.13 47,36 L47,46 Z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:angle="315"
                android:startColor="#38BDF8"
                android:centerColor="#60A5FA"
                android:endColor="#2563EB"
                android:type="linear" />
        </aapt:attr>
    </path>

    <!-- Speech Bubble Body -->
    <path android:pathData="M54,36 C67.25,36 78,46.75 78,60 C78,65.8 75.9,71.2 72.5,75.3 L78.5,86.5 C78.8,87.1 78.2,87.8 77.5,87.5 L66.8,82.9 C62.9,84.9 58.6,86 54,86 C40.75,86 30,75.25 30,60 C30,46.75 40.75,36 54,36 Z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:angle="270"
                android:startColor="#3B82F6"
                android:centerColor="#1D4ED8"
                android:endColor="#1E3A8A"
                android:type="linear" />
        </aapt:attr>
    </path>

    <!-- Glassmorphic Rim Light Highlight -->
    <path
        android:pathData="M54,37.5 C66.4,37.5 76.5,47.6 76.5,60 C76.5,64.5 75,69 72.3,72.7"
        android:strokeWidth="1.5"
        android:strokeColor="#80FFFFFF"
        android:strokeLineCap="round" />

    <!-- Centered Keyhole -->
    <path
        android:fillColor="#060F22"
        android:pathData="M51.5,58.5 L50.2,68 C50.1,68.8 50.7,69.5 51.5,69.5 L56.5,69.5 C57.3,69.5 57.9,68.8 57.8,68 L56.5,58.5 C57.7,57.5 58.5,56 58.5,54.2 C58.5,51.7 56.5,49.7 54,49.7 C51.5,49.7 49.5,51.7 49.5,54.2 C49.5,56 50.3,57.5 51.5,58.5 Z" />
</vector>
EOF

# Monochrome icon layer for Android 13+ Material You Themed Icons
cat << 'EOF' > "${android_res_dir}/drawable/ic_launcher_monochrome.xml"
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <!-- Monochrome Shackle -->
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M40,46 L40,36 C40,28.27 46.27,22 54,22 C61.73,22 68,28.27 68,36 L68,46 L61,46 L61,36 C61,32.13 57.87,29 54,29 C50.13,29 47,32.13 47,36 L47,46 Z" />
    <!-- Monochrome Bubble with EvenOdd Keyhole Cutout -->
    <path
        android:fillColor="#FFFFFF"
        android:fillType="evenOdd"
        android:pathData="M54,36 C67.25,36 78,46.75 78,60 C78,65.8 75.9,71.2 72.5,75.3 L78.5,86.5 C78.8,87.1 78.2,87.8 77.5,87.5 L66.8,82.9 C62.9,84.9 58.6,86 54,86 C40.75,86 30,75.25 30,60 C30,46.75 40.75,36 54,36 Z M51.5,58.5 L50.2,68 C50.1,68.8 50.7,69.5 51.5,69.5 L56.5,69.5 C57.3,69.5 57.9,68.8 57.8,68 L56.5,58.5 C57.7,57.5 58.5,56 58.5,54.2 C58.5,51.7 56.5,49.7 54,49.7 C51.5,49.7 49.5,51.7 49.5,54.2 C49.5,56 50.3,57.5 51.5,58.5 Z" />
</vector>
EOF

# Update Android adaptive icon configurations
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

echo "==> Generating Android raster mipmaps (WebP)..."
typeset -A mipmap_sizes=(
  mdpi 48
  hdpi 72
  xhdpi 96
  xxhdpi 144
  xxxhdpi 192
)

for density in ${(k)mipmap_sizes}; do
  typeset size="${mipmap_sizes[$density]}"
  typeset target_dir="${android_res_dir}/mipmap-${density}"
  mkdir -p "$target_dir"

  # Square / standard launcher WebP
  magick "$master_icon" \
    -resize "${size}x${size}" \
    -quality 95 \
    "${target_dir}/ic_launcher.webp"

  # Round launcher WebP with circular alpha mask
  magick "$master_icon" \
    \( +clone -threshold -1 -negate -fill white -draw "circle 512,512 512,0" \) \
    -alpha off -compose CopyOpacity -composite \
    -resize "${size}x${size}" \
    -quality 95 \
    "${target_dir}/ic_launcher_round.webp"
done

echo "==> Application icon assets successfully generated for iOS and Android!"
