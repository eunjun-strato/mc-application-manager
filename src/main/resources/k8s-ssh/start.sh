#!/bin/sh
set -eu
umask 077
chmod 755 /run/mcmp-ssh
cp /etc/mcmp-ssh/host-key /run/mcmp-ssh/host-key
cp /etc/mcmp-ssh/authorized_keys /run/mcmp-ssh/authorized_keys
chmod 600 /run/mcmp-ssh/host-key
chmod 644 /run/mcmp-ssh/authorized_keys
exec /usr/sbin/sshd.pam -D -e -f /opt/mcmp-ssh/sshd_config
