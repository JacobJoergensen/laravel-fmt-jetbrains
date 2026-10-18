# laravel-fmt for JetBrains

<!-- Plugin description -->
Formats PHP and Blade files with [laravel-fmt](https://github.com/jacobjoergensen/laravel-fmt) when
you run Reformat Code or save. It uses your project's laravel-fmt config, so you get the same output
as the CLI.

You'll need the binary: `composer require --dev jacobjoergensen/laravel-fmt`
<!-- Plugin description end -->

## Requirements

PhpStorm 2025.3 or later, or IntelliJ IDEA Ultimate with the PHP plugin.

## Usage

It turns on by itself in projects that use laravel-fmt: `composer.json` requires it, or there's a
`laravel-fmt.json` or `vendor/bin/laravel-fmt`. Other PHP projects keep PhpStorm's formatter.

Press Reformat Code (Ctrl+Alt+L, or ⌥⌘L on macOS) in a file, or on a folder in the Project view. From
PhpStorm 2026.1 it also formats just a selection.

To format on save, turn on Run on save in the settings below.

## How it finds laravel-fmt

It checks, in order:

1. the path in Settings | Tools | laravel-fmt
2. `vendor/bin/laravel-fmt`, including subfolders in a monorepo (trusted projects only)
3. your `PATH`

If Composer ran in Sail or WSL, it downloads the same version for your system once. No PHP needed.

## Settings

Settings | Tools | laravel-fmt lets you:

- choose when it runs: Automatic (only in projects that use laravel-fmt), Always, or Off
- format only PHP or only Blade, and leave the other to PhpStorm or a tool like Pint
- run it on save
- point it at a specific binary

## Development

```bash
./gradlew test
./gradlew runIde
./gradlew verifyPlugin
```

Set `LARAVEL_FMT_BINARY` to a laravel-fmt binary if you want the LSP contract tests to run too.

To release, bump `pluginVersion` in `gradle.properties`, move the Unreleased notes in `CHANGELOG.md`
under the new version, and publish a GitHub release tagged with the version, e.g. `1.0.0`. Pre-releases go to the EAP
channel.

## License

MIT. See [LICENSE](LICENSE.md).
