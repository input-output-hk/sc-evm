#!/usr/bin/env bash

# Builds the image and tags it with latest

set -o errexit
set -o pipefail
set -o nounset

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"

cd "$SCRIPT_DIR/../../"

sbt 'set node/version := "latest"' node/Docker/publishLocal
