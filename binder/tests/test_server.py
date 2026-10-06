import http.client
import importlib
import json
import shutil
import sys
import tempfile
import threading
import unittest
from pathlib import Path


class LocalServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        root = Path(cls.temp.name)
        source = Path(__file__).resolve().parents[1]
        (root / 'backend').mkdir()
        (root / 'data').mkdir()
        for name in ('pokemon_server.py', 'pokemon_binder.py'):
            shutil.copy2(source / 'backend' / name, root / 'backend' / name)
        for item in (source / 'data').glob('*.default'):
            shutil.copy2(item, root / 'data' / item.name)
        sys.path.insert(0, str(root / 'backend'))
        cls.module = importlib.import_module('pokemon_server')
        cls.config = {'rows': 3, 'firebase_enabled': False, 'gsheet_enabled': False,
                      'firebase_email': 'test@example.invalid', 'firebase_password': 'private-password',
                      'firebase_id_token': 'private-token', 'firebase_secret': 'private-secret'}
        cls.cards = []
        cls.module.pokemon_binder.load_config = lambda: cls.config
        cls.module.pokemon_binder.load_collection = lambda: cls.cards
        cls.module.pokemon_binder.add_card_to_csv = lambda card: cls.cards.append(card) or True
        cls.module.pokemon_binder.save_collection = lambda cards: cls.cards.__setitem__(slice(None), cards) or True
        cls.httpd = cls.module.ThreadedHTTPServer(('127.0.0.1', 0), cls.module.BinderHTTPRequestHandler)
        cls.module.PORT = cls.httpd.server_address[1]
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()
        cls.thread.join()
        sys.path.remove(str(Path(cls.temp.name) / 'backend'))
        sys.modules.pop('pokemon_server', None)
        sys.modules.pop('pokemon_binder', None)
        cls.temp.cleanup()

    def request(self, method, path, data=None, headers=None):
        conn = http.client.HTTPConnection('127.0.0.1', self.module.PORT)
        body = json.dumps(data).encode() if data is not None else None
        request_headers = {'Content-Type': 'application/json'} if data is not None else {}
        request_headers.update(headers or {})
        conn.request(method, path, body=body, headers=request_headers)
        response = conn.getresponse()
        status, content = response.status, response.read()
        conn.close()
        return status, content

    def test_local_routes_and_rejections(self):
        self.assertEqual(self.httpd.server_address[0], '127.0.0.1')
        status, content = self.request('GET', '/api/settings')
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(content)['firebase_email'], 'test@example.invalid')
        for secret in (b'private-password', b'private-token', b'private-secret'):
            self.assertNotIn(secret, content)
        self.assertEqual(self.request('GET', '/api/settings', headers={'Host': 'evil.example'})[0], 403)
        self.assertEqual(self.request('POST', '/api/add', {'name': 'pikachu'},
                                      {'Origin': 'https://evil.example'})[0], 403)
        self.assertEqual(self.request('POST', '/api/add', {'name': 'pikachu'},
                                      {'Sec-Fetch-Site': 'cross-site'})[0], 403)
        self.assertEqual(self.request('POST', '/api/add', {
            'name': 'pikachu', 'dex_id': {'id': 25, 'type': 'electric'}, 'page': 1, 'slot': 1
        })[0], 200)
        self.assertEqual(len(self.cards), 1)
        self.assertEqual(self.cards[0]['Dex Number'], 25)
        self.assertEqual(self.request('POST', '/api/remove', {'name': 'pikachu', 'page': 1, 'slot': 1})[0], 200)
        self.assertFalse(self.cards)
        self.assertEqual(self.request('POST', '/api/heartbeat')[0], 200)
        self.module.pokemon_binder.firebase_sign_in = lambda config: (True, 'ok')
        self.module.pokemon_binder.sync_with_firebase = lambda config, cards: (True, 'ok')
        self.module.pokemon_binder.push_profile_to_firebase = lambda config: None
        status, content = self.request('POST', '/api/firebase/login', {'email': 'test@example.invalid', 'password': 'private-password'})
        self.assertEqual(status, 200)
        self.assertNotIn(b'private-password', content)
        status, content = self.request('POST', '/api/settings', {'rows': 4})
        self.assertEqual(status, 200)
        self.assertNotIn(b'private-password', content)
        self.assertEqual(self.request('POST', '/api/sync')[0], 200)
        status, content = self.request('POST', '/api/upload-cover-image', {'image_data': 'ZHVtbXk='})
        self.assertEqual(status, 200)
        self.assertNotIn(b'private-password', content)
        self.assertEqual(self.request('POST', '/api/firebase/logout')[0], 200)
        self.assertEqual(self.request('POST', '/api/shutdown')[0], 200)


if __name__ == '__main__':
    unittest.main()
