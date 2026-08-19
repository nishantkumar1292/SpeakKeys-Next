from __future__ import annotations

from collections import Counter
from collections import deque
from dataclasses import dataclass
import threading
import time
from typing import Callable


class ConnectionLimitExceeded(Exception):
    pass


class ConnectionAdmission:
    """Process-local connection caps; the load balancer enforces replica-wide limits."""

    def __init__(self, max_connections: int, max_connections_per_user: int) -> None:
        if max_connections <= 0 or max_connections_per_user <= 0:
            raise ValueError("Connection limits must be positive")
        if max_connections_per_user > max_connections:
            raise ValueError("Per-user connection limit cannot exceed the global limit")
        self._max_connections = max_connections
        self._max_connections_per_user = max_connections_per_user
        self._lock = threading.Lock()
        self._total = 0
        self._by_user: Counter[str] = Counter()

    @property
    def active_connections(self) -> int:
        with self._lock:
            return self._total

    def acquire(self, user_id: str) -> None:
        if not user_id:
            raise ValueError("Verified user identity cannot be empty")
        with self._lock:
            if self._total >= self._max_connections:
                raise ConnectionLimitExceeded("Natural Hinglish has too many open sessions")
            if self._by_user[user_id] >= self._max_connections_per_user:
                raise ConnectionLimitExceeded("Too many voice sessions are open for this account")
            self._total += 1
            self._by_user[user_id] += 1

    def release(self, user_id: str) -> None:
        with self._lock:
            count = self._by_user.get(user_id, 0)
            if count <= 0:
                raise RuntimeError("Connection admission released without acquisition")
            if count == 1:
                del self._by_user[user_id]
            else:
                self._by_user[user_id] = count - 1
            self._total -= 1


class UtteranceRateLimitExceeded(Exception):
    pass


@dataclass(eq=False)
class _RateEntry:
    timestamp: float


class UtteranceReservation:
    def __init__(
        self,
        limiter: "UtteranceRateLimiter",
        user_id: str,
        entry: _RateEntry,
    ) -> None:
        self._limiter = limiter
        self._user_id = user_id
        self._entry = entry
        self._finished = False

    def consume(self) -> None:
        """Keep this commit in the sliding window."""

        self._finished = True

    def cancel(self) -> None:
        """Refund a commit that could not be admitted to the inference queue."""

        if self._finished:
            return
        self._finished = True
        self._limiter._refund(self._user_id, self._entry)


class UtteranceRateLimiter:
    """Process-local sliding-window limit keyed by verified Firebase UID."""

    def __init__(
        self,
        max_utterances: int,
        window_seconds: float,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        if max_utterances <= 0 or window_seconds <= 0:
            raise ValueError("Utterance rate limits must be positive")
        self._max_utterances = max_utterances
        self._window_seconds = window_seconds
        self._clock = clock
        self._lock = threading.Lock()
        self._entries: dict[str, deque[_RateEntry]] = {}

    @property
    def tracked_users(self) -> int:
        with self._lock:
            self._sweep_locked(self._clock())
            return len(self._entries)

    def reserve(self, user_id: str) -> UtteranceReservation:
        if not user_id:
            raise ValueError("Verified user identity cannot be empty")
        now = self._clock()
        with self._lock:
            self._sweep_locked(now)
            entries = self._entries.setdefault(user_id, deque())
            if len(entries) >= self._max_utterances:
                if not entries:
                    del self._entries[user_id]
                raise UtteranceRateLimitExceeded(
                    "Too many voice requests were made; wait a moment and try again",
                )
            entry = _RateEntry(now)
            entries.append(entry)
        return UtteranceReservation(self, user_id, entry)

    def _refund(self, user_id: str, entry: _RateEntry) -> None:
        with self._lock:
            entries = self._entries.get(user_id)
            if entries is None:
                return
            try:
                entries.remove(entry)
            except ValueError:
                return
            if not entries:
                del self._entries[user_id]

    def _sweep_locked(self, now: float) -> None:
        cutoff = now - self._window_seconds
        empty_users: list[str] = []
        for user_id, entries in self._entries.items():
            while entries and entries[0].timestamp <= cutoff:
                entries.popleft()
            if not entries:
                empty_users.append(user_id)
        for user_id in empty_users:
            del self._entries[user_id]
