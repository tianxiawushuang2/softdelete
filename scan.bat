@echo off
java -jar logic-delete-analyzer\target\logic-delete-analyzer.jar scan --project %1 --config %2 --output %3
