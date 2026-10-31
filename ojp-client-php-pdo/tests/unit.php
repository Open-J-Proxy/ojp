<?php
declare(strict_types=1);

use OpenJProxy\PDO\OjpPDO;
use OpenJProxy\PDO\OjpPDOException;
use OpenJProxy\PDO\OjpPDOStatement;
use Com\Openjproxy\Grpc\SqlErrorResponse;

require dirname(__DIR__) . '/vendor/autoload.php';

if (!is_subclass_of(OjpPDO::class, PDO::class)) {
    throw new RuntimeException('OjpPDO must extend PDO');
}
if (!is_subclass_of(OjpPDOStatement::class, PDOStatement::class)) {
    throw new RuntimeException('OjpPDOStatement must extend PDOStatement');
}
if (!method_exists(OjpPDO::class, 'isClosed')) {
    throw new RuntimeException('OjpPDO must expose its closed state');
}

$sqlError = new SqlErrorResponse();
$sqlError->setSqlState('23505');
$sqlError->setVendorCode(23505);
$sqlError->setReason('duplicate key');
$parseSqlError = new ReflectionMethod(OpenJProxy\PDO\OjpConnection::class, 'sqlErrorInfo');
$parseSqlError->setAccessible(true);
$parsedSqlError = $parseSqlError->invoke(null, [
    'com.openjproxy.grpc.sqlerrorresponse-bin' => [$sqlError->serializeToString()],
]);
if ($parsedSqlError !== ['23505', 23505, 'duplicate key']) {
    throw new RuntimeException('OJP SQL error trailer was not decoded: ' . var_export($parsedSqlError, true));
}

foreach ([
    'not-an-ojp-dsn',
    'ojp:host=localhost;port=0;url=jdbc:h2:mem:test',
    'ojp:host=localhost;port=1059;url=h2:mem:test',
] as $invalidDsn) {
    try {
        new OjpPDO($invalidDsn);
        throw new RuntimeException('Expected an invalid DSN error for ' . $invalidDsn);
    } catch (OjpPDOException) {
    }
}

fwrite(STDOUT, "PASS: PHP PDO L1 API and DSN validation\n");
