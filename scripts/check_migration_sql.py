#!/usr/bin/env python3
"""Runs the Room 1->2 statements (AppMigrations.V2_STATEMENTS) twice on SQLite to prove they are valid and repeatable,
and checks every entity's columns exist. It cannot replace MigrationTestHelper (androidTest), which also checks Room's schema hash."""
import re, sqlite3, pathlib
src = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/java/com/example/autoposter"
block = (src / "AppDatabase.kt").read_text().split("V2_STATEMENTS")[1].split("val MIGRATION_1_2")[0]
stmts = re.findall(r'^\s+"(CREATE[^"]*)"', block, re.M)
assert len(stmts) == 10, f"expected 10 statements, found {len(stmts)}"
db = sqlite3.connect(":memory:")
db.execute("CREATE TABLE posts (id TEXT PRIMARY KEY NOT NULL)"); db.execute("INSERT INTO posts VALUES ('keep')")
for _ in range(2):
    for s in stmts: db.execute(s)
assert db.execute("SELECT COUNT(*) FROM posts").fetchone()[0] == 1
data = (src / "V07Data.kt").read_text() + (src / "AiGateway.kt").read_text()
def fields(cls):
    i = data.index(f"data class {cls}(") + len(f"data class {cls}("); depth = 1; j = i
    while depth:                      # balanced-parenthesis scan (a class can be one line or many)
        depth += {"(": 1, ")": -1}.get(data[j], 0); j += 1
    return re.findall(r"val (\w+):", data[i:j - 1])
for table, cls in [("brand_profiles","BrandProfile"),("media_items","MediaItemEntity"),("media_folders","MediaFolderEntity"),("composer_drafts","ComposerDraftEntity"),("ai_usage_cache","AiUsageEntity")]:
    cols = {r[1] for r in db.execute(f"pragma table_info({table})")}
    assert set(fields(cls)) == cols, f"{table}: entity {sorted(set(fields(cls)) ^ cols)} differ from migration"
print("OK: 10 migration statements valid, repeatable, and match all 5 entities.")
