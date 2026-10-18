#!/usr/bin/env python3
"""Debloat reversivel: snapshot, apply e restore. Nao desinstala apps."""
import argparse
import json
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from firetv.adb import Device, ROOT, OPS, lines, save as save_json, load as load_json
from firetv.planning import select_packages, can_restrict_uid

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('action',choices=['snapshot','apply','restore'])
parser.add_argument('--device',default=os.getenv('ADB_SERIAL'))
parser.add_argument('--state',type=Path,default=ROOT/'.local/debloat')
parser.add_argument('--block-updates',action='store_true',help='Somente ao criar o snapshot; bloqueia OTA tambem.')
parser.add_argument('--keep',action='append',default=[],help='Pacote adicional preservado; pode repetir.')
args=parser.parse_args()
device=Device(args.device)
BACKUP=args.state/'backup.json'; LOG=args.state/'apply.json'
shell=device.shell; inventory=device.inventory; package_info=device.package_info
policy_ids=device.policy_ids
KEEP=set(lines(ROOT/'debloat/data/keep.txt')) | set(args.keep)
FILTER='dnsfilter.android'

def names(file): return [x.split()[0] for x in names_lines(file)]
def names_lines(file): return lines(ROOT/'debloat/data'/Path(file).name)
def load(): return load_json(BACKUP)
def logload():
    return load_json(LOG) if LOG.exists() else {'packages':{},'restricted':{},'settings':[],'network_added_blacklist':[],'network_added_whitelist':[]}
def save(log): save_json(LOG,log)

def snapshot():
    assert not BACKUP.exists(), 'Preservar o backup original desta etapa.'
    identity = device.preflight()
    installed = inventory()
    thirdparty = inventory('-3')
    never = set(names('data/fireos/never-disable.txt'))
    # The actual shopping UI is confirmed in this device's launcher inventory.
    candidates = set(names('data/fireos/packages.txt'))
    update_packages = set(names('data/fireos/updates.txt'))
    if args.block_updates: candidates |= update_packages
    selected = select_packages(candidates, installed, KEEP, never, thirdparty)
    protected = select_packages(names('data/fireos/protected.txt'), installed, KEEP, never, thirdparty)
    settings = []
    current = {}
    for ns in ['secure', 'global']:
        current[ns] = dict(line.split('=', 1) for line in shell('settings', 'list', ns).splitlines() if '=' in line)
    for line in names_lines('data/fireos/settings.txt'):
        ns, key, wanted = line.split()
        settings.append({'namespace': ns, 'key': key, 'before': current[ns].get(key), 'wanted': wanted})
    uid_map = {}
    for package, uid in re.findall(r'^package:([^\s]+)\s+uid:(\d+)', shell('pm', 'list', 'packages', '-U'), re.M):
        uid_map.setdefault(uid, []).append(package)
    if args.block_updates: protected = sorted(set(protected) | (update_packages & set(installed) - never - KEEP - set(thirdparty)))
    all_targets = sorted(set(selected) | set(protected))
    if any(p in {'tv.dumbtv.home','com.amazon.tv.launcher'} for p in all_targets):
        raise RuntimeError('Launcher entrou no plano; nada aplicado.')
    states = {package: package_info(package) for package in all_targets}
    keep_names = sorted(set(thirdparty) | KEEP | {'com.amazon.venezia', 'com.amazon.tv.launcher', 'com.amazon.vizzini'})
    kept = {package: package_info(package) for package in keep_names if package in installed}
    data = {
        'created_at': datetime.now(timezone.utc).isoformat(), **identity,
        'block_updates_authorized': args.block_updates, 'selected': selected, 'protected': protected,
        'states': states, 'kept': kept, 'all_packages_before': installed, 'third_party_before': thirdparty,
        'disabled_before': inventory('-d'), 'uid_map': uid_map, 'settings': settings,
        'restrict_background_before': 'enabled' in shell('cmd', 'netpolicy', 'get', 'restrict-background'),
        'blacklist_before': policy_ids('blacklist'), 'whitelist_before': policy_ids('whitelist'),
    }
    save_json(BACKUP, data, exclusive=True)
    print(f'Plano e backup salvos: {len(selected)} alvos de limpeza, {len(protected)} protegidos e {len(kept)} apps/componentes preservados.', flush=True)

def apply_packages():
    data = load()
    device.verify_backup(data)
    if inventory() != data['all_packages_before']:
        raise RuntimeError('Inventario mudou. Revise antes de aplicar.')
    log = logload()
    for package in data['selected']:
        if log['packages'].get(package, {}).get('status') in {'disabled', 'already_disabled', 'protected_skipped'}:
            continue
        before = data['states'][package]
        if before['enabled'] in (2, 3, 4):
            log['packages'][package] = {'status': 'already_disabled'}
            save(log)
            continue
        result = shell('pm', 'disable-user', '--user', '0', package, check=False)
        if 'disabled-user' in result:
            status = 'disabled'
        elif 'protected' in result.lower():
            status = 'protected_skipped'
        else:
            log['packages'][package] = {'status': 'unexpected', 'result': result}
            save(log)
            raise RuntimeError('Limpeza interrompida: ' + package + ': ' + result)
        log['packages'][package] = {'status': status, 'result': result}
        save(log)
        print(status, package, flush=True)
    for item in data['settings']:
        shell('settings', 'put', item['namespace'], item['key'], item['wanted'])
        log['settings'].append(item['key'])
        save(log)
    # Apply background restrictions only when a UID cannot affect kept/system apps.
    target_set = set(data['states'])
    for package, state in data['states'].items():
        uid = state['uid']
        sharing = set(data['uid_map'].get(str(uid), []))
        if not can_restrict_uid(uid, sharing, target_set):
            log['restricted'][package] = {'status': 'shared_uid_skipped', 'uid': uid}
            save(log)
            continue
        for op in OPS:
            shell('appops', 'set', package, op, 'ignore')
        shell('am', 'force-stop', package, check=False)
        if uid not in data['blacklist_before'] and uid not in log['network_added_blacklist']:
            shell('cmd', 'netpolicy', 'add', 'restrict-background-blacklist', str(uid))
            log['network_added_blacklist'].append(uid)
        log['restricted'][package] = {'status': 'restricted', 'uid': uid}
        save(log)
    for package, state in data['kept'].items():
        uid = state['uid']
        if uid >= 10000 and uid not in data['whitelist_before'] and uid not in log['network_added_whitelist']:
            shell('cmd', 'netpolicy', 'add', 'restrict-background-whitelist', str(uid))
            log['network_added_whitelist'].append(uid)
            save(log)
    shell('cmd', 'netpolicy', 'set', 'restrict-background', 'true')
    log['restrict_background_enabled'] = True
    save(log)
    print('Limpeza adicional e restricoes aplicadas. Apps preservados com excecoes para uso de rede em segundo plano.', flush=True)


def restore():
    data=load(); device.verify_backup(data)
    commands={0:'default-state',1:'enable',2:'disable',3:'disable-user',4:'disable-until-used'}
    for package,state in data['states'].items():
        current=package_info(package)
        # Never apply a saved UID policy to a reinstalled app with a new UID.
        if current['uid'] != state['uid']: raise RuntimeError('UID mudou: '+package)
    for package,state in data['states'].items():
        if package_info(package)['enabled'] != state['enabled']:
            shell('pm',commands[state['enabled']],'--user','0',package)
        if package_info(package)['enabled'] != state['enabled']:
            raise RuntimeError('Estado nao restaurado: '+package)
        for op,value in state['appops'].items(): shell('appops','set',package,op,value)
    log=logload()
    for kind in ['blacklist','whitelist']:
        original=set(data[kind+'_before']); current=set(policy_ids(kind))
        for uid in set(log['network_added_'+kind]) & (current-original):
            shell('cmd','netpolicy','remove','restrict-background-'+kind,str(uid))
    shell('cmd','netpolicy','set','restrict-background',str(data['restrict_background_before']).lower())
    for item in data['settings']:
        device.restore_setting(item['namespace'],item['key'],item['before'])
    print('Debloat restaurado; DNS e launcher mantidos. Reinicie a TV.')

if __name__=='__main__':
    {'snapshot':snapshot,'apply':apply_packages,'restore':restore}[args.action]()
