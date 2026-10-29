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

import signale, { type SignaleConfig } from "signale";

export interface Logger {
  info(...arguments_: unknown[]): unknown;
  warn(...arguments_: unknown[]): unknown;
  error(...arguments_: unknown[]): unknown;
  success?(...arguments_: unknown[]): unknown;
}

function createSignaleConfig(): SignaleConfig {
  return {
    displayScope: false,
    displayBadge: true,
    displayDate: false,
    displayTimestamp: false,
    displayFilename: false,
    displayLabel: false,
  };
}

export function createLogger(): Logger {
  const config = createSignaleConfig();
  return new signale.Signale({ config });
}
