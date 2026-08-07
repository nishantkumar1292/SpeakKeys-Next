from __future__ import annotations

import asyncio
from dataclasses import dataclass
from typing import Protocol


class AuthenticationError(Exception):
    pass


class TokenVerifier(Protocol):
    async def verify(self, token: str) -> str: ...


@dataclass
class DevelopmentTokenVerifier:
    user_id: str = "local-development-user"

    async def verify(self, token: str) -> str:
        return self.user_id


class FirebaseTokenVerifier:
    """Verifies Firebase ID tokens without any database call in the audio path."""

    def __init__(self, project_id: str | None = None) -> None:
        import firebase_admin

        options = {"projectId": project_id} if project_id else None
        try:
            firebase_admin.get_app()
        except ValueError:
            firebase_admin.initialize_app(options=options)

    async def verify(self, token: str) -> str:
        if not token:
            raise AuthenticationError("Missing bearer token")

        from firebase_admin import auth

        try:
            decoded = await asyncio.to_thread(auth.verify_id_token, token)
        except Exception as error:
            raise AuthenticationError("Invalid or expired session") from error
        user_id = decoded.get("uid") or decoded.get("sub")
        if not isinstance(user_id, str) or not user_id:
            raise AuthenticationError("Session has no user identity")
        return user_id


def bearer_token(header: str | None) -> str:
    if not header:
        raise AuthenticationError("Missing bearer token")
    scheme, separator, token = header.partition(" ")
    if separator != " " or scheme.lower() != "bearer" or not token.strip():
        raise AuthenticationError("Malformed bearer token")
    return token.strip()
