#!/usr/bin/env sh

set -e

host="$1"
shift
cmd="$@"

until nc -z -w 2 "$host" 3306; do
  >&2 echo "mysql is unavailable - sleeping"
  sleep 1
done

>&2 echo "mysql is up - executing command"
exec $cmd

