import os
path = r'D:\EInkReader\app\src\main\java\com\einkreader\ui\reader\ReaderActivity.kt'
print(f'Size: {os.path.getsize(path)} bytes')
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()
print(f'Total chars: {len(content)}')
idx = content.find('triggerLazyLoadChapter')
print(f'triggerLazyLoadChapter at: {idx}')
if idx >= 0:
    print(content[idx:idx+500])
