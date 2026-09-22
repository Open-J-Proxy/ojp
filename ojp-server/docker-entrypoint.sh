#!/bin/sh
set -eu

set -- \
    -XX:+UseG1GC \
    -Dojp.libs.path=/opt/ojp/ojp-libs \
    -Duser.timezone=UTC

build_logging_property_arg() {
    env_value="$1"
    property_name="$2"

    if [ -z "${env_value}" ]; then
        return
    fi

    printf '%s' "-D${property_name}=${env_value}"
}

logging_arg="$(build_logging_property_arg "${OJP_SERVER_LOGLEVEL-}" "ojp.server.logLevel")"
if [ -n "${logging_arg}" ]; then
    set -- "$@" "${logging_arg}"
fi

logging_arg="$(build_logging_property_arg "${OJP_SERVER_LOG_FILE-}" "ojp.server.log.file")"
if [ -n "${logging_arg}" ]; then
    set -- "$@" "${logging_arg}"
fi

logging_arg="$(build_logging_property_arg "${OJP_SERVER_LOG_FILENAMEPATTERN-}" "ojp.server.log.fileNamePattern")"
if [ -n "${logging_arg}" ]; then
    set -- "$@" "${logging_arg}"
fi

logging_arg="$(build_logging_property_arg "${OJP_SERVER_LOG_MAXHISTORY-}" "ojp.server.log.maxHistory")"
if [ -n "${logging_arg}" ]; then
    set -- "$@" "${logging_arg}"
fi

logging_arg="$(build_logging_property_arg "${OJP_SERVER_LOG_TOTALSIZECAP-}" "ojp.server.log.totalSizeCap")"
if [ -n "${logging_arg}" ]; then
    set -- "$@" "${logging_arg}"
fi

logging_arg="$(build_logging_property_arg "${OJP_SERVER_LOG_PATTERN-}" "ojp.server.log.pattern")"
if [ -n "${logging_arg}" ]; then
    set -- "$@" "${logging_arg}"
fi

exec java \
    "$@" \
    -jar /opt/ojp/ojp-server.jar
