#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# build.sh — build the FIX Test Harness fat-jar
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

cd "$(dirname "$0")"

echo "==> Building fix-testharness..."
mvn clean package -DskipTests -q

JAR="target/fix-testharness-1.0.0-SNAPSHOT.jar"
echo "==> Build complete: $JAR"
echo ""
echo "Usage:"
echo "  java -jar $JAR [config/client.cfg] [scenarios/order_flow.yml]"
echo ""
echo "Examples:"
echo "  java -jar $JAR"
echo "  java -jar $JAR config/client.cfg scenarios/quote_flow.yml"
