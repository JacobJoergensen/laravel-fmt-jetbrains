# Changelog

## Unreleased

## Version 0.2.0 (2026-10-23)
* Added an Automatic mode, now the default, that only runs laravel-fmt in projects that use it
* Added a Run laravel-fmt save action that formats only PHP and Blade files
* Added PHP and Blade checkboxes, so laravel-fmt can format just one of them
* Changed the minimum PhpStorm version to 2025.3
* Changed the plugin icon to laravel-fmt's own logo
* Fixed laravel-fmt not starting in Sail or WSL projects, or without PHP on your machine
* Fixed Reformat Code and format on save ignoring laravel-fmt's `endOfLine` setting
* Fixed Reformat Code on a selection formatting the whole file in PhpStorm 2025.3
* Fixed Reformat Code and format on save unfolding code that laravel-fmt didn't change
* Fixed Reformat Code on a folder and before commit skipping files that aren't open

## Version 0.1.0 (2026-10-14)
* Initial release
