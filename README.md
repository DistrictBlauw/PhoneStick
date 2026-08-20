# PhoneStick 📱💾

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/icon.png" width="128" height="128" alt="PhoneStick Icon">
</p>

PhoneStick turns your rooted Android device into a USB Mass Storage drive or CD-ROM emulator using Android Kernel ConfigFS and USB gadget drivers.

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1_main.png" width="360" alt="PhoneStick Screenshot">
</p>

## Features

- **Material Design 3**: Modern, clean UI with dynamic light/dark theme support and speed-dial FAB actions.
- **Mount Raw Disk Images**: Mount `.img`, `.iso`, `.bin`, `.raw`, `.vhd`, `.qcow2` files directly as USB flash drives or CD-ROMs.
- **Zero-Copy SAF Resolution**: Resolves external storage URIs (SD cards, Downloads) to direct Linux kernel paths without duplicating files.
- **Emulation Modes**: Supports Read-Only and virtual CD-ROM emulation modes.
- **ConfigFS & Sysfs Support**: Direct binding support for Android ConfigFS (`/config/usb_gadget/g1` & `/sys/kernel/config`) and sysfs LUN targets.
- **OnePlus / OPPO / realme Support**: Native ColorOS / OxygenOS adaptation that composes the mass storage function directly on configfs and re-binds the UDC, without touching `sys.usb.config`.
- **Detailed Logging & Export**: Every operation (root checks, strategy selection, shell results, backup/restore verification) is recorded in a persistent in-app log viewable, exportable and shareable as plain text.
- **Blank Image Creation**: Easily allocate and format blank disk images right inside the app.

## Installation

- **GitHub Release**: Download the latest APK from [Releases](https://github.com/mingww64/PhoneStick/releases).
- **F-Droid**: Build recipe and metadata available in [`metadata/mingww64.phonestick.yml`](metadata/mingww64.phonestick.yml).

## Requirements

- Rooted Android device (Magisk / KernelSU / APatch)
- Android kernel with USB Mass Storage gadget support (`CONFIG_USB_F_MASS_STORAGE` or ConfigFS)

## How the OPlus (OnePlus / OPPO / realme) strategy works

ColorOS mounts configfs at `/config` and pre-creates `usb_gadget/g1` with a
`mass_storage.0` function instance, but its init never composes
`sys.usb.config` with `mass_storage`, so the classic `setprop` toggle does
nothing. PhoneStick instead:

1. Reads the active UDC (`sys.usb.controller`, e.g. `a600000.dwc3`) and
   detaches `g1` from it.
2. Fills `functions/mass_storage.0/lun.0` (`file`, `ro`, `cdrom`,
   `removable`, `stall`).
3. Symlinks `functions/mass_storage.0` into the active config
   (`configs/b.1`), keeping ADB/MTP alive as a composite device.
4. Re-attaches the UDC so the host re-enumerates and the drive appears.

If your OPlus build denies configfs writes to root under enforcing SELinux,
the app briefly drops to permissive for the LUN write only and restores
enforcing immediately after.

## Configuration backup & restore

Before mounting, PhoneStick snapshots the original USB gadget state into
app-private storage:

- UDC binding (e.g. `a600000.dwc3`)
- every function symlink inside every gadget config
- every mass_storage LUN (`file` / `ro` / `cdrom`) and `stall` flag
- on legacy devices, the original `sys.usb.config` value

Unmounting restores that snapshot exactly: mass_storage links are stripped,
links that existed before are recreated, LUN parameters are written back, and
the saved UDC is re-attached. The restore result is verified and, if anything
fails, the backup is kept so you can simply tap unmount again. Snapshots are
never overwritten by repeated mounts, so the restore target is always the
true original state.

## Logging & diagnostics

Open **Logs** from the main menu to inspect the persistent application log
(level-colored, newest first). It records device info, root checks, OPlus
detection, strategy selection, every shell command with exit code / stdout /
stderr, backup snapshots and restore verification results. Use the toolbar
menu to export the log to any location, share it as a `.txt` attachment, or
clear it. Logs survive app restarts (up to 2000 entries / 512 KB).

## Localization

English, German, French, Italian, Lithuanian and Simplified Chinese.

## License

[MIT License](LICENSE)

Original work by streetwalrus, dratini0, donfanning, Swyter, and JinbaIttai.
