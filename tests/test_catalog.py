import json
import os
import re
import unittest

CATALOG = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "catalog.json")
PKG = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$")


class CatalogTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(CATALOG, encoding="utf-8") as f:
            cls.db = json.load(f)

    def test_unique_ids_and_packages(self):
        ids = [p["id"] for p in self.db["programs"]]
        pkgs = [p["pkg"] for p in self.db["programs"]]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertEqual(len(pkgs), len(set(pkgs)))

    def test_fields(self):
        cats = {c["id"] for c in self.db["categories"]}
        for p in self.db["programs"]:
            self.assertIn(p["category"], cats, p["id"])
            self.assertIn(p["source"], ("fdroid", "play"), p["id"])
            self.assertRegex(p["pkg"], PKG)
            self.assertTrue(p["name"] and p["desc"], p["id"])

    def test_every_category_has_programs(self):
        used = {p["category"] for p in self.db["programs"]}
        for c in self.db["categories"]:
            self.assertIn(c["id"], used, c["id"])

    def test_presets_reference_programs(self):
        ids = {p["id"] for p in self.db["programs"]}
        for pr in self.db["presets"]:
            self.assertTrue(pr["ids"], pr["id"])
            self.assertEqual(len(pr["ids"]), len(set(pr["ids"])), pr["id"])
            for i in pr["ids"]:
                self.assertIn(i, ids, f"{pr['id']}: {i}")


if __name__ == "__main__":
    unittest.main()
