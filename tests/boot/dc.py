# usage: dc.py <class/path/without/.class> ...   -> decompiles with CFR, appends "/*mcp*/" names
import sys, subprocess, csv, re, os
T = os.path.join(os.environ['LOCALAPPDATA'], 'enchcracker-build', 'tools')
CP = os.path.join(os.environ['LOCALAPPDATA'], 'enchcracker-build', 'cp-1.16.5-36.2.42')
names = {}
for f in ('methods.csv', 'fields.csv'):
    with open(os.path.join(T, 'mcp', f), newline='') as fh:
        for row in csv.DictReader(fh):
            names[row['searge']] = row['name']
java = os.path.join(T, 'jdk8x', 'jdk8u504-b01', 'bin', 'java.exe')
for cls in sys.argv[1:]:
    path = os.path.join(CP, cls.replace('/', os.sep) + '.class')
    out = subprocess.run([java, '-jar', os.path.join(T, 'cfr.jar'), path, '--extraclasspath', CP, '--silent', 'true'],
                         capture_output=True, text=True).stdout
    out = re.sub(r'\b((?:func|field)_\d+_[a-zA-Z_]+?_?)\b', lambda m: m.group(1) + '/*' + names.get(m.group(1), '?') + '*/', out)
    name = cls.split('/')[-1]
    with open(os.path.join(T, 'decomp', name + '.java'), 'w', encoding='utf-8') as fh:
        fh.write(out)
    print(name, len(out))
