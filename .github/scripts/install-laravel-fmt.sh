#!/usr/bin/env bash
set -euo pipefail

latest="$(curl -fsSLI --retry 3 -o /dev/null -w '%{url_effective}' https://github.com/JacobJoergensen/laravel-fmt/releases/latest)"
version="${latest##*/}"

if [ -z "$version" ] || [ "$version" = "latest" ]; then
  echo "::error::Could not resolve the latest laravel-fmt release"
  exit 1
fi

archive="laravel-fmt_${version#v}_Linux_x86_64.tar.gz"
checksums="laravel-fmt_${version#v}_checksums.txt"
base="https://github.com/JacobJoergensen/laravel-fmt/releases/download/$version"
dir="$RUNNER_TEMP/laravel-fmt"

mkdir -p "$dir"
cd "$dir"

curl -fsSL --retry 3 -o "$archive" "$base/$archive"
curl -fsSL --retry 3 -o "$checksums" "$base/$checksums"

grep -E "[[:space:]]$archive\$" "$checksums" | sha256sum --check --strict -
tar -xzf "$archive" laravel-fmt

echo "LARAVEL_FMT_BINARY=$dir/laravel-fmt" >> "$GITHUB_ENV"
echo "LARAVEL_FMT_VERSION=$version" >> "$GITHUB_ENV"
"$dir/laravel-fmt" --version
