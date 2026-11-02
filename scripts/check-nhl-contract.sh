#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
scratch=$(mktemp -d "${TMPDIR:-/tmp}/banner-contract.XXXXXX")
trap 'rm -rf "$scratch"' EXIT
swiftc -parse-as-library "$root/apple/MyApp/Foundation/BannerNHLFeed.swift" \
    "$root/scripts/contracts/NHLContractCheck.swift" -o "$scratch/check"
"$scratch/check" "$root/contracts/nhl/score-v1.json" 2023020204
if [ "$#" -eq 1 ]; then "$scratch/check" "$1" 2023030417; fi
