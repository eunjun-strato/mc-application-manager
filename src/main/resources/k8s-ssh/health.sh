#!/bin/sh
exec curl --noproxy '*' --fail --silent --output /dev/null --max-time 3 http://127.0.0.1:18084/healthz
