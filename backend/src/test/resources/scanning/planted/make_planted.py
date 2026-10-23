"""SM-7's planted SQLite files (Story 16.3), regenerated with: python make_planted.py . Every person and number is invented."""
import os
import sqlite3
import sys

out = sys.argv[1]
os.makedirs(out, exist_ok=True)


def fresh(name, encoding=None):
    path = os.path.join(out, name)
    if os.path.exists(path):
        os.remove(path)
    db = sqlite3.connect(path)
    db.execute('PRAGMA page_size = 4096')
    if encoding:
        db.execute(f"PRAGMA encoding = '{encoding}'")
    db.execute('PRAGMA journal_mode = DELETE')
    return db


def phone(i):
    return '09' + str(12_345_600 + i).zfill(8)


# A customer export: enough rows for interior pages, and one note long enough for overflow pages.
db = fresh('khach-hang.sqlite')
db.execute('''CREATE TABLE "khach_hang" (
    id INTEGER PRIMARY KEY,
    ho_ten TEXT NOT NULL,
    email TEXT,
    so_dien_thoai TEXT,
    ghi_chu TEXT,
    CONSTRAINT email_unique UNIQUE (email)
)''')
db.execute('CREATE TABLE settings (key TEXT, value TEXT)')
db.execute("INSERT INTO settings VALUES ('theme', 'light')")
for i in range(300):
    db.execute('INSERT INTO khach_hang (ho_ten, email, so_dien_thoai, ghi_chu) VALUES (?, ?, ?, ?)',
               (f'Khách {i}', f'khach.so{i}@gmail.com', phone(i), 'Đặt bàn tối thứ bảy'))
long_note = ' '.join(f'Liên hệ lại nguoi.goi{i}@yahoo.com' for i in range(200))
db.execute('INSERT INTO khach_hang (ho_ten, email, so_dien_thoai, ghi_chu) VALUES (?, ?, ?, ?)',
           ('Khách ghi chú dài', 'khach.dai@gmail.com', phone(900), long_note))
db.commit()
db.close()

# A clean app database: settings only.
db = fresh('cau-hinh.db')
db.execute('CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT) WITHOUT ROWID')
db.execute('CREATE TABLE flags (name TEXT, enabled INTEGER, ratio REAL)')
db.execute("INSERT INTO settings VALUES ('theme', 'light'), ('locale', 'vi')")
db.execute("INSERT INTO flags VALUES ('booking', 1, 0.5)")
db.commit()
db.close()

# The same kind of export in UTF-16, for the reader's decoding.
db = fresh('utf16.sqlite', 'UTF-16le')
db.execute('CREATE TABLE contacts (email TEXT, sdt TEXT)')
db.execute("INSERT INTO contacts VALUES ('lan.utf16@gmail.com', '0912000111'), ('minh.utf16@gmail.com', '0912000222')")
db.commit()
db.close()
for name in os.listdir(out):
    print(name, os.path.getsize(os.path.join(out, name)))
