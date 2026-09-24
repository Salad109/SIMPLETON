#!/usr/bin/env bash
set -e

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

run_gc() {
    local gc_flag="$1"

    echo ""
    echo "=== Running $gc_flag ==="
    echo ""
    cd "$ROOT"
    ./mvnw spring-boot:run -Dspring-boot.run.profiles=benchmark-gc "-Dspring-boot.run.jvmArguments=-Xmx12g -Xms12g -XX:+AlwaysPreTouch -Dconjunction.schedule.cron=- $gc_flag"
}

run_gc "-XX:+UseG1GC"
run_gc "-XX:+UseParallelGC"
run_gc "-XX:+UseShenandoahGC"
run_gc "-XX:+UseZGC"
