#!/usr/bin/env bash
# Sets the System Audio Recording (kTCCServiceAudioCapture) decision of the processes a hosted macOS runner starts
# tests from: every client the image already granted the microphone (the runner's own processes and shells) and the
# JDK's launcher, in the user's and the system's permission database. Hosted runners have SIP off, so the databases
# are writable. Usage: macos-tap-permission.sh <auth_value> (0 refused, 2 granted, none: not asked yet)
set -euo pipefail
value="$1"
now=$(date +%s)
java="${JAVA_HOME:-}/bin/java"
for db in "$HOME/Library/Application Support/com.apple.TCC/TCC.db" "/Library/Application Support/com.apple.TCC/TCC.db"; do
  if [ ! -f "$db" ]; then
    continue
  fi
  if [ "$value" = none ]; then
    sudo sqlite3 "$db" "DELETE FROM access WHERE service = 'kTCCServiceAudioCapture';"
    continue
  fi
  sudo sqlite3 "$db" "
    CREATE TEMP TABLE clients(client TEXT, client_type INTEGER);
    INSERT INTO clients SELECT DISTINCT client, client_type FROM access WHERE service = 'kTCCServiceMicrophone';
    INSERT INTO clients VALUES ('$java', 1), ('/bin/bash', 1), ('/bin/sh', 1), ('/bin/zsh', 1);
    INSERT OR REPLACE INTO access (service, client, client_type, auth_value, auth_reason, auth_version, flags, last_modified)
      SELECT DISTINCT 'kTCCServiceAudioCapture', client, client_type, $value, 4, 1, 0, $now FROM clients;
    SELECT 'kTCCServiceAudioCapture', client, auth_value FROM access WHERE service = 'kTCCServiceAudioCapture';"
done
# tccd caches decisions per client; both daemons restart on demand.
sudo killall tccd || true
killall tccd || true
sleep 2
