import json, os, zipfile, pathlib
I = pathlib.Path(os.environ['USERPROFILE']) / 'curseforge/minecraft/Install'
L = I / 'libraries'
paths = []
for v in ['forge-36.2.42/forge-36.2.42.json', '1.16.5/1.16.5.json']:
    d = json.load(open(I / 'versions' / v))
    for lib in d.get('libraries', []):
        art = lib.get('downloads', {}).get('artifact')
        if art and art.get('path'):
            p = L / art['path']
        else:
            g, a, ver = lib['name'].split(':')[:3]
            p = L / g.replace('.', '/') / a / ver / f'{a}-{ver}.jar'
        if p.exists() and p not in paths:
            paths.append(p)
F = L / 'net/minecraftforge/forge/1.16.5-36.2.42'
M = L / 'net/minecraft/client/1.16.5-20210115.111550'
core = [F/'forge-1.16.5-36.2.42-client.jar', F/'forge-1.16.5-36.2.42-universal.jar', M/'client-1.16.5-20210115.111550-srg.jar', M/'client-1.16.5-20210115.111550-extra.jar']
allp = core + [p for p in paths if p not in core]
cp = ' '.join(pathlib.Path(p).as_uri() for p in allp)
with zipfile.ZipFile('cp.jar', 'w') as z:
    lines = 'Manifest-Version: 1.0\r\nClass-Path: ' + cp
    # wrap at 70 bytes
    out = ''; 
    first = True
    while lines:
        chunk = lines[:70] if first else lines[:69]
        out += ('' if first else ' ') + chunk + '\r\n'; lines = lines[len(chunk):]; first = False
    z.writestr('META-INF/MANIFEST.MF', out + '\r\n')
print(len(allp), 'entries'); print('\n'.join(str(p.name) for p in allp if 'guava' in str(p) or 'forge' in str(p)))
