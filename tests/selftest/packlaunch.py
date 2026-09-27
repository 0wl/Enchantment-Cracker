"""Full-pack self test: a throwaway copy of the DDSS2 instance (every mod, config, KubeJS and script,
the user's own Enchantment Cracker jar swapped for the build under test) runs PackTest in singleplayer.

Usage: packlaunch.py <enchcrackertest.jar>
Report: run-pack/selftest-report-pack.txt. The real instance is only read, never written.
"""
import glob, hashlib, json, os, pathlib, shutil, subprocess, sys, uuid, zipfile

TEST_JAR = pathlib.Path(sys.argv[1])
HERE = pathlib.Path(__file__).resolve().parent
MOD_JAR = os.environ.get('ENCH_MOD_JAR') or sorted(
    glob.glob(str(HERE.parents[2] / 'output' / 'enchcracker-*-forge-1.16.5.jar')), key=os.path.getmtime)[-1]
I = pathlib.Path(os.environ['USERPROFILE']) / 'curseforge/minecraft/Install'
L = I / 'libraries'
PACK = pathlib.Path(os.environ['USERPROFILE']) / 'curseforge/minecraft/Instances/Dungeons Dragons and Space Shuttles 2'
JAVA = I / 'runtime/jre-legacy/windows-x64/jre-legacy/bin/java.exe'

game = HERE / 'run-pack'
if game.exists():
    shutil.rmtree(game)
(game / 'mods').mkdir(parents=True)
for jar in (PACK / 'mods').glob('*.jar'):
    if not jar.name.startswith('enchcracker'):
        shutil.copy(jar, game / 'mods')
shutil.copy(MOD_JAR, game / 'mods')
shutil.copy(TEST_JAR, game / 'mods')
for folder in ('config', 'defaultconfigs', 'kubejs', 'scripts', 'datapacks', 'patchouli_books'):
    if (PACK / folder).exists():
        shutil.copytree(PACK / folder, game / folder)
(game / 'options.txt').write_text('\n'.join([
    'pauseOnLostFocus:false', 'renderDistance:4', 'guiScale:2', 'tutorialStep:none',
    'skipMultiplayerWarning:true', 'joinedFirstServer:true', 'soundCategory_master:0.0',
    'fullscreen:false', 'overrideWidth:1280', 'overrideHeight:720']) + '\n')

cp = []
natives = HERE / 'natives'
natives.mkdir(exist_ok=True)
for version in ('forge-36.2.42/forge-36.2.42.json', '1.16.5/1.16.5.json'):
    data = json.load(open(I / 'versions' / version))
    for lib in data.get('libraries', []):
        art = lib.get('downloads', {}).get('artifact')
        if art and art.get('path'):
            path = L / art['path']
            if path.exists() and str(path) not in cp:
                cp.append(str(path))
cp.append(str(I / 'versions/1.16.5/1.16.5.jar'))

offline = uuid.UUID(bytes=hashlib.md5(b'OfflinePlayer:SelfTest').digest(), version=3)
args = [str(JAVA), '-Xmx7G', '-XX:+UseG1GC', '-Djava.library.path=' + str(natives),
        '-Denchcracker.selftest.mode=pack',
        '-cp', ';'.join(cp), 'cpw.mods.modlauncher.Launcher',
        '--username', 'SelfTest', '--version', 'forge-36.2.42', '--gameDir', str(game),
        '--assetsDir', str(I / 'assets'), '--assetIndex', '1.16', '--uuid', offline.hex,
        '--accessToken', '0', '--userType', 'legacy', '--versionType', 'release',
        '--width', '1280', '--height', '720',
        '--launchTarget', 'fmlclient', '--fml.forgeVersion', '36.2.42', '--fml.mcVersion', '1.16.5',
        '--fml.forgeGroup', 'net.minecraftforge', '--fml.mcpVersion', '20210115.111550']
print('launching the full pack (%d mods) with %s' % (len(list((game / 'mods').glob('*.jar'))), MOD_JAR))
with open(game / 'launch-output.txt', 'w', encoding='utf-8', errors='replace') as out:
    proc = subprocess.run(args, cwd=str(game), stdout=out, stderr=subprocess.STDOUT, timeout=5400)
print('exit', proc.returncode)
report = game / 'selftest-report-pack.txt'
print(report.read_text() if report.exists() else 'no report written')
