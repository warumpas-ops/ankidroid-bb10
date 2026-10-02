import sqlite3, os, zstandard, zipfile, shutil

bkd = r"C:\Users\Growth\AppData\Roaming\Anki2\User 1\backups"
found = False
for bk in sorted(os.listdir(bkd), reverse=True):
    if not bk.endswith(".colpkg"):
        continue
    p = os.path.join(bkd, bk)
    try:
        with zipfile.ZipFile(p, "r") as z:
            if "collection.anki21b" in z.namelist():
                os.makedirs("tmp_z", exist_ok=True)
                z.extract("collection.anki21b", "tmp_z")
                dctx = zstandard.ZstdDecompressor()
                with open("tmp_z/collection.anki21b", "rb") as inf, open("tmp_z/col.db", "wb") as outf:
                    dctx.copy_stream(inf, outf)
                conn = sqlite3.connect("tmp_z/col.db")
                c = conn.cursor()
                c.execute("SELECT id, queue, type, due, ivl, data FROM cards WHERE length(data) > 2 LIMIT 5")
                rows = c.fetchall()
                if rows:
                    print(f"=== {bk} ===")
                    for r in rows:
                        print(" ", r)
                    found = True
                conn.close()
                shutil.rmtree("tmp_z", ignore_errors=True)
                if found:
                    break
    except Exception as e:
        shutil.rmtree("tmp_z", ignore_errors=True)
        pass
if not found:
    print("No cards with data > 2 found in backups!")
