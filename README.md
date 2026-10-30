# laravel-fmt for JetBrains

<!-- Plugin description -->
Formats PHP and Blade files with [laravel-fmt](https://github.com/jacobjoergensen/laravel-fmt) when
you run Reformat Code or save. It uses your project's laravel-fmt config, so you get the same output
as the CLI.

You'll need the binary: `composer require --dev jacobjoergensen/laravel-fmt`
<!-- Plugin description end -->

## Requirements

PhpStorm 2026.1.4 or later, or IntelliJ IDEA Ultimate with the PHP plugin.

## Usage

It turns on by itself in projects that use laravel-fmt: `composer.json` requires it, or there's a
`laravel-fmt.json` or `vendor/bin/laravel-fmt`. Other PHP projects keep PhpStorm's formatter.

In a monorepo, each app that uses laravel-fmt runs its own installed version, so you get the same
output as running its CLI. Apps that don't use it keep PhpStorm's formatter.

Press Reformat Code (Ctrl+Alt+L, or ⌥⌘L on macOS) in a file, on a selection, or on a folder in the
Project view.

To format on save, turn on Run on save in the settings below.

## How it finds laravel-fmt

It checks, in order:

1. the path in Settings | Tools | laravel-fmt
2. `vendor/bin/laravel-fmt` in the file's app, or in the surrounding folder, like a monorepo's root
   (trusted projects only)
3. your `PATH`

If Composer ran in Sail or WSL, it downloads the same version for your system once. No PHP needed.

## Settings

Settings | Tools | laravel-fmt lets you:

- choose when it runs: Automatic (only in projects that use laravel-fmt), Always, or Off
- format only PHP or only Blade, and leave the other to PhpStorm or a tool like Pint
- run it on save
- point it at a specific binary

## License

MIT. See [LICENSE](LICENSE.md).
