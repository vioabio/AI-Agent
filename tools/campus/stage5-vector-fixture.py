"""Bounded mutations of isolated stage5 vector fixtures only."""
import sys
import json
import hashlib
import sqlite3
from pathlib import Path

action, value = sys.argv[1:3]
root = Path('artifacts').resolve()
source = Path(value).resolve()
if not source.is_relative_to(root) or source.suffix != '.sqlite' or not source.is_file():
    raise ValueError('ISOLATED_VECTOR_FILE_REQUIRED')
with sqlite3.connect(str(source)) as connection:
    if action == 'snapshot':
        rows = connection.execute('SELECT index_id,chunk_id,content_hash,payload,payload_hash FROM guide_vector ORDER BY index_id,chunk_id').fetchall()
        meta = connection.execute('SELECT index_id,chunk_count FROM guide_vector_meta ORDER BY index_id').fetchall()
        def digest(values):
            return hashlib.sha256(json.dumps(values, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()
        print(json.dumps({'vectorCount': len(rows), 'indexCount': len(meta), 'rowsSha256': digest(rows), 'metaSha256': digest(meta)}))
    elif action == 'tamper-one':
        row = connection.execute('SELECT index_id,chunk_id FROM guide_vector ORDER BY index_id,chunk_id LIMIT 1').fetchone()
        if row is None:
            raise ValueError('INDEX_REQUIRED')
        connection.execute('UPDATE guide_vector SET payload_hash=? WHERE index_id=? AND chunk_id=?', ('controlled-invalid-digest', row[0], row[1]))
        connection.commit()
        print(json.dumps({'tamperedRows': 1}))
    elif action == 'backup':
        target = Path(sys.argv[3]).resolve()
        if not target.is_relative_to(root) or target.suffix != '.sqlite' or target.exists():
            raise ValueError('NEW_ISOLATED_BACKUP_REQUIRED')
        with sqlite3.connect(str(target)) as output:
            connection.backup(output)
        print(json.dumps({'backupCreated': True}))
    else:
        raise ValueError('UNSUPPORTED_FIXTURE_OPERATION')
