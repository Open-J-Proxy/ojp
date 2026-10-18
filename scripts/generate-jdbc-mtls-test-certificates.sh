#!/usr/bin/env bash
set -euo pipefail
umask 077

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <output-directory>" >&2
    exit 2
fi

output_dir=$1
store_password=ojp-test-only
mkdir -p "$output_dir"
chmod 700 "$output_dir"

keytool -genkeypair \
    -alias ojp-server \
    -keyalg RSA -keysize 2048 -validity 365 \
    -storetype PKCS12 \
    -keystore "$output_dir/server-keystore.p12" \
    -storepass "$store_password" -keypass "$store_password" \
    -dname "CN=localhost, OU=OJP Tests, O=Open-J-Proxy" \
    -ext "SAN=dns:localhost,ip:127.0.0.1"
keytool -exportcert -rfc \
    -alias ojp-server \
    -keystore "$output_dir/server-keystore.p12" \
    -storepass "$store_password" \
    -file "$output_dir/server-cert.pem"

keytool -genkeypair \
    -alias ojp-client \
    -keyalg RSA -keysize 2048 -validity 365 \
    -storetype PKCS12 \
    -keystore "$output_dir/client-keystore.p12" \
    -storepass "$store_password" -keypass "$store_password" \
    -dname "CN=ojp-test-client, OU=OJP Tests, O=Open-J-Proxy"
keytool -exportcert -rfc \
    -alias ojp-client \
    -keystore "$output_dir/client-keystore.p12" \
    -storepass "$store_password" \
    -file "$output_dir/client-cert.pem"

keytool -genkeypair \
    -alias ojp-untrusted-client \
    -keyalg RSA -keysize 2048 -validity 365 \
    -storetype PKCS12 \
    -keystore "$output_dir/untrusted-client-keystore.p12" \
    -storepass "$store_password" -keypass "$store_password" \
    -dname "CN=ojp-untrusted-test-client, OU=OJP Tests, O=Open-J-Proxy"

keytool -importcert -noprompt \
    -alias ojp-client \
    -file "$output_dir/client-cert.pem" \
    -storetype PKCS12 \
    -keystore "$output_dir/server-truststore.p12" \
    -storepass "$store_password"
keytool -importcert -noprompt \
    -alias ojp-server \
    -file "$output_dir/server-cert.pem" \
    -storetype PKCS12 \
    -keystore "$output_dir/client-truststore.p12" \
    -storepass "$store_password"
