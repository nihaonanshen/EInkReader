import os
path = r'D:\EInkReader\app\src\main\java\com\einkreader\ui\reader\ReaderActivity.kt'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()
# Find all function definitions
import re
functions = re.findall(r'fun \w+', content)
for f in functions:
    print(f)
