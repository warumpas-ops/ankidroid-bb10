import sqlite3, time, subprocess

# Stop Anki
subprocess.run(["taskkill", "/F", "/IM", "anki.exe"], capture_output=True)
time.sleep(1)

src = r"C:\Users\Growth\AppData\Roaming\Anki2\User 1\collection.anki2"
conn = sqlite3.connect(src)
c = conn.cursor()

c.execute("SELECT crt FROM col")
crt = c.fetchone()[0]
now = int(time.time())
today = (now - crt) // 86400
day_start_ms = (crt + today * 86400) * 1000

did = 1790973027612

# Count unique new cards studied today
c.execute("SELECT count(distinct revlog.cid) FROM revlog JOIN cards ON revlog.cid = cards.id WHERE cards.did = ? AND revlog.id >= ? AND (revlog.type = 0 OR revlog.lastIvl = 0)", (did, day_start_ms))
new_studied = c.fetchone()[0]

# Count review cards studied today
c.execute("SELECT count(*) FROM revlog JOIN cards ON revlog.cid = cards.id WHERE cards.did = ? AND revlog.id >= ? AND revlog.type = 1", (did, day_start_ms))
rev_studied = c.fetchone()[0]

print(f"Deck {did}: new_studied={new_studied}, rev_studied={rev_studied}")

def encode_varint(val):
    res = bytearray()
    while True:
        b = val & 0x7f
        val >>= 7
        if val:
            res.append(b | 0x80)
        else:
            res.append(b)
            break
    return bytes(res)

new_common = bytearray()
new_common.extend(b"\x18" + encode_varint(today))
new_common.extend(b"\x20" + encode_varint(new_studied))
new_common.extend(b"\x28" + encode_varint(rev_studied))
other_val = b'{"desiredRetention":null}'
new_common.extend(b"\xfa\x0f" + encode_varint(len(other_val)) + other_val)

c.execute("UPDATE decks SET common = ?, usn = -1 WHERE id = ?", (bytes(new_common), did))
c.execute("UPDATE revlog SET lastIvl = 0 WHERE type = 0 AND lastIvl = -60 AND cid IN (SELECT id FROM cards WHERE did = ?)", (did,))
conn.commit()
print("SUCCESSFULLY updated decks.common and revlog!")

conn.close()

# Start Anki back up
subprocess.Popen([r"C:\Users\Growth\AppData\Local\Programs\Anki\anki.exe"])
print("Anki Desktop restarted!")
