#!/usr/bin/env zsh

# Launches the app on the one and only physical iOS device attached via USB.
#
# Usage: ios_device_launch.zsh [-r]
#
#   -r  Reset the app's storage (Keychain and UserDefaults) at launch by passing
#       the "-reset-storage" argument to the app, so that it starts as if freshly
#       installed (i.e. at the onboarding / entropy collection screen).

emulate -L zsh
setopt errexit nounset pipefail extendedglob

source "${0:A:h}/util/say.zsh"

typeset -r bundle_id="prefix924.shhhhhh"

typeset reset_storage=0
for arg in "$@"; do
  case "${arg}" in
    -r)
      reset_storage=1
      ;;
    *)
      say_error "unrecognized argument: ${arg}"
      say "Usage: ${0:t} [-r]" >&2
      exit 2
      ;;
  esac
done

# Find the physical iOS devices attached via USB ("wired"), as "<udid> <name>" lines.
typeset -r devices_json_file="$(mktemp -t ios_device_launch)"
trap 'rm -f -- "${devices_json_file}"' EXIT

xcrun devicectl list devices --quiet --json-output "${devices_json_file}" >/dev/null

typeset -a devices
devices=("${(@f)$(python3 - "${devices_json_file}" <<'EOF'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as f:
  data = json.load(f)

for device in data["result"]["devices"]:
  hardware = device.get("hardwareProperties", {})
  connection = device.get("connectionProperties", {})
  if (
    hardware.get("reality") == "physical"
    and hardware.get("platform") == "iOS"
    and connection.get("transportType") == "wired"
  ):
    name = device.get("deviceProperties", {}).get("name", "")
    print(hardware["udid"], name)
EOF
)}")
# Drop the single empty element produced when no devices were found.
devices=("${(@)devices:#}")

if (( ${#devices} == 0 )); then
  say_error "no physical iOS device is attached via USB"
  exit 1
elif (( ${#devices} > 1 )); then
  say_error "${#devices} physical iOS devices are attached via USB, but exactly 1 is required:"
  for device in "${devices[@]}"; do
    say "  ${device}" >&2
  done
  exit 1
fi

typeset -r device_udid="${devices[1]%% *}"

typeset -a args=(
  xcrun
  devicectl
  device
  process
  launch
  --device
  "${device_udid}"
  --terminate-existing
  "${bundle_id}"
)

if (( reset_storage )); then
  args+=(-- -reset-storage)
fi

say_args "${args[@]}"
"${args[@]}"
