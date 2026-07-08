from __future__ import annotations

from liepin_rpa.config import Settings


def build_mysql_connection_kwargs(settings: Settings) -> dict[str, object]:
    return {
        "host": settings.mysql_host,
        "port": settings.mysql_port,
        "user": settings.mysql_user,
        "password": settings.mysql_password,
        "database": settings.mysql_database,
        "charset": settings.mysql_charset,
        "autocommit": False,
    }
