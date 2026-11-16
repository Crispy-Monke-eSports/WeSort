#!/bin/sh
set -eu
umask 077
exec java -XX:MaxRAMPercentage=70 -Djava.awt.headless=true -Dorg.sqlite.lib.path=/opt/wesort/native -jar /opt/wesort/wesort-manager.jar "$@" --data /var/lib/wesort
