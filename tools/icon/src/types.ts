// Copyright 2026 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

export const DENSITY_SIZES: Record<string, number> = {
  mdpi: 48,
  hdpi: 72,
  xhdpi: 96,
  xxhdpi: 144,
  xxxhdpi: 192,
};

export const SUPPORTED_TYPES = [
  "AppIcon.png",
  "ios/AppIcon.png",
  "ios/AppIcon-Dark.png",
  "ios/AppIcon-Tinted.png",
  "android/drawable/ic_launcher_background.xml",
  "android/drawable/ic_launcher_foreground.xml",
  "android/drawable/ic_launcher_monochrome.xml",
  "android/mipmap-mdpi/ic_launcher.webp",
  "android/mipmap-mdpi/ic_launcher_round.webp",
  "android/mipmap-hdpi/ic_launcher.webp",
  "android/mipmap-hdpi/ic_launcher_round.webp",
  "android/mipmap-xhdpi/ic_launcher.webp",
  "android/mipmap-xhdpi/ic_launcher_round.webp",
  "android/mipmap-xxhdpi/ic_launcher.webp",
  "android/mipmap-xxhdpi/ic_launcher_round.webp",
  "android/mipmap-xxxhdpi/ic_launcher.webp",
  "android/mipmap-xxxhdpi/ic_launcher_round.webp",
] as const;

export type SupportedType = (typeof SUPPORTED_TYPES)[number] | (string & {});
