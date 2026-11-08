# Personal offline bundle

The APK accepts a ZIP with `manifest.json` as its first entry, followed by the five files listed in the manifest. Use `create_bundle.py` to generate it from verified Linux runtime, KDE desktop, PRoot and DirectAudio archives plus an ARM64 Steam client already installed from files you supplied.

Example:

```powershell
python tools/offline/create_bundle.py `
  --runtime .\downloads\linuxfs.tar.zst --runtime-version ubuntu-tag `
  --desktop .\downloads\kde.tar.zst --desktop-version kde-tag `
  --proot .\downloads\proot.tar.zst --directaudio .\downloads\directaudio.tar.zst `
  --steam-root .\steam-client-root --output .\droiddeck-offline.zip
```

Set `--steam-root` to the directory containing your supplied ARM64 Steam client. It must contain `steamrtarm64\steam` and `linuxarm64\`.

The Steam archive contains only ARM64 client files. It excludes game installs, login/account files, configuration, logs and caches. Keep the resulting bundle private: the script does not grant permission to redistribute Steam client files. PRoot and DirectAudio payloads must match the hashes pinned in the APK; an incompatible APK rejects them at install time.
