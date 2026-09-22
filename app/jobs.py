from __future__ import annotations

import itertools
import queue
import threading
import traceback
from typing import Any, Callable

from .db import utcnow


class JobRunner:
    """Single background worker.

    Instagram work is serialized anyway (one lock in the client), so one worker
    thread with a FIFO queue is both the simplest and the safest shape: the UI
    fires off syncs and polls for progress instead of blocking a request.
    """

    def __init__(self, history: int = 40) -> None:
        self._queue: queue.Queue[tuple[str, Callable[[], Any]]] = queue.Queue()
        self._jobs: dict[str, dict[str, Any]] = {}
        self._order: list[str] = []
        self._lock = threading.Lock()
        self._ids = itertools.count(1)
        self._history = history
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()

    def submit(self, label: str, fn: Callable[[], Any]) -> str:
        job_id = f"job{next(self._ids)}"
        with self._lock:
            self._jobs[job_id] = {
                "id": job_id,
                "label": label,
                "status": "queued",
                "queued_at": utcnow(),
                "started_at": None,
                "finished_at": None,
                "result": None,
                "error": None,
            }
            self._order.append(job_id)
            self._trim()
        self._queue.put((job_id, fn))
        return job_id

    def snapshot(self) -> list[dict[str, Any]]:
        with self._lock:
            return [dict(self._jobs[i]) for i in reversed(self._order)]

    def active_count(self) -> int:
        with self._lock:
            return sum(
                1
                for job in self._jobs.values()
                if job["status"] in ("queued", "running")
            )

    def _trim(self) -> None:
        while len(self._order) > self._history:
            stale = self._order.pop(0)
            self._jobs.pop(stale, None)

    def _update(self, job_id: str, **fields: Any) -> None:
        with self._lock:
            job = self._jobs.get(job_id)
            if job is not None:
                job.update(fields)

    def _loop(self) -> None:
        while True:
            job_id, fn = self._queue.get()
            self._update(job_id, status="running", started_at=utcnow())
            try:
                result = fn()
                self._update(
                    job_id, status="done", result=result, finished_at=utcnow()
                )
            except Exception as exc:  # a failed sync must not kill the worker
                traceback.print_exc()
                self._update(
                    job_id,
                    status="error",
                    error=str(exc) or exc.__class__.__name__,
                    finished_at=utcnow(),
                )
            finally:
                self._queue.task_done()


runner = JobRunner()
