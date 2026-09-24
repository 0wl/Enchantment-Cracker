"""Launch a throwaway Forge 1.16.5 client with the mod + self-test mod. Usage: launch.py vanilla|apoth"""
import json, os, pathlib, shutil, subprocess, sys, uuid, zipfile

mode = sys.argv[1]
HERE = pathlib.Path(__file__).resolve().parent
MOD_JAR = HERE.parents[2] / 'output' / 'enchcracker-1.1.0-forge-1.16.5.jar'
I = pathlib.Path(os.environ['USERPROFILE']) / 'curseforge/minecraft/Install'
L = I / 'libraries'
PACK_MODS = pathlib.Path(os.environ['USERPROFILE']) / 'curseforge/minecraft/Instances/Dungeons Dragons and Space Shuttles 2/mods'
JAVA = I / 'runtime/jre-legacy/windows-x64/jre-legacy/bin/java.exe'

game = HERE / ('run-' + mode)
if game.exists():
    shutil.rmtree(game)
(game / 'mods').mkdir(parents=True)
shutil.copy(MOD_JAR, game / 'mods')
shutil.copy(HERE / 'enchcrackertest.jar', game / 'mods')
if mode == 'apoth':
    for jar in ('Apotheosis-1.16.5-4.8.9A0.jar', 'Placebo-1.16.5-4.7.1.jar'):
        shutil.copy(PACK_MODS / jar, game / 'mods')
(game / 'options.txt').write_text('\n'.join([
    'pauseOnLostFocus:false', 'renderDistance:4', 'guiScale:2', 'tutorialStep:none',
    'skipMultiplayerWarning:true', 'joinedFirstServer:true', 'soundCategory_master:0.0',
    'fullscreen:false', 'overrideWidth:1280', 'overrideHeight:720']) + '\n')

classpath = []
natives = HERE / 'natives'
natives.mkdir(exist_ok=True)
for version in ('forge-36.2.42/forge-36.2.42.json', '1.16.5/1.16.5.json'):
    data = json.load(open(I / 'versions' / version))
    for lib in data.get('libraries', []):
        downloads = lib.get('downloads', {})
        art = downloads.get('artifact')
        if art and art.get('path'):
            path = L / art['path']
            if path.exists() and str(path) not in classpath:
                classpath.append(str(path))
        classifiers = downloads.get('classifiers', {})
        native = classifiers.get('natives-windows')
        if native:
            path = L / native['path']
            if path.exists():
                with zipfile.ZipFile(path) as z:
                    for name in z.namelist():
                        if name.endswith('.dll') and ('64' in name or 'x86' not in name):
                            target = natives / pathlib.Path(name).name
                            if not target.exists():
                                target.write_bytes(z.read(name))
classpath.append(str(I / 'versions/1.16.5/1.16.5.jar'))

args = [str(JAVA), '-Xmx3G', '-Djava.library.path=' + str(natives),
        '-Denchcracker.selftest.mode=' + mode,
        '-cp', ';'.join(classpath), 'cpw.mods.modlauncher.Launcher',
        '--username', 'SelfTest', '--version', 'forge-36.2.42', '--gameDir', str(game),
        '--assetsDir', str(I / 'assets'), '--assetIndex', '1.16', '--uuid', uuid.uuid4().hex,
        '--accessToken', '0', '--userType', 'legacy', '--versionType', 'release',
        '--width', '1280', '--height', '720',
        '--launchTarget', 'fmlclient', '--fml.forgeVersion', '36.2.42', '--fml.mcVersion', '1.16.5',
        '--fml.forgeGroup', 'net.minecraftforge', '--fml.mcpVersion', '20210115.111550']
print('launching', mode, 'with', len(classpath), 'classpath entries')
with open(game / 'launch-output.txt', 'w', encoding='utf-8', errors='replace') as out:
    proc = subprocess.run(args, cwd=str(game), stdout=out, stderr=subprocess.STDOUT, timeout=1500)
print('exit', proc.returncode)
