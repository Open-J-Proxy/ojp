<?php
declare(strict_types=1);

use OpenJProxy\PDO\OjpPDO;
use OpenJProxy\PDO\OjpPDOException;

$enabled = strtolower(trim((string) getenv('OJP_TEST_H2')));
if (in_array($enabled, ['', 'false', '0', 'no'], true)) {
    fwrite(STDOUT, "SKIP: set OJP_TEST_H2=true to run the real-server H2 L1 suite\n");
    exit(0);
}
if (!in_array($enabled, ['true', '1', 'yes'], true)) {
    throw new RuntimeException('OJP_TEST_H2 must be true or false');
}

require dirname(__DIR__) . '/vendor/autoload.php';

$endpoint = trim((string) getenv('OJP_TEST_H2_ADDR'));
if ($endpoint === '') {
    throw new RuntimeException('OJP_TEST_H2_ADDR is required when OJP_TEST_H2=true');
}
$endpointMatch = [];
if (preg_match('/^(.+):([0-9]+)$/D', $endpoint, $endpointMatch) !== 1) {
    throw new RuntimeException('OJP_TEST_H2_ADDR must be a host:port endpoint');
}
[, $host, $port] = $endpointMatch;
if (str_contains($host, ':') && !str_starts_with($host, '[')) {
    $host = '[' . $host . ']';
}
[$databaseUrl, $username, $password] = readH2ConnectionConfig();
$pdo = new OjpPDO(
    'ojp:host=' . $host . ';port=' . $port . ';url=' . $databaseUrl,
    $username,
    $password
);

$table = 'ojp_php_l1_' . bin2hex(random_bytes(6));
try {
    assertSameValue('ojp', $pdo->getAttribute(PDO::ATTR_DRIVER_NAME), 'PDO driver name');
    assertSameValue(1, $pdo->query('SELECT 1')->fetchColumn(), 'readiness query');

    $pdo->exec("CREATE TABLE $table (id INT PRIMARY KEY, name VARCHAR(100) NOT NULL, active BOOLEAN NOT NULL)");

    $insert = $pdo->prepare("INSERT INTO $table (id, name, active) VALUES (?, ?, ?)");
    assertTrue($insert->execute([1, 'before', true]), 'insert execution');
    assertSameValue(1, $insert->rowCount(), 'insert count');
    assertRow($pdo, $table, [1, 'before', true]);

    $update = $pdo->prepare("UPDATE $table SET name = ?, active = ? WHERE id = ?");
    assertTrue($update->execute(['after', false, 1]), 'update execution');
    assertSameValue(1, $update->rowCount(), 'update count');
    assertRow($pdo, $table, [1, 'after', false]);

    assertSqlState(
        static fn() => $pdo->exec("INSERT INTO $table (id, name, active) VALUES (1, 'duplicate', TRUE)"),
        '23505'
    );
    assertSqlState(static fn() => $pdo->exec('THIS IS NOT VALID SQL'), '42001');
    assertSqlState(static fn() => $pdo->query("SELECT * FRM $table"), '42000');

    assertSameValue(1, $pdo->exec("DELETE FROM $table WHERE id = 1"), 'delete count');
    $empty = $pdo->query("SELECT id, name, active FROM $table WHERE id = 1");
    assertSameValue(3, $empty->columnCount(), 'empty-result column count');
    assertSameValue(false, $empty->fetch(), 'empty result');
    assertTrue($empty->closeCursor(), 'close empty result cursor');

    $pdo->exec("DROP TABLE IF EXISTS $table");
    assertTrue($pdo->close(), 'terminate OJP session');
    try {
        $pdo->query('SELECT 1');
        throw new RuntimeException('Expected a closed-connection error');
    } catch (OjpPDOException) {
    }
} finally {
    if (!$pdo->isClosed()) {
        try {
            $pdo->exec("DROP TABLE IF EXISTS $table");
        } finally {
            $pdo->close();
        }
    }
}

fwrite(STDOUT, "PASS: PHP PDO-compatible OJP H2 L1 CRUD and lifecycle\n");

/**
 * @return array{0: string, 1: string, 2: string}
 */
function readH2ConnectionConfig(): array
{
    $path = __DIR__ . '/testdata/h2_l1_connection.csv';
    $file = fopen($path, 'rb');
    if ($file === false) {
        throw new RuntimeException('Cannot open H2 connection CSV: ' . $path);
    }
    try {
        $record = fgetcsv($file, null, ',', '"', '');
        if ($record === false || count($record) !== 3 || trim($record[0]) === '') {
            throw new RuntimeException('Expected CSV fields: JDBC URL, username, password');
        }
        if (fgetcsv($file, null, ',', '"', '') !== false) {
            throw new RuntimeException('Expected exactly one H2 connection record');
        }
        return [trim($record[0]), trim($record[1]), $record[2]];
    } finally {
        fclose($file);
    }
}

function assertRow(OjpPDO $pdo, string $table, array $expected): void
{
    $statement = $pdo->prepare("SELECT id, name, active FROM $table WHERE id = ?");
    assertTrue($statement->execute([1]), 'select execution');
    assertSameValue($expected, $statement->fetch(PDO::FETCH_NUM), 'selected row');
}

function assertSqlState(callable $operation, string $expected): void
{
    try {
        $operation();
    } catch (PDOException $error) {
        $actual = $error instanceof OjpPDOException
            ? $error->errorInfo[0]
            : ($error->errorInfo[0] ?? null);
        assertSameValue($expected, $actual, 'SQLSTATE');
        return;
    }
    throw new RuntimeException('Expected SQLSTATE ' . $expected . ' error');
}

function assertSameValue(mixed $expected, mixed $actual, string $description): void
{
    if ($expected !== $actual) {
        throw new RuntimeException(
            $description . ' mismatch: expected ' . var_export($expected, true)
            . ', got ' . var_export($actual, true)
        );
    }
}

function assertTrue(bool $actual, string $description): void
{
    assertSameValue(true, $actual, $description);
}
