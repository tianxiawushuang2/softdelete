#!/usr/bin/env sh
set -eu
java -jar logic-delete-analyzer/target/logic-delete-analyzer.jar scan --project "${1:-examples/sample-project}" --config "${2:-examples/logic-delete-tables.yml}" --output "${3:-target/sample-report}"
