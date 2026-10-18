"""ADB explícito, preflight e snapshots locais; nenhuma descoberta de IP."""
import json
import os
import re
import shlex
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OPS = ['RUN_IN_BACKGROUND', 'RUN_ANY_IN_BACKGROUND']

class Device:
    def __init__(self, serial):
        if not serial or serial.startswith('-'):
            raise ValueError('Informe --device ou ADB_SERIAL.')
        self.serial = serial
        self.binary = os.environ.get('ADB', 'adb')

    def adb(self, *args, check=True, timeout=60):
        p = subprocess.run([self.binary, '-s', self.serial, *map(str,args)],
                           capture_output=True, text=True, timeout=timeout)
        if check and p.returncode:
            raise RuntimeError(p.stdout + p.stderr)
        return (p.stdout + (p.stderr if p.returncode else '')).strip().replace('\r','')

    def shell(self, *args, check=True):
        return self.adb('shell', shlex.join([str(a) for a in args]), check=check)

    def preflight(self):
        if self.adb('get-state') != 'device':
            raise RuntimeError('ADB indisponivel ou nao autorizado.')
        if self.shell('getprop','ro.product.manufacturer').lower() != 'amazon':
            raise RuntimeError('Este perfil exige aparelho Amazon.')
        model=self.shell('getprop','ro.product.model')
        if model != 'AFTSS' or self.shell('getprop','ro.build.version.sdk') != '28':
            raise RuntimeError('Perfil validado apenas para AFTSS / Fire OS 7 / API 28.')
        return {'device':self.serial, 'model':model,
                'serial':self.shell('getprop','ro.serialno')}

    def verify_backup(self, data):
        current=self.preflight()
        if any(current[k] != data[k] for k in current):
            raise RuntimeError('Backup pertence a outro aparelho/conexao.')

    def inventory(self, flag=None):
        return sorted(x[8:] for x in self.shell('pm','list','packages',*([flag] if flag else [])).splitlines() if x.startswith('package:'))

    def package_info(self, package):
        output=self.shell('dumpsys','package',package)
        state=re.search(r'User 0:.*?installed=(\w+).*?enabled=(\d+)',output)
        uid=re.search(r'\buserId=(\d+)',output)
        if not state or not uid:
            raise RuntimeError('Estado nao identificado: '+package)
        modes={}
        for op in OPS:
            match=re.search(re.escape(op)+r':\s*(allow|ignore|deny|default|foreground|errored)',self.shell('appops','get',package,op,check=False))
            modes[op]=match[1] if match else 'default'
        return {'installed':state[1]=='true','enabled':int(state[2]),'uid':int(uid[1]),'appops':modes}

    def setting(self, namespace, key):
        values=dict(line.split('=',1) for line in self.shell('settings','list',namespace).splitlines() if '=' in line)
        return values.get(key)

    def restore_setting(self, namespace,key,value):
        if value is None: self.shell('settings','delete',namespace,key)
        else: self.shell('settings','put',namespace,key,value)

    def policy_ids(self, kind):
        return sorted(int(x) for x in re.findall(r'\b\d+\b', self.shell('cmd','netpolicy','list','restrict-background-'+kind)))

def lines(path):
    return [x.split('#')[0].strip() for x in Path(path).read_text().splitlines() if x.split('#')[0].strip()]

def save(path,data,exclusive=False):
    path=Path(path); path.parent.mkdir(parents=True,exist_ok=True)
    if exclusive:
        with path.open('x',encoding='utf-8') as f: json.dump(data,f,ensure_ascii=False,indent=2)
    else:
        tmp=path.with_suffix(path.suffix+'.tmp')
        tmp.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        tmp.replace(path)

def load(path): return json.loads(Path(path).read_text())
