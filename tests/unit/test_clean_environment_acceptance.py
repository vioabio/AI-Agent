from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import threading

from eval.clean_environment_acceptance import _publish_port


def test_port_reader_cannot_observe_a_partially_written_publication(tmp_path, monkeypatch):
    opened, release = threading.Event(), threading.Event()
    original = Path.write_text

    def delayed_write(path, text, **kwargs):
        original(path, "", **kwargs)
        opened.set()
        assert release.wait(timeout=5)
        return original(path, text, **kwargs)

    monkeypatch.setattr(Path, "write_text", delayed_write)
    with ThreadPoolExecutor(max_workers=1) as pool:
        writer = pool.submit(_publish_port, tmp_path, 12345)
        try:
            assert opened.wait(timeout=5)
            published = tmp_path / "port.txt"
            assert not published.exists() or published.read_text(encoding="ascii") == "12345"
        finally:
            release.set()
        writer.result(timeout=5)
    assert published.read_text(encoding="ascii") == "12345"
