import os
path = r'D:\EInkReader\app\src\main\java\com\einkreader\ui\reader\ReaderActivity.kt'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()
idx = content.find('loadChapter')
if idx >= 0:
    print(content[max(0,idx-200):idx+500])
