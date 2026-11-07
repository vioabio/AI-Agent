"""Actual SDK function-call frames, using the existing numeric-loopback-only client."""
import json

from liepin_rpa.queued_openai import QueuedOpenAIFixtureAdapter, _usage
from liepin_rpa.bounded_tools.runner import ModelReply, ToolCall
from liepin_rpa.analysis_versioning import content_hash


class SDKToolFixtureProvider:
    simulated = True
    identity = "sdk-loopback-tools-v1"
    tool_support = "supported"

    def __init__(self, queue, *, base_url, model="fixture-tools"):
        self.transport = QueuedOpenAIFixtureAdapter(queue, base_url=base_url)
        self.model = model
        self.identity = "sdk-tools-" + content_hash({"model": model, "sdk": __import__("openai").__version__})[:48]

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.transport.close()

    def complete(self, request):
        body = {"model": self.model, "messages": request.messages, "temperature": 0, "store": False,
                "max_completion_tokens": request.max_output_tokens}
        if request.tools:
            body["tools"] = request.tools
        response = self.transport.client.chat.completions.create(**body, timeout=min(5.0, request.remaining_seconds))
        first, second, invalid = _usage(response)
        if invalid or response.model != self.model or len(response.choices) != 1:
            raise ValueError("Invalid fixture model response")
        choice = response.choices[0]
        if choice.message.refusal:
            raise ValueError("Fixture refused")
        if choice.finish_reason == "tool_calls" and choice.message.tool_calls and not choice.message.content:
            calls = tuple(ToolCall(call_id=call.id, name=call.function.name, arguments=call.function.arguments)
                          for call in choice.message.tool_calls if call.type == "function")
            if len(calls) != len(choice.message.tool_calls):
                raise ValueError("Unsupported tool type")
            return ModelReply(tool_calls=calls, input_tokens=first, output_tokens=second)
        if choice.finish_reason != "stop" or choice.message.tool_calls:
            raise ValueError("Invalid final frame")
        document = json.loads(choice.message.content)
        if not isinstance(document, dict) or set(document) != {"payload", "citations"}:
            raise ValueError("Invalid final envelope")
        return ModelReply(payload=document["payload"], citations=document["citations"], input_tokens=first, output_tokens=second)


class SDKToolProvider(SDKToolFixtureProvider):
    simulated = False
    execution_mode = "configured"

    def __init__(self, queue, *, base_url, api_key, model, tool_support="unknown", transport=None):
        from liepin_rpa.queued_openai import QueuedOpenAIAdapter
        if tool_support not in {"supported", "unsupported", "unknown"}:
            raise ValueError("Invalid explicit provider capability")
        self.transport = QueuedOpenAIAdapter(queue, base_url=base_url, api_key=api_key, transport=transport)
        self.model, self.tool_support = model, tool_support
        self.identity = "configured-tools-" + content_hash({"model": model, "endpoint": base_url,
            "sdk": __import__("openai").__version__})[:40]
