#!/bin/sh
set -eu

append_logging_property() {
    property_name="$1"
    env_var_name="$2"
    current_java_tool_options="${JAVA_TOOL_OPTIONS-}"

    eval "env_value=\${$env_var_name-}"

    if [ -z "${env_value}" ]; then
        return
    fi

    if printf '%s' "${current_java_tool_options}" | grep -Fq -- "-D${property_name}="; then
        return
    fi

    escaped_value=$(printf '%s' "${env_value}" | sed 's/\\/\\\\/g; s/"/\\"/g')
    EXTRA_JAVA_TOOL_OPTIONS="${EXTRA_JAVA_TOOL_OPTIONS} -D${property_name}=\"${escaped_value}\""
}

EXTRA_JAVA_TOOL_OPTIONS=""

append_logging_property "ojp.server.logLevel" "OJP_SERVER_LOGLEVEL"
append_logging_property "ojp.server.log.file" "OJP_SERVER_LOG_FILE"
append_logging_property "ojp.server.log.fileNamePattern" "OJP_SERVER_LOG_FILENAMEPATTERN"
append_logging_property "ojp.server.log.maxHistory" "OJP_SERVER_LOG_MAXHISTORY"
append_logging_property "ojp.server.log.totalSizeCap" "OJP_SERVER_LOG_TOTALSIZECAP"
append_logging_property "ojp.server.log.pattern" "OJP_SERVER_LOG_PATTERN"

if [ -n "${EXTRA_JAVA_TOOL_OPTIONS}" ]; then
    export JAVA_TOOL_OPTIONS="${EXTRA_JAVA_TOOL_OPTIONS# } ${JAVA_TOOL_OPTIONS-}"
fi

exec java \
    -XX:+UseG1GC \
    -Dojp.libs.path=/opt/ojp/ojp-libs \
    -Duser.timezone=UTC \
    -jar /opt/ojp/ojp-server.jar
