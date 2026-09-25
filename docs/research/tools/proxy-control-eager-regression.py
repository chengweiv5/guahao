#!/usr/bin/env python3
"""Reproduce the mitmproxy/Python eager-task startup ordering bug.

Usage: python proxy-control-eager-regression.py OLD_CONTROL.py FIXED_CONTROL.py
This harness never starts a listener or accesses the network.
"""
import asyncio
import importlib.util
import json
from pathlib import Path
import sys


async def exercise(path: Path, label: str) -> dict:
    spec = importlib.util.spec_from_file_location(f"control_{label}", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader
    spec.loader.exec_module(module)
    module.event = lambda *args, **kwargs: None
    module.ctx.options = object()
    control = module.Control()
    loop = asyncio.get_running_loop()
    previous = loop.get_task_factory()
    loop.set_task_factory(asyncio.eager_task_factory)
    try:
        control.running()
        await asyncio.sleep(0)
        if control.task.done():
            error = control.task.exception()
            return {
                "task_failed": True,
                "exception": type(error).__name__,
                "message": str(error),
            }
        control.task.cancel()
        return {"task_failed": False}
    finally:
        loop.set_task_factory(previous)


async def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("expected OLD_CONTROL.py FIXED_CONTROL.py")
    result = {
        "old": await exercise(Path(sys.argv[1]), "old"),
        "fixed": await exercise(Path(sys.argv[2]), "fixed"),
    }
    assert result["old"]["exception"] == "AttributeError"
    assert "mode" in result["old"]["message"]
    assert not result["fixed"]["task_failed"]
    print(json.dumps(result))


asyncio.run(main())
