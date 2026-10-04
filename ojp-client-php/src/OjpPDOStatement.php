<?php
declare(strict_types=1);

namespace OpenJProxy\PDO;

use PDO;
use PDOStatement;
use Throwable;

final class OjpPDOStatement extends PDOStatement
{
    private array $boundValues = [];
    private array $boundReferences = [];
    private array $boundTypes = [];
    private array $columns = [];
    private array $rows = [];
    private int $rowIndex = 0;
    private int $affectedRows = 0;
    private int $fetchMode = PDO::FETCH_BOTH;
    private ?Throwable $lastError = null;

    public function __construct(
        private OjpPDO $pdo,
        private OjpConnection $connection,
        private string $sql
    ) {
    }

    public function execute(?array $params = null): bool
    {
        try {
            $arguments = $params === null
                ? $this->getBoundValues()
                : self::normalizeExecuteParameters($params);
            $this->columns = [];
            $this->rows = [];
            $this->rowIndex = 0;
            $this->affectedRows = 0;
            $this->lastError = null;

            if (self::isQuery($this->sql)) {
                $result = $this->connection->executeQuery($this->sql, $arguments);
                $this->columns = $result['columns'];
                $this->rows = $result['rows'];
            } else {
                $this->affectedRows = $this->connection->executeUpdate($this->sql, $arguments);
            }
            $this->pdo->clearError();
            return true;
        } catch (Throwable $error) {
            $this->lastError = $error;
            return $this->pdo->handleError($error);
        }
    }

    public function bindValue(string|int $param, mixed $value, int $type = PDO::PARAM_STR): bool
    {
        try {
            $index = self::parameterIndex($param);
            $this->boundValues[$index] = self::normalizeBoundValue($value, $type);
            unset($this->boundReferences[$index], $this->boundTypes[$index]);
            ksort($this->boundValues);
            return true;
        } catch (Throwable $error) {
            $this->lastError = $error;
            return $this->pdo->handleError($error);
        }
    }

    public function bindParam(
        string|int $param,
        mixed &$var,
        int $type = PDO::PARAM_STR,
        int $maxLength = 0,
        mixed $driverOptions = null
    ): bool {
        try {
            $index = self::parameterIndex($param);
            $this->boundReferences[$index] = &$var;
            $this->boundTypes[$index] = $type;
            unset($this->boundValues[$index]);
            return true;
        } catch (Throwable $error) {
            $this->lastError = $error;
            return $this->pdo->handleError($error);
        }
    }

    public function fetch(
        int $mode = PDO::FETCH_DEFAULT,
        int $cursorOrientation = PDO::FETCH_ORI_NEXT,
        int $cursorOffset = 0
    ): mixed {
        if ($cursorOrientation !== PDO::FETCH_ORI_NEXT || $cursorOffset !== 0) {
            throw new OjpPDOException('Only forward-only PDO result fetching is supported in L1');
        }
        if ($this->rowIndex >= count($this->rows)) {
            return false;
        }
        $row = $this->rows[$this->rowIndex++];
        return self::formatRow($row, $this->columns, $mode === PDO::FETCH_DEFAULT ? $this->fetchMode : $mode);
    }

    public function fetchAll(int $mode = PDO::FETCH_DEFAULT, mixed ...$args): array
    {
        $mode = $mode === PDO::FETCH_DEFAULT ? $this->fetchMode : $mode;
        if ($mode === PDO::FETCH_COLUMN) {
            $column = $args[0] ?? 0;
            $result = [];
            while (($row = $this->fetch(PDO::FETCH_NUM)) !== false) {
                $result[] = $row[$column] ?? false;
            }
            return $result;
        }
        $result = [];
        while (($row = $this->fetch($mode)) !== false) {
            $result[] = $row;
        }
        return $result;
    }

    public function fetchColumn(int $column = 0): mixed
    {
        $row = $this->fetch(PDO::FETCH_NUM);
        return $row === false ? false : ($row[$column] ?? false);
    }

    public function setFetchMode(int $mode, mixed ...$args): bool
    {
        if ($args !== [] || !in_array($mode, [PDO::FETCH_ASSOC, PDO::FETCH_NUM, PDO::FETCH_BOTH, PDO::FETCH_OBJ], true)) {
            return $this->pdo->handleError(new OjpPDOException('Unsupported PDO fetch mode'));
        }
        $this->fetchMode = $mode;
        return true;
    }

    public function rowCount(): int
    {
        return $this->columns === [] ? $this->affectedRows : count($this->rows);
    }

    public function columnCount(): int
    {
        return count($this->columns);
    }

    public function getColumnMeta(int $column): array|false
    {
        if (!array_key_exists($column, $this->columns)) {
            return false;
        }
        return [
            'name' => $this->columns[$column],
            'native_type' => null,
            'pdo_type' => PDO::PARAM_STR,
        ];
    }

    public function closeCursor(): bool
    {
        $this->rows = [];
        $this->rowIndex = 0;
        return true;
    }

    public function errorCode(): ?string
    {
        return $this->lastError instanceof OjpPDOException
            ? (string) ($this->lastError->errorInfo[0] ?? 'HY000')
            : ($this->lastError === null ? null : 'HY000');
    }

    public function errorInfo(): array
    {
        if ($this->lastError instanceof OjpPDOException) {
            return $this->lastError->errorInfo;
        }
        return $this->lastError === null
            ? ['00000', null, null]
            : ['HY000', null, $this->lastError->getMessage()];
    }

    private static function parameterIndex(string|int $param): int
    {
        if (!is_int($param) || $param < 1) {
            throw new OjpPDOException('L1 supports positional PDO parameters only; use one-based integer indexes');
        }
        return $param;
    }

    private function getBoundValues(): array
    {
        $values = $this->boundValues;
        foreach ($this->boundReferences as $index => &$value) {
            $values[$index] = self::normalizeBoundValue($value, $this->boundTypes[$index]);
        }
        unset($value);
        ksort($values);
        if ($values !== [] && array_keys($values) !== range(1, count($values))) {
            throw new OjpPDOException('Bound PDO parameters must use contiguous one-based indexes');
        }
        return array_values($values);
    }

    private static function normalizeExecuteParameters(array $params): array
    {
        if ($params === []) {
            return [];
        }
        foreach ($params as $key => $value) {
            if (!is_int($key) || $key < 0) {
                throw new OjpPDOException('L1 supports positional PDO parameters only');
            }
        }
        ksort($params);
        if (array_keys($params) !== range(0, count($params) - 1)) {
            throw new OjpPDOException('PDO execute parameters must use contiguous zero-based indexes');
        }
        return array_values($params);
    }

    private static function normalizeBoundValue(mixed $value, int $type): mixed
    {
        return match ($type) {
            PDO::PARAM_NULL => null,
            PDO::PARAM_BOOL => (bool) $value,
            PDO::PARAM_INT => (int) $value,
            PDO::PARAM_STR => is_resource($value)
                ? throw new OjpPDOException('L1 does not support stream-backed PDO parameters')
                : (string) $value,
            default => throw new OjpPDOException('Unsupported PDO parameter type: ' . $type),
        };
    }

    private static function isQuery(string $sql): bool
    {
        $sql = preg_replace('/\A(?:\s|--[^\r\n]*(?:\r?\n|\z)|\/\*.*?\*\/)*/s', '', $sql) ?? $sql;
        return preg_match('/^(?:SELECT|VALUES|TABLE|WITH)\b/i', $sql) === 1;
    }

    private static function formatRow(array $row, array $columns, int $mode): mixed
    {
        if ($mode === PDO::FETCH_NUM) {
            return $row;
        }
        $associative = [];
        foreach ($columns as $index => $column) {
            $associative[$column] = $row[$index] ?? null;
        }
        return match ($mode) {
            PDO::FETCH_ASSOC => $associative,
            PDO::FETCH_OBJ => (object) $associative,
            PDO::FETCH_BOTH => $associative + $row,
            default => throw new OjpPDOException('Unsupported PDO fetch mode: ' . $mode),
        };
    }
}
