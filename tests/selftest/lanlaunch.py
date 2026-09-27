"""LAN self test, the way a guest on a friend's LAN world plays: one client hosts a singleplayer world
opened to LAN, a second client joins it over TCP and runs NetTest against it.

Usage: lanlaunch.py vanilla|apoth <enchcrackertest.jar>

Both clients use CurseForge's Java 8 and libraries, offline accounts ("Host" and "SelfTest") and
their own game folders. With `apoth`, Apotheosis and Placebo from the DDSS2 pack go on both. The
guest's report lands in run-languest-<mode>/selftest-report-net-<mode>.txt.
"""
import glob, hashlib, json, os, pathlib, shutil, subprocess, sys, time, uuid, zipfile

mode = sys.argv[1]
TEST_JAR = pathlib.Path(sys.argv[2])
HERE = pathlib.Path(__file__).resolve().parent
MOD_JAR = os.environ.get('ENCH_MOD_JAR') or sorted(
    glob.glob(str(HERE.parents[2] / 'output' / 'enchcracker-*-forge-1.16.5.jar')), key=os.path.getmtime)[-1]
I = pathlib.Path(os.environ['USERPROFILE']) / 'curseforge/minecraft/Install'
L = I / 'libraries'
PACK_MODS = pathlib.Path(os.environ['USERPROFILE']) / 'curseforge/minecraft/Instances/Dungeons Dragons and Space Shuttles 2/mods'
JAVA = I / 'runtime/jre-legacy/windows-x64/jre-legacy/bin/java.exe'
PORT = 25601
APOTH_JARS = ('Apotheosis-1.16.5-4.8.9A0.jar', 'Placebo-1.16.5-4.7.1.jar')


def classpath():
    cp = []
    natives = HERE / 'natives'
    natives.mkdir(exist_ok=True)
    for version in ('forge-36.2.42/forge-36.2.42.json', '1.16.5/1.16.5.json'):
        data = json.load(open(I / 'versions' / version))
        for lib in data.get('libraries', []):
            downloads = lib.get('downloads', {})
            art = downloads.get('artifact')
            if art and art.get('path'):
                path = L / art['path']
                if path.exists() and str(path) not in cp:
                    cp.append(str(path))
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
    cp.append(str(I / 'versions/1.16.5/1.16.5.jar'))
    return cp, natives


def game_dir(name):
    game = HERE / name
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
        'fullscreen:false', 'overrideWidth:960', 'overrideHeight:540']) + '\n')
    return game


def launch(game, user, test_mode, out_name):
    cp, natives = classpath()
    offline = uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + user).encode()).digest(), version=3)
    args = [str(JAVA), '-Xmx3G', '-Djava.library.path=' + str(natives),
            '-Denchcracker.selftest.mode=' + test_mode, '-Denchcracker.selftest.port=%d' % PORT,
            '-Denchcracker.selftest.quick=' + os.environ.get('QUICK', 'false'),
            '-cp', ';'.join(cp), 'cpw.mods.modlauncher.Launcher',
            '--username', user, '--version', 'forge-36.2.42', '--gameDir', str(game),
            '--assetsDir', str(I / 'assets'), '--assetIndex', '1.16', '--uuid', offline.hex,
            '--accessToken', '0', '--userType', 'legacy', '--versionType', 'release',
            '--width', '960', '--height', '540',
            '--launchTarget', 'fmlclient', '--fml.forgeVersion', '36.2.42', '--fml.mcVersion', '1.16.5',
            '--fml.forgeGroup', 'net.minecraftforge', '--fml.mcpVersion', '20210115.111550']
    out = open(game / out_name, 'w', encoding='utf-8', errors='replace')
    return subprocess.Popen(args, cwd=str(game), stdout=out, stderr=subprocess.STDOUT), out


host_game = game_dir('run-lanhost-' + mode)
guest_game = game_dir('run-languest-' + mode)
# A wrong remembered XP seed for this server, as an old version could leave behind.
(guest_game / 'config' / 'enchcracker' / 'worlds').mkdir(parents=True)
(guest_game / 'config' / 'enchcracker' / 'worlds' / ('mp_127.0.0.1_%d.properties' % PORT)).write_text(
    'xpSeed=12340000\nitem=diamond_sword\nmaxShelves=15\nwishlist=\n')

print('launching LAN host', mode, 'with', MOD_JAR)
host, host_out = launch(host_game, 'Host', 'lanhost', 'launch-output.txt')
start = time.time()
while not (host_game / 'lan-ready').exists():
    if host.poll() is not None:
        sys.exit('host exited early, see ' + str(host_game / 'launch-output.txt'))
    if time.time() - start > 600:
        host.kill()
        sys.exit('host did not open to LAN')
    time.sleep(2)
print('host open to LAN after %.0fs; launching guest' % (time.time() - start))
try:
    guest, guest_out = launch(guest_game, 'SelfTest', 'net' + mode, 'launch-output.txt')
    guest.wait(timeout=4800)
    guest_out.close()
    print('guest exit', guest.returncode)
finally:
    (host_game / 'lan-stop').touch()
    try:
        host.wait(timeout=120)
    except Exception:
        host.kill()
    host_out.close()
report = guest_game / ('selftest-report-net-' + mode + '.txt')
print(report.read_text() if report.exists() else 'no guest report written')
