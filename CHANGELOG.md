# Changelog

## Unreleased
* Added an Automatic mode, now the default, that only turns laravel-fmt on in projects that use it, so a global laravel-fmt no longer takes over Reformat Code in every PHP project
* Added a Run laravel-fmt save action, so PHP and Blade files can be formatted on save without the IDE's Reformat code save action reformatting every other language
* Added PHP and Blade checkboxes to the settings, so laravel-fmt can format only one of them and leave the other to PhpStorm or a tool like Pint
* Changed the minimum PhpStorm version to 2025.3
* Changed the plugin icon to laravel-fmt's own logo

## Version 0.1.0 (2026-10-14)
* Initial release
