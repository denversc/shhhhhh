#!/usr/bin/env node

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

import { Command } from "commander";
import path from "node:path";
import { generateIcon } from "./generator.ts";
import { createLogger } from "./logging.ts";

const logger = createLogger();
const program = new Command();

program
  .name("shhhhhh-icon")
  .description("Generate application icon assets for iOS and Android from SVG")
  .version("1.0.0")
  .requiredOption("-i, --input <path>", "Path to input SVG file")
  .requiredOption("-o, --output <path>", "Path to output file")
  .requiredOption(
    "-t, --type <type>",
    `Output file type identifier (e.g. "AppIcon.png", "ios/AppIcon-Dark.png", "android/drawable/ic_launcher_background.xml", "android/mipmap-hdpi/ic_launcher_round.webp")`,
  )
  .action(async (options) => {
    try {
      const inputPath = path.resolve(options.input);
      const outputPath = path.resolve(options.output);
      const outputType = options.type;

      await generateIcon(inputPath, outputPath, outputType);
      logger.info(`Generated [${outputType}] -> ${path.relative(process.cwd(), outputPath)}`);
    } catch (error) {
      logger.error(
        `Failed to generate icon: ${error instanceof Error ? error.message : String(error)}`,
      );
      process.exit(1);
    }
  });

await program.parseAsync(process.argv);
