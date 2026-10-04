<?php
declare(strict_types=1);

use OpenJProxy\PDO\OjpPDO;
use OpenJProxy\PDO\OjpPDOException;
use OpenJProxy\PDO\OjpPDOStatement;

require dirname(__DIR__) . '/vendor/autoload.php';

if (!is_subclass_of(OjpPDO::class, PDO::class)) {
    throw new RuntimeException('OjpPDO must extend PDO');
}
if (!is_subclass_of(OjpPDOStatement::class, PDOStatement::class)) {
    throw new RuntimeException('OjpPDOStatement must extend PDOStatement');
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
