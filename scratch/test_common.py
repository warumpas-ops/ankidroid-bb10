import sys
sys.path.insert(0, r"C:\Users\Growth\AppData\Local\Programs\Anki\app_packages")
from anki import decks_pb2

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

today = 0
new_studied = 30
rev_studied = 0

f3 = b"\x18" + encode_varint(today)
f4 = b"\x20" + encode_varint(new_studied)
f5 = b"\x28" + encode_varint(rev_studied)
other_val = b'{"desiredRetention":null}'
f255 = b"\xfa\x0f" + encode_varint(len(other_val)) + other_val

new_common = f3 + f4 + f5 + f255
print("Encoded new_common hex:", new_common.hex())

deck_msg = decks_pb2.Deck()
deck_msg.common.ParseFromString(new_common)
print("Decoded successfully!")
print("last_day_studied:", deck_msg.common.last_day_studied)
print("new_studied:", deck_msg.common.new_studied)
print("review_studied:", deck_msg.common.review_studied)
print("other:", deck_msg.common.other)
