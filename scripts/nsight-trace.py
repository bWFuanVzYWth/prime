"""CPU-only reader for existing Nsight GPU Trace files; see docs/guides/nsight.md."""

import sys

try:
    from nsight_diagnostics.cli import main
except ModuleNotFoundError as error:
    print("Missing offline-reader dependency: " + str(error) +
          ". Install with: python -m pip install -r scripts/nsight-requirements.txt", file=sys.stderr)
    raise SystemExit(2)


if __name__ == "__main__":
    raise SystemExit(main())
