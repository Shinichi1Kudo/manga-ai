#!/bin/bash
JAR_FILE="/root/.openclaw/workspace/manga-ai/backend/target/manga-ai-1.0.0-SNAPSHOT.jar"
EXPLODE_DIR="/root/.openclaw/workspace/manga-ai/backend/target/exploded"

rm -rf "$EXPLODE_DIR"
mkdir -p "$EXPLODE_DIR"
cd "$EXPLODE_DIR"
jar xf "$JAR_FILE"

exec java -cp BOOT-INF/classes:BOOT-INF/lib/* com.manga.ai.MangaAiApplication
