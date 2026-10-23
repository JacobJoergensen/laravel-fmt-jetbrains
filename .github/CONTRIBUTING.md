# Contributing

## Reporting bugs

Found a bug? [Open an issue](https://github.com/JacobJoergensen/laravel-fmt-jetbrains/issues/new/choose) and fill in the bug report.

A bug in the formatted output itself belongs in [laravel-fmt](https://github.com/JacobJoergensen/laravel-fmt/issues/new/choose).

## Setup

Fork the repo. You need JDK 21.

```sh
./gradlew runIde
```

## Pull requests

Before opening one:

1. [File an issue](https://github.com/JacobJoergensen/laravel-fmt-jetbrains/issues/new?template=feature.yml) first for anything beyond a small fix.
2. Branch from `main`.
3. Add a test. When fixing a bug, add a test that would have caught it.
4. Add a line under `## Unreleased` in `CHANGELOG.md` for anything users will notice.

Keep the diff focused. Don't bundle a fix with a refactor.

## Coding standards

The plugin must produce the same output as the laravel-fmt CLI. It never formats on its own, and it never changes
laravel-fmt's output.

Never block the EDT: file system lookups, process starts and downloads run on a pooled thread. Keep to the API of the
minimum PhpStorm version in `gradle.properties`, and keep user-facing strings in `LaravelFmtBundle.properties`.

Keep logic that doesn't need the IDE out of IDE classes, so it can be unit tested.

## Running tests

```sh
./gradlew test
./gradlew verifyPlugin
```

The LSP contract tests are skipped unless `LARAVEL_FMT_BINARY` points at a laravel-fmt binary, e.g. one built with
`go build -o /tmp/laravel-fmt .` in the laravel-fmt repo.
