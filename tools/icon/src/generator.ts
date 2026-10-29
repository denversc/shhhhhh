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

import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import nunjucks from "nunjucks";
import sharp from "sharp";
import { DENSITY_SIZES, type SupportedType } from "./types.ts";

const currentDir = path.dirname(fileURLToPath(import.meta.url));
const templatesDir = path.resolve(currentDir, "../templates");

nunjucks.configure(templatesDir, { autoescape: false });

export async function generateIcon(
  inputSvgPath: string,
  outputPath: string,
  outputType: SupportedType,
): Promise<void> {
  await fs.mkdir(path.dirname(outputPath), { recursive: true });

  const svgContent = await fs.readFile(inputSvgPath, "utf-8");
  const svgBuffer = Buffer.from(svgContent);

  switch (outputType) {
    case "AppIcon.png":
    case "ios/AppIcon.png": {
      // 1024x1024 universal icon for iOS
      await sharp(svgBuffer, { density: (1024 * 72) / 108 })
        .resize(1024, 1024)
        .png()
        .toFile(outputPath);
      break;
    }

    case "ios/AppIcon-Dark.png": {
      // iOS 18 Dark appearance: deep obsidian background with vibrant glow
      const darkSvg = svgContent.replace(
        /<linearGradient id="bg"[\s\S]*?<\/linearGradient>/,
        `<linearGradient id="bg" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#020817" />
      <stop offset="50%" stop-color="#01040f" />
      <stop offset="100%" stop-color="#000208" />
    </linearGradient>`,
      );
      await sharp(Buffer.from(darkSvg), { density: (1024 * 72) / 108 })
        .resize(1024, 1024)
        .png()
        .toFile(outputPath);
      break;
    }

    case "ios/AppIcon-Tinted.png": {
      // iOS 18 Tinted appearance: pure transparent background with solid white silhouette
      const tintedSvg = svgContent
        .replace(/<rect\s+width="108"\s+height="108"\s+fill="url\(#bg\)"\s*\/>/, "")
        .replace(/fill="url\(#shackleGrad\)"/g, 'fill="#FFFFFF"')
        .replace(/fill="url\(#bubbleGrad\)"/g, 'fill="#FFFFFF"')
        .replace(/fill="#060f22"/g, 'fill="#000000" fill-opacity="0"');

      await sharp(Buffer.from(tintedSvg), { density: (1024 * 72) / 108 })
        .resize(1024, 1024)
        .png()
        .toFile(outputPath);
      break;
    }

    case "android/drawable/ic_launcher_background.xml": {
      const backgroundXml = nunjucks.render("ic_launcher_background.xml.njk", {});
      await fs.writeFile(outputPath, backgroundXml, "utf-8");
      break;
    }

    case "android/drawable/ic_launcher_foreground.xml": {
      const foregroundXml = nunjucks.render("ic_launcher_foreground.xml.njk", {});
      await fs.writeFile(outputPath, foregroundXml, "utf-8");
      break;
    }

    case "android/drawable/ic_launcher_monochrome.xml": {
      const monochromeXml = nunjucks.render("ic_launcher_monochrome.xml.njk", {});
      await fs.writeFile(outputPath, monochromeXml, "utf-8");
      break;
    }

    default: {
      const mipmapMatch = outputType.match(
        /^android\/mipmap-([a-z]+)\/ic_launcher(_round)?\.webp$/,
      );

      if (mipmapMatch) {
        const density = mipmapMatch[1];
        const isRound = Boolean(mipmapMatch[2]);
        const size = DENSITY_SIZES[density];

        if (!size) {
          throw new Error(
            `Unsupported density "${density}". Supported densities: ${Object.keys(
              DENSITY_SIZES,
            ).join(", ")}`,
          );
        }

        if (isRound) {
          const radius = size / 2;
          const circleMask = Buffer.from(
            `<svg viewBox="0 0 ${size} ${size}" width="${size}" height="${size}"><circle cx="${radius}" cy="${radius}" r="${radius}" fill="#FFFFFF" /></svg>`,
          );
          await sharp(svgBuffer, { density: (size * 72) / 108 })
            .resize(size, size)
            .composite([{ input: circleMask, blend: "dest-in" }])
            .webp({ quality: 95 })
            .toFile(outputPath);
        } else {
          await sharp(svgBuffer, { density: (size * 72) / 108 })
            .resize(size, size)
            .webp({ quality: 95 })
            .toFile(outputPath);
        }
        break;
      }

      throw new Error(`Unknown output file type: "${outputType}"`);
    }
  }
}
