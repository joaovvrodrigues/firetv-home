import tempfile
import unittest
from pathlib import Path
from firetv.adb import Device, save
from firetv.planning import select_packages, can_restrict_uid

class SafetyTests(unittest.TestCase):
    def test_preserves_apps_and_core_even_when_in_candidates(self):
        candidates={'prime','youtube','settings','ads','missing'}
        self.assertEqual(select_packages(candidates,{'prime','youtube','settings','ads'}, {'prime'}, {'settings'}, {'youtube'}),['ads'])

    def test_shared_and_system_uids_are_not_restricted(self):
        self.assertFalse(can_restrict_uid(1000,{'ads'},{'ads'}))
        self.assertFalse(can_restrict_uid(10001,{'ads','prime'},{'ads'}))
        self.assertFalse(can_restrict_uid(10001,set(),{'ads'}))
        self.assertTrue(can_restrict_uid(10001,{'ads','metrics'},{'ads','metrics'}))

    def test_restores_absent_settings_with_delete(self):
        device=Device('example'); calls=[]
        device.shell=lambda *args: calls.append(args)
        device.restore_setting('global','flag',None)
        device.restore_setting('global','flag','0')
        self.assertEqual(calls,[('settings','delete','global','flag'),('settings','put','global','flag','0')])

    def test_original_backup_cannot_be_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'backup.json'
            save(path,{'original':True},exclusive=True)
            with self.assertRaises(FileExistsError): save(path,{'original':False},exclusive=True)
            self.assertIn('true',path.read_text())

if __name__=='__main__': unittest.main()

class SnapshotTests(unittest.TestCase):
    def test_plan_uses_device_inventory_and_ota_is_opt_in(self):
        import json
        import runpy
        import sys
        from unittest.mock import patch
        from firetv.adb import ROOT
        installed=['com.amazon.tv.acr','com.amazon.firebat','com.amazon.tv.forcedotaupdater.v2','com.exemplo.player']
        class FakeDevice:
            def __init__(self,serial): pass
            def preflight(self): return {'device':'example','serial':'example','model':'AFTSS'}
            def inventory(self,flag=None):
                if flag=='-3': return ['com.exemplo.player']
                if flag=='-d': return []
                return sorted(installed)
            def package_info(self,package): return {'installed':True,'enabled':0,'uid':10001+installed.index(package),'appops':{}}
            def policy_ids(self,kind): return []
            def shell(self,*args,**kwargs):
                if args==('pm','list','packages','-U'):
                    return '\n'.join('package:'+p+' uid:'+str(10001+i) for i,p in enumerate(installed))
                return ''
        for block_updates in [False,True]:
            with tempfile.TemporaryDirectory() as directory:
                argv=['manage.py','snapshot','--device','example','--state',directory]+(['--block-updates'] if block_updates else [])
                with patch.object(sys,'argv',argv), patch('firetv.adb.Device',FakeDevice):
                    runpy.run_path(str(ROOT/'debloat/manage.py'),run_name='__main__')
                data=json.loads((Path(directory)/'backup.json').read_text())
                self.assertIn('com.amazon.tv.acr',data['selected'])
                self.assertNotIn('com.amazon.firebat',data['selected'])
                self.assertNotIn('com.exemplo.player',data['selected'])
                self.assertEqual('com.amazon.tv.forcedotaupdater.v2' in data['states'],block_updates)
