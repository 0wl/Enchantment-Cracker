"""Network self test: a real Forge 1.16.5 dedicated server plus a test client that joins it over TCP.

Usage: netlaunch.py vanilla|apoth <enchcrackertest.jar>

The server is a Forge 36.2.42 install in %LOCALAPPDATA%\\enchcracker-build\\server (install it with
`java -jar forge-1.16.5-36.2.42-installer.jar --installServer <dir>`). Offline mode, the test player
is op, and a flat world is made fresh each run. With `apoth`, Apotheosis and Placebo from the DDSS2
pack go on both sides. The report lands in run-net<mode>/selftest-report-net-<mode>.txt.
"""
import glob, hashlib, json, os, pathlib, shutil, subprocess, sys, time, uuid, zipfile

mode = sys.argv[1]
TEST_JAR = pathlib.Path(sys.argv[2])
HERE = pathlib.Path(__file__).resolve().parent
MOD_JAR = sorted(glob.glob(str(HERE.parents[2] / 'output' / 'enchcracker-*-forge-1.16.5.jar')), key=os.path.getmtime)[-1]
I = pathlib.Path(os.environ['USERPROFILE']) / 'curseforge/minecraft/Install'
L = I / 'libraries'
PACK_MODS = pathlib.Path(os.environ['USERPROFILE']) / 'curseforge/minecraft/Instances/Dungeons Dragons and Space Shuttles 2/mods'
JAVA = I / 'runtime/jre-legacy/windows-x64/jre-legacy/bin/java.exe'
SERVER = pathlib.Path(os.environ['LOCALAPPDATA']) / 'enchcracker-build' / 'server'
PORT = 25599
NAME = 'SelfTest'
APOTH_JARS = ('Apotheosis-1.16.5-4.8.9A0.jar', 'Placebo-1.16.5-4.7.1.jar')

# ---------------------------------------------------------------- server
for d in ('mods', 'world', 'logs', 'config'):
    shutil.rmtree(SERVER / d, ignore_errors=True)
(SERVER / 'mods').mkdir()
if mode == 'apoth':
    for jar in APOTH_JARS:
        shutil.copy(PACK_MODS / jar, SERVER / 'mods')
(SERVER / 'eula.txt').write_text('eula=true\n')
(SERVER / 'server.properties').write_text('\n'.join([
    'online-mode=false', 'server-port=%d' % PORT, 'server-ip=127.0.0.1', 'level-type=flat', 'spawn-protection=0',
    'view-distance=4', 'difficulty=peaceful', 'gamemode=survival', 'max-players=2', 'motd=enchcracker test',
    'spawn-monsters=false', 'enable-command-block=false']) + '\n')
offline = uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + NAME).encode()).digest(), version=3)
(SERVER / 'ops.json').write_text(json.dumps([{'uuid': str(offline), 'name': NAME, 'level': 4,
                                               'bypassesPlayerLimit': False}]))
server_log = open(HERE / ('server-' + mode + '.log'), 'w', encoding='utf-8', errors='replace')
server = subprocess.Popen([str(JAVA), '-Xmx2G', '-jar', 'forge-1.16.5-36.2.42.jar', 'nogui'], cwd=str(SERVER),
                          stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
start = time.time()
while True:
    line = server.stdout.readline().decode('utf-8', 'replace')
    if not line:
        sys.exit('server exited early, see server-%s.log' % mode)
    server_log.write(line)
    if 'Done (' in line:
        break
    if time.time() - start > 600:
        server.kill()
        sys.exit('server did not start')
print('server up in %.0fs' % (time.time() - start))

import threading
def pump():
    for raw in iter(server.stdout.readline, b''):
        server_log.write(raw.decode('utf-8', 'replace'))
        server_log.flush()
threading.Thread(target=pump, daemon=True).start()

# ---------------------------------------------------------------- client
game = HERE / ('run-net' + mode)
if game.exists():
    shutil.rmtree(game)
(game / 'mods').mkdir(parents=True)
shutil.copy(MOD_JAR, game / 'mods')
shutil.copy(TEST_JAR, game / 'mods')
if mode == 'apoth':
    for jar in APOTH_JARS:
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
        native = downloads.get('classifiers', {}).get('natives-windows')
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
        '-Denchcracker.selftest.mode=net' + mode, '-Denchcracker.selftest.port=%d' % PORT,
        '-cp', ';'.join(classpath), 'cpw.mods.modlauncher.Launcher',
        '--username', NAME, '--version', 'forge-36.2.42', '--gameDir', str(game),
        '--assetsDir', str(I / 'assets'), '--assetIndex', '1.16', '--uuid', offline.hex,
        '--accessToken', '0', '--userType', 'legacy', '--versionType', 'release',
        '--width', '1280', '--height', '720',
        '--launchTarget', 'fmlclient', '--fml.forgeVersion', '36.2.42', '--fml.mcVersion', '1.16.5',
        '--fml.forgeGroup', 'net.minecraftforge', '--fml.mcpVersion', '20210115.111550']
print('launching client', mode, 'with', MOD_JAR)
try:
    with open(game / 'launch-output.txt', 'w', encoding='utf-8', errors='replace') as out:
        proc = subprocess.run(args, cwd=str(game), stdout=out, stderr=subprocess.STDOUT, timeout=1800)
    print('client exit', proc.returncode)
finally:
    try:
        server.stdin.write(b'stop\n')
        server.stdin.flush()
        server.wait(timeout=60)
    except Exception:
        server.kill()
    server_log.close()
report = game / ('selftest-report-net-' + mode + '.txt')
print(report.read_text() if report.exists() else 'no report written')
