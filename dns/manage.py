#!/usr/bin/env python3
"""Filtro DNS local independente; requer reinicializacao ao instalar/restaurar."""
import argparse
import hashlib
import ipaddress
import os
import re
import sys
import time
import urllib.request
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from firetv.adb import Device, ROOT, lines, save as save_json, load as load_json

APK_URL='https://f-droid.org/repo/dnsfilter.android_1506103.apk'
CONFIG_URL='https://raw.githubusercontent.com/IngoZenz/personaldnsfilter/master/app/src/main/assets/dnsfilter.conf'
HOSTS_URL='https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/native.amazon-onlydomains.txt'
FILTER='dnsfilter.android'
FILTER_DIR='/sdcard/Android/data/dnsfilter.android/files/PersonalDNSFilter'
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('action',choices=['install','restore','verify'])
parser.add_argument('--device',default=os.getenv('ADB_SERIAL'))
parser.add_argument('--state',type=Path,default=ROOT/'.local/dns')
parser.add_argument('--block-updates',action='store_true',help='Inclui os dominios OTA; reduz protecao por atualizacoes.')
args=parser.parse_args()
device=Device(args.device); adb=device.adb; shell=device.shell; package_info=device.package_info
STAGE=args.state/'files'; BACKUP=args.state/'backup.json'

def download(url,path):
    with urllib.request.urlopen(url,timeout=60) as response: Path(path).write_bytes(response.read())
def save(log): save_json(args.state/'apply.json',log)

def prepare_dns():
    STAGE.mkdir(parents=True,exist_ok=True)
    download(APK_URL, STAGE/'personaldnsfilter.apk')
    download(CONFIG_URL, STAGE/'dnsfilter-upstream.conf')
    download(HOSTS_URL, STAGE/'hagezi-amazon.txt')
    apk = STAGE / 'personaldnsfilter.apk'
    assert hashlib.sha256(apk.read_bytes()).hexdigest() == '3ddd0db322d1ca5fe950ea29dc1788af6e6581bb5f34694085fb4aa2e77b68ad'
    route = shell('ip', 'route', 'show', 'table', 'all')
    gateway = next((re.search(r'\bvia\s+(\d+\.\d+\.\d+\.\d+)', line)[1] for line in route.splitlines() if line.startswith('default') and ' via ' in line and 'tun' not in line and 'linkdown' not in line), None)
    assert gateway, 'Gateway nao identificado'
    domains = set()
    for file in [STAGE / 'hagezi-amazon.txt', ROOT / 'dns/data/extra-hosts.txt']:
        for line in file.read_text().splitlines():
            host = line.split('#')[0].strip().lower()
            if not host or 'aiv-delivery' in host or 'aiv-cdn' in host:
                continue
            assert re.fullmatch(r'[a-z0-9_.\-*]+', host), host
            domains.add(host)
    for host in lines(ROOT/'dns/data/verify-allowed.txt'):
        assert not any(host == d or host.endswith('.' + d) for d in domains), host
    (STAGE / 'FILTERHOSTS.TXT').write_text('\n'.join(sorted(domains)) + '\n')
    replacements = {
        'filterActive': 'true', 'detectDNS': 'false', 'fallbackDNS': f'#router; [{gateway}]::53::UDP',
        'routeIPs': gateway, 'routeUnderlyingDNS': 'true', 'showInitialInfoPopUp': 'false',
        'reloadIntervalDays': '3650', 'filterHostsFile': 'FILTERHOSTS.TXT',
        'previousAutoUpdateURL': '',
    }
    config = (STAGE / 'dnsfilter-upstream.conf').read_text()
    for key, value in replacements.items():
        config, count = re.subn(r'^' + re.escape(key) + r'\s*=.*$', key + ' = ' + value, config, flags=re.M)
        assert count == 1, key
    config, count = re.subn(r'^filterAutoUpdateURL\s*=.*$', 'filterAutoUpdateURL =', config, flags=re.M)
    assert count == 1
    (STAGE / 'dnsfilter.conf').write_text(config)
    print(f'Filtro preparado: {len(domains)} dominios; CDN do Prime preservada; atualizacoes na lista de bloqueio.', flush=True)

def install_dns():
    identity=device.preflight()
    if BACKUP.exists(): raise RuntimeError('Backup DNS ja existe; restaure antes de reaplicar.')
    if FILTER in device.inventory(): raise RuntimeError('Filtro existente exige backup manual. Nada alterado.')
    vpn=device.setting('secure','always_on_vpn_app')
    if vpn not in (None,'','null'): raise RuntimeError('Outra VPN configurada; nao substituir.')
    data={**identity,'vpn_before':vpn,'whitelist_before':device.policy_ids('whitelist'),
          'idle_before':FILTER in shell('dumpsys','deviceidle','whitelist')}
    save_json(BACKUP,data,exclusive=True)
    log={'network_added_whitelist':[]}
    result = adb('install', '-r', str(STAGE / 'personaldnsfilter.apk'), timeout=90)
    assert 'Success' in result, result
    log['filter_installed'] = True
    save(log)
    shell('appops', 'set', FILTER, 'ACTIVATE_VPN', 'allow')
    shell('dumpsys', 'deviceidle', 'whitelist', '+' + FILTER)
    state = package_info(FILTER)
    log['filter_uid'] = state['uid']
    if state['uid'] not in data['whitelist_before']:
        shell('cmd', 'netpolicy', 'add', 'restrict-background-whitelist', str(state['uid']))
        log['network_added_whitelist'].append(state['uid'])
    save(log)
    shell('am', 'start', '-n', FILTER + '/.DNSProxyActivity')
    for _ in range(6):
        if 'yes' in shell('sh', '-c', f'test -d {FILTER_DIR} && echo yes', check=False):
            break
        time.sleep(2)
    else:
        raise RuntimeError('Pasta do filtro nao foi criada.')
    # This force-stop is only during initial setup; the required reboot follows.
    shell('am', 'force-stop', FILTER)
    time.sleep(2)
    adb('push', str(STAGE / 'dnsfilter.conf'), FILTER_DIR + '/dnsfilter.conf')
    adb('push', str(STAGE / 'FILTERHOSTS.TXT'), FILTER_DIR + '/FILTERHOSTS.TXT')
    ensure_local_filter_metadata()
    shell('settings', 'put', 'secure', 'always_on_vpn_app', FILTER)
    log['filter_configured'] = True
    save(log)
    print('Configuracao salva. Reiniciando para ativar o caminho DNS.',flush=True)
    adb('reboot')
    print('Filtro instalado. Aguarde a TV iniciar e execute verify.', flush=True)

def ensure_local_filter_metadata():
    # An empty reload URL still starts AutoFilterUpdater. Missing download metadata
    # or a previous URL of <none> makes it replace the manual file with a header.
    count = len((STAGE / 'FILTERHOSTS.TXT').read_text().splitlines())
    modified = int(shell('stat', '-c', '%Y', FILTER_DIR + '/FILTERHOSTS.TXT')) * 1000
    file = STAGE / 'FILTERHOSTS.TXT.DLD_CNT'
    file.write_text(f'{count}\n{modified}\n')
    adb('push', str(file), FILTER_DIR + '/FILTERHOSTS.TXT.DLD_CNT')
    shell('rm', '-rf', FILTER_DIR + '/FILTERHOSTS.TXT.idx', FILTER_DIR + '/IDX_OUTDATED')


def restore():
    data=load_json(BACKUP); device.verify_backup(data)
    log=load_json(args.state/'apply.json') if (args.state/'apply.json').exists() else {}
    if FILTER in device.inventory() and 'filter_uid' in log and package_info(FILTER)['uid'] != log['filter_uid']:
        raise RuntimeError('UID do filtro mudou. Revise antes de remover.')
    device.restore_setting('secure','always_on_vpn_app',data['vpn_before'])
    if FILTER in device.inventory():
        uid=package_info(FILTER)['uid']
        if uid not in data['whitelist_before'] and uid in device.policy_ids('whitelist'):
            shell('cmd','netpolicy','remove','restrict-background-whitelist',str(uid))
        if not data['idle_before']: shell('dumpsys','deviceidle','whitelist','-'+FILTER)
        result=adb('uninstall',FILTER)
        if 'Success' not in result: raise RuntimeError(result)
    adb('reboot')
    print('Filtro removido e VPN anterior restaurada. TV reiniciando.')

def resolved(host):
    result=shell('ping','-c','1','-W','2',host,check=False)
    match=re.search(r'PING [^\n]*?\(([^)]+)\)',result)
    if not match: return False
    try: address=ipaddress.ip_address(match[1])
    except ValueError: return False
    return not (address.is_loopback or address.is_unspecified)

def verify():
    device.preflight()
    if device.setting('secure','always_on_vpn_app') != FILTER: raise RuntimeError('VPN DNS nao configurada.')
    blocked=lines(ROOT/'dns/data/verify-blocked.txt')
    failures=[]
    for host,wanted in [(h,False) for h in blocked]+[(h,True) for h in lines(ROOT/'dns/data/verify-allowed.txt')]:
        okay=resolved(host)==wanted
        print('ok' if okay else 'FALHOU',host,'resolve' if wanted else 'bloqueado')
        if not okay: failures.append(host)
    if failures: raise RuntimeError('Verificacao DNS falhou. Confira rede e reinicie a TV: '+', '.join(failures))

if __name__=='__main__':
    if args.action=='install':
        if not args.block_updates: parser.error('Este perfil DNS bloqueia OTA. Informe --block-updates para aceitar.')
        device.preflight()
        if BACKUP.exists() or FILTER in device.inventory(): raise RuntimeError('Instalacao existente; revise o backup antes de reaplicar.')
        prepare_dns(); install_dns()
    elif args.action=='restore': restore()
    else: verify()
