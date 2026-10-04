<?php
declare(strict_types=1);

namespace OpenJProxy\PDO;

use Com\Openjproxy\Grpc\ConnectionDetails;
use Com\Openjproxy\Grpc\OpResult;
use Com\Openjproxy\Grpc\SessionInfo;
use Com\Openjproxy\Grpc\SessionTerminationStatus;
use Com\Openjproxy\Grpc\StatementRequest;
use Grpc\BaseStub;

final class StatementServiceClient extends BaseStub
{
    public function connect(ConnectionDetails $request): \Grpc\UnaryCall
    {
        return $this->_simpleRequest(
            '/com.openjproxy.grpc.StatementService/connect',
            $request,
            [SessionInfo::class, 'decode']
        );
    }

    public function executeUpdate(StatementRequest $request): \Grpc\UnaryCall
    {
        return $this->_simpleRequest(
            '/com.openjproxy.grpc.StatementService/executeUpdate',
            $request,
            [OpResult::class, 'decode']
        );
    }

    public function executeQuery(StatementRequest $request): \Grpc\ServerStreamingCall
    {
        return $this->_serverStreamRequest(
            '/com.openjproxy.grpc.StatementService/executeQuery',
            $request,
            [OpResult::class, 'decode']
        );
    }

    public function terminateSession(SessionInfo $request): \Grpc\UnaryCall
    {
        return $this->_simpleRequest(
            '/com.openjproxy.grpc.StatementService/terminateSession',
            $request,
            [SessionTerminationStatus::class, 'decode']
        );
    }
}
