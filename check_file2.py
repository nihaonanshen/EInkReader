import os
path = r'D:\EInkReader\app\src\main\java\com\einkreader\ui\reader\ReaderActivity.kt'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()
# Search for various patterns
patterns = ['triggerLazyLoad', 'lazyLoadChapter', 'loadChapter', 'chapter.content = text', 'Lazy load chapter']
for p in patterns:
    idx = content.find(p)
    print(f'{p}: {idx}')
    if idx >= 0:
        print(content[max(0,idx-50):idx+200])
        print('---')
