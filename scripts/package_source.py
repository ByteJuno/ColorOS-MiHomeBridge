"""Package redistributable sources, excluding signing keys, firmware and private captures."""
import hashlib
import json
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
metadata = json.loads((root / 'app/build/outputs/apk/release/output-metadata.json').read_text(encoding='utf8'))
version = metadata['elements'][0]['versionName']
dist = root / 'dist'
apk = dist / f'ColorOS-MiHomeBridge-{version}.apk'
if not apk.is_file():
    raise SystemExit('Build the release before packaging.')
files = [root / name for name in ['README.md', 'CHANGELOG.md', '.gitignore', 'build.gradle.kts', 'settings.gradle.kts',
                                 'gradle.properties', 'gradlew', 'gradlew.bat', 'app/build.gradle.kts']]
for directory in ['app/src', 'gradle', 'scripts', 'docs']:
    files.extend(path for path in (root / directory).rglob('*')
                 if path.is_file() and '__pycache__' not in path.parts and path.suffix != '.pyc')
archive = dist / f'ColorOS-MiHomeBridge-{version}-source.zip'
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as output:
    for path in sorted(files, key=lambda path: path.relative_to(root).as_posix()):
        output.write(path, path.relative_to(root).as_posix())
with zipfile.ZipFile(archive) as check:
    assert check.testzip() is None
    assert 'app/src/main/AndroidManifest.xml' in check.namelist()
    assert all(not name.startswith(('.local/', 'dist/', '.gradle/')) for name in check.namelist())
lines = []
for path in [apk, archive]:
    with path.open('rb') as stream:
        lines.append(f'{hashlib.file_digest(stream, "sha256").hexdigest()}  {path.name}')
(dist / 'SHA256SUMS.txt').write_text('\n'.join(lines) + '\n', encoding='ascii')
print(f'Packaged {archive.name}: {len(files)} files, keys and private captures excluded.')
