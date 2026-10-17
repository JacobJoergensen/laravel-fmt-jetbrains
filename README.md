# laravel-fmt for JetBrains

<!-- Plugin description -->
Formats PHP and Blade files with [laravel-fmt](https://github.com/jacobjoergensen/laravel-fmt) when
you run Reformat Code or save. It uses your project's laravel-fmt config, so you get the same output
as the CLI.

You'll need the binary: `composer require --dev jacobjoergensen/laravel-fmt`
<!-- Plugin description end -->

## Requirements

PhpStorm 2025.3 or later, or IntelliJ IDEA Ultimate with the PHP plugin.

## How it finds laravel-fmt

It checks, in order:

1. the path in Settings | Tools | laravel-fmt
2. `vendor/bin/laravel-fmt`, including subfolders if you're in a monorepo
3. your `PATH`

Untrusted projects can't run their own `vendor/bin` binary.

## Settings

Settings | Tools | laravel-fmt lets you turn it off for a project or point it at a specific binary.

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
