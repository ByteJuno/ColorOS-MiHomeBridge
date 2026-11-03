"""Build artifacts and publish versioned releases without storing signing keys in Git."""
import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CERTIFICATE = 'e1631c0dcb618209c1e81f74c4b03a198a457314bb2141c3931d903d1cab84c0'
VERSION = re.compile(r'[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?')


def command(*args, capture=True):
    return subprocess.run(args, cwd=ROOT, check=True, text=True, encoding='utf8',
                          stdout=subprocess.PIPE if capture else None).stdout


def ci_only():
    if os.environ.get('GITHUB_ACTIONS') != 'true' or Path(os.environ.get('GITHUB_WORKSPACE', '')).resolve() != ROOT:
        raise RuntimeError('Signing preparation and cleanup only run inside this repository in GitHub Actions.')


def property_value(value):
    encoded = value.encode('utf-16-be')
    return ''.join('\\u' + encoded[i:i + 2].hex() for i in range(0, len(encoded), 2))


def prepare_signing():
    ci_only()
    directory = ROOT / '.local/signing'
    store, config = directory / 'module.p12', directory / 'signing.properties'
    if store.exists() or config.exists():
        raise RuntimeError('Refusing to replace existing signing files.')
    keys = ['ANDROID_SIGNING_KEYSTORE_BASE64', 'ANDROID_SIGNING_STORE_PASSWORD',
            'ANDROID_SIGNING_KEY_ALIAS', 'ANDROID_SIGNING_KEY_PASSWORD']
    if any(not os.environ.get(key) for key in keys):
        raise RuntimeError('Configure the four ANDROID_SIGNING_* repository secrets first.')
    material = base64.b64decode(os.environ[keys[0]], validate=True)
    if not 1 <= len(material) <= 1024 * 1024:
        raise RuntimeError('Invalid signing store size.')
    directory.mkdir(parents=True, exist_ok=True)
    store.write_bytes(material)
    values = {'storePassword': os.environ[keys[1]], 'keyAlias': os.environ[keys[2]], 'keyPassword': os.environ[keys[3]]}
    config.write_text('storeFile=.local/signing/module.p12\n' + ''.join(
        key + '=' + property_value(value) + '\n' for key, value in values.items()), encoding='ascii')
    store.chmod(0o600)
    config.chmod(0o600)
    print('Original signing configuration prepared.')


def cleanup_signing():
    ci_only()
    for name in ['module.p12', 'signing.properties']:
        (ROOT / '.local/signing' / name).unlink(missing_ok=True)


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def collect(build_tools):
    metadata_file = ROOT / 'app/build/outputs/apk/release/output-metadata.json'
    metadata = json.loads(metadata_file.read_text(encoding='utf8'))
    if metadata['applicationId'] != 'io.github.coloros.mihomebridge' or len(metadata['elements']) != 1:
        raise RuntimeError('Unexpected release package metadata.')
    element = metadata['elements'][0]
    version = element['versionName']
    if not VERSION.fullmatch(version):
        raise RuntimeError('Unsupported version name.')
    if os.environ.get('GITHUB_REF_TYPE') == 'tag' and os.environ.get('GITHUB_REF_NAME') != 'v' + version:
        raise RuntimeError('The Git tag must match versionName: v' + version)
    source_apk = (metadata_file.parent / element['outputFile']).resolve()
    if not source_apk.is_relative_to(metadata_file.parent.resolve()):
        raise RuntimeError('APK path is outside the release output directory.')
    tools = Path(build_tools)
    signer = tools / ('apksigner.bat' if os.name == 'nt' else 'apksigner')
    aligner = tools / ('zipalign.exe' if os.name == 'nt' else 'zipalign')
    verification = command(str(signer), 'verify', '--verbose', '--print-certs', str(source_apk))
    certificates = re.findall(r'certificate SHA-256 digest:\s*([a-fA-F0-9]+)', verification)
    if [value.lower() for value in certificates] != [CERTIFICATE] or 'Verified using v2 scheme (APK Signature Scheme v2): true' not in verification:
        raise RuntimeError('Release APK must use the existing module certificate and v2 signature.')
    command(str(aligner), '-c', '-P', '16', '4', str(source_apk))
    dist = ROOT / 'dist'
    dist.mkdir(exist_ok=True)
    apk = dist / f'ColorOS-MiHomeBridge-{version}.apk'
    shutil.copyfile(source_apk, apk)
    command(sys.executable, str(ROOT / 'scripts/package_source.py'), capture=False)
    source = dist / f'ColorOS-MiHomeBridge-{version}-source.zip'
    tests = {}
    for directory in sorted((ROOT / 'app/build/test-results').glob('test*UnitTest')):
        suites = [ET.parse(path).getroot() for path in directory.glob('TEST-*.xml')]
        tests[directory.name] = {key: sum(int(suite.get(key, 0)) for suite in suites)
                                 for key in ['tests', 'failures', 'errors', 'skipped']}
    acceptance = ROOT / f'docs/build-verification-{version.removesuffix("-preview")}.json'
    accepted_hash = json.loads(acceptance.read_text(encoding='utf8')).get('sha256') if acceptance.exists() else None
    info = {'version': version, 'version_code': element['versionCode'], 'package': metadata['applicationId'],
            'source_commit': command('git', 'rev-parse', 'HEAD').strip(),
            'source_dirty': bool(command('git', 'status', '--porcelain').strip()),
            'certificate_sha256': CERTIFICATE, 'signature_v2_verified': True, 'zipalign_verified': True,
            'github_run': os.environ.get('GITHUB_RUN_ID'), 'tests': tests,
            'device_acceptance_apk_sha256': accepted_hash,
            'matches_device_tested_apk_bytes': accepted_hash == digest(apk),
            'files': {path.name: {'bytes': path.stat().st_size, 'sha256': digest(path)} for path in [apk, source]}}
    report = dist / 'build-info.json'
    report.write_text(json.dumps(info, ensure_ascii=False, indent=2) + '\n', encoding='utf8')
    (dist / 'SHA256SUMS.txt').write_text(''.join(f'{digest(path)}  {path.name}\n' for path in [apk, source, report]), encoding='ascii')
    print(json.dumps({'version': version, 'apk': apk.name, 'certificate_verified': True}))


def publish():
    ci_only()
    info = json.loads((ROOT / 'dist/build-info.json').read_text(encoding='utf8'))
    version, commit = info['version'], info['source_commit']
    tag = 'v' + version
    if commit != os.environ['GITHUB_SHA'] or info['source_dirty']:
        raise RuntimeError('Publish only a clean checkout of the workflow commit.')
    reference = subprocess.run(['git', 'rev-parse', '--verify', '--quiet', f'refs/tags/{tag}^{{commit}}'],
                               cwd=ROOT, capture_output=True, text=True)
    if reference.returncode == 0 and reference.stdout.strip() != commit:
        raise RuntimeError('This version tag belongs to another commit; increment versionName and versionCode.')
    repo = os.environ['GITHUB_REPOSITORY']
    if json.loads(command('gh', 'repo', 'view', repo, '--json', 'visibility'))['visibility'] != 'PRIVATE':
        raise RuntimeError('This packaging workflow publishes only to the private project repository.')
    existing = subprocess.run(['gh', 'release', 'view', tag, '--repo', repo, '--json', 'url'],
                              cwd=ROOT, capture_output=True, text=True)
    if existing.returncode == 0:
        if reference.returncode != 0:
            raise RuntimeError('Existing release has no matching local tag; inspect it before publishing.')
        print('Release already exists: ' + json.loads(existing.stdout)['url'])
        return
    changes = (ROOT / 'CHANGELOG.md').read_text(encoding='utf-8-sig')
    heading = '## ' + version
    section = changes.split(heading, 1)[1].split('\n## ', 1)[0].strip() if heading in changes else '仓库自动构建。'
    body = (f'从 `{commit}` 自动构建，沿用现有模块签名，可覆盖已有安装。\n\n'
            f'{section}\n\n构建校验、产物 SHA-256 与实机验收包的关系见 `build-info.json`；'
            '同版本功能的实机验证记录见仓库 `docs/validation.md`。\n')
    notes = ROOT / '.local/release-notes.md'
    notes.parent.mkdir(exist_ok=True)
    notes.write_text(body, encoding='utf8')
    assets = [ROOT / 'dist' / name for name in info['files']]
    assets.extend([ROOT / 'dist/SHA256SUMS.txt', ROOT / 'dist/build-info.json'])
    args = ['gh', 'release', 'create', tag, '--repo', repo, '--target', commit, '--title', version,
            '--notes-file', str(notes)]
    if '-' in version:
        args.extend(['--prerelease', '--latest=false'])
    command(*args, *(str(path) for path in assets), capture=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=['prepare-signing', 'cleanup-signing', 'collect', 'publish'])
    parser.add_argument('--build-tools')
    args = parser.parse_args()
    if args.operation == 'prepare-signing': prepare_signing()
    elif args.operation == 'cleanup-signing': cleanup_signing()
    elif args.operation == 'publish': publish()
    elif args.build_tools: collect(args.build_tools)
    else: parser.error('collect requires --build-tools')


if __name__ == '__main__':
    main()
