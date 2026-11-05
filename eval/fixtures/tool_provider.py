"""Scripted protocol replies. Not a language model or an SDK transport."""


class ScriptedToolProvider:
    simulated = True
    identity = "scripted-tools-v1"

    def __init__(self, steps, *, tool_support="supported"):
        self.steps = list(steps)
        self.tool_support = tool_support
        self.requests = []  # Private in-memory transcript for protocol tests, never exported.

    def complete(self, request):
        self.requests.append(request)
        if not self.steps:
            raise RuntimeError("Scripted reply exhausted")
        step = self.steps.pop(0)
        if isinstance(step, Exception):
            raise step
        return step(request) if callable(step) else step
