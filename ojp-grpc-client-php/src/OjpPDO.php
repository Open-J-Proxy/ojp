<?php
declare(strict_types=1);

namespace OpenJProxy\PDO;

use PDO;
use PDOException;
use Throwable;

final class OjpPDO extends PDO
{
    private OjpConnection $connection;
    private string $endpoint;
    private int $errorMode = PDO::ERRMODE_EXCEPTION;
    private array $lastError = ['00000', null, null];

    public function __construct(
        string $dsn,
        ?string $username = null,
        ?string $password = null,
        ?array $options = null
    ) {
        [$this->endpoint, $databaseUrl] = self::parseDsn($dsn);
        foreach ($options ?? [] as $attribute => $value) {
            if ($attribute === PDO::ATTR_ERRMODE) {
                if (!in_array($value, [PDO::ERRMODE_SILENT, PDO::ERRMODE_WARNING, PDO::ERRMODE_EXCEPTION], true)) {
                    throw new OjpPDOException('Unsupported PDO error mode');
                }
                $this->errorMode = $value;
                continue;
            }
            throw new OjpPDOException('Unsupported PDO option: ' . $attribute);
        }
        $this->connection = new OjpConnection(
            $this->endpoint,
            $databaseUrl,
            $username ?? '',
            $password ?? ''
        );
    }

    public function prepare(string $query, array $options = []): \PDOStatement|false
    {
        if ($options !== []) {
            return $this->handleError(new OjpPDOException('PDO statement options are not supported in L1'));
        }
        if (trim($query) === '') {
            return $this->handleError(new OjpPDOException('SQL query must not be empty'));
        }
        return new OjpPDOStatement($this, $this->connection, $query);
    }

    public function query(string $query, ?int $fetchMode = null, mixed ...$fetchModeArgs): \PDOStatement|false
    {
        if ($fetchModeArgs !== []) {
            return $this->handleError(new OjpPDOException('Additional PDO query fetch arguments are not supported'));
        }
        $statement = $this->prepare($query);
        if (!$statement instanceof OjpPDOStatement) {
            return false;
        }
        if ($fetchMode !== null && !$statement->setFetchMode($fetchMode)) {
            return false;
        }
        return $statement->execute() ? $statement : false;
    }

    public function exec(string $statement): int|false
    {
        try {
            $count = $this->connection->executeUpdate($statement, []);
            $this->clearError();
            return $count;
        } catch (Throwable $error) {
            return $this->handleError($error);
        }
    }

    public function setAttribute(int $attribute, mixed $value): bool
    {
        if ($attribute !== PDO::ATTR_ERRMODE
            || !in_array($value, [PDO::ERRMODE_SILENT, PDO::ERRMODE_WARNING, PDO::ERRMODE_EXCEPTION], true)) {
            return false;
        }
        $this->errorMode = $value;
        return true;
    }

    public function getAttribute(int $attribute): mixed
    {
        return match ($attribute) {
            PDO::ATTR_DRIVER_NAME => 'ojp',
            PDO::ATTR_ERRMODE => $this->errorMode,
            PDO::ATTR_CONNECTION_STATUS => 'Connected to OJP server ' . $this->endpoint,
            default => false,
        };
    }

    public function lastInsertId(?string $name = null): string|false
    {
        return false;
    }

    public function errorCode(): ?string
    {
        return $this->lastError[0] === '00000' ? null : (string) $this->lastError[0];
    }

    public function errorInfo(): array
    {
        return $this->lastError;
    }

    public function close(): bool
    {
        if ($this->connection->isClosed()) {
            return true;
        }
        try {
            $this->connection->terminate();
            $this->clearError();
            return true;
        } catch (Throwable $error) {
            $this->recordError($error);
            return $this->handleError($error);
        }
    }

    public function isClosed(): bool
    {
        return $this->connection->isClosed();
    }

    public function __destruct()
    {
        if (isset($this->connection) && !$this->connection->isClosed()) {
            try {
                $this->connection->terminate();
            } catch (Throwable) {
                // Destructors must not throw while PHP is shutting down.
            } finally {
                try {
                    $this->connection->closeTransport();
                } catch (Throwable) {
                    // Destructors must not throw while PHP is shutting down.
                }
            }
        }
    }

    public function errorMode(): int
    {
        return $this->errorMode;
    }

    public function recordError(Throwable $error): void
    {
        if ($error instanceof OjpPDOException) {
            $this->lastError = $error->errorInfo;
            return;
        }
        $this->lastError = ['HY000', null, $error->getMessage()];
    }

    public function clearError(): void
    {
        $this->lastError = ['00000', null, null];
    }

    public function handleError(Throwable $error): false
    {
        $this->recordError($error);
        if ($this->errorMode === PDO::ERRMODE_EXCEPTION) {
            if ($error instanceof PDOException) {
                throw $error;
            }
            throw new OjpPDOException($error->getMessage(), $this->lastError, $error);
        }
        if ($this->errorMode === PDO::ERRMODE_WARNING) {
            trigger_error($error->getMessage(), E_USER_WARNING);
        }
        return false;
    }

    /**
     * @return array{0: string, 1: string}
     */
    private static function parseDsn(string $dsn): array
    {
        if (!str_starts_with($dsn, 'ojp:')) {
            throw new OjpPDOException('OJP PDO DSN must start with "ojp:"');
        }
        $parts = explode(';', substr($dsn, 4), 3);
        if (count($parts) !== 3
            || !str_starts_with($parts[0], 'host=')
            || !str_starts_with($parts[1], 'port=')
            || !str_starts_with($parts[2], 'url=')) {
            throw new OjpPDOException(
                'OJP PDO DSN format is "ojp:host=<host>;port=<port>;url=<backend JDBC URL>"'
            );
        }
        $host = substr($parts[0], 5);
        $port = substr($parts[1], 5);
        $databaseUrl = substr($parts[2], 4);
        if ($host === '' || filter_var($port, FILTER_VALIDATE_INT, ['options' => ['min_range' => 1, 'max_range' => 65535]]) === false) {
            throw new OjpPDOException('OJP PDO DSN has an invalid host or port');
        }
        if (str_contains($host, ':') && !str_starts_with($host, '[')) {
            $host = '[' . $host . ']';
        }
        if ($databaseUrl === '' || !str_starts_with($databaseUrl, 'jdbc:')) {
            throw new OjpPDOException('OJP PDO DSN requires a backend JDBC URL');
        }
        return [$host . ':' . $port, $databaseUrl];
    }
}
