#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import stat
import tarfile
import tempfile
import zipfile

PAYLOADS = {
    "runtime": "runtime.tar.zst",
    "desktop": "desktop.tar.zst",
    "proot": "proot.tar.zst",
    "directaudio": "directaudio.tar.zst",
    "steam": "steam.tar",
}
PRIVATE = {"steamapps", "userdata", "logs", "dumps", "depotcache", "appcache", "shadercache", "compatibilitytools.d", "droiddeck-download"}
CLIENT_ROOTS = {"steamrtarm64", "linuxarm64", "linux64", "linux32"}
MAX_PAYLOAD = 32 * 1024**3
MAX_BUNDLE = 48 * 1024**3
MAX_STEAM_EXTRACTED = 20 * 1024**3
MAX_STEAM_ENTRIES = 100_000
PRIVATE_FILES = {"loginusers.vdf", "localconfig.vdf", "steam.pid", "steam.pipe"}


def safe_path(value):
    path = PurePosixPath(value)
    parts = path.parts
    if not value or path.is_absolute() or "\\" in value or any(p in ("", ".", "..") for p in parts):
        return False
    if parts[0] not in CLIENT_ROOTS or any(p in PRIVATE or p.startswith("ssfn") for p in parts):
        return False
    name = parts[-1].lower()
    return name not in PRIVATE_FILES and not name.endswith((".log", ".dmp")) and name != ".crash"


def safe_link(name, target):
    if not safe_path(name) or not target or target.startswith("/") or "\\" in target:
        return False
    resolved = PurePosixPath(name).parent.joinpath(target)
    parts = []
    for part in resolved.parts:
        if part == "..":
            if not parts:
                return False
            parts.pop()
        elif part not in ("", "."):
            if part in PRIVATE or part.startswith("ssfn"):
                return False
            parts.append(part)
    return True


def digest(path):
    sha = hashlib.sha256()
    size = 0
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            size += len(chunk)
            if size > MAX_PAYLOAD:
                raise ValueError(f"Payload is too large: {path}")
            sha.update(chunk)
    return size, sha.hexdigest()


def steam_archive(root, target):
    root = root.resolve(strict=True)
    if not (root / "steamrtarm64/steam").is_file() or not (root / "linuxarm64").is_dir():
        raise ValueError("Steam root must contain steamrtarm64/steam and linuxarm64")
    entry_count = 0
    extracted_bytes = 0
    with tarfile.open(target, "w", format=tarfile.PAX_FORMAT) as archive:
        def add(path, relative):
            nonlocal entry_count, extracted_bytes
            if not safe_path(relative):
                return
            entry_count += 1
            if entry_count > MAX_STEAM_ENTRIES:
                raise ValueError("Steam client contains too many files")
            info = path.lstat()
            if stat.S_ISLNK(info.st_mode):
                link = os.readlink(path)
                if not safe_link(relative, link):
                    raise ValueError(f"Unsafe Steam symlink: {relative} -> {link}")
                archive.add(path, arcname=relative, recursive=False)
            elif stat.S_ISDIR(info.st_mode):
                archive.add(path, arcname=relative, recursive=False)
                for child in sorted(path.iterdir()):
                    add(child, f"{relative}/{child.name}")
            elif stat.S_ISREG(info.st_mode):
                extracted_bytes += info.st_size
                if extracted_bytes > MAX_STEAM_EXTRACTED:
                    raise ValueError("Steam client expands beyond the supported size")
                archive.add(path, arcname=relative, recursive=False)
            else:
                raise ValueError(f"Unsupported Steam file type: {relative}")

        for name in sorted(CLIENT_ROOTS):
            path = root / name
            if path.exists() or path.is_symlink():
                add(path, name)
    return Path(target)


def main():
    parser = argparse.ArgumentParser(description="Build a private DroidDeck offline bundle; Steam files stay local to you.")
    for name in ("runtime", "desktop", "proot", "directaudio"):
        parser.add_argument(f"--{name}", type=Path, required=True, help=f"verified {name} archive")
    parser.add_argument("--runtime-version", required=True)
    parser.add_argument("--desktop-version", required=True)
    parser.add_argument("--steam-root", type=Path, required=True, help="your own installed ARM64 Steam client directory")
    parser.add_argument("--steam-version", default="user-supplied")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    inputs = {name: getattr(args, name).resolve(strict=True) for name in ("runtime", "desktop", "proot", "directaudio")}
    with tempfile.TemporaryDirectory(prefix="droiddeck-offline-") as temporary:
        inputs["steam"] = steam_archive(args.steam_root, Path(temporary) / PAYLOADS["steam"])
        versions = {
            "runtime": args.runtime_version,
            "desktop": args.desktop_version,
            "proot": digest(inputs["proot"])[1],
            "directaudio": digest(inputs["directaudio"])[1],
            "steam": args.steam_version,
        }
        manifest = {"schema": 1}
        for name, file_name in PAYLOADS.items():
            size, sha256 = digest(inputs[name])
            if size < 1:
                raise ValueError(f"Empty {name} payload")
            manifest[name] = {"path": f"payload/{file_name}", "version": versions[name], "size": size, "sha256": sha256}
        if sum(item["size"] for item in manifest.values() if isinstance(item, dict)) > MAX_BUNDLE:
            raise ValueError("Offline bundle exceeds the supported total size")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(args.output, "w", allowZip64=True, compression=zipfile.ZIP_STORED) as bundle:
            bundle.writestr("manifest.json", json.dumps(manifest, indent=2, sort_keys=True) + "\n")
            for name, file_name in PAYLOADS.items():
                bundle.write(inputs[name], f"payload/{file_name}")
    print(f"Created {args.output} ({args.output.stat().st_size} bytes). Steam client files were included from your local installation.")


if __name__ == "__main__":
    main()
