#!/usr/bin/env python3
"""Instala um APK local e configura HOME, ou devolve HOME a Amazon."""
import argparse
import os
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from firetv.adb import Device, ROOT, save, load

PKG='tv.dumbtv.home'
GUARD=PKG+'/'+PKG+'.Guard'
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('action',choices=['install','restore'])
parser.add_argument('--device',default=os.getenv('ADB_SERIAL'))
parser.add_argument('--apk',type=Path,default=ROOT/'launcher/build/dumbtv-home.apk')
parser.add_argument('--state',type=Path,default=ROOT/'.local/launcher')
args=parser.parse_args()
d=Device(args.device); identity=d.preflight(); backup=args.state/'backup.json'
settings=[('secure','enabled_accessibility_services'),('secure','accessibility_enabled'),('global','alarm_manager_constants')]
if args.action=='install':
    if not args.apk.is_file(): raise SystemExit('Compile primeiro: ./launcher/build.sh')
    if not backup.exists():
        data={**identity,'settings':[{'namespace':ns,'key':key,'before':d.setting(ns,key)} for ns,key in settings],
              'idle_before':PKG in d.shell('dumpsys','deviceidle','whitelist')}
        save(backup,data,exclusive=True)
        if PKG in d.inventory():
            d.adb('pull','/sdcard/Android/data/'+PKG+'/files',str(args.state/'files-before'),check=False)
    else: d.verify_backup(load(backup))
    result=d.adb('install','-r',str(args.apk.resolve()),timeout=90)
    if 'Success' not in result: raise RuntimeError(result)
    # No automatic uninstall on signature mismatch: data and installed launcher survive.
    current=d.setting('secure','enabled_accessibility_services') or ''
    services=[s for s in current.split(':') if s and s!='null' and s!=GUARD]
    services.append(GUARD)
    d.shell('settings','put','secure','enabled_accessibility_services',':'.join(services))
    d.shell('settings','put','secure','accessibility_enabled','1')
    current=d.setting('global','alarm_manager_constants') or ''
    values=[s for s in current.split(',') if s and s!='null' and not s.startswith('min_futurity=')]
    values.append('min_futurity=0')
    d.shell('settings','put','global','alarm_manager_constants',','.join(values))
    d.shell('dumpsys','deviceidle','whitelist','+'+PKG)
    d.shell('am','start','-W','-n',PKG+'/.Home')
    if GUARD not in (d.setting('secure','enabled_accessibility_services') or ''):
        raise RuntimeError('Acessibilidade nao ativada. Confira as configuracoes da TV.')
    print('Launcher instalado. Teste HOME, Opcoes, Configuracoes e seus apps no controle.')
else:
    data=load(backup); d.verify_backup(data)
    for item in data['settings']: d.restore_setting(item['namespace'],item['key'],item['before'])
    if not data['idle_before']: d.shell('dumpsys','deviceidle','whitelist','-'+PKG)
    d.shell('am','start','-n','com.amazon.tv.launcher/.ui.HomeActivity')
    print('Configuracao HOME anterior restaurada. Launcher permanece instalado com seus dados.')
