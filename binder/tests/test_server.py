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
        sys.modules.pop('pokemon_server', None)
        sys.modules.pop('pokemon_binder', None)
        sys.path.insert(0, str(root / 'backend'))
        cls.module = importlib.import_module('pokemon_server')
        cls.module.DATA_DIR = str(root / 'data')
        cls.module.pokemon_binder.DATA_DIR = str(root / 'data')
        cls.module.pokemon_binder.CONFIG_FILE = str(root / 'data' / 'binder_config.json')
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
        self.assertEqual(status, 200, content)
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
        self.assertEqual(status, 200, content)
        self.assertNotIn(b'private-password', content)
        self.assertEqual(self.request('POST', '/api/firebase/logout')[0], 200)
        self.assertEqual(self.request('POST', '/api/shutdown')[0], 200)

    def test_market_script_is_served(self):
        source = Path(__file__).resolve().parents[1] / 'frontend'
        previous = self.module.FRONTEND_DIR
        self.module.FRONTEND_DIR = str(source)
        try:
            status, content = self.request('GET', '/market.js')
            self.assertEqual(status, 200)
            self.assertIn(b'class MarketClient', content)
        finally:
            self.module.FRONTEND_DIR = previous

    def test_add_preserves_printed_name_and_locale_identity(self):
        self.cards.clear()
        self.addCleanup(self.cards.clear)
        status, content = self.request('POST', '/api/add', {
            'name': 'Hisuian Zorua', 'species_name': 'Zorua', 'form': 'Hisuian',
            'dex_id': 570, 'card_language': 'ja', 'catalog_provider': 'TCGdex',
            'catalog_locale': 'ja', 'catalog_card_id': 'sv10-1',
            'set_id': 'sv10', 'set_name': 'Black Bolt', 'card_number': '1',
            'catalog_variant': 'normal', 'type': 'Mega Hyper Rare',
            'page': 64, 'slot': 3, 'condition': 'NM', 'notes': 'fixture',
        })
        self.assertEqual(status, 200, content)
        saved = json.loads(content)['card']
        self.assertEqual('Hisuian Zorua', saved['Name'])
        self.assertEqual(570, saved['Dex Number'])
        self.assertEqual('Zorua', saved['Species Name'])
        self.assertEqual('Hisuian', saved['Form'])
        self.assertEqual('ja', saved['Card Language'])
        self.assertEqual('TCGdex', saved['Catalog Provider'])
        self.assertEqual('ja', saved['Catalog Locale'])
        self.assertEqual('sv10-1', saved['Catalog Card ID'])
        self.assertEqual('normal', saved['Catalog Variant'])
        self.assertEqual('Mega Hyper Rare', saved['Type'])

    def test_identity_edit_clears_the_previous_printing_and_art(self):
        self.cards.clear()
        self.addCleanup(self.cards.clear)
        status, content = self.request('POST', '/api/add', {
            'name': 'Hisuian Zorua', 'species_name': 'Zorua', 'form': 'Hisuian',
            'dex_id': 570, 'card_language': 'en', 'catalog_provider': 'TCGdex',
            'catalog_locale': 'en', 'catalog_card_id': 'swsh11-147', 'set_id': 'swsh11',
            'set_name': 'Lost Origin', 'card_number': '147', 'catalog_variant': 'normal',
            'catalog_rarity': 'Rare', 'image_url': 'https://assets.tcgdex.net/en/swsh/swsh11/147/low.webp',
            'page': 64, 'slot': 3, 'condition': 'NM', 'notes': 'keep me',
        })
        self.assertEqual(200, status, content)
        original = json.loads(content)['card']

        status, content = self.request('POST', '/api/update-card', {
            'copy_id': original['Copy ID'], 'name': 'Alolan Raichu', 'species_name': 'Raichu',
            'form': 'Alolan', 'dex_id': 26, 'card_language': 'ja',
        })
        self.assertEqual(200, status, content)
        saved = json.loads(content)['card']

        self.assertEqual('Alolan Raichu', saved['Name'])
        self.assertEqual('Raichu', saved['Species Name'])
        self.assertEqual('Alolan', saved['Form'])
        self.assertEqual('ja', saved['Card Language'])
        self.assertEqual(original['Copy ID'], saved['Copy ID'])
        self.assertEqual('keep me', saved['Notes'])
        for field in ('Catalog Provider', 'Catalog Locale', 'Catalog Card ID', 'Set ID', 'Set Name',
                      'Card Number', 'Catalog Variant', 'Catalog Rarity', 'Image URL', 'Image Source', 'Image Match ID'):
            self.assertEqual('', saved.get(field, ''), field)

    def test_non_english_catalog_does_not_use_english_scrydex_fallback(self):
        calls = []
        original = self.module.resolve_scrydex_image
        self.module.resolve_scrydex_image = lambda *args, **kwargs: calls.append((args, kwargs)) or {'card_id': 'english-id'}
        try:
            status, content = self.request('POST', '/api/catalog-image-fallback', {
                'tcgdex_card_id': 'same-id', 'catalog_provider': 'TCGdex', 'catalog_locale': 'ja'
            })
        finally:
            self.module.resolve_scrydex_image = original
        self.assertEqual(status, 200, content)
        self.assertEqual([], calls)
        self.assertIsNone(json.loads(content)['match'])

    def test_catalog_card_id_requires_its_provider_and_locale(self):
        self.cards.clear()
        self.addCleanup(self.cards.clear)
        status, content = self.request('POST', '/api/add', {
            'name': 'Pikachu', 'dex_id': 25, 'catalog_card_id': 'same-id', 'set_id': 'set',
            'set_name': 'Set', 'card_number': '1', 'page': 1, 'slot': 1
        })
        self.assertEqual(400, status, content)
        self.assertFalse(self.cards)

    def test_manual_entry_keeps_printed_set_and_number_without_catalog(self):
        self.cards.clear()
        self.addCleanup(self.cards.clear)
        status, content = self.request('POST', '/api/add', {
            'name': 'リザードン', 'species_name': 'Charizard', 'card_language': 'Japanese',
            'set_name': 'Starter Deck', 'card_number': '3', 'catalog_rarity': 'Rare',
            'page': 1, 'slot': 1,
        })
        self.assertEqual(200, status, content)
        saved = json.loads(content)['card']
        self.assertEqual('', saved['Catalog Card ID'])
        self.assertEqual('Japanese', saved['Card Language'])
        self.assertEqual('Starter Deck', saved['Set Name'])
        self.assertEqual('3', saved['Card Number'])
        self.assertEqual('Rare', saved['Catalog Rarity'])


if __name__ == '__main__':
    unittest.main()
