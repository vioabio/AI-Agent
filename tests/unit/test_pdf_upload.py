from pathlib import Path
from types import SimpleNamespace

from pydantic import SecretStr

from liepin_rpa.pdf_upload import _has_password


def test_password_auth_is_enabled_only_for_non_empty_secret() -> None:
    assert not _has_password(SimpleNamespace(pdf_upload_password=None))
    assert not _has_password(SimpleNamespace(pdf_upload_password=SecretStr("")))
    assert _has_password(SimpleNamespace(pdf_upload_password=SecretStr("secret")))
