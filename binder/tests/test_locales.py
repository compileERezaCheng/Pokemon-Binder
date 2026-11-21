from unittest.mock import patch

from test_regressions import TemporaryBinderTest, card, pokemon_binder


class CardIdentitySchemaTests(TemporaryBinderTest):
    def test_csv_round_trip_preserves_print_language_form_and_catalog_context(self):
        identity = {
            "Species Name": "Zorua",
            "Form": "Hisuian",
            "Card Language": "ja",
            "Catalog Provider": "TCGdex",
            "Catalog Locale": "ja",
            "Catalog Variant": "normal",
            "Catalog Card ID": "sv10-1",
            "Set ID": "sv10",
            "Set Name": "Black Bolt",
            "Card Number": "1",
            "Catalog Rarity": "Common",
            "Image URL": "https://assets.tcgdex.net/ja/sv/sv10/1/low.webp",
            "Image Source": "TCGdex",
            "Copy ID": "copy-ja-1",
        }
        self.assertTrue(pokemon_binder.add_card_to_csv({
            **card("ヒスイゾロア", dex=570, rarity="Mega Hyper Rare"), **identity
        }))

        saved = pokemon_binder.load_collection()[0]
        for field, value in identity.items():
            self.assertEqual(value, saved.get(field), field)
        self.assertEqual("ヒスイゾロア", saved["Name"])
        self.assertEqual("Mega Hyper Rare", saved["Type"])
        self.assertTrue(pokemon_binder.save_collection(pokemon_binder.load_collection()))
        self.assertEqual(["copy-ja-1"], [item["Copy ID"] for item in pokemon_binder.load_collection()])

    def test_google_sheets_export_preserves_printing_identity_columns(self):
        worksheet = type("Worksheet", (), {
            "col_count": len(pokemon_binder.COLLECTION_FIELDS),
            "rows": [],
            "clear": lambda self: self.rows.clear(),
            "append_row": lambda self, row: self.rows.append(row),
            "append_rows": lambda self, rows: self.rows.extend(rows),
        })()
        spreadsheet = type("Spreadsheet", (), {"worksheet": lambda self, name: worksheet})()
        client = type("Client", (), {"open": lambda self, name: spreadsheet})()
        saved = {
            **card("ヒスイゾロア", dex=570),
            "Copy ID": "copy-ja-1",
            "Species Name": "Zorua",
            "Form": "Hisuian",
            "Card Language": "ja",
            "Catalog Provider": "TCGdex",
            "Catalog Locale": "ja",
            "Catalog Card ID": "sv10-1",
            "Set ID": "sv10",
            "Set Name": "Black Bolt",
            "Card Number": "1",
            "Catalog Variant": "normal",
            "Catalog Rarity": "Common",
            "Image URL": "https://assets.tcgdex.net/ja/sv/sv10/1/low.webp",
            "Image Source": "TCGdex",
        }

        with patch.object(pokemon_binder, "get_gspread_client", return_value=(client, None)):
            ok, message = pokemon_binder.sync_with_google_sheets({"gsheet_enabled": True, "gsheet_name": "fixture"}, [saved])

        self.assertTrue(ok, message)
        headers, values = worksheet.rows
        for field in ("Name", "Species Name", "Form", "Card Language", "Catalog Provider", "Catalog Locale",
                      "Catalog Card ID", "Set ID", "Set Name", "Card Number", "Catalog Variant", "Catalog Rarity", "Image URL"):
            self.assertEqual(saved[field], values[headers.index(field)], field)

    def test_sync_merges_locale_form_and_art_as_one_printing_identity(self):
        base = {
            **card("Hisuian Zorua", dex=570),
            "Copy ID": "copy-1",
            "Species Name": "Zorua",
            "Form": "Hisuian",
            "Card Language": "en",
            "Catalog Provider": "TCGdex",
            "Catalog Locale": "en",
            "Catalog Card ID": "same-id",
            "Set ID": "set-en",
            "Set Name": "English set",
            "Card Number": "1",
            "Catalog Variant": "normal",
            "Image URL": "https://assets.tcgdex.net/en/swsh/set-en/1/low.webp",
        }
        local = {
            **base,
            "Card Language": "ja",
            "Catalog Locale": "ja",
            "Set ID": "set-ja",
            "Set Name": "Japanese set",
            "Image URL": "https://assets.tcgdex.net/ja/swsh/set-ja/1/low.webp",
        }
        remote = {
            **base,
            "Card Language": "fr",
            "Catalog Locale": "fr",
            "Set ID": "set-fr",
            "Set Name": "French set",
            "Image URL": "https://assets.tcgdex.net/fr/swsh/set-fr/1/low.webp",
        }

        merged, conflicts = pokemon_binder._merge_copy(base, local, remote)

        self.assertIn("printing", conflicts)
        expected = {
            "Name": "Hisuian Zorua", "Species Name": "Zorua", "Form": "Hisuian",
            "Card Language": "ja", "Catalog Provider": "TCGdex", "Catalog Locale": "ja",
            "Catalog Card ID": "same-id", "Set ID": "set-ja", "Set Name": "Japanese set",
            "Card Number": "1", "Catalog Variant": "normal",
            "Image URL": "https://assets.tcgdex.net/ja/swsh/set-ja/1/low.webp",
        }
        for field, value in expected.items():
            self.assertEqual(value, merged[field], field)
