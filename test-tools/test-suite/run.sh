#!/bin/bash
# Convenience script for launching the test-suite orchestrator
cd "$(dirname "$0")"

# Check if Python 3 is installed
if ! command -v python3 &> /dev/null; then
    echo "❌ ERROR: Python 3 is not installed on this system."
    exit 1
fi

# Run orchestration script forwarding arguments
python3 orchestrator.py "$@"
