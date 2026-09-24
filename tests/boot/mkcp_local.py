"""Build cp.jar: a manifest-only jar whose Class-Path chains every Forge/Minecraft runtime
jar, so LoadTest/Verify can link the mod against the real classes with no huge command line.

Points at %APPDATA%\\.minecraft\\libraries, where this machine keeps the Forge 1.16.5 install
(both the Forge jars and the Minecraft client SRG jar). Writes cp.jar in the current directory.
"""
import os, pathlib, re, zipfile

FORGE = "1.16.5-36.2.42"
MC = "1.16.5-20210115.111550"
LIB = pathlib.Path(os.environ['APPDATA']) / '.minecraft' / 'libraries'

forge_dir = LIB / 'net/minecraftforge/forge' / FORGE
mc_dir = LIB / 'net/minecraft/client' / MC
core = [
    forge_dir / f'forge-{FORGE}-client.jar',
    forge_dir / f'forge-{FORGE}-universal.jar',
    mc_dir / f'client-{MC}-srg.jar',
    mc_dir / f'client-{MC}-extra.jar',
]
for jar in core:
    if not jar.exists():
        raise SystemExit('missing ' + str(jar))

skip = re.compile(r'1\.12|1\.20|1\.21|neoforged|sources|javadoc')
jars = list(core)
for jar in LIB.rglob('*.jar'):
    if skip.search(str(jar)):
        continue
    if jar not in jars:
        jars.append(jar)

class_path = ' '.join(pathlib.Path(j).as_uri() for j in jars)
manifest = 'Manifest-Version: 1.0\r\nClass-Path: ' + class_path
# Wrap at 70 bytes per the jar manifest spec (continuation lines start with a space).
wrapped, first = '', True
while manifest:
    chunk = manifest[:70] if first else manifest[:69]
    wrapped += ('' if first else ' ') + chunk + '\r\n'
    manifest = manifest[len(chunk):]
    first = False
with zipfile.ZipFile('cp.jar', 'w') as z:
    z.writestr('META-INF/MANIFEST.MF', wrapped + '\r\n')
print(len(jars), 'runtime jars ->', pathlib.Path('cp.jar').resolve())
