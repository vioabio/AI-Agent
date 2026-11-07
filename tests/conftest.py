"""Offline tests never inherit local production configuration or remote access."""
from __future__ import annotations

import ipaddress
import os
import socket

import pytest
from sqlalchemy import Engine, event

from apps.hr_review_api.config import HRReviewSettings
from liepin_rpa.config import Settings


# Runs before test module collection; explicit per-test settings still work.
for settings_type in (Settings, HRReviewSettings):
    settings_type.model_config["env_file"] = None
    for name, field in settings_type.model_fields.items():
        os.environ.pop(field.alias or name, None)
os.environ["APP_ENV"] = "test"


def _is_loopback(host) -> bool:
    if host is None or host in ("localhost", b"localhost"):
        return True
    try:
        return ipaddress.ip_address(host.decode() if isinstance(host, bytes) else host).is_loopback
    except ValueError:
        return False


@pytest.fixture(autouse=True)
def block_external_network(monkeypatch):
    """Allow local event loops/TestClient, reject external DNS and TCP/UDP."""
    original_connect = socket.socket.connect
    original_connect_ex = socket.socket.connect_ex
    original_getaddrinfo = socket.getaddrinfo
    original_sendto = socket.socket.sendto

    def check_address(address):
        if isinstance(address, tuple) and not _is_loopback(address[0]):
            raise RuntimeError("External network access is disabled in offline tests")

    def connect(sock, address):
        check_address(address)
        return original_connect(sock, address)

    def connect_ex(sock, address):
        check_address(address)
        return original_connect_ex(sock, address)

    def getaddrinfo(host, *args, **kwargs):
        if not _is_loopback(host):
            raise RuntimeError("External DNS is disabled in offline tests")
        return original_getaddrinfo(host, *args, **kwargs)

    def sendto(sock, data, *args):
        check_address(args[-1])
        return original_sendto(sock, data, *args)

    monkeypatch.setattr(socket.socket, "connect", connect)
    monkeypatch.setattr(socket.socket, "connect_ex", connect_ex)
    monkeypatch.setattr(socket.socket, "sendto", sendto)
    monkeypatch.setattr(socket, "getaddrinfo", getaddrinfo)

    def sqlite_only(dialect, _connection_record, _cargs, _cparams):
        if dialect.name != "sqlite":
            raise RuntimeError("Offline tests allow only temporary SQLite connections")

    event.listen(Engine, "do_connect", sqlite_only)
    yield
    event.remove(Engine, "do_connect", sqlite_only)
