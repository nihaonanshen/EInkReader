import os
path = r'D:\EInkReader\app\src\main\java\com\einkreader\ui\reader\ReaderActivity.kt'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()
import re
functions = re.findall(r'private fun \w+|fun \w+', content)
for f in functions:
    print(f)
