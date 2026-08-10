#!/bin/bash
# Warns if decision.md/flow.md weren't touched this session but source files were
if git diff --name-only | grep -qE '\.(java|tsx|ts)$'; then
  if ! git diff --name-only | grep -qE 'decision\.md|flow\.md'; then
    echo "⚠️  Code changed but decision.md/flow.md not updated" >&2
    exit 2
  fi
fi