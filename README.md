# Skyrim Anim Tool

An Android app that manages [FNIS Behavior 7.6](https://www.nexusmods.com/skyrim/mods/11811)
for Skyrim Legendary Edition running on the static Android port through
[Skyrim Mod Loader](https://github.com/TweakerZ/Skyrim-Mod-Loader).

The app reads the FNIS files inside your Skyrim `Data` folder, shows the
generation options and the patches, and writes your choices back to
`Data/FNIS.ini`. It can also run the FNIS template pass and export the
generated scripts and patch report into `Data`.

## What it does

- Reads `FNIS.ini`, `PatchList.txt`, `Alternate_AnimationGroups.txt`,
  `AnimVars.txt`, and `FNIS_Templates.txt` from the selected Data folder.
- Lists all 25 patches from `PatchList.txt` and lets you enable or disable the
  13 selectable patches. Disabled patch IDs are passed to the template pass.
- Saves option and patch overrides in app preferences, so they survive a
  restart.
- Writes the active options (PSCD, Instant Execute, Load CTD Calculation) to
  `Data/FNIS.ini`.
- Generates the FNIS scripts and `FNIS_Patches.txt`, and backs up any file it
  replaces under
  `Data/tools/GenerateFNIS_for_Users/temporary_logs/backup_<timestamp>/`.

## What it does not do yet

Behavior and animation data files are generated in app storage but not
exported. The template engine does not reproduce FNIS output closely enough:
generated behavior graphs still contain unresolved references, and the
animation data record layout is not exact. Writing those files over working
ones T-poses the character or crashes Skyrim on the first frame. The app keeps
the mod's behavior and animation data files untouched and reports what it
skipped in the log.

So the app currently installs scripts and the patch report, not new behaviors.
Full behavior parity is the remaining work.

## Requirements

- Skyrim Legendary Edition static Android port. The app expects the folder that
  contains `Data`.
- Skyrim Mod Loader, with FNIS Behavior 7.6 installed through it.
- Android 7.0 (API 24) or newer.
- All-files access, because FNIS files are read and written by real filesystem
  path.

## Build

```sh
./gradlew assembleRelease
```

Release signing reads `keystore.properties` from the project root:

```properties
storeFile=keystore/fnis-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Both `keystore.properties` and the `keystore` folder are git ignored. Without
them the release build is unsigned.

## Related projects

- [loot-android](https://github.com/TweakerZ/loot-android): LOOT front-end for
  the same port. This app follows its single-page layout and Nordic color
  palette.
- [Skyrim Mod Loader](https://github.com/TweakerZ/Skyrim-Mod-Loader): the mod
  manager that installs and deploys the FNIS mod this app configures.

## Credits

- FNIS Behavior 7.6 by fore. The templates, patch list, animation group data,
  and script sources come from that mod.
- Skyrim and its behavior and animation formats by Bethesda Game Studios. Havok
  packfile format by Havok. The XML to binary packfile writer in this app was
  built for interoperability with those formats.
- The Skyrim Legendary Edition static Android port, which this app targets.
- Skyrim Mod Loader by TweakerZ, a fork of DroidModLoader.

## Disclaimer

AI was used in building this. The code and APKs are provided as-is, with no
warranty. Back up your `Data` folder before exporting, and test on a save you
can lose.
