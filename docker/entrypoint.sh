#!/bin/sh
set -eu
umask 077
exec java --enable-native-access=ALL-UNNAMED -XX:MaxRAMPercentage=70 -Djava.awt.headless=true -Dorg.sqlite.lib.path=/opt/wesort/native -jar /opt/wesort/wesort-manager.jar "$@" --data /var/lib/wesort
