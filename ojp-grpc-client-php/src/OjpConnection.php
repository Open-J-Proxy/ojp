<?php
declare(strict_types=1);

namespace OpenJProxy\PDO;

use Com\Openjproxy\Grpc\ConnectionDetails;
use Com\Openjproxy\Grpc\OpResult;
use Com\Openjproxy\Grpc\ParameterProto;
use Com\Openjproxy\Grpc\ParameterTypeProto;
use Com\Openjproxy\Grpc\ParameterValue;
use Com\Openjproxy\Grpc\ResultType;
use Com\Openjproxy\Grpc\SessionInfo;
use Com\Openjproxy\Grpc\StatementRequest;
use RuntimeException;
use Throwable;

final class OjpConnection
{
    private StatementServiceClient $client;
    private ?SessionInfo $session;
    private bool $closed = false;

    public function __construct(string $endpoint, string $databaseUrl, string $username, string $password)
    {
        $this->client = new StatementServiceClient($endpoint, [
            'credentials' => \Grpc\ChannelCredentials::createInsecure(),
        ]);

        $details = new ConnectionDetails();
        $details->setUrl($databaseUrl);
        $details->setUser($username);
        $details->setPassword($password);
        $details->setClientUUID(self::processUuid());

        try {
            [$session, $status] = $this->client->connect($details)->wait();
            self::checkStatus('connect', $status);
            if (!$session instanceof SessionInfo) {
                throw new RuntimeException('OJP server returned an empty session');
            }
            $this->session = $session;
        } catch (Throwable $error) {
            $this->client->close();
            throw $error;
        }
    }

    public function executeUpdate(string $sql, array $arguments): int
    {
        $result = $this->unaryUpdate($sql, $arguments);
        if ($result->getType() !== ResultType::INTEGER) {
            throw new RuntimeException('OJP server returned an unexpected update result type');
        }
        return $result->getIntValue();
    }

    public function executeQuery(string $sql, array $arguments): array
    {
        $this->assertOpen();
        $request = $this->statementRequest($sql, $arguments);
        $call = $this->client->executeQuery($request);
        $columns = [];
        $rows = [];
        foreach ($call->responses() as $result) {
            if (!$result instanceof OpResult) {
                continue;
            }
            $this->applySession($result->getSession());
            $queryResult = $result->getQueryResult();
            if ($queryResult === null) {
                continue;
            }
            if ($columns === []) {
                foreach ($queryResult->getLabels() as $label) {
                    $columns[] = $label;
                }
            }
            foreach ($queryResult->getRows() as $row) {
                $values = [];
                foreach ($row->getColumns() as $value) {
                    $values[] = self::decodeValue($value);
                }
                $rows[] = $values;
            }
        }
        self::checkStatus('executeQuery', $call->getStatus());
        return ['columns' => $columns, 'rows' => $rows];
    }

    public function terminate(): void
    {
        if ($this->closed) {
            return;
        }
        $this->assertOpen();
        [$response, $status] = $this->client->terminateSession($this->session)->wait();
        self::checkStatus('terminateSession', $status);
        if ($response === null || !$response->getTerminated()) {
            throw new RuntimeException('OJP server did not terminate the session');
        }
        $this->closed = true;
        $this->client->close();
    }

    public function isClosed(): bool
    {
        return $this->closed;
    }

    public function closeTransport(): void
    {
        $this->client->close();
    }

    private function unaryUpdate(string $sql, array $arguments): OpResult
    {
        $this->assertOpen();
        [$result, $status] = $this->client->executeUpdate(
            $this->statementRequest($sql, $arguments)
        )->wait();
        self::checkStatus('executeUpdate', $status);
        if (!$result instanceof OpResult) {
            throw new RuntimeException('OJP server returned an empty update result');
        }
        $this->applySession($result->getSession());
        return $result;
    }

    private function statementRequest(string $sql, array $arguments): StatementRequest
    {
        $request = new StatementRequest();
        $request->setSession($this->session);
        $request->setSql($sql);
        foreach ($arguments as $index => $argument) {
            $request->getParameters()[] = self::encodeParameter($index + 1, $argument);
        }
        return $request;
    }

    private function applySession(?SessionInfo $session): void
    {
        if ($session !== null) {
            $this->session = $session;
        }
    }

    private function assertOpen(): void
    {
        if ($this->closed || $this->session === null) {
            throw new RuntimeException('OJP connection is closed');
        }
    }

    private static function processUuid(): string
    {
        static $uuid;
        if ($uuid === null) {
            $bytes = random_bytes(16);
            $bytes[6] = chr((ord($bytes[6]) & 0x0f) | 0x40);
            $bytes[8] = chr((ord($bytes[8]) & 0x3f) | 0x80);
            $hex = bin2hex($bytes);
            $uuid = sprintf(
                '%s-%s-%s-%s-%s',
                substr($hex, 0, 8),
                substr($hex, 8, 4),
                substr($hex, 12, 4),
                substr($hex, 16, 4),
                substr($hex, 20)
            );
        }
        return $uuid;
    }

    private static function encodeParameter(int $index, mixed $value): ParameterProto
    {
        $parameter = new ParameterProto();
        $parameter->setIndex($index);
        $encoded = new ParameterValue();
        if ($value === null) {
            $parameter->setType(ParameterTypeProto::PT_NULL);
            $encoded->setIsNull(true);
        } elseif (is_bool($value)) {
            $parameter->setType(ParameterTypeProto::PT_BOOLEAN);
            $encoded->setBoolValue($value);
        } elseif (is_int($value)) {
            $parameter->setType(ParameterTypeProto::PT_LONG);
            $encoded->setLongValue($value);
        } elseif (is_float($value)) {
            $parameter->setType(ParameterTypeProto::PT_DOUBLE);
            $encoded->setDoubleValue($value);
        } elseif (is_string($value)) {
            $parameter->setType(ParameterTypeProto::PT_STRING);
            $encoded->setStringValue($value);
        } else {
            throw new RuntimeException('Unsupported PDO parameter type: ' . get_debug_type($value));
        }
        $parameter->getValues()[] = $encoded;
        return $parameter;
    }

    private static function decodeValue(ParameterValue $value): mixed
    {
        return match ($value->getValue()) {
            'bool_value' => $value->getBoolValue(),
            'int_value' => $value->getIntValue(),
            'long_value' => $value->getLongValue(),
            'float_value' => $value->getFloatValue(),
            'double_value' => $value->getDoubleValue(),
            'string_value' => $value->getStringValue(),
            'bytes_value' => $value->getBytesValue(),
            'is_null', '' => null,
            default => throw new RuntimeException('Unsupported OJP result value type: ' . $value->getValue()),
        };
    }

    private static function checkStatus(string $operation, object $status): void
    {
        if ($status->code === 0) {
            return;
        }
        $message = $status->details !== '' ? $status->details : 'gRPC status ' . $status->code;
        $sqlErrorInfo = self::sqlErrorInfo($status->metadata ?? []);
        if ($sqlErrorInfo !== null) {
            throw new OjpPDOException($message, $sqlErrorInfo);
        }
        throw new RuntimeException('OJP ' . $operation . ' failed: ' . $message);
    }

    private static function sqlErrorInfo(array $metadata): ?array
    {
        foreach ($metadata as $key => $values) {
            if (strtolower((string) $key) !== 'com.openjproxy.grpc.sqlerrorresponse-bin') {
                continue;
            }
            foreach ((array) $values as $binaryValue) {
                try {
                    $error = new \Com\Openjproxy\Grpc\SqlErrorResponse();
                    $error->mergeFromString($binaryValue);
                    if ($error->getSqlState() !== '' || $error->getReason() !== '') {
                        return [$error->getSqlState(), $error->getVendorCode(), $error->getReason()];
                    }
                } catch (Throwable) {
                    return null;
                }
            }
        }
        return null;
    }
}
