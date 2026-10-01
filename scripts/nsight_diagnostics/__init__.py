"""Offline diagnostics. No target, replay, GPU, or network interfaces."""

OUTPUT_SCHEMA_VERSION = 1
TOOL_VERSION = "1.0"


class FormatError(ValueError):
    """Input is corrupt or outside the explicitly supported format profile."""
