#!/bin/sh
set -eu

set -- \
    -XX:+UseG1GC \
    -Dojp.libs.path=/opt/ojp/ojp-libs \
    -Duser.timezone=UTC

if [ -n "${OJP_SERVER_LOGLEVEL-}" ] && ! printf '%s' "${JAVA_TOOL_OPTIONS-}" | grep -Fq -- "-Dojp.server.logLevel="; then
    set -- "$@" "-Dojp.server.logLevel=${OJP_SERVER_LOGLEVEL}"
fi

if [ -n "${OJP_SERVER_LOG_FILE-}" ] && ! printf '%s' "${JAVA_TOOL_OPTIONS-}" | grep -Fq -- "-Dojp.server.log.file="; then
    set -- "$@" "-Dojp.server.log.file=${OJP_SERVER_LOG_FILE}"
fi

if [ -n "${OJP_SERVER_LOG_FILENAMEPATTERN-}" ] && ! printf '%s' "${JAVA_TOOL_OPTIONS-}" | grep -Fq -- "-Dojp.server.log.fileNamePattern="; then
    set -- "$@" "-Dojp.server.log.fileNamePattern=${OJP_SERVER_LOG_FILENAMEPATTERN}"
fi

if [ -n "${OJP_SERVER_LOG_MAXHISTORY-}" ] && ! printf '%s' "${JAVA_TOOL_OPTIONS-}" | grep -Fq -- "-Dojp.server.log.maxHistory="; then
    set -- "$@" "-Dojp.server.log.maxHistory=${OJP_SERVER_LOG_MAXHISTORY}"
fi

if [ -n "${OJP_SERVER_LOG_TOTALSIZECAP-}" ] && ! printf '%s' "${JAVA_TOOL_OPTIONS-}" | grep -Fq -- "-Dojp.server.log.totalSizeCap="; then
    set -- "$@" "-Dojp.server.log.totalSizeCap=${OJP_SERVER_LOG_TOTALSIZECAP}"
fi

if [ -n "${OJP_SERVER_LOG_PATTERN-}" ] && ! printf '%s' "${JAVA_TOOL_OPTIONS-}" | grep -Fq -- "-Dojp.server.log.pattern="; then
    set -- "$@" "-Dojp.server.log.pattern=${OJP_SERVER_LOG_PATTERN}"
fi

exec java \
    "$@" \
    -jar /opt/ojp/ojp-server.jar
